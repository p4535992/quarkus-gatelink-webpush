package com.airhacks.gatelink.keymanagement.boundary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Exposes only the public VAPID key required by browser subscriptions.
 *
 * @author airhacks.com
 */
@Path("/keys")
@ApplicationScoped
public class KeysResource {

    @Inject
    InMemoryKeyStore store;

    @GET
    @Path("public")
    @Produces(MediaType.TEXT_PLAIN)
    public String getPublicKey() {
        return this.store.getKeys().getBase64PublicKeyWithoutPadding();
    }
}
