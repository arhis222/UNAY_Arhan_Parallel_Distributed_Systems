package edu.polytech.verifier.tom.tests;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import edu.polytech.verifier.VerificationResult.Verdict;
import edu.polytech.verifier.tom.ApplicationEnvelope;
import edu.polytech.verifier.tom.ApplicationEnvelopeCodec;
import edu.polytech.verifier.tom.EnvelopeFormatException;

/** One real Actor/Group/queue/TOM no-failure scenario, following TomGroupTest. */
public final class VerifierTomGroupTest {
    private static final int SIZE = 3, SENDS = 2, MESSAGES = SIZE * SENDS;
    private static final String RUN = "verifier-tom-baseline", GROUP = "fixed-three";
    private final Task driver = Executor.self().newTask("verifier-tom-driver");
    // All scenario state, including the verifier, is accessed only on the event pump.
    private final Actor[] actors = new Actor[SIZE + 1];
    private final TomMember[] members = new TomMember[SIZE + 1];
    private final View[] views = new View[SIZE + 1];
    private final ActorId[] identities = new ActorId[SIZE + 1];
    private final Set<MessageId> expected = new HashSet<>();
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

    private void runScenario() throws Exception {
        onPump(() -> {
            GroupManager manager = new GroupManager(SIZE);
            for (int i = 1; i <= SIZE; i++) {
                final int me = i;
                actors[i] = new Actor("verifier-baseline-" + i, false);
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
            verifier = new GroupVerifier(new BaselineScenario(RUN, GROUP, plan, false,
                    MESSAGES, (long) MESSAGES * SIZE, 0, 10000));
            for (int slot = 0; slot < SENDS; slot++) {
                for (int sender = 1; sender <= SIZE; sender++) send(sender, slot);
            }
            verifier.closeWorkload();
        });

        boolean complete = waitFor(() -> verifier.snapshot().validDeliveries() == (long) MESSAGES * SIZE);
        onPump(() -> {
            checkCallbacks();
            VerificationResult result = verifier.closeObservation(complete ? CloseReason.NORMAL : CloseReason.TIMEOUT);
            check(result.verdict() == Verdict.SUCCESSFUL, "Expected successful run: " + result + " " + result.diagnostics());
            check(result.confirmedMessages() == MESSAGES && result.provisionalMessages() == 0
                    && result.unaccountedSends() == 0 && result.missingDeliveries().isEmpty(), "Incomplete send/delivery accounting");
            List<MessageId> reference = result.histories().get(identities[1]);
            for (int i = 1; i <= SIZE; i++) {
                List<MessageId> history = result.histories().get(identities[i]);
                check(history.size() == MESSAGES && new HashSet<>(history).equals(expected),
                        "Recipient " + i + " did not deliver each expected application message exactly once");
                check(history.equals(reference), "Recipient " + i + " has a different delivery order");
                check(!actors[i].dead(), "Unexpected actor death");
            }
            System.out.println("VerifierTomGroupTest: SUCCESSFUL; 3 members, 6 messages, 18 deliveries, identical histories");
        });
    }

    private void send(int sender, int slot) {
        MessageId id = new MessageId(RUN, GROUP, identities[sender], 100L + slot);
        byte[] workload = {(byte) slot, 0, (byte) 0xff}; // Equal bytes from distinct senders.
        byte[] frame;
        try { frame = ApplicationEnvelopeCodec.encode(new ApplicationEnvelope(id, workload)); }
        catch (EnvelopeFormatException failure) { throw new AssertionError("Local encoding failed", failure); }
        check(expected.add(id), "Application identity reused");
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

    public static void main(String[] args) {
        int status = 0;
        try { new VerifierTomGroupTest().runScenario(); }
        catch (Throwable failure) { failure.printStackTrace(); status = 1; }
        // Executor has no shutdown API; match TomGroupTest's standalone-process cleanup.
        System.exit(status);
    }
}
