# Docker / Docker Compose deployment

This guide documents the production-oriented GateLink Compose layout and runtime contract.

## Layout

```text
quarkus-gatelink-webpush/
├── docker-compose.yml
├── .env.example
├── config/
│   └── nginx.conf
├── data/
│   └── quarkus-gatelink-webpush-server/
│       ├── logs/
│       └── tmp/
├── quarkus-gatelink-webpush-server/
│   ├── Dockerfile
│   ├── docker-entrypoint.sh
│   ├── application.properties
│   ├── pom.xml
│   ├── README.md
│   └── src/
└── quarkus-gatelink-webpush-ui/
    ├── Dockerfile
    ├── docker-entrypoint.sh
    ├── package.json
    ├── angular.json
    ├── README.md
    └── src/
```

The roles are intentionally simple:

- `docker-compose.yml`: single source for the source-tree deployment;
- `config/nginx.conf`: Nginx TLS/reverse-proxy configuration;
- `data/quarkus-gatelink-webpush-server/`: host-visible Quarkus logs and temp data;
- `quarkus-gatelink-webpush-server/application.properties`: external Docker/production Quarkus config;
- `quarkus-gatelink-webpush-server/src/main/resources/application.properties`: normal Maven/dev/test config.

There is no `deploy/` directory and no second checked-in Compose topology.

## Runtime topology

```text
Browser
   |
   | HTTPS :443
   v
quarkus-gatelink-webpush-ui
Angular + Nginx
   |
   | /api/*
   | HTTPS :8443
   | Docker DNS
   v
quarkus-gatelink-webpush-server
Quarkus / Java 21
   |
   | JDBC
   v
postgres :5432
```

The server also makes outbound HTTPS requests to browser Push Services.

## Fixed service/container/hostname identities

The service name, `container_name` and hostname are identical for the two application containers:

```text
quarkus-gatelink-webpush-ui
quarkus-gatelink-webpush-server
```

Nginx therefore reaches Quarkus at:

```text
https://quarkus-gatelink-webpush-server:8443/
```

Do not replace that internal Docker hostname with `localhost`.

## Published ports

| Service | Host port | Protocol | Purpose |
| --- | ---: | --- | --- |
| UI | `80` | HTTP | redirect normal traffic to HTTPS |
| UI | `443` | HTTPS | normal browser entry point |
| Quarkus | `8080` | HTTP | direct REST/operations access |
| Quarkus | `8443` | HTTPS | direct TLS REST/operations access |
| PostgreSQL | none | PostgreSQL | internal only |

## Prepare the host

```bash
cp .env.example .env

mkdir -p data/quarkus-gatelink-webpush-server/logs
mkdir -p data/quarkus-gatelink-webpush-server/tmp

sudo chown -R 10001:10001 data/quarkus-gatelink-webpush-server/logs
sudo chown -R 10001:10001 data/quarkus-gatelink-webpush-server/tmp
sudo chmod 0750 data/quarkus-gatelink-webpush-server/logs
sudo chmod 0750 data/quarkus-gatelink-webpush-server/tmp

chmod 0644 quarkus-gatelink-webpush-server/application.properties
chmod 0644 config/nginx.conf
```

The server image runs as UID/GID `10001:10001`, so its bind-mounted data directories must be writable by that identity.

## Configure `.env`

At minimum configure production database and VAPID values:

```text
POSTGRES_PASSWORD
WEBPUSH_VAPID_PUBLIC_KEY
WEBPUSH_VAPID_PRIVATE_KEY
WEBPUSH_VAPID_SUBJECT
```

The three-container stack does not include an identity provider. The example therefore defaults to:

```text
OIDC_ENABLED=false
OIDC_CLIENT_ID=quarkus-gatelink-webpush-server
```

For protected administrative endpoints, enable OIDC and provide the real issuer.

## TLS

No private TLS key is committed to Git.

The UI entrypoint generates:

```text
/etc/nginx/tls/tls.crt
/etc/nginx/tls/tls.key
```

stored in Docker volume `ui_tls`.

The server entrypoint generates:

```text
/opt/app/tls/tls.crt
/opt/app/tls/tls.key
```

stored in Docker volume `server_tls`.

Default identities come from `.env.example`:

```text
TLS_DAYS=825
UI_TLS_COMMON_NAME=quarkus-gatelink-webpush-ui
UI_TLS_SAN=DNS:quarkus-gatelink-webpush-ui,DNS:localhost,IP:127.0.0.1
SERVER_TLS_COMMON_NAME=quarkus-gatelink-webpush-server
SERVER_TLS_SAN=DNS:quarkus-gatelink-webpush-server,DNS:localhost,IP:127.0.0.1
```

Add the actual deployment DNS name/IP to the SAN list before the first start when clients use another hostname.

