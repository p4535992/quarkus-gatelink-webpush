package com.quarkus.gatelink.notifications.boundary;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;

import com.quarkus.gatelink.encryption.boundary.EncryptionService;

import io.smallrye.faulttolerance.api.RateLimit;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/** Administrative notification fan-out endpoint. */
@ApplicationScoped
@Path("/notifications")
public class NotificationsResource {

    @Inject
    NotificationsSender sender;

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @RolesAllowed("gatelink-admin")
    @RateLimit(value = 20, window = 1, windowUnit = ChronoUnit.MINUTES)
    public void send(
            @NotBlank
            @Size(max = EncryptionService.MAX_PLAINTEXT_LENGTH) String message) {
        if (message.getBytes(StandardCharsets.UTF_8).length > EncryptionService.MAX_PLAINTEXT_LENGTH) {
            throw new BadRequestException(
                    "Web Push payload exceeds " + EncryptionService.MAX_PLAINTEXT_LENGTH + " UTF-8 octets");
        }
        this.sender.send(message);
    }
}
