# `quarkus-gatelink-webpush-ui`

This folder contains the **browser side** of GateLink.

The application under `src/` is a small framework-free JavaScript demo that keeps the browser Push API visible. Framework integration examples live under `examples/`; the first is **Angular + TypeScript** in [`examples/angular-typescript/`](examples/angular-typescript/).

## What the UI does

The browser client:

- registers a Service Worker;
- asks the user for notification permission;
- obtains the public VAPID key from `quarkus-gatelink-server`;
- asks the browser Push API to create a Push Subscription;
- registers/unregisters that subscription through GateLink REST;
- receives push events through the Service Worker.

## Architecture

```text
+---------+
|  User   |
+----+----+
     |
     v
+----+--------------------------------------+
| Browser + quarkus-gatelink-webpush-ui    |
| vanilla demo OR Angular application       |
| Service Worker                            |
+----+--------------------------+-----------+
     |                          |
     | GateLink REST API        | Browser Push API
     v                          v
+----+----------------------+  +-----------------------+
| quarkus-gatelink-server  |  | Browser Push Service  |
| Quarkus REST             |  | FCM / Mozilla / etc. |
+------------+-------------+  +-----------------------+
             |
             | subscriptions
             v
       +-----+------+
       | PostgreSQL |
       +------------+
```

There are two distinct network relationships: the UI calls **GateLink** for application APIs, while the browser Push API talks to the browser vendor's **Push Service** to create the Push Subscription.

## Subscription flow

```text
User clicks Subscribe
        |
        v
Browser UI
        |
        | GET /keys/public
        v
quarkus-gatelink-server
        |
        | VAPID public key
        v
Browser Push API / Angular SwPush
        |
        | subscribe
        v
Browser Push Service
        |
        | PushSubscription
        v
Browser UI
        |
        | POST /subscriptions
        v
quarkus-gatelink-server
        |
        v
PostgreSQL
```

The VAPID private key never belongs in frontend code.

## Framework-free demo

Important files:

| File / folder | Responsibility |
| --- | --- |
| `src/index.html` | browser entry page |
| `src/app.js` | bootstrap |
| `src/app/control/MicroService.js` | `fetch` wrapper for GateLink REST calls |
| `src/subscription/control/SubscriptionService.js` | subscribe/unsubscribe flow |
| `src/push-worker.js` | Service Worker receiving push events |
| `src/notifications/` | notification UI/control code |
| `src/subscription/` | subscription UI/control/entity code |

Backend default:

```text
http://localhost:8080
```

Override it before loading the modules:

```html
<script>
  globalThis.GATELINK_BASE_URI = 'https://gatelink.example.com';
</script>
```

Run the demo:

```bash
npm install -g browser-sync
./startBrowserSync.sh
```

Keep `quarkus-gatelink-server` running separately.

## Angular + TypeScript

See:

```text
examples/angular-typescript/
├── README.md
├── gatelink-webpush.service.ts
├── push-demo.component.ts
└── app.config.example.ts
```

The example uses standard Angular APIs:

```text
HttpClient
   +---- GET /keys/public
   +---- POST /subscriptions
   +---- DELETE /subscriptions/{endpoint}

SwPush (@angular/service-worker)
   +---- requestSubscription({ serverPublicKey })
   +---- subscription
   +---- unsubscribe()
   +---- notificationClicks / messages
```

Official Angular documentation:

- https://angular.dev/ecosystem/service-workers/push-notifications
- https://angular.dev/api/service-worker/SwPush
- https://angular.dev/ecosystem/service-workers

## GateLink REST contract used by frontends

| Browser action | GateLink request |
| --- | --- |
| load VAPID public key | `GET /keys/public` |
| register browser subscription | `POST /subscriptions` |
| unregister browser subscription | `DELETE /subscriptions/{base64urlEndpoint}` |

The framework never receives the VAPID private key.

## Secure-context requirement

Service Workers and the Push API require a secure browser context. Use HTTPS in production; browsers normally treat `http://localhost` as trustworthy for development.

## Current protocol

GateLink implements the current standardized Web Push path only:

```text
Browser PushSubscription
        |
        v
quarkus-gatelink-server
        |
        | RFC 8291 / RFC 8188
        | Content-Encoding: aes128gcm
        | RFC 8292 VAPID
        v
Browser Push Service
        |
        v
Service Worker
```

There is no legacy `aesgcm` compatibility layer.

Standards and browser references:

- W3C Push API: https://www.w3.org/TR/push-api/
- RFC 8030: https://www.rfc-editor.org/rfc/rfc8030.html
- RFC 8188: https://www.rfc-editor.org/rfc/rfc8188.html
- RFC 8291: https://www.rfc-editor.org/rfc/rfc8291.html
- RFC 8292: https://www.rfc-editor.org/rfc/rfc8292.html
- MDN Push API: https://developer.mozilla.org/en-US/docs/Web/API/Push_API
- MDN Service Worker API: https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API

## Java and TypeScript integration examples

See [`../docs/integration-examples.md`](../docs/integration-examples.md) for framework-neutral TypeScript, Angular `SwPush`, Java 21 `HttpClient`, and MicroProfile REST Client examples.
