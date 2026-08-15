# Quarkus GateLink Web Push

GateLink is a Java 21 / Quarkus Web Push gateway with durable PostgreSQL subscriptions and a browser UI.

The project is intentionally **modern-only**:

- RFC 8030 Web Push delivery;
- RFC 8188 / RFC 8291 `aes128gcm` only;
- RFC 8292 VAPID;
- no obsolete `aesgcm` compatibility path.

> **Operator documentation:** start with [`docs/operator-guide.md`](docs/operator-guide.md). It explains every runtime flow step by step, including browser subscription, PostgreSQL persistence, notification fan-out, Push Service delivery, unsubscribe, security, metrics and failure semantics.

## Repository structure

There are two application projects:

| Folder | Responsibility |
| --- | --- |
| [`quarkus-gatelink-server/`](quarkus-gatelink-server/) | Java/Quarkus backend plus all Java tests |
| [`quarkus-gatelink-webpush-ui/`](quarkus-gatelink-webpush-ui/) | browser UI plus frontend examples |

Additional documentation:

| Document | Purpose |
| --- | --- |
| [`docs/operator-guide.md`](docs/operator-guide.md) | complete operator-oriented runtime lifecycle |
| [`docs/integration-examples.md`](docs/integration-examples.md) | Java and TypeScript integration examples |
| [`quarkus-gatelink-server/README.md`](quarkus-gatelink-server/README.md) | server internals and configuration |
| [`quarkus-gatelink-webpush-ui/README.md`](quarkus-gatelink-webpush-ui/README.md) | browser-side behavior |

## System at a glance

```text
+-----------+       +----------------------+       +---------------------+
| User      | ----> | Browser / UI         | ----> | GateLink            |
|           |       | Service Worker       | REST  | Java 21 / Quarkus   |
+-----------+       +----------+-----------+       +----------+----------+
                              |                             |
                              | Browser Push API            | JDBC / JPA
                              v                             v
                    +---------+-----------+       +---------+-----------+
                    | Browser Push Service|       | PostgreSQL 18       |
                    | FCM / Mozilla / etc.|       | subscriptions       |
                    +---------+-----------+       +---------------------+
                              ^
                              |
                              | RFC 8030 + aes128gcm + VAPID
                              |
                    +---------+-----------+
                    | GateLink            |
                    +---------------------+
```

The most important operational fact is that **GateLink never opens a direct connection to the user's browser**. The browser vendor Push Service is the delivery intermediary.

## Actors and responsibilities

| Actor / component | Responsibility |
| --- | --- |
| User | grants notification permission and receives notifications |
| Browser UI | calls GateLink REST and manages the browser subscription lifecycle |
| Browser Push API | creates the PushSubscription with the vendor Push Service |
| GateLink | validates, persists, encrypts, signs and forwards Web Push messages |
| PostgreSQL | stores durable `endpoint`, `p256dh` and `auth` subscription data |
| OIDC provider | authenticates administrative callers |
| Push Service | accepts Web Push requests and delivers to the browser |
| Service Worker | receives the browser `push` event and shows the notification |

## End-to-end lifecycle: step by step

This is the complete flow from an empty installation to a notification appearing in the browser.

