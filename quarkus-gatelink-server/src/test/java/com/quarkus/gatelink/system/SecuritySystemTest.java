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
class SecuritySystemTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @TestHTTPResource
    URI baseUri;

    @Test
    void anonymousCallerCannotSendNotifications() throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve("/notifications"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("not-authorized"))
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void anonymousCallerCannotListSubscriptions() throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve("/subscriptions"))
                .GET()
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
