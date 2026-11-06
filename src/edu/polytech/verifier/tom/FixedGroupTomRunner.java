package edu.polytech.verifier.tom;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import edu.polytech.actors.Actor;
import edu.polytech.groups.GroupManager;
import edu.polytech.groups.View;
import edu.polytech.queues.Task;
import edu.polytech.tom.TomMember;
import edu.polytech.utils.Executor;
import edu.polytech.verifier.ActorId;
import edu.polytech.verifier.BaselineScenario;
import edu.polytech.verifier.GroupVerifier;
import edu.polytech.verifier.GroupVerifier.SubmissionIssue;
import edu.polytech.verifier.MessageId;
import edu.polytech.verifier.VerificationResult;
import edu.polytech.verifier.VerificationResult.CloseReason;

/** Finite three-member, two-sends-per-member no-failure workload on real Group/TOM.
 * Call run once from outside the event pump. The caller owns process shutdown.
 */
public final class FixedGroupTomRunner {
    private static final int SIZE = 3, SENDS = 2, MESSAGES = SIZE * SENDS;
    private static final String GROUP = "fixed-three";
    private final String runId;
    private final int periodMillis;
    private final long[] lastReturnNanos = new long[SIZE + 1];
    private int completedSenders;
    private boolean started;
    private volatile boolean stopped;

    public FixedGroupTomRunner(String runId, int periodMillis) {
        if (runId == null || runId.trim().isEmpty() || periodMillis < 0)
            throw new IllegalArgumentException("Run identity and nonnegative sending period required");
        this.runId = runId;
        this.periodMillis = periodMillis;
    }
    private final Task driver = Executor.self().newTask("verifier-tom-driver");
    // All scenario state, including the verifier, is accessed only on the event pump.
    private final Actor[] actors = new Actor[SIZE + 1];
    private final TomMember[] members = new TomMember[SIZE + 1];
    private final View[] views = new View[SIZE + 1];
    private final ActorId[] identities = new ActorId[SIZE + 1];
    private GroupVerifier verifier;
    private Throwable callbackFailure;

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

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
                checkCallbacks();
                ready[0] = condition.get();
            });
            if (ready[0]) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private void checkCallbacks() {
        if (callbackFailure != null) throw new AssertionError("Delivery/view callback failed", callbackFailure);
    }

    private void delivered(int recipient, int sender, int incarnation, byte[] frame) {
        try {
            ApplicationEnvelope envelope = ApplicationEnvelopeCodec.decode(frame);
            MessageId id = envelope.id();
            check(id.sender().actorNumber() == sender && id.sender().incarnation() == incarnation,
                    "Callback sender differs from application origin at recipient " + recipient + ": " + id);
            // Originals come only from beginMulticast, never from these callback bytes.
            verifier.delivered(identities[recipient], id, envelope.workload());
        } catch (Throwable failure) {
            if (callbackFailure == null) callbackFailure = failure;
        }
    }

    public VerificationResult run() throws Exception {
        if (Thread.currentThread() == Executor.self())
            throw new IllegalStateException("Runner waits must not block the event pump");
        if (started) throw new IllegalStateException("Runner is single-use");
        started = true;
        try { return runScenario(); }
        finally { stopped = true; }
    }

    private VerificationResult runScenario() throws Exception {
        onPump(() -> {
            GroupManager manager = new GroupManager(SIZE);
            for (int i = 1; i <= SIZE; i++) {
                final int me = i;
                actors[i] = new Actor(runId + "-actor-" + i, false);
                manager.join(actors[i], i, group -> {
                    identities[me] = new ActorId(group.self().id, group.self().incarnation);
                    members[me] = new TomMember(group);
                    members[me].setViewListener(view -> {
                        if (views[me] != null && callbackFailure == null)
                            callbackFailure = new AssertionError("Unexpected membership change at " + me);
                        views[me] = view;
                    });
                    // Internal TOM sequence is deliberately not used as application identity.
                    members[me].setListener((sender, incarnation, sequence, payload) ->
                            delivered(me, sender, incarnation, payload));
                });
            }
        });
        check(waitFor(() -> {
            for (int i = 1; i <= SIZE; i++) if (views[i] == null) return false;
            return true;
        }), "Initial group readiness timed out");

        onPump(() -> {
            Map<ActorId, Integer> plan = new LinkedHashMap<>();
            for (int i = 1; i <= SIZE; i++) {
                check(views[i].size() == SIZE, "Wrong initial view size");
                for (int j = 1; j <= SIZE; j++) {
                    check(views[i].contains(j) && views[i].member(j).incarnation == identities[j].incarnation(),
                            "Initial member/incarnation mismatch");
                }
                plan.put(identities[i], SENDS);
            }
            verifier = new GroupVerifier(new BaselineScenario(runId, GROUP, plan, false,
                    MESSAGES, (long) MESSAGES * SIZE, periodMillis, 10000));
            for (int sender = 1; sender <= SIZE; sender++) schedule(sender, 0, 0);
        });

        boolean complete = waitFor(() -> completedSenders == SIZE
                && verifier.snapshot().validDeliveries() == (long) MESSAGES * SIZE);
        VerificationResult[] result = new VerificationResult[1];
        onPump(() -> {
            stopped = true;
            checkCallbacks();
            for (int i = 1; i <= SIZE; i++) check(!actors[i].dead(), "Unexpected actor death");
            result[0] = verifier.closeObservation(complete ? CloseReason.NORMAL : CloseReason.TIMEOUT);
        });
        return result[0];
    }

    private void schedule(int sender, int slot, int delay) {
        // One send per runnable, including period zero: yield to queue/protocol events.
        driver.post(() -> {
            if (stopped || callbackFailure != null) return;
            try {
                if (slot > 0) check(System.nanoTime() - lastReturnNanos[sender]
                        >= TimeUnit.MILLISECONDS.toNanos(periodMillis), "Sender paced too early");
                send(sender, slot);
                lastReturnNanos[sender] = System.nanoTime();
                if (slot + 1 < SENDS) schedule(sender, slot + 1, periodMillis);
                else if (++completedSenders == SIZE) verifier.closeWorkload();
            } catch (Throwable failure) {
                if (callbackFailure == null) callbackFailure = failure;
            }
        }, delay);
    }

    private void send(int sender, int slot) {
        MessageId id = new MessageId(runId, GROUP, identities[sender], 100L + slot);
        byte[] workload = {(byte) slot, 0, (byte) 0xff}; // Equal bytes from distinct senders.
        byte[] frame;
        try { frame = ApplicationEnvelopeCodec.encode(new ApplicationEnvelope(id, workload)); }
        catch (EnvelopeFormatException failure) { throw new AssertionError("Local encoding failed", failure); }
        check(verifier.beginMulticast(slot, id, workload), "Registration rejected: " + id);
        boolean normal = false;
        try {
            members[sender].multicast(frame);
            normal = true;
        } finally {
            if (!normal || actors[sender].dead())
                verifier.recordSubmissionIssue(identities[sender], slot, SubmissionIssue.UNCERTAIN,
                        "Multicast did not return normally with a live sender");
        }
        check(!actors[sender].dead() && verifier.confirmMulticast(id), "Confirmation rejected: " + id);
    }

}
