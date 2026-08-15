# Quarkus GateLink Web Push

A modern Web Push gateway built with Quarkus, plus a browser UI and Angular/TypeScript integration examples.

## Repository structure

There are only **two top-level projects**:

| Folder | Role | Runs where? |
| --- | --- | --- |
| [`quarkus-gatelink-server/`](quarkus-gatelink-server/) | Quarkus backend **plus all Java tests**, including HTTP/system tests | JVM / container / test JVM |
| [`quarkus-gatelink-webpush-ui/`](quarkus-gatelink-webpush-ui/) | browser UI + frontend integration examples | browser |

There is no separate Java client project anymore. The former client/system-test code is now part of `quarkus-gatelink-server/src/test/java/com/quarkus/gatelink/system/` and uses MicroProfile REST Client against the Quarkus test server.

## Architecture

```text
                                  SUBSCRIPTION SETUP

+--------+       opens       +------------------------------------+
|  User  | ----------------> | Browser / Web Push UI              |
+--------+                    | vanilla JS or Angular/TypeScript   |
                              +----------------+-------------------+
                                               |
                                               | GET /keys/public
                                               v
                              +----------------+-------------------+
                              | quarkus-gatelink-server            |
                              | Quarkus REST                       |
                              +----------------+-------------------+
                                               |
                                               | VAPID public key
                                               v
                              +----------------+-------------------+
                              | Browser Push API / Angular SwPush  |
                              +----------------+-------------------+
                                               |
                                               | subscribe
                                               v
                              +----------------+-------------------+
                              | Browser Push Service               |
                              | FCM / Mozilla / vendor service     |
                              +----------------+-------------------+
                                               |
                                               | PushSubscription
                                               v
                              +----------------+-------------------+
                              | Browser UI                         |
                              +----------------+-------------------+
                                               |
                                               | POST /subscriptions
                                               v
                              +----------------+-------------------+
                              | quarkus-gatelink-server            |
                              +----------------+-------------------+
                                               |
                                               | JPA / JDBC
                                               v
                              +----------------+-------------------+
                              | PostgreSQL                         |
                              | durable subscriptions              |
                              +------------------------------------+


                                 NOTIFICATION DELIVERY

+----------------+       POST /notifications      +-------------------------------+
| Admin / caller | -----------------------------> | quarkus-gatelink-server       |
+----------------+                                | RFC 8291 aes128gcm            |
                                                  | RFC 8292 VAPID                |
                                                  +---------------+---------------+
                                                                  |
                                                                  | load subscriptions
                                                                  v
                                                          +-------+-------+
                                                          | PostgreSQL    |
                                                          +-------+-------+
                                                                  |
                                                                  | RFC 8030 Web Push HTTP
                                                                  v
                                                  +---------------+---------------+
                                                  | Browser Push Service          |
                                                  +---------------+---------------+
                                                                  |
                                                                  | push delivery
                                                                  v
                                                  +---------------+---------------+
                                                  | Browser Service Worker        |
                                                  +---------------+---------------+
                                                                  |
                                                                  v
                                                               +--+--+
                                                               | User |
                                                               +------+
```

The server never opens a direct connection to the user's browser. It sends to the Push Service URL contained in the browser's Push Subscription.

## Java test architecture

The HTTP client exists only as test code inside the server project:

```text
mvn clean verify
      |
      +---- unit tests
      |
      +---- @QuarkusTest starts GateLink
      |          |
      |          +---- @TestHTTPResource -> real test URI
      |          |
      |          +---- MicroProfile REST Client
      |                         |
      |                         v
      |                  GateLink REST endpoints
      |
      +---- PostgreSQL persistence tests
      +---- web-push AES128GCM / RFC 8188 interoperability vector
```

This keeps the useful black-box HTTP contract without maintaining a second Maven project.

## Technology stack

