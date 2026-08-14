package com.airhacks.gatelink.keygeneration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.airhacks.gatelink.keymanagement.control.ECKeyGenerator;

/**
 * @author airhacks.com
 */
public class KeyGeneratorTest {

    @Test
    public void createKeys() {
        var vapidKeys = ECKeyGenerator.generate();

        byte[] privateKey = vapidKeys.getPrivateKeyAsBytes();
        assertThat(privateKey).hasSize(32);

        byte[] publicKey = vapidKeys.getUncompressedPublicKey();
        assertThat(publicKey).hasSize(65);
        // RFC 5480 uncompressed EC point marker.
        assertThat(publicKey[0]).isEqualTo((byte) 0x04);

        assertThat(vapidKeys.getBase64URLEncodedPublicKeyWithoutPadding()).doesNotContain("=");
        assertThat(vapidKeys.getBase64URLEncodedPrivateKeyWithoutPadding()).doesNotContain("=");
    }
}
