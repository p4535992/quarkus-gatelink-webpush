package com.quarkus.gatelink.keymanagement.entity;

import java.math.BigInteger;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.util.Base64;

import com.quarkus.gatelink.bytes.control.ByteOperations;

/**
 * Elliptic Curve key pair convenience methods for P-256 Web Push keys.
 *
 * @author airhacks.com
 */
public record ECKeys(ECPrivateKey privateKey, ECPublicKey publicKey) {

    private static final int P256_COORDINATE_LENGTH = 32;
    private static final int UNCOMPRESSED_PUBLIC_KEY_LENGTH = 65;

    public byte[] getPrivateKeyAsBytes() {
        return unsignedFixedLength(privateKey.getS(), P256_COORDINATE_LENGTH);
    }

    public byte[] getUncompressedPublicKey() {
        return decompressedRepresentation(publicKey.getW());
    }

    public static byte[] decompressedRepresentation(ECPublicKey publicKey) {
        return decompressedRepresentation(publicKey.getW());
    }

    /**
     * Returns the standard 65-byte uncompressed P-256 representation:
     * {@code 0x04 || X(32 bytes) || Y(32 bytes)}.
     */
    public static byte[] decompressedRepresentation(ECPoint ecPoint) {
        var xArray = unsignedFixedLength(ecPoint.getAffineX(), P256_COORDINATE_LENGTH);
        var yArray = unsignedFixedLength(ecPoint.getAffineY(), P256_COORDINATE_LENGTH);
        var result = new byte[UNCOMPRESSED_PUBLIC_KEY_LENGTH];
        result[0] = 4;
        System.arraycopy(xArray, 0, result, 1, P256_COORDINATE_LENGTH);
        System.arraycopy(yArray, 0, result, 1 + P256_COORDINATE_LENGTH, P256_COORDINATE_LENGTH);
        return result;
    }

    private static byte[] unsignedFixedLength(BigInteger value, int length) {
        var unsigned = ByteOperations.stripLeadingZeros(value.toByteArray());
        if (unsigned.length > length) {
            throw new IllegalArgumentException("Value does not fit in " + length + " bytes");
        }
        var result = new byte[length];
        System.arraycopy(unsigned, 0, result, length - unsigned.length, unsigned.length);
        return result;
    }

    public String getBase64URLEncodedPrivateKeyWithoutPadding() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(getPrivateKeyAsBytes());
    }

    public String getBase64URLEncodedPublicKeyWithoutPadding() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(getUncompressedPublicKey());
    }

    public ECPrivateKey getPrivateKey() {
        return privateKey;
    }

    public ECPublicKey getPublicKey() {
        return publicKey;
    }
}
