package com.example.yukiai;

import java.util.List;

public class VisionRequestGate {

    public static final long MIN_INTERVAL_MS = 5000L;

    private volatile boolean busy = false;
    private volatile long lastFinishedAt = 0L;

    // последний "снимок" сцены, который ещё не отправили, если gate был занят
    private volatile List<String> pendingScene = null;

    public synchronized boolean tryAcquire() {
        if (busy) return false;
        if (System.currentTimeMillis() - lastFinishedAt < MIN_INTERVAL_MS) return false;
        busy = true;
        return true;
    }

    public synchronized void release() {
        busy = false;
        lastFinishedAt = System.currentTimeMillis();
    }

    public synchronized void setPending(List<String> scene) {
        pendingScene = scene;
    }

    public synchronized List<String> consumePending() {
        List<String> p = pendingScene;
        pendingScene = null;
        return p;
    }

    public boolean isBusy() {
        return busy;
    }
}