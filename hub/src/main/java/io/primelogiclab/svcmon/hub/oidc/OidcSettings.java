package io.primelogiclab.svcmon.hub.oidc;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.config.HubProperties.Oidc.ClientAuth;
import io.primelogiclab.svcmon.hub.config.PublicUrl;

/**
 * svcmon.oidc, checked once at start-up so a mistake stops the hub with the setting's name instead of failing the
 * first person who signs in. The redirect URI is built from svcmon.public-url, never from a request's Host, so a
 * forged Host cannot send a sign-in code anywhere else.
 */
public record OidcSettings(
        Issuer issuer,
        ClientID clientId,
        ClientAuth clientAuth,
        Path clientSecretFile,
        Path privateKeyFile,
        Path certificateFile,
        JWSAlgorithm signingAlgorithm,
        Scope scope,
        List<String> rolesPath,
        String rolesClaim,
        String adminRole,
        String readRole,
        String usernameClaim,
        String buttonLabel,
        String publicUrl,
        URI redirectUri,
        URI postLogoutRedirectUri) {

    public static final String START = "/api/v1/oidc/start";
    public static final String CALLBACK = "/api/v1/oidc/callback";
    public static final String JWKS = "/api/v1/oidc/jwks";

    /** The ways the hub may sign its proof with its RSA key. */
    static final Set<JWSAlgorithm> SIGNING = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.PS256);

    private static final Pattern SCOPE_TOKEN = Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,100}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    /** Null when svcmon.oidc.issuer is unset. Throws IllegalStateException naming the setting for a bad value. */
    public static OidcSettings from(HubProperties props) {
        HubProperties.Oidc o = props.oidc() == null ? HubProperties.Oidc.DEFAULT : props.oidc();
        if (!o.enabled()) {
            return null;
        }
        URI issuerUri = issuer(o.issuer().strip());
        String clientId = required("client-id", o.clientId());
        if (clientId.length() > 255 || CONTROL.matcher(clientId).find()) {
            throw invalid("client-id", "it must be one line of at most 255 characters");
        }
        ClientAuth auth = o.clientAuth() == null ? ClientAuth.PRIVATE_KEY_JWT : o.clientAuth();
        Path keyFile = o.privateKeyFile();
        Path certFile = o.certificateFile();
        if ((keyFile != null || certFile != null) && auth != ClientAuth.PRIVATE_KEY_JWT) {
            throw invalid(keyFile != null ? "private-key-file" : "certificate-file", "it is only for client-auth:"
                    + " private-key-jwt");
        }
        if (keyFile != null && certFile == null) {
            throw invalid("certificate-file", "private-key-file needs the key's certificate too (signing-keys-tool"
                    + " writes it beside the key as <name>-cert.pem)");
        }
        if (certFile != null && keyFile == null) {
            throw invalid("private-key-file", "certificate-file needs the private key it belongs to");
        }
        Path secretFile = o.clientSecretFile();
        if (auth == ClientAuth.CLIENT_SECRET) {
            if (secretFile == null) {
                throw invalid("client-secret-file", "client-auth: client-secret needs the path of a file that holds"
                        + " the secret");
            }
            readSecret(secretFile);
        } else if (secretFile != null) {
            throw invalid("client-secret-file", "it is only for client-auth: client-secret; with private-key-jwt the"
                    + " hub signs in with its own key and has no secret");
        }
        JWSAlgorithm alg = JWSAlgorithm.parse(required("signing-algorithm", o.signingAlgorithm()).toUpperCase(Locale.ROOT));
        if (!SIGNING.contains(alg)) {
            throw invalid("signing-algorithm", "it must be RS256 or PS256");
        }
        List<String> scopes = o.scopes() == null ? List.of() : o.scopes().stream().map(String::strip).toList();
        if (!scopes.contains("openid")) {
            throw invalid("scopes", "the list must include openid");
        }
        for (String s : scopes) {
            if (!SCOPE_TOKEN.matcher(s).matches()) {
                throw invalid("scopes", "'" + s + "' is not a scope name");
            }
        }
        String rolesClaim = required("roles-claim", o.rolesClaim());
        List<String> rolesPath = Arrays.asList(rolesClaim.split("\\.", -1));
        if (rolesPath.stream().anyMatch(String::isBlank)) {
            throw invalid("roles-claim", "it must be a claim name or a dotted path such as realm_access.roles");
        }
        String adminRole = required("admin-role", o.adminRole());
        String readRole = o.readRole() == null || o.readRole().isBlank() ? null : o.readRole().strip();
        if (adminRole.equals(readRole)) {
            throw invalid("read-role", "it must differ from admin-role");
        }
        String usernameClaim = required("username-claim", o.usernameClaim());
        String label = required("button-label", o.buttonLabel());
        if (label.length() > 60 || CONTROL.matcher(label).find()) {
            throw invalid("button-label", "it must be one line of at most 60 characters");
        }
        String publicUrl;
        try {
            publicUrl = PublicUrl.normalize(props.publicUrl());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage());
        }
        if (publicUrl == null) {
            throw new IllegalStateException("svcmon.oidc needs svcmon.public-url, the address people open the dashboard"
                    + " at: the provider sends them back to it after they sign in");
        }
        return new OidcSettings(new Issuer(issuerUri.toString()), new ClientID(clientId), auth, secretFile, keyFile,
                certFile, alg,
                new Scope(scopes.toArray(String[]::new)), rolesPath, rolesClaim, adminRole, readRole, usernameClaim,
                label, publicUrl, URI.create(publicUrl + CALLBACK), URI.create(publicUrl + "/"));
    }

    /** The client secret, read from its file each time it is needed, so a new one is used without a restart. */
    static String readSecret(Path file) {
        String secret;
        try {
            secret = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw invalid("client-secret-file", "the file " + file + " cannot be read (" + e.getClass().getSimpleName()
                    + ")");
        }
        if (secret.isEmpty() || CONTROL.matcher(secret).find()) {
            throw invalid("client-secret-file", "the file " + file + " must hold the secret on one line");
        }
        return secret;
    }

    /** True for an address on this machine, the only place a provider may be reached over plain HTTP. */
    static boolean loopback(URI uri) {
        return uri.getHost() != null && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private static URI issuer(String text) {
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw invalid("issuer", "it is not a valid address");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(scheme.equals("https") || scheme.equals("http") && loopback(uri))) {
            throw invalid("issuer", "it must be an https:// address with a host and no user name, query or fragment"
                    + " (plain http only on this machine)");
        }
        // Kept exactly as written, trailing slash and all: the ID token's iss must match it character for character.
        return uri;
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(name, "it is required when svcmon.oidc.issuer is set");
        }
        return value.strip();
    }

    static IllegalStateException invalid(String name, String why) {
        return new IllegalStateException("svcmon.oidc." + name + ": " + why);
    }
}
