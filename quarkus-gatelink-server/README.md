# `quarkus-gatelink-server`

This is the single Java 21 / Quarkus backend project. It contains production code and all Java tests.

Start with:

- [`../docs/operator-guide.md`](../docs/operator-guide.md) for the complete user → browser → GateLink → PostgreSQL → Push Service lifecycle;
- [`../docs/docker-deployment.md`](../docs/docker-deployment.md) for the Docker/Compose deployment;
- [`../docs/webpush-java.md`](../docs/webpush-java.md) for the selected Java Web Push library.

## Server responsibilities

GateLink server is responsible for:

- exposing the REST API;
- owning the application-server VAPID key pair;
- validating browser PushSubscription input;
- persisting subscriptions in PostgreSQL;
- loading subscriptions for notification fan-out;
- delegating RFC 8291 / RFC 8188 payload encryption to `nl.martijndwars:web-push`;
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

## Docker runtime

The production runtime image is based on:

```text
eclipse-temurin:21-jre-noble
```

No Red Hat UBI or Podman-specific base image is used.

The Maven project is packaged as a Quarkus uber-JAR with final artifact name `app`. The Docker build copies it into the runtime image as:

```text
/opt/app/app.jar
```

The Java process starts as:

```text
java -jar /opt/app/app.jar
```

The runtime process is non-root:

```text
UID 10001
GID 10001
```

`JAVA_TOOL_OPTIONS` sets:

```text
-Djava.io.tmpdir=/opt/app/tmp
-Djava.util.logging.manager=org.jboss.logmanager.LogManager
```

Runtime paths:

```text
/opt/app/app.jar                         image content
/opt/app/config/application.properties  read-only bind mount
/opt/app/logs/                           writable bind mount
/opt/app/tmp/                            writable bind mount
```

The Dockerfile is at:

```text
quarkus-gatelink-server/Dockerfile
```

Build only the backend image from the repository root:

```bash
docker compose build backend
```

Run/update only the backend while preserving PostgreSQL and frontend containers:

```bash
docker compose up -d --no-deps backend
```

See [`../docs/docker-deployment.md`](../docs/docker-deployment.md) for host ownership/permissions and the complete procedure.

## External Quarkus configuration

The production Compose stack bind-mounts:

```text
./quarkus-gatelink-server/config/application.properties
```

as:

```text
/opt/app/config/application.properties
```

in read-only mode.

Because the container uses `WORKDIR /opt/app`, this is the standard Quarkus external configuration location:

```text
$PWD/config/application.properties
```

The runtime configuration can therefore be changed without rebuilding the application image. Restart the backend after changing the file:

```bash
docker compose restart backend
```

Secrets are not written into this properties file. Compose injects them through environment variables.

## PostgreSQL connection in Docker

Inside Compose, GateLink reaches PostgreSQL using the service DNS name:

```text
jdbc:postgresql://postgres:5432/${DB_NAME}
```

It must **not** use `localhost` for the database from inside the backend container.

The relevant environment variables are:

```text
DB_NAME
DB_USER
DB_PASSWORD
```

which Compose derives from:

```text
POSTGRES_DB
POSTGRES_USER
POSTGRES_PASSWORD
```

PostgreSQL is not exposed on an application host port in the production-style stack.

## Startup: what the server does

1. Docker Compose waits until PostgreSQL passes `pg_isready`.
2. The backend container starts.
3. Quarkus creates the PostgreSQL datasource.
4. Flyway validates and applies pending migrations.
5. Hibernate validates the Java mappings against the schema.
6. `InMemoryKeyStore` initializes the VAPID identity.
7. If both VAPID keys are configured, GateLink loads the stable P-256 pair.
8. If only one VAPID key is configured, startup fails.
9. If neither is configured, GateLink generates a temporary pair and logs a warning.
10. Quarkus initializes OIDC/security, validation, metrics and tracing.
11. GateLink exposes the HTTP server on `0.0.0.0:8080`.
12. Compose waits for `GET /q/health/ready` before starting the frontend.

A production deployment must always use a stable VAPID pair. PostgreSQL persistence alone is not sufficient if the application-server identity changes after restart.

## `GET /keys/public`: server-side sequence

1. Browser calls Nginx at `/api/keys/public` in the Dockerized deployment.
2. Nginx forwards it to GateLink as `GET /keys/public`.
3. `KeysResource` obtains the current `ECKeys` from `InMemoryKeyStore`.
4. GateLink serializes only the public key.
5. The public key is returned as unpadded Base64URL text.
6. The VAPID private key never leaves the server.

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

