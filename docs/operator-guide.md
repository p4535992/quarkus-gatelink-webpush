# GateLink operator guide

This document explains **what happens at runtime, in which order, and which component communicates with which other component**.

The normal user path is HTTPS-first:

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

Quarkus also makes outbound HTTPS requests directly to browser Push Services such as FCM or Mozilla/vendor infrastructure.

The Web Push crypto path is modern-only: `nl.martijndwars:web-push:5.1.2` is used with `Encoding.AES128GCM`; GateLink has no legacy `aesgcm` delivery path.

## 1. Fixed runtime names and ports

The application containers intentionally use identical Compose service, container and hostname values:

```text
quarkus-gatelink-webpush-ui
quarkus-gatelink-webpush-server
```

Ports:

| Component | Port | Purpose |
| --- | ---: | --- |
| UI | `80` HTTP | redirect normal user traffic to HTTPS; local `/healthz` remains available |
| UI | `443` HTTPS | **normal user/browser entry point** |
| Quarkus server | `8080` HTTP | optional direct REST/operations access |
| Quarkus server | `8443` HTTPS | optional direct TLS REST/operations access and Nginx upstream |
| PostgreSQL | `5432` | internal only; not published on the host |

Nginx reaches Quarkus using:

```text
https://quarkus-gatelink-webpush-server:8443/
```

It never uses `localhost` for container-to-container traffic.

## 2. Components and responsibilities

```text
+-------------------------+
| User                    |
| opens UI / subscribes   |
| sees notifications      |
+------------+------------+
             |
             | HTTPS :443
             v
+------------+------------+
| Browser                  |
| - Angular UI             |
| - Push API / SwPush      |
| - Service Worker         |
+------+--------------+----+
       |              |
       | /api/*       | browser Push API
       v              v
+------+----------+  +-------------------------+
| Nginx / UI      |  | Browser Push Service    |
| TLS termination |  | FCM / Mozilla / vendor  |
| SPA + /api proxy|  +-----------+-------------+
+------+----------+              |
       | HTTPS :8443             | Web Push delivery
       v                         v
+------+----------------+  +-----+-------------------+
| Quarkus server        |  | Browser Service Worker |
| REST / OIDC / DB      |  +-------------------------+
| VAPID / Web Push      |
| metrics / tracing     |
+------+----------------+
       |
       | JDBC
       v
+------+----------------+
| PostgreSQL 18         |
| subscription registry |
+-----------------------+
```

The important boundaries are:

- the browser talks to the **UI HTTPS origin**, not directly to Docker hostnames;
- Nginx proxies application REST calls to Quarkus over internal HTTPS;
- the browser Push API talks to the browser vendor Push Service when creating a subscription;
- GateLink stores subscription information in PostgreSQL;
- when sending a notification, GateLink talks to the Push Service endpoint, not directly to the browser;
- the Push Service later delivers to the browser Service Worker.

## 3. Self-signed certificate lifecycle

No TLS private key is stored in Git.

### UI certificate

On first start `quarkus-gatelink-webpush-ui` creates:

```text
/etc/nginx/tls/tls.crt
/etc/nginx/tls/tls.key
```

and persists them in Docker volume `ui_tls`.

### Server certificate

On first start `quarkus-gatelink-webpush-server` creates:

```text
/opt/app/tls/tls.crt
/opt/app/tls/tls.key
```

and persists them in `server_tls`.

Default identities:

```text
UI_TLS_COMMON_NAME=quarkus-gatelink-webpush-ui
UI_TLS_SAN=DNS:quarkus-gatelink-webpush-ui,DNS:localhost,IP:127.0.0.1

SERVER_TLS_COMMON_NAME=quarkus-gatelink-webpush-server
SERVER_TLS_SAN=DNS:quarkus-gatelink-webpush-server,DNS:localhost,IP:127.0.0.1
```

If users/operators access the host with another DNS name or IP, add it to the appropriate SAN list **before first start**.

A self-signed certificate encrypts traffic but is not automatically trusted. Browser/operator clients must explicitly trust or accept it.

Nginx uses encrypted HTTPS to Quarkus but disables certificate-chain verification for this self-signed internal mode. A deployment with an internal CA should enable upstream verification.

## 4. Stack startup: step by step

