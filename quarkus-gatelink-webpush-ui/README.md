# `quarkus-gatelink-webpush-ui`

Angular + TypeScript UI served by Nginx.

## Docker identity

```text
Compose service: quarkus-gatelink-webpush-ui
container_name:  quarkus-gatelink-webpush-ui
hostname:        quarkus-gatelink-webpush-ui
```

Normal user access:

```text
HTTPS :443
```

Port `80` redirects ordinary requests to HTTPS and keeps the local `/healthz` health endpoint.

## Files

```text
quarkus-gatelink-webpush-ui/
├── Dockerfile
├── docker-entrypoint.sh
├── package.json
├── angular.json
├── README.md
└── src/
```

The Nginx/reverse-proxy configuration is intentionally outside the UI source module:

```text
../config/nginx.conf
```

Compose mounts it read-only at:

```text
/etc/nginx/conf.d/default.conf
```

The Docker build also copies the same file into the image as its default config.

## Runtime path

```text
Browser
   |
   | HTTPS :443
   v
quarkus-gatelink-webpush-ui
   |
   | /api/*
   | HTTPS :8443
   v
quarkus-gatelink-webpush-server
```

Angular only uses same-origin `/api/...` URLs and never resolves Docker hostnames in browser JavaScript.

## Nginx proxy

Canonical source:

```text
config/nginx.conf
```

API upstream:

```nginx
proxy_pass https://quarkus-gatelink-webpush-server:8443/;
```

The internal server certificate is self-signed, so the current proxy config uses `proxy_ssl_verify off`. An internal CA deployment can enable verification.

SPA routing uses:

```nginx
try_files $uri $uri/ /index.html;
```

so `/dashboard` and `/settings` work after browser refresh.

## TLS

The UI entrypoint creates:

```text
/etc/nginx/tls/tls.crt
/etc/nginx/tls/tls.key
```

inside Docker volume `ui_tls`.

Defaults:

```text
UI_TLS_COMMON_NAME=quarkus-gatelink-webpush-ui
UI_TLS_SAN=DNS:quarkus-gatelink-webpush-ui,DNS:localhost,IP:127.0.0.1
TLS_DAYS=825
```

## Angular build

Production build:

```text
node:24.18.0-bookworm-slim
    -> npm install
    -> ng build --configuration production
    -> dist/gatelink-webpush-ui/browser
    -> nginx:1.28.3-alpine
```

Build only the UI image:

```bash
docker compose -f docker-compose.yml build quarkus-gatelink-webpush-ui
```

## Browser Push flow

Angular:

1. loads the public VAPID key from `/api/keys/public`;
2. requests a browser PushSubscription with `SwPush`;
3. sends the subscription to `/api/subscriptions`;
4. receives later Push Service deliveries through the Service Worker.

The browser never receives the VAPID private key, database credentials or TLS private keys.

## Local development

```bash
npm install
npm start
```

`ng serve` is only for local development; production is the Docker/Nginx path.

Read [`../docs/docker-deployment.md`](../docs/docker-deployment.md) for the complete deployment model.
