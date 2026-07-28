package com.xpressscanner.logic;

import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ScanHistoryRepository {
    private static final String HISTORY_KEY = "scan_history";
    private static final long HISTORY_RETENTION_MS = 2L * 24 * 60 * 60 * 1000;

    private final SharedPreferences prefs;

    public ScanHistoryRepository(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public void saveScan(String value) {
        String history = prefs.getString(HISTORY_KEY, "");
        long now = System.currentTimeMillis();
        String newEntry = now + "|" + value + "|||";
        prefs.edit().putString(HISTORY_KEY, newEntry + history).apply();
    }

    public List<ScanHistoryItem> getRecentHistory() {
        String history = prefs.getString(HISTORY_KEY, "");
        long cutoffTime = System.currentTimeMillis() - HISTORY_RETENTION_MS;
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault());
        List<ScanHistoryItem> items = new ArrayList<>();
        StringBuilder retainedHistory = new StringBuilder();

        if (!history.isEmpty()) {
            String[] entries = history.split("\\|\\|\\|");
            for (String entry : entries) {
                if (entry.trim().isEmpty()) continue;
                String[] parts = entry.split("\\|", 2);
                if (parts.length != 2) continue;
                try {
                    long timestamp = Long.parseLong(parts[0]);
                    String scannedData = parts[1];
                    if (timestamp >= cutoffTime) {
                        retainedHistory.append(entry).append("|||");
                        items.add(new ScanHistoryItem(timestamp, sdf.format(new Date(timestamp)), scannedData));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        prefs.edit().putString(HISTORY_KEY, retainedHistory.toString()).apply();
        return items;
    }

    public void clearHistory() {
        prefs.edit().remove(HISTORY_KEY).apply();
    }

    public static class ScanHistoryItem {
        public final long timestamp;
        public final String formattedTime;
        public final String value;

        public ScanHistoryItem(long timestamp, String formattedTime, String value) {
            this.timestamp = timestamp;
            this.formattedTime = formattedTime;
            this.value = value;
        }
    }
}
