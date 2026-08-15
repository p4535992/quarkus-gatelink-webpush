# Docker / Docker Compose deployment

This guide is the operational reference for the GateLink Docker stack.

## 1. Runtime topology

The stack contains three services:

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
                 | Docker DNS hostname:
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
```

The server also makes outbound HTTPS requests to browser Push Services. An external OIDC issuer may also be contacted when OIDC is enabled.

## 2. Fixed service, container and hostname names

GateLink deliberately uses the same string for the Compose service name, `container_name` and Docker hostname:

```text
quarkus-gatelink-webpush-ui
quarkus-gatelink-webpush-server
```

PostgreSQL uses the service hostname:

```text
postgres
```

Container-to-container communication must use those Docker DNS names. `localhost` refers only to the process's own container.

## 3. Published ports

| Service | Host port | Container port | Protocol | Purpose |
| --- | ---: | ---: | --- | --- |
| `quarkus-gatelink-webpush-ui` | `80` | `80` | HTTP | redirect user traffic to HTTPS; container-local `/healthz` also exists |
| `quarkus-gatelink-webpush-ui` | `443` | `443` | HTTPS | **normal browser/user entry point** |
| `quarkus-gatelink-webpush-server` | `8080` | `8080` | HTTP | direct REST/operations access when explicitly needed |
| `quarkus-gatelink-webpush-server` | `8443` | `8443` | HTTPS | direct REST/operations access over TLS |
| `postgres` | not published | `5432` | PostgreSQL | internal server → database traffic only |

For ordinary application use, clients should use the UI HTTPS origin on port `443` and reach GateLink REST endpoints through `/api/...`.

## 4. Repository layout

```text
quarkus-gatelink-webpush/
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
│
├── quarkus-gatelink-webpush-server/
│   ├── Dockerfile
│   ├── docker-entrypoint.sh
│   ├── pom.xml
│   ├── runtime/
│   │   ├── logs/
│   │   └── tmp/
│   └── src/
│
└── quarkus-gatelink-webpush-ui/
    ├── Dockerfile
    ├── docker-entrypoint.sh
    ├── nginx.conf
    ├── package.json
    ├── angular.json
    └── src/
```

The external Quarkus runtime configuration intentionally lives in `deploy/server/`, outside the Maven module. Compose mounts it read-only at:

```text
/opt/app/config/application.properties
```

This prevents Docker-only hostnames and TLS paths from leaking into normal Maven test/dev execution.

## 5. Prepare the host

From the repository root:

```bash
cp .env.example .env
mkdir -p quarkus-gatelink-webpush-server/runtime/logs
mkdir -p quarkus-gatelink-webpush-server/runtime/tmp
```

The Quarkus runtime uses:

```text
UID 10001
GID 10001
```

Prepare the source-Compose bind mounts:

```bash
sudo chown -R 10001:10001 quarkus-gatelink-webpush-server/runtime/logs
sudo chown -R 10001:10001 quarkus-gatelink-webpush-server/runtime/tmp
sudo chmod 0750 quarkus-gatelink-webpush-server/runtime/logs
sudo chmod 0750 quarkus-gatelink-webpush-server/runtime/tmp
chmod 0644 deploy/server/application.properties
```

The downloadable Release bundle uses Docker named volumes for server logs/tmp instead, so it does not require these host-directory ownership commands.

## 6. Configure `.env`

At minimum change production secrets and VAPID identity:

```text
POSTGRES_PASSWORD
WEBPUSH_VAPID_PUBLIC_KEY
WEBPUSH_VAPID_PRIVATE_KEY
WEBPUSH_VAPID_SUBJECT
```

The supplied example intentionally allows a local self-contained boot with:

```text
OIDC_ENABLED=false
OIDC_CLIENT_ID=quarkus-gatelink-webpush-server
```

For production administrative endpoints, configure a real external issuer:

```text
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://id.example.com/realms/gatelink
OIDC_CLIENT_ID=quarkus-gatelink-webpush-server
```

and use an access token carrying role `gatelink-admin`.

## 7. Self-signed TLS configuration

GateLink generates self-signed certificates at container startup if no certificate exists in the corresponding TLS volume.

No certificate private key is committed to Git.

### UI certificate

Generated inside `quarkus-gatelink-webpush-ui`:

```text
/etc/nginx/tls/tls.crt
/etc/nginx/tls/tls.key
```

Persisted in the Docker named volume:

```text
ui_tls
```

### Quarkus server certificate

Generated inside `quarkus-gatelink-webpush-server`:

```text
/opt/app/tls/tls.crt
/opt/app/tls/tls.key
```

Persisted in:

```text
server_tls
```

### Default certificate identity

`.env.example` contains:

```text
TLS_DAYS=825
UI_TLS_COMMON_NAME=quarkus-gatelink-webpush-ui
UI_TLS_SAN=DNS:quarkus-gatelink-webpush-ui,DNS:localhost,IP:127.0.0.1
SERVER_TLS_COMMON_NAME=quarkus-gatelink-webpush-server
SERVER_TLS_SAN=DNS:quarkus-gatelink-webpush-server,DNS:localhost,IP:127.0.0.1
```

If an operator opens the UI as, for example, `https://gatelink.internal.example/`, add that DNS name to `UI_TLS_SAN` before the first start:

