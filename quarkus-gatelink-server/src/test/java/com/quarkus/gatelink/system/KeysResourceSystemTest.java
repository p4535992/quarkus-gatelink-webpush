package com.quarkus.gatelink.system;

import java.net.URI;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class KeysResourceSystemTest {

    @TestHTTPResource
    URI baseUri;

    @Test
    void publicKeyIsAvailableAndValidP256() {
        String publicKey = fetchPublicKey();
        byte[] decoded = Base64.getUrlDecoder().decode(publicKey);

        assertThat(decoded).hasSize(65);
        assertThat(decoded[0]).isEqualTo((byte) 0x04);
    }

    @Test
    void publicKeyIsStableWithinTheRunningServer() {
        assertThat(fetchPublicKey()).isEqualTo(fetchPublicKey());
    }

    private String fetchPublicKey() {
        GateLinkApi api = SystemTestClient.create(baseUri);
        try (Response response = api.publicKey()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(String.class);
        }
    }
}
