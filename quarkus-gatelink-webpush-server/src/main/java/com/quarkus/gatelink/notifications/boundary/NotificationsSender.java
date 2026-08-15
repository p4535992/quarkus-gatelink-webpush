package com.quarkus.gatelink.notifications.boundary;

import java.net.URI;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.quarkus.gatelink.Boundary;
import com.quarkus.gatelink.encryption.boundary.EncryptionService;
import com.quarkus.gatelink.encryption.entity.EncryptedContent;
import com.quarkus.gatelink.keymanagement.boundary.InMemoryKeyStore;
import com.quarkus.gatelink.keymanagement.entity.ECKeys;
import com.quarkus.gatelink.notifications.control.PushServiceClient;
import com.quarkus.gatelink.notifications.control.PushServiceClient.NotificationResponse;
import com.quarkus.gatelink.signature.control.JsonWebSignature;
import com.quarkus.gatelink.subscriptions.control.SubscriptionsStore;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;

/**
 * Sends only RFC 8291 aes128gcm messages and RFC 8292 VAPID authentication.
 * Encryption is delegated to the web-push library; GateLink retains the HTTP
 * request construction so no legacy transport headers can be emitted.
 */
@Boundary
public class NotificationsSender {

    @Inject
    SubscriptionsStore store;

    @Inject
    MeterRegistry registry;

    @Inject
    InMemoryKeyStore keyStore;

    @Inject
    EncryptionService encryptionService;

    @Inject
    @ConfigProperty(name = "webpush.vapid.subject", defaultValue = "mailto:admin@example.com")
    String subject;

    public void send(String message) {
        registry.counter("webpush.messages.forwarded").increment();
        ECKeys serverKeys = this.keyStore.getKeys();

        this.store.all().stream()
                .map(subscription -> new Notification(subscription, message))
                .forEach(notification -> this.send(notification, serverKeys));
    }

    public boolean send(Notification notification, ECKeys serverKeys) {
        var encryptedContent = this.encryptionService.encrypt(notification);
        var endpoint = notification.getEndpoint();
        var notificationStatus = this.sendEncryptedMessage(serverKeys, endpoint, encryptedContent);
        registry.counter("webpush.responses", "status", Integer.toString(notificationStatus.status())).increment();
        return notificationStatus.isSuccessful();
    }

    public NotificationResponse sendEncryptedMessage(
            ECKeys serverKeys,
            String endpoint,
            EncryptedContent encryptedContent) {
        var audience = extractAud(endpoint);
        var vapidPublicKey = serverKeys.getBase64URLEncodedPublicKeyWithoutPadding();
        var authorizationToken = JsonWebSignature.create(serverKeys.getPrivateKey(), subject, audience);

        var pushServiceHost = URI.create(audience).getHost();
        registry.counter("webpush.push.attempts", "push_service", pushServiceHost).increment();

        return PushServiceClient.sendNotification(
                endpoint,
                vapidPublicKey,
                authorizationToken,
                encryptedContent.body());
    }

    static String extractAud(String endpoint) {
        var uri = URI.create(endpoint);
        var scheme = uri.getScheme();
        var host = uri.getHost();
        if (!uri.isAbsolute() || scheme == null || host == null || !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Web Push endpoint must be an absolute HTTPS URI");
        }
        return uri.getPort() == -1
                ? "https://" + host
                : "https://" + host + ":" + uri.getPort();
    }
}
