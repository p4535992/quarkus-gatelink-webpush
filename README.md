# Quarkus GateLink Web Push

GateLink is a modern Web Push gateway built with Java 21 / Quarkus, PostgreSQL and an Angular frontend served by Nginx.

The protocol implementation is intentionally **modern-only**:

- RFC 8030 Web Push delivery;
- RFC 8188 / RFC 8291 `aes128gcm` only;
- RFC 8292 VAPID;
- no obsolete `aesgcm` compatibility path.

## Start here

| Document | Purpose |
| --- | --- |
| [`docs/operator-guide.md`](docs/operator-guide.md) | complete user → browser → GateLink → PostgreSQL → Push Service lifecycle |
| [`docs/docker-deployment.md`](docs/docker-deployment.md) | Docker/Compose deployment, permissions, Nginx proxy, persistence and updates |
| [`docs/webpush-java.md`](docs/webpush-java.md) | selected Java Web Push library and integration boundary |
| [`docs/integration-examples.md`](docs/integration-examples.md) | Java and TypeScript API integration examples |
| [`quarkus-gatelink-webpush-server/README.md`](quarkus-gatelink-webpush-server/README.md) | Quarkus server internals |
| [`quarkus-gatelink-webpush-ui/README.md`](quarkus-gatelink-webpush-ui/README.md) | Angular/browser behavior |

## Repository structure

```text
.
├── compose.yaml
├── .env.example
├── deploy/
│   └── backend/
│       └── application.properties
├── quarkus-gatelink-webpush-server/
│   ├── Dockerfile
│   ├── runtime/logs/
│   ├── runtime/tmp/
│   └── src/
└── quarkus-gatelink-webpush-ui/
    ├── Dockerfile
    ├── nginx.conf
    ├── package.json
    ├── angular.json
    └── src/
```

The container runtime configuration intentionally lives outside the Quarkus module. If a file named `config/application.properties` were kept under `quarkus-gatelink-webpush-server/`, normal Maven/dev/test executions could load Docker-only settings such as the hostname `postgres`. Compose instead mounts `deploy/server/application.properties` into the standard runtime path `/opt/app/config/application.properties`.

## Production-style Docker quick start

The runtime stack contains exactly three services:

```text
Browser
   |
   | :8081
   v
frontend
Angular + Nginx
   |
   | /api/*
   v
backend
Quarkus :8080
   |
   | JDBC
   v
postgres :5432
```

Prepare the host:

```bash
cp .env.example .env
mkdir -p quarkus-gatelink-webpush-server/runtime/logs
mkdir -p quarkus-gatelink-webpush-server/runtime/tmp
sudo chown -R 10001:10001 quarkus-gatelink-webpush-server/runtime/logs
sudo chown -R 10001:10001 quarkus-gatelink-webpush-server/runtime/tmp
```

Build and start:

```bash
docker compose build
docker compose up -d
docker compose ps
docker compose logs -f
```

Open:

```text
http://localhost:8081
```

The backend and PostgreSQL are not published on host ports. Browser requests use `/api/...`; Nginx resolves `backend` through Docker DNS and Quarkus resolves PostgreSQL as `postgres:5432`.

See [`docs/docker-deployment.md`](docs/docker-deployment.md) before production deployment, especially for VAPID keys, OIDC, host permissions and HTTPS.

## Docker security/runtime choices

Backend runtime:

```text
eclipse-temurin:21-jre-noble
```

GateLink does not use Red Hat UBI or Podman-specific images. The Quarkus process runs as:

```text
10001:10001
```

and starts as:

```text
java -jar /opt/app/app.jar
```

The following paths are external to the application binary:

```text
/opt/app/config/application.properties   read-only configuration
/opt/app/logs/                           persistent application logs
/opt/app/tmp/                            java.io.tmpdir
```

The Docker build stage explicitly requests a Quarkus uber-JAR named `app-runner.jar` and copies it to `/opt/app/app.jar`. Normal Maven test/dev packaging is left unchanged. Java source and Maven are not present in the runtime image.

