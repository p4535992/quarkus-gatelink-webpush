package com.quarkus.gatelink.notifications.control;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/**
 * Minimal RFC 8030 Web Push HTTP client.
 *
 * GateLink sends only RFC 8291 aes128gcm payloads and RFC 8292 VAPID
 * authentication. No obsolete aesgcm headers or authorization formats are
 * emitted.
 */
public interface PushServiceClient {

    HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    record NotificationResponse(int status) {
        public boolean isSuccessful() {
            return status >= 200 && status < 300;
        }
    }

    static HttpRequest request(String endpoint, String vapidPublicKey,
            String authorizationToken, byte[] aes128gcmBody) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .POST(BodyPublishers.ofByteArray(aes128gcmBody))
                .header("TTL", "2419200")
                .header("Content-Encoding", "aes128gcm")
                .header("Authorization", "vapid t=" + authorizationToken + ", k=" + vapidPublicKey)
                .header("Content-Type", "application/octet-stream")
                .build();
    }

    static NotificationResponse sendNotification(String endpoint, String vapidPublicKey,
            String authorizationToken, byte[] aes128gcmBody) {
        var request = request(endpoint, vapidPublicKey, authorizationToken, aes128gcmBody);
        try {
            var response = CLIENT.send(request, BodyHandlers.discarding());
            return new NotificationResponse(response.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending Web Push message", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot send Web Push message", ex);
        }
    }
}
