package edu.polytech.groups;

/** Identity of one incarnation of a group member. */
public final class MemberId {
  public final int id;
  public final int incarnation;

  public MemberId(int id, int incarnation) {
    this.id = id;
    this.incarnation = incarnation;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof MemberId)) return false;
    MemberId m = (MemberId) o;
    return id == m.id && incarnation == m.incarnation;
  }

  @Override public int hashCode() { return 31 * id + incarnation; }
  @Override public String toString() { return id + "#" + incarnation; }
}