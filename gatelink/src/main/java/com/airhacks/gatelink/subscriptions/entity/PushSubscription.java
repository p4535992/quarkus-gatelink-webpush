package com.airhacks.gatelink.subscriptions.entity;

import com.airhacks.gatelink.keymanagement.control.KeyLoader;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import jakarta.json.JsonObject;
import jakarta.json.bind.annotation.JsonbTransient;

/**
 * Represents a PushSubscription as described by the Push API.
 *
 * @author airhacks.com
 */
public class PushSubscription {

    public String endpoint;

    /**
     * Browser-provided key material used for Web Push message encryption.
     */
    public JsonObject keys;

    static final String PUBLIC_KEY = "p256dh";

    @JsonbTransient
    public String getP256dh() {
        return keys.getString(PUBLIC_KEY);
    }

    @JsonbTransient
    public String getAuth() {
        return keys.getString("auth");
    }

    @JsonbTransient
    public ECPublicKey getP256dhAsPublicKey()
            throws NoSuchProviderException, NoSuchAlgorithmException, InvalidKeySpecException {
        return KeyLoader.loadUrlEncodedPublicKey(this.getP256dh());
    }

    @Override
    public String toString() {
        return "PushSubscription{endpoint='" + endpoint + "'}";
    }
}
