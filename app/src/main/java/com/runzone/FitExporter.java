package com.runzone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Writes the Garmin FIT activity profile shared by exports and provider uploads. */
public final class FitExporter {
    static final String MIME_TYPE = "application/octet-stream";
    private static final long FIT_EPOCH_SECONDS = 631065600L;
    private static final long INVALID_UINT32 = 0xffffffffL;
    private static final int INVALID_UINT16 = 0xffff;
    private static final int INVALID_COORDINATE = 0x7fffffff;
    private static final int ENUM = 0, UINT8 = 2, UINT16 = 0x84, SINT32 = 0x85, UINT32 = 0x86;
    private static final int FILE_ID = 0, EVENT = 1, RECORD = 2, LAP = 3, SESSION = 4, ACTIVITY = 5, HRV = 6;
    private static final int TIMER_START = 0, TIMER_STOP_ALL = 4, RUNNING = 1, GENERIC = 0;

    private FitExporter() {}

    private static final class Sample {
        RunRecord.Point point;
        int bpm = -1;
        int cadence = 255;
        long activeMs = -1;
        List<Integer> rrTicks;
    }

    public static void write(RunRecord run, OutputStream output) throws IOException {
        Messages data = new Messages();
        long start = run.startTime;
        long end = start + run.elapsedMs;

        data.define(FILE_ID, 0, 0, ENUM, 1, UINT16, 2, UINT16, 4, UINT32);
        data.message(FILE_ID, 4, 255, 0, timestamp(start)); // Activity, development manufacturer.
        data.define(EVENT, 21, 253, UINT32, 0, ENUM, 1, ENUM);
        data.define(RECORD, 20, 253, UINT32, 0, SINT32, 1, SINT32, 2, UINT16, 3, UINT8, 4, UINT8, 5, UINT32, 6, UINT16);
        timer(data, start, TIMER_START);

        TreeMap<Integer, TreeMap<Long, Sample>> tracks = new TreeMap<>();
        for (RunRecord.Point point : run.points) {
            Sample sample = sample(tracks, point.track, point.time);
            sample.point = point;
            sample.bpm = point.bpm;
            sample.activeMs = point.activeMs;
        }
        boolean hasRrIntervals = false;
        for (RunRecord.Pulse pulse : run.pulses) {
            Sample sample = sample(tracks, pulse.track, pulse.time);
            sample.bpm = pulse.bpm;
            if (pulse.activeMs >= 0) sample.activeMs = pulse.activeMs;
            if (pulse.rrTicks.length > 0) {
                hasRrIntervals = true;
                if (sample.rrTicks == null) sample.rrTicks = new ArrayList<>();
                for (int ticks : pulse.rrTicks) sample.rrTicks.add(ticks);
            }
        }
        if (hasRrIntervals) data.define(HRV, 78, 0, UINT16);
        for (RunRecord.Cadence cadence : run.cadence) {
            // FIT running cadence counts strides, while the phone detects individual steps.
            sample(tracks, cadence.track, cadence.time).cadence = Math.min(254, (cadence.stepsPerMinute + 1) / 2);
        }
        if (tracks.isEmpty()) {
            // FIT activities need a record even when the run only captured summary timing.
            data.message(RECORD, timestamp(start), INVALID_COORDINATE, INVALID_COORDINATE,
                    INVALID_UINT16, 255, 255, INVALID_UINT32, INVALID_UINT16);
        }

        long previousTime = start;
        long pausedMs = 0;
        double distance = 0;
        boolean exactTimer = run.timerOffsets != null;
        List<Long> timerOffsets = exactTimer ? run.timerOffsets : new ArrayList<>();
        int nextEvent = 0;
        long observedMs = 0, movingMs = 0;
        double ascent = 0, descent = 0;
        boolean hasElevation = false;
        for (var segment : tracks.entrySet()) {
            TreeMap<Long, Sample> track = segment.getValue();
            long segmentEnd = end;
            if (exactTimer) {
                // Every resume increments the saved track number, even if a track has no samples.
                long transitions = 2L * segment.getKey();
                while (nextEvent < transitions && nextEvent < timerOffsets.size()) {
                    writeTransition(data, start, timerOffsets, nextEvent++);
                }
                if (nextEvent > 0) previousTime = Math.max(previousTime, start + timerOffsets.get(nextEvent - 1));
                if (nextEvent < timerOffsets.size()) segmentEnd = start + timerOffsets.get(nextEvent);
            } else {
                long firstTime = Math.max(previousTime, Math.min(end, track.firstKey()));
                Sample first = track.firstEntry().getValue();
                long pauseAt = first.activeMs >= 0
                        ? Math.max(previousTime, Math.min(firstTime, start + first.activeMs + pausedMs))
                        : previousTime;
                if (segment.getKey() > 0 && firstTime > pauseAt) {
                    timerOffsets.add(pauseAt - start);
                    timerOffsets.add(firstTime - start);
                    timer(data, pauseAt, TIMER_STOP_ALL);
                    timer(data, firstTime, TIMER_START);
                }
            }
            RunRecord.Point previousPoint = null;
            Double elevationAnchor = null;
            for (var entry : track.entrySet()) {
                Sample sample = entry.getValue();
                long time = Math.max(previousTime, Math.min(segmentEnd, entry.getKey()));
                RunRecord.Point point = sample.point;
                if (point != null) distance = Math.max(distance, point.distance);
                long speed = recordSpeed(point, previousPoint);
                if (sample.rrTicks != null) {
                    for (int ticks : sample.rrTicks) {
                        // BLE reports 1/1024-second ticks. FIT stores millisecond intervals.
                        data.message(HRV, Math.round(ticks * 1000.0 / 1024));
                    }
                }
                data.message(RECORD, timestamp(time),
                        point == null ? INVALID_COORDINATE : semicircles(point.lat),
                        point == null ? INVALID_COORDINATE : semicircles(point.lon),
                        point == null || point.altitude == null ? INVALID_UINT16
                                : unsigned((point.altitude + 500) * 5, INVALID_UINT16),
                        heartRate(sample.bpm), sample.cadence,
                        point == null && sample.cadence != 255 && !RunRecord.validBpm(sample.bpm)
                                ? INVALID_UINT32 : unsigned(distance * 100, INVALID_UINT32), speed);
                if (point != null) {
                    long interval = previousPoint == null ? 0 : sampleInterval(point, previousPoint);
                    if (interval > 0 && interval <= 10000 && speed != INVALID_UINT16) {
                        observedMs += interval;
                        if (speed >= 500) movingMs += interval;
                    }
                    if (point.altitude == null || interval <= 0 || interval > 10000 || elevationAnchor == null) {
                        elevationAnchor = point.altitude;
                    } else {
                        hasElevation = true;
                        double change = point.altitude - elevationAnchor;
                        // Accumulate changes once they clear a small GPS altitude noise band.
                        if (Math.abs(change) >= 5) {
                            ascent += Math.max(0, change);
                            descent += Math.max(0, -change);
                            elevationAnchor = point.altitude;
                        }
                    }
                    previousPoint = point;
                }
                if (sample.activeMs >= 0) pausedMs = Math.max(0, time - start - sample.activeMs);
                previousTime = time;
            }
        }
        if (exactTimer) {
            while (nextEvent < timerOffsets.size()) writeTransition(data, start, timerOffsets, nextEvent++);
            if (nextEvent % 2 == 0) timer(data, end, TIMER_STOP_ALL);
        } else {
            long stop = Math.min(end, Math.max(previousTime, start + run.durationMs + pausedMs));
            timer(data, stop, TIMER_STOP_ALL);
            if (stop < end) timerOffsets.add(stop - start);
        }

        long totalElapsed = unsigned(run.elapsedMs, INVALID_UINT32);
        long totalTimer = unsigned(run.durationMs, INVALID_UINT32);
        long totalDistance = unsigned(run.meters * 100, INVALID_UINT32);
        int average = heartRate(run.averageBpm());
        int maximum = heartRate(run.maxBpm());
        int laps = writeLaps(data, run, timerOffsets, average, maximum);
        data.define(SESSION, 18, 254, UINT16, 253, UINT32, 0, ENUM, 1, ENUM, 2, UINT32,
                5, ENUM, 6, ENUM, 7, UINT32, 8, UINT32, 9, UINT32, 14, UINT16, 16, UINT8, 17, UINT8,
                22, UINT16, 23, UINT16, 25, UINT16, 26, UINT16, 59, UINT32);
        data.message(SESSION, 0, timestamp(end), 8, 1, timestamp(start), RUNNING, GENERIC,
                totalElapsed, totalTimer, totalDistance, speed(run.meters, run.durationMs), average, maximum,
                hasElevation ? unsigned(ascent, INVALID_UINT16) : INVALID_UINT16,
                hasElevation ? unsigned(descent, INVALID_UINT16) : INVALID_UINT16, 0, laps,
                observedMs == 0 ? INVALID_UINT32 : unsigned(Math.min(run.durationMs, movingMs), INVALID_UINT32));
        data.define(ACTIVITY, 34, 253, UINT32, 0, UINT32, 1, UINT16, 2, ENUM, 3, ENUM, 4, ENUM);
        data.message(ACTIVITY, timestamp(end), totalTimer, 1, 0, 26, 1);
        data.writeFile(output);
    }

