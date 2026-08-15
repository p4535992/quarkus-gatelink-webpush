package com.quarkus.gatelink.system;

import java.net.URI;

import org.eclipse.microprofile.rest.client.RestClientBuilder;

import jakarta.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared factory/helpers for HTTP-level GateLink tests. */
final class SystemTestClient {

    private SystemTestClient() {
    }

    static GateLinkApi create(URI baseUri) {
        return RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .build(GateLinkApi.class);
    }

    static void clearSubscriptions(GateLinkApi api) {
        try (Response response = api.removeAllSubscriptions()) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
    }
}
