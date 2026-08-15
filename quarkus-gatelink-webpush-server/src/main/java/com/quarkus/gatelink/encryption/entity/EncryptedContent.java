package com.quarkus.gatelink.encryption.entity;

/**
 * Complete RFC 8188 aes128gcm body ready to be POSTed to a Web Push endpoint.
 * Salt, record size, and the ephemeral ECDH public key are already embedded in
 * this body.
 */
public record EncryptedContent(byte[] body) {

    public EncryptedContent {
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
