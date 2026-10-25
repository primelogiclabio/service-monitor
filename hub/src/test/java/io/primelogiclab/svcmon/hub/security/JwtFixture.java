package io.primelogiclab.svcmon.hub.security;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** A pretend OpenID Connect provider's signing key and the tokens it would issue, for the sign-in tests. */
public final class JwtFixture {

    public static final String AUDIENCE = "https://monitor.example.test/api";
    public static final String ADMIN_ROLE = "platform-admin";

    public final String issuer;
    public final String kid;
    public final RSAKey key;

    public JwtFixture(String issuer) {
        this(issuer, "key-1");
    }

    public JwtFixture(String issuer, String kid) {
        this.issuer = issuer;
        this.kid = kid;
        try {
            this.key = new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The provider's published keys (public parts only), as its jwks_uri would serve them. */
    public String jwksJson() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    /** Claims of a good token for an administrator: right issuer and audience, five minutes to live. */
    public JWTClaimsSet.Builder claims() {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(AUDIENCE)
                .subject("5b0f3c1e-0000-4000-8000-000000000001")
                .claim("preferred_username", "alice")
                .issueTime(new Date(now))
                .expirationTime(new Date(now + 5 * 60_000))
                .claim("realm_access", Map.of("roles", List.of("offline_access", ADMIN_ROLE)));
    }

    public String admin() {
        return sign(claims().build());
    }

    public String sign(JWTClaimsSet claims) {
        return sign(key, kid, claims);
    }

    public static String sign(RSAKey signer, String kid, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(signer));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
