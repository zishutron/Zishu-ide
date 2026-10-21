package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zishu.ide.core.util.Base64Util;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.util.MiniJson;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class UtilTest {
    @Test
    public void base64MatchesTheJdkImplementation() throws Exception {
        Random rnd = new Random(42);
        for (int len = 0; len < 200; len++) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);
            String mine = Base64Util.encode(data);
            assertEquals(java.util.Base64.getEncoder().encodeToString(data), mine);
            assertTrue(java.util.Arrays.equals(data, Base64Util.decode(mine)));
        }
    }

    @Test
    public void base64DecodeIgnoresWhitespaceAndRejectsGarbage() {
        assertEquals("hello", new String(Base64Util.decode("aGVs\nbG8=\r\n"), IoUtil.UTF8));
        try {
            Base64Util.decode("a$b=");
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("s", "a\"b\\c\n\u0001é");
        m.put("n", 5L);
        m.put("d", 1.5);
        m.put("t", Boolean.TRUE);
        m.put("z", null);
        List<Object> l = new ArrayList<Object>();
        l.add(1L);
        l.add("x");
        m.put("l", l);
        Object back = MiniJson.parse(MiniJson.encode(m));
        assertEquals(m, back);
    }

    @Test
    public void jsonParsesGitHubStyleDocument() throws Exception {
        String doc = "{\"total_count\":1,\"workflow_runs\":[{\"id\":123456789012,\"status\":\"completed\","
                + "\"conclusion\":\"success\",\"display_title\":\"Zishu build ab12\\u00e9\"}],\"x\":-1.5e2}";
        Map<String, Object> m = MiniJson.asMap(MiniJson.parse(doc));
        List<Object> runs = MiniJson.asList(m.get("workflow_runs"));
        Map<String, Object> r = MiniJson.asMap(runs.get(0));
        assertEquals(123456789012L, MiniJson.num(r, "id", 0));
        assertEquals("Zishu build ab12é", MiniJson.str(r, "display_title"));
        assertEquals(-150.0, ((Double) m.get("x")).doubleValue(), 0.0);
        assertNull(MiniJson.str(r, "missing"));
        assertEquals(7, MiniJson.num(r, "missing", 7));
    }

    @Test
    public void jsonRejectsInvalidInput() {
        for (String bad : new String[]{"", "{", "}", "[1,]", "{\"a\"}", "nul", "1.", "-", "\"\\x\"", "[1] 2", "{\"a\":1,}", "\"a\nb\""}) {
            try {
                MiniJson.parse(bad);
                fail("should reject: " + bad);
            } catch (MiniJson.JsonException expected) {
                assertTrue(expected.line >= 1 && expected.column >= 1);
            }
        }
    }

    @Test
    public void jsonRefusesPathologicalNesting() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            sb.append('[');
        }
        try {
            MiniJson.parse(sb.toString());
            fail();
        } catch (MiniJson.JsonException expected) {
            assertTrue(expected.getMessage().contains("nested"));
        }
    }

    @Test
    public void formatSize() {
        assertEquals("512 B", IoUtil.formatSize(512));
        assertEquals("1.0 KB", IoUtil.formatSize(1024));
        assertEquals("12.5 MB", IoUtil.formatSize(13107200));
    }
}
