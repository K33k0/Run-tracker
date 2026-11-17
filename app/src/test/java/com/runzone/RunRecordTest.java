package com.runzone;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RunRecordTest {
    @Test public void olderRunsRemainReadableWithoutInventedHeartRate()throws Exception{
        RunRecord run=RunRecord.fromJson(new JSONObject("{\"date\":1700000100000,\"duration\":60000,\"meters\":100,\"route\":[{\"lat\":51,\"lon\":0,\"time\":1700000040000,\"bpm\":-1},{\"lat\":51.001,\"lon\":0,\"time\":1700000045000,\"bpm\":140}]}"));
        assertTrue(run.legacy);assertEquals(1700000040000L,run.startTime);assertEquals(100,run.points.get(1).distance,.001);assertEquals(140,run.averageBpm());assertTrue(run.splits().isEmpty());
    }
    @Test public void activeTimeDrivesSplitsAndElapsedIncludesPause()throws Exception{
        RunRecord run=RunRecord.fromJson(new JSONObject("{\"date\":1700000000000,\"startTime\":1700000000000,\"endTime\":1700001000000,\"duration\":900000,\"meters\":2500,\"route\":[{\"lat\":51,\"lon\":0,\"time\":1700000360000,\"activeMs\":360000,\"distanceMeters\":1000},{\"lat\":51.01,\"lon\":0,\"time\":1700000820000,\"activeMs\":720000,\"distanceMeters\":2000,\"track\":1}],\"heartRate\":[{\"time\":1700000001000,\"activeMs\":1000,\"bpm\":140},{\"time\":1700000002000,\"activeMs\":2000,\"bpm\":160}]}"));
        assertFalse(run.legacy);assertEquals(1000000,run.elapsedMs);assertEquals(3,run.splits().size());assertEquals(360000,run.splits().get(0).durationMs);assertEquals(180000,run.splits().get(2).durationMs);assertEquals(150,run.averageBpm());assertEquals(160,run.maxBpm());
    }
    @Test public void recordedHeartRateWithoutGpsIsRetained()throws Exception{
        RunRecord run=RunRecord.fromJson(new JSONObject("{\"date\":1700000000000,\"duration\":10000,\"meters\":0,\"route\":[],\"heartRate\":[{\"time\":1700000000000,\"bpm\":85}]}"));assertEquals(85,run.averageBpm());assertEquals(0,run.points.size());
    }
}
