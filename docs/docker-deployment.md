# Docker / Docker Compose deployment

This guide describes the production-oriented three-container GateLink stack:

```text
Browser
   |
   | http(s)://host/
   v
frontend :80
Nginx + Angular
   |
   | /api/*
   v
backend :8080
Quarkus / Java 21
   |
   | JDBC
   v
postgres :5432
PostgreSQL 18
```

The stack uses **Docker and Docker Compose**, not Podman. Container-to-container communication always uses Compose service names; no Docker IP addresses are hardcoded.

The backend also makes outbound HTTPS requests to browser Push Services (FCM, Mozilla/vendor services), and production administrative calls can use an external OIDC provider. The dedicated Compose bridge network is therefore private to the stack for inbound service discovery, but it is intentionally **not** declared `internal: true`, because GateLink needs outbound network access.

## 1. Final repository layout

```text
quarkus-gatelink-webpush/
├── compose.yaml
├── .env.example
├── .gitignore
│
├── quarkus-gatelink-server/
│   ├── Dockerfile
│   ├── .dockerignore
│   ├── pom.xml
│   ├── config/
│   │   └── application.properties
│   ├── runtime/
│   │   ├── logs/
│   │   │   └── .gitkeep
│   │   └── tmp/
│   │       └── .gitkeep
│   └── src/
│
└── quarkus-gatelink-webpush-ui/
    ├── Dockerfile
    ├── .dockerignore
    ├── nginx.conf
    ├── package.json
    ├── angular.json
    ├── tsconfig.json
    ├── tsconfig.app.json
    ├── ngsw-config.json
    └── src/
```

Source code and application binaries are baked into images. Production does **not** bind-mount Java source, the Quarkus JAR, Angular source or Angular build output.

## 2. Prepare the host

From the repository root:

```bash
cp .env.example .env
mkdir -p quarkus-gatelink-server/runtime/logs
mkdir -p quarkus-gatelink-server/runtime/tmp
```

The Quarkus image runs as the explicit identity:

```text
UID 10001
GID 10001
```

The bind-mounted runtime directories must therefore be writable by `10001:10001`:

```bash
sudo chown -R 10001:10001 quarkus-gatelink-server/runtime/logs
sudo chown -R 10001:10001 quarkus-gatelink-server/runtime/tmp
sudo chmod 0750 quarkus-gatelink-server/runtime/logs
sudo chmod 0750 quarkus-gatelink-server/runtime/tmp
```

The external Quarkus configuration only needs to be readable by the container:

```bash
chmod 0644 quarkus-gatelink-server/config/application.properties
```

Do not put production secrets directly in that properties file. Database password, VAPID keys and OIDC values are supplied through environment variables.

## 3. Configure `.env`

Edit the copied `.env` before production use.

At minimum change:

```text
POSTGRES_PASSWORD
WEBPUSH_VAPID_PUBLIC_KEY
WEBPUSH_VAPID_PRIVATE_KEY
WEBPUSH_VAPID_SUBJECT
```

The supplied example leaves VAPID keys empty so a local stack can start without generating keys first. GateLink then creates a temporary VAPID pair. That is only suitable for development: production requires a stable VAPID identity so existing browser subscriptions remain usable across restarts.

The requested three-container stack does not include an identity provider. Therefore `.env.example` defaults to:

```text
OIDC_ENABLED=false
```

This lets the stack boot by itself and supports the public browser subscription flow. To use the protected administrative endpoints in production, set:

```text
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://id.example.com/realms/gatelink
OIDC_CLIENT_ID=gatelink-server
```

and use a token carrying role `gatelink-admin`.

## 4. Build everything

```bash
docker compose build
```

### Backend build

The backend Dockerfile is multi-stage:

```text
maven:3.9.13-eclipse-temurin-21-noble
        |
        | mvn package
        v
app-runner.jar
        |
        v
eclipse-temurin:21-jre-noble
        |
        v
/opt/app/app.jar
```

The Maven project is configured as a Quarkus `uber-jar`, with final artifact name `app`. The runtime image contains only the built application and JRE, not Maven or Java source.

The Java process starts exactly as:

```text
java -jar /opt/app/app.jar
```

`JAVA_TOOL_OPTIONS` sets:

