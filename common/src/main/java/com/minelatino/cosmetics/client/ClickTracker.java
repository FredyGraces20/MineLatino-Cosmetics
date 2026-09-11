package com.minelatino.cosmetics.client;

import java.util.ArrayDeque;
import java.util.Deque;

/** Counts physical mouse presses over a rolling one-second window. */
public final class ClickTracker {
    private static final Deque<Long> LEFT = new ArrayDeque<>();
    private static final Deque<Long> RIGHT = new ArrayDeque<>();
    private ClickTracker() {}

    public static synchronized void press(int button, int action) {
        if (action != 1 || (button != 0 && button != 1)) return;
        long now = System.currentTimeMillis();
        (button == 0 ? LEFT : RIGHT).addLast(now);
        prune(now);
    }

    public static synchronized int left() {
        prune(System.currentTimeMillis());
        return LEFT.size();
    }

    public static synchronized int right() {
        prune(System.currentTimeMillis());
        return RIGHT.size();
    }

    private static void prune(long now) {
        while (!LEFT.isEmpty() && now - LEFT.peekFirst() >= 1_000) LEFT.removeFirst();
        while (!RIGHT.isEmpty() && now - RIGHT.peekFirst() >= 1_000) RIGHT.removeFirst();
    }
}
