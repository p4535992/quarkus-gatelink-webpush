# `quarkus-gatelink-webpush-server`

This module is the Java 21 / Quarkus Web Push server. It contains the production Java code and all Java tests.

Maven coordinates:

```xml
<groupId>com.quarkus</groupId>
<artifactId>quarkus-gatelink-webpush-server</artifactId>
```

Read also:

- [`../docs/operator-guide.md`](../docs/operator-guide.md) — complete runtime lifecycle;
- [`../docs/docker-deployment.md`](../docs/docker-deployment.md) — Docker/HTTPS deployment;
- [`../docs/webpush-java.md`](../docs/webpush-java.md) — Java Web Push library boundary.

## Runtime identity and ports

Inside Docker, all three identifiers are deliberately identical:

```text
Compose service:  quarkus-gatelink-webpush-server
container_name:   quarkus-gatelink-webpush-server
Docker hostname:  quarkus-gatelink-webpush-server
```

The server listens simultaneously on:

```text
HTTP  :8080
HTTPS :8443
```

The normal browser path does **not** call those ports directly. Nginx in `quarkus-gatelink-webpush-ui` receives browser traffic on HTTPS 443 and proxies `/api/...` to:

```text
https://quarkus-gatelink-webpush-server:8443/
```

Direct operator/API calls remain possible when required:

```bash
curl http://localhost:8080/q/health/ready
curl -k https://localhost:8443/q/health/ready
```

## What the server owns

GateLink owns:

- Quarkus REST endpoints;
- PushSubscription validation;
- PostgreSQL subscription persistence;
- OIDC/RBAC for administrative operations;
- notification rate limiting;
- VAPID application-server identity and JWT creation;
- Web Push fan-out and outbound HTTP;
- health, metrics, tracing and logs.

Payload encryption is delegated to:

```text
nl.martijndwars:web-push:5.1.2
```

with `Encoding.AES128GCM` selected explicitly.

```text
REST request
    |
    +-- validation / security / rate limit
    |
    v
GateLink
    |
    +--> PostgreSQL: endpoint + p256dh + auth
    |
    +--> web-push Java: RFC 8291 / RFC 8188 encryption
    |
    +--> GateLink: RFC 8292 VAPID JWT
    |
    `--> JDK HttpClient: browser Push Service
```

There is no GateLink legacy path for obsolete `aesgcm`, `Encryption` or `Crypto-Key` delivery formats.

## Docker image

Build stage:

```text
maven:3.9.13-eclipse-temurin-21-noble
```

Runtime image:

```text
eclipse-temurin:21-jre-noble
```

The Docker build creates a Quarkus uber-JAR and installs it as:

```text
/opt/app/app.jar
```

Runtime identity:

```text
UID:GID = 10001:10001
```

Important runtime paths:

```text
/opt/app/app.jar                         application binary
/opt/app/config/application.properties  external Docker config, read-only
/opt/app/logs/                           persistent logs
/opt/app/tmp/                            java.io.tmpdir
/opt/app/tls/tls.crt                    generated server certificate
/opt/app/tls/tls.key                    generated server private key
```

The image installs OpenSSL for first-start certificate creation and `curl` for the HTTPS healthcheck.

## Self-signed HTTPS

`docker-entrypoint.sh` checks the `server_tls` volume before starting Java.

If TLS material is missing it creates:

```text
/opt/app/tls/tls.crt
/opt/app/tls/tls.key
```

using RSA-3072 / SHA-256. Defaults are controlled by:

```text
SERVER_TLS_COMMON_NAME=quarkus-gatelink-webpush-server
SERVER_TLS_SAN=DNS:quarkus-gatelink-webpush-server,DNS:localhost,IP:127.0.0.1
TLS_DAYS=825
```

The private key is generated at runtime and is never stored in Git.

The external Docker Quarkus configuration uses the PEM files and keeps HTTP enabled:

```properties
quarkus.http.port=8080
quarkus.http.ssl-port=8443
quarkus.http.insecure-requests=enabled
quarkus.tls.key-store.pem.0.cert=/opt/app/tls/tls.crt
quarkus.tls.key-store.pem.0.key=/opt/app/tls/tls.key
```

## External Docker configuration

The source file is intentionally outside the Maven module:

```text
../deploy/server/application.properties
```

Compose mounts it read-only as:

```text
/opt/app/config/application.properties
```

This separation is intentional: normal `mvn test` and `mvn quarkus:dev` executions must not accidentally load Docker-only hostnames such as `postgres` or Docker TLS paths.

Inside Compose, the server reaches PostgreSQL as:

```text
jdbc:postgresql://postgres:5432/${DB_NAME}
```

Compose supplies:

```text
DB_NAME
DB_USER
DB_PASSWORD
```

## Standalone JAR configuration

The packaged application also retains its non-Compose production profile. When the released JAR is run directly **without** `deploy/server/application.properties`, its packaged `%prod` configuration expects:

```text
GATELINK_DB_URL
GATELINK_DB_USER
GATELINK_DB_PASSWORD
GATELINK_OIDC_AUTH_SERVER_URL
GATELINK_OIDC_CLIENT_ID
```

The Docker/Compose deployment instead uses the external config described above.

## PostgreSQL role

PostgreSQL stores the durable browser subscription registry:

```text
push_subscriptions
+----------+------+
| endpoint | TEXT | primary key
| p256dh   | TEXT | not null
| auth     | TEXT | not null
+----------+------+
```

It does not store notification history, delivery acknowledgements or the VAPID private key and is not used as a message queue.

## Startup sequence

```text
postgres healthy
      |
      v
