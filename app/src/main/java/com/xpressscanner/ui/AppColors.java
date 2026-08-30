package com.xpressscanner.ui;

import android.graphics.Color;

public final class AppColors {
    private AppColors() {}

    public static int color(String key) {
        switch (key) {
            // Soft matte darks
            case "bg": return Color.parseColor("#121212");
            case "card": return Color.parseColor("#1E1E24");
            case "cardStroke": return Color.parseColor("#2D2D36");

            // Pills
            case "pillConnected": return Color.parseColor("#173724"); // Glow-like green tint
            case "pillDefault": return Color.parseColor("#292930");

            // Typography
            case "textMain": return Color.parseColor("#F5F5F5");
            case "textSub": return Color.parseColor("#9E9EA7");
            case "textLabel": return Color.parseColor("#6A6A75");

            // Buttons (Muted, corporate tones)
            case "btnRefresh": return Color.parseColor("#383842");
            case "btnDisconnect": return Color.parseColor("#8E244D"); // Deep rose/red
            case "btnConnect": return Color.parseColor("#1D6E50"); // Professional emerald

            case "inputBg": return Color.parseColor("#0D0D0F");
            case "btnSend": return Color.parseColor("#3A61C2"); // Professional corporate blue

            // Accents
            case "accentGreen": return Color.parseColor("#2ECA7F");
            case "accentIndigo": return Color.parseColor("#6B8AF0");
            case "accentRed": return Color.parseColor("#FF5A5F");

            // Camera Overlay
            case "overlay": return Color.parseColor("#D9000000");
            case "overlayActive": return Color.parseColor("#D93A61C2");
            default: return Color.WHITE;
        }
    }
}
