# GateLink operator guide

This guide explains **what happens at runtime, in which order, and which component talks to which other component**.

It is intentionally operational rather than API-centric. An operator should be able to follow a request from the user and browser, through GateLink and PostgreSQL, to the browser vendor Push Service and back to the Service Worker.

GateLink is a Java 21 / Quarkus application. Web Push payload encryption is delegated to the Java `nl.martijndwars:web-push` library with `Encoding.AES128GCM` selected explicitly. There is no legacy `aesgcm` compatibility path.

## 1. Components and responsibilities

```text
+----------------------+       +------------------------+
| User                 |       | Operator / admin       |
| clicks Subscribe     |       | sends notifications    |
| receives notification|       | monitors the service   |
+----------+-----------+       +-----------+------------+
           |                               |
           v                               | OIDC Bearer token
+----------+-------------------------------+------------+
| Browser / browser UI                                  |
|                                                       |
| - page / Angular application                          |
| - Push API / PushManager                              |
| - Service Worker                                      |
+----------+----------------------+---------------------+
           |                      |
           | GateLink REST        | browser Push API
           v                      v
+----------+-----------+   +------+----------------------+
| GateLink server      |   | Browser vendor Push Service|
| Java 21 / Quarkus    |   | FCM / Mozilla / vendor    |
|                      |   | infrastructure             |
| REST                 |   +------+----------------------+
| validation           |          |
| OIDC / RBAC          |          | Web Push delivery
| Web Push encryption  |          v
| VAPID                |   +------+----------------------+
| metrics / tracing    |   | Browser Service Worker     |
+----------+-----------+   +-----------------------------+
           |
           | JDBC / JPA
           v
+----------+-----------+
| PostgreSQL 18        |
| PushSubscription data|
+----------------------+
```

The important distinction is:

- the **browser UI calls GateLink** for application REST operations;
- the **browser Push API talks to the browser vendor Push Service** when creating a Push Subscription;
- **GateLink later talks to that Push Service**, not directly to the browser;
- the Push Service delivers the message to the browser's **Service Worker**.

## 2. What each component owns

| Component | Owns / knows | Does not own |
| --- | --- | --- |
| Browser UI | GateLink URL, public VAPID key, browser PushSubscription | VAPID private key |
| Browser Push API | subscription lifecycle and vendor Push Service registration | GateLink database |
| GateLink | REST API, VAPID key pair, validation, fan-out, encryption adapter, metrics | direct browser connection |
| PostgreSQL | `endpoint`, `p256dh`, `auth` for each subscription | VAPID private key |
| OIDC provider | admin identities and roles | browser PushSubscription data |
| Push Service | vendor delivery endpoint and browser delivery infrastructure | GateLink database |
| Service Worker | final browser-side push event | server-side secrets |

`p256dh` and `auth` are browser-generated Web Push key material and must be treated as sensitive subscription data even though they are not the GateLink VAPID private key.

## 3. Server startup: step by step

### Diagram

```text
Operator
   |
   | starts PostgreSQL
   v
PostgreSQL 18
   |
   | becomes healthy
   v
Operator
   |
   | starts Quarkus
   v
GateLink
   |
   +--> opens datasource connection -----------------> PostgreSQL
   |
   +--> Flyway validates/applies migrations ---------> PostgreSQL
   |
   +--> Hibernate validates Java mapping vs schema --> PostgreSQL
   |
   +--> loads configured VAPID key pair
   |       or generates a temporary pair if none exists
   |
   +--> initializes OIDC / security
   |
   +--> initializes metrics / OpenTelemetry
   |
   `--> exposes HTTP endpoints
```

### Exact sequence

1. The operator starts PostgreSQL.
2. PostgreSQL exposes database `gatelink`.
3. GateLink starts and creates the Quarkus PostgreSQL datasource.
4. Flyway checks `flyway_schema_history` and applies any pending versioned migrations.
5. Hibernate validates that the mapped entities match the database schema; Hibernate does not own schema creation.
6. GateLink initializes its VAPID identity.
7. If **both** `webpush.vapid.public-key` and `webpush.vapid.private-key` are configured, GateLink loads them.
8. If only one of the two VAPID keys is configured, startup fails.
9. If neither is configured, GateLink generates a temporary P-256 VAPID pair and logs a warning.
10. Quarkus initializes OIDC security, validation, metrics and tracing.
11. In development, Quarkus OIDC Dev Services can start Keycloak automatically; `alice` is configured with role `gatelink-admin`.
12. The service begins accepting REST requests.

### Operator warning: VAPID identity must be stable in production

PostgreSQL persists browser subscriptions across restarts, but a browser subscription was created for a specific application-server public key. Therefore production GateLink must use a **stable VAPID key pair**.

```text
PostgreSQL persistent + VAPID persistent   -> correct production setup
PostgreSQL persistent + VAPID regenerated  -> subscriptions remain in DB,
                                              but application identity changed
