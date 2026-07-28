package com.xpressscanner.logic;

public final class HidKeyboardMapper {
    public static final byte[] RELEASE_REPORT = new byte[8];
    private static final byte[][] ASCII_REPORTS = buildAsciiReports();

    private HidKeyboardMapper() {}

    public static final byte[] HID_KEYBOARD_DESCRIPTOR = {
            (byte) 0x05, (byte) 0x01, (byte) 0x09, (byte) 0x06, (byte) 0xA1, (byte) 0x01,
            (byte) 0x05, (byte) 0x07, (byte) 0x19, (byte) 0xE0, (byte) 0x29, (byte) 0xE7,
            (byte) 0x15, (byte) 0x00, (byte) 0x25, (byte) 0x01, (byte) 0x75, (byte) 0x01,
            (byte) 0x95, (byte) 0x08, (byte) 0x81, (byte) 0x02, (byte) 0x75, (byte) 0x08,
            (byte) 0x95, (byte) 0x01, (byte) 0x81, (byte) 0x01, (byte) 0x05, (byte) 0x08,
            (byte) 0x19, (byte) 0x01, (byte) 0x29, (byte) 0x05, (byte) 0x75, (byte) 0x01,
            (byte) 0x95, (byte) 0x05, (byte) 0x91, (byte) 0x02, (byte) 0x75, (byte) 0x03,
            (byte) 0x95, (byte) 0x01, (byte) 0x91, (byte) 0x01, (byte) 0x05, (byte) 0x07,
            (byte) 0x19, (byte) 0x00, (byte) 0x29, (byte) 0x65, (byte) 0x15, (byte) 0x00,
            (byte) 0x25, (byte) 0x65, (byte) 0x75, (byte) 0x08, (byte) 0x95, (byte) 0x06,
            (byte) 0x81, (byte) 0x00, (byte) 0xC0
    };

    public static byte[] charToHidReport(char c) {
        if (c < ASCII_REPORTS.length) {
            return ASCII_REPORTS[c];
        }
        return RELEASE_REPORT;
    }

    private static byte[][] buildAsciiReports() {
        byte[][] reports = new byte[128][];
        for (int i = 0; i < reports.length; i++) {
            reports[i] = createReport((char) i);
        }
        return reports;
    }

    private static byte[] createReport(char c) {
        byte mod = 0; byte key = 0;
        if (c >= 'a' && c <= 'z') key = (byte) (c - 'a' + 4);
        else if (c >= 'A' && c <= 'Z') { mod = 2; key = (byte) (c - 'A' + 4); }
        else if (c >= '1' && c <= '9') key = (byte) (c - '1' + 30);
        else if (c == '0') key = 39;
        else if (c == '\n') key = 40;
        else if (c == ' ') key = 44;
        else if (c == '-') key = 45;
        else if (c == '=') key = 46;
        else if (c == '[') key = 47;
        else if (c == ']') key = 48;
        else if (c == '\\') key = 49;
        else if (c == ';') key = 51;
        else if (c == '\'') key = 52;
        else if (c == ',') key = 54;
        else if (c == '.') key = 55;
        else if (c == '/') key = 56;
        else if (c == '_') { mod = 2; key = 45; }
        else if (c == '+') { mod = 2; key = 46; }
        else if (c == ':') { mod = 2; key = 51; }
        if (mod == 0 && key == 0) return RELEASE_REPORT;
        return new byte[]{mod, 0, key, 0, 0, 0, 0, 0};
    }
}
