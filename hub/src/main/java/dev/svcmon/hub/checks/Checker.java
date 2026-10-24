package dev.svcmon.hub.checks;

import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckResult;
import dev.svcmon.hub.model.CheckType;

/** Runs one kind of network check from the hub. Implementations must not throw for ordinary failures. */
public interface Checker {

    CheckType type();

    CheckResult check(CheckDef def);
}
