# `quarkus-gatelink-webpush-server`

This is the Java 21 / Quarkus backend. It contains production code and all Java tests.

Read these documents first:

- [`../docs/operator-guide.md`](../docs/operator-guide.md) — complete runtime lifecycle;
- [`../docs/docker-deployment.md`](../docs/docker-deployment.md) — Docker/Compose deployment;
- [`../docs/webpush-java.md`](../docs/webpush-java.md) — Java Web Push library boundary.

## What the server does

GateLink owns:

- Quarkus REST endpoints;
- strict PushSubscription validation;
- PostgreSQL subscription persistence;
- OIDC/RBAC for administrative calls;
- notification rate limiting;
- stable VAPID application-server identity;
- RFC 8292 VAPID JWT creation;
- Web Push fan-out and Push Service HTTP calls;
- health, metrics, tracing and logs.

Payload cryptography is delegated to `nl.martijndwars:web-push:5.1.2` with `Encoding.AES128GCM` selected explicitly.

```text
REST request
    |
    +-- validation / security / rate limit
    |
    v
GateLink
    |
    +--> PostgreSQL: subscription state
    |
    +--> web-push Java: RFC 8291 / RFC 8188 encryption
    |
    +--> GateLink: VAPID JWT
    |
    `--> JDK HttpClient: Push Service
```

## Docker runtime

The production runtime image is:

```text
eclipse-temurin:21-jre-noble
```

No Red Hat UBI and no Podman-specific image is used.

The Docker build stage requests an uber-JAR only for the image build:

```text
-Dquarkus.package.jar.type=uber-jar
-Dquarkus.package.output-name=app
```

Normal Maven test/dev packaging remains unchanged.

Inside the runtime image:

```text
/opt/app/app.jar                         application binary
/opt/app/config/application.properties  external config, read-only
/opt/app/logs/                           writable persistent logs
/opt/app/tmp/                            writable java.io.tmpdir
```

The process runs as:

```text
UID:GID = 10001:10001
```

and starts with:

```text
java -jar /opt/app/app.jar
```

`JAVA_TOOL_OPTIONS` includes:

```text
-Djava.io.tmpdir=/opt/app/tmp
-Djava.util.logging.manager=org.jboss.logmanager.LogManager
```

## External production configuration

The source file is intentionally outside this Maven module:

```text
../deploy/server/application.properties
```

Compose mounts it as:

```text
/opt/app/config/application.properties
```

Because the container uses `WORKDIR /opt/app`, Quarkus loads it from the standard external location:

```text
$PWD/config/application.properties
```

Keeping the source copy outside `quarkus-gatelink-webpush-server/config/` is deliberate. A module-local `config/application.properties` would also be visible to ordinary Maven/dev/test executions and could leak Docker-only settings such as hostname `postgres` into tests.

The file is mounted read-only. Secrets remain environment variables.

## PostgreSQL in Docker

The backend reaches the database as:

```text
jdbc:postgresql://postgres:5432/${DB_NAME}
```

Never use `localhost` for backend → PostgreSQL communication inside Compose.

Compose injects:

```text
DB_NAME
DB_USER
DB_PASSWORD
```

from the PostgreSQL values in `.env`.

PostgreSQL stores only the durable PushSubscription registry:

```text
push_subscriptions
+----------+------+
| endpoint | TEXT | primary key
| p256dh   | TEXT | not null
| auth     | TEXT | not null
+----------+------+
```

It is not a notification queue, notification history, delivery-acknowledgement store, or VAPID private-key store.

## Startup sequence

```text
PostgreSQL healthy
       |
       v
backend starts
       |
       +-- datasource -> postgres:5432
       +-- Flyway migration
       +-- Hibernate mapping validation
       +-- VAPID key loading
       +-- OIDC/security initialization
       +-- metrics/tracing initialization
       |
       v
/q/health/ready = UP
       |
       v
frontend may start
```

Production must use a stable VAPID pair. If both VAPID keys are absent, GateLink creates a temporary development pair; if only one is supplied, startup fails.

## `GET /keys/public`

1. Nginx receives `/api/keys/public`.
2. It proxies to GateLink as `/keys/public`.
3. `KeysResource` gets the active VAPID key pair.
4. GateLink returns only the public key in unpadded Base64URL form.
5. The private VAPID key never leaves the backend.

## `POST /subscriptions`

```text
POST /subscriptions
       |
       v
PushSubscription validation
       |
       +-- absolute HTTPS endpoint
       +-- canonical unpadded Base64URL
       +-- p256dh = 65-byte uncompressed P-256 point
       `-- auth = 16 bytes
       |
       v
SubscriptionsStore
       |
       | INSERT ... ON CONFLICT DO UPDATE
       v
PostgreSQL
```

The endpoint is the primary key, so re-registering the same browser refreshes `p256dh`/`auth` rather than creating a duplicate.

