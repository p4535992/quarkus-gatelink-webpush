# GateLink Web Push

A small Web Push gateway and browser demo powered by Quarkus.

## Runtime

- Java 21
- Quarkus 3.33.3 LTS
- RESTEasy Classic / JSON-B
- SmallRye Health
- Micrometer + Prometheus metrics

## Build and run

```bash
cd gatelink
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar
```

Development mode:

```bash
cd gatelink
mvn quarkus:dev
```

Useful endpoints:

- public VAPID key: `GET /keys/public`
- subscriptions: `/subscriptions`
- send a notification: `POST /notifications`
- health: `/q/health`
- Prometheus metrics: `/q/metrics`

## VAPID keys

For local development, GateLink generates a temporary P-256 VAPID key pair when no key is configured.
The private key is never exposed by the REST API or written to the log.

Production deployments should provide a stable Base64URL encoded key pair:

```bash
export WEBPUSH_VAPID_PUBLIC_KEY='...'
export WEBPUSH_VAPID_PRIVATE_KEY='...'
```

A stable application-server key is important because browser subscriptions are associated with the VAPID public key used when the subscription is created.

## CORS

Development mode accepts cross-origin requests so that the standalone demo UI can call the local backend.
Production does not enable wildcard origins. Configure the allowed origins explicitly, for example:

```bash
export QUARKUS_HTTP_CORS_ORIGINS='https://push.example.com'
```

## Docker

```bash
cd gatelink
mvn clean package
docker build -f src/main/docker/Dockerfile.jvm -t gatelink:latest .
docker run --rm -p 8080:8080 \
  -e WEBPUSH_VAPID_PUBLIC_KEY \
  -e WEBPUSH_VAPID_PRIVATE_KEY \
  gatelink:latest
```

The image uses Java 21 and the Quarkus fast-jar layout.

## Demo UI

The `webpush-ui` directory contains the browser sample based on the Notification API, Push API, Service Workers, Custom Elements and Fetch API.
It defaults to `http://localhost:8080` for the backend. Set `globalThis.GATELINK_BASE_URI` before loading the modules if a different backend URL is required.

## Security and production readiness

This repository started as a compact Web Push demonstration. The Quarkus 3.33 modernization removes the most immediate secret exposure and unsafe CORS defaults, but the project should not yet be exposed as a public push gateway without additional access control and abuse protection around subscription deletion and `POST /notifications`.

The current custom payload-encryption implementation also still uses the older `aesgcm` Web Push content coding and header format. Modern RFC 8291 Web Push uses `aes128gcm`; migrating that protocol path (or replacing the hand-written crypto with a maintained Web Push library) should be treated as a separate compatibility change with interoperability tests against current browser push services.

## Tests

Backend unit/integration tests:

```bash
cd gatelink
mvn verify
```

The `gatelink-st` module contains system tests that target a running GateLink instance.
