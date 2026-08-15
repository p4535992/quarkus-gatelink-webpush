package com.quarkus.gatelink.encryption.boundary;

import java.security.GeneralSecurityException;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.quarkus.gatelink.encryption.entity.EncryptedContent;
import com.quarkus.gatelink.notifications.boundary.Notification;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;

/**
 * Produces RFC 8291 / RFC 8188 Web Push bodies by delegating the cryptographic
 * implementation to the maintained web-push Java library.
 *
 * GateLink always selects {@link Encoding#AES128GCM}. The library's obsolete
 * AESGCM mode and legacy HTTP sender are intentionally never used.
 */
@ApplicationScoped
public class EncryptionService {

    public static final int MAX_PLAINTEXT_LENGTH = 3993;

    @PostConstruct
    void initializeCryptoProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public EncryptedContent encrypt(Notification notification) {
        var payload = notification.getMessageAsBytes();
        if (payload.length > MAX_PLAINTEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Web Push payload is too large: maximum UTF-8 payload is "
                            + MAX_PLAINTEXT_LENGTH + " octets");
        }

        // Validate the RFC 8291 auth secret before handing key material to the
        // third-party implementation.
        notification.getAuthAsBytes();

        var subscription = notification.getSubscription();
        try {
            var libraryNotification = new nl.martijndwars.webpush.Notification(
                    notification.getEndpoint(),
                    subscription.getP256dh(),
                    subscription.getAuth(),
                    payload);

            var encrypted = AbstractPushService.encrypt(
                    libraryNotification.getPayload(),
                    libraryNotification.getUserPublicKey(),
                    libraryNotification.getUserAuth(),
                    Encoding.AES128GCM);

            return new EncryptedContent(encrypted.getCiphertext());
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Invalid Web Push subscription key material", ex);
        }
    }
}
