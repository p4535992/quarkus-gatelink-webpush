# `quarkus-gatelink-webpush-ui`

This module is the production Angular + TypeScript UI for GateLink. Angular is compiled during the Docker build and served by Nginx; production does **not** use `ng serve`.

Read also:

- [`../docs/docker-deployment.md`](../docs/docker-deployment.md) — Docker/HTTPS deployment;
- [`../docs/operator-guide.md`](../docs/operator-guide.md) — complete Web Push lifecycle.

## Runtime identity

Inside Docker, the Compose service name, container name and hostname are all:

```text
quarkus-gatelink-webpush-ui
```

Normal browser traffic uses HTTPS 443:

```text
Browser
   |
   | HTTPS :443
   v
quarkus-gatelink-webpush-ui
Angular + Nginx
   |
   | /api/* over HTTPS :8443
   | Docker DNS hostname
   v
quarkus-gatelink-webpush-server
Quarkus
```

The browser never sees or needs Docker service names. It only talks to the origin that served Angular.

## Published ports

```text
80   HTTP  -> redirects normal requests to HTTPS
443  HTTPS -> normal browser/user entry point
```

Nginx keeps a cheap container-local HTTP `/healthz` endpoint on port 80 for the Docker healthcheck; ordinary HTTP paths redirect with `308`.

## Angular stack

The UI uses:

- Angular 22;
- TypeScript 6;
- Angular Router;
- Angular `HttpClient`;
- Angular Service Worker / `SwPush`;
- production AOT/build output;
- Nginx runtime.

Important files:

```text
Dockerfile
docker-entrypoint.sh
nginx.conf
package.json
angular.json
tsconfig.json
tsconfig.app.json
ngsw-config.json
src/
├── index.html
├── main.ts
├── style.css
└── app/
    ├── app.component.ts
    ├── app.config.ts
    ├── app.routes.ts
    ├── gatelink-webpush.service.ts
    └── push-page.component.ts
```

## Docker build

```text
node:24.18.0-bookworm-slim
        |
        | npm install
        | ng build --configuration production
        v
dist/gatelink-webpush-ui/browser
        |
        v
nginx:1.28.3-alpine + OpenSSL
        |
        v
/usr/share/nginx/html
```

Build only this image:

```bash
docker compose build quarkus-gatelink-webpush-ui
```

Run/update only this service:

```bash
docker compose up -d --no-deps quarkus-gatelink-webpush-ui
```

## Self-signed UI certificate

On first container start, `docker-entrypoint.sh` creates:

```text
/etc/nginx/tls/tls.crt
/etc/nginx/tls/tls.key
```

if they are not already present in the `ui_tls` Docker volume.

Defaults:

```text
UI_TLS_COMMON_NAME=quarkus-gatelink-webpush-ui
UI_TLS_SAN=DNS:quarkus-gatelink-webpush-ui,DNS:localhost,IP:127.0.0.1
TLS_DAYS=825
```

If users open the UI through another hostname/IP, include it in `UI_TLS_SAN` before the first start.

The certificate is self-signed, therefore the browser must explicitly trust or accept it. No TLS private key is committed to Git.

## Nginx HTTPS behavior

Nginx listens on 443 with:

```text
TLS 1.2 / TLS 1.3
/etc/nginx/tls/tls.crt
/etc/nginx/tls/tls.key
```

Angular calls GateLink only with relative same-origin paths:

```text
/api/keys/public
/api/subscriptions
/api/notifications
```

The API upstream is:

```nginx
location /api/ {
    proxy_pass https://quarkus-gatelink-webpush-server:8443/;
    proxy_ssl_server_name on;
    proxy_ssl_name quarkus-gatelink-webpush-server;
    proxy_ssl_verify off;
}
```

The trailing slash is intentional:

```text
Browser:  https://host/api/keys/public
Nginx:    https://quarkus-gatelink-webpush-server:8443/keys/public
Quarkus:  GET /keys/public
```

`proxy_ssl_verify off` is used because the internal Quarkus certificate is self-signed. The connection is encrypted, but this mode does not authenticate the upstream certificate chain. A deployment with an internal CA can configure Nginx to trust that CA and enable verification.

Nginx also forwards:

```text
Host
X-Real-IP
X-Forwarded-For
X-Forwarded-Host
X-Forwarded-Proto=https
X-Forwarded-Port=443
```

## Browser subscription flow

```text
User
  |
  | opens HTTPS UI and chooses Subscribe
  v
Angular
  |
  | GET /api/keys/public
  v
Nginx :443
  |
  | HTTPS to quarkus-gatelink-webpush-server:8443/keys/public
  v
GateLink
  |
  | public VAPID key
  v
Angular / SwPush
  |
  | requestSubscription
  v
Browser Push API
  |
  | registration with vendor Push Service
  v
PushSubscription(endpoint, p256dh, auth)
  |
  v
Angular
  |
  | POST /api/subscriptions
  v
Nginx :443 -> Quarkus :8443 -> PostgreSQL
```

The UI never receives the VAPID private key.

## `GateLinkWebPushService`

The production Angular service uses:

```ts
private readonly baseUrl = '/api';
```

Subscription sequence:

1. verify Angular Service Worker / Push API availability;
2. reuse an existing browser subscription if present;
3. request `/api/keys/public`;
4. call `SwPush.requestSubscription({ serverPublicKey })`;
5. POST `subscription.toJSON()` to `/api/subscriptions`.

Unsubscribe sequence:

1. obtain the current browser subscription;
2. Base64URL-encode its Push Service endpoint;
3. DELETE `/api/subscriptions/{encodedEndpoint}`;
4. after server-side removal succeeds, call `SwPush.unsubscribe()`.

This order avoids removing the local browser reference while GateLink still has the subscription stored.

## Service Worker and secure context

Angular Service Worker is enabled in production builds. The production Compose path now provides HTTPS directly in Nginx, so normal browser use is:

```text
https://<host>/
```

For localhost-only Angular development, browser secure-context exceptions may apply, but that is separate from the production Docker path.

## Angular SPA routing

Nginx uses:

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

so direct refreshes of:

```text
/dashboard
/settings
```

return Angular `index.html` rather than Nginx 404.

`/api/` is handled separately and never falls through to the SPA fallback.

Service Worker bootstrap/manifest files are revalidated; hashed Angular application assets can be cached aggressively.

## Healthcheck

Inside the UI container:

```text
GET http://127.0.0.1/healthz -> 200 ok
```

From the host/operator side HTTPS is also available:

```bash
curl -k https://localhost/healthz
```

## Local Angular development

Frontend-only development can still use:

```bash
npm install
npm start
```

A local `ng serve` process needs its own development API proxy/arrangement. Browser JavaScript must never try to resolve the Docker-only hostname `quarkus-gatelink-webpush-server` directly.

## Integration examples

Copy-ready examples are under:

```text
examples/angular-typescript/
```

They use `/api`, matching the Nginx production layout.

## Security rules

Never put these in Angular source, generated static bundles or browser-downloadable runtime configuration:

- VAPID private key;
- PostgreSQL credentials;
- confidential OIDC client secrets;
- another user's stored PushSubscription secrets;
- TLS private keys.

The browser only needs the public VAPID key returned by GateLink.
