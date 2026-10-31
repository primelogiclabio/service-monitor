package io.primelogiclab.svcmon.hub.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.config.HubProperties.Oidc.ClientAuth;
import io.primelogiclab.svcmon.hub.oidc.FileKey;
import io.primelogiclab.svcmon.hub.oidc.HubKey;
import io.primelogiclab.svcmon.hub.oidc.OidcSettings;
import io.primelogiclab.svcmon.hub.oidc.ProviderClient.ProviderException;
import io.primelogiclab.svcmon.hub.oidc.ProviderSignIn;
import io.primelogiclab.svcmon.hub.oidc.SigningKey;
import io.primelogiclab.svcmon.hub.security.NetworkPolicy;
import io.primelogiclab.svcmon.hub.security.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in with the identity provider. {@code /start} and {@code /callback} are where the browser is sent, so they
 * answer with redirects: on to the provider, then back to the dashboard, which reads {@code signin_error} when it did
 * not work. They need no sign-in, but follow the network rules for people (see {@link NetworkPolicy#forUser}).
 * {@code /jwks} is the hub's public key, open to anyone, the provider included. The provider's settings, for the
 * Settings screen, are for administrators.
 */
@RestController
@RequestMapping("/api/v1/oidc")
public class OidcController {

    /** Ties a sign-in to the browser that started it; Lax, because the provider sends the browser back from its site. */
    static final String BINDING_COOKIE = "__Host-svcmon-oidc";
    static final String PLAIN_BINDING_COOKIE = "svcmon-oidc";

    private static final Logger log = LoggerFactory.getLogger(OidcController.class);

    /**
     * What the Settings screen shows; nothing secret (the private key never leaves the hub). {@code keySource} is
     * {@code hub} for the pair the hub made, {@code files} for one it was given; then {@code certificatePem} and
     * {@code certificateExpiresAt} (epoch milliseconds) describe its certificate.
     */
    public record ProviderView(boolean configured, String issuer, String clientId, String clientAuth,
                               String signingAlgorithm, String keyId, String publicKeyPem, String keySource,
                               String privateKeyFile, String certificatePem, Long certificateExpiresAt,
                               String redirectUri, String jwksUri, String postLogoutRedirectUri, List<String> scopes,
                               String rolesClaim, String adminRole, String readRole, String usernameClaim,
                               String buttonLabel, boolean reachable, String problem, boolean signOutAtProvider) {
    }

    private final ProviderSignIn signIn;
    private final SessionService sessions;
    private final NetworkPolicy network;
    private final AuditService audit;

    public OidcController(ProviderSignIn signIn, SessionService sessions, NetworkPolicy network, AuditService audit) {
        this.signIn = signIn;
        this.sessions = sessions;
        this.network = network;
        this.audit = audit;
    }

    @GetMapping
    public ProviderView view() {
        OidcSettings s = signIn.settings();
        if (s == null) {
            return new ProviderView(false, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, false, null, false);
        }
        SigningKey key = signIn.key();
        boolean privateKey = s.clientAuth() == ClientAuth.PRIVATE_KEY_JWT;
        FileKey file = key instanceof FileKey f ? f : null;
        return new ProviderView(true, s.issuer().getValue(), s.clientId().getValue(),
                privateKey ? "private-key-jwt" : "client-secret", privateKey ? s.signingAlgorithm().getName() : null,
                signIn.keyId(), key == null ? null : HubKey.pem(key.current()),
                key == null ? null : file != null ? "files" : "hub", file == null ? null : file.keyFile().toString(),
                file == null ? null : certificatePem(file.certificate()),
                file == null ? null : file.expiresAt().toEpochMilli(), s.redirectUri().toString(),
                privateKey ? s.publicUrl() + OidcSettings.JWKS : null, s.postLogoutRedirectUri().toString(),
                s.scope().toStringList(), s.rolesClaim(), s.adminRole(), s.readRole(), s.usernameClaim(),
                s.buttonLabel(), signIn.client().reachable(), signIn.client().problem(),
                signIn.endSessionUri() != null);
    }

    @GetMapping("/start")
    public ResponseEntity<?> start(HttpServletRequest req, HttpServletResponse resp) {
        if (!signIn.enabled()) {
            return notConfigured();
        }
        NetworkPolicy.Refusal refusal = network.forUser(req);
        if (refusal != null) {
            audit.refused(refusal.code, req.getRemoteAddr(), "GET " + OidcSettings.START);
            return back(refusal.code);
        }
        try {
            ProviderSignIn.Start start = signIn.start();
            resp.addHeader(HttpHeaders.SET_COOKIE, SessionService.cookie(req, BINDING_COOKIE, PLAIN_BINDING_COOKIE,
                    "Lax", start.binding(), TimeUnit.MINUTES.toSeconds(ProviderSignIn.PENDING_MINUTES)).toString());
            return redirect(start.redirect());
        } catch (ProviderException e) {
            log.warn("Could not start a sign-in with the identity provider for {}: {}", req.getRemoteAddr(),
                    e.getMessage());
            return back(e.code);
        }
    }

    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) String code,
                                      @RequestParam(required = false) String state,
                                      @RequestParam(required = false) String error,
                                      HttpServletRequest req, HttpServletResponse resp) {
        if (!signIn.enabled()) {
            return notConfigured();
        }
        String binding = SessionService.cookie(req, BINDING_COOKIE, PLAIN_BINDING_COOKIE);
        resp.addHeader(HttpHeaders.SET_COOKIE, SessionService.cookie(req, BINDING_COOKIE, PLAIN_BINDING_COOKIE, "Lax",
                "", 0).toString());
        NetworkPolicy.Refusal refusal = network.forUser(req);
        if (refusal != null) {
            audit.refused(refusal.code, req.getRemoteAddr(), "GET " + OidcSettings.CALLBACK);
            return back(refusal.code);
        }
        ProviderSignIn.Finish done = signIn.finish(code, state, error, binding, req.getRemoteAddr(),
                req.getHeader("User-Agent"));
        if (done.error() != null) {
            return back(done.error());
        }
        sessions.writeCookie(req, resp, done.session().raw());
        return redirect(URI.create(signIn.settings().publicUrl() + "/"));
    }

    @GetMapping("/jwks")
    public ResponseEntity<?> jwks() {
        SigningKey key = signIn.key();
        if (key == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found",
                    "message", "This hub signs in to no identity provider with a key of its own"));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-cache").contentType(MediaType.APPLICATION_JSON)
                .body(key.publicKeys().toString());
    }

    /** The certificate as PEM, for a provider that wants it uploaded (it holds only the public key). */
    static String certificatePem(X509Certificate cert) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(cert.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            return null;
        }
    }

    /** Back to the dashboard, which explains {@code code}; the codes are the hub's own, never the provider's text. */
    private ResponseEntity<?> back(String code) {
        return redirect(URI.create(signIn.settings().publicUrl() + "/?signin_error=" + code));
    }

    private static ResponseEntity<?> redirect(URI to) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, to.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static ResponseEntity<?> notConfigured() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ProviderSignIn.NOT_CONFIGURED,
                "message", "This hub has no identity provider (svcmon.oidc)"));
    }
}