- Java 21
- Quarkus 3.33.3 LTS
- Quarkus REST / Jakarta REST on the server
- MicroProfile REST Client for HTTP-level tests
- MicroProfile Health
- MicroProfile OpenAPI
- Micrometer + Prometheus
- PostgreSQL 18
- Hibernate ORM with Panache
- Flyway database migrations
- Hibernate Validator for REST input validation
- OIDC Bearer authentication + role-based authorization
- SmallRye Fault Tolerance notification rate limiting
- OpenTelemetry tracing + JDBC telemetry
- structured JSON logging in production
- `nl.martijndwars:web-push` 5.1.2 for Web Push payload encryption
- RFC 8030 Web Push delivery
- RFC 8188 / RFC 8291 `aes128gcm` message encryption only
- RFC 8292 VAPID authentication
- standard browser Push API / Service Worker APIs
- Angular `HttpClient` + `SwPush` integration example

## Quick start

### 1. Start PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

Local connection:

```text
jdbc:postgresql://localhost:5432/gatelink
user:     gatelink
password: gatelink
```

### 2. Run all Java tests

```bash
cd quarkus-gatelink-server
mvn clean verify
```

This one command runs unit tests, PostgreSQL persistence tests and HTTP/system tests.

### 3. Start the server

```bash
cd quarkus-gatelink-server
mvn quarkus:dev
```

Default URL:

```text
http://localhost:8080
```

With Docker available, Quarkus also starts Keycloak Dev Services for the secured administrative endpoints. Open `/q/dev-ui`, use the OIDC card, and authenticate as `alice` (`gatelink-admin`).

### 4. Start the browser demo

In another terminal:

```bash
cd quarkus-gatelink-webpush-ui
./startBrowserSync.sh
```

Angular/TypeScript examples are under:

```text
quarkus-gatelink-webpush-ui/examples/angular-typescript/
```

## REST technology choice

The server uses **Quarkus REST**, not RESTEasy Classic:

```xml
<artifactId>quarkus-rest</artifactId>
<artifactId>quarkus-rest-jsonb</artifactId>
```

Server resources use `jakarta.ws.rs.*`.

The test suite uses **MicroProfile REST Client** through `quarkus-rest-client-jsonb` to call those same REST endpoints through HTTP. The dependency is test-scoped because there is no production Java client application.

Official documentation:

- Quarkus REST: https://quarkus.io/guides/rest
- Quarkus REST Client: https://quarkus.io/guides/rest-client
- Quarkus testing: https://quarkus.io/guides/getting-started-testing
- MicroProfile REST Client: https://microprofile.io/specifications/rest-client/

## Main server endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/keys/public` | public VAPID key |
| `POST` | `/subscriptions` | persist a browser Push Subscription |
| `GET` | `/subscriptions` | **admin**: list known endpoints |
| `DELETE` | `/subscriptions/{endpoint}` | delete one subscription |
| `DELETE` | `/subscriptions` | **admin**: delete all subscriptions |
| `POST` | `/notifications` | **admin**: fan-out notification, rate limited to 20/minute |
| `GET` | `/q/health` | health |
| `GET` | `/q/metrics` | Prometheus metrics |
| `GET` | `/q/openapi` | OpenAPI document |
| `GET` | `/q/swagger-ui` | Swagger UI in dev/test |

## PostgreSQL persistence

Browser subscriptions survive server restarts:

```text
POST /subscriptions
        |
        v
SubscriptionsStore
        |
        v
Hibernate ORM / Panache
        |
        v
PostgreSQL
```

Flyway owns the `push_subscriptions` schema. The local PostgreSQL volume survives `docker compose down`.

Inspect subscriptions:

```bash
docker compose exec postgres psql -U gatelink -d gatelink
```

```sql
SELECT endpoint FROM push_subscriptions;
```

Delete all local database data intentionally:

```bash
docker compose down -v
```

See [`quarkus-gatelink-server/README.md`](quarkus-gatelink-server/README.md) for datasource, migrations and production configuration.

## Angular / TypeScript

The Angular example uses framework-native APIs:

```text
Angular HttpClient
      |
      +-- GET /keys/public
      +-- POST /subscriptions
      +-- DELETE /subscriptions/{endpoint}

Angular SwPush
      |
      +-- requestSubscription({ serverPublicKey })
      +-- subscription
      +-- unsubscribe()
      +-- messages / notificationClicks
```

See [`quarkus-gatelink-webpush-ui/examples/angular-typescript/README.md`](quarkus-gatelink-webpush-ui/examples/angular-typescript/README.md).

