package com.xpressscanner.viewmodel;

import androidx.lifecycle.ViewModel;

import java.util.concurrent.ConcurrentLinkedQueue;

public class ScannerViewModel extends ViewModel {
    private static final long DUPLICATE_SCAN_WINDOW_MS = 1800;

    private final ConcurrentLinkedQueue<String> scanQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean typing = false;
    private boolean autoScanMode = true;
    private boolean cameraTouched = false;
    private String lastSent = "";
    private long lastSentAt = 0L;

    public boolean toggleAutoScanMode() {
        autoScanMode = !autoScanMode;
        return autoScanMode;
    }

    public void setCameraTouched(boolean cameraTouched) {
        this.cameraTouched = cameraTouched;
    }

    public boolean shouldAcceptScan(String value) {
        if (!autoScanMode && !cameraTouched) return false;

        long now = System.currentTimeMillis();
        if (value.equals(lastSent) && now - lastSentAt < DUPLICATE_SCAN_WINDOW_MS) return false;
        lastSent = value;
        lastSentAt = now;
        return true;
    }

    public void enqueueScan(String value) {
        scanQueue.add(value);
    }

    public boolean hasPendingScans() {
        return !scanQueue.isEmpty();
    }

    public String pollScan() {
        return scanQueue.poll();
    }

    public boolean isTyping() {
        return typing;
    }

    public void setTyping(boolean typing) {
        this.typing = typing;
    }
}