```

The automatically generated key pair is suitable for temporary development, not for a restart-safe production installation.

## 4. Browser subscription registration: step by step

This flow happens when a user enables notifications in the browser.

### Sequence diagram

```text
User        Browser UI       GateLink       Push API       Push Service       PostgreSQL
 |              |               |              |                |                 |
 | Subscribe    |               |              |                |                 |
 |------------->|               |              |                |                 |
 |              | GET /keys/public             |                |                 |
 |              |-------------->|              |                |                 |
 |              | public VAPID key             |                |                 |
 |              |<--------------|              |                |                 |
 |              | permission / subscribe       |                |                 |
 |              |----------------------------->|                |                 |
 |              |               |              | register       |                 |
 |              |               |              |--------------->|                 |
 |              |               |              | PushSubscription                 |
 |              |               |              |<---------------|                 |
 |              | PushSubscription             |                |                 |
 |              |<-----------------------------|                |                 |
 |              | POST /subscriptions          |                |                 |
 |              |-------------->|              |                |                 |
 |              |               | validate     |                |                 |
 |              |               |----------------------------------------------->|
 |              |               |          INSERT ... ON CONFLICT UPDATE          |
 |              |               |<-----------------------------------------------|
 |              | HTTP 204      |              |                |                 |
 |              |<--------------|              |                |                 |
```

### Exact sequence

1. The user opens the browser UI.
2. The browser registers or activates the Service Worker used for push events.
3. The user chooses to enable/subscribe to notifications.
4. The UI calls `GET /keys/public` on GateLink.
5. GateLink reads its current VAPID key pair from the in-memory key store.
6. GateLink returns **only the public VAPID key**, in unpadded Base64URL form.
7. The browser asks the user for notification permission if permission has not already been granted.
8. The UI passes the public VAPID key to the browser Push API (`PushManager` or Angular `SwPush`).
9. The browser Push API contacts the browser vendor Push Service.
10. The Push Service creates or returns a subscription associated with this browser and application-server key.
11. The browser receives a `PushSubscription` containing at least:
    - `endpoint`: vendor Push Service URL;
    - `p256dh`: browser public encryption key;
    - `auth`: browser authentication secret.
12. The browser UI sends that object to GateLink with `POST /subscriptions`.
13. Before touching PostgreSQL, Jakarta Validation checks the request.
14. GateLink requires an absolute HTTPS endpoint.
15. GateLink requires canonical unpadded Base64URL key material.
16. `p256dh` must decode to a 65-octet uncompressed P-256 public key.
17. `auth` must decode to exactly 16 octets.
18. Invalid input is rejected with HTTP `400` and is not stored.
19. Valid input reaches `SubscriptionsStore`.
20. PostgreSQL executes an atomic upsert keyed by `endpoint`:

```sql
INSERT INTO push_subscriptions (endpoint, p256dh, auth)
VALUES (...)
ON CONFLICT (endpoint) DO UPDATE SET
    p256dh = EXCLUDED.p256dh,
    auth = EXCLUDED.auth;
