package com.quarkus.gatelink.system;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestSecurity(user = "admin", roles = "gatelink-admin")
class NotificationsResourceSystemTest {

    @TestHTTPResource
    URI baseUri;

    private GateLinkApi api;

    @BeforeEach
    void clearSubscriptions() {
        this.api = SystemTestClient.create(baseUri);
        SystemTestClient.clearSubscriptions(api);
    }

    @Test
    void acceptsNotificationWhenNoBrowsersAreSubscribed() {
        try (Response response = api.sendNotification("system-test")) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
    }
}