```text
UI_TLS_SAN=DNS:quarkus-gatelink-webpush-ui,DNS:localhost,DNS:gatelink.internal.example,IP:127.0.0.1
```

If direct HTTPS calls use that host as well, add it to `SERVER_TLS_SAN` too.

Changing SAN/CN environment variables later does **not** rewrite an existing certificate. Stop the stack and remove only the appropriate TLS volume(s) when a certificate must be regenerated. Do not delete `postgres_data` just to regenerate TLS material.

### Trust behavior

A self-signed certificate is encrypted TLS but is not automatically trusted by browsers/clients. Operators must import/trust the UI certificate or explicitly accept the warning.

Do not enable HSTS while using an untrusted development/self-signed certificate because HSTS makes certificate recovery and hostname changes harder for operators.

## 8. Quarkus HTTPS configuration

The container external configuration enables both HTTP and HTTPS at the same time:

```properties
quarkus.http.host=0.0.0.0
quarkus.http.port=8080
quarkus.http.ssl-port=8443
quarkus.http.insecure-requests=enabled
quarkus.tls.key-store.pem.0.cert=/opt/app/tls/tls.crt
quarkus.tls.key-store.pem.0.key=/opt/app/tls/tls.key
```

This gives the requested server contract:

```text
HTTP  :8080
HTTPS :8443
```

The server healthcheck deliberately uses the HTTPS listener:

```text
https://127.0.0.1:8443/q/health/ready
```

with certificate verification disabled because the certificate is self-signed.

## 9. Nginx HTTPS configuration

Nginx has two server blocks.

### Port 80

The local `/healthz` endpoint remains available for the container healthcheck; all normal paths redirect to HTTPS:

```nginx
location / {
    return 308 https://$host$request_uri;
}
```

### Port 443

Nginx loads:

```text
/etc/nginx/tls/tls.crt
/etc/nginx/tls/tls.key
```

and accepts TLS 1.2/1.3.

Angular calls relative same-origin REST URLs such as:

```text
/api/keys/public
/api/subscriptions
/api/notifications
```

Nginx forwards `/api/` to the **internal Docker hostname**:

```nginx
location /api/ {
    proxy_pass https://quarkus-gatelink-webpush-server:8443/;
    proxy_ssl_server_name on;
    proxy_ssl_name quarkus-gatelink-webpush-server;
    proxy_ssl_verify off;
}
```

The trailing `/` on `proxy_pass` is intentional:

```text
Browser request:
https://host/api/keys/public

Nginx upstream:
https://quarkus-gatelink-webpush-server:8443/keys/public
```

`proxy_ssl_verify off` is limited to this self-signed internal-TLS mode. With an internal CA, configure Nginx to trust that CA and enable upstream certificate verification.

Nginx forwards:

```text
Host
X-Real-IP
X-Forwarded-For
X-Forwarded-Host
X-Forwarded-Proto=https
X-Forwarded-Port=443
```

## 10. Angular SPA routing

The HTTPS server uses:

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

so direct browser refreshes on routes such as:

```text
/dashboard
/settings
```

return the Angular application instead of Nginx 404.

`/api/` is a separate, more specific location and never falls through to Angular `index.html`.

