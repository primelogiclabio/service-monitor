package io.primelogiclab.svcmon.hub.checks;

import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckResult;
import io.primelogiclab.svcmon.hub.model.CheckType;

/** Runs one kind of network check from the hub. Implementations must not throw for ordinary failures. */
public interface Checker {

    CheckType type();

    CheckResult check(CheckDef def);
}