```

21. Registering the same endpoint again refreshes its keys instead of creating a duplicate.
22. GateLink returns HTTP `204`.
23. The subscription is now durable and survives a GateLink restart.

## 5. What is stored in PostgreSQL

```text
push_subscriptions
+----------+----------------------------------------------+
| endpoint | TEXT PRIMARY KEY                             |
| p256dh   | TEXT NOT NULL                                |
| auth     | TEXT NOT NULL                                |
+----------+----------------------------------------------+
```

The database does **not** need the VAPID private key. VAPID identity is server configuration, not subscription data.

To inspect registered endpoints locally:

```bash
docker compose exec postgres psql -U gatelink -d gatelink
```

```sql
SELECT endpoint FROM push_subscriptions ORDER BY endpoint;
```

## 6. Sending a notification: step by step

This is the administrative fan-out flow.

### Sequence diagram

```text
Admin        OIDC          GateLink        PostgreSQL      web-push Java      Push Service
 |             |              |                 |                |                 |
 | get token   |              |                 |                |                 |
 |------------>|              |                 |                |                 |
 | token       |              |                 |                |                 |
 |<------------|              |                 |                |                 |
 | POST /notifications        |                 |                |                 |
 | Authorization: Bearer ...  |                 |                |                 |
 |--------------------------->|                 |                |                 |
 |             |              | validate token / role            |                 |
 |             |              | enforce 20/min rate limit        |                 |
 |             |              | validate payload                 |                 |
 |             |              | SELECT subscriptions             |                 |
 |             |              |---------------->|                |                 |
 |             |              | subscriptions  |                |                 |
 |             |              |<----------------|                |                 |
 |             |              | for each subscription            |                 |
 |             |              |------------------------------->|                 |
 |             |              |         AES128GCM body          |                 |
 |             |              |<-------------------------------|                 |
 |             |              | VAPID JWT + HTTP POST -------------------------->|
 |             |              |                                      HTTP status |
 |             |              |<-------------------------------------------------|
 |             |              | record Micrometer status                            |
 | HTTP 204    |              |                 |                |                 |
 |<---------------------------|                 |                |                 |
```

### Exact sequence

1. An administrator or trusted backend obtains an OIDC access token.
2. The token must map to role `gatelink-admin`.
3. The caller sends `POST /notifications` with `Content-Type: text/plain` and `Authorization: Bearer <token>`.
4. Quarkus authentication validates the token.
5. `@RolesAllowed("gatelink-admin")` checks authorization.
6. Missing authentication normally results in HTTP `401`; an authenticated identity without the role is rejected by authorization.
7. SmallRye Fault Tolerance enforces the current limit of **20 calls per minute** for the notification endpoint.
8. Calls over the limit receive HTTP `429`.
9. Jakarta Validation rejects a blank payload.
10. GateLink also verifies the UTF-8 encoded payload size.
11. The maximum plaintext payload is currently **3993 UTF-8 octets**.
12. GateLink increments `webpush.messages.forwarded` once for the fan-out request.
13. GateLink obtains the stable VAPID key pair from its key store.
14. GateLink loads the current subscription list from PostgreSQL.
15. The JPA persistence context is cleared before the read so the list reflects current database state.
16. GateLink processes every returned subscription.
17. For each subscription it constructs a Java notification object containing the stored subscription and the plaintext message.
18. `EncryptionService` validates the browser `auth` material again before encryption.
19. `EncryptionService` creates the library notification object for `nl.martijndwars:web-push`.
20. GateLink invokes the library with `Encoding.AES128GCM` explicitly.
21. The Java library performs the RFC 8291 / RFC 8188 payload cryptography and returns the encrypted body.
22. GateLink extracts the Push Service origin from the subscription endpoint and uses it as the VAPID JWT audience.
23. GateLink signs an ES256 VAPID JWT using the GateLink VAPID private key.
24. GateLink increments `webpush.push.attempts{push_service="..."}`.
25. GateLink sends an HTTPS POST to the exact subscription endpoint with:

```text
TTL: 2419200
Content-Encoding: aes128gcm
Authorization: vapid t=<JWT>, k=<VAPID-public-key>
Content-Type: application/octet-stream
```

26. `2419200` seconds is 28 days.
27. GateLink intentionally does not emit the obsolete Web Push delivery headers `Encryption` or `Crypto-Key`.
28. The Push Service returns an HTTP status.
29. GateLink records it in `webpush.responses{status="..."}`.
30. Any `2xx` status is considered successful by the internal Push Service client.
31. A non-`2xx` Push Service status is recorded but does **not** currently produce a per-subscription error response to the original admin caller.
32. If the loop completes without an exception, the REST endpoint returns HTTP `204`.

## 7. Push Service to browser delivery: step by step

After GateLink has successfully handed the encrypted message to the vendor Push Service:

1. GateLink is no longer in the direct delivery path.
2. The Push Service identifies the browser/device represented by the subscription endpoint.
3. The Push Service schedules or performs delivery according to the vendor/browser implementation and TTL.
4. The browser receives the Web Push message.
5. The registered Service Worker receives the `push` event.
6. The Service Worker processes the payload and normally displays a user-visible notification.
7. The user sees the notification.
8. Notification click handling is browser/UI logic, not GateLink server logic.

```text
GateLink
   |
   | encrypted Web Push HTTP POST
   v