```text
-Djava.io.tmpdir=/opt/app/tmp
-Djava.util.logging.manager=org.jboss.logmanager.LogManager
```

### Frontend build

The frontend Dockerfile is also multi-stage:

```text
node:24.18.0-bookworm-slim
        |
        | npm install
        | ng build --configuration production
        v
dist/gatelink-webpush-ui/browser
        |
        v
nginx:1.28.3-alpine
        |
        v
/usr/share/nginx/html
```

`ng serve` is not used in production.

## 5. Start the stack

```bash
docker compose up -d
```

Then inspect status:

```bash
docker compose ps
```

Expected dependency order:

```text
postgres healthy
      |
      v
backend healthy
      |
      v
frontend healthy
```

Docker Compose waits for dependencies using `condition: service_healthy`.

The public entry point is:

```text
http://localhost:8081
```

or the value of `FRONTEND_PORT` in `.env`.

The backend and PostgreSQL are intentionally **not** published on host ports.

## 6. Service networking

Compose creates a dedicated bridge network named from the stack and network key `gatelink`.

Service DNS names are:

```text
frontend
backend
postgres
```

The important internal URLs are:

```text
Nginx -> http://backend:8080/
Quarkus -> jdbc:postgresql://postgres:5432/<database>
```

Do not replace these with `localhost`.

`localhost` is correct only when a process checks itself inside the same container, such as the healthchecks against `127.0.0.1`.

## 7. Nginx `/api/` reverse proxy

Angular calls GateLink using relative URLs:

```text
/api/keys/public
/api/subscriptions
/api/notifications
```

Nginx contains:

```nginx
location /api/ {
    proxy_pass http://backend:8080/;
}
```

The trailing slash after `backend:8080/` is intentional. Nginx replaces the matching `/api/` location prefix with `/`.

Therefore:

```text
Browser request:  /api/keys/public
Quarkus receives: /keys/public

Browser request:  /api/subscriptions
Quarkus receives: /subscriptions
```

`location = /api` redirects to `/api/`, preventing the bare API path from being handled by Angular's SPA fallback.

Nginx also forwards:

```text
Host
X-Real-IP
X-Forwarded-For
X-Forwarded-Proto
```

## 8. Angular SPA routing

Angular routes such as:

```text
/
/dashboard
/settings
```

must work after a browser refresh. Nginx therefore uses:

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

The `/api/` location is more specific and is evaluated separately, so API requests never fall through to `index.html`.

## 9. External Quarkus configuration

The host file:

```text
./quarkus-gatelink-server/config/application.properties
```

is mounted read-only as:

```text
/opt/app/config/application.properties
```

The image uses:

```text
WORKDIR /opt/app
```

so Quarkus sees the file at its standard external location:

```text
$PWD/config/application.properties
```

This external file overrides corresponding configuration packaged in the JAR. Changing runtime configuration therefore does not require rebuilding the backend image; restart the backend container after changing values.

## 10. PostgreSQL

The database uses the explicitly pinned image:

```text
postgres:18.4-alpine3.24
```

It is not exposed on a host port.

Persistent storage is the named volume:

```text
postgres_data
```

mounted at:

```text
/var/lib/postgresql
```

For the official PostgreSQL 18 image, `PGDATA` lives under the version-specific subdirectory inside this mount.

The healthcheck uses `pg_isready` with the configured database/user.

GateLink stores browser PushSubscriptions here. PostgreSQL is a durable subscription registry, **not** a Web Push message queue or notification history.

## 11. Backend logs

GateLink logs to both destinations:

1. stdout/stderr for container logging;
2. `/opt/app/logs/application.log` for persistent host-visible logging.

View container logs:

```bash
docker compose logs -f backend
```

View the persistent file:

```bash
tail -f quarkus-gatelink-server/runtime/logs/application.log
```

Current file rotation:

```text
maximum file size: 50 MB
backup index:      10
rotated suffix:    date + .gz compression
```

## 12. Temporary files

The host directory:

```text
./quarkus-gatelink-server/runtime/tmp
```

is mounted as:

```text
/opt/app/tmp
```

and `java.io.tmpdir` points there. This keeps temporary JVM/application files outside the immutable application content and makes them inspectable from the host when necessary.

## 13. Healthchecks

### PostgreSQL

```text
pg_isready
```

