# `quarkus-gatelink-webpush-ui`

This folder is the production Angular + TypeScript frontend for GateLink.

It is built during the Docker image build and served by Nginx. Production does **not** use `ng serve` or BrowserSync.

See also:

- [`../docs/docker-deployment.md`](../docs/docker-deployment.md) for Docker/Nginx deployment;
- [`../docs/operator-guide.md`](../docs/operator-guide.md) for the complete Web Push lifecycle.

## Runtime architecture

```text
Browser
   |
   | http(s)://frontend/
   v
Nginx :80
   |
   +-- Angular static files
   |
   `-- /api/* -----------------> backend:8080
                                  Quarkus
```

The browser never uses Docker service names. It only talks to the same origin that served Angular.

Angular calls GateLink through:

```text
/api
```

Nginx resolves the Compose hostname `backend` and removes the `/api/` prefix before proxying.

Example:

```text
Browser:  /api/keys/public
Nginx:    http://backend:8080/keys/public
Quarkus:  GET /keys/public
```

## Angular stack

The frontend uses:

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
nginx:1.28.3-alpine
        |
        v
/usr/share/nginx/html
```

Build only this image from the repository root:

```bash
docker compose build frontend
```

Run/update only this service:

```bash
docker compose up -d --no-deps frontend
```

## Browser subscription flow

```text
User
  |
  | Subscribe
  v
Angular
  |
  | GET /api/keys/public
  v
Nginx
  |
  | GET /keys/public
  v
GateLink
  |
  | public VAPID key
  v
Angular SwPush
  |
  | requestSubscription
  v
Browser Push Service
  |
  | PushSubscription(endpoint, p256dh, auth)
  v
Angular
  |
  | POST /api/subscriptions
  v
GateLink -> PostgreSQL
```

The frontend never receives the VAPID private key.

## `GateLinkWebPushService`

The production Angular service uses a fixed same-origin base:

```ts
private readonly baseUrl = '/api';
```

Subscription sequence:

1. verify Angular Service Worker / Push API availability;
2. reuse an existing subscription if one already exists;
3. request `/api/keys/public`;
4. call `SwPush.requestSubscription({ serverPublicKey })`;
5. POST `subscription.toJSON()` to `/api/subscriptions`.

Unsubscribe sequence:

1. obtain current browser subscription;
2. Base64URL-encode the Push Service endpoint;
3. DELETE `/api/subscriptions/{encodedEndpoint}`;
4. only after server-side removal succeeds, call `SwPush.unsubscribe()`.

That order prevents silently losing the browser-side reference while GateLink still believes the endpoint is registered.

## Service Worker and HTTPS

Angular Service Worker is enabled in production builds.

Browser Push API and Service Workers require a secure context. Therefore:

```text
http://localhost     suitable for local development
https://...          required for real production browser use
```

The Compose Nginx image serves HTTP directly; a real deployment should terminate TLS either in Nginx with deployment-specific certificates or, more commonly, at an upstream ingress/reverse proxy/load balancer.

## Nginx API proxy

The relevant configuration is:

```nginx
location /api/ {
    proxy_pass http://backend:8080/;
}
```

The slash after `8080/` is intentional. It replaces the matching `/api/` prefix.

```text
/api/subscriptions -> /subscriptions
/api/q/health      -> /q/health
```

The proxy also forwards:

```text
Host
X-Real-IP
X-Forwarded-For
X-Forwarded-Proto
```

`/api` without a trailing slash receives a redirect to `/api/`, so it cannot fall into Angular routing.

## Angular SPA routing

Nginx uses:

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

Therefore direct refreshes of routes such as:

```text
/dashboard
/settings
```

return Angular `index.html` rather than Nginx 404.

The `/api/` location is handled separately and is not affected by the SPA fallback.

## Frontend healthcheck

Nginx exposes:

```text
GET /healthz
```

which returns HTTP 200 and `ok`.

Compose checks this endpoint locally inside the frontend container.

## Local Angular development

For frontend-only development you can still run:

```bash
npm install
npm start
```

However the production architecture is the Docker/Nginx path. A local `ng serve` session needs its own development proxy configuration or an alternate API arrangement; it does not use Docker DNS hostname `backend` directly from the browser.

## Integration examples

Copy-ready framework examples remain under:

```text
examples/angular-typescript/
```

The example service now also defaults to `/api`, matching the production Nginx layout.

## Security rules

Never put these in Angular source, runtime config downloaded by the browser, or generated bundles:

- VAPID private key;
- PostgreSQL credentials;
- confidential OIDC client secrets;
- other users' stored PushSubscription secrets.

The browser only needs the public VAPID key returned by GateLink.

## HTTPS runtime

The UI service/container/hostname is `quarkus-gatelink-webpush-ui`. Nginx listens on `443` for normal user traffic and keeps port `80` for redirect plus the local health endpoint. A self-signed certificate is generated on first start and persisted at `/etc/nginx/tls`.

`/api/` is proxied to `https://quarkus-gatelink-webpush-server:8443/` using Docker DNS; the browser never needs the internal server hostname.
