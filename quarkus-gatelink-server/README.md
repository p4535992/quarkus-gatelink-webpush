# `quarkus-gatelink-server`

This is the single Java 21 / Quarkus backend project. It contains production code and all Java tests.

> For the complete user → browser → GateLink → PostgreSQL → Push Service → Service Worker lifecycle, see [`../docs/operator-guide.md`](../docs/operator-guide.md).

## Server responsibilities

GateLink server is responsible for:

- exposing the REST API;
- owning the application-server VAPID key pair;
- validating browser PushSubscription input;
- persisting subscriptions in PostgreSQL;
- loading subscriptions for notification fan-out;
- delegating RFC 8291 / RFC 8188 payload encryption to the Java `nl.martijndwars:web-push` library;
- creating RFC 8292 VAPID authentication;
- sending RFC 8030 requests to browser Push Service endpoints;
- enforcing OIDC/RBAC and notification rate limits;
- exposing health, metrics, OpenAPI, logs and tracing.

## Internal runtime path

```text
HTTP request
    |
    v
Quarkus REST resource
    |
    +-- Jakarta Validation
    +-- OIDC / @RolesAllowed where required
    +-- rate limit where required
    |
    v
application boundary / control
    |
    +----------------------------+
    |                            |
    v                            v
SubscriptionsStore         EncryptionService
    |                            |
    | Panache / JPA              | web-push Java library
    v                            v
PostgreSQL                  AES128GCM body
                                 |
                                 v
                           VAPID JWT
                                 |
                                 v
                           JDK HttpClient
                                 |
                                 v
                           Browser Push Service
```

## Startup: what the server does

1. Quarkus creates the PostgreSQL datasource.
2. Flyway validates and applies pending migrations.
3. Hibernate validates the Java mappings against the schema.
4. `InMemoryKeyStore` initializes the VAPID identity.
5. If both VAPID keys are configured, GateLink loads the stable P-256 pair.
6. If only one key is configured, startup fails.
7. If neither key is configured, GateLink generates a temporary pair and logs a warning.
8. Quarkus initializes OIDC security.
9. Hibernate Validator becomes active on REST parameters/entities.
10. Micrometer and OpenTelemetry instrumentation are initialized.
11. GateLink starts accepting HTTP requests.

A production deployment should always use a stable VAPID pair. PostgreSQL persistence alone is not enough if the application-server identity changes after restart.

## `GET /keys/public`: server-side sequence

1. Browser calls `GET /keys/public`.
2. `KeysResource` obtains the current `ECKeys` from `InMemoryKeyStore`.
3. GateLink serializes only the public key.
4. The public key is returned as unpadded Base64URL text.
5. The VAPID private key never leaves the server.

## `POST /subscriptions`: server-side sequence

```text
POST /subscriptions
       |
       v
SubscriptionsResource
       |
       | @Valid
       v
PushSubscription validation
       |
       +-- endpoint is absolute HTTPS
       +-- p256dh is canonical Base64URL
       +-- p256dh decodes to 65-byte uncompressed P-256 key
       +-- auth is canonical Base64URL
       `-- auth decodes to exactly 16 bytes
       |
       v
SubscriptionsStore.addSubscription(...)
       |
       | native PostgreSQL upsert
       v
push_subscriptions
```

Exact sequence:

1. Quarkus deserializes the JSON body into `PushSubscription`.
2. Jakarta Validation runs before persistence.
3. Invalid input is rejected with HTTP `400`.
4. Valid input reaches `SubscriptionsStore.addSubscription`.
5. The JPA persistence context is cleared.
6. GateLink performs:

```sql
INSERT INTO push_subscriptions (endpoint, p256dh, auth)
VALUES (:endpoint, :p256dh, :auth)
ON CONFLICT (endpoint) DO UPDATE SET
    p256dh = EXCLUDED.p256dh,
    auth = EXCLUDED.auth;
```

7. The endpoint is the primary key, so re-registration refreshes the keys without duplicating the subscription.
8. The persistence context is cleared again.
9. The REST call returns HTTP `204`.

## `POST /notifications`: server-side sequence

This is an administrative endpoint.

```text
POST /notifications
       |
       +-- OIDC Bearer authentication
       +-- gatelink-admin role
       +-- 20 requests/minute rate limit
       +-- non-blank payload
       +-- max 3993 UTF-8 bytes
       |
       v