PostgreSQL answers **who GateLink can send to**. It is not a Web Push message queue, notification history, delivery acknowledgement database or VAPID private-key store.

The Docker named volume `postgres_data` makes this state survive PostgreSQL container replacement.

Inspect subscriptions without publishing port 5432:

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

Through the production Nginx frontend these are exposed with `/api` prefixed, e.g. `/api/subscriptions`.

The browser-facing endpoints remain public because they are part of subscription lifecycle management. Administrative inventory and fan-out operations require OIDC when OIDC is enabled/configured.

## OIDC in the three-container stack

The requested Compose stack contains:

```text
frontend + backend + postgres
```

and intentionally does not add a fourth identity-provider container.

`.env.example` therefore leaves OIDC disabled so the stack can boot by itself. This is appropriate only for exercising public browser subscription flows.

For production administrative endpoints configure an external OIDC provider:

```text
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://id.example.com/realms/gatelink
OIDC_CLIENT_ID=gatelink-server
```

Administrative tokens must carry role:

```text
gatelink-admin
```

## Unsubscribe handling

`DELETE /subscriptions/{endpoint}` receives a Base64URL-encoded subscription endpoint.

1. GateLink validates that the path value is non-blank, bounded and Base64URL-shaped.
2. GateLink decodes it to the original endpoint string.
3. `SubscriptionsStore.remove(endpoint)` deletes that primary-key row.
4. This removes GateLink's server-side record; frontend code separately manages browser Push API subscription state.

`DELETE /subscriptions` is a distinct admin-only operation that deletes every database row.

## Selected Web Push library

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

See [`../docs/webpush-java.md`](../docs/webpush-java.md).

## VAPID configuration

Production Compose configuration comes from `.env` or an equivalent secret/environment source:

```text
WEBPUSH_VAPID_PUBLIC_KEY=...
WEBPUSH_VAPID_PRIVATE_KEY=...
WEBPUSH_VAPID_SUBJECT=mailto:admin@example.com
```

Both public and private key must be provided together.

The VAPID pair identifies the application server and signs RFC 8292 authentication. Payload encryption uses separate ephemeral key agreement inside the Web Push encryption implementation.

## Logging

The external container configuration keeps console logging enabled, so:

```bash
docker compose logs -f backend
```

shows application output.

GateLink also writes persistent file logs to:

```text
/opt/app/logs/application.log
```

which is bound to:

```text
quarkus-gatelink-server/runtime/logs/application.log
```

Rotation is configured for:

```text
50 MB maximum file size
10 backup files
compressed date-suffixed rotated files
```

## Temporary files

`java.io.tmpdir` is:

```text
/opt/app/tmp
```

bound to:

```text
quarkus-gatelink-server/runtime/tmp
```

The host directories `runtime/logs` and `runtime/tmp` must be writable by `10001:10001`.

## Observability

### Health

```text
GET /q/health
GET /q/health/ready
```

`quarkus-smallrye-health` is already included.

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

OpenTelemetry is enabled together with JDBC telemetry. OTLP export in the external container configuration is opt-in through `OTEL_EXPORT_ENABLED`.

### OpenAPI

```text
GET /q/openapi
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

## Build and test

Backend tests use PostgreSQL on host port 5432 in the test profile:

```bash
docker compose up -d postgres
mvn clean verify
```

From the repository root, CI additionally validates the Compose model and builds both production application images.

## Local Quarkus development

```bash
cd quarkus-gatelink-server
mvn quarkus:dev
```

Development remains separate from the production Compose runtime and uses the source-tree `src/main/resources/application.properties` profile settings.

## Related documentation

- [`../docs/docker-deployment.md`](../docs/docker-deployment.md) — full Docker/Compose deployment and update procedure.
- [`../docs/operator-guide.md`](../docs/operator-guide.md) — end-to-end runtime and troubleshooting semantics.
- [`../docs/webpush-java.md`](../docs/webpush-java.md) — selected Java Web Push library.
- [`../docs/integration-examples.md`](../docs/integration-examples.md) — Java and TypeScript API examples.
- [`../README.md`](../README.md) — repository overview.
- [`../quarkus-gatelink-webpush-ui/README.md`](../quarkus-gatelink-webpush-ui/README.md) — Angular/Nginx browser-side flow.
