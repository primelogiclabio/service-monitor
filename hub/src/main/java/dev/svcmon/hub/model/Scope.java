package dev.svcmon.hub.model;

/** What a token may do. ADMIN includes READ. ENROLL registers one agent; AGENT is that agent's credential. */
public enum Scope {
    ADMIN("a"), READ("r"), ENROLL("e"), AGENT("g");

    private final String code;

    Scope(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
