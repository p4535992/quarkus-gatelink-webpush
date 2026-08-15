package com.quarkus.gatelink.subscriptions.entity;

import jakarta.json.Json;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Durable database representation of a browser PushSubscription.
 *
 * The HTTP/API model remains {@link PushSubscription}; this entity exists only
 * to map the PostgreSQL row back to the Web Push API model. Writes are handled
 * atomically by SubscriptionsStore.
 */
@Entity
@Table(name = "push_subscriptions")
public class StoredPushSubscription {

    @Id
    @Column(name = "endpoint", nullable = false, columnDefinition = "text")
    public String endpoint;

    @Column(name = "p256dh", nullable = false, columnDefinition = "text")
    public String p256dh;

    @Column(name = "auth", nullable = false, columnDefinition = "text")
    public String auth;

    protected StoredPushSubscription() {
    }

    public PushSubscription toPushSubscription() {
        var subscription = new PushSubscription();
        subscription.endpoint = this.endpoint;
        subscription.keys = Json.createObjectBuilder()
                .add("p256dh", this.p256dh)
                .add("auth", this.auth)
                .build();
        return subscription;
    }
}
