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

/** Three completed application workloads at unambiguous view boundaries; not a general membership policy. */
public final class VerifierReinsertionGroupTest {
    /** Independent send-side oracle, shared by the real scenario and replay negative case. */
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
                errors.add("FORBIDDEN_REPLAY: receiver=" + receiver + " message=" + id); return;
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
        void assess(ActorId first, ActorId second, ActorId returning, Set<MessageId> postReturn) {
            check(errors.isEmpty(), "Delivery violations: " + errors);
            check(complete(), "Missing independent message/recipient obligation or confirmation");
            check(histories.get(first).equals(histories.get(second)), "Survivor full histories disagree");
            List<MessageId> projection = new ArrayList<>();
            for (MessageId id : histories.get(first)) if (postReturn.contains(id)) projection.add(id);
            check(projection.size() == postReturn.size(), "Missing post-return workload");
            check(histories.get(returning).equals(projection), "Returning history differs or contains earlier workload");
        }
    }

    private final Task driver = Executor.self().newTask("verifier-reinsertion-driver");
    private final Actor[] actors = new Actor[4];
    private final Group[] groups = new Group[4];
    private final TomMember[] members = new TomMember[4];
    private final View[] views = new View[4];
    private final ActorId[] ids = new ActorId[4];
    private final Reports reports = new Reports();
    private final Set<MessageId> postReturn = new LinkedHashSet<>();
    private Throwable callbackFailure;
    private boolean deathObserved, removalsObserved, admissionObserved;

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
        Set<ActorId> recipients = new LinkedHashSet<>();
        for (int sender : senders) recipients.add(ids[sender]);
        for (int sender : senders) {
            MessageId id = new MessageId("reinsertion-run", "reinsertion-group", ids[sender], phase);
            byte[] original = {(byte) phase, (byte) sender, 0, -1};
            try {
                byte[] frame = ApplicationEnvelopeCodec.encode(new ApplicationEnvelope(id, original));
                reports.begin(id, original, recipients); // Before any synchronous or queued delivery.
                members[sender].multicast(frame);
                check(!actors[sender].dead(), "Sender died during invocation");
                reports.confirm(id); // Only normal live return confirms.
                if (phase == 3) postReturn.add(id);
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
    }
    private void scenario() throws Exception {
        onPump(() -> {
            GroupManager manager = new GroupManager(3);
            for (int i = 1; i <= 3; i++) {
                final int member = i;
                actors[i] = new Actor("reinsertion-verifier-" + i, false);
                manager.join(actors[i], i, group -> attach(member, group));
            }
        });
        check(waitFor(() -> sees(1, 1, 2, 3) && sees(2, 1, 2, 3) && sees(3, 1, 2, 3)), "Initial readiness timeout");
        onPump(() -> workload(1, 1, 2, 3));
        check(waitFor(reports::complete), "Before-death workload timeout");
        final ActorId[] old = new ActorId[1];
        onPump(() -> {
            old[0] = ids[2];
            actors[2].suicide();
            deathObserved = actors[2].dead();
            check(deathObserved, "Actual death not observed");
        });
        check(waitFor(() -> sees(1, 1, 3) && sees(3, 1, 3)), "Removal views timeout");
        onPump(() -> { removalsObserved = true; workload(2, 1, 3); });
        check(waitFor(reports::complete), "Absent-actor workload timeout");
        onPump(() -> {
            actors[2] = actors[2].resurrect();
            check(actors[2].incarnation() > old[0].incarnation(), "Resurrection did not create a new incarnation");
            views[2] = null;
            groups[1].admit(actors[2], 2, group -> attach(2, group));
        });
        check(waitFor(() -> sees(1, 1, 2, 3) && sees(2, 1, 2, 3) && sees(3, 1, 2, 3)
                && ids[2].incarnation() > old[0].incarnation()), "Effective reinsertion timeout");
        onPump(() -> { admissionObserved = true; workload(3, 1, 2, 3); });
        check(waitFor(reports::complete), "Post-return workload timeout");
        // Explicit extra observation interval, not a proof of global protocol quiescence.
        CompletableFuture<Void> window = new CompletableFuture<>();
        onPump(() -> driver.post(() -> window.complete(null), 100));
        window.get(5, TimeUnit.SECONDS);
        onPump(() -> {
            check(callbackFailure == null, "Callback failed: " + callbackFailure);
            check(deathObserved && removalsObserved && admissionObserved, "Missing transition evidence");
            for (int i = 1; i <= 3; i++) check(!actors[i].dead() && sees(i, 1, 2, 3), "Final participation changed");
            check(reports.originals.size() == 8 && reports.confirmed.size() == 8, "Expected eight independent submissions");
            reports.assess(ids[1], ids[3], ids[2], postReturn);
            check(reports.histories.get(ids[1]).size() == 8 && reports.histories.get(ids[2]).size() == 3,
                    "Incorrect survivor/returning delivery totals");
            System.out.println("VerifierReinsertionGroupTest: SUCCESSFUL; death/removal/new incarnation observed; survivors 8 each, returning 3, no earlier replay");
        });
    }
    private static void rejectsReplay() {
        Reports reports = new Reports();
        ActorId survivor = new ActorId(1, 1), returning = new ActorId(2, 2);
        MessageId missed = new MessageId("negative", "g", survivor, 2);
        byte[] original = {2};
        reports.begin(missed, original, new LinkedHashSet<>(Arrays.asList(survivor)));
        reports.confirm(missed);
        reports.deliver(survivor, missed, original);
        reports.deliver(returning, missed, original);
        check(reports.errors.size() == 1 && reports.errors.get(0).startsWith("FORBIDDEN_REPLAY:")
                && reports.errors.get(0).contains(returning.toString())
                && reports.errors.get(0).contains(missed.toString()), "Replay attribution missing");
        boolean rejected = false;
        try { reports.assess(survivor, survivor, returning, new HashSet<MessageId>()); }
        catch (AssertionError expected) { rejected = expected.getMessage().contains("FORBIDDEN_REPLAY"); }
        check(rejected && !reports.histories.containsKey(returning), "Replay was accepted as eligible delivery");
        System.out.println("VerifierReinsertionGroupTest: simulated forbidden-replay negative case passed");
    }
    public static void main(String[] args) {
        int status = 0;
        try { rejectsReplay(); new VerifierReinsertionGroupTest().scenario(); }
        catch (Throwable failure) { failure.printStackTrace(); status = 1; }
        System.exit(status); // Executor has no shutdown API; same standalone cleanup as TomGroupTest.
    }
}
