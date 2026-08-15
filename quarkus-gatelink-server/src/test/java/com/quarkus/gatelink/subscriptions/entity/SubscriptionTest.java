package com.quarkus.gatelink.subscriptions.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import jakarta.json.Json;
import jakarta.json.bind.JsonbBuilder;

class SubscriptionTest {

    @Test
    void acceptsCanonicalBrowserKeyMaterial() {
        var subscription = subscription();

        assertThat(subscription.isEndpointValid()).isTrue();
        assertThat(subscription.isP256dhValid()).isTrue();
        assertThat(subscription.isAuthValid()).isTrue();
    }

    @Test
    void jsonbSerializesOnlyPushApiFields() {
        var subscription = subscription();

        String json = JsonbBuilder.create().toJson(subscription);

        assertThat(json).contains("endpoint", "keys", "p256dh", "auth");
        assertThat(json).doesNotContain("endpointValid", "p256dhValid", "authValid");
    }

    private static PushSubscription subscription() {
        var subscription = new PushSubscription();
        subscription.endpoint = "https://push.example.net/push/test";
        subscription.keys = Json.createObjectBuilder()
                .add("p256dh", "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
                .add("auth", "BTBZMqHH6r4Tts7J_aSIgg")
                .build();
        return subscription;
    }
}
