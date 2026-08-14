package com.airhacks.gatelink.notifications.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.airhacks.gatelink.notifications.control.PushServiceClient.NotificationResponse;

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
}
