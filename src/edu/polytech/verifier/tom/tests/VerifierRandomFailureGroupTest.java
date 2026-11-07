package edu.polytech.verifier.tom.tests;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import edu.polytech.actors.Actor;
import edu.polytech.groups.Group;
import edu.polytech.groups.GroupManager;
import edu.polytech.groups.MemberId;
import edu.polytech.groups.View;
import edu.polytech.queues.Task;
import edu.polytech.tom.TomMember;
import edu.polytech.utils.Executor;
import edu.polytech.verifier.ActorId;
import edu.polytech.verifier.MessageId;
import edu.polytech.verifier.SurvivorVerifier;
import edu.polytech.verifier.VerificationResult.Verdict;
import edu.polytech.verifier.tom.ApplicationEnvelope;
import edu.polytech.verifier.tom.ApplicationEnvelopeCodec;

/** Seeded runtime-triggered random actor death, no reinsertion; all state is pump-confined. */
public final class VerifierRandomFailureGroupTest {
    private static final long SEED = 123L;
    private static final double PROBABILITY = 0.35;
    private final int periodMillis;
    private final long[] lastReturn = new long[4];
    private int finishedSenders, probeExecutions;
    private Throwable runtimeFailure;
    private boolean stopped;

    private VerifierRandomFailureGroupTest(int periodMillis) { this.periodMillis = periodMillis; }

