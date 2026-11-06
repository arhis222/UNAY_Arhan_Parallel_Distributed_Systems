package edu.polytech.tom;

public interface TotalOrderMulticast {

  /** Receives the messages of the group, in the same order on every member. */
  public interface Listener {
    void delivered(int senderId, int incarnation, long sequence, byte[] payload);
  }

  void setListener(Listener l);

  /** Multicast a payload to every member of the group, including myself. Returns its sequence number. */
  long multicast(byte[] payload);
}
