# `quarkus-gatelink-server`

This is the **single Java/Quarkus project** in the repository. It contains both the production GateLink backend and all Java tests, including the former external client/system-test logic.

The server is responsible for:

- exposing the REST API used by browser clients;
- owning the VAPID application-server key pair;
- persisting browser Push Subscriptions in PostgreSQL;
- delegating RFC 8291 / RFC 8188 `aes128gcm` payload encryption to `nl.martijndwars:web-push`;
- authenticating Web Push requests with RFC 8292 VAPID;
- forwarding notifications to browser Push Service endpoints;
- exposing health, metrics and OpenAPI endpoints.

For the complete end-to-end architecture, see the repository [`README.md`](../README.md).

## Runtime architecture

```text
+-------------------------------+
| Browser / Angular / other UI  |
+---------------+---------------+
                |
                | REST
                | GET /keys/public
                | POST /subscriptions
                v
+---------------+---------------+
| quarkus-gatelink-server       |
|                               |
| Quarkus REST                  |
| RFC 8291 aes128gcm            |
| RFC 8292 VAPID                |
+-------+---------------+-------+
        |               |
        | JDBC/JPA      | RFC 8030 Web Push HTTP
        v               v
+-------+------+   +----+------------------+
| PostgreSQL   |   | Browser Push Service  |
| subscriptions|   | FCM / Mozilla / etc. |
+--------------+   +-----------+-----------+
                               |
                               | push delivery
                               v
                    +----------+-----------+
                    | Browser Service      |
                    | Worker               |
                    +----------------------+
```

## One project for server and HTTP client tests

The old standalone `quarkus-gatelink-webpush-client` module has been removed. Its useful role is preserved under:

```text
src/test/java/com/quarkus/gatelink/system/
├── GateLinkApi.java
├── SystemTestClient.java
├── HealthResourceSystemTest.java
├── KeysResourceSystemTest.java
├── NotificationsResourceSystemTest.java
└── SubscriptionsResourceSystemTest.java
```

The tests use **MicroProfile REST Client** against the HTTP server started by `@QuarkusTest`:

```text
mvn clean verify
      |
      v
@QuarkusTest starts GateLink
      |
      | @TestHTTPResource
      v
real test URI (normally port 8081)
      |
      | MicroProfile REST Client
      v
GateLink REST resources
      |
      +---- PostgreSQL
```

This means there is no second Maven project, no `st.sh`, and no requirement to manually start GateLink before running its Java system tests.

Official Quarkus testing documentation:

- https://quarkus.io/guides/getting-started-testing
- https://quarkus.io/guides/rest-client

## REST technology

The production server uses **Quarkus REST**:

```xml
<artifactId>quarkus-rest</artifactId>
<artifactId>quarkus-rest-jsonb</artifactId>
```

Resources use `jakarta.ws.rs.*`. This is not RESTEasy Classic.

The HTTP test contract uses `quarkus-rest-client-jsonb` in **test scope**, so the MicroProfile REST Client implementation is not an extra production service.

## Persistence

Push subscriptions are stored with:

- PostgreSQL 18;
- Hibernate ORM with Panache;
- Flyway migrations;
- Agroal JDBC pooling through Quarkus PostgreSQL JDBC.

```text
Browser
   |
   | POST /subscriptions
   v
SubscriptionsResource
   |
   v
SubscriptionsStore
   |
   | Hibernate ORM / Panache
   v
PostgreSQL
```

A Push Subscription is durable application state. Without PostgreSQL, a restart would lose every registered browser.

Official documentation:

- Quarkus datasource: https://quarkus.io/guides/datasource
- Hibernate ORM with Panache: https://quarkus.io/guides/hibernate-orm-panache
- Flyway: https://quarkus.io/guides/flyway

## Local PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

Connection settings:

```text
host:     localhost
port:     5432
database: gatelink
username: gatelink
password: gatelink
```

Open `psql`:

```bash
docker compose exec postgres psql -U gatelink -d gatelink
```

Inspect subscriptions:

```sql
SELECT endpoint FROM push_subscriptions;
```

Stop while preserving data:

```bash
docker compose down
```

Delete the local database volume:

```bash
docker compose down -v
```

## Database migrations

Flyway owns the schema under:

```text
src/main/resources/db/migration/
```

The initial migration creates:

```text
push_subscriptions
+----------+------+
| endpoint | TEXT | primary key
| p256dh   | TEXT | not null
| auth     | TEXT | not null
+----------+------+
```

Do not edit an already-applied migration in a deployed environment; add a new versioned migration.

## API endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/keys/public` | public VAPID application-server key |
| `POST` | `/subscriptions` | creates or updates a browser Push Subscription |
| `GET` | `/subscriptions` | **admin**: lists known subscription endpoints |
| `DELETE` | `/subscriptions/{endpoint}` | removes one Base64URL-encoded endpoint |
| `DELETE` | `/subscriptions` | **admin**: removes all subscriptions |
| `POST` | `/notifications` | **admin**: sends notification; limited to 20 requests/minute |
| `GET` | `/q/health` | MicroProfile Health |
| `GET` | `/q/metrics` | Prometheus/Micrometer metrics |
| `GET` | `/q/openapi` | OpenAPI document |
| `GET` | `/q/swagger-ui` | Swagger UI in dev/test |

