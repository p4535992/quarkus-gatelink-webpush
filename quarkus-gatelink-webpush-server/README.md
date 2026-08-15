# `quarkus-gatelink-webpush-server`

Java 21 / Quarkus Web Push server.

Maven coordinates:

```xml
<groupId>com.quarkus</groupId>
<artifactId>quarkus-gatelink-webpush-server</artifactId>
```

## Docker identity

```text
Compose service: quarkus-gatelink-webpush-server
container_name:  quarkus-gatelink-webpush-server
hostname:        quarkus-gatelink-webpush-server
```

Ports:

```text
HTTP  :8080
HTTPS :8443
```

Normal browser traffic reaches the server through Nginx:

```text
https://quarkus-gatelink-webpush-server:8443/
```

## Files

```text
quarkus-gatelink-webpush-server/
├── Dockerfile
├── docker-entrypoint.sh
├── application.properties
├── pom.xml
├── README.md
└── src/
```

There are two distinct Quarkus configuration roles:

```text
application.properties
    Docker/production external configuration
    mounted at /opt/app/config/application.properties

src/main/resources/application.properties
    normal Maven/dev/test configuration
```

Keeping the Docker override at the module root prevents it from being auto-loaded by ordinary Maven/dev/test runs while still keeping the server's deployment configuration next to the server module.

## Runtime paths

```text
/opt/app/app.jar
/opt/app/config/application.properties
/opt/app/logs/
/opt/app/tmp/
/opt/app/tls/tls.crt
/opt/app/tls/tls.key
```

The container runs as:

```text
UID:GID = 10001:10001
```

Host-visible data is outside the source module:

```text
../data/quarkus-gatelink-webpush-server/logs
../data/quarkus-gatelink-webpush-server/tmp
```

## TLS

`docker-entrypoint.sh` creates a self-signed certificate/key in `/opt/app/tls` when the `server_tls` volume is empty.

Defaults:

```text
SERVER_TLS_COMMON_NAME=quarkus-gatelink-webpush-server
SERVER_TLS_SAN=DNS:quarkus-gatelink-webpush-server,DNS:localhost,IP:127.0.0.1
TLS_DAYS=825
```

## PostgreSQL

Inside Compose:

```text
jdbc:postgresql://postgres:5432/${DB_NAME}
```

PostgreSQL stores the durable PushSubscription registry:

```text
endpoint
p256dh
auth
```

It is not a notification queue or VAPID-key store.

## Web Push

Payload encryption uses:

```text
nl.martijndwars:web-push:5.1.2
Encoding.AES128GCM
```

GateLink owns VAPID JWT creation, HTTP delivery, persistence, OIDC/RBAC, rate limiting, metrics and tracing.

There is no legacy `aesgcm` path.

## REST endpoints

| Method | Path | Access |
| --- | --- | --- |
| `GET` | `/keys/public` | public |
| `POST` | `/subscriptions` | public + validation |
| `DELETE` | `/subscriptions/{endpoint}` | public + validation |
| `GET` | `/subscriptions` | `gatelink-admin` |
| `DELETE` | `/subscriptions` | `gatelink-admin` |
| `POST` | `/notifications` | `gatelink-admin` + rate limit |

Management endpoints:

```text
/q/health
/q/health/ready
/q/metrics
/q/openapi
```

## Build and test

From the repository root:

```bash
docker compose -f docker-compose.yml up -d postgres
```

Then:

```bash
cd quarkus-gatelink-webpush-server
mvn clean verify
mvn quarkus:dev
```

Build only the production server image:

```bash
docker compose -f docker-compose.yml build quarkus-gatelink-webpush-server
```

Read [`../docs/docker-deployment.md`](../docs/docker-deployment.md) for the complete Docker deployment contract.
