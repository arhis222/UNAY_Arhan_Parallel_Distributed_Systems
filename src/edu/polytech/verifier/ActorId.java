package edu.polytech.verifier;

/** Immutable identity supplied by the caller, independent of runtime Actors. */
public final class ActorId {
    private final int actorNumber;
    private final int incarnation;

    public ActorId(int actorNumber, int incarnation) {
        if (actorNumber < 0 || incarnation < 0)
            throw new IllegalArgumentException("Negative actor identity");
        this.actorNumber = actorNumber;
        this.incarnation = incarnation;
    }

    public int actorNumber() { return actorNumber; }
    public int incarnation() { return incarnation; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ActorId)) return false;
        ActorId id = (ActorId) other;
        return actorNumber == id.actorNumber && incarnation == id.incarnation;
    }

    @Override public int hashCode() { return 31 * actorNumber + incarnation; }
    @Override public String toString() { return actorNumber + "." + incarnation; }
}