Self-signed TLS encrypts traffic but is not automatically trusted. Browser/operator clients must explicitly trust or accept the certificate.

## Quarkus runtime config

The Docker/production external config is:

```text
quarkus-gatelink-webpush-server/application.properties
```

Compose mounts it read-only as:

```text
/opt/app/config/application.properties
```

It enables both listeners:

```properties
quarkus.http.port=8080
quarkus.http.ssl-port=8443
quarkus.http.insecure-requests=enabled
```

and points the datasource to:

```text
jdbc:postgresql://postgres:5432/${DB_NAME}
```

The file at `src/main/resources/application.properties` remains the normal Maven/dev/test configuration and is not the Docker override.

## Nginx proxy config

The canonical proxy file is:

```text
config/nginx.conf
```

Compose also bind-mounts that file read-only into:

```text
/etc/nginx/conf.d/default.conf
```

The API block sends requests to internal HTTPS:

```nginx
location /api/ {
    proxy_pass https://quarkus-gatelink-webpush-server:8443/;
    proxy_ssl_server_name on;
    proxy_ssl_name quarkus-gatelink-webpush-server;
    proxy_ssl_verify off;
}
```

`proxy_ssl_verify off` is specific to the self-signed internal mode. With an internal CA, configure the CA and enable verification.

## Build and start

```bash
docker compose -f docker-compose.yml config
docker compose -f docker-compose.yml build
docker compose -f docker-compose.yml up -d --wait
docker compose -f docker-compose.yml ps
```

Expected startup order:

```text
postgres healthy
   -> quarkus-gatelink-webpush-server healthy
      -> quarkus-gatelink-webpush-ui healthy
```

## Verify

HTTP redirect:

```bash
curl -I http://localhost/
```

HTTPS UI and reverse proxy:

```bash
curl -k https://localhost/healthz
curl -k https://localhost/api/q/health/ready
curl -k https://localhost/dashboard
```

Direct Quarkus:

```bash
curl http://localhost:8080/q/health/ready
curl -k https://localhost:8443/q/health/ready
```

Docker identities:

```bash
docker inspect -f '{{.Name}} {{.Config.Hostname}}' quarkus-gatelink-webpush-ui
docker inspect -f '{{.Name}} {{.Config.Hostname}}' quarkus-gatelink-webpush-server
```

Internal DNS:

```bash
docker compose -f docker-compose.yml exec quarkus-gatelink-webpush-ui \
  getent hosts quarkus-gatelink-webpush-server
```

## Data and persistence

PostgreSQL uses the named Docker volume:

```text
postgres_data
```

TLS uses:

```text
server_tls
ui_tls
```

Quarkus logs and temp files use host bind mounts:

```text
./data/quarkus-gatelink-webpush-server/logs -> /opt/app/logs
./data/quarkus-gatelink-webpush-server/tmp  -> /opt/app/tmp
```

Follow logs:

```bash
docker compose -f docker-compose.yml logs -f quarkus-gatelink-webpush-server
tail -f data/quarkus-gatelink-webpush-server/logs/application.log
```

## Stop and update

Stop while preserving named volumes:

```bash
docker compose -f docker-compose.yml down
```

Do not routinely add `-v`; that removes PostgreSQL and TLS volumes.

Update only the server:

```bash
docker compose -f docker-compose.yml build quarkus-gatelink-webpush-server
docker compose -f docker-compose.yml up -d --no-deps quarkus-gatelink-webpush-server
```

If only `quarkus-gatelink-webpush-server/application.properties` changes:

```bash
docker compose -f docker-compose.yml restart quarkus-gatelink-webpush-server
```

Update only the UI/proxy:

```bash
docker compose -f docker-compose.yml build quarkus-gatelink-webpush-ui
docker compose -f docker-compose.yml up -d --no-deps quarkus-gatelink-webpush-ui
```

If only `config/nginx.conf` changes, the bind mount already exposes the new file; reload or restart the UI container:

```bash
docker compose -f docker-compose.yml restart quarkus-gatelink-webpush-ui
```

## Release bundle

The release workflow does not keep a second source topology under `deploy/`.

Instead it assembles a ready-to-run bundle at release time with the same shape:

```text
quarkus-gatelink-webpush-compose-<version>/
├── docker-compose.yml
├── .env.example
├── README.md
├── config/
│   └── nginx.conf
├── data/
│   └── quarkus-gatelink-webpush-server/
│       ├── logs/
│       └── tmp/
├── quarkus-gatelink-webpush-server/
│   ├── app.jar
│   ├── application.properties
│   ├── Dockerfile
│   └── docker-entrypoint.sh
└── quarkus-gatelink-webpush-ui/
    ├── Dockerfile
    ├── docker-entrypoint.sh
    └── dist/
```

The target host needs Docker/Compose only; it does not need Maven or Node.js.
