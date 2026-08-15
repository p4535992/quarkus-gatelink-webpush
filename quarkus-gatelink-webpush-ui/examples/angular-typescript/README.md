# Angular + TypeScript example

This directory shows how an Angular application can integrate with GateLink without copying the framework-free JavaScript implementation.

It is a **drop-in example**, not a complete Angular workspace.

The integration uses standard Angular APIs:

- `HttpClient` from `@angular/common/http` for GateLink REST;
- `SwPush` from `@angular/service-worker` for browser Push Subscription management;
- a standalone Angular component for subscribe/unsubscribe UI.

Official references:

- Angular Push Notifications: https://angular.dev/ecosystem/service-workers/push-notifications
- Angular `SwPush`: https://angular.dev/api/service-worker/SwPush
- Angular Service Worker setup: https://angular.dev/ecosystem/service-workers/getting-started

## Architecture

```text
Angular component
      |
      v
GateLinkWebPushService
      |
      +---------------- HttpClient ----------------+
      |                                            |
      | GET /keys/public                           | POST /subscriptions
      | DELETE /subscriptions/{endpoint}           |
      v                                            v
quarkus-gatelink-webpush-server ------------------------> PostgreSQL
      ^
      |
      | VAPID public key
      |
Angular SwPush
      |
      | requestSubscription({ serverPublicKey })
      v
Angular Service Worker / Browser Push API
      |
      v
Browser Push Service
```

## 1. Enable Angular Service Worker support

In an existing Angular application, add Angular PWA/Service Worker support using normal Angular tooling and make sure `@angular/service-worker` is installed.

The example assumes Angular's `ngsw-worker.js` is registered.

## 2. Copy the service

Copy:

```text
gatelink-webpush.service.ts
```

into your Angular application, for example:

```text
src/app/webpush/gatelink-webpush.service.ts
```

The service performs:

```text
subscribe()
   |
   | GET /keys/public
   v
serverPublicKey
   |
   | SwPush.requestSubscription({ serverPublicKey })
   v
PushSubscription
   |
   | POST /subscriptions
   v
GateLink + PostgreSQL
```

If a browser subscription already exists, the service re-registers it with GateLink. This is useful after database restore/replacement while the browser still owns its Push Subscription.

## 3. Configure Angular providers

`app.config.example.ts` demonstrates:

```ts
provideHttpClient()
provideServiceWorker('ngsw-worker.js', ...)
{
  provide: GATELINK_WEBPUSH_BASE_URL,
  useValue: 'http://localhost:8080'
}
```

Use an environment-specific backend URL in a real application.

## 4. Use the component or service

`push-demo.component.ts` is a small standalone example. You can also inject `GateLinkWebPushService` directly and call:

```ts
await push.subscribe();
await push.unsubscribe();
```

The service exposes Angular `SwPush` streams:

```ts
push.subscription$
push.messages$
push.notificationClicks$
```

## Subscribe sequence

```text
1. User clicks Subscribe
2. Angular calls GET /keys/public
3. GateLink returns only the VAPID public key
4. Angular calls SwPush.requestSubscription(...)
5. Browser asks for notification permission
6. Browser Push Service creates a PushSubscription
7. Angular POSTs subscription.toJSON() to /subscriptions
8. GateLink persists endpoint + p256dh + auth in PostgreSQL
```

Conceptual subscription JSON:

```json
{
  "endpoint": "https://push-service.example/...",
  "keys": {
    "p256dh": "...",
    "auth": "..."
  }
}
```

The frontend never receives the VAPID private key.

## Unsubscribe sequence

```text
1. Angular reads SwPush.subscription
2. endpoint is encoded as Base64URL
3. DELETE /subscriptions/{encodedEndpoint}
4. GateLink removes the PostgreSQL record
5. Angular calls SwPush.unsubscribe()
```

The example removes the GateLink record before removing the browser subscription. If the server call fails, the browser subscription remains intact rather than silently creating inconsistent state.

## Local development

Start PostgreSQL from the repository root:

```bash
docker compose up -d postgres
```

Start GateLink:

```bash
cd quarkus-gatelink-webpush-server
mvn quarkus:dev
```

Use:

```text
http://localhost:8080
```

as `GATELINK_WEBPUSH_BASE_URL`.

The development profile allows local cross-origin browser calls; production must use an explicit CORS allow-list.

## Service Worker caveat

Angular Service Worker behavior is normally exercised from a production build rather than normal development-mode behavior. Test Push/Service Worker functionality using the application's production Service Worker configuration, served from `localhost` or HTTPS.

## Web Push delivery

The backend implements only the current standardized delivery path:

```text
Angular SwPush / browser subscription
        |
        | POST /subscriptions
        v
quarkus-gatelink-webpush-server
        |
        | RFC 8291 / RFC 8188 aes128gcm
        | RFC 8292 VAPID
        v
Browser Push Service
        |
        v
Angular Service Worker
```

There is no legacy `aesgcm` compatibility path.

Standards:

- RFC 8030: https://www.rfc-editor.org/rfc/rfc8030.html
- RFC 8188: https://www.rfc-editor.org/rfc/rfc8188.html
- RFC 8291: https://www.rfc-editor.org/rfc/rfc8291.html
- RFC 8292: https://www.rfc-editor.org/rfc/rfc8292.html
- W3C Push API: https://www.w3.org/TR/push-api/