## Web Push protocol

GateLink implements only the current protocol; there is no obsolete `aesgcm` fallback. Payload encryption is delegated to `nl.martijndwars:web-push` 5.1.2 with `Encoding.AES128GCM` selected explicitly. GateLink deliberately does not use the library's legacy HTTP sender paths.

```text
PushSubscription
(endpoint, p256dh, auth)
        |
        | P-256 ECDH
        v
shared secret
        |
        | HKDF-SHA256 + auth secret
        v
RFC 8291 IKM
        |
        | random 16-byte salt
        | RFC 8188 CEK + nonce derivation
        v
AES-128-GCM
        |
        | salt + rs + ephemeral key in body
        v
Content-Encoding: aes128gcm
        |
        | Authorization: vapid t=<JWT>, k=<VAPID public key>
        v
Push Service
```

Implementation constraints include a 4096-octet record size, 65-octet uncompressed P-256 keys, a 16-octet subscription auth secret, a fresh ephemeral ECDH key per notification and a maximum plaintext size chosen so the complete encrypted body fits within 4096 octets.

The library encryption path is checked against the RFC 8188 `aes128gcm` interoperability vector. GateLink separately tests the HTTP request shape and guarantees that obsolete `Encryption` and `Crypto-Key` delivery headers are absent.

Standards:

- RFC 8030: https://www.rfc-editor.org/rfc/rfc8030.html
- RFC 8188: https://www.rfc-editor.org/rfc/rfc8188.html
- RFC 8291: https://www.rfc-editor.org/rfc/rfc8291.html
- RFC 8292: https://www.rfc-editor.org/rfc/rfc8292.html
- W3C Push API: https://www.w3.org/TR/push-api/

## Source map

```text
src/main/java/com/quarkus/gatelink/
├── keymanagement/     # VAPID keys
├── subscriptions/     # REST + PostgreSQL persistence
├── notifications/     # fan-out + Push Service HTTP
├── encryption/        # adapter to web-push AES128GCM
├── signature/         # RFC 8292 VAPID
├── health/            # MicroProfile Health
├── bytes/             # byte helpers
└── log/               # logging

src/test/java/com/quarkus/gatelink/
├── system/            # HTTP/system tests via MicroProfile REST Client
└── ...                # unit/component/persistence tests
```

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

A single Maven build now runs the complete Java test suite.

## Development mode

```bash
cd quarkus-gatelink-server
mvn quarkus:dev
```

Default URL:

```text
http://localhost:8080
```

In dev mode Quarkus starts Keycloak Dev Services automatically when Docker is available. Use `/q/dev-ui` and the built-in `alice` identity, configured with role `gatelink-admin`, to exercise secured administration endpoints.

## Production database configuration

```bash
export GATELINK_DB_URL='jdbc:postgresql://postgres.example.internal:5432/gatelink'
export GATELINK_DB_USER='gatelink'
export GATELINK_DB_PASSWORD='replace-me'
export GATELINK_OIDC_AUTH_SERVER_URL='https://id.example.com/realms/gatelink'
export GATELINK_OIDC_CLIENT_ID='gatelink-server'
```

Do not reuse local development credentials in production.

## VAPID keys

Configure a stable P-256 VAPID pair in production:

```bash
export WEBPUSH_VAPID_PUBLIC_KEY='...'
export WEBPUSH_VAPID_PRIVATE_KEY='...'
export WEBPUSH_VAPID_SUBJECT='mailto:admin@example.com'
```

The VAPID key pair signs RFC 8292 authentication. A separate ephemeral P-256 key pair is generated per notification for RFC 8291 key agreement. The private VAPID key must never be returned by the API or logged.

## CORS

Production origins must be explicit:

```bash
export QUARKUS_HTTP_CORS_ORIGINS='https://push.example.com'
```

Documentation: https://quarkus.io/guides/security-cors

## Docker image

```bash
mvn clean package
docker build -f src/main/docker/Dockerfile.jvm -t quarkus-gatelink-server:latest .
```

## Security and observability

- `quarkus-hibernate-validator` rejects malformed subscriptions before persistence/crypto.
- `quarkus-oidc` + `@RolesAllowed("gatelink-admin")` protect notification fan-out and subscription inventory/destructive administration.
- SmallRye Fault Tolerance limits `POST /notifications` to 20 calls/minute and returns HTTP 429 when exceeded.
- `quarkus-opentelemetry` is enabled together with JDBC telemetry.
- `quarkus-logging-json` provides structured production logs; dev/test stay human-readable.
- Browser subscription registration and individual unsubscribe remain public because they are browser-facing flows, but their inputs are strictly validated.

## Remaining production hardening

Before public Internet exposure, add:

- stronger SSRF/network-policy protection for Push Service endpoints;
- production secret management for database/VAPID credentials and stored subscription secrets;
- database backup/restore policy;
- end-to-end tests against the real Push Services of supported browsers.

## Java and TypeScript integration examples

External applications should integrate through the GateLink HTTP API rather than importing server implementation classes. See [`../docs/integration-examples.md`](../docs/integration-examples.md) for Java 21, MicroProfile REST Client, TypeScript, and Angular examples.
