package com.airhacks.gatelink.keymanagement.boundary;

import java.security.GeneralSecurityException;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.airhacks.gatelink.keymanagement.control.ECKeyGenerator;
import com.airhacks.gatelink.keymanagement.control.KeyLoader;
import com.airhacks.gatelink.keymanagement.entity.ECKeys;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Holds the application-server VAPID key pair.
 *
 * Configure {@code webpush.vapid.public-key} and
 * {@code webpush.vapid.private-key} with Base64URL encoded P-256 keys for
 * stable production identities. If neither is configured, a temporary key
 * pair is generated for development compatibility.
 *
 * @author airhacks.com
 */
@ApplicationScoped
public class InMemoryKeyStore {

    private static final Logger LOG = Logger.getLogger(InMemoryKeyStore.class);

    @Inject
    @ConfigProperty(name = "webpush.vapid.public-key")
    Optional<String> configuredPublicKey;

    @Inject
    @ConfigProperty(name = "webpush.vapid.private-key")
    Optional<String> configuredPrivateKey;

    private ECKeys keys;

    @PostConstruct
    public void initializeProvider() {
        if (configuredPublicKey.isPresent() != configuredPrivateKey.isPresent()) {
            throw new IllegalStateException(
                    "Configure both webpush.vapid.public-key and webpush.vapid.private-key, or neither");
        }

        if (configuredPublicKey.isPresent()) {
            this.keys = loadConfiguredKeys(configuredPrivateKey.orElseThrow(), configuredPublicKey.orElseThrow());
            LOG.info("Loaded configured VAPID key pair");
            return;
        }

        this.keys = ECKeyGenerator.generate();
        LOG.warn("No persistent VAPID keys configured; generated a temporary key pair for this process");
    }

    private static ECKeys loadConfiguredKeys(String privateKey, String publicKey) {
        try {
            return new ECKeys(
                    KeyLoader.loadURLEncodedPrivateKey(privateKey),
                    KeyLoader.loadUrlEncodedPublicKey(publicKey));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot load configured VAPID key pair", ex);
        }
    }

    public ECKeys getKeys() {
        return this.keys;
    }
}
