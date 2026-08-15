# `quarkus-gatelink-webpush-ui`

This folder contains the browser side of GateLink.

The framework-free demo lives under `src/`. Framework examples live under `examples/`, including Angular + TypeScript.

> For the complete operator-oriented user → browser → GateLink → PostgreSQL → Push Service → Service Worker flow, see [`../docs/operator-guide.md`](../docs/operator-guide.md).

## What the browser side is responsible for

The browser application:

- registers the Service Worker;
- asks the user for notification permission;
- obtains the GateLink public VAPID key;
- asks the browser Push API to create a PushSubscription;
- sends that PushSubscription to GateLink;
- removes its GateLink subscription record when unsubscribing;
- receives final push events in the Service Worker;
- displays or handles the browser notification.

The browser never receives the VAPID private key.

## Browser-side architecture

```text
+---------+
| User    |
+----+----+
     |
     | subscribe / interact
     v
+----+--------------------------------------+
| Browser + GateLink UI                    |
|                                           |
| page / Angular component                  |
| Push API / PushManager / SwPush           |
| Service Worker                            |
+-----------+--------------------+----------+
            |                    |
            | GateLink REST      | browser-native Push API
            v                    v
+-----------+----------+   +-----+----------------------+
| GateLink server      |   | Browser vendor Push Service|
| Java / Quarkus       |   | FCM / Mozilla / vendor    |
+-----------+----------+   +-----+----------------------+
            |                    |
            | PostgreSQL         | later push delivery
            v                    v
      +-----+------+       +-----+----------------+
      | subscription|       | Browser Service Worker|
      | persistence |       +----------------------+
      +-------------+
```

There are two different network relationships:

1. the browser UI calls **GateLink** for application REST APIs;
2. the browser Push API talks to the browser vendor **Push Service** to create and maintain the browser subscription.

## Subscribe: step by step

```text
User
  |
  | clicks Subscribe
  v
Browser UI
  |
  +-- ensure Service Worker is registered/ready
  |
  +-- GET /keys/public ---------------------------> GateLink
  |                                                   |
  |<---------------- public VAPID key ----------------+
  |
  +-- request notification permission
  |
  +-- PushManager.subscribe / SwPush.requestSubscription
  |             |
  |             v
  |       Browser Push Service
  |             |
  |<----- PushSubscription(endpoint, p256dh, auth)
  |
  +-- POST /subscriptions ------------------------> GateLink
                                                      |
                                                      +--> validate
                                                      +--> PostgreSQL upsert
```

Exact browser sequence:

1. User opens the application.
2. The application registers the Service Worker.
3. User chooses to enable notifications.
4. UI calls `GET /keys/public` on GateLink.
5. GateLink returns its public VAPID application-server key.
6. The UI converts that key to the representation required by the browser/framework if necessary.
7. The browser requests notification permission if needed.
8. The UI calls the browser Push API with `userVisibleOnly: true` and the GateLink public VAPID key.
9. The browser itself contacts its vendor Push Service.
10. The Push Service returns a browser PushSubscription.
11. The subscription contains:
    - `endpoint` — vendor Push Service URL;
    - `p256dh` — browser public encryption key;
    - `auth` — browser Web Push auth secret.
12. The UI serializes the subscription.
13. The UI calls `POST /subscriptions` on GateLink.
14. GateLink validates and persists the subscription in PostgreSQL.
15. GateLink returns HTTP `204` for a valid registration.
16. The browser is now registered both with its vendor Push Service and with GateLink's database.

These are separate pieces of state:

```text
Browser / Push Service subscription
        !=
GateLink PostgreSQL record
```

The frontend is responsible for keeping its browser subscription lifecycle aligned with GateLink registration/unregistration.

## Notification receive flow: step by step

The browser is not called directly by GateLink.

```text
GateLink
   |
   | encrypted RFC 8030 Web Push request
   v
Browser Push Service
   |
   | vendor-specific delivery
   v
Browser
   |
   | push event
   v
Service Worker
   |
   | process payload
   | show notification
   v
User
```

Exact sequence after the server sends:

