package io.primelogiclab.svcmon.hub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final LoginThrottle throttle = new LoginThrottle(now::get);

    private void fail(String client, String account, boolean inside, int times) {
        for (int i = 0; i < times; i++) {
            throttle.failed(client, account, inside);
            now.addAndGet(1000);
        }
    }

    private void fail(String client, int times) {
        fail(client, "admin", true, times);
    }

    private long waitFor(String client) {
        return throttle.waitSeconds(client, "admin", true);
    }

    @Test
    void aClientMayBeWrongAFewTimesThenMustWait() {
        fail("10.0.0.1", LoginThrottle.PER_CLIENT - 1);
        assertEquals(0, waitFor("10.0.0.1"));

        fail("10.0.0.1", 1);
        long wait = waitFor("10.0.0.1");
        assertTrue(wait > 0 && wait <= TimeUnit.MINUTES.toSeconds(15), "wait " + wait);
        assertEquals(0, waitFor("10.0.0.2"), "another client is not held up by it");
    }

    @Test
    void theWaitEndsWhenTheOldestFailureAgesOut() {
        fail("10.0.0.1", LoginThrottle.PER_CLIENT);
        now.addAndGet(TimeUnit.MINUTES.toMillis(15) - TimeUnit.SECONDS.toMillis(LoginThrottle.PER_CLIENT) - 1);
        assertTrue(waitFor("10.0.0.1") > 0);

        now.addAndGet(2000);
        assertEquals(0, waitFor("10.0.0.1"));
    }

    @Test
    void aCorrectPasswordClearsThatClientsCount() {
        fail("10.0.0.1", LoginThrottle.PER_CLIENT - 1);
        throttle.succeeded("10.0.0.1");
        fail("10.0.0.1", LoginThrottle.PER_CLIENT - 1);
        assertEquals(0, waitFor("10.0.0.1"));
    }

    @Test
    void oneAccountGuessedFromManyAddressesIsHeldOnItsOwn() {
        for (int i = 0; i < LoginThrottle.PER_ACCOUNT; i++) {
            fail("10.0.1." + i, "carol", true, 1);
        }
        assertTrue(throttle.waitSeconds("10.9.9.9", "carol", true) > 0, "carol is held");
        assertTrue(throttle.waitSeconds("10.9.9.9", "CAROL ", true) > 0, "under any spelling of the name");
        assertEquals(0, throttle.waitSeconds("10.9.9.9", "dave", true), "dave is not");
    }

    @Test
    void manyAddressesStillRunIntoTheTotalLimit() {
        for (int i = 0; i < LoginThrottle.IN_TOTAL; i++) {
            fail("10.0.1." + i, "user" + i, true, 1);
        }
        assertTrue(waitFor("10.9.9.9") > 0, "even a client that never failed is held");

        now.addAndGet(TimeUnit.MINUTES.toMillis(16));
        assertEquals(0, waitFor("10.9.9.9"));
    }

    /** Guessing from outside fills the outside's counts only, so it cannot lock out the people inside. */
    @Test
    void guessingFromOutsideDoesNotHoldUpTheInside() {
        for (int i = 0; i < LoginThrottle.IN_TOTAL; i++) {
            fail("203.0.113." + i, "admin", false, 1);
        }
        assertTrue(throttle.waitSeconds("198.51.100.1", "admin", false) > 0, "outside is held");
        assertEquals(0, throttle.waitSeconds("10.0.0.5", "admin", true), "admin may still sign in from inside");
    }

    @Test
    void successDoesNotClearTheTotal() {
        for (int i = 0; i < LoginThrottle.IN_TOTAL; i++) {
            fail("10.0.1." + i, "user" + i, true, 1);
        }
        throttle.succeeded("10.0.1.0");
        assertTrue(waitFor("10.0.1.0") > 0);
    }

    @Test
    void anAttackerChangingAddressAndNameCannotGrowItWithoutBound() {
        for (int i = 0; i < 30_000; i++) {
            throttle.failed("203.0." + (i / 250) + "." + (i % 250), "name" + i, false);
            now.addAndGet(1);
        }
        assertTrue(throttle.waitSeconds("198.51.100.1", "someone", false) > 0, "the total limit holds");
    }
}
