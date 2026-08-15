package com.quarkus.gatelink.keymanagement.control;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.util.Base64;

import com.quarkus.gatelink.bytes.control.ByteOperations;

/** Loads raw Base64URL P-256 keys used for the stable VAPID identity. */
public interface KeyLoader {

    int P256_PUBLIC_KEY_LENGTH = 65;
    int P256_PRIVATE_KEY_LENGTH = 32;
    int P256_COORDINATE_LENGTH = 32;

    static ECPublicKey loadUrlEncodedPublicKey(String content)
            throws InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {
        byte[] decodedPublicKey = Base64.getUrlDecoder().decode(content);
        return loadPublicKeyFromBytes(decodedPublicKey);
    }

    static ECPublicKey loadPublicKeyFromBytes(byte[] keyContent)
            throws InvalidKeySpecException, NoSuchAlgorithmException, NoSuchProviderException {
        if (keyContent.length != P256_PUBLIC_KEY_LENGTH) {
            throw new IllegalArgumentException("P-256 public key must be exactly 65 octets in uncompressed form");
        }
        if (keyContent[0] != 0x04) {
            throw new IllegalArgumentException("P-256 public key must use uncompressed X9.62 form (0x04 prefix)");
        }

        var x = ByteOperations.fromUnsignedByteArray(keyContent, 1, P256_COORDINATE_LENGTH);
        var y = ByteOperations.fromUnsignedByteArray(
                keyContent,
                1 + P256_COORDINATE_LENGTH,
                P256_COORDINATE_LENGTH);
        var point = new ECPoint(x, y);
        return (ECPublicKey) getKeyFactory().generatePublic(new ECPublicKeySpec(point, getParameterSpec()));
    }

    static ECPrivateKey loadURLEncodedPrivateKey(String encodedPrivateKey)
            throws NoSuchProviderException, NoSuchAlgorithmException, InvalidKeySpecException {
        var decodedPrivateKey = Base64.getUrlDecoder().decode(encodedPrivateKey);
        if (decodedPrivateKey.length != P256_PRIVATE_KEY_LENGTH) {
            throw new IllegalArgumentException("P-256 private key must be exactly 32 octets");
        }
        var scalar = new BigInteger(1, decodedPrivateKey);
        var privateKeySpec = new ECPrivateKeySpec(scalar, getParameterSpec());
        return (ECPrivateKey) getKeyFactory().generatePrivate(privateKeySpec);
    }

    static ECParameterSpec getParameterSpec() {
        try {
            var parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (NoSuchAlgorithmException | InvalidParameterSpecException ex) {
            throw new IllegalStateException("Cannot load P-256 parameters", ex);
        }
    }

    static KeyFactory getKeyFactory() throws NoSuchAlgorithmException, NoSuchProviderException {
        return KeyFactory.getInstance("EC");
    }
}
