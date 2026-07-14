package com.agora.service.trading;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-process per-position lease shared by exchange-mutating position workflows. */
public final class PositionMutationGuard {

    private static final ConcurrentHashMap<Long, ActiveOperation> ACTIVE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Map<Long, Integer>> HELD_BY_THREAD =
            ThreadLocal.withInitial(HashMap::new);

    private PositionMutationGuard() {
    }

    public static Lease tryAcquire(Long positionId, String operation) {
        if (positionId == null) return Lease.rejected(null, "POSITION_ID_REQUIRED");
        Map<Long, Integer> held = HELD_BY_THREAD.get();
        Integer depth = held.get(positionId);
        if (depth != null && depth > 0) {
            held.put(positionId, depth + 1);
            ActiveOperation active = ACTIVE.get(positionId);
            return Lease.reentrant(positionId,
                    active == null ? operation : active.operation());
        }
        String token = UUID.randomUUID().toString();
        String normalizedOperation = operation == null || operation.isBlank()
                ? "UNKNOWN" : operation;
        ActiveOperation candidate = new ActiveOperation(normalizedOperation, token);
        ActiveOperation existing = ACTIVE.putIfAbsent(positionId, candidate);
        if (existing != null) return Lease.rejected(positionId, existing.operation());
        held.put(positionId, 1);
        return Lease.acquired(positionId, normalizedOperation, token);
    }

    public static boolean isBusy(Long positionId) {
        return positionId != null && ACTIVE.containsKey(positionId);
    }

    public static String activeOperation(Long positionId) {
        ActiveOperation active = positionId == null ? null : ACTIVE.get(positionId);
        return active == null ? null : active.operation();
    }

    private record ActiveOperation(String operation, String token) {
    }

    public static final class Lease implements AutoCloseable {
        private final Long positionId;
        private final String operation;
        private final String token;
        private final boolean acquired;
        private final boolean root;
        private boolean closed;

        private Lease(Long positionId, String operation, String token, boolean acquired, boolean root) {
            this.positionId = positionId;
            this.operation = operation;
            this.token = token;
            this.acquired = acquired;
            this.root = root;
        }

        static Lease acquired(Long positionId, String operation, String token) {
            return new Lease(positionId, operation, token, true, true);
        }

        static Lease reentrant(Long positionId, String operation) {
            return new Lease(positionId, operation, null, true, false);
        }

        static Lease rejected(Long positionId, String activeOperation) {
            return new Lease(positionId, activeOperation, null, false, false);
        }

        public boolean acquired() {
            return acquired;
        }

        public String activeOperation() {
            return operation;
        }

        @Override
        public void close() {
            if (closed || !acquired || positionId == null) return;
            closed = true;
            Map<Long, Integer> held = HELD_BY_THREAD.get();
            if (root) {
                held.remove(positionId);
                if (held.isEmpty()) HELD_BY_THREAD.remove();
                if (token != null) {
                    ACTIVE.computeIfPresent(positionId, (id, active) ->
                            token.equals(active.token()) ? null : active);
                }
                return;
            }
            int depth = held.getOrDefault(positionId, 0);
            if (depth > 1) {
                held.put(positionId, depth - 1);
                return;
            }
            held.remove(positionId);
            if (held.isEmpty()) HELD_BY_THREAD.remove();
        }
    }
}
