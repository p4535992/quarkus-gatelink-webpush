package com.quarkus.gatelink.notifications.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.quarkus.gatelink.notifications.control.PushServiceClient.NotificationResponse;

class PushServiceClientTest {

    @Test
    void acceptsOnlyTwoHundredStatusCodes() {
        assertThat(new NotificationResponse(200).isSuccessful()).isTrue();
        assertThat(new NotificationResponse(204).isSuccessful()).isTrue();
        assertThat(new NotificationResponse(299).isSuccessful()).isTrue();
        assertThat(new NotificationResponse(300).isSuccessful()).isFalse();
        assertThat(new NotificationResponse(400).isSuccessful()).isFalse();
        assertThat(new NotificationResponse(500).isSuccessful()).isFalse();
    }

    @Test
    void buildsOnlyModernWebPushHeaders() {
        var request = PushServiceClient.request(
                "https://push.example.net/push/123",
                "vapid-public-key",
                "signed-jwt",
                new byte[] { 1, 2, 3 });

        assertThat(request.headers().firstValue("Content-Encoding")).contains("aes128gcm");
        assertThat(request.headers().firstValue("Authorization"))
                .contains("vapid t=signed-jwt, k=vapid-public-key");
        assertThat(request.headers().firstValue("Encryption")).isEmpty();
        assertThat(request.headers().firstValue("Crypto-Key")).isEmpty();
    }
}
