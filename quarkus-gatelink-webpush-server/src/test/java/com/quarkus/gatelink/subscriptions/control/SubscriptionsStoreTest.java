package com.quarkus.gatelink.subscriptions.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.quarkus.gatelink.subscriptions.entity.PushSubscription;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.json.Json;

@QuarkusTest
class SubscriptionsStoreTest {

    @Inject
    SubscriptionsStore store;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        store.removeAll();
    }

    @Test
    void persistsAndUpdatesSubscription() {
        var first = subscription("https://push.example.test/subscription/1", "p256dh-first", "auth-first");
        store.addSubscription(first);

        assertThat(store.numberOfSubscriptions()).isEqualTo(1);
        assertThat(store.all()).singleElement().satisfies(saved -> {
            assertThat(saved.endpoint).isEqualTo(first.endpoint);
            assertThat(saved.getP256dh()).isEqualTo("p256dh-first");
            assertThat(saved.getAuth()).isEqualTo("auth-first");
        });

        var updated = subscription(first.endpoint, "p256dh-updated", "auth-updated");
        store.addSubscription(updated);

        assertThat(store.numberOfSubscriptions()).isEqualTo(1);
        assertThat(store.all()).singleElement().satisfies(saved -> {
            assertThat(saved.getP256dh()).isEqualTo("p256dh-updated");
            assertThat(saved.getAuth()).isEqualTo("auth-updated");
        });
    }

    @Test
    void removesSubscription() {
        var subscription = subscription("https://push.example.test/subscription/2", "p256dh", "auth");
        store.addSubscription(subscription);

        store.remove(subscription.endpoint);

        assertThat(store.numberOfSubscriptions()).isZero();
    }

    private static PushSubscription subscription(String endpoint, String p256dh, String auth) {
        var subscription = new PushSubscription();
        subscription.endpoint = endpoint;
        subscription.keys = Json.createObjectBuilder()
                .add("p256dh", p256dh)
                .add("auth", auth)
                .build();
        return subscription;
    }
}