    private static void writeTransition(Messages data, long start, List<Long> offsets, int index) {
        timer(data, start + offsets.get(index), index % 2 == 0 ? TIMER_STOP_ALL : TIMER_START);
    }

    private static int writeLaps(Messages data, RunRecord run, List<Long> timerOffsets, int average, int maximum) {
        List<RunRecord.Split> splits = run.splits();
        int count = Math.max(1, splits.size());
        data.define(LAP, 19, 254, UINT16, 253, UINT32, 0, ENUM, 1, ENUM, 2, UINT32,
                7, UINT32, 8, UINT32, 9, UINT32, 13, UINT16, 15, UINT8, 16, UINT8, 24, ENUM, 25, ENUM);
        long active = 0, elapsed = 0, paused = 0;
        double distance = 0;
        int pause = 0;
        for (int i = 0; i < count; i++) {
            boolean last = i == count - 1;
            long nextActive = last ? run.durationMs : active + Math.min(run.durationMs - active, splits.get(i).durationMs);
            // Convert each active-time split boundary to wall time. A boundary at the
            // instant a pause begins ends the lap before that pause.
            while (pause + 1 < timerOffsets.size() && timerOffsets.get(pause) < nextActive + paused) {
                paused += timerOffsets.get(pause + 1) - timerOffsets.get(pause);
                pause += 2;
            }
            long nextElapsed = last ? run.elapsedMs : Math.min(run.elapsedMs, nextActive + paused);
            double nextDistance = last ? run.meters : distance + splits.get(i).meters;
            data.message(LAP, i, timestamp(run.startTime + nextElapsed), 9, 1,
                    timestamp(run.startTime + elapsed), unsigned(nextElapsed - elapsed, INVALID_UINT32),
                    unsigned(nextActive - active, INVALID_UINT32), unsigned((nextDistance - distance) * 100, INVALID_UINT32),
                    speed(nextDistance - distance, nextActive - active),
                    count == 1 ? average : 255, count == 1 ? maximum : 255, last ? 7 : 2, RUNNING);
            active = nextActive;
            elapsed = nextElapsed;
            distance = nextDistance;
        }
        return count;
    }

