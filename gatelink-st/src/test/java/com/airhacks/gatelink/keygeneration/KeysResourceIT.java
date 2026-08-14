package com.airhacks.gatelink.keygeneration;

import com.airhacks.gatelink.SystemTest;

import io.quarkus.test.junit.QuarkusTest;

import java.util.Base64;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author airhacks.com
 */
@QuarkusTest
public class KeysResourceIT {

    private WebTarget tut;

    @BeforeEach
    public void init() {
        this.tut = SystemTest.target("keys");
    }

    @Test
    public void publicKeyIsAvailable() {
        String publicKey = fetchPublicKey();
        assertNotNull(publicKey);
        byte[] decoded = Base64.getDecoder().decode(publicKey);
        assertThat(decoded).hasSize(65);
        assertThat(decoded[0]).isEqualTo((byte) 0x04);
    }

    @Test
    public void alwaysReturnsTheSamePublicKey() {
        String first = fetchPublicKey();
        String next = fetchPublicKey();
        assertThat(first).isEqualTo(next);
    }

    String fetchPublicKey() {
        Response response = this.tut.path("public")
                .request(MediaType.TEXT_PLAIN)
                .get();
        assertThat(response.getStatus()).isEqualTo(200);
        return response.readEntity(String.class);
    }
}
