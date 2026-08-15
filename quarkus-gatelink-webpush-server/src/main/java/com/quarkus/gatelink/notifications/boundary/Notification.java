package com.quarkus.gatelink.notifications.boundary;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.quarkus.gatelink.subscriptions.entity.PushSubscription;

/**
 * A message plus the browser PushSubscription needed to encrypt and deliver it.
 */
public class Notification {

    private final PushSubscription subscription;
    private final String message;

    public Notification(PushSubscription subscription, String message) {
        this.subscription = subscription;
        this.message = message;
    }

    public PushSubscription getSubscription() {
        return subscription;
    }

    public String getMessage() {
        return message;
    }

    public byte[] getAuthAsBytes() {
        var decoded = convertAuth(this.subscription.getAuth());
        if (decoded.length != 16) {
            throw new IllegalArgumentException("RFC 8291 requires a 16-octet subscription auth secret");
        }
        return decoded;
    }

    static byte[] convertAuth(String auth) {
        return Base64.getUrlDecoder().decode(auth);
    }

    public byte[] getMessageAsBytes() {
        return this.message.getBytes(StandardCharsets.UTF_8);
    }

    public String getEndpoint() {
        return this.subscription.endpoint;
    }
}