Push Service
   |
   | vendor delivery channel
   v
Browser
   |
   | push event
   v
Service Worker
   |
   | showNotification(...)
   v
User
```

A successful response from the Push Service means the Push Service **accepted the request**. It is not the same thing as an application-level acknowledgement from the user or Service Worker.

## 8. Unsubscribe: step by step

GateLink's browser-facing unsubscribe removes the durable server record for a known endpoint.

```text
User / Browser UI
      |
      | knows its PushSubscription.endpoint
      |
      | Base64URL-encodes endpoint
      v
DELETE /subscriptions/{encodedEndpoint}
      |
      v
GateLink
      |
      | validate path format and size
      | Base64URL decode
      v
SubscriptionsStore
      |
      | DELETE by endpoint primary key
      v
PostgreSQL
```

1. The browser UI obtains the endpoint of its current Push Subscription.
2. The UI Base64URL-encodes the endpoint for use as a path segment.
3. The UI calls `DELETE /subscriptions/{encodedEndpoint}`.
4. GateLink rejects blank, oversized or non-Base64URL path input.
5. GateLink decodes the endpoint.
6. `SubscriptionsStore.remove(endpoint)` deletes that primary-key row from PostgreSQL.
7. This endpoint is intentionally browser-facing and currently does not require the admin role.
8. Browser Push API unsubscription and GateLink database removal are separate responsibilities; frontend code should perform the browser-side subscription lifecycle as well.

## 9. Administrative subscription operations

### List subscriptions

`GET /subscriptions` requires `gatelink-admin`.

Step by step:

1. Admin sends an OIDC Bearer token.
2. Quarkus authenticates the identity.
3. Role `gatelink-admin` is checked.
4. GateLink reads all current records from PostgreSQL.
5. GateLink returns an array containing the endpoint strings.

### Remove all subscriptions

`DELETE /subscriptions` also requires `gatelink-admin`.

1. Admin authenticates with OIDC.
2. GateLink checks `gatelink-admin`.
3. `SubscriptionsStore.removeAll()` deletes all rows.
4. Browser-side subscriptions are not remotely cancelled by this database operation; the GateLink server simply forgets them.

## 10. Security boundaries

```text
PUBLIC BROWSER FLOW
-------------------
GET    /keys/public
POST   /subscriptions
DELETE /subscriptions/{encodedEndpoint}

ADMINISTRATIVE FLOW
-------------------
GET    /subscriptions
DELETE /subscriptions
POST   /notifications
          |
          +-- OIDC authentication
          +-- gatelink-admin role
          `-- 20/minute rate limit for notification fan-out
```

The public endpoints are public because they participate in the browser subscription lifecycle. They are still strictly validated.

## 11. What the operator can monitor

### Health

```text
GET /q/health
```

Use this for service health checks.

### Prometheus / Micrometer metrics

```text
GET /q/metrics
```

GateLink-specific counters currently include:

| Metric | Meaning |
| --- | --- |
| `webpush.messages.forwarded` | notification fan-out requests accepted for processing |
| `webpush.push.attempts` with `push_service` tag | individual sends attempted by Push Service host |
| `webpush.responses` with `status` tag | HTTP status codes returned by Push Services |

Example operational interpretation:

```text
webpush.messages.forwarded rises
        |
        +-- webpush.push.attempts does not rise
        |      -> probably no subscriptions in PostgreSQL
        |
        +-- attempts rise, responses mostly 2xx
        |      -> Push Services are accepting requests
        |
        `-- responses contain 4xx/5xx
               -> inspect subscription validity / Push Service response pattern
