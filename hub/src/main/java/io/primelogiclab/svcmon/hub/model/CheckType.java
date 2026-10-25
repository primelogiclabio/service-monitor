package io.primelogiclab.svcmon.hub.model;

public enum CheckType {
    HTTP, TCP, PING, SERVICE, CONTAINER, CPU, MEMORY, DISK;

    /** Network checks the hub can run itself. */
    public boolean hubCapable() {
        return this == HTTP || this == TCP || this == PING;
    }

    /** Local host checks that only an agent can run. */
    public boolean agentOnly() {
        return !hubCapable();
    }
}
