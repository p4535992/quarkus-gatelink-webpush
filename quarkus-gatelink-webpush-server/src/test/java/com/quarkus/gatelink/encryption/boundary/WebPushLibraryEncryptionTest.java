package com.quarkus.gatelink.encryption.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Base64;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.quarkus.gatelink.notifications.boundary.Notification;
import com.quarkus.gatelink.subscriptions.entity.PushSubscription;

import jakarta.json.Json;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.HttpEce;

class WebPushLibraryEncryptionTest {

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Test
    void libraryMatchesRfc8188Aes128gcmVector() throws Exception {
        var httpEce = new HttpEce();
        var plaintext = "I am the walrus".getBytes(StandardCharsets.UTF_8);
        var salt = DECODER.decode("I1BsxtFttlv3u_Oo94xnmw");
        var key = DECODER.decode("yqdlZ-tYemfogSmv7Ws5PQ");
        var expected = DECODER.decode(
                "I1BsxtFttlv3u_Oo94xnmwAAEAAA-NAVub2qFgBEuQKRapoZu-IxkIva3MEB1PD-ly8Thjg");

        var actual = httpEce.encrypt(plaintext, salt, key, null, null, null, Encoding.AES128GCM);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void gateLinkAlwaysBuildsAes128gcmBodyThroughLibrary() {
        var service = new EncryptionService();
        service.initializeCryptoProvider();

        var encrypted = service.encrypt(new Notification(subscription(), "hello"));
        var body = encrypted.body();

        assertThat(body.length).isGreaterThan(86);
        assertThat(ByteBuffer.wrap(body, 16, Integer.BYTES).getInt()).isEqualTo(4096);
        assertThat(Byte.toUnsignedInt(body[20])).isEqualTo(65);
    }

    @Test
    void rejectsPayloadAboveWebPushLimitBeforeLibraryCall() {
        var service = new EncryptionService();
        service.initializeCryptoProvider();
        var notification = new Notification(
                subscription(),
                "x".repeat(EncryptionService.MAX_PLAINTEXT_LENGTH + 1));

        assertThatThrownBy(() -> service.encrypt(notification))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    private static PushSubscription subscription() {
        var subscription = new PushSubscription();
        subscription.endpoint = "https://push.example.net/push/example";
        subscription.keys = Json.createObjectBuilder()
                .add("p256dh", "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
                .add("auth", "BTBZMqHH6r4Tts7J_aSIgg")
                .build();
        return subscription;
    }
}
