package com.runzone;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReleaseVersionTest {
    @Test public void comparesNumerically() {
        assertTrue(ReleaseVersion.newer("v1.10.0", "1.9.9"));
        assertTrue(ReleaseVersion.newer("v1.2.1", "1.2"));
        assertFalse(ReleaseVersion.newer("v1.2.0", "1.2"));
        assertFalse(ReleaseVersion.newer("v1.1.9", "1.2"));
    }
    @Test public void rejectsUnusableVersions() {
        for (String value : new String[]{"v1.2.3-beta", "1.1000.0", "999999999999999999999.1.1", "2101.0.0", "", "v01.2.0"})
            assertEquals(-1, ReleaseVersion.code(value));
        assertFalse(ReleaseVersion.newer("v2.0.0", "invalid"));
    }
}
