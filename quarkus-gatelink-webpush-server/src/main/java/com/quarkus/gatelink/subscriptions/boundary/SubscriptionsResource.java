package com.quarkus.gatelink.subscriptions.boundary;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.quarkus.gatelink.subscriptions.control.SubscriptionsStore;
import com.quarkus.gatelink.subscriptions.entity.PushSubscription;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.stream.JsonCollectors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@RequestScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Path("/subscriptions")
public class SubscriptionsResource {

    @Inject
    SubscriptionsStore store;

    /** Browser registration remains public; the payload is strictly validated. */
    @POST
    public void subscribe(@Valid @NotNull PushSubscription subscription) {
        this.store.addSubscription(subscription);
    }

    /** Administrative destructive operation. */
    @DELETE
    @RolesAllowed("gatelink-admin")
    public void removeAll() {
        this.store.removeAll();
    }

    /**
     * Browser unsubscribe remains public so the current browser integration can
     * remove its own known endpoint. The encoded path parameter is canonical
     * Base64URL and bounded before decoding.
     */
    @DELETE
    @Path("{endpoint}")
    public void unsubscribe(
            @PathParam("endpoint")
            @NotBlank
            @Size(max = 4096)
            @Pattern(regexp = "[A-Za-z0-9_-]+") String endpoint) {
        try {
            byte[] rawEndpoint = Base64.getUrlDecoder().decode(endpoint);
            this.store.remove(new String(rawEndpoint, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid Base64URL subscription endpoint", ex);
        }
    }

    /** Subscription inventory is administrative data. */
    @GET
    @RolesAllowed("gatelink-admin")
    public JsonArray all() {
        return this.store.all().stream()
                .map(subscription -> subscription.endpoint)
                .map(Json::createValue)
                .collect(JsonCollectors.toJsonArray());
    }
}
