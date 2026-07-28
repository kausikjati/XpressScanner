package com.xpressscanner.ui;

import android.graphics.Color;

public final class AppColors {
    private AppColors() {}

    public static int color(String key) {
        switch (key) {
            case "bg": return Color.parseColor("#0B0F1A");
            case "card": return Color.parseColor("#141B2E");
            case "cardStroke": return Color.parseColor("#232C42");
            case "pillConnected": return Color.parseColor("#4338CA");
            case "pillDefault": return Color.parseColor("#1C2438");
            case "textMain": return Color.parseColor("#F1F5F9");
            case "textSub": return Color.parseColor("#8B93A7");
            case "textLabel": return Color.parseColor("#5B6478");
            case "btnRefresh": return Color.parseColor("#2A3350");
            case "btnDisconnect": return Color.parseColor("#DC2626");
            case "btnConnect": return Color.parseColor("#15803D");
            case "inputBg": return Color.parseColor("#0B0F1A");
            case "btnSend": return Color.parseColor("#4F46E5");
            case "accentGreen": return Color.parseColor("#2DD4BF");
            case "accentIndigo": return Color.parseColor("#6366F1");
            case "accentRed": return Color.parseColor("#F87171");
            case "overlay": return Color.parseColor("#99000000");
            case "overlayActive": return Color.parseColor("#CC1D4ED8");
            default: return Color.WHITE;
        }
    }
}
