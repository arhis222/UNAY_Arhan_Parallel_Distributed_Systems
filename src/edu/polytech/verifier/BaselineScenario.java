package edu.polytech.verifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated, immutable configuration. Counts do not allocate planned slots. */
public final class BaselineScenario {
    private final String executionId, groupId;
    private final Map<ActorId, Integer> plannedSends;
    private final boolean emptyWorkloadTest;
    private final long minimumConfirmedMessages, minimumValidDeliveries, plannedMessages, possibleDeliveries;
    private final int periodMillis;
    private final long budgetMillis;

    public BaselineScenario(String executionId, String groupId, Map<ActorId, Integer> plannedSends,
            boolean emptyWorkloadTest, long minimumConfirmedMessages, long minimumValidDeliveries,
            int periodMillis, long budgetMillis) {
        this.executionId = MessageId.requireName(executionId);
        this.groupId = MessageId.requireName(groupId);
        if (plannedSends == null || plannedSends.isEmpty()) throw new IllegalArgumentException("Members required");
        Map<ActorId, Integer> copy = new LinkedHashMap<>();
        long total = 0;
        try {
            for (Map.Entry<ActorId, Integer> entry : plannedSends.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)
                    throw new IllegalArgumentException("Invalid planned sends");
                copy.put(entry.getKey(), entry.getValue());
                total = Math.addExact(total, entry.getValue().longValue());
            }
            possibleDeliveries = Math.multiplyExact(total, (long) copy.size());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Workload count overflow", overflow);
        }
        if (periodMillis < 0 || budgetMillis <= 0 || minimumConfirmedMessages < 0 || minimumValidDeliveries < 0
                || minimumConfirmedMessages > total || minimumValidDeliveries > possibleDeliveries)
            throw new IllegalArgumentException("Invalid period, budget or coverage");
        if (emptyWorkloadTest ? total != 0 || minimumConfirmedMessages != 0 || minimumValidDeliveries != 0
                : minimumConfirmedMessages < 1 || minimumValidDeliveries < 1)
            throw new IllegalArgumentException("Declare either an empty test or positive traffic coverage");
        this.plannedSends = Collections.unmodifiableMap(copy);
        this.plannedMessages = total;
        this.emptyWorkloadTest = emptyWorkloadTest;
        this.minimumConfirmedMessages = minimumConfirmedMessages;
        this.minimumValidDeliveries = minimumValidDeliveries;
        this.periodMillis = periodMillis;
        this.budgetMillis = budgetMillis;
    }

    public String executionId() { return executionId; }
    public String groupId() { return groupId; }
    public Map<ActorId, Integer> plannedSends() { return plannedSends; }
    public boolean emptyWorkloadTest() { return emptyWorkloadTest; }
    public long minimumConfirmedMessages() { return minimumConfirmedMessages; }
    public long minimumValidDeliveries() { return minimumValidDeliveries; }
    public long plannedMessages() { return plannedMessages; }
    public long possibleDeliveries() { return possibleDeliveries; }
    public int periodMillis() { return periodMillis; }
    public long budgetMillis() { return budgetMillis; }
}
