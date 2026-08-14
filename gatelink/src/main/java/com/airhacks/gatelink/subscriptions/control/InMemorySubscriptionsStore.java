package com.airhacks.gatelink.subscriptions.control;

import com.airhacks.gatelink.subscriptions.entity.PushSubscription;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory subscription store. This is suitable for development and demos;
 * production deployments should replace it with durable storage.
 *
 * @author airhacks.com
 */
@ApplicationScoped
public class InMemorySubscriptionsStore {

    private ConcurrentMap<String, PushSubscription> store;

    @PostConstruct
    public void initialize() {
        this.store = new ConcurrentHashMap<>();
    }

    public int numberOfSubscriptions() {
        return this.store.size();
    }

    public void addSubscription(PushSubscription subscription) {
        this.store.put(subscription.endpoint, subscription);
    }

    public List<PushSubscription> all() {
        return new ArrayList<>(this.store.values());
    }

    public void removeAll() {
        this.store.clear();
    }

    public void remove(String endpoint) {
        this.store.remove(endpoint);
    }
}
