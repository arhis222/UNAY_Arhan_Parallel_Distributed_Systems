package edu.polytech.groups;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** A membership view. viewId increases by one on every membership change. */
public final class View {
  public final long viewId;
  public final List<MemberId> members; // sorted by id, unmodifiable

  View(long viewId, Collection<MemberId> members) {
    List<MemberId> sorted = new ArrayList<MemberId>(members);
    Collections.sort(sorted, (a, b) -> Integer.compare(a.id, b.id));
    this.viewId = viewId;
    this.members = Collections.unmodifiableList(sorted);
  }

  public int size() { return members.size(); }

  public boolean contains(int id) {
    for (MemberId m : members)
      if (m.id == id) return true;
    return false;
  }

  /** Member ids, e.g. for the TomProcess constructor. */
  public Set<Integer> ids() {
    Set<Integer> ids = new TreeSet<Integer>();
    for (MemberId m : members) ids.add(m.id);
    return ids;
  }

  View without(int id) {
    List<MemberId> rest = new ArrayList<MemberId>();
    for (MemberId m : members)
      if (m.id != id) rest.add(m);
    return new View(viewId + 1, rest);
  }

  @Override public String toString() { return "view " + viewId + " " + members; }
}