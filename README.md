# Quarkus GateLink Web Push

GateLink is a modern Web Push gateway built with Java 21 / Quarkus, PostgreSQL and an Angular frontend served by Nginx.

The Web Push implementation is intentionally modern-only:

- RFC 8030 Web Push delivery;
- RFC 8188 / RFC 8291 `aes128gcm` only;
- RFC 8292 VAPID;
- no obsolete `aesgcm` compatibility path.

## Start here

| Document | Purpose |
| --- | --- |
| [`docs/operator-guide.md`](docs/operator-guide.md) | complete user → browser → GateLink → PostgreSQL → Push Service lifecycle |
| [`docs/docker-deployment.md`](docs/docker-deployment.md) | Docker/Compose, HTTPS, certificates, persistence and operations |
| [`docs/webpush-java.md`](docs/webpush-java.md) | selected Java Web Push library and integration boundary |
| [`docs/integration-examples.md`](docs/integration-examples.md) | Java and TypeScript API integration examples |
| [`quarkus-gatelink-webpush-server/README.md`](quarkus-gatelink-webpush-server/README.md) | Quarkus server internals |
| [`quarkus-gatelink-webpush-ui/README.md`](quarkus-gatelink-webpush-ui/README.md) | Angular/browser behavior |

## Runtime architecture

The normal user entry point is the HTTPS UI on port `443`.

```text
User / Browser
      |
      | HTTPS :443
      v
+----------------------------------+
| quarkus-gatelink-webpush-ui      |
| Angular + Nginx                  |
| HTTP :80 -> HTTPS redirect       |
| HTTPS :443                       |
+----------------+-----------------+
                 |
                 | /api/*
                 | HTTPS :8443
                 | Docker DNS:
                 | quarkus-gatelink-webpush-server
                 v
+----------------------------------+
| quarkus-gatelink-webpush-server  |
| Quarkus / Java 21                |
| HTTP :8080                       |
| HTTPS :8443                      |
+----------------+-----------------+
                 |
                 | JDBC
                 | postgres:5432
                 v
+----------------------------------+
| postgres                         |
| PostgreSQL 18                    |
+----------------------------------+

Quarkus also sends outbound HTTPS Web Push requests
from the server directly to browser Push Services.
```

### Fixed Docker identities

The Compose service name, `container_name` and Docker hostname are deliberately the same:

```text
quarkus-gatelink-webpush-ui
quarkus-gatelink-webpush-server
```

Nginx never uses `localhost` to reach Quarkus. Its internal upstream is:

```text
https://quarkus-gatelink-webpush-server:8443/
```

## Published ports

| Component | Port | Protocol | Purpose |
| --- | ---: | --- | --- |
| UI | `80` | HTTP | redirect normal traffic to HTTPS; local health endpoint also available |
| UI | `443` | HTTPS | **normal user/browser entry point** |
| Quarkus server | `8080` | HTTP | direct REST/operations access when explicitly needed |
| Quarkus server | `8443` | HTTPS | direct TLS REST/operations access when explicitly needed |
| PostgreSQL | `5432` | internal only | server → database; not published on the host |

## Repository structure

```text
.
├── compose.yaml
├── .env.example
├── deploy/
│   ├── server/
│   │   └── application.properties
│   └── release/
│       ├── compose.yaml
│       ├── server.Dockerfile
│       ├── ui.Dockerfile
│       └── README.md
├── quarkus-gatelink-webpush-server/
│   ├── Dockerfile
│   ├── docker-entrypoint.sh
│   ├── pom.xml
│   ├── runtime/logs/
│   ├── runtime/tmp/
│   └── src/
└── quarkus-gatelink-webpush-ui/
    ├── Dockerfile
    ├── docker-entrypoint.sh
    ├── nginx.conf
    ├── package.json
    ├── angular.json
    └── src/
```

The Maven coordinate of the server is:

```xml
<groupId>com.quarkus</groupId>
<artifactId>quarkus-gatelink-webpush-server</artifactId>
```

## Quick start with Docker Compose

Prepare the environment and writable server directories:

```bash
cp .env.example .env
mkdir -p quarkus-gatelink-webpush-server/runtime/logs
mkdir -p quarkus-gatelink-webpush-server/runtime/tmp
sudo chown -R 10001:10001 quarkus-gatelink-webpush-server/runtime/logs
sudo chown -R 10001:10001 quarkus-gatelink-webpush-server/runtime/tmp
```

Before the first production-style start, edit `.env` and at minimum configure:

