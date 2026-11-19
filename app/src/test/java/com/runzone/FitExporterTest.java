package com.runzone;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FitExporterTest {
    private static final long START = 1700000000000L;
    private static final long FIT_START = 1068934400L;

    @Test public void exportsRunningSessionWithRecordedSamplesAndTimerEvents() throws Exception {
        RunRecord run = new RunRecord(START, START + 100000, 80000, 200, 120, 150, false,
                List.of(new RunRecord.Point(51, -.1, START + 1000, 1000, 0, 140, 0, 50d),
                        new RunRecord.Point(51.001, -.1, START + 90000, 70000, 200, 160, 1, 52d)),
                List.of(new RunRecord.Pulse(START + 1000, 1000, 141, 0),
                        new RunRecord.Pulse(START + 91000, 71000, 161, 1)));
        Map<Integer, List<Map<Integer, Long>>> fit = decode(run);
        assertEquals(4L, field(fit, 0, 0, 0));
        assertEquals(FIT_START, field(fit, 0, 0, 4));
        assertEquals(1L, field(fit, 18, 0, 5));
        assertEquals(100000L, field(fit, 18, 0, 7));
        assertEquals(80000L, field(fit, 18, 0, 8));
        assertEquals(20000L, field(fit, 19, 0, 9));
        assertEquals(1L, field(fit, 34, 0, 1));
        assertEquals(3, fit.get(20).size());
        assertEquals(608453700L, field(fit, 20, 0, 0));
        assertEquals(2750L, field(fit, 20, 0, 2));
        assertEquals(141L, field(fit, 20, 0, 3));
        assertEquals(20000L, field(fit, 20, 2, 5));
        assertEquals(4, fit.get(21).size());
        assertEquals(4L, field(fit, 21, 1, 1));
        assertEquals(FIT_START + 70, field(fit, 21, 1, 253));
        assertEquals(0L, field(fit, 21, 2, 1));
        assertEquals(FIT_START + 90, field(fit, 21, 2, 253));
    }

    @Test public void heartRateOnlyUsesFitMissingValueForPositionAndAltitude() throws Exception {
        RunRecord run = new RunRecord(START, START + 10000, 10000, 0, 120, 150, false,
                List.of(), List.of(new RunRecord.Pulse(START + 1000, 1000, 90, 0)));
        Map<Integer, List<Map<Integer, Long>>> fit = decode(run);
        assertEquals(0x7fffffffL, field(fit, 20, 0, 0));
        assertEquals(0x7fffffffL, field(fit, 20, 0, 1));
        assertEquals(0xffffL, field(fit, 20, 0, 2));
        assertEquals(90L, field(fit, 20, 0, 3));
        assertEquals(0xffffL, field(fit, 20, 0, 6));
        assertEquals(255L, field(fit, 20, 0, 4));
        assertEquals(0xffffL, field(fit, 18, 0, 22));
        assertEquals(0xffffffffL, field(fit, 18, 0, 59));
        assertFalse(fit.containsKey(78));
    }

    @Test public void preservesBeatIntervalOrderAndConvertsBluetoothTicksToFitMilliseconds() throws Exception {
        RunRecord run = RunRecord.fromJson(new JSONObject("""
                {"date":1700000000000, "startTime":1700000000000, "endTime":1700000010000,
                 "duration":10000, "meters":0,
                 "heartRate":[{"time":1700000002000,"bpm":120,"rrTicks":[512,768,0]},
                              {"time":1700000002000,"bpm":121,"rrTicks":[513]},
                              {"time":1700000003000,"bpm":120}]}
                """));
        Map<Integer, List<Map<Integer, Long>>> fit = decode(run);
        assertEquals(3, fit.get(78).size());
        assertEquals(500L, field(fit, 78, 0, 0));
        assertEquals(750L, field(fit, 78, 1, 0));
        assertEquals(501L, field(fit, 78, 2, 0));
        assertEquals(2, fit.get(20).size());
    }

    @Test public void exportsCadenceWithoutGpsAndSummarizesMovementAndElevation() throws Exception {
        RunRecord run = RunRecord.fromJson(new JSONObject("""
                {
                  "date":1700000000000, "startTime":1700000000000, "endTime":1700000030000,
                  "duration":20000, "meters":20, "timerOffsets":[10000,20000],
                  "cadence":[{"time":1700000002000,"spm":180}],
                  "route":[
                    {"lat":51,"lon":0,"time":1700000001000,"activeMs":1000,"distanceMeters":0,"altitude":100},
                    {"lat":51,"lon":0,"time":1700000003000,"activeMs":3000,"distanceMeters":10,"altitude":103},
                    {"lat":51,"lon":0,"time":1700000005000,"activeMs":5000,"distanceMeters":20,"altitude":108},
                    {"lat":51,"lon":0,"time":1700000007000,"activeMs":7000,"distanceMeters":20,"altitude":102},
                    {"lat":51,"lon":0,"time":1700000021000,"activeMs":11000,"distanceMeters":20,"altitude":150,"track":1}
                  ]
                }
                """));
        Map<Integer, List<Map<Integer, Long>>> fit = decode(run);
        assertEquals(FIT_START+2, field(fit, 20, 1, 253));
        assertEquals(90L, field(fit, 20, 1, 4));
        assertEquals(0x7fffffffL, field(fit, 20, 1, 0));
        assertEquals(8L, field(fit, 18, 0, 22));
        assertEquals(6L, field(fit, 18, 0, 23));
        assertEquals(4000L, field(fit, 18, 0, 59));
        assertEquals(20000L, field(fit, 18, 0, 8));
        assertEquals(30000L, field(fit, 18, 0, 7));
    }

    @Test public void summaryOnlyRunIncludesARecordWithoutInventingSensorData() throws Exception {
        RunRecord run = new RunRecord(START, START + 10000, 10000, 0, 120, 150, false,
                List.of(), List.of());
        Map<Integer, List<Map<Integer, Long>>> fit = decode(run);
        assertEquals(FIT_START, field(fit, 20, 0, 253));
        assertEquals(0x7fffffffL, field(fit, 20, 0, 0));
        assertEquals(0xffffffffL, field(fit, 20, 0, 5));
    }

    @Test public void exportsKilometreLapsAndExactPausesIncludingASaveWhilePaused() throws Exception {
        RunRecord run = RunRecord.fromJson(new JSONObject("""
                {
                  "date": 1700000000000, "startTime": 1700000000000, "endTime": 1700000800000,
                  "duration": 730000, "meters": 2500, "timerOffsets": [200000, 240000, 770000],
                  "route": [
                    {"lat": 51, "lon": 0, "time": 1700000100000, "activeMs": 100000,
                     "distanceMeters": 400, "track": 0},
                    {"lat": 51.01, "lon": 0, "time": 1700000340000, "activeMs": 300000,
                     "distanceMeters": 1000, "track": 1},
                    {"lat": 51.02, "lon": 0, "time": 1700000640000, "activeMs": 600000,
                     "distanceMeters": 2000, "track": 1},
                    {"lat": 51.025, "lon": 0, "time": 1700000760000, "activeMs": 720000,
                     "distanceMeters": 2500, "track": 1}
                  ]
                }
                """));
        Map<Integer, List<Map<Integer, Long>>> fit = decode(run);
        assertEquals(3L, field(fit, 18, 0, 26));
        assertEquals(3, fit.get(19).size());
        assertEquals(FIT_START + 340, field(fit, 19, 0, 253));
        assertEquals(FIT_START + 340, field(fit, 19, 1, 2));
        assertEquals(340000L, field(fit, 19, 0, 7));
        assertEquals(300000L, field(fit, 19, 0, 8));
        assertEquals(100000L, field(fit, 19, 0, 9));
        assertEquals(100000L, field(fit, 19, 1, 9));
        assertEquals(160000L, field(fit, 19, 2, 7));
        assertEquals(130000L, field(fit, 19, 2, 8));
        assertEquals(50000L, field(fit, 19, 2, 9));
        assertEquals(3333L, field(fit, 19, 0, 13));
        assertEquals(3425L, field(fit, 18, 0, 14));
        assertEquals(4, fit.get(21).size());
        assertEquals(FIT_START + 200, field(fit, 21, 1, 253));
        assertEquals(FIT_START + 240, field(fit, 21, 2, 253));
        assertEquals(FIT_START + 770, field(fit, 21, 3, 253));
    }

    @Test public void usesMeasuredSpeedAndDerivesSpeedOnlyWithinTheSameTrack() throws Exception {
        RunRecord run = RunRecord.fromJson(new JSONObject("""
                {
                  "date":1700000000000, "startTime":1700000000000, "endTime":1700000030000,
                  "duration":20000, "meters":35, "timerOffsets":[10000,20000],
                  "route":[
                    {"lat":51,"lon":0,"time":1700000001000,"activeMs":1000,"distanceMeters":0},
                    {"lat":51,"lon":0,"time":1700000003000,"activeMs":3000,"distanceMeters":10,"speedMps":3.25},
                    {"lat":51,"lon":0,"time":1700000005000,"activeMs":5000,"distanceMeters":20},
                    {"lat":51,"lon":0,"time":1700000021000,"activeMs":11000,"distanceMeters":20,"track":1},
                    {"lat":51,"lon":0,"time":1700000026000,"activeMs":16000,"distanceMeters":35,"track":1}
                  ]
                }
                """));
        Map<Integer, List<Map<Integer, Long>>> fit = decode(run);
        assertEquals(0xffffL, field(fit, 20, 0, 6));
        assertEquals(3250L, field(fit, 20, 1, 6));
        assertEquals(5000L, field(fit, 20, 2, 6));
        assertEquals(0xffffL, field(fit, 20, 3, 6));
        assertEquals(3000L, field(fit, 20, 4, 6));
        assertEquals(1750L, field(fit, 18, 0, 14));
    }

    @Test public void emitsPauseAndResumeEvenWhenTheRunHasNoSamples() throws Exception {
        RunRecord run = RunRecord.fromJson(new JSONObject("""
                {"date":1700000000000, "startTime":1700000000000, "endTime":1700000010000,
                 "duration":7000, "meters":0, "timerOffsets":[2000,5000]}
                """));
        Map<Integer, List<Map<Integer, Long>>> fit = decode(run);
        assertEquals(4, fit.get(21).size());
        assertEquals(FIT_START + 2, field(fit, 21, 1, 253));
        assertEquals(FIT_START + 5, field(fit, 21, 2, 253));
        assertEquals(FIT_START + 10, field(fit, 21, 3, 253));
    }

    private static long field(Map<Integer, List<Map<Integer, Long>>> fit, int message, int index, int field) {
        return fit.get(message).get(index).get(field);
    }

    private static Map<Integer, List<Map<Integer, Long>>> decode(RunRecord run) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FitExporter.write(run, output);
        byte[] bytes = output.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(12, buffer.get());
        assertEquals(0x10, buffer.get());
        buffer.getShort();
        int dataLength = buffer.getInt();
        assertEquals(bytes.length - 14, dataLength);
        assertEquals(0x5449462e, buffer.getInt());

        // FIT's published nibble table checks the file CRC independently of the writer.
        int[] table = {0, 0xcc01, 0xd801, 0x1400, 0xf001, 0x3c00, 0x2800, 0xe401,
                0xa001, 0x6c00, 0x7800, 0xb401, 0x5000, 0x9c01, 0x8801, 0x4400};
        int crc = 0;
        for (byte value : bytes) {
            crc = (crc >>> 4) ^ table[crc & 15] ^ table[value & 15];
            crc = (crc >>> 4) ^ table[crc & 15] ^ table[(value >>> 4) & 15];
        }
        assertEquals(0, crc);

        int[] global = new int[16];
        int[][] fields = new int[16][];
        int[][] sizes = new int[16][];
        Map<Integer, List<Map<Integer, Long>>> messages = new HashMap<>();
        while (buffer.position() < 12 + dataLength) {
            int header = Byte.toUnsignedInt(buffer.get());
            int local = header & 15;
            if ((header & 0x40) != 0) {
                assertEquals(0, buffer.get()); // Reserved.
                assertEquals(0, buffer.get()); // Little endian.
                global[local] = Short.toUnsignedInt(buffer.getShort());
                int count = Byte.toUnsignedInt(buffer.get());
                fields[local] = new int[count];
                sizes[local] = new int[count];
                for (int i = 0; i < count; i++) {
                    fields[local][i] = Byte.toUnsignedInt(buffer.get());
                    sizes[local][i] = Byte.toUnsignedInt(buffer.get());
                    buffer.get(); // Base type.
                }
            } else {
                Map<Integer, Long> values = new HashMap<>();
                for (int i = 0; i < fields[local].length; i++) {
                    long value = switch (sizes[local][i]) {
                        case 1 -> Byte.toUnsignedLong(buffer.get());
                        case 2 -> Short.toUnsignedLong(buffer.getShort());
                        case 4 -> Integer.toUnsignedLong(buffer.getInt());
                        default -> throw new AssertionError("Unexpected field size");
                    };
                    values.put(fields[local][i], value);
                }
                messages.computeIfAbsent(global[local], key -> new ArrayList<>()).add(values);
            }
        }
        assertEquals(12 + dataLength, buffer.position());
        return messages;
    }
}
