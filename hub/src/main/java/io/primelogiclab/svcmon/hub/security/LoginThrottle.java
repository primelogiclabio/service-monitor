package io.primelogiclab.svcmon.hub.security;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing. In a quarter of an hour each client address may get a password wrong
 * {@value #PER_CLIENT} times, each account {@value #PER_ACCOUNT} times and all clients together {@value #IN_TOTAL}
 * times, before every further attempt is refused (even a correct one) until the oldest failure ages out.
 * <p>
 * The account and total counts are kept apart for the internal network and the outside, so someone guessing from
 * outside cannot lock the people inside out. The total keeps an attacker with many addresses to a few thousand
 * guesses a day. Refused attempts cost no password hashing. Kept in memory only, so a restart clears it.
 */
@Component
public class LoginThrottle {

    static final int PER_CLIENT = 5;
    static final int PER_ACCOUNT = 10;
    static final int IN_TOTAL = 20;
    static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(15);
    /** Bounds the memory an attacker can use by varying their address or the name they try. */
    private static final int MAX_KEYS = 10_000;

    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> perClient = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> perAccount = new HashMap<>();
    private final ArrayDeque<Long> totalInside = new ArrayDeque<>();
    private final ArrayDeque<Long> totalOutside = new ArrayDeque<>();

    @Autowired
    public LoginThrottle() {
        this(System::currentTimeMillis);
    }

    LoginThrottle(LongSupplier clock) {
        this.clock = clock;
    }

    /** Seconds the caller must wait before it may try, or 0 when it may. */
    public synchronized long waitSeconds(String client, String account, boolean inside) {
        long now = clock.getAsLong();
        ArrayDeque<Long> total = inside ? totalInside : totalOutside;
        prune(total, now);
        long wait = overLimit(total, IN_TOTAL, now);
        wait = Math.max(wait, check(perClient, client, PER_CLIENT, now));
        wait = Math.max(wait, check(perAccount, accountKey(account, inside), PER_ACCOUNT, now));
        return wait == 0 ? 0 : Math.max(1, TimeUnit.MILLISECONDS.toSeconds(wait + 999));
    }

    public synchronized void failed(String client, String account, boolean inside) {
        long now = clock.getAsLong();
        record(inside ? totalInside : totalOutside, now, IN_TOTAL);
        add(perClient, client, PER_CLIENT, now);
        add(perAccount, accountKey(account, inside), PER_ACCOUNT, now);
    }

    /** A correct password clears that client's own count; the account and total keep counting what happened. */
    public synchronized void succeeded(String client) {
        perClient.remove(client);
    }

    private static String accountKey(String account, boolean inside) {
        String name = account == null ? "" : account.strip().toLowerCase(Locale.ROOT);
        return (inside ? "in:" : "out:") + (name.length() > 100 ? name.substring(0, 100) : name);
    }

    private static long check(Map<String, ArrayDeque<Long>> buckets, String key, int limit, long now) {
        ArrayDeque<Long> mine = buckets.get(key);
        if (mine == null) {
            return 0;
        }
        prune(mine, now);
        if (mine.isEmpty()) {
            buckets.remove(key);
            return 0;
        }
        return overLimit(mine, limit, now);
    }

    private static void add(Map<String, ArrayDeque<Long>> buckets, String key, int limit, long now) {
        if (buckets.size() >= MAX_KEYS && !buckets.containsKey(key)) {
            buckets.values().forEach(q -> prune(q, now));
            buckets.values().removeIf(ArrayDeque::isEmpty);
            if (buckets.size() >= MAX_KEYS) {
                return; // still counted in the total, which is what limits this
            }
        }
        record(buckets.computeIfAbsent(key, k -> new ArrayDeque<>()), now, limit);
    }

    /** Only the latest {@code limit} failures matter: the oldest of them says when the limit is lifted. */
    private static void record(ArrayDeque<Long> failures, long now, int limit) {
        failures.addLast(now);
        while (failures.size() > limit) {
            failures.removeFirst();
        }
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
