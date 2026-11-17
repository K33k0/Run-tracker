package com.runzone;

public final class HeartRate {
    private HeartRate() {}
    public static int decode(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return -1;
        // Sensor contact supported, but not detected.
        if ((bytes[0] & 4) != 0 && (bytes[0] & 2) == 0) return -1;
        if ((bytes[0] & 1) != 0) return bytes.length < 3 ? -1 : (bytes[1] & 255) | ((bytes[2] & 255) << 8);
        return bytes[1] & 255;
    }
    public static int zone(int bpm, int low, int high) {
        return bpm < low ? -1 : bpm > high ? 1 : 0;
    }
}
