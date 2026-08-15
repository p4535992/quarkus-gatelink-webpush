# Java Web Push library used by GateLink

GateLink stays entirely on the JVM. There is no Node.js, PHP, Python or sidecar service in the Web Push delivery path.

## Selected library

GateLink uses the Java library:

```text
nl.martijndwars:web-push:5.1.2
```

Upstream project:

- https://github.com/web-push-libs/webpush-java

Maven dependency:

```xml
<dependency>
    <groupId>nl.martijndwars</groupId>
    <artifactId>web-push</artifactId>
    <version>5.1.2</version>
</dependency>
```

The dependency is pinned deliberately. A library upgrade should be treated as a protocol-sensitive change and validated with the existing Web Push interoperability and HTTP-shape tests.

## Why GateLink uses this library

The goal is not to delegate the complete GateLink architecture to a third-party sender. The library is used to avoid maintaining our own implementation of the RFC 8291 / RFC 8188 payload cryptography while keeping GateLink as a single Java/Quarkus service.

The integration is intentionally narrow:

```text
GateLink Notification
(endpoint + p256dh + auth + payload)
        |
        v
EncryptionService
        |
        | nl.martijndwars:web-push
        | Encoding.AES128GCM
        v
RFC 8291 / RFC 8188 encrypted body
        |
        v
GateLink PushServiceClient
        |
        | JDK HttpClient
        | RFC 8292 VAPID headers
        v
Browser Push Service
```

This isolation means the rest of GateLink does not depend directly on the library API. If the Java Web Push implementation is replaced in the future, the replacement should be localized mainly to the encryption adapter and its tests.

## Exactly what is delegated to the library

GateLink delegates Web Push **payload encryption**:

- browser `p256dh` key handling required for RFC 8291;
- browser `auth` secret use;
- ephemeral P-256 ECDH material used by Web Push encryption;
- HKDF / key derivation required by RFC 8291;
- RFC 8188 record framing;
- AES-128-GCM encrypted content generation.

GateLink calls the library with:

```java
Encoding.AES128GCM
```

explicitly.

## What GateLink keeps under its own control

GateLink does **not** use the library as a generic legacy-compatible sender.

GateLink itself owns:

- the stable application-server VAPID key pair;
- VAPID subject configuration;
- RFC 8292 VAPID JWT generation;
- the Push Service audience calculation;
- outbound HTTP request construction;
- timeouts;
- HTTP response handling and metrics;
- subscription persistence in PostgreSQL;
- OIDC/RBAC, validation and rate limiting.

The outbound request is constructed by GateLink with the current standardized shape:

```text
TTL: 2419200
Content-Encoding: aes128gcm
Authorization: vapid t=<JWT>, k=<VAPID-public-key>
Content-Type: application/octet-stream
```

## Modern-only rule

GateLink supports only the current `aes128gcm` Web Push content coding.

There is intentionally no GateLink compatibility path for:

```text
aesgcm
Encryption: ...
Crypto-Key: ...
```

Even if the external library contains APIs or code paths for older protocol variants, GateLink does not invoke those paths.

## Verification

The library boundary is protected by project tests.

The test suite checks, among other things:

1. AES128GCM interoperability against an RFC 8188 test vector;
2. explicit use of `Encoding.AES128GCM`;
3. fresh Web Push encryption output for repeated sends;
4. maximum plaintext size enforcement;
5. `Content-Encoding: aes128gcm` on outbound requests;
6. modern RFC 8292 `Authorization: vapid ...` format;
7. absence of obsolete `Encryption` and `Crypto-Key` delivery headers.

The intent is that a future dependency upgrade cannot silently reintroduce a legacy delivery mode without breaking tests.

## Relationship with PostgreSQL

The Web Push library and PostgreSQL solve two completely different problems.

```text
PostgreSQL
    |
    | tells GateLink WHO to send to
    | endpoint + p256dh + auth
    v
GateLink
    |
    | asks web-push Java HOW to encrypt
    v
web-push Java library
    |
    | returns encrypted body
    v
GateLink HTTP sender
    |
    v
Push Service
```

PostgreSQL is not used by the library itself and is not required by the Web Push RFCs. GateLink uses it as its durable subscription registry.

See also:

- [`operator-guide.md`](operator-guide.md) for the complete runtime lifecycle;
- [`../quarkus-gatelink-webpush-server/README.md`](../quarkus-gatelink-webpush-server/README.md) for server internals;
- [`../README.md`](../README.md) for the architecture overview.
