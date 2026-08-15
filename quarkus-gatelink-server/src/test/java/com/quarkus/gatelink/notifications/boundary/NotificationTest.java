package com.quarkus.gatelink.notifications.boundary;

import com.quarkus.gatelink.notifications.boundary.Notification;
import com.quarkus.gatelink.log.boundary.Tracer;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.InvalidKeySpecException;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

public class NotificationTest {

    @Test
    public void getAuthAsBytes() throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeySpecException {
        byte[] converted = Notification.convertAuth("04APBRKx_UeyMvcShSNzKA");
        assertNotNull(converted);
        new Tracer().log("converted", converted);
    }


}
