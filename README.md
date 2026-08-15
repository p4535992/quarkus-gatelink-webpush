# Quarkus GateLink Web Push

GateLink is a Java 21 / Quarkus Web Push gateway with PostgreSQL and an Angular UI served by Nginx.

The implementation is modern-only:

- RFC 8030 Web Push;
- RFC 8188 / RFC 8291 `aes128gcm`;
- RFC 8292 VAPID;
- no obsolete `aesgcm` delivery path.

## Runtime architecture

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
   | Docker DNS: quarkus-gatelink-webpush-server
   v
quarkus-gatelink-webpush-server
Quarkus / Java 21
   |
   | JDBC
   v
postgres :5432
```

The fixed Compose service, container and hostname values are:

```text
quarkus-gatelink-webpush-ui
quarkus-gatelink-webpush-server
```

Published ports:

| Component | Port | Purpose |
| --- | ---: | --- |
| UI | `80` | HTTP redirect to HTTPS; local `/healthz` |
| UI | `443` | normal user/browser entry point |
| Quarkus | `8080` | direct HTTP REST/operations access |
| Quarkus | `8443` | direct HTTPS REST/operations access |
| PostgreSQL | `5432` internal only | subscription registry |

## Repository layout

```text
.
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

`config/nginx.conf` is the reverse-proxy configuration used by the UI container.

`data/quarkus-gatelink-webpush-server/` contains host-visible runtime data for Quarkus logs and temporary files.

`quarkus-gatelink-webpush-server/application.properties` is the external Docker/production configuration. Compose mounts it read-only at `/opt/app/config/application.properties`.

The normal Quarkus source configuration remains under `src/main/resources/application.properties` for Maven/dev/test use.

## Quick start

```bash
cp .env.example .env

sudo chown -R 10001:10001 data/quarkus-gatelink-webpush-server/logs
sudo chown -R 10001:10001 data/quarkus-gatelink-webpush-server/tmp

docker compose -f docker-compose.yml build
docker compose -f docker-compose.yml up -d --wait
docker compose -f docker-compose.yml ps
```

Normal browser entry:

```text
https://localhost/
```

Direct Quarkus access:

```text
http://localhost:8080/
https://localhost:8443/
```

The supplied TLS certificates are generated automatically and are self-signed. Add deployment DNS names/IP addresses to `UI_TLS_SAN` and `SERVER_TLS_SAN` before first start when needed.

## Nginx proxy

The canonical proxy file is:

```text
config/nginx.conf
```

Angular uses same-origin `/api/...` URLs. Nginx forwards them to:

```text
https://quarkus-gatelink-webpush-server:8443/
```

For the self-signed internal certificate, Nginx currently uses `proxy_ssl_verify off`. A deployment with an internal CA can enable upstream verification.

## Persistence

| Data | Persistence |
| --- | --- |
| browser PushSubscriptions | Docker volume `postgres_data` |
| server TLS certificate/key | Docker volume `server_tls` |
| UI TLS certificate/key | Docker volume `ui_tls` |
| Quarkus logs | `data/quarkus-gatelink-webpush-server/logs/` |
| Quarkus temp files | `data/quarkus-gatelink-webpush-server/tmp/` |
| runtime Quarkus config | `quarkus-gatelink-webpush-server/application.properties` |
| proxy config | `config/nginx.conf` |

PostgreSQL stores `endpoint`, `p256dh` and `auth` for browser subscriptions. It is not a notification queue or delivery-history database.

## Server Maven identity

```xml
<groupId>com.quarkus</groupId>
<artifactId>quarkus-gatelink-webpush-server</artifactId>
```

The current server uses Java 21 and Quarkus 3.33.3 LTS.

## Web Push implementation

GateLink uses:

```text
nl.martijndwars:web-push:5.1.2
```

with:

```java
Encoding.AES128GCM
```

GateLink itself owns subscription persistence, VAPID identity/JWT creation, JDK `HttpClient` delivery, OIDC/RBAC, rate limiting, metrics and tracing.

The maximum plaintext payload is 3993 UTF-8 bytes.

## Release artifacts

A GitHub Release generates:

```text
quarkus-gatelink-webpush-server-<version>.jar
quarkus-gatelink-webpush-ui-<version>.zip
quarkus-gatelink-webpush-ui-<version>.tar.gz
quarkus-gatelink-webpush-compose-<version>.zip
quarkus-gatelink-webpush-compose-<version>.tar.gz
SHA256SUMS
```

The Compose bundle mirrors the same runtime layout: `docker-compose.yml`, `config/`, `data/`, `quarkus-gatelink-webpush-server/` and `quarkus-gatelink-webpush-ui/`.

## Documentation

- [`docs/docker-deployment.md`](docs/docker-deployment.md) — Docker/Compose deployment and operations
- [`docs/operator-guide.md`](docs/operator-guide.md) — complete runtime/Web Push lifecycle
- [`docs/webpush-java.md`](docs/webpush-java.md) — Java Web Push implementation boundary
- [`docs/integration-examples.md`](docs/integration-examples.md) — integration examples
- [`quarkus-gatelink-webpush-server/README.md`](quarkus-gatelink-webpush-server/README.md) — server details
- [`quarkus-gatelink-webpush-ui/README.md`](quarkus-gatelink-webpush-ui/README.md) — UI details
