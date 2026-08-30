package com.xpressscanner.ui;

import android.graphics.Color;

public final class AppColors {
    private AppColors() {}

    public static int color(String key) {
        switch (key) {
            case "bg": return Color.parseColor("#1F2026");
            case "card": return Color.parseColor("#30323B");
            case "cardStroke": return Color.parseColor("#4A4D58");
            case "pillConnected": return Color.parseColor("#31343D");
            case "pillDefault": return Color.parseColor("#30323A");
            case "textMain": return Color.parseColor("#F2F2F5");
            case "textSub": return Color.parseColor("#B4B5BD");
            case "textLabel": return Color.parseColor("#A5A6AF");
            case "btnRefresh": return Color.parseColor("#343741");
            case "btnDisconnect": return Color.parseColor("#56373B");
            case "btnConnect": return Color.parseColor("#2E6F5B");
            case "inputBg": return Color.parseColor("#202126");
            case "btnSend": return Color.parseColor("#3C67C8");
            case "accentGreen": return Color.parseColor("#38C68B");
            case "accentIndigo": return Color.parseColor("#7196EA");
            case "accentRed": return Color.parseColor("#E28B91");
            case "overlay": return Color.parseColor("#E82E3038");
            case "overlayActive": return Color.parseColor("#E83C67C8");
            default: return Color.WHITE;
        }
    }
}