Invalid subscriptions are rejected before persistence.

## `POST /notifications`

This is an administrative endpoint.

```text
POST /notifications
       |
       +-- OIDC Bearer authentication
       +-- role gatelink-admin
       +-- 20 calls/minute rate limit
       +-- non-blank payload
       +-- max 3993 UTF-8 octets
       |
       v
NotificationsSender
       |
       +--> SELECT subscriptions from PostgreSQL
       |
       `--> for each subscription
              |
              +--> web-push Java / Encoding.AES128GCM
              +--> GateLink VAPID JWT
              +--> HTTPS POST to Push Service
              `--> record response status metric
```

The outbound modern-only request is:

```text
TTL: 2419200
Content-Encoding: aes128gcm
Authorization: vapid t=<JWT>, k=<public-key>
Content-Type: application/octet-stream
```

GateLink does not emit obsolete `Encryption` or `Crypto-Key` delivery headers and has no application path using `Encoding.AESGCM`.

## API access model

| Method | Path | Access |
| --- | --- | --- |
| `GET` | `/keys/public` | public |
| `POST` | `/subscriptions` | public + strict validation |
| `DELETE` | `/subscriptions/{endpoint}` | public + path validation |
| `GET` | `/subscriptions` | `gatelink-admin` |
| `DELETE` | `/subscriptions` | `gatelink-admin` |
| `POST` | `/notifications` | `gatelink-admin` + 20/min rate limit |

Nginx exposes the same endpoints to the browser under `/api/...`.

## OIDC

The three-container Compose stack intentionally contains only:

```text
frontend + backend + postgres
```

so no identity provider container is added. `.env.example` leaves OIDC disabled for a self-contained local startup.

For production administrative operations configure an external OIDC provider:

```text
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://id.example.com/realms/gatelink
OIDC_CLIENT_ID=gatelink-server
```

Administrative tokens need role:

```text
gatelink-admin
```

## VAPID

Production environment:

```text
WEBPUSH_VAPID_PUBLIC_KEY=...
WEBPUSH_VAPID_PRIVATE_KEY=...
WEBPUSH_VAPID_SUBJECT=mailto:admin@example.com
```

The VAPID key pair identifies the application server. It is separate from the ephemeral encryption key material produced for individual Web Push messages by the encryption implementation.

## Logging

Console output remains enabled for:

```bash
docker compose logs -f backend
```

GateLink additionally writes:

```text
/opt/app/logs/application.log
```

through the host bind mount:

```text
quarkus-gatelink-webpush-server/runtime/logs/
```

File rotation is configured for:

```text
max file size: 50 MB
max backups:   10
rotation:      date-suffixed, compressed .gz
```

## Temporary files

`java.io.tmpdir` is:

```text
/opt/app/tmp
```

bound to:

```text
quarkus-gatelink-webpush-server/runtime/tmp/
```

Both runtime directories must be writable on the host by `10001:10001`.

## Observability

Health:

```text
GET /q/health
GET /q/health/ready
```

Metrics:

```text
GET /q/metrics
```

GateLink-specific counters include:

```text
webpush.messages.forwarded
webpush.push.attempts{push_service="..."}
webpush.responses{status="..."}
```

OpenTelemetry is enabled with JDBC telemetry. OTLP export is opt-in in the container configuration.

OpenAPI:

```text
GET /q/openapi
```

## Current delivery semantics

Operators should not infer behavior that is not implemented:

- no automatic Push Service retry;
- no automatic database cleanup on Push Service `404`/`410`;
- no per-browser delivery report in the admin REST response;
- a network I/O exception can stop the remaining synchronous fan-out;
- Push Service `2xx` means request accepted by that Push Service, not user acknowledgement.

See [`../docs/operator-guide.md`](../docs/operator-guide.md).

## Build and test

Start PostgreSQL for the normal local test profile:

```bash
docker compose up -d postgres
```

Then:

```bash
cd quarkus-gatelink-webpush-server
mvn clean verify
```

The production container image is built from the repository root:

```bash
docker compose build backend
```

CI runs the Java test suite, validates the resolved Compose model and builds both production application images.

## Local development

```bash
cd quarkus-gatelink-webpush-server
mvn quarkus:dev
```

Local Maven/dev/test uses `src/main/resources/application.properties`; it does not load the Docker runtime file from `deploy/server/`.

## Container HTTP/HTTPS contract

The server service/container/hostname is `quarkus-gatelink-webpush-server`. It listens on HTTP `8080` and HTTPS `8443` simultaneously. The container entrypoint creates `/opt/app/tls/tls.crt` and `/opt/app/tls/tls.key` on first start when they are absent.

UI traffic arrives through Nginx over `https://quarkus-gatelink-webpush-server:8443/`. Direct operator calls may use `http://localhost:8080` or `https://localhost:8443`.
