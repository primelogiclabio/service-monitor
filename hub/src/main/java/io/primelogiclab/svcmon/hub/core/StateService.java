package io.primelogiclab.svcmon.hub.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckResult;
import io.primelogiclab.svcmon.hub.model.CheckState;
import io.primelogiclab.svcmon.hub.model.Status;
import io.primelogiclab.svcmon.hub.store.ResultRepo;
import io.primelogiclab.svcmon.hub.store.StateRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Stores check results and keeps each check's effective status. A DOWN result only becomes the effective
 * status after {@code failuresBeforeDown} consecutive failures, so one dropped packet does not alert.
 */
@Service
public class StateService {

    private static final Logger log = LoggerFactory.getLogger(StateService.class);

    private final ResultRepo results;
    private final StateRepo states;
    private final ApplicationEventPublisher events;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public StateService(ResultRepo results, StateRepo states, ApplicationEventPublisher events) {
        this.results = results;
        this.states = states;
        this.events = events;
    }

    public void record(CheckDef def, CheckResult r) {
        StatusChangedEvent event = null;
        ReentrantLock lock = locks.computeIfAbsent(def.id(), k -> new ReentrantLock());
        lock.lock();
        try {
            results.insert(r);
            CheckState prev = states.find(def.id()).orElse(null);

            int failures;
            Status effective;
            if (r.status() == Status.DOWN) {
                failures = prev == null ? 1 : prev.consecutiveFailures() + 1;
                if (failures >= def.failuresBeforeDown()) {
                    effective = Status.DOWN;
                } else {
                    effective = prev == null ? Status.UNKNOWN : prev.status();
                }
            } else {
                failures = 0;
                effective = r.status();
            }

            long lastChange = prev == null || prev.status() != effective ? r.ts() : prev.lastChange();
            states.upsert(new CheckState(def.id(), effective, failures, lastChange, r.ts(),
                    r.latencyMs(), r.value(), r.message()));

            boolean changed = prev == null
                    ? effective != Status.UP && effective != Status.UNKNOWN
                    : prev.status() != effective;
            if (changed) {
                event = new StatusChangedEvent(def, prev == null ? Status.UNKNOWN : prev.status(),
                        effective, r.message(), r.ts());
            }
        } catch (DataAccessException e) {
            // The check may have been deleted while it was running.
            log.warn("Could not store result for check {}: {}", def.id(), e.getMessage());
        } finally {
            lock.unlock();
        }
        if (event != null) {
            events.publishEvent(event);
        }
    }

    public void forget(String checkId) {
        locks.remove(checkId);
    }
}
