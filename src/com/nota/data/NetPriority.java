package com.nota.data;

public class NetPriority {

    private static final long MAX_HOLD_MS = 15000;

    private static volatile long audioUntil;

    public static void audioStarting() {
        audioUntil = System.currentTimeMillis() + MAX_HOLD_MS;
    }

    public static void audioReady() {
        audioUntil = 0;
    }

    public static void yieldToAudio() {
        while (System.currentTimeMillis() < audioUntil) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
