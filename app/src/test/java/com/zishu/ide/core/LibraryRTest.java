package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.ondevice.LibraryR;

import org.junit.Test;

public class LibraryRTest {
    private static final String LIB = "int attr foo 0x7f010001\nint string hello 0x7f020001\n"
            + "int[] styleable Bar { 0x7f010001, 0x7f010002 }\nint styleable Bar_foo 0\nint styleable Bar_baz 1\n";
    private static final String FINAL = "int attr foo 0x7f040010\nint attr baz 0x7f040011\nint string hello 0x7f0e0042\nint string other 0x7f0e0043\n"
            + "int[] styleable Bar { 0x7f040010, 0x7f040011 }\nint styleable Bar_foo 0\nint styleable Bar_baz 1\n";

    @Test
    public void parseReadsIntsAndArrays() {
        LibraryR.Table t = LibraryR.parse(LIB);
        assertEquals(5, t.size());
        assertEquals("0x7f010001", t.get("attr", "foo").value);
        assertTrue(t.get("styleable", "Bar").array);
        assertEquals("0x7f010001, 0x7f010002", t.get("styleable", "Bar").value);
        assertFalse(t.get("styleable", "Bar_foo").array);
        assertEquals(null, t.get("string", "nope"));
    }

    @Test
    public void generateUsesFinalIdsAndOnlyOwnSymbols() {
        String src = LibraryR.generate("x.lib", LibraryR.parse(LIB), LibraryR.parse(FINAL));
        assertTrue(src, src.startsWith("package x.lib;"));
        assertTrue(src, src.contains("public static final int foo = 0x7f040010;"));
        assertTrue(src, src.contains("public static final int hello = 0x7f0e0042;"));
        assertTrue(src, src.contains("public static final int[] Bar = { 0x7f040010, 0x7f040011 };"));
        assertFalse("symbols of other libraries are not copied", src.contains("other"));
        assertTrue(src, src.contains("public static final int Bar_baz = 1;"));
    }

    @Test
    public void unknownSymbolKeepsLibraryValueAndMergeAddsMissing() {
        String src = LibraryR.generate("x.lib", LibraryR.parse(LIB), LibraryR.parse(""));
        assertTrue(src, src.contains("foo = 0x7f010001;"));
        LibraryR.Table a = LibraryR.parse("int string a 0x1\n");
        LibraryR.merge(a, LibraryR.parse("int string a 0x9\nint string b 0x2\n"));
        assertEquals(2, a.size());
        assertEquals("0x1", a.get("string", "a").value);
        assertNotNull(a.get("string", "b"));
    }
}