### Quarkus

```text
GET /q/health/ready
```

This project already includes `quarkus-smallrye-health`, so the Quarkus health endpoint is available.

### Frontend

Nginx serves an inexpensive local endpoint:

```text
GET /healthz -> 200 ok
```

The healthcheck uses BusyBox `wget` already present in the Alpine runtime image; no extra frontend runtime package is installed only for health checking.

## 14. Security properties of the stack

Backend:

- runtime base is `eclipse-temurin:21-jre-noble`, not UBI;
- runs as `10001:10001`;
- `no-new-privileges:true`;
- external config is mounted read-only;
- database/VAPID passwords are not copied into the image;
- no Docker socket mount;
- no `privileged: true`;
- backend port 8080 is not published to the host;
- PostgreSQL port 5432 is not published to the host;
- graceful shutdown period is 30 seconds.

Frontend:

- Angular is built ahead of time;
- runtime contains Nginx and static artifacts, not Node tooling;
- browser API calls are same-origin through `/api/`.

Production Web Push still requires HTTPS at the browser-facing edge. `http://localhost` is suitable for local browser development, but a real deployment should terminate TLS at Nginx or at an upstream reverse proxy/load balancer.

## 15. View logs

All services:

```bash
docker compose logs -f
```

One service:

```bash
docker compose logs -f frontend
docker compose logs -f backend
docker compose logs -f postgres
```

## 16. Stop the stack

Preserve the database volume:

```bash
docker compose down
```

Delete containers **and all PostgreSQL data intentionally**:

```bash
docker compose down -v
```

Do not use `-v` during a normal application update.

## 17. Update only the backend

After pulling/changing backend source:

```bash
docker compose build backend
docker compose up -d --no-deps backend
```

Check health/logs:

```bash
docker compose ps backend
docker compose logs -f backend
```

If the external `application.properties` changes but code does not, an image rebuild is unnecessary:

```bash
docker compose restart backend
```

## 18. Update only the frontend

```bash
docker compose build frontend
docker compose up -d --no-deps frontend
```

Then:

```bash
docker compose ps frontend
docker compose logs -f frontend
```

## 19. Full rebuild/restart

```bash
docker compose build
docker compose up -d
docker compose ps
```

## 20. Useful operational commands

```bash
# Render/validate the resolved Compose model
docker compose config

# Container status
docker compose ps

# Follow all logs
docker compose logs -f

# Inspect the persistent PostgreSQL volume
docker volume ls

# Open psql without publishing port 5432
docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"

# Check Quarkus health from inside its container
docker compose exec backend curl -fsS http://127.0.0.1:8080/q/health/ready

# Check Nginx -> Quarkus proxy from the host
curl -fsS http://localhost:${FRONTEND_PORT:-8081}/api/q/health
```

When shell variables are not exported, `docker compose exec postgres` can instead use the concrete values from `.env`.

## 21. What persists across container replacement

| Data | Persistence mechanism | Survives image/container replacement? |
| --- | --- | --- |
| PostgreSQL subscriptions | Docker named volume `postgres_data` | yes |
| Quarkus application log files | host bind mount `runtime/logs` | yes |
| Quarkus temporary files | host bind mount `runtime/tmp` | yes, intentionally |
| external Quarkus config | host bind mount `config/application.properties` | yes |
| Quarkus JAR | image | rebuilt/replaced with image |
| Angular application | frontend image | rebuilt/replaced with image |
| VAPID identity | environment/secret source | must remain stable in production |

## 22. Validation checklist

Before deployment verify:

- `docker compose config` succeeds;
- `postgres`, `backend`, and `frontend` service names match internal hostnames;
- JDBC uses `postgres:5432`, not `localhost`;
- Nginx uses `backend:8080`, not `localhost`;
- backend config is mounted read-only;
- UID/GID `10001:10001` can write `runtime/logs` and `runtime/tmp`;
- PostgreSQL uses `postgres_data` and is not published on the host;
- `/dashboard` and `/settings` refresh to Angular rather than returning Nginx 404;
- `/api/...` reaches Quarkus and never returns Angular `index.html`;
- production VAPID keys are stable;
- OIDC is enabled/configured before administrative endpoints are exposed;
- no secrets are baked into Docker images;
- production browser traffic uses HTTPS.