NotificationsSender
       |
       +--> load VAPID keys
       +--> PostgreSQL: load subscriptions
       |
       `--> for each subscription
              |
              +--> EncryptionService
              |      |
              |      `--> web-push / Encoding.AES128GCM
              |
              +--> create VAPID JWT
              |
              +--> JDK HttpClient POST to Push Service
              |
              `--> record HTTP status metric
```

Exact sequence:

1. Quarkus authenticates the OIDC Bearer token.
2. `@RolesAllowed("gatelink-admin")` checks authorization.
3. SmallRye Fault Tolerance enforces 20 calls/minute.
4. Jakarta Validation rejects blank messages.
5. GateLink verifies the UTF-8 payload is at most 3993 octets.
6. `webpush.messages.forwarded` is incremented.
7. `NotificationsSender` obtains the GateLink VAPID key pair.
8. `SubscriptionsStore.all()` clears the persistence context and reads a fresh list from PostgreSQL.
9. GateLink creates one `Notification` per stored subscription.
10. `EncryptionService` validates the subscription auth material again.
11. GateLink creates the library `nl.martijndwars.webpush.Notification`.
12. `AbstractPushService.encrypt(...)` is called with `Encoding.AES128GCM` explicitly.
13. The library returns the RFC 8291 / RFC 8188 encrypted body.
14. GateLink extracts the origin of the subscription endpoint for the VAPID audience.
15. GateLink creates an ES256 VAPID JWT using the stable private key.
16. `webpush.push.attempts{push_service="..."}` is incremented.
17. `PushServiceClient` sends the JDK HTTP request to the subscription endpoint.
18. The request contains only the modern delivery shape:

```text
TTL: 2419200
Content-Encoding: aes128gcm
Authorization: vapid t=<JWT>, k=<public-key>
Content-Type: application/octet-stream
```

19. The Push Service response status is recorded as `webpush.responses{status="..."}`.
20. `2xx` is considered successful by the internal client.
21. Non-`2xx` statuses are currently recorded but not returned as a per-subscription delivery report.
22. If all sends complete without an exception, the REST endpoint returns HTTP `204`.

## PostgreSQL persistence

GateLink stores browser subscriptions in PostgreSQL 18 using Hibernate ORM with Panache and Flyway.

```text
push_subscriptions
+----------+------+
| endpoint | TEXT | primary key
| p256dh   | TEXT | not null
| auth     | TEXT | not null
+----------+------+
```

The VAPID private key is not stored in this table.

### Local PostgreSQL

From repository root:

```bash
docker compose up -d postgres
```

Connection:

```text
host:     localhost
port:     5432
database: gatelink
username: gatelink
password: gatelink
```

Inspect subscriptions:

```bash
docker compose exec postgres psql -U gatelink -d gatelink
```

```sql
SELECT endpoint, p256dh, auth FROM push_subscriptions;
```

Flyway owns schema changes under:

```text
src/main/resources/db/migration/
```

Never rewrite a migration already applied in a deployed environment; add a new versioned migration.

## API access model

| Method | Path | Access |
| --- | --- | --- |
| `GET` | `/keys/public` | public |
| `POST` | `/subscriptions` | public + strict validation |
| `DELETE` | `/subscriptions/{endpoint}` | public + path validation |
| `GET` | `/subscriptions` | `gatelink-admin` |
| `DELETE` | `/subscriptions` | `gatelink-admin` |
| `POST` | `/notifications` | `gatelink-admin` + 20/minute rate limit |

The browser-facing endpoints remain public because they are part of subscription lifecycle management. Administrative inventory and fan-out operations require OIDC.

## Unsubscribe handling

`DELETE /subscriptions/{endpoint}` receives a Base64URL-encoded subscription endpoint.

1. GateLink validates that the path value is non-blank, bounded and Base64URL-shaped.
2. GateLink decodes it to the original endpoint string.
3. `SubscriptionsStore.remove(endpoint)` deletes that primary-key row.
4. This removes GateLink's server-side record; frontend code is separately responsible for the browser Push API subscription lifecycle.

`DELETE /subscriptions` is a distinct admin-only operation that deletes every database row.

## Web Push implementation

GateLink uses:

```xml
<groupId>nl.martijndwars</groupId>
<artifactId>web-push</artifactId>
<version>5.1.2</version>
```

The library is used for payload cryptography only. GateLink retains outbound HTTP construction so the modern wire format is explicit and testable.