```

### Logs

Development and test profiles use human-readable console logs. Production console logging is configured as structured JSON.

### Tracing

OpenTelemetry is enabled, including JDBC telemetry. Dev/test do not attempt OTLP export unless configured.

### OpenAPI

```text
GET /q/openapi
GET /q/swagger-ui    # dev/test
```

## 12. HTTP outcomes an operator should recognize

| Situation | Expected behavior |
| --- | --- |
| valid browser subscription | `POST /subscriptions` -> `204` |
| malformed endpoint/key/auth | `POST /subscriptions` -> `400` |
| admin endpoint without identity | normally `401` |
| authenticated caller without required role | authorization rejected |
| notification rate limit exceeded | `429` |
| blank / too-large notification | `400` |
| no registered subscriptions | notification call completes without Push Service sends |
| Push Service returns non-2xx | status is counted; no per-subscription result is returned to admin today |
| Push Service network I/O fails | send throws and current fan-out request fails |

## 13. Current failure semantics

These details are important when operating the current implementation.

### No automatic retry

GateLink does not automatically retry Push Service sends. This avoids accidental duplicate delivery without an explicit retry policy.

### No automatic removal of 404 / 410 subscriptions

A Push Service may report an expired or invalid subscription with statuses such as `404` or `410`. GateLink currently records the response status in metrics but does not automatically delete that PostgreSQL row.

This is a candidate for future lifecycle hardening, but operators should not assume it happens today.

### A network exception can stop the current fan-out

The JDK HTTP client uses a 10-second connect timeout and a 30-second request timeout. An I/O failure or interruption throws an exception. Because subscriptions are currently processed in the request thread, such an exception can terminate the current fan-out before later subscriptions are processed.

### REST success is not browser acknowledgement

The administrative `POST /notifications` does not return a delivery report per browser. A `204` means the GateLink request completed without an exception; it does not prove that every user saw a notification.

## 14. End-to-end example from an empty installation

This is the complete lifecycle in one list.

1. Operator starts PostgreSQL.
2. Operator starts GateLink.
3. Flyway creates or validates the subscription schema.
4. GateLink loads a stable VAPID pair (production) or generates a temporary one (development).
5. User opens the browser UI.
6. Browser registers the Service Worker.
7. User clicks Subscribe and grants notification permission.
8. Browser UI calls `GET /keys/public`.
9. GateLink returns the VAPID public key.
10. Browser Push API contacts FCM/Mozilla/vendor Push Service.
11. Push Service returns `endpoint`, `p256dh`, `auth` to the browser.
12. Browser UI sends them to `POST /subscriptions`.
13. GateLink validates the subscription.
14. PostgreSQL stores/upserts it.
15. Later, an admin obtains an OIDC token carrying `gatelink-admin`.
16. Admin calls `POST /notifications` with a text payload.
17. GateLink validates authentication, authorization, rate limit and payload size.
18. GateLink reads subscriptions from PostgreSQL.
19. For each subscription, GateLink asks the Java `web-push` library to create an `aes128gcm` encrypted body.
20. GateLink creates the VAPID JWT.
21. GateLink sends the encrypted request to the subscription's vendor Push Service URL.
22. GateLink records the returned status in Micrometer metrics.
23. Push Service delivers the message to the browser.
24. Browser Service Worker receives the push event.
25. Service Worker displays the notification.
26. User sees or clicks the notification.

## 15. Production operator checklist

Before treating the service as production-ready, verify all of the following:

- [ ] PostgreSQL data is persistent and backed up.
- [ ] `GATELINK_DB_URL`, user and password point to the intended production database.
- [ ] a stable VAPID public/private key pair is configured.
- [ ] the VAPID private key is stored as a secret and is not exposed to frontend code.
- [ ] the VAPID subject is a valid operational contact URI such as `mailto:...`.
- [ ] `GATELINK_OIDC_AUTH_SERVER_URL` and client ID point to the production identity provider.
- [ ] the admin identity receives `gatelink-admin`.
- [ ] production CORS origins are explicit.
- [ ] `/q/health` is monitored.
- [ ] `/q/metrics` is scraped and Push Service error statuses are alerted on.
- [ ] structured logs are collected centrally.
- [ ] OpenTelemetry export is configured if distributed tracing is required.
- [ ] network policy / SSRF controls restrict outbound access appropriately for Push Service endpoints.
- [ ] operators understand that 404/410 subscriptions are not automatically removed yet.
- [ ] real-browser delivery is tested with the browser vendors that must be supported.

## 16. Related documentation

- [`../README.md`](../README.md) — repository overview and quick start.
- [`../quarkus-gatelink-server/README.md`](../quarkus-gatelink-server/README.md) — Java/Quarkus server implementation and configuration.
- [`../quarkus-gatelink-webpush-ui/README.md`](../quarkus-gatelink-webpush-ui/README.md) — browser-side responsibilities.
- [`integration-examples.md`](integration-examples.md) — Java and TypeScript integration examples.
