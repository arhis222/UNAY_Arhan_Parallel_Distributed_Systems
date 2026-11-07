package edu.polytech.verifier.tom.tests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import edu.polytech.actors.Actor;
import edu.polytech.groups.Group;
import edu.polytech.groups.GroupManager;
import edu.polytech.groups.View;
import edu.polytech.queues.Task;
import edu.polytech.tom.TomMember;
import edu.polytech.utils.Executor;
import edu.polytech.verifier.ActorId;
import edu.polytech.verifier.MessageId;
import edu.polytech.verifier.tom.ApplicationEnvelope;
import edu.polytech.verifier.tom.ApplicationEnvelopeCodec;

/** Voluntary join and leave between finite workloads at installed view boundaries. */
public final class VerifierJoinLeaveGroupTest {
    /** Independent originals and eligibility for the three controlled membership intervals. */
    private static final class Reports {
        private final Map<MessageId, byte[]> originals = new LinkedHashMap<>();
        private final Map<MessageId, Set<ActorId>> eligible = new LinkedHashMap<>();
        private final Set<MessageId> confirmed = new HashSet<>();
        private final Map<ActorId, List<MessageId>> histories = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();

        void begin(MessageId id, byte[] original, Set<ActorId> recipients) {
            check(!originals.containsKey(id), "Reused application identity");
            originals.put(id, original.clone());
            eligible.put(id, new LinkedHashSet<>(recipients));
            for (ActorId recipient : recipients)
                if (!histories.containsKey(recipient)) histories.put(recipient, new ArrayList<MessageId>());
        }
        void confirm(MessageId id) { check(originals.containsKey(id) && confirmed.add(id), "Invalid confirmation"); }
        void deliver(ActorId receiver, MessageId id, byte[] bytes) {
            if (!originals.containsKey(id)) { errors.add("UNKNOWN_MESSAGE: " + id); return; }
            if (!eligible.get(id).contains(receiver)) {
                errors.add("INELIGIBLE_DELIVERY: receiver=" + receiver + " message=" + id); return;
            }
            List<MessageId> history = histories.get(receiver);
            if (history.contains(id)) errors.add("DUPLICATE: " + receiver + " " + id);
            if (!Arrays.equals(originals.get(id), bytes)) errors.add("CONTENT_MISMATCH: " + receiver + " " + id);
            history.add(id);
        }
        boolean complete() {
            if (confirmed.size() != originals.size()) return false;
            for (Map.Entry<MessageId, Set<ActorId>> e : eligible.entrySet())
                for (ActorId receiver : e.getValue()) if (!histories.get(receiver).contains(e.getKey())) return false;
            return true;
        }
        void assess() {
            check(errors.isEmpty(), "Delivery violations: " + errors);
            check(complete(), "Missing independent obligation or confirmation");
            for (int phase = 1; phase <= 3; phase++) {
                Set<MessageId> messages = new LinkedHashSet<>();
                Set<ActorId> recipients = null;
                for (MessageId id : originals.keySet()) if (id.sequence() / 10 == phase) {
                    messages.add(id);
                    if (recipients == null) recipients = eligible.get(id);
                    check(recipients.equals(eligible.get(id)), "Workload eligibility changed");
                }
                check(messages.size() == (phase == 2 ? 8 : 6), "Missing planned workload messages");
                List<MessageId> reference = null;
                for (ActorId actor : recipients) {
                    List<MessageId> projection = new ArrayList<>();
                    for (MessageId id : histories.get(actor)) if (messages.contains(id)) projection.add(id);
                    check(projection.size() == messages.size() && new HashSet<>(projection).equals(messages),
                            "Workload " + phase + " incomplete at " + actor);
                    if (reference == null) reference = projection;
                    check(reference.equals(projection), "Workload " + phase + " order disagreement at " + actor);
                }
            }
        }
    }

