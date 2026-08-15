package com.quarkus.gatelink.keymanagement.boundary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Exposes only the public VAPID key required by browser subscriptions.
 *
 * The key is returned as unpadded Base64URL, matching the representation used
 * by the Push API applicationServerKey and RFC 8292 VAPID.
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
        return this.store.getKeys().getBase64URLEncodedPublicKeyWithoutPadding();
    }
}
