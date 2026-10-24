package dev.svcmon.hub.checks;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckResult;
import dev.svcmon.hub.model.CheckType;
import dev.svcmon.hub.model.Status;
import org.springframework.stereotype.Component;

/** Dispatches a hub-run check to the checker for its type. */
@Component
public class CheckRunner {

    private final Map<CheckType, Checker> checkers = new EnumMap<>(CheckType.class);

    public CheckRunner(List<Checker> all) {
        for (Checker c : all) {
            checkers.put(c.type(), c);
        }
    }

    public CheckResult run(CheckDef def) {
        Checker checker = checkers.get(def.type());
        if (checker == null) {
            return CheckResult.of(def.id(), Status.UNKNOWN, null, null,
                    def.type() + " checks run on an agent, not on the hub");
        }
        try {
            return checker.check(def);
        } catch (RuntimeException e) {
            return CheckResult.of(def.id(), Status.DOWN, null, null,
                    "Check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