```text
POSTGRES_PASSWORD
WEBPUSH_VAPID_PUBLIC_KEY
WEBPUSH_VAPID_PRIVATE_KEY
WEBPUSH_VAPID_SUBJECT
```

If the UI or direct Quarkus HTTPS endpoint will be opened through a machine DNS name/IP other than `localhost`, add it to `UI_TLS_SAN` / `SERVER_TLS_SAN` **before the first start**.

Build and start:

```bash
docker compose build
docker compose up -d --wait
docker compose ps
```

Normal browser entry:

```text
https://localhost/
```

The supplied certificates are self-signed, so a browser/client must explicitly trust or accept them.

Useful checks:

```bash
# UI HTTP -> HTTPS redirect
curl -I http://localhost/

# Normal HTTPS UI and UI -> Quarkus proxy
curl -k https://localhost/healthz
curl -k https://localhost/api/q/health/ready

# Direct Quarkus access
curl http://localhost:8080/q/health/ready
curl -k https://localhost:8443/q/health/ready
```

## Self-signed TLS certificates

No TLS private key is committed to the repository.

On first container startup:

1. `quarkus-gatelink-webpush-ui` generates `/etc/nginx/tls/tls.crt` and `/etc/nginx/tls/tls.key`;
2. `quarkus-gatelink-webpush-server` generates `/opt/app/tls/tls.crt` and `/opt/app/tls/tls.key`;
3. the certificate/key pairs are kept in the `ui_tls` and `server_tls` Docker named volumes;
4. later container replacements reuse the same certificates while those volumes exist.

The defaults are configured in `.env.example`:

```text
UI_TLS_COMMON_NAME=quarkus-gatelink-webpush-ui
UI_TLS_SAN=DNS:quarkus-gatelink-webpush-ui,DNS:localhost,IP:127.0.0.1
SERVER_TLS_COMMON_NAME=quarkus-gatelink-webpush-server
SERVER_TLS_SAN=DNS:quarkus-gatelink-webpush-server,DNS:localhost,IP:127.0.0.1
```

Changing those variables does not rewrite an existing certificate. Regenerate only the TLS volumes when SAN/CN values change; do not delete `postgres_data` merely to regenerate certificates.

Because the internal Quarkus certificate is self-signed, Nginx encrypts the UI → server hop but currently uses `proxy_ssl_verify off`. A deployment with an internal CA can replace that mode with certificate verification.

## Nginx request flow

Angular always calls same-origin `/api/...` URLs.

```text
Browser
  |
  | GET https://host/api/keys/public
  v
Nginx :443
  |
  | proxy_pass
  v
https://quarkus-gatelink-webpush-server:8443/keys/public
```

The matching `/api/` prefix is removed because the `proxy_pass` URL ends with `/`.

Angular client-side routes still use the SPA fallback:

```nginx
try_files $uri $uri/ /index.html;
```

so routes such as `/dashboard` and `/settings` work after a browser refresh without intercepting `/api/`.

## What PostgreSQL is for

PostgreSQL is **not part of the Web Push protocol**. GateLink uses PostgreSQL as the durable registry of browser PushSubscriptions.

Each registered browser contributes:

```text
endpoint + p256dh + auth
```

GateLink stores those values so the server still knows which browsers can receive notifications after a process/container restart.

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

## Selected Java Web Push library

GateLink stays entirely on the JVM and currently uses:

```text
nl.martijndwars:web-push:5.1.2
```

GateLink delegates RFC 8291 / RFC 8188 payload cryptography to the library and explicitly selects:

```java
Encoding.AES128GCM
```

Responsibility boundary:

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

GateLink retains VAPID identity/JWT creation, HTTP request construction, response metrics, persistence, OIDC/RBAC and rate limiting. See [`docs/webpush-java.md`](docs/webpush-java.md).

## Browser subscription: step by step

1. User opens `https://<host>/`.
2. Nginx serves the Angular application from `quarkus-gatelink-webpush-ui`.
3. Angular registers its Service Worker.
4. User chooses Subscribe.
5. Angular requests `/api/keys/public` over the same HTTPS origin.
6. Nginx sends that request to `https://quarkus-gatelink-webpush-server:8443/keys/public` through Docker DNS.
7. GateLink returns the public VAPID key.
8. The browser Push API contacts its vendor Push Service.
9. The browser receives `endpoint`, `p256dh` and `auth`.
10. Angular sends the PushSubscription to `/api/subscriptions`.
11. Nginx forwards it to the Quarkus server over internal HTTPS.
12. GateLink validates the endpoint/key material.
13. PostgreSQL performs `INSERT ... ON CONFLICT DO UPDATE` keyed by endpoint.
14. The subscription survives Quarkus container replacement/restart.