1. GateLink POSTs the encrypted message to the subscription endpoint.
2. The vendor Push Service accepts or rejects the request.
3. If accepted, the Push Service later delivers the message to the browser.
4. The browser wakes/routes the event to the registered Service Worker.
5. The Service Worker receives the `push` event.
6. The Service Worker reads the payload.
7. The Service Worker displays a user-visible notification or performs the configured push handling.
8. If the user clicks the notification, the Service Worker handles `notificationclick` / framework-specific click events.

A successful GateLink request does not mean the user has seen the notification; GateLink only knows about acceptance by the Push Service.

## Unsubscribe: step by step

```text
Browser UI
    |
    | current PushSubscription.endpoint
    |
    +-- Base64URL encode endpoint
    |
    +-- DELETE /subscriptions/{encodedEndpoint} ---> GateLink
    |                                                   |
    |                                                   `--> PostgreSQL delete
    |
    `-- browser Push API unsubscribe
```

1. UI obtains the current PushSubscription.
2. UI Base64URL-encodes its endpoint for the GateLink path parameter.
3. UI calls `DELETE /subscriptions/{encodedEndpoint}`.
4. GateLink removes the durable database record.
5. UI also performs the browser Push API unsubscribe lifecycle where appropriate.
6. Removing the PostgreSQL record alone does not magically cancel browser-vendor subscription state.

## REST calls made by the frontend

| Browser action | GateLink request | Authentication |
| --- | --- | --- |
| load application-server public key | `GET /keys/public` | public |
| register/update subscription | `POST /subscriptions` | public + validation |
| remove known subscription | `DELETE /subscriptions/{base64urlEndpoint}` | public + path validation |

Administrative calls such as listing all subscriptions or sending notifications are not frontend responsibilities and require OIDC `gatelink-admin`.

## Framework-free demo

Important files:

| File / folder | Responsibility |
| --- | --- |
| `src/index.html` | browser entry page |
| `src/app.js` | application bootstrap |
| `src/app/control/MicroService.js` | GateLink `fetch` wrapper |
| `src/subscription/control/SubscriptionService.js` | subscribe/unsubscribe orchestration |
| `src/push-worker.js` | Service Worker receiving push events |
| `src/notifications/` | notification UI/control code |
| `src/subscription/` | subscription UI/control/entity code |

Default GateLink backend:

```text
http://localhost:8080
```

Override it before loading modules:

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

Example files:

```text
examples/angular-typescript/
├── README.md
├── gatelink-webpush.service.ts
├── push-demo.component.ts
└── app.config.example.ts
```

Core Angular responsibilities:

```text
HttpClient
   +---- GET /keys/public
   +---- POST /subscriptions
   +---- DELETE /subscriptions/{endpoint}

SwPush
   +---- requestSubscription({ serverPublicKey })
   +---- subscription
   +---- unsubscribe()
   +---- messages
   `---- notificationClicks
```

A typical Angular subscription sequence is:

```ts
const serverPublicKey = await firstValueFrom(
  http.get(`${baseUrl}/keys/public`, { responseType: 'text' }),
);

const subscription = await swPush.requestSubscription({
  serverPublicKey: serverPublicKey.trim(),
});

await firstValueFrom(
  http.post(`${baseUrl}/subscriptions`, subscription.toJSON()),
);
```

## What must never be in frontend code

Do not put any of the following into the browser bundle:

- VAPID private key;
- production database credentials;
- OIDC client secrets intended for confidential server applications;
- PostgreSQL subscription data for other users.

The browser only needs the **public** VAPID key.

## Secure-context requirement

Service Workers and the Push API require a secure browser context.

Use HTTPS in production. Browsers normally treat `http://localhost` as a trustworthy development origin.

## Current Web Push protocol

The browser-facing application participates in the current standardized flow only:

```text
PushSubscription
      |
      v
GateLink
      |
      | AES128GCM encrypted payload
      | RFC 8292 VAPID
      v
Push Service
      |
      v
Service Worker
```

There is no GateLink legacy `aesgcm` compatibility layer.

## Related documentation

- [`../docs/operator-guide.md`](../docs/operator-guide.md) — complete end-to-end operational lifecycle.
- [`../docs/integration-examples.md`](../docs/integration-examples.md) — Java and TypeScript integration examples.
- [`../quarkus-gatelink-server/README.md`](../quarkus-gatelink-server/README.md) — server-side handling.
- [`../README.md`](../README.md) — repository overview and quick start.
