# GateLink @VERSION@ - Docker Compose bundle

This archive contains prebuilt GateLink application artifacts plus small runtime-only Dockerfiles. Maven and Node.js are **not** required on the target host.

## Included

```text
compose.yaml
.env.example
backend/
  app.jar
  application.properties
  Dockerfile
frontend/
  dist/
  nginx.conf
  Dockerfile
```

The stack contains:

```text
Browser :8081 -> Nginx/Angular -> Quarkus :8080 -> PostgreSQL :5432
```

Only the frontend port is published on the host.

## Start

1. Copy the example environment file:

   ```bash
   cp .env.example .env
   ```

2. Edit `.env` before production use. At minimum change `POSTGRES_PASSWORD` and configure a stable VAPID key pair. Enable/configure OIDC when administrative endpoints must be protected by the external identity provider.

3. Build the two small runtime images and start the stack:

   ```bash
   docker compose up -d --build --wait
   ```

4. Verify it:

   ```bash
   curl --fail http://127.0.0.1:8081/healthz
   curl --fail http://127.0.0.1:8081/api/q/health/ready
   docker compose ps
   ```

Open `http://127.0.0.1:8081/` in a browser.

## Operations

View logs:

```bash
docker compose logs -f
```

Stop without deleting PostgreSQL data:

```bash
docker compose down
```

Stop and delete all named volumes, including PostgreSQL data:

```bash
docker compose down -v
```

The bundle uses named volumes for PostgreSQL data, Quarkus file logs and Quarkus temporary storage. This avoids host UID/GID preparation while the backend itself still runs as non-root UID/GID `10001:10001`.

## Upgrade

For a later GateLink release, extract the new bundle, copy/adapt your `.env`, then run:

```bash
docker compose up -d --build --wait
```

Keep a PostgreSQL backup before production upgrades.