## Notification fan-out: step by step

1. An administrative caller obtains an OIDC token with role `gatelink-admin`.
2. The caller sends `POST /api/notifications` through the HTTPS UI endpoint, or deliberately uses a direct Quarkus port.
3. Quarkus authenticates/authorizes the caller.
4. GateLink applies the notification rate limit.
5. GateLink validates the plaintext payload.
6. GateLink loads current subscriptions from PostgreSQL.
7. For every subscription, `nl.martijndwars:web-push` produces an `aes128gcm` body.
8. GateLink creates the RFC 8292 VAPID JWT.
9. GateLink sends HTTPS directly to the subscription Push Service endpoint.
10. The Push Service status is recorded in metrics.
11. The Push Service later delivers to the browser Service Worker.
12. The Service Worker displays the notification.

A Push Service `2xx` means the service accepted the request; it does not prove that the user saw the notification.

## REST endpoints

Quarkus paths:

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

Via the normal UI origin, prefix application paths with `/api`, for example `/api/keys/public`.

Direct server examples:

```text
http://host:8080/keys/public
https://host:8443/keys/public
```

## Health and startup order

```text
postgres --healthy--> quarkus-gatelink-webpush-server --healthy--> quarkus-gatelink-webpush-ui
```

Checks:

```text
postgres:                         pg_isready
quarkus-gatelink-webpush-server: https://127.0.0.1:8443/q/health/ready
quarkus-gatelink-webpush-ui:     http://127.0.0.1/healthz (container-local check)
```

## Logging and persistence

Quarkus logs to both stdout/stderr and `/opt/app/logs/application.log`.

| Data | Persistence |
| --- | --- |
| browser subscriptions | Docker named volume `postgres_data` |
| server TLS certificate/key | Docker named volume `server_tls` |
| UI TLS certificate/key | Docker named volume `ui_tls` |
| Quarkus application logs | host path `quarkus-gatelink-webpush-server/runtime/logs` in source Compose |
| Quarkus temp | host path `quarkus-gatelink-webpush-server/runtime/tmp` in source Compose |
| external Quarkus config | `deploy/server/application.properties` |

## OIDC

The self-contained three-container stack does not include an identity provider, therefore `.env.example` defaults to:

```text
OIDC_ENABLED=false
OIDC_CLIENT_ID=quarkus-gatelink-webpush-server
```

For protected administrative endpoints in production configure a real issuer and enable OIDC.

## Release artifacts

Publishing a GitHub Release builds and validates artifacts using the repository/project prefix `quarkus-gatelink-webpush`:

```text
quarkus-gatelink-webpush-server-<version>.jar
quarkus-gatelink-webpush-ui-<version>.zip
quarkus-gatelink-webpush-ui-<version>.tar.gz
quarkus-gatelink-webpush-compose-<version>.zip
quarkus-gatelink-webpush-compose-<version>.tar.gz
SHA256SUMS
```

The UI and Compose archive root directories use the same names as their archive filenames without the `.zip` / `.tar.gz` suffix. The Compose archive already contains the Quarkus JAR, Angular production files, TLS entrypoints, Nginx configuration and runtime Dockerfiles. The target host needs Docker/Compose, not Maven or Node.js.

## Local Java development

For backend/server development:

```bash
docker compose up -d postgres
cd quarkus-gatelink-webpush-server
mvn clean verify
mvn quarkus:dev
```

The Docker TLS configuration lives outside the Maven module in `deploy/server/application.properties`, so normal test/dev execution does not accidentally load Docker service names or runtime certificate paths.

## Current delivery semantics

Operators should know that GateLink currently has:

- no automatic Push Service retry;
- no automatic deletion of PostgreSQL subscriptions after Push Service `404` / `410`;
- no per-browser delivery report returned by `POST /notifications`;
- synchronous fan-out, so a network exception can stop later sends in the current request;
- Push Service acceptance rather than end-user acknowledgement.

See [`docs/operator-guide.md`](docs/operator-guide.md) for the full operational lifecycle.

## Technology stack

- Java 21
- Quarkus 3.33.3 LTS
- Quarkus REST + JSON-B
- Hibernate Validator
- OIDC + `gatelink-admin`
- SmallRye Fault Tolerance
- Hibernate ORM with Panache
- PostgreSQL 18 / Docker Official Image `postgres:18.4`
- Flyway
- Micrometer + Prometheus
- OpenTelemetry + JDBC telemetry
- `nl.martijndwars:web-push` 5.1.2
- Angular 22 + TypeScript
- Nginx
- Docker / Docker Compose
