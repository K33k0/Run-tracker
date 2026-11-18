package com.runzone;

/** Numeric release comparison, also supporting older two-part installed versions. */
final class ReleaseVersion {
    static long code(String version) {
        if (version == null || !version.matches("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))?")) return -1;
        try {
            String[] parts = version.replaceFirst("^v", "").split("\\.");
            long major = Long.parseLong(parts[0]), minor = Long.parseLong(parts[1]);
            long patch = parts.length == 3 ? Long.parseLong(parts[2]) : 0;
            if (major > 2100 || minor > 999 || patch > 999) return -1;
            long code = major * 1_000_000 + minor * 1_000 + patch;
            return major > 2100 || minor > 999 || patch > 999 || code <= 0 || code > 2_100_000_000L ? -1 : code;
        } catch (NumberFormatException e) { return -1; }
    }
    static boolean newer(String release, String installed) {
        long current = code(installed), next = code(release);
        return current >= 0 && next > current;
    }
}
