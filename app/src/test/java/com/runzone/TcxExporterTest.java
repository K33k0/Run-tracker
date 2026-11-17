package com.runzone;
import org.junit.Test;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class TcxExporterTest {
    private static final String NS="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2";
    private RunRecord run(){return new RunRecord(1700000000000L,1700000100000L,80000,200,120,150,false,Arrays.asList(new RunRecord.Point(51,-.1,1700000001000L,1000,0,140,0,50d),new RunRecord.Point(51.001,-.1,1700000090000L,70000,200,160,1,52d)),Arrays.asList(new RunRecord.Pulse(1700000001000L,1000,140,0),new RunRecord.Pulse(1700000091000L,71000,160,1)));}
    @Test public void utcNamespacesDistanceAndPauseTracksSurviveExport()throws Exception{
        Locale before=Locale.getDefault();try{Locale.setDefault(Locale.GERMANY);StringWriter writer=new StringWriter();TcxExporter.write(run(),writer);String xml=writer.toString();DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);Document doc=f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(2,doc.getElementsByTagNameNS(NS,"Track").getLength());assertEquals(3,doc.getElementsByTagNameNS(NS,"Trackpoint").getLength());assertEquals("80.000",doc.getElementsByTagNameNS(NS,"TotalTimeSeconds").item(0).getTextContent());assertEquals("51.0000000",doc.getElementsByTagNameNS(NS,"LatitudeDegrees").item(0).getTextContent());assertTrue(doc.getElementsByTagNameNS(NS,"Id").item(0).getTextContent().endsWith("Z"));assertEquals(3,doc.getElementsByTagNameNS(NS,"HeartRateBpm").getLength());
        }finally{Locale.setDefault(before);}
    }
    @Test public void noGpsDoesNotProduceFabricatedCoordinates()throws Exception{
        RunRecord run=new RunRecord(1700000000000L,1700000010000L,10000,0,120,150,false,Collections.emptyList(),Collections.singletonList(new RunRecord.Pulse(1700000001000L,1000,90,0)));StringWriter writer=new StringWriter();TcxExporter.write(run,writer);assertFalse(writer.toString().contains("<Position>"));assertTrue(writer.toString().contains("<Value>90</Value>"));
    }
    @Test public void exportFixture()throws Exception{
        File dir=new File("build/export-fixtures");assertTrue(dir.isDirectory()||dir.mkdirs());try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(dir,"paused-run.tcx")),java.nio.charset.StandardCharsets.UTF_8)){TcxExporter.write(run(),writer);}
    }
}
