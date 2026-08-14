# GateLink backend

## Prerequisites

- Java 21
- Maven 3.9+

## Build

```bash
mvn clean verify
```

## Run

Development mode:

```bash
mvn quarkus:dev
```

Packaged JVM application:

```bash
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar
```

## Web Push flow

1. GateLink loads the configured VAPID P-256 key pair. If no keys are configured, a temporary pair is generated for development.
2. The browser requests notification permission.
3. The browser fetches `GET /keys/public` and creates a Push API subscription with the VAPID public key.
4. The browser posts the subscription to `POST /subscriptions`.
5. GateLink stores the subscription in memory.
6. A caller posts a text payload to `POST /notifications`.
7. GateLink encrypts and forwards the payload to every registered push-service endpoint.
8. VAPID keys sign the authorization token; per-message ephemeral EC keys are used for payload encryption.

## Production VAPID identity

Configure both values as Base64URL without padding:

```bash
export WEBPUSH_VAPID_PUBLIC_KEY='...'
export WEBPUSH_VAPID_PRIVATE_KEY='...'
```

Never expose or log the private key. If either property is supplied without the other, GateLink fails fast at startup.

## Observability

- readiness/liveness: `/q/health`
- Prometheus/Micrometer metrics: `/q/metrics`

## Known production gaps

The subscription store is still in-memory and is lost on restart. The notification and subscription-management endpoints also need authentication/authorization before exposing this service as a public gateway.

The custom encryption path currently uses the legacy Web Push `aesgcm` content coding. RFC 8291 interoperability requires `aes128gcm`; that protocol migration should be implemented and tested separately against current browser push services.