## Nginx and Angular

The production frontend is Angular + TypeScript. `ng serve` is not used in production.

The Docker build runs Angular's production build and copies the generated browser artifacts into Nginx.

Angular calls GateLink with same-origin URLs:

```text
/api/keys/public
/api/subscriptions
/api/notifications
```

Nginx proxies:

```nginx
location /api/ {
    proxy_pass http://backend:8080/;
}
```

The trailing slash means:

```text
/api/keys/public  ->  backend:8080/keys/public
```

Angular client-side routes use the SPA fallback:

```nginx
try_files $uri $uri/ /index.html;
```

so `/dashboard` and `/settings` continue to work after a browser refresh without intercepting `/api/`.

## What PostgreSQL is for

PostgreSQL is **not required by the Web Push RFCs**. GateLink uses it as a durable registry of browser PushSubscriptions.

A registered browser contributes:

```text
endpoint + p256dh + auth
```

GateLink persists those values so it still knows which browsers to send to after a server/container restart.

```text
Browser subscription
      |
      v
POST /subscriptions
      |
      v
PostgreSQL
endpoint + p256dh + auth

... later ...

POST /notifications
      |
      v
GateLink SELECTs subscriptions
      |
      v
one Web Push send per stored subscription
```

PostgreSQL is not used as:

- a notification queue;
- notification history;
- delivery acknowledgement storage;
- VAPID private-key storage.

The table is intentionally small:

```text
push_subscriptions
+----------+------------------+
| endpoint | TEXT PRIMARY KEY |
| p256dh   | TEXT NOT NULL    |
| auth     | TEXT NOT NULL    |
+----------+------------------+
```

## Selected Java Web Push library

GateLink remains entirely on the JVM and uses:

```text
nl.martijndwars:web-push:5.1.2
```

GateLink delegates only the RFC 8291 / RFC 8188 payload cryptography to that library and explicitly calls:

```java
Encoding.AES128GCM
```

The responsibility boundary is:

```text
PostgreSQL
endpoint + p256dh + auth
      |
      v
GateLink EncryptionService
      |
      | nl.martijndwars:web-push
      | Encoding.AES128GCM
      v
encrypted body
      |
      +--> GateLink VAPID JWT
      |
      v
GateLink JDK HttpClient
      |
      v
Browser Push Service
```

GateLink retains control of VAPID identity, VAPID JWT creation, HTTP requests, response metrics, OIDC/RBAC, persistence and rate limiting. It does not use the library's legacy sender path and does not emit obsolete `Encryption` or `Crypto-Key` delivery headers.

See [`docs/webpush-java.md`](docs/webpush-java.md).

## Browser subscription: step by step

1. User opens the Angular UI.
2. Angular registers its Service Worker.
3. User chooses Subscribe.
4. Angular requests `/api/keys/public`.
5. Nginx forwards the request to `backend:8080/keys/public`.
6. GateLink returns the public VAPID key.
7. Angular `SwPush` / browser Push API contacts the browser vendor Push Service.
8. The browser receives `endpoint`, `p256dh` and `auth`.
9. Angular sends that PushSubscription to `/api/subscriptions`.
10. GateLink validates HTTPS endpoint and Web Push key material.
11. PostgreSQL performs `INSERT ... ON CONFLICT DO UPDATE` keyed by endpoint.
12. The subscription survives backend container replacement/restart.

## Notification fan-out: step by step

1. An administrative caller obtains an OIDC token with role `gatelink-admin`.
2. Caller sends `POST /notifications` (through Nginx: `/api/notifications`).
3. Quarkus authenticates and authorizes the caller.
4. GateLink applies the 20 requests/minute rate limit.
5. GateLink validates the plaintext payload (maximum 3993 UTF-8 octets).
6. GateLink loads current subscriptions from PostgreSQL.
7. For each subscription, `nl.martijndwars:web-push` produces an `aes128gcm` body.
8. GateLink creates the RFC 8292 VAPID JWT.
9. GateLink sends the message to the subscription's Push Service endpoint.
10. The Push Service HTTP status is recorded in Micrometer metrics.
11. The Push Service later delivers to the browser Service Worker.
12. The Service Worker displays the notification.

