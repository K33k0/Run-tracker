package com.runzone;

public final class HeartRate {
    private static final int[] NO_INTERVALS = new int[0];
    private HeartRate() {}
    public static int decode(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return -1;
        // Sensor contact supported, but not detected.
        if ((bytes[0] & 4) != 0 && (bytes[0] & 2) == 0) return -1;
        if ((bytes[0] & 1) != 0) return bytes.length < 3 ? -1 : (bytes[1] & 255) | ((bytes[2] & 255) << 8);
        return bytes[1] & 255;
    }
    static int[] rrIntervals(byte[] bytes) {
        if (decode(bytes) <= 0 || (bytes[0] & 0x10) == 0) return NO_INTERVALS;
        int offset = (bytes[0] & 1) == 0 ? 2 : 3;
        if ((bytes[0] & 8) != 0) offset += 2; // Optional energy expended field.
        int remaining = bytes.length - offset;
        if (remaining < 2 || remaining % 2 != 0) return NO_INTERVALS;
        int[] intervals = new int[remaining / 2];
        for (int i = 0; i < intervals.length; i++, offset += 2) {
            intervals[i] = (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
        }
        return intervals;
    }
    public static int zone(int bpm, int low, int high) {
        return bpm < low ? -1 : bpm > high ? 1 : 0;
    }
}
