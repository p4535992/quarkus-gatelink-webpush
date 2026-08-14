package com.airhacks.gatelink.notifications.control;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

public interface PushServiceClient {

    HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    record NotificationResponse(int status) {
        public boolean isSuccessful() {
            return status >= 200 && status < 300;
        }
    }

    static NotificationResponse sendNotification(String endpoint, String salt, String ephemeralPublicKey,
            String vapidPublicKey, String authorizationToken, byte[] encryptedContent) {
        var uri = URI.create(endpoint);
        var request = HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .POST(BodyPublishers.ofByteArray(encryptedContent))
                .header("TTL", "2419200")
                .header("Content-Encoding", "aesgcm")
                .header("Encryption", "salt=" + salt)
                .header("Authorization", "WebPush " + authorizationToken)
                .header("Crypto-Key", "dh=" + ephemeralPublicKey + ";p256ecdsa=" + vapidPublicKey)
                .header("Content-Type", "application/octet-stream")
                .build();
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