## Web Push protocol

GateLink targets only the current standardized protocol. There is no backward-compatibility path for obsolete content codings.

Payload encryption is delegated to the maintained `nl.martijndwars:web-push` library. GateLink explicitly calls `Encoding.AES128GCM`; it does **not** use the library's legacy sender/API paths. GateLink keeps its own small HTTP sender so the wire request is restricted to the modern RFC 8292 VAPID form and never emits obsolete `Encryption` or `Crypto-Key` delivery headers.

```text
PushSubscription (endpoint + p256dh + auth)
        |
        | ECDH P-256 + HKDF-SHA256
        v
RFC 8291 input keying material
        |
        | RFC 8188 single-record framing
        | salt + rs + ephemeral key in body
        v
AES-128-GCM encrypted body
Content-Encoding: aes128gcm
        |
        | RFC 8292
        | Authorization: vapid t=<JWT>, k=<public-key>
        v
Push Service
```

The external encryption implementation is checked with the RFC 8188 `aes128gcm` interoperability vector. GateLink HTTP tests assert `Content-Encoding: aes128gcm`, modern `Authorization: vapid t=..., k=...`, and the absence of obsolete `Encryption` / `Crypto-Key` delivery headers.

Standards:

- W3C Push API: https://www.w3.org/TR/push-api/
- RFC 8030: https://www.rfc-editor.org/rfc/rfc8030.html
- RFC 8188: https://www.rfc-editor.org/rfc/rfc8188.html
- RFC 8291: https://www.rfc-editor.org/rfc/rfc8291.html
- RFC 8292: https://www.rfc-editor.org/rfc/rfc8292.html

## Security, validation and observability

GateLink treats browser registration and administration differently:

```text
Browser
  |-- GET /keys/public                         public
  |-- POST /subscriptions                     public + strict validation
  `-- DELETE /subscriptions/{encodedEndpoint} public + strict validation

Administrator / service
  |-- GET /subscriptions                      OIDC + gatelink-admin
  |-- DELETE /subscriptions                   OIDC + gatelink-admin
  `-- POST /notifications                     OIDC + gatelink-admin + 20/min rate limit
```

`PushSubscription` input is validated before persistence or encryption: HTTPS endpoint, canonical unpadded Base64URL, a 65-octet uncompressed P-256 `p256dh` key, and a 16-octet RFC 8291 `auth` secret.

In development mode Quarkus OIDC Dev Services starts Keycloak automatically when Docker is available. The built-in `alice` user receives the `gatelink-admin` role; use the Quarkus Dev UI at `/q/dev-ui` to obtain/test tokens. Tests use `quarkus-test-security` instead of starting Keycloak.

OpenTelemetry is enabled with JDBC telemetry; dev/test do not export OTLP by default. Production console logging uses structured JSON while dev/test keep human-readable output.

## Production configuration

```bash
export GATELINK_DB_URL='jdbc:postgresql://db.example.internal:5432/gatelink'
export GATELINK_DB_USER='gatelink'
export GATELINK_DB_PASSWORD='replace-me'
export WEBPUSH_VAPID_PUBLIC_KEY='...'
export WEBPUSH_VAPID_PRIVATE_KEY='...'
export WEBPUSH_VAPID_SUBJECT='mailto:admin@example.com'
export GATELINK_OIDC_AUTH_SERVER_URL='https://id.example.com/realms/gatelink'
export GATELINK_OIDC_CLIENT_ID='gatelink-server'
export QUARKUS_HTTP_CORS_ORIGINS='https://push.example.com'
```

OIDC/RBAC, strict subscription validation and notification rate limiting are already enabled. Before public Internet exposure, add stronger Push Service endpoint SSRF/network-policy controls, production secret management, database backup/restore policy and real-browser Push Service interoperability tests. Database credentials, VAPID private keys and stored subscription `auth` secrets are sensitive data.

## Java and TypeScript integration examples

Copy-ready examples for **Java 21 `HttpClient`**, **Quarkus / MicroProfile REST Client**, **TypeScript `fetch` + PushManager**, and **Angular `HttpClient` + `SwPush`** are in [`docs/integration-examples.md`](docs/integration-examples.md).
