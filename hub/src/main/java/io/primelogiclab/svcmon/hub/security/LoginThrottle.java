package io.primelogiclab.svcmon.hub.security;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing against the local admin account. Each client address may get the password wrong
 * {@value #PER_CLIENT} times in a quarter of an hour, and all clients together {@value #IN_TOTAL} times, before
 * every further attempt is refused (even a correct one) until the oldest failure ages out. The total limit keeps
 * an attacker with many addresses to a few thousand guesses a day; it also means they can make the real admin
 * wait, which is why an API token or the sign-in through the proxy is the way in during that time.
 * Refused attempts cost no password hashing. Kept in memory only, so a restart clears it.
 */
@Component
public class LoginThrottle {

    static final int PER_CLIENT = 5;
    static final int IN_TOTAL = 20;
    static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(15);
    /** Bounds the memory an attacker can use by varying their address. */
    private static final int MAX_CLIENTS = 10_000;

    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> perClient = new HashMap<>();
    private final ArrayDeque<Long> total = new ArrayDeque<>();

    @Autowired
    public LoginThrottle() {
        this(System::currentTimeMillis);
    }

    LoginThrottle(LongSupplier clock) {
        this.clock = clock;
    }

    /** Seconds the caller must wait before it may try, or 0 when it may. */
    public synchronized long waitSeconds(String client) {
        long now = clock.getAsLong();
        prune(total, now);
        long wait = overLimit(total, IN_TOTAL, now);
        ArrayDeque<Long> mine = perClient.get(client);
        if (mine != null) {
            prune(mine, now);
            wait = Math.max(wait, overLimit(mine, PER_CLIENT, now));
            if (mine.isEmpty()) {
                perClient.remove(client);
            }
        }
        return wait == 0 ? 0 : Math.max(1, TimeUnit.MILLISECONDS.toSeconds(wait + 999));
    }

    public synchronized void failed(String client) {
        long now = clock.getAsLong();
        record(total, now, IN_TOTAL);
        if (perClient.size() >= MAX_CLIENTS && !perClient.containsKey(client)) {
            perClient.values().forEach(q -> prune(q, now));
            perClient.values().removeIf(ArrayDeque::isEmpty);
            if (perClient.size() >= MAX_CLIENTS) {
                return; // still counted in the total, which is what limits this
            }
        }
        record(perClient.computeIfAbsent(client, k -> new ArrayDeque<>()), now, PER_CLIENT);
    }

    /** Only the latest {@code limit} failures matter: the oldest of them says when the limit is lifted. */
    private static void record(ArrayDeque<Long> failures, long now, int limit) {
        failures.addLast(now);
        while (failures.size() > limit) {
            failures.removeFirst();
        }
    }

    /** A correct password clears that client's own count; the total keeps counting what happened. */
    public synchronized void succeeded(String client) {
        perClient.remove(client);
    }

    private static void prune(ArrayDeque<Long> failures, long now) {
        while (!failures.isEmpty() && now - failures.peekFirst() >= WINDOW_MS) {
            failures.removeFirst();
        }
    }

    /** Milliseconds until the oldest failure ages out when the limit is reached, otherwise 0. */
    private static long overLimit(ArrayDeque<Long> failures, int limit, long now) {
        return failures.size() >= limit ? failures.peekFirst() + WINDOW_MS - now : 0;
    }
}
