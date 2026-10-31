package io.primelogiclab.svcmon.hub.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import io.primelogiclab.svcmon.hub.model.Role;
import org.junit.jupiter.api.Test;

/** The ID token's roles and name, as the hub reads them for any provider. */
class RoleMappingTest {

    private static final List<String> KEYCLOAK = List.of("realm_access", "roles");
    private static final List<String> FLAT = List.of("roles");

    @Test
    void theAdminRoleWinsThenTheReadRoleElseNothing() {
        Map<String, Object> both = Map.of("realm_access", Map.of("roles", List.of("monitor-viewer", "platform-admin")));
        assertEquals(Role.ADMIN, ProviderSignIn.role(both, KEYCLOAK, "platform-admin", "monitor-viewer"));
        assertEquals(Role.READ, ProviderSignIn.role(Map.of("realm_access", Map.of("roles", List.of("monitor-viewer"))),
                KEYCLOAK, "platform-admin", "monitor-viewer"));
        assertNull(ProviderSignIn.role(Map.of("realm_access", Map.of("roles", List.of("monitor-viewer"))), KEYCLOAK,
                "platform-admin", null), "administrators only");
        assertNull(ProviderSignIn.role(Map.of("realm_access", Map.of("roles", List.of("other"))), KEYCLOAK,
                "platform-admin", "monitor-viewer"));
    }

    @Test
    void rolesAreAListOrOneStringAtAnyDepthAndAnythingElseIsNoRole() {
        assertEquals(Role.ADMIN, ProviderSignIn.role(Map.of("roles", List.of("Monitor.Admin")), FLAT, "Monitor.Admin", null),
                "Entra ID's app roles");
        assertEquals(Role.ADMIN, ProviderSignIn.role(Map.of("roles", "admin"), FLAT, "admin", null));
        assertNull(ProviderSignIn.role(Map.of("roles", List.of(1, true, Map.of())), FLAT, "admin", null));
        assertNull(ProviderSignIn.role(Map.of("realm_access", "platform-admin"), KEYCLOAK, "platform-admin", null),
                "a path through something that is not an object");
        assertNull(ProviderSignIn.role(Map.of(), KEYCLOAK, "platform-admin", null));
        assertNull(ProviderSignIn.role(Map.of("roles", List.of("ADMIN")), FLAT, "admin", null), "names match exactly");
    }

    @Test
    void theNameIsTheClaimOrTheEmailWhenItFitsAHubName() {
        assertEquals("alice", ProviderSignIn.username(Map.of("preferred_username", "alice", "email", "a@x.example"),
                "preferred_username"));
        assertEquals("a@x.example", ProviderSignIn.username(Map.of("preferred_username", "Alice Smith",
                "email", "a@x.example"), "preferred_username"));
        assertEquals("a@x.example", ProviderSignIn.username(Map.of("email", "a@x.example"), "preferred_username"));
        assertEquals("a.smith", ProviderSignIn.username(Map.of("upn", "a.smith"), "upn"));
        assertNull(ProviderSignIn.username(Map.of("preferred_username", "x".repeat(65)), "preferred_username"));
        assertNull(ProviderSignIn.username(Map.of("preferred_username", "bob\nadmin"), "preferred_username"));
        assertNull(ProviderSignIn.username(Map.of(), "preferred_username"));
    }
}
