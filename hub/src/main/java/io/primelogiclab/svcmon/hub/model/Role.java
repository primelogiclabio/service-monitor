package io.primelogiclab.svcmon.hub.model;

/** What a person who signs in may do: everything, or only look. Each maps to the token scope with the same rights. */
public enum Role {
    ADMIN(Scope.ADMIN),
    READ(Scope.READ);

    private final Scope scope;

    Role(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }
}