quarkus-gatelink-webpush-server starts
      |
      +-- TLS certificate already present/generated by entrypoint
      +-- datasource -> postgres:5432
      +-- Flyway migrations
      +-- Hibernate mapping validation
      +-- VAPID key loading
      +-- OIDC/security initialization
      +-- metrics/tracing initialization
      |
      v
HTTPS /q/health/ready = UP on :8443
      |
      v
quarkus-gatelink-webpush-ui may start
```

Production must use a stable VAPID pair. If both VAPID keys are absent GateLink generates a temporary development pair; if only one is supplied startup fails.

## `GET /keys/public`

Normal UI flow:

1. Browser requests `GET https://<ui-host>/api/keys/public`.
2. Nginx removes `/api/` and calls `https://quarkus-gatelink-webpush-server:8443/keys/public`.
3. `KeysResource` obtains the active VAPID identity.
4. GateLink returns only the public VAPID key in unpadded Base64URL form.
5. The VAPID private key never leaves the server.

Direct server paths are still `/keys/public` on ports 8080/8443.

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

The endpoint is the primary key. Re-registering the same endpoint refreshes `p256dh` and `auth` instead of creating a duplicate.

From the normal UI origin this endpoint is `/api/subscriptions`.

## `POST /notifications`

This is an administrative operation:

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

Outbound modern Web Push request shape:

```text
TTL: 2419200
Content-Encoding: aes128gcm
Authorization: vapid t=<JWT>, k=<public-key>
Content-Type: application/octet-stream
```

From the normal UI/reverse-proxy origin the REST path is `/api/notifications`.

## REST access model

| Method | Quarkus path | UI path | Access |
| --- | --- | --- | --- |
| `GET` | `/keys/public` | `/api/keys/public` | public |
| `POST` | `/subscriptions` | `/api/subscriptions` | public + validation |
| `DELETE` | `/subscriptions/{endpoint}` | `/api/subscriptions/{endpoint}` | public + validation |
| `GET` | `/subscriptions` | `/api/subscriptions` | `gatelink-admin` |
| `DELETE` | `/subscriptions` | `/api/subscriptions` | `gatelink-admin` |
| `POST` | `/notifications` | `/api/notifications` | `gatelink-admin` + rate limit |

Management endpoints are also reachable through `/api/q/...` from Nginx and directly as `/q/...` on 8080/8443.

## OIDC

The three-container Compose stack contains:

```text
quarkus-gatelink-webpush-ui
quarkus-gatelink-webpush-server
postgres
```

It does not contain an identity provider. `.env.example` therefore defaults to:

```text
OIDC_ENABLED=false
OIDC_CLIENT_ID=quarkus-gatelink-webpush-server
```

Production administrative operations should use a real external issuer and tokens carrying:

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

VAPID identity is separate from the ephemeral ECDH encryption material generated for individual Web Push messages.

## Logging and temporary files

Follow container logs:

```bash
docker compose logs -f quarkus-gatelink-webpush-server
```

Persistent source-Compose log file:

```text
quarkus-gatelink-webpush-server/runtime/logs/application.log
```

The server also uses:

```text
/opt/app/tmp
```

as `java.io.tmpdir`.

File logging rotates at 50 MB with 10 compressed backups.

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

GateLink counters include:

```text
webpush.messages.forwarded
webpush.push.attempts{push_service="..."}
webpush.responses{status="..."}
```

OpenAPI:

```text
GET /q/openapi
```

OpenTelemetry includes JDBC telemetry; OTLP export is opt-in in the Docker config.

## Current delivery semantics

Operators should not infer behavior that does not exist:

- no automatic Push Service retry;
- no automatic PostgreSQL cleanup on Push Service `404` / `410`;
- no per-browser delivery report in the admin REST response;
- a network I/O exception can stop the remaining synchronous fan-out;
- Push Service `2xx` means accepted by that Push Service, not user acknowledgement.

## Build and test

Start PostgreSQL:

```bash
docker compose up -d postgres
```

Run the Java suite:

```bash
cd quarkus-gatelink-webpush-server
mvn clean verify
```

Build only the server image:

```bash
docker compose build quarkus-gatelink-webpush-server
```

Run/update only the server container:

```bash
docker compose up -d --no-deps quarkus-gatelink-webpush-server
```

## Local development

```bash
cd quarkus-gatelink-webpush-server
mvn quarkus:dev
```

Normal Maven/dev/test runs use the packaged application configuration and do not load `deploy/server/application.properties`.
