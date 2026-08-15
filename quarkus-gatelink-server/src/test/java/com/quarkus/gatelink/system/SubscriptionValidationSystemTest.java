package com.quarkus.gatelink.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class SubscriptionValidationSystemTest {

    private static final String VALID_P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String VALID_AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    private final HttpClient http = HttpClient.newHttpClient();

    @TestHTTPResource
    URI baseUri;

    @Test
    void rejectsNonHttpsPushEndpoint() throws Exception {
        var body = """
                {
                  "endpoint": "http://localhost/internal",
                  "keys": {
                    "p256dh": "%s",
                    "auth": "%s"
                  }
                }
                """.formatted(VALID_P256DH, VALID_AUTH);

        assertBadSubscription(body);
    }

    @Test
    void rejectsInvalidAuthSecret() throws Exception {
        var body = """
                {
                  "endpoint": "https://push.example.net/push/validation",
                  "keys": {
                    "p256dh": "%s",
                    "auth": "too-short"
                  }
                }
                """.formatted(VALID_P256DH);

        assertBadSubscription(body);
    }

    private void assertBadSubscription(String json) throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve("/subscriptions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(400);
    }
}
