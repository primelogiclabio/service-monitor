package io.primelogiclab.svcmon.hub.model;

/**
 * What a token may do. ADMIN includes READ. ENROLL registers one agent; JOIN (a join key) registers any number
 * of agents until it is revoked or expires; AGENT is one agent's own credential.
 */
public enum Scope {
    ADMIN("a"), READ("r"), ENROLL("e"), JOIN("j"), AGENT("g");

    private final String code;

    Scope(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