    private final Task driver = Executor.self().newTask("verifier-reinsertion-driver");
    private final Actor[] actors = new Actor[5];
    private final Group[] groups = new Group[5];
    private final TomMember[] members = new TomMember[5];
    private final View[] views = new View[5];
    private final ActorId[] ids = new ActorId[5];
    private final Reports reports = new Reports();
    private final int periodMillis;
    private int completedSenders;
    private final long[] lastReturn = new long[5];
    private boolean stopped;
    private VerifierJoinLeaveGroupTest(int periodMillis) { this.periodMillis = periodMillis; }
    private Throwable callbackFailure;
    private boolean joinObserved, leaveObserved;

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
                check(callbackFailure == null, "Callback error: " + callbackFailure);
                check(reports.errors.isEmpty(), "Delivery violations: " + reports.errors);
                ready[0] = condition.get();
            });
            if (ready[0]) return true;
            Thread.sleep(20);
        }
        return false;
    }
    private void attach(int member, Group group) {
        final ActorId receiver = new ActorId(group.self().id, group.self().incarnation);
        ids[member] = receiver;
        groups[member] = group;
        members[member] = new TomMember(group);
        members[member].setViewListener(view -> views[member] = view);
        members[member].setListener((sender, incarnation, sequence, frame) -> {
            try {
                ApplicationEnvelope envelope = ApplicationEnvelopeCodec.decode(frame);
                check(envelope.id().sender().equals(new ActorId(sender, incarnation)), "Callback origin mismatch");
                reports.deliver(receiver, envelope.id(), envelope.workload());
            } catch (Throwable failure) { if (callbackFailure == null) callbackFailure = failure; }
        });
    }
    private boolean sees(int observer, int... members) {
        View view = views[observer];
        if (view == null || view.size() != members.length) return false;
        for (int member : members)
            if (ids[member] == null || view.member(member) == null || view.member(member).incarnation != ids[member].incarnation()) return false;
        return true;
    }
    private void workload(int phase, int... senders) {
        completedSenders = 0;
        Set<ActorId> recipients = new LinkedHashSet<>();
        for (int sender : senders) recipients.add(ids[sender]);
        for (int sender : senders) pacedSend(phase, sender, 0, recipients);
    }
    private void pacedSend(int phase, int sender, int slot, Set<ActorId> recipients) {
        driver.post(() -> {
            if (stopped || callbackFailure != null) return;
            try {
                if (slot > 0) check(System.nanoTime() - lastReturn[sender]
                        >= TimeUnit.MILLISECONDS.toNanos(periodMillis), "Sender paced too early");
                MessageId id = new MessageId("join-leave-" + periodMillis, "join-leave-group", ids[sender], phase * 10L + slot);
                byte[] original = {(byte) phase, (byte) slot, 0, -1};
                byte[] frame = ApplicationEnvelopeCodec.encode(new ApplicationEnvelope(id, original));
                reports.begin(id, original, recipients);
                members[sender].multicast(frame);
                check(!actors[sender].dead(), "Sender died during invocation");
                reports.confirm(id);
                lastReturn[sender] = System.nanoTime();
                if (slot == 0) pacedSend(phase, sender, 1, recipients);
                else completedSenders++;
            } catch (Throwable failure) { callbackFailure = failure; }
        }, slot == 0 ? 0 : periodMillis);
    }
    private void scenario() throws Exception {
        onPump(() -> {
            Executor.self().configureFailures(0, 0);
            GroupManager manager = new GroupManager(3);
            for (int i = 1; i <= 3; i++) {
                final int member = i;
                actors[i] = new Actor("join-leave-" + periodMillis + "-" + i, false);
                manager.join(actors[i], i, group -> attach(member, group));
            }
        });
        check(waitFor(() -> sees(1, 1, 2, 3) && sees(2, 1, 2, 3) && sees(3, 1, 2, 3)), "Initial readiness timeout");
        onPump(() -> workload(1, 1, 2, 3));
        check(waitFor(() -> completedSenders == 3 && reports.complete()), "Before-join workload timeout");
        onPump(() -> {
            actors[4] = new Actor("join-leave-" + periodMillis + "-4", false);
            groups[1].admit(actors[4], 4, group -> attach(4, group));
        });
        check(waitFor(() -> sees(1, 1, 2, 3, 4) && sees(2, 1, 2, 3, 4)
                && sees(3, 1, 2, 3, 4) && sees(4, 1, 2, 3, 4)), "Join views timeout");
        onPump(() -> { joinObserved = true; workload(2, 1, 2, 3, 4); });
        check(waitFor(() -> completedSenders == 4 && reports.complete()), "Post-join workload timeout");
        onPump(() -> groups[2].leave());
        check(waitFor(() -> sees(1, 1, 3, 4) && sees(3, 1, 3, 4) && sees(4, 1, 3, 4)
                && groups[2].view() == null && !actors[2].dead()), "Voluntary leave views timeout");
        onPump(() -> { leaveObserved = true; workload(3, 1, 3, 4); });
        check(waitFor(() -> completedSenders == 3 && reports.complete()), "Post-leave workload timeout");
        // Explicit extra observation interval, not a proof of global protocol quiescence.
        CompletableFuture<Void> window = new CompletableFuture<>();
        onPump(() -> driver.post(() -> window.complete(null), 100));
        window.get(5, TimeUnit.SECONDS);
        onPump(() -> {
            check(callbackFailure == null, "Callback failed: " + callbackFailure);
            check(joinObserved && leaveObserved, "Missing membership transition evidence");
            for (int i = 1; i <= 4; i++) check(!actors[i].dead(), "Unexpected actor death");
            check(sees(1, 1, 3, 4) && sees(3, 1, 3, 4) && sees(4, 1, 3, 4)
                    && groups[2].view() == null, "Final membership changed");
            check(reports.originals.size() == 20 && reports.confirmed.size() == 20, "Expected twenty independent submissions");
            reports.assess();
            check(reports.histories.get(ids[1]).equals(reports.histories.get(ids[3])), "Continuous members disagree");
            check(reports.histories.get(ids[1]).size() == 20 && reports.histories.get(ids[4]).size() == 14
                    && reports.histories.get(ids[2]).size() == 14, "Incorrect participation totals");
            for (MessageId id : reports.histories.get(ids[4])) check(id.sequence() / 10 != 1, "Newcomer received before-join message");
            for (MessageId id : reports.histories.get(ids[2])) check(id.sequence() / 10 != 3, "Departing actor received post-leave message");
            System.out.println("VerifierJoinLeaveGroupTest: period=" + periodMillis
                    + " ms SUCCESSFUL; join/leave views observed, 20 messages, 68 eligible deliveries, common workload order");
        });
    }
    private void run() throws Exception {
        try { scenario(); }
        finally { onPump(() -> stopped = true); }
    }
    public static void main(String[] args) {
        int status = 0;
        try { new VerifierJoinLeaveGroupTest(0).run(); new VerifierJoinLeaveGroupTest(5).run(); }
        catch (Throwable failure) { failure.printStackTrace(); status = 1; }
        System.exit(status); // Same standalone-process cleanup as TomGroupTest.
    }
}