1. The operator starts PostgreSQL.
2. The operator starts GateLink.
3. GateLink connects to PostgreSQL.
4. Flyway validates and applies schema migrations.
5. Hibernate validates the Java mapping against the database schema.
6. GateLink loads its configured VAPID key pair, or creates a temporary development pair if none is configured.
7. The user opens the browser UI.
8. The browser registers the Service Worker.
9. The user chooses to enable notifications.
10. The browser UI calls `GET /keys/public`.
11. GateLink returns the public VAPID application-server key.
12. The browser Push API contacts the vendor Push Service and creates a PushSubscription.
13. The browser receives `endpoint`, `p256dh` and `auth`.
14. The UI sends that subscription to `POST /subscriptions`.
15. GateLink validates the endpoint and key material before writing anything.
16. PostgreSQL atomically inserts or refreshes the subscription by endpoint.
17. Later, an administrator obtains an OIDC token carrying role `gatelink-admin`.
18. The administrator calls `POST /notifications` with a text payload.
19. GateLink checks authentication, authorization, the 20/minute rate limit and payload size.
20. GateLink loads all current subscriptions from PostgreSQL.
21. For every subscription, GateLink delegates Web Push payload encryption to the Java `nl.martijndwars:web-push` library using `Encoding.AES128GCM` explicitly.
22. GateLink creates the RFC 8292 VAPID JWT.
23. GateLink sends the encrypted HTTP request to the subscription's vendor Push Service endpoint.
24. GateLink records the Push Service HTTP status in Micrometer metrics.
25. The Push Service delivers the push to the browser.
26. The Service Worker receives the push event.
27. The Service Worker displays the notification to the user.

For exact request headers, PostgreSQL behavior, HTTP outcomes and failure semantics, see [`docs/operator-guide.md`](docs/operator-guide.md).

## Subscription setup flow

```text
User
  |
  | clicks Subscribe
  v
Browser UI
  |
  | GET /keys/public
  v
GateLink
  |
  | public VAPID key
  v
Browser Push API
  |
  | register browser/application-server key
  v
Browser vendor Push Service
  |
  | PushSubscription(endpoint, p256dh, auth)
  v
Browser UI
  |
  | POST /subscriptions
  v
GateLink
  |
  | validate
  | INSERT ... ON CONFLICT UPDATE
  v
PostgreSQL
```

The VAPID **private** key never goes to the browser or PostgreSQL.

## Notification delivery flow

```text
Admin / trusted backend
        |
        | OIDC Bearer token + gatelink-admin
        | POST /notifications
        v
GateLink
        |
        +-- validate payload
        +-- rate limit: 20/minute
        |
        +--> PostgreSQL: load subscriptions
        |
        +--> web-push Java library: AES128GCM encryption
        |
        +--> VAPID ES256 JWT
        |
        `--> HTTPS POST to each Push Service endpoint
                 |
                 | RFC 8030 Web Push
                 v
          Browser Push Service
                 |
                 v
          Browser Service Worker
                 |
                 v
               User
```

## Quick start

### 1. Start PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

Local datasource:

```text
jdbc:postgresql://localhost:5432/gatelink
user:     gatelink
password: gatelink
```

### 2. Run the complete Java test suite

```bash
cd quarkus-gatelink-server
mvn clean verify
```

The same Maven build runs unit tests, PostgreSQL persistence tests, Web Push interoperability tests and HTTP/system tests.

### 3. Start GateLink

```bash
cd quarkus-gatelink-server
mvn quarkus:dev
```

Default URL:

```text
http://localhost:8080
```

With Docker available, Quarkus OIDC Dev Services starts Keycloak for secured administrative endpoints. The development identity `alice` is configured with `gatelink-admin`; use `/q/dev-ui` to exercise OIDC flows.

### 4. Start the browser demo

```bash
cd quarkus-gatelink-webpush-ui
./startBrowserSync.sh
```

## REST endpoints

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/keys/public` | public | public VAPID application-server key |
| `POST` | `/subscriptions` | public + validation | create/update browser PushSubscription |
| `DELETE` | `/subscriptions/{endpoint}` | public + validation | remove one known subscription endpoint |
| `GET` | `/subscriptions` | `gatelink-admin` | list known endpoints |
| `DELETE` | `/subscriptions` | `gatelink-admin` | remove all stored subscriptions |
| `POST` | `/notifications` | `gatelink-admin` + rate limit | send text notification to all subscriptions |
| `GET` | `/q/health` | management | health |
| `GET` | `/q/metrics` | management | Prometheus / Micrometer metrics |
| `GET` | `/q/openapi` | management | OpenAPI document |
| `GET` | `/q/swagger-ui` | dev/test | Swagger UI |

## PostgreSQL persistence

The durable state is the browser PushSubscription:

```text
push_subscriptions
+----------+----------------------------------+
| endpoint | TEXT PRIMARY KEY                 |
| p256dh   | TEXT NOT NULL                    |
| auth     | TEXT NOT NULL                    |
+----------+----------------------------------+
```

Registration is idempotent for the same endpoint because GateLink uses PostgreSQL `INSERT ... ON CONFLICT DO UPDATE`.

Inspect local subscriptions:

```bash
docker compose exec postgres psql -U gatelink -d gatelink
```

```sql
SELECT endpoint FROM push_subscriptions ORDER BY endpoint;
```

## VAPID identity

Production must use a stable P-256 VAPID key pair:

```bash
export WEBPUSH_VAPID_PUBLIC_KEY='...'
export WEBPUSH_VAPID_PRIVATE_KEY='...'
export WEBPUSH_VAPID_SUBJECT='mailto:admin@example.com'
```

If neither key is configured, GateLink generates a temporary pair. This is convenient for development but not appropriate for a restart-safe production installation even though PostgreSQL itself is persistent.

## Web Push implementation

GateLink delegates the cryptographic body generation to:

```text
nl.martijndwars:web-push 5.1.2
```

GateLink always chooses:

```java
Encoding.AES128GCM
```

The outbound HTTP request remains under GateLink control so the wire contract is explicitly modern-only:

```text
TTL: 2419200
Content-Encoding: aes128gcm
Authorization: vapid t=<JWT>, k=<public-key>
Content-Type: application/octet-stream
```

GateLink does not emit legacy `Encryption` or `Crypto-Key` delivery headers.

## Security and validation

Browser-facing operations remain public because they are part of the browser subscription lifecycle, but they are validated before persistence.

Administrative operations require OIDC identity with role:

```text
gatelink-admin
```

Subscription validation includes:

- absolute HTTPS endpoint;
- canonical unpadded Base64URL key material;
- 65-octet uncompressed P-256 `p256dh` key;
- 16-octet RFC 8291 `auth` secret.

Notification payloads must be non-blank and are limited to **3993 UTF-8 octets**.

## Observability

GateLink exposes standard Quarkus management endpoints plus Web Push-specific counters.

Important counters include:

```text
webpush.messages.forwarded
webpush.push.attempts{push_service="..."}
webpush.responses{status="..."}
```

OpenTelemetry is enabled with JDBC telemetry. Production console logs use structured JSON while development and tests remain human-readable.

The operator guide explains how to interpret these metrics and what happens on Push Service non-2xx responses or network failures.

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

## Current operational limitations

The current implementation deliberately keeps delivery behavior simple:

- no automatic Push Service retry;
- no automatic deletion of subscriptions when a Push Service returns `404` / `410`;
- no per-browser delivery report returned from `POST /notifications`;
- a Push Service network exception can terminate the current synchronous fan-out;
- a Push Service `2xx` means the service accepted the request, not that the user acknowledged or saw the notification.

These behaviors are documented in detail in [`docs/operator-guide.md`](docs/operator-guide.md).

## Technology stack

- Java 21
- Quarkus 3.33.3 LTS
- Quarkus REST + JSON-B
- MicroProfile REST Client in test scope
- Hibernate Validator
- OIDC + role-based authorization
- SmallRye Fault Tolerance
- Hibernate ORM with Panache
- PostgreSQL 18
- Flyway
- Micrometer + Prometheus
- OpenTelemetry + JDBC telemetry
- structured JSON production logging
- `nl.martijndwars:web-push` for modern Web Push payload encryption
- Angular / TypeScript integration example

## More documentation

- **Operator lifecycle:** [`docs/operator-guide.md`](docs/operator-guide.md)
- **Java / TypeScript integration:** [`docs/integration-examples.md`](docs/integration-examples.md)
- **Server implementation:** [`quarkus-gatelink-server/README.md`](quarkus-gatelink-server/README.md)
- **Browser implementation:** [`quarkus-gatelink-webpush-ui/README.md`](quarkus-gatelink-webpush-ui/README.md)