```text
Operator
   |
   | docker compose up -d --wait
   v
postgres
   |
   | initialize data directory
   | pg_isready -> healthy
   v
quarkus-gatelink-webpush-server
   |
   +-- entrypoint creates/reuses server TLS certificate
   +-- Java starts
   +-- datasource -> postgres:5432
   +-- Flyway applies pending migrations
   +-- Hibernate validates mappings
   +-- VAPID identity loads/generates
   +-- OIDC/security initializes
   +-- metrics/tracing initialize
   +-- HTTP :8080 starts
   +-- HTTPS :8443 starts
   |   /q/health/ready -> UP
   v
quarkus-gatelink-webpush-ui
   |
   +-- entrypoint creates/reuses UI TLS certificate
   +-- Nginx starts :80 and :443
   +-- local /healthz -> healthy
   v
Stack ready
```

Exact sequence:

1. Docker starts PostgreSQL using the pinned Docker Official Image `postgres:18.4`.
2. PostgreSQL initializes/reuses the `postgres_data` volume.
3. `pg_isready` must report healthy.
4. Docker starts `quarkus-gatelink-webpush-server`.
5. Its entrypoint checks `server_tls`.
6. If the certificate/key do not exist, OpenSSL creates them from the configured CN/SAN values.
7. Java starts `/opt/app/app.jar` as UID/GID `10001:10001`.
8. Quarkus opens the JDBC datasource to `postgres:5432`.
9. Flyway applies pending schema migrations.
10. Hibernate validates the Java mappings against the schema.
11. GateLink loads the VAPID public/private key pair. If both are absent, a temporary development pair is generated; production should use stable keys.
12. Quarkus starts HTTP `8080` and HTTPS `8443`.
13. The server healthcheck calls `https://127.0.0.1:8443/q/health/ready` with self-signed verification disabled.
14. Only after the server is healthy does Docker start `quarkus-gatelink-webpush-ui`.
15. The UI entrypoint creates/reuses its certificate in `ui_tls`.
16. Nginx starts HTTP `80` and HTTPS `443`.
17. Docker checks the UI's local HTTP `/healthz` endpoint.
18. The stack is considered ready.

## 5. What PostgreSQL is for

PostgreSQL is **not part of the Web Push protocol itself**. GateLink uses it as a durable browser subscription registry.

Stored table:

```text
push_subscriptions
+----------+---------------------------------+
| endpoint | TEXT PRIMARY KEY                |
| p256dh   | TEXT NOT NULL                   |
| auth     | TEXT NOT NULL                   |
+----------+---------------------------------+
```

Meaning:

- `endpoint` identifies the browser vendor Push Service URL to which GateLink later sends;
- `p256dh` is the browser's public encryption key;
- `auth` is browser-generated Web Push authentication material.

PostgreSQL does **not** store:

- notification history;
- a notification queue;
- per-browser delivery acknowledgements;
- the VAPID private key;
- TLS private keys.

Operational shorthand:

```text
PostgreSQL answers: "WHO can GateLink send to?"
web-push Java answers: "HOW is this payload encrypted for that subscription?"
```

## 6. User opens the UI

```text
User
  |
  | https://host/
  v
Browser
  |
  | TLS :443
  v
quarkus-gatelink-webpush-ui / Nginx
  |
  | serves Angular static files
  v
Browser Angular application
```

Step by step:

1. The user opens `https://<host>/`.
2. The browser performs TLS with Nginx on port 443.
3. With the supplied self-signed certificate, the browser must trust/accept that certificate.
4. Nginx returns Angular `index.html` and generated assets.
5. Angular starts in the browser.
6. Angular registers its Service Worker.
7. Angular uses same-origin `/api/...` URLs for GateLink calls.

If the user enters `http://<host>/`, Nginx returns `308` to the HTTPS URL.

## 7. Browser subscription registration: step by step

### Full sequence