Angular Service Worker metadata remains revalidatable while hashed application assets are cached aggressively.

## 11. Build the source stack

```bash
docker compose build
```

### Server image

Build stage:

```text
maven:3.9.13-eclipse-temurin-21-noble
```

Runtime:

```text
eclipse-temurin:21-jre-noble
```

The image installs `curl` for health checks and `openssl` for first-start self-signed certificate generation.

The Docker build creates an uber-JAR and copies it to:

```text
/opt/app/app.jar
```

The runtime process is non-root `10001:10001`.

### UI image

Build stage:

```text
node:24.18.0-bookworm-slim
```

Runtime:

```text
nginx:1.28.3-alpine
```

`ng serve` is not used in production. Nginx serves the compiled Angular files and has OpenSSL available for certificate generation.

## 12. Start the stack

```bash
docker compose up -d --wait
docker compose ps
```

Expected dependency chain:

```text
postgres healthy
      |
      v
quarkus-gatelink-webpush-server healthy
      |
      v
quarkus-gatelink-webpush-ui healthy
```

## 13. Verify the stack

### Normal UI path

```bash
curl -I http://localhost/
curl -k https://localhost/healthz
curl -k https://localhost/api/q/health/ready
curl -k https://localhost/dashboard
```

Expected:

- HTTP `/` returns `308`;
- HTTPS `/healthz` returns `200`;
- `/api/q/health/ready` reaches Quarkus through Nginx over internal HTTPS;
- `/dashboard` returns Angular `index.html`.

### Direct Quarkus path

```bash
curl http://localhost:8080/q/health/ready
curl -k https://localhost:8443/q/health/ready
```

Both are intentionally enabled.

### Fixed container names/hostnames

```bash
docker inspect -f '{{.Name}} {{.Config.Hostname}}' quarkus-gatelink-webpush-ui
docker inspect -f '{{.Name}} {{.Config.Hostname}}' quarkus-gatelink-webpush-server
```

Expected values:

```text
/quarkus-gatelink-webpush-ui quarkus-gatelink-webpush-ui
/quarkus-gatelink-webpush-server quarkus-gatelink-webpush-server
```

### Internal DNS resolution

```bash
docker compose exec quarkus-gatelink-webpush-ui \
  getent hosts quarkus-gatelink-webpush-server
```

## 14. PostgreSQL

GateLink uses the pinned Docker Official Image:

```text
postgres:18.4
```

PostgreSQL is not published on the host.

The named volume:

```text
postgres_data
```

is mounted at `/var/lib/postgresql` as required by the official PostgreSQL 18 image layout.

GateLink stores browser PushSubscriptions (`endpoint`, `p256dh`, `auth`) in PostgreSQL. PostgreSQL is **not** a notification queue, notification-history store, delivery acknowledgement store or VAPID private-key store.

## 15. Server logs and temporary files

Source Compose bind mounts:

```text
./quarkus-gatelink-webpush-server/runtime/logs -> /opt/app/logs
./quarkus-gatelink-webpush-server/runtime/tmp  -> /opt/app/tmp
```

`JAVA_TOOL_OPTIONS` points `java.io.tmpdir` to `/opt/app/tmp`.

Quarkus logs both to stdout/stderr and:

```text
/opt/app/logs/application.log
```

File logging rotates at 50 MB, retains 10 backups and compresses rotated files.

View logs:

```bash
docker compose logs -f quarkus-gatelink-webpush-server
tail -f quarkus-gatelink-webpush-server/runtime/logs/application.log
```

## 16. Security properties

Server:

- runtime image `eclipse-temurin:21-jre-noble`;
- non-root `10001:10001`;
- `no-new-privileges:true`;
- no Docker socket mount;
- external application config mounted read-only;
- VAPID/database secrets supplied by environment/secret source;
- HTTP 8080 and HTTPS 8443 are deliberately published per the requested operations standard.

UI:

- Angular is compiled before runtime;
- runtime does not contain Node build tooling;
- normal browser traffic is HTTPS 443;
- HTTP 80 redirects to HTTPS;
- `/api/` uses encrypted HTTPS to the Quarkus container.

Self-signed mode provides encryption but not public trust. Replace it with certificates issued by an organizational/public CA when appropriate.

