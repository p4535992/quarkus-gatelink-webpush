package com.quarkus.gatelink.system;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestSecurity(user = "admin", roles = "gatelink-admin")
class SubscriptionsResourceSystemTest {

    private static final String ENDPOINT = "https://push.example.test/subscriptions/system-test";
    private static final String AUTH_SECRET = "AAAAAAAAAAAAAAAAAAAAAA";

    @TestHTTPResource
    URI baseUri;

    private GateLinkApi api;

    @BeforeEach
    void resetSubscriptions() {
        this.api = SystemTestClient.create(baseUri);
        SystemTestClient.clearSubscriptions(api);
    }

    @Test
    void subscribingTwiceIsIdempotent() {
        JsonObject subscription = subscription();

        assertNoContent(api.subscribe(subscription));
        assertNoContent(api.subscribe(subscription));

        assertThat(allSubscriptions()).containsExactly(ENDPOINT);
    }

    @Test
    void subscribesListsAndDeletes() {
        assertNoContent(api.subscribe(subscription()));
        assertThat(allSubscriptions()).containsExactly(ENDPOINT);

        assertNoContent(api.unsubscribe(encodeEndpoint(ENDPOINT)));
        assertThat(allSubscriptions()).doesNotContain(ENDPOINT);
    }

    @Test
    void removeAllClearsTheDatabase() {
        assertNoContent(api.subscribe(subscription()));
        SystemTestClient.clearSubscriptions(api);
        assertThat(allSubscriptions()).isEmpty();
    }

    private JsonObject subscription() {
        return Json.createObjectBuilder()
                .add("endpoint", ENDPOINT)
                .add("keys", Json.createObjectBuilder()
                        .add("p256dh", publicKey())
                        .add("auth", AUTH_SECRET))
                .build();
    }

    private String publicKey() {
        try (Response response = api.publicKey()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(String.class);
        }
    }

    private java.util.List<String> allSubscriptions() {
        try (Response response = api.subscriptions()) {
            assertThat(response.getStatus()).isEqualTo(200);
            JsonArray subscriptions = response.readEntity(JsonArray.class);
            return subscriptions.getValuesAs(JsonString.class)
                    .stream()
                    .map(JsonString::getString)
                    .toList();
        }
    }

    private static String encodeEndpoint(String endpoint) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(endpoint.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertNoContent(Response response) {
        try (response) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
    }
}