    private static long recordSpeed(RunRecord.Point point, RunRecord.Point previous) {
        if (point == null) return INVALID_UINT16;
        if (RunRecord.validSpeed(point.speedMps)) return unsigned(point.speedMps * 1000, INVALID_UINT16);
        if (previous == null) return INVALID_UINT16;
        return speed(point.distance - previous.distance, sampleInterval(point, previous));
    }

    private static long sampleInterval(RunRecord.Point point, RunRecord.Point previous) {
        return point.activeMs >= 0 && previous.activeMs >= 0
                ? point.activeMs - previous.activeMs : point.time - previous.time;
    }

    private static long speed(double meters, long durationMs) {
        if (durationMs <= 0 || !RunRecord.validSpeed(meters)) return INVALID_UINT16;
        return unsigned(meters * 1_000_000 / durationMs, INVALID_UINT16);
    }

    private static Sample sample(TreeMap<Integer, TreeMap<Long, Sample>> tracks, int track, long time) {
        return tracks.computeIfAbsent(track, key -> new TreeMap<>()).computeIfAbsent(time, key -> new Sample());
    }

    private static void timer(Messages data, long time, int type) {
        data.message(EVENT, timestamp(time), 0, type);
    }

    private static long timestamp(long time) {
        return unsigned(time / 1000 - FIT_EPOCH_SECONDS, INVALID_UINT32);
    }

    private static int semicircles(double degrees) {
        int value = (int) Math.round(degrees * (0x80000000L / 180.0));
        return value == INVALID_COORDINATE ? INVALID_COORDINATE - 1 : value;
    }

    private static int heartRate(int bpm) {
        return RunRecord.validBpm(bpm) && bpm < 255 ? bpm : 255;
    }

    private static long unsigned(double value, long invalid) {
        long rounded = Math.round(value);
        return rounded >= 0 && rounded < invalid ? rounded : invalid;
    }

    private static final class Messages extends ByteArrayOutputStream {
        private final int[][] fieldSizes = new int[HRV + 1][];

        void define(int local, int global, int... fields) {
            int[] sizes = new int[fields.length / 2];
            fieldSizes[local] = sizes;
            write(0x40 | local);
            write(0); // Reserved.
            write(0); // Little-endian architecture.
            littleEndian(global, 2);
            write(sizes.length);
            for (int i = 0; i < sizes.length; i++) {
                int type = fields[2 * i + 1];
                sizes[i] = type == UINT16 ? 2 : (type == UINT32 || type == SINT32 ? 4 : 1);
                write(fields[2 * i]);
                write(sizes[i]);
                write(type);
            }
        }

        void message(int local, long... values) {
            int[] sizes = fieldSizes[local];
            if (values.length != sizes.length) throw new IllegalArgumentException("FIT field count");
            write(local);
            for (int i = 0; i < sizes.length; i++) littleEndian(values[i], sizes[i]);
        }

        void writeFile(OutputStream output) throws IOException {
            Messages header = new Messages();
            header.write(12);
            header.write(0x10); // FIT protocol 1.0.
            header.littleEndian(100, 2); // FIT profile 1.00.
            header.littleEndian(count, 4);
            header.write(new byte[]{'.', 'F', 'I', 'T'});
            header.writeTo(output);
            writeTo(output);
            int crc = crc16(crc16(0, header.buf, header.count), buf, count);
            output.write(crc & 0xff);
            output.write(crc >>> 8);
        }

        private void littleEndian(long value, int bytes) {
            for (int i = 0; i < bytes; i++) write((int) (value >>> (8 * i)) & 0xff);
        }

        private static int crc16(int crc, byte[] bytes, int length) {
            for (int i = 0; i < length; i++) {
                crc ^= bytes[i] & 0xff;
                for (int bit = 0; bit < 8; bit++) {
                    crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xa001);
                }
            }
            return crc;
        }
    }
}
