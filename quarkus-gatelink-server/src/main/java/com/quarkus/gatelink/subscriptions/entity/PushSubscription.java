package com.quarkus.gatelink.subscriptions.entity;

import java.net.URI;
import java.util.Base64;

import jakarta.json.JsonObject;
import jakarta.json.bind.annotation.JsonbTransient;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Represents the browser PushSubscription persisted and used by GateLink.
 */
public class PushSubscription {

    static final String PUBLIC_KEY = "p256dh";
    static final String AUTH_SECRET = "auth";

    @NotBlank
    @Size(max = 2048)
    public String endpoint;

    /** Browser-provided key material used for RFC 8291 encryption. */
    @NotNull
    public JsonObject keys;

    @JsonbTransient
    public String getP256dh() {
        return keys == null ? null : keys.getString(PUBLIC_KEY, null);
    }

    @JsonbTransient
    public String getAuth() {
        return keys == null ? null : keys.getString(AUTH_SECRET, null);
    }

    @AssertTrue(message = "endpoint must be an absolute HTTPS URI")
    @JsonbTransient
    public boolean isEndpointValid() {
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        try {
            var uri = URI.create(endpoint);
            return uri.isAbsolute()
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @AssertTrue(message = "keys.p256dh must be unpadded Base64URL for a 65-octet uncompressed P-256 point")
    @JsonbTransient
    public boolean isP256dhValid() {
        var value = getP256dh();
        if (!isCanonicalBase64Url(value)) {
            return false;
        }
        try {
            var decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == 65 && decoded[0] == 0x04;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @AssertTrue(message = "keys.auth must be unpadded Base64URL for a 16-octet RFC 8291 auth secret")
    @JsonbTransient
    public boolean isAuthValid() {
        var value = getAuth();
        if (!isCanonicalBase64Url(value)) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(value).length == 16;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isCanonicalBase64Url(String value) {
        return value != null
                && !value.isBlank()
                && !value.contains("=")
                && value.matches("[A-Za-z0-9_-]+");
    }

    @Override
    public String toString() {
        return "PushSubscription{endpoint='" + endpoint + "'}";
    }
}