```text
User     Browser/Angular   Nginx UI     Quarkus server     Push API     Push Service   PostgreSQL
 |             |              |              |                |             |             |
 | Subscribe   |              |              |                |             |             |
 |------------>|              |              |                |             |             |
 |             | GET /api/keys/public        |                |             |             |
 |             |------------->|              |                |             |             |
 |             |              | HTTPS :8443 /keys/public      |             |             |
 |             |              |------------->|                |             |             |
 |             |              | public VAPID key              |             |             |
 |             |              |<-------------|                |             |             |
 |             |<-------------|              |                |             |             |
 |             | request permission / subscription            |             |             |
 |             |--------------------------------------------->|             |             |
 |             |              |              |                | register    |             |
 |             |              |              |                |------------>|             |
 |             |              |              |                | subscription             |
 |             |              |              |                |<------------|             |
 |             | PushSubscription(endpoint,p256dh,auth)       |             |             |
 |             |<---------------------------------------------|             |             |
 |             | POST /api/subscriptions     |                |             |             |
 |             |------------->|              |                |             |             |
 |             |              | HTTPS :8443 /subscriptions    |             |             |
 |             |              |------------->|                |             |             |
 |             |              |              | validate       |             |             |
 |             |              |              |------------------------------------------->|
 |             |              |              | INSERT ... ON CONFLICT UPDATE              |
 |             |              |              |<-------------------------------------------|
 |             |              | HTTP 204     |                |             |             |
 |             |<-------------|<-------------|                |             |             |
```

Exact sequence:

1. User selects Subscribe.
2. Angular calls `GET /api/keys/public` on the same HTTPS origin.
3. Nginx strips `/api/` and calls `https://quarkus-gatelink-webpush-server:8443/keys/public`.
4. GateLink returns only the public VAPID key.
5. Angular asks the browser Push API to create/reuse a subscription using that public key.
6. The browser Push API contacts the vendor Push Service.
7. The browser obtains a `PushSubscription` containing `endpoint`, `p256dh` and `auth`.
8. Angular sends it to `POST /api/subscriptions`.
9. Nginx proxies to Quarkus `/subscriptions` over internal HTTPS.
10. GateLink validates that `endpoint` is absolute HTTPS.
11. `p256dh` must be canonical unpadded Base64URL and decode to a 65-byte uncompressed P-256 public key.
12. `auth` must be canonical unpadded Base64URL and decode to 16 bytes.
13. Invalid data returns `400` before persistence.
14. Valid data reaches `SubscriptionsStore`.
15. PostgreSQL executes an atomic upsert keyed by endpoint.
16. Re-registering the same endpoint refreshes its key material rather than inserting a duplicate.
17. GateLink returns `204`.
18. The subscription now survives server/container restart because PostgreSQL is persistent.

## 8. Sending a notification: step by step

The normal administrative request path is also through the UI HTTPS origin:

```text
Admin/client
   |
   | POST https://host/api/notifications
   | Authorization: Bearer <token>
   | Content-Type: text/plain
   v
Nginx :443
   |
   | HTTPS :8443 /notifications
   v
Quarkus
   |
   +-- OIDC authentication
   +-- gatelink-admin authorization
   +-- 20/min rate limit
   +-- payload validation
   |
   +--> PostgreSQL SELECT subscriptions
   |
   `--> for every subscription
          |
          +--> nl.martijndwars:web-push / AES128GCM
          +--> GateLink VAPID JWT
          +--> HTTPS POST to subscription Push Service
          `--> Micrometer response-status metric
```

Exact sequence:

1. An administrator or trusted calling service obtains an OIDC access token.
2. The identity must have role `gatelink-admin`.
3. Caller sends `POST /api/notifications` with the Bearer token and text payload.
4. Nginx forwards it over HTTPS to Quarkus `/notifications`.
5. Quarkus authenticates the token.
6. `@RolesAllowed("gatelink-admin")` checks authorization.
7. Unauthenticated requests are rejected; authenticated users without the role are forbidden.
8. GateLink enforces the current 20 requests/minute notification rate limit.
9. Blank payloads are rejected.
10. Plaintext payload size is limited to 3993 UTF-8 bytes.
11. GateLink loads current subscriptions from PostgreSQL.
12. For each subscription, GateLink constructs the Java Web Push notification object.
13. `EncryptionService` invokes `nl.martijndwars:web-push` explicitly with `Encoding.AES128GCM`.
14. The library performs RFC 8291 / RFC 8188 payload encryption.
15. GateLink derives the Push Service origin from the subscription endpoint for the VAPID audience.
16. GateLink signs the RFC 8292 ES256 VAPID JWT using its stable VAPID private key.
17. GateLink sends an HTTPS POST directly to the exact subscription endpoint.
18. Request headers include:

```text
TTL: 2419200
Content-Encoding: aes128gcm
Authorization: vapid t=<JWT>, k=<VAPID-public-key>
Content-Type: application/octet-stream
```

19. GateLink intentionally does not emit obsolete `Encryption` or `Crypto-Key` delivery headers.
20. The Push Service returns an HTTP status.
21. GateLink records that status in Micrometer metrics.
22. If the synchronous fan-out completes without an exception, the admin REST call returns `204`.

## 9. Push Service → browser → user

After the Push Service accepts GateLink's encrypted request:

```text
quarkus-gatelink-webpush-server
        |
        | HTTPS Web Push
        v
Browser Push Service
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

1. GateLink is no longer in the direct delivery path after handing the message to the Push Service.
2. The Push Service identifies the browser/device associated with the endpoint.
3. It delivers according to vendor/browser behavior and TTL.
4. The browser wakes/invokes the registered Service Worker.
5. The Service Worker receives the `push` event.
6. The Service Worker displays the notification.
7. Notification click behavior is handled by browser/UI logic.

A Push Service `2xx` means **the Push Service accepted GateLink's request**. It does not prove that the browser displayed the notification or that the user saw/clicked it.

## 10. Unsubscribe: step by step

```text
Browser Angular
   |
   | current PushSubscription.endpoint
   | Base64URL encode endpoint
   v
DELETE /api/subscriptions/{encodedEndpoint}
   |
   v
Nginx :443
   |
   | HTTPS :8443
   v
Quarkus
   |
   | validate/decode endpoint
   v
PostgreSQL DELETE
   |
   v
Angular SwPush.unsubscribe()
```

1. Angular reads the current browser subscription.
2. It Base64URL-encodes the endpoint for the REST path.
3. It asks GateLink to remove the stored subscription.
4. GateLink validates and decodes the endpoint.
5. PostgreSQL removes the row keyed by that endpoint.
6. After server-side removal succeeds, Angular asks the browser Push API to unsubscribe locally.

Server-side deletion and browser Push API unsubscription are distinct operations.

## 11. Administrative subscription operations

Through the normal HTTPS origin:

```text
GET    /api/subscriptions    -> list endpoints           -> gatelink-admin
DELETE /api/subscriptions    -> remove all subscriptions -> gatelink-admin
```

Direct Quarkus equivalents omit `/api` and may be called on 8080 or 8443.

## 12. Direct Quarkus REST access

Normal users should stay on HTTPS 443 via Nginx. Operators may deliberately access Quarkus directly:

```text
HTTP  http://host:8080/
HTTPS https://host:8443/
```

Examples:

```bash
curl http://localhost:8080/q/health/ready
curl -k https://localhost:8443/q/health/ready
curl -k https://localhost:8443/keys/public
```

For direct HTTPS from another machine name/IP, that name/IP must be present in `SERVER_TLS_SAN` when the certificate is generated.

## 13. OIDC behavior

The Docker stack contains no identity-provider container. `.env.example` therefore defaults to:

```text
OIDC_ENABLED=false
OIDC_CLIENT_ID=quarkus-gatelink-webpush-server
```

This is convenient for a self-contained local boot, but production administrative endpoints require a configured external OIDC issuer and role `gatelink-admin`.

When OIDC is enabled, the issuer is external to the UI/server/PostgreSQL Compose stack.

## 14. VAPID identity

PostgreSQL persistence alone is not sufficient for restart-safe production Web Push. VAPID identity must also remain stable.

```text
PostgreSQL persistent + VAPID persistent   -> correct
PostgreSQL persistent + VAPID regenerated  -> DB rows remain,
                                              application-server identity changed
