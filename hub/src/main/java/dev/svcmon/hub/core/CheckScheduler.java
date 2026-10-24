package dev.svcmon.hub.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.svcmon.hub.checks.CheckRunner;
import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckResult;
import dev.svcmon.hub.model.RunFrom;
import dev.svcmon.hub.store.CheckRepo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs hub-side checks on their interval. One platform thread keeps time; each run executes on a virtual
 * thread, so many slow checks do not block each other. A check never overlaps with itself.
 */
@Component
public class CheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(CheckScheduler.class);

    private final CheckRepo checks;
    private final CheckRunner runner;
    private final StateService state;

    private final ScheduledThreadPoolExecutor ticker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "svcmon-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    public CheckScheduler(CheckRepo checks, CheckRunner runner, StateService state) {
        this.checks = checks;
        this.runner = runner;
        this.state = state;
        ticker.setRemoveOnCancelPolicy(true);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        var all = checks.findHubEnabled();
        all.forEach(this::schedule);
        log.info("Scheduled {} hub check(s)", all.size());
    }

    /** (Re)schedules a check. Disabled and agent-run checks are not scheduled on the hub. */
    public void schedule(CheckDef def) {
        unschedule(def.id());
        if (!def.enabled() || def.runFrom() != RunFrom.HUB || !def.type().hubCapable()) {
            return;
        }
        long jitterMs = ThreadLocalRandom.current().nextLong(Math.min(2000, def.intervalSeconds() * 1000L) + 1);
        ScheduledFuture<?> f = ticker.scheduleAtFixedRate(() -> trigger(def), jitterMs,
                def.intervalSeconds() * 1000L, TimeUnit.MILLISECONDS);
        futures.put(def.id(), f);
    }

    public void unschedule(String checkId) {
        ScheduledFuture<?> f = futures.remove(checkId);
        if (f != null) {
            f.cancel(false);
        }
        running.remove(checkId);
    }

    /** Runs a check immediately on the calling thread and records the result. */
    public CheckResult runNow(CheckDef def) {
        CheckResult r = runner.run(def);
        state.record(def, r);
        return r;
    }

    private void trigger(CheckDef def) {
        AtomicBoolean busy = running.computeIfAbsent(def.id(), k -> new AtomicBoolean());
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        workers.submit(() -> {
            try {
                runNow(def);
            } catch (RuntimeException e) {
                log.warn("Check {} failed unexpectedly", def.id(), e);
            } finally {
                busy.set(false);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        ticker.shutdownNow();
        workers.shutdownNow();
    }
}