## 17. Stop the stack

Preserve all named volumes:

```bash
docker compose down
```

Do **not** routinely run:

```bash
docker compose down -v
```

because `-v` deletes PostgreSQL data and generated TLS material.

## 18. Update only the server

```bash
docker compose build quarkus-gatelink-webpush-server
docker compose up -d --no-deps quarkus-gatelink-webpush-server
docker compose ps quarkus-gatelink-webpush-server
```

If only `deploy/server/application.properties` changes:

```bash
docker compose restart quarkus-gatelink-webpush-server
```

## 19. Update only the UI

```bash
docker compose build quarkus-gatelink-webpush-ui
docker compose up -d --no-deps quarkus-gatelink-webpush-ui
docker compose ps quarkus-gatelink-webpush-ui
```

## 20. Full update

```bash
docker compose build
docker compose up -d --wait
docker compose ps
```

## 21. Useful operational commands

```bash
# Validate the resolved Compose model
docker compose config

# Status
docker compose ps

# All logs
docker compose logs -f

# Server logs
docker compose logs -f quarkus-gatelink-webpush-server

# UI logs
docker compose logs -f quarkus-gatelink-webpush-ui

# psql without publishing 5432
docker compose exec postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"

# Server health from inside the container over HTTPS
docker compose exec quarkus-gatelink-webpush-server \
  curl -kfsS https://127.0.0.1:8443/q/health/ready

# Inspect generated certificates
docker compose exec quarkus-gatelink-webpush-server \
  openssl x509 -in /opt/app/tls/tls.crt -noout -subject -issuer -dates -ext subjectAltName

docker compose exec quarkus-gatelink-webpush-ui \
  openssl x509 -in /etc/nginx/tls/tls.crt -noout -subject -issuer -dates -ext subjectAltName
```

## 22. Persistence matrix

| Data | Mechanism | Survives container/image replacement? |
| --- | --- | --- |
| browser subscriptions | `postgres_data` named volume | yes |
| UI TLS certificate/key | `ui_tls` named volume | yes |
| server TLS certificate/key | `server_tls` named volume | yes |
| server application logs | source Compose bind mount; Release bundle uses named volume | yes |
| server temp files | source Compose bind mount; Release bundle uses named volume | yes |
| external Quarkus config | `deploy/server/application.properties` | yes |
| Quarkus JAR | server image | replaced with image |
| Angular application | UI image | replaced with image |
| VAPID identity | external env/secret source | must remain stable in production |

## 23. Ready-to-run GitHub Release bundle

GitHub Releases contain a Compose archive with:

```text
gatelink-compose-<version>/
├── compose.yaml
├── .env.example
├── README.md
├── server/
│   ├── app.jar
│   ├── application.properties
│   ├── Dockerfile
│   └── docker-entrypoint.sh
└── ui/
    ├── dist/
    ├── nginx.conf
    ├── Dockerfile
    └── docker-entrypoint.sh
```

The target machine needs Docker and Docker Compose only. Maven and Node.js are not needed.

Start a release bundle with:

```bash
cp .env.example .env
# edit secrets / TLS SANs
docker compose up -d --build --wait
```

The release workflow smoke-tests the generated archive using the same HTTP/HTTPS ports, service names, DNS resolution and certificate generation described in this document.

## 24. Deployment checklist

Before exposing a deployment, verify:

- Docker/Compose resolves `quarkus-gatelink-webpush-server` from the UI container;
- UI HTTPS `443` works;
- UI HTTP `80` redirects to HTTPS;
- `/api/...` reaches Quarkus through `https://quarkus-gatelink-webpush-server:8443/`;
- direct Quarkus `8080` and `8443` behave as intended for the environment;
- `UI_TLS_SAN` contains the browser-facing hostname/IP;
- `SERVER_TLS_SAN` contains any hostname/IP used for direct TLS access plus the internal server DNS name;
- the self-signed UI certificate has been explicitly trusted/accepted, or replaced by a trusted certificate;
- production VAPID keys are stable;
- `POSTGRES_PASSWORD` is changed;
- OIDC is enabled/configured before protected administrative endpoints are exposed;
- PostgreSQL port 5432 is not published;
- no private TLS key or other secret is committed to the repository;
- backups exist before database/application upgrades.