```text
PushSubscription
(endpoint + p256dh + auth)
        |
        v
EncryptionService
        |
        | nl.martijndwars:web-push
        | Encoding.AES128GCM
        v
RFC 8291 / RFC 8188 body
        |
        +-- VAPID JWT generated by GateLink
        |
        v
PushServiceClient
        |
        v
Browser Push Service
```

There is no GateLink path using `Encoding.AESGCM`, and GateLink does not emit legacy `Encryption` / `Crypto-Key` delivery headers.

## VAPID configuration

Production:

```bash
export WEBPUSH_VAPID_PUBLIC_KEY='...'
export WEBPUSH_VAPID_PRIVATE_KEY='...'
export WEBPUSH_VAPID_SUBJECT='mailto:admin@example.com'
```

Both public and private key must be provided together.

The VAPID pair identifies the application server and signs RFC 8292 authentication. Payload encryption uses separate ephemeral key agreement inside the Web Push encryption implementation.

## OIDC configuration

Production:

```bash
export GATELINK_OIDC_AUTH_SERVER_URL='https://id.example.com/realms/gatelink'
export GATELINK_OIDC_CLIENT_ID='gatelink-server'
```

Development uses Quarkus OIDC Dev Services when Docker is available. `alice` is assigned `gatelink-admin`.

Tests disable the external OIDC provider and use `quarkus-test-security` identities.

## Datasource configuration

Production:

```bash
export GATELINK_DB_URL='jdbc:postgresql://postgres.example.internal:5432/gatelink'
export GATELINK_DB_USER='gatelink'
export GATELINK_DB_PASSWORD='replace-me'
```

Do not reuse development credentials in production.

## CORS

Production origins must be explicit:

```bash
export QUARKUS_HTTP_CORS_ORIGINS='https://push.example.com'
```

## Observability

### Health

```text
GET /q/health
```

### Metrics

```text
GET /q/metrics
```

GateLink-specific counters:

```text
webpush.messages.forwarded
webpush.push.attempts{push_service="..."}
webpush.responses{status="..."}
```

### Tracing

OpenTelemetry is enabled together with JDBC telemetry. Dev/test do not export OTLP unless explicitly configured.

### Logging

Production console logs are structured JSON. Development and tests use human-readable logs.

### OpenAPI

```text
GET /q/openapi
GET /q/swagger-ui    # dev/test
```

## Current delivery semantics

Operators should know these behaviors:

- no automatic retry of Push Service requests;
- no automatic deletion of stored subscriptions after Push Service `404` / `410`;
- no per-browser delivery report in the admin REST response;
- a Push Service non-`2xx` is recorded in metrics but does not by itself fail the fan-out REST response;
- a network I/O exception can terminate the current synchronous fan-out;
- Push Service acceptance is not a user acknowledgement.

See [`../docs/operator-guide.md`](../docs/operator-guide.md) for the detailed operational interpretation.

## Source map

```text
src/main/java/com/quarkus/gatelink/
├── keymanagement/     # stable VAPID application-server identity
├── subscriptions/     # REST + PostgreSQL persistence
├── notifications/     # fan-out + Push Service HTTP
├── encryption/        # adapter to web-push AES128GCM
├── signature/         # RFC 8292 VAPID JWT
├── health/            # MicroProfile Health
├── bytes/             # key/byte helpers
└── log/               # logging helpers
```

HTTP/system tests are inside the same Maven project:

```text
src/test/java/com/quarkus/gatelink/system/
```

They use MicroProfile REST Client and raw HTTP where needed against the real Quarkus test endpoint.

## Build and test

Requirements:

- Java 21
- Maven 3.9+
- PostgreSQL on `localhost:5432` for the configured test profile

```bash
docker compose up -d postgres
cd quarkus-gatelink-server
mvn clean verify
```

## Development mode

```bash
cd quarkus-gatelink-server
mvn quarkus:dev
```

Default URL:

```text
http://localhost:8080
```

## Docker image

```bash
mvn clean package
docker build -f src/main/docker/Dockerfile.jvm -t quarkus-gatelink-server:latest .
```

## Related documentation

- [`../docs/operator-guide.md`](../docs/operator-guide.md) — complete operational flow and troubleshooting semantics.
- [`../docs/integration-examples.md`](../docs/integration-examples.md) — Java and TypeScript client examples.
- [`../README.md`](../README.md) — repository overview and quick start.
- [`../quarkus-gatelink-webpush-ui/README.md`](../quarkus-gatelink-webpush-ui/README.md) — browser-side flow.
