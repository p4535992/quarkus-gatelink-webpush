/*
 */
package com.airhacks.gatelink.notifications.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.airhacks.gatelink.EncryptionTestEnvironment;
import com.airhacks.gatelink.encryption.boundary.EncryptionServiceIT;
import com.airhacks.gatelink.log.boundary.Tracer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 *
 * @author airhacks.com
 */
public class NotificationsSenderIT extends EncryptionTestEnvironment {

    private NotificationsSender cut;

    @BeforeEach
    public void initialize() throws Exception {
        super.init("chrome");
        this.cut = new NotificationsSender();
        this.cut.registry = new SimpleMeterRegistry();
        this.cut.tracer = new Tracer();
        this.cut.encryptionService = new EncryptionServiceIT().getCut();
    }

    public NotificationsSender getCut() throws Exception {
        this.initialize();
        return cut;
    }

    @Test
    public void sendNotification() throws IOException {
        var subscriptionOptional = this.getCurrentSubscription();
        assumeTrue(subscriptionOptional.isPresent());
        var notification = this.serverKeysWithSubscription.getNotification("hey duke " + System.currentTimeMillis());
        var response = this.cut.send(notification, this.serverKeysWithSubscription.getServerKeys());
        assertThat(response).isTrue();
    }

    Optional<String> getCurrentSubscription() throws IOException {
        var subscriptionPath = Path.of("src/test/resources/subscription.json");
        var subscriptionContent = Files.readString(subscriptionPath);
        if (subscriptionContent.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(subscriptionContent);
    }
}
