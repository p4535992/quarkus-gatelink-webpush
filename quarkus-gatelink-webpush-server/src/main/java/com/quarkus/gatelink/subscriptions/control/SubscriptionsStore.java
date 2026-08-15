package com.quarkus.gatelink.subscriptions.control;

import java.util.List;

import com.quarkus.gatelink.subscriptions.entity.PushSubscription;
import com.quarkus.gatelink.subscriptions.entity.StoredPushSubscription;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * PostgreSQL-backed store for browser PushSubscription records.
 *
 * Keeping this state durable is important: a subscription must survive a
 * server restart if GateLink is expected to keep delivering notifications.
 */
@ApplicationScoped
public class SubscriptionsStore implements PanacheRepositoryBase<StoredPushSubscription, String> {

    public long numberOfSubscriptions() {
        return count();
    }

    /**
     * Atomically creates or refreshes a subscription identified by its endpoint.
     *
     * PostgreSQL's ON CONFLICT avoids a read-before-write race when the same
     * browser subscription is registered concurrently or re-registered by a UI.
     * Because this write is native SQL, the JPA persistence context is cleared
     * before and after it so cached entities cannot hide the database update.
     */
    @Transactional
    public void addSubscription(PushSubscription subscription) {
        getEntityManager().clear();
        getEntityManager().createNativeQuery("""
                INSERT INTO push_subscriptions (endpoint, p256dh, auth)
                VALUES (:endpoint, :p256dh, :auth)
                ON CONFLICT (endpoint) DO UPDATE SET
                    p256dh = EXCLUDED.p256dh,
                    auth = EXCLUDED.auth
                """)
                .setParameter("endpoint", subscription.endpoint)
                .setParameter("p256dh", subscription.getP256dh())
                .setParameter("auth", subscription.getAuth())
                .executeUpdate();
        getEntityManager().clear();
    }

    /**
     * Reads a fresh view from PostgreSQL rather than returning a previously
     * materialized StoredPushSubscription from the first-level JPA cache.
     */
    @Transactional
    public List<PushSubscription> all() {
        getEntityManager().clear();
        return listAll().stream()
                .map(StoredPushSubscription::toPushSubscription)
                .toList();
    }

    @Transactional
    public void removeAll() {
        deleteAll();
    }

    @Transactional
    public void remove(String endpoint) {
        deleteById(endpoint);
    }
}
