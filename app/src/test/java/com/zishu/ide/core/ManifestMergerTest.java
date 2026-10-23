package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zishu.ide.core.ondevice.ManifestMerger;
import com.zishu.ide.core.ondevice.ToolException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ManifestMergerTest {
    private static final String APP = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.app\">\n"
            + "    <uses-permission android:name=\"android.permission.INTERNET\" />\n"
            + "    <application android:label=\"A\">\n"
            + "        <activity android:name=\".Main\" android:exported=\"true\" />\n"
            + "    </application>\n</manifest>\n";

    private static final String LIB = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" "
            + "xmlns:tools=\"http://schemas.android.com/tools\" package=\"androidx.startup\">\n"
            + "  <uses-sdk android:minSdkVersion=\"14\" />\n"
            + "  <uses-permission android:name=\"android.permission.INTERNET\" />\n"
            + "  <uses-permission android:name=\"android.permission.WAKE_LOCK\" />\n"
            + "  <application>\n"
            + "    <provider android:name=\"androidx.startup.InitializationProvider\" "
            + "android:authorities=\"${applicationId}.androidx-startup\" android:exported=\"false\" tools:node=\"merge\">\n"
            + "      <meta-data android:name=\"androidx.x.Initializer\" android:value=\"androidx.startup\" />\n"
            + "    </provider>\n"
            + "    <activity android:name=\".LibActivity\" />\n"
            + "  </application>\n</manifest>";

    @Test
    public void mergesPermissionsAndComponentsWithoutDuplicates() throws Exception {
        List<String> notes = new ArrayList<String>();
        String out = ManifestMerger.merge(APP, Arrays.asList(new ManifestMerger.Lib("startup", LIB),
                new ManifestMerger.Lib("again", LIB)), "com.app.release", 21, notes);
        assertEquals(1, count(out, "android.permission.INTERNET"));
        assertTrue(out, out.contains("android.permission.WAKE_LOCK"));
        assertTrue(out, out.contains("android:authorities=\"com.app.release.androidx-startup\""));
        assertEquals(1, count(out, "androidx.startup.InitializationProvider\""));
        assertTrue(out, out.contains("android:name=\"androidx.startup.LibActivity\""));
        assertFalse(out, out.contains("tools:"));
        assertTrue(out, out.indexOf("WAKE_LOCK") < out.indexOf("<application"));
        assertTrue(out, out.indexOf("InitializationProvider") < out.indexOf("</application>"));
        assertTrue(out, out.contains("<activity android:name=\".Main\""));
        assertEquals(1, notes.size());
        // well formed
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(out)));
    }

    @Test
    public void libraryMinSdkAboveAppMinSdkIsAnError() throws Exception {
        String hi = LIB.replace("android:minSdkVersion=\"14\"", "android:minSdkVersion=\"24\"");
        try {
            ManifestMerger.merge(APP, Arrays.asList(new ManifestMerger.Lib("startup", hi)), "com.app", 21, new ArrayList<String>());
            fail();
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("minSdk 24"));
        }
    }

    @Test
    public void selfClosingApplicationAndMissingApplicationAreHandled() throws Exception {
        String selfClosed = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"a.b\">\n"
                + "    <application android:label=\"x\" />\n</manifest>";
        String out = ManifestMerger.merge(selfClosed, Arrays.asList(new ManifestMerger.Lib("l", LIB)), "a.b", 21, new ArrayList<String>());
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(out)));
        assertTrue(out, out.contains("</application>"));
        String none = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"a.b\">\n</manifest>";
        out = ManifestMerger.merge(none, Arrays.asList(new ManifestMerger.Lib("l", LIB)), "a.b", 21, new ArrayList<String>());
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(out)));
        assertTrue(out, out.contains("<application>"));
    }

    @Test
    public void noLibsOrNothingNewLeavesManifestUntouched() throws Exception {
        assertEquals(APP, ManifestMerger.merge(APP, new ArrayList<ManifestMerger.Lib>(), "com.app", 21, new ArrayList<String>()));
        String plain = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"x\"><application/></manifest>";
        assertEquals(APP, ManifestMerger.merge(APP, Arrays.asList(new ManifestMerger.Lib("p", plain)), "com.app", 21, new ArrayList<String>()));
    }

    private static int count(String s, String needle) {
        int c = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            c++;
        }
        return c;
    }
}
