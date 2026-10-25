package io.primelogiclab.svcmon.hub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(1000);

    @Test
    void aPasswordVerifiesAgainstItsOwnHashAndNothingElse() {
        String stored = hasher.hash("correct horse battery staple");
        assertTrue(hasher.verify("correct horse battery staple", stored));
        assertFalse(hasher.verify("correct horse battery stapl", stored));
        assertFalse(hasher.verify("Correct horse battery staple", stored));
        assertFalse(hasher.verify("", stored));
    }

    @Test
    void theStoredTextHoldsNoPasswordAndEveryHashHasItsOwnSalt() {
        String a = hasher.hash("correct horse battery staple");
        String b = hasher.hash("correct horse battery staple");
        assertNotEquals(a, b, "the salt is random");
        assertTrue(a.startsWith("pbkdf2-sha256$1000$"), a);
        assertFalse(a.contains("correct"));
    }

    @Test
    void theCostInTheStoredTextIsUsedSoItCanBeRaisedLater() {
        String cheap = new PasswordHasher(1000).hash("correct horse battery staple");
        assertTrue(new PasswordHasher(2000).verify("correct horse battery staple", cheap));
    }

    @Test
    void anythingMalformedIsJustNotAMatch() {
        for (String bad : new String[] {null, "", "x", "pbkdf2-sha256$1000$AAAA", "md5$1000$AAAA$AAAA",
                "pbkdf2-sha256$abc$AAAA$AAAA", "pbkdf2-sha256$0$AAAA$AAAA", "pbkdf2-sha256$-5$AAAA$AAAA",
                "pbkdf2-sha256$999999999$AAAA$AAAA", "pbkdf2-sha256$1000$***$***"}) {
            assertFalse(hasher.verify("correct horse battery staple", bad), String.valueOf(bad));
        }
        assertFalse(hasher.verify(null, hasher.hash("correct horse battery staple")));
    }

    @Test
    void theSameWordTypedComposedOrDecomposedIsTheSamePassword() {
        String composed = "café au lait 2026";
        String decomposed = "café au lait 2026";
        assertNotEquals(composed, decomposed);
        assertTrue(hasher.verify(decomposed, hasher.hash(composed)));
    }

    @Test
    void theRuleIsLengthAndNothingElse() {
        assertNull(PasswordHasher.problem("a".repeat(12)));
        assertNull(PasswordHasher.problem("a".repeat(128)));
        assertNull(PasswordHasher.problem("just words in a row"));
        assertNotNull(PasswordHasher.problem("a".repeat(11)));
        assertNotNull(PasswordHasher.problem("a".repeat(129)));
        assertNotNull(PasswordHasher.problem(""));
        assertNotNull(PasswordHasher.problem(null));
    }

    @Test
    void generatedPasswordsAreLongRandomAndAcceptable() {
        String a = PasswordHasher.generate();
        assertEquals(24, a.length());
        assertNotEquals(a, PasswordHasher.generate());
        assertNull(PasswordHasher.problem(a));
        assertTrue(a.matches("[A-Za-z0-9_-]+"), a);
    }
}