    private final Task driver = Executor.self().newTask("verifier-survivor-driver");
    private final Actor[] actors = new Actor[4];
    private final TomMember[] members = new TomMember[4];
    private final View[] views = new View[4];
    private final ActorId[] ids = new ActorId[4];
    private final long[][] inFlight = new long[4][4];
    private SurvivorVerifier verifier;
    private Throwable callbackFailure;
    private int submissions;

    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }

    private void onPump(Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        driver.post(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(5, TimeUnit.SECONDS);
    }

    private boolean waitFor(Supplier<Boolean> condition) throws Exception {
        for (int i = 0; i < 250; i++) {
            boolean[] ready = new boolean[1];
            onPump(() -> {
                if (callbackFailure != null) throw new AssertionError("Callback failed", callbackFailure);
                ready[0] = condition.get();
            });
            if (ready[0]) return true;
            Thread.sleep(20);
        }
        return false;
    }

    /** Observes opaque packet transit only; never inspects DATA/ACK or changes routing. */
    private final class ObservedGroup implements Group {
        private final Group delegate;
        private Listener listener;
        ObservedGroup(Group group) {
            delegate = group;
            delegate.setListener(new Listener() {
                public void viewInstalled(View view) { listener.viewInstalled(view); }
                public void received(int from, byte[] packet) {
                    int to = self().id;
                    try {
                        check(inFlight[from][to] > 0, "Unaccounted packet receipt");
                        inFlight[from][to]--;
                        listener.received(from, packet);
                    } catch (Throwable failure) { callbackFailure = failure; }
                }
            });
        }
        public MemberId self() { return delegate.self(); }
        public View view() { return delegate.view(); }
        public void setListener(Listener value) { listener = value; }
        public void send(int to, byte[] packet) {
            if (delegate.view() != null && delegate.view().contains(to) && !actors[self().id].dead())
                inFlight[self().id][to] = Math.addExact(inFlight[self().id][to], 1);
            delegate.send(to, packet);
        }
        public void admit(Actor actor, int id, JoinListener listener) { delegate.admit(actor, id, listener); }
        public void leave() { delegate.leave(); }
    }

    private boolean drained(boolean afterDeath) {
        for (int from = 1; from <= 3; from++)
            for (int to = 1; to <= 3; to++)
                // An actually dead recipient cannot generate further application/protocol work.
                if ((!afterDeath || to != 2) && inFlight[from][to] != 0) return false;
        return true;
    }

    private boolean removed() {
        for (int survivor : new int[] {1, 3})
            if (views[survivor] == null || views[survivor].size() != 2
                    || !views[survivor].contains(1) || !views[survivor].contains(3)
                    || views[survivor].contains(2)) return false;
        return actors[2].dead();
    }

    private void send(int sender, int slot) {
        MessageId id = new MessageId("random-run-" + periodMillis, "survivor-group", ids[sender], slot);
        byte[] payload = {(byte) slot, 0, -1};
        try {
            byte[] frame = ApplicationEnvelopeCodec.encode(new ApplicationEnvelope(id, payload));
            verifier.beginMulticast(id, payload);
            boolean returned = false;
            try { members[sender].multicast(frame); returned = true; }
            finally { if (!returned || actors[sender].dead()) verifier.uncertainMulticast(id); }
            check(!actors[sender].dead(), "Unexpected sender death");
            verifier.confirmMulticast(id);
            submissions++;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private void scenario() throws Exception {
        onPump(() -> {
            Executor.self().configureFailures(0, SEED);
            check(!driver.canFail(), "Driver must be protected");
            GroupManager manager = new GroupManager(3);
            for (int i = 1; i <= 3; i++) {
                final int me = i;
                actors[i] = new Actor("random-check-" + periodMillis + "-" + i, i == 2);
                ids[i] = new ActorId(i, actors[i].incarnation());
                manager.join(actors[i], i, group -> {
                    members[me] = new TomMember(new ObservedGroup(group));
                    members[me].setViewListener(view -> {
                        views[me] = view;
                        if (verifier != null && me != 2 && view.size() == 2
                                && view.contains(1) && view.contains(3) && !view.contains(2))
                            verifier.observedRemoval(ids[me]);
                    });
                    members[me].setListener((sender, incarnation, sequence, frame) -> {
                        try {
                            ApplicationEnvelope value = ApplicationEnvelopeCodec.decode(frame);
                            check(value.id().sender().equals(new ActorId(sender, incarnation)), "Sender mismatch");
                            verifier.delivered(ids[me], value.id(), value.workload());
                        } catch (Throwable failure) { callbackFailure = failure; }
                    });
                });
            }
        });
        check(waitFor(() -> views[1] != null && views[2] != null && views[3] != null), "Initial readiness timeout");
        onPump(() -> {
            for (int i = 1; i <= 3; i++) {
                check(views[i].size() == 3, "Initial view size");
                for (int j = 1; j <= 3; j++) check(views[i].member(j) != null
                        && views[i].member(j).incarnation == ids[j].incarnation(), "Initial incarnation mismatch");
            }
            verifier = new SurvivorVerifier("random-run-" + periodMillis, "survivor-group",
                    new LinkedHashSet<>(Arrays.asList(ids[1], ids[2], ids[3])), ids[2]);
            send(2, 0);
        });
        // Drain the finite pre-death send before requesting death: this scenario exercises all-delivered.
        check(waitFor(() -> drained(false)), "Pre-death traffic did not finish");
        onPump(() -> {
            Task probe = actors[2].newTask("seeded-failure-probe");
            check(probe.canFail(), "Candidate probe is not fail-capable");
            probe.set(new Task.Listener() {
                public void failed(Task task, Throwable cause) { runtimeFailure = cause; }
                public void completed(Task task, Object result) {
                    callbackFailure = new AssertionError("Probe unexpectedly completed");
                }
            });
            Executor.self().configureFailures(PROBABILITY, SEED);
            pulse(probe);
        });
        check(waitFor(() -> actors[2].dead() && runtimeFailure != null), "Runtime random death timeout");
        onPump(() -> {
            check(runtimeFailure instanceof IllegalStateException
                    && "Simulated task failure".equals(runtimeFailure.getMessage()), "Not a runtime random failure");
            verifier.observedDeath(ids[2]);
        });
        boolean removal = waitFor(this::removed);
        if (removal) onPump(() -> {
            check(!actors[1].dead() && !actors[3].dead(), "Unexpected survivor death");
            pacedSend(1, 1); pacedSend(3, 1);
        });
        boolean complete = removal && waitFor(() -> finishedSenders == 2 && verifier.requiredDelivered() && drained(true));
        onPump(() -> {
            check(!actors[1].dead() && !actors[3].dead(), "Unexpected survivor death");
            SurvivorVerifier.Result result = verifier.assess(submissions == 5, removal && drained(true), !complete);
            check(result.verdict() == Verdict.SUCCESSFUL, "Survivor assessment: " + result);
            check(result.histories().get(ids[1]).size() == 5
                    && result.histories().get(ids[1]).equals(result.histories().get(ids[3])), "Survivor histories differ");
            System.out.println("VerifierRandomFailureGroupTest: period=" + periodMillis + " ms seed=" + SEED
                    + " probability=" + PROBABILITY + " probeExecutions=" + probeExecutions
                    + " SUCCESSFUL; runtime death, both removal views, 4 required messages, identical survivor histories");
        });
    }

    private void pulse(Task probe) {
        probe.post(() -> {
            if (++probeExecutions < 100) pulse(probe);
        }, 1);
    }

    private void pacedSend(int sender, int slot) {
        driver.post(() -> {
            if (stopped || callbackFailure != null) return;
            try {
                if (slot > 1) check(System.nanoTime() - lastReturn[sender]
                        >= TimeUnit.MILLISECONDS.toNanos(periodMillis), "Sender paced too early");
                send(sender, slot);
                lastReturn[sender] = System.nanoTime();
                if (slot < 2) pacedSend(sender, slot + 1);
                else finishedSenders++;
            } catch (Throwable failure) { callbackFailure = failure; }
        }, slot == 1 ? 0 : periodMillis);
    }

    private void run() throws Exception {
        try { scenario(); }
        finally {
            Executor.self().configureFailures(0, SEED);
            // Thread-safe reset does not depend on a responsive pump.
            onPump(() -> stopped = true);
        }
    }

    public static void main(String[] args) {
        int status = 0;
        try {
            new VerifierRandomFailureGroupTest(0).run();
            new VerifierRandomFailureGroupTest(5).run();
        } catch (Throwable failure) { failure.printStackTrace(); status = 1; }
        finally { Executor.self().configureFailures(0, SEED); }
        System.exit(status); // Same standalone cleanup as TomGroupTest.
    }
}
