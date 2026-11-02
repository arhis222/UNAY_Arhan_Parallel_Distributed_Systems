package edu.polytech.groups;

import java.util.List;
import edu.polytech.tom.TomProcess;

public interface Group extends TomProcess.Transport {

  interface Listener {               // remplace GroupListener.java
    void received(int from, byte[] packet);
    void viewInstalled(View view);
  }

  interface JoinListener {           // remplace JoinListener.java
    void joined(Group group);
  }

  MemberId self();
  View view();
  void send(int to, byte[] packet);
  void setListener(Listener l);
 
}