A Push Service `2xx` means the service accepted the Web Push request; it is not proof that the user saw the notification.

## REST endpoints

The Quarkus paths are:

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/keys/public` | public | public VAPID key |
| `POST` | `/subscriptions` | public + validation | create/update browser subscription |
| `DELETE` | `/subscriptions/{endpoint}` | public + validation | remove one subscription |
| `GET` | `/subscriptions` | `gatelink-admin` | list subscription endpoints |
| `DELETE` | `/subscriptions` | `gatelink-admin` | remove all subscriptions |
| `POST` | `/notifications` | `gatelink-admin` + rate limit | fan-out text notification |
| `GET` | `/q/health` | management | health |
| `GET` | `/q/metrics` | management | Prometheus metrics |
| `GET` | `/q/openapi` | management | OpenAPI |

Through the production frontend, prefix these with `/api`, for example `/api/keys/public`.

## Health and startup order

Compose startup order is:

```text
postgres --healthy--> backend --healthy--> frontend
```

Health checks:

```text
postgres: pg_isready
backend:  /q/health/ready
frontend: /healthz
```

The Quarkus project includes `quarkus-smallrye-health`.

## Logging and persistence

Quarkus writes to both:

```text
stdout/stderr                         docker compose logs
/opt/app/logs/application.log         host bind mount
```

File rotation is configured for 50 MB files, 10 backups and compressed rotated files.

Persistent data:

| Data | Location |
| --- | --- |
| browser subscriptions | Docker named volume `postgres_data` |
| Quarkus file logs | `quarkus-gatelink-webpush-server/runtime/logs` |
| Quarkus temp | `quarkus-gatelink-webpush-server/runtime/tmp` |
| external runtime config | `deploy/server/application.properties` |

## Local Java development

For backend-only development/tests, PostgreSQL can still be started separately:

```bash
docker compose up -d postgres
cd quarkus-gatelink-webpush-server
mvn clean verify
mvn quarkus:dev
```

The test/dev profile uses the local PostgreSQL port expected by the Java tests. The production Compose backend uses the external mounted configuration and Docker hostname `postgres` instead.

## Current delivery semantics

Operators should know that GateLink currently has:

- no automatic Push Service retry;
- no automatic deletion of PostgreSQL subscriptions after Push Service `404` / `410`;
- no per-browser delivery report returned by `POST /notifications`;
- synchronous fan-out, so a network exception can stop later sends in the current request;
- Push Service acceptance rather than end-user acknowledgement.

See [`docs/operator-guide.md`](docs/operator-guide.md) for the complete operational explanation.

## Technology stack

- Java 21
- Quarkus 3.33.3 LTS
- Quarkus REST + JSON-B
- Hibernate Validator
- OIDC + `gatelink-admin`
- SmallRye Fault Tolerance
- Hibernate ORM with Panache
- PostgreSQL 18
- Flyway
- Micrometer + Prometheus
- OpenTelemetry + JDBC telemetry
- `nl.martijndwars:web-push` 5.1.2
- Angular 22 + TypeScript
- Nginx
- Docker / Docker Compose

## HTTPS, container names and direct API ports

The normal user entry point is **HTTPS 443** on `quarkus-gatelink-webpush-ui`. HTTP 80 exists only to redirect to HTTPS. Nginx proxies `/api/` over the private Docker network to `https://quarkus-gatelink-webpush-server:8443/`.

Quarkus intentionally exposes `http://localhost:8080` and `https://localhost:8443` for direct REST/operations access. The fixed Docker service/container/hostname identities are `quarkus-gatelink-webpush-ui` and `quarkus-gatelink-webpush-server`.

On first start each container creates its own self-signed certificate and persists it in a Docker volume. Configure `UI_TLS_SAN` and `SERVER_TLS_SAN` before first start when clients use another hostname or IP. Self-signed certificates must be explicitly trusted/accepted.
