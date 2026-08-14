package com.airhacks.gatelink.notifications.boundary;

import java.net.URI;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jose4j.lang.JoseException;

import com.airhacks.gatelink.Boundary;
import com.airhacks.gatelink.encryption.boundary.EncryptionService;
import com.airhacks.gatelink.encryption.entity.EncryptedContent;
import com.airhacks.gatelink.keymanagement.boundary.InMemoryKeyStore;
import com.airhacks.gatelink.keymanagement.entity.ECKeys;
import com.airhacks.gatelink.log.boundary.Tracer;
import com.airhacks.gatelink.notifications.control.PushServiceClient;
import com.airhacks.gatelink.notifications.control.PushServiceClient.NotificationResponse;
import com.airhacks.gatelink.signature.control.JsonWebSignature;
import com.airhacks.gatelink.subscriptions.control.InMemorySubscriptionsStore;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;

/**
 * Sends a message to every registered Web Push subscription.
 *
 * @author airhacks.com
 */
@Boundary
public class NotificationsSender {

    @Inject
    InMemorySubscriptionsStore store;

    @Inject
    MeterRegistry registry;

    @Inject
    InMemoryKeyStore keyStore;

    @Inject
    EncryptionService encryptionService;

    @Inject
    Tracer tracer;

    /**
     * Server contact person. Push services can use this contact in case of
     * abuse or denial-of-service incidents.
     */
    @Inject
    @ConfigProperty(name = "subject", defaultValue = "mailto:admin@airhacks.com")
    String subject;

    public void send(String message) {
        registry.counter("webpush.messages.forwarded").increment();
        tracer.log("Sending " + message);
        ECKeys serverKeys = this.keyStore.getKeys();

        this.store.all()
                .stream()
                .map(subscription -> new Notification(subscription, message))
                .forEach(notification -> this.send(notification, serverKeys));
    }

    public boolean send(Notification notification, ECKeys serverKeys) {
        try {
            var encryptedContent = this.encryptionService.encrypt(notification, serverKeys);
            String endpoint = notification.getEndpoint();
            tracer.log("Sending to: " + endpoint);
            var notificationStatus = this.sendEncryptedMessage(serverKeys, endpoint, encryptedContent);
            registry.counter("webpush.responses", "status", Integer.toString(notificationStatus.status())).increment();
            return notificationStatus.isSuccessful();
        } catch (JoseException | NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchProviderException
                | InvalidKeyException | NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException ex) {
            throw new IllegalStateException("Cannot encrypt", ex);
        }
    }

    public NotificationResponse sendEncryptedMessage(ECKeys serverKeys, String endpoint, EncryptedContent encryptedContent)
            throws JoseException {
        tracer.log("Sending to endpoint " + endpoint);
        var audience = extractAud(endpoint);
        var salt = encryptedContent.getEncodedSalt();
        var ephemeralPublicKey = encryptedContent.getEncodedEphemeralPublicKey();
        var vapidPublicKey = serverKeys.getBase64URLEncodedPublicKeyWithoutPadding();
        tracer.log("audience: " + audience);
        var authorizationToken = JsonWebSignature.create(serverKeys.getPrivateKey(), subject, audience);

        var pushServiceHost = URI.create(audience).getHost();
        if (pushServiceHost != null) {
            registry.counter("webpush.push.attempts", "push_service", pushServiceHost).increment();
        }

        return PushServiceClient.sendNotification(endpoint, salt, ephemeralPublicKey, vapidPublicKey,
                authorizationToken, encryptedContent.encryptedContent());
    }

    static String extractAud(String endpoint) {
        var uri = URI.create(endpoint);
        var host = uri.getHost();
        var protocol = uri.getScheme();
        if (host == null || protocol == null) {
            return endpoint;
        }
        return "%s://%s".formatted(protocol, host);
    }
}
