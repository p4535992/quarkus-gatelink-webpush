# GateLink integration examples

GateLink exposes a small HTTP API. Integration code should use the HTTP contract rather than importing server implementation classes.

```text
Java service / TypeScript app
          |
          | HTTP
          v
quarkus-gatelink-server
          |
          +---- PostgreSQL subscriptions
          |
          +---- Web Push delivery
```

A browser `PushSubscription` is created by the browser Push API (or Angular `SwPush`). Java server-to-server clients normally query GateLink, trigger notifications, or forward a subscription that was originally created in a browser.

## REST endpoints used by integrations

| Method | Path | Typical integration use |
| --- | --- | --- |
| `GET` | `/keys/public` | obtain the VAPID public key for browser subscription |
| `POST` | `/subscriptions` | register/update a browser `PushSubscription` |
| `GET` | `/subscriptions` | admin (`gatelink-admin`): list registered endpoints |
| `DELETE` | `/subscriptions/{base64urlEndpoint}` | remove a subscription |
| `POST` | `/notifications` | admin (`gatelink-admin`): send a notification; 20/minute limit |
| `GET` | `/q/health` | health check |

## Authentication for administrative calls

Browser subscription registration (`POST /subscriptions`) and individual browser unsubscribe remain public and strictly validated. Administrative calls (`GET /subscriptions`, `DELETE /subscriptions`, `POST /notifications`) require an OIDC Bearer token carrying role `gatelink-admin`.

In dev mode, Quarkus Dev Services starts Keycloak automatically when Docker is available; use `/q/dev-ui` and the built-in `alice` identity. In production, obtain a token from the configured OIDC provider and send:

```http
Authorization: Bearer <access-token>
```

## Java 21: standard `HttpClient`

No Quarkus-specific dependency is required for a plain Java integration.

```java
package com.example.gatelink;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class GateLinkClient {
    private final HttpClient http = HttpClient.newHttpClient();
    private final URI baseUri;
    private final String accessToken;

    public GateLinkClient(String baseUrl, String accessToken) {
        this.baseUri = URI.create(baseUrl.replaceAll("/+$", ""));
        this.accessToken = accessToken;
    }

    public String publicVapidKey() throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve("/keys/public"))
                .GET()
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GateLink returned HTTP " + response.statusCode());
        }
        return response.body().trim();
    }

    public void sendNotification(String message) throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve("/notifications"))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(message))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 204) {
            throw new IllegalStateException("GateLink returned HTTP " + response.statusCode());
        }
    }
}
```

Usage:

```java
var gateLink = new GateLinkClient("http://localhost:8080", accessToken);
System.out.println(gateLink.publicVapidKey());
gateLink.sendNotification("Hello from Java");
```

## Java / Quarkus: MicroProfile REST Client

For a Quarkus service, use the type-safe MicroProfile REST Client.

Maven dependency:

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-rest-client-jsonb</artifactId>
</dependency>
```

Client contract:

```java
package com.example.gatelink;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
@RegisterRestClient(configKey = "gatelink")
public interface GateLinkApi {

    @GET
    @Path("keys/public")
    @Produces(MediaType.TEXT_PLAIN)
    String publicKey();

    @POST
    @Path("notifications")
    @Consumes(MediaType.TEXT_PLAIN)
    void sendNotification(@HeaderParam("Authorization") String authorization, String message);
}
```

Configuration:

```properties
quarkus.rest-client.gatelink.url=http://localhost:8080
```

Injection:

```java
package com.example.gatelink;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class NotificationGateway {
    @Inject
    @RestClient
    GateLinkApi gateLink;

    public void send(String message, String accessToken) {
        gateLink.sendNotification("Bearer " + accessToken, message);
    }
}
```

The GateLink repository uses the same MicroProfile REST Client approach in `quarkus-gatelink-server/src/test/java/com/quarkus/gatelink/system/` for HTTP-level tests.

## TypeScript: standard `fetch`

This works in browser TypeScript projects without Angular.

```ts
const gateLinkBaseUrl = 'http://localhost:8080';

export async function getVapidPublicKey(): Promise<string> {
  const response = await fetch(`${gateLinkBaseUrl}/keys/public`);
  if (!response.ok) {
    throw new Error(`GateLink returned HTTP ${response.status}`);
  }
  return (await response.text()).trim();
}

export async function registerSubscription(
  subscription: PushSubscription,
): Promise<void> {
  const response = await fetch(`${gateLinkBaseUrl}/subscriptions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(subscription.toJSON()),
  });
  if (!response.ok) {
    throw new Error(`GateLink returned HTTP ${response.status}`);
  }
}

export async function sendNotification(message: string, accessToken: string): Promise<void> {
  const response = await fetch(`${gateLinkBaseUrl}/notifications`, {
    method: 'POST',
    headers: {
      'Content-Type': 'text/plain',
      'Authorization': `Bearer ${accessToken}`,
    },
    body: message,
  });
  if (!response.ok) {
    throw new Error(`GateLink returned HTTP ${response.status}`);
  }
}
```

Browser subscription flow:

```ts
const publicKey = await getVapidPublicKey();
const registration = await navigator.serviceWorker.ready;
const subscription = await registration.pushManager.subscribe({
  userVisibleOnly: true,
  applicationServerKey: publicKey,
});
await registerSubscription(subscription);
```

The public VAPID key returned by GateLink is unpadded Base64URL. If a browser/library API expects a `Uint8Array`, decode that Base64URL value before passing it as `applicationServerKey`.

## TypeScript: unsubscribe

GateLink identifies the subscription to delete by a Base64URL-encoded endpoint.

```ts
function base64UrlEncode(value: string): string {
  const bytes = new TextEncoder().encode(value);
  const binary = Array.from(bytes, b => String.fromCharCode(b)).join('');
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

export async function unregisterSubscription(
  subscription: PushSubscription,
): Promise<void> {
  const encoded = base64UrlEncode(subscription.endpoint);
  const response = await fetch(
    `${gateLinkBaseUrl}/subscriptions/${encoded}`,
    { method: 'DELETE' },
  );
  if (!response.ok) {
    throw new Error(`GateLink returned HTTP ${response.status}`);
  }
}
```

## Angular + TypeScript: `HttpClient` and `SwPush`

A complete drop-in Angular example is under `quarkus-gatelink-webpush-ui/examples/angular-typescript/`.

Core flow:

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

Angular `SwPush` owns the browser subscription lifecycle; `HttpClient` is used for the GateLink REST API.

## Which integration should I use?

```text
Plain Java service          -> java.net.http.HttpClient
Quarkus Java service        -> MicroProfile REST Client
Plain TypeScript browser    -> fetch + PushManager
Angular application         -> HttpClient + SwPush
```

## Local integration environment

Start PostgreSQL and GateLink:

```bash
docker compose up -d postgres
cd quarkus-gatelink-server
mvn quarkus:dev
```

GateLink is then available at `http://localhost:8080`.

Browser Push API and Service Workers require a secure context. Use HTTPS in production; `localhost` is normally treated as a trustworthy development origin.
