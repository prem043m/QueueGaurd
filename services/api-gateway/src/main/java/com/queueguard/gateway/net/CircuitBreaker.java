package com.queueguard.gateway.net;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tiny per-downstream-service circuit breaker.
 * CLOSED -> (5 consecutive failures) -> OPEN -> (10s) -> HALF_OPEN -> (probe ok) -> CLOSED
 */
public class CircuitBreaker {

    public enum State {CLOSED, OPEN, HALF_OPEN}

    private static final int FAILURE_THRESHOLD = 5;
    private static final long OPEN_DURATION_MS = 10_000;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    public boolean allow() {
        if (state.get() == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() > OPEN_DURATION_MS) {
                state.compareAndSet(State.OPEN, State.HALF_OPEN);
                return true; // one probe request is let through
            }
            return false;
        }
        return true;
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    public void onFailure() {
        if (state.get() == State.HALF_OPEN) {
            trip();
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
            trip();
        }
    }

    private void trip() {
        state.set(State.OPEN);
        openedAt.set(System.currentTimeMillis());
        consecutiveFailures.set(0);
    }

    public State currentState() {
        return state.get();
    }
}
