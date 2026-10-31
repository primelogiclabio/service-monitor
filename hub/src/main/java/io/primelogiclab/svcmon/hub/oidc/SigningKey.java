package io.primelogiclab.svcmon.hub.oidc;

import java.security.cert.X509Certificate;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * The RSA key pair the hub proves who it is with at the identity provider (private_key_jwt): one the hub makes itself
 * ({@link HubKey}), or one from files, such as signing-keys-tool writes ({@link FileKey}). Only the public half ever
 * leaves the hub.
 */
public interface SigningKey {

    /** The key with its private half, read again when its file changed. */
    RSAKey current();

    /** The certificate for the key, or null for a key without one (the hub's own). */
    X509Certificate certificate();

    /** Only the public half (with the certificate, when there is one), as the provider fetches it. */
    default JWKSet publicKeys() {
        return new JWKSet(current().toPublicJWK());
    }
}
