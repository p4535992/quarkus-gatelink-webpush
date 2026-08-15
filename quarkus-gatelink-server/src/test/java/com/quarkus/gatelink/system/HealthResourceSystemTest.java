package com.quarkus.gatelink.system;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HealthResourceSystemTest {

    @TestHTTPResource
    URI baseUri;

    @Test
    void healthEndpointIsUp() {
        GateLinkApi api = SystemTestClient.create(baseUri);
        try (Response response = api.health()) {
            assertThat(response.getStatus()).isEqualTo(200);

            JsonObject health = response.readEntity(JsonObject.class);
            JsonArray checks = health.getJsonArray("checks");
            List<JsonObject> checkList = checks.getValuesAs(JsonObject.class);

            assertThat(checkList)
                    .anySatisfy(check -> assertThat(check.getString("name")).isEqualTo("pushserver"));
        }
    }
}
