package com.quarkus.gatelink.notifications.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NotificationsSenderTest {

    @Test
    void extractsHttpsOriginForVapidAudience() {
        var endpoint = "https://updates.push.services.mozilla.com/wpush/v2/example";
        assertThat(NotificationsSender.extractAud(endpoint))
                .isEqualTo("https://updates.push.services.mozilla.com");
    }

    @Test
    void preservesNonDefaultPortInVapidAudience() {
        assertThat(NotificationsSender.extractAud("https://push.example.net:8443/push/123"))
                .isEqualTo("https://push.example.net:8443");
    }

    @Test
    void rejectsNonHttpsOrRelativePushEndpoints() {
        assertThatThrownBy(() -> NotificationsSender.extractAud("http://push.example.net/push/123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationsSender.extractAud("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
