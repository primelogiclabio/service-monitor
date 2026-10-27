package io.primelogiclab.svcmon.hub.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserServiceTest {

    @Test
    void aNewPasswordIsLongEnoughAndNotTheUsersName() {
        assertNull(UserService.problem("correct horse battery staple", "carol"));
        assertNotNull(UserService.problem("short", "carol"));
        assertNotNull(UserService.problem(null, "carol"));
        assertNotNull(UserService.problem("operations-team", "operations-team"));
        assertNotNull(UserService.problem(" Operations-Team ", "operations-team"), "whatever the case and spaces");
    }

    @Test
    void namesArePlain() {
        assertTrue(UserService.USERNAME.matcher("ops.lead@example-1_x").matches());
        for (String bad : new String[] {"", "a b", "x".repeat(65), "tab\t", "<script>", "naïve"}) {
            assertFalse(UserService.USERNAME.matcher(bad).matches(), bad);
        }
    }
}