```

Production should set:

```text
WEBPUSH_VAPID_PUBLIC_KEY
WEBPUSH_VAPID_PRIVATE_KEY
WEBPUSH_VAPID_SUBJECT
```

The VAPID private key is server configuration/secret material; it is never stored in PostgreSQL or sent to Angular.

## 15. Health, logs and metrics

### Health

```text
UI through HTTPS:             https://host/healthz
UI -> Quarkus readiness:      https://host/api/q/health/ready
Direct Quarkus HTTP:          http://host:8080/q/health/ready
Direct Quarkus HTTPS:         https://host:8443/q/health/ready
```

### Logs

```bash
docker compose logs -f quarkus-gatelink-webpush-ui
docker compose logs -f quarkus-gatelink-webpush-server
docker compose logs -f postgres
```

Quarkus also writes `/opt/app/logs/application.log`, mapped to the host in the source Compose deployment.

### Metrics

Useful GateLink counters include:

```text
webpush.messages.forwarded
webpush.push.attempts{push_service="..."}
webpush.responses{status="..."}
```

Management endpoints are available directly under `/q/...` and through Nginx under `/api/q/...`.

## 16. Common HTTP outcomes

| Where | Status | Operator meaning |
| --- | ---: | --- |
| UI HTTP `:80` | `308` | expected redirect to HTTPS |
| subscription registration | `204` | subscription stored/upserted |
| invalid subscription | `400` | validation rejected before persistence |
| protected API without valid authentication | `401` / authorization rejection | OIDC/authentication problem |
| authenticated caller without role | authorization rejection | missing `gatelink-admin` |
| rate limit | `429` | notification caller exceeded configured rate |
| Push Service | `2xx` | Push Service accepted this Web Push request |
| Push Service | non-`2xx` | remote Push Service rejected this particular send |

## 17. Current failure semantics

Operators must not infer behavior that is not implemented:

- GateLink does not automatically retry failed Push Service calls;
- GateLink does not automatically remove a stored subscription when a Push Service returns `404` or `410`;
- `POST /notifications` does not return a per-browser delivery report;
- synchronous fan-out means a network I/O exception can stop later sends in the current request;
- Push Service acceptance is not end-user acknowledgement;
- PostgreSQL is not a queue and cannot replay missed notifications.

## 18. Certificate troubleshooting

Inspect the generated UI certificate:

```bash
docker compose exec quarkus-gatelink-webpush-ui \
  openssl x509 -in /etc/nginx/tls/tls.crt \
  -noout -subject -issuer -dates -ext subjectAltName
```

Inspect the server certificate:

```bash
docker compose exec quarkus-gatelink-webpush-server \
  openssl x509 -in /opt/app/tls/tls.crt \
  -noout -subject -issuer -dates -ext subjectAltName
```

If SANs must change, stop the stack and remove only the relevant TLS volume before restarting. Preserve `postgres_data`.

## 19. PostgreSQL inspection

Open `psql` without exposing port 5432:

```bash
docker compose exec postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

Useful query:

```sql
SELECT endpoint FROM push_subscriptions ORDER BY endpoint;
```

Avoid displaying `p256dh` and `auth` unnecessarily in operator logs or screenshots.

## 20. Production checklist

Before production use verify:

- [ ] users access the UI through HTTPS 443;
- [ ] `UI_TLS_SAN` contains the browser-facing hostname/IP;
- [ ] `SERVER_TLS_SAN` contains `quarkus-gatelink-webpush-server` and every hostname/IP used for direct HTTPS access;
- [ ] self-signed certificates are explicitly trusted, or replaced with organizational/public CA certificates;
- [ ] PostgreSQL `postgres_data` is persistent and backed up;
- [ ] `POSTGRES_PASSWORD` is not the example value;
- [ ] stable VAPID public/private keys are configured and backed up securely;
- [ ] the VAPID private key is not exposed to Angular, logs or PostgreSQL;
- [ ] OIDC is enabled/configured before protected administrative endpoints are exposed;
- [ ] OIDC client ID is `quarkus-gatelink-webpush-server` unless the external IdP intentionally uses another registered client ID;
- [ ] admin identities receive `gatelink-admin`;
- [ ] PostgreSQL port 5432 is not published;
- [ ] direct Quarkus ports 8080/8443 are allowed by firewall/network policy only where operationally intended;
- [ ] `/q/health/ready` and Push Service response metrics are monitored;
- [ ] no TLS/VAPID/database private secret is committed to Git;
- [ ] database backup/restore has been tested;
- [ ] operators understand that no automatic Push Service retry or `404/410` cleanup currently exists.
