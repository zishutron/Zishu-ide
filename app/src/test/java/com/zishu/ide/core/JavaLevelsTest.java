package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zishu.ide.core.ondevice.GradleValues;
import com.zishu.ide.core.ondevice.JavaLevels;

import org.junit.Test;

public class JavaLevelsTest {
    private static int level(String gradle) {
        return GradleValues.parse(gradle).javaLevel;
    }

    @Test
    public void readsCommonSourceCompatibilityForms() {
        assertEquals(8, level("compileOptions {\n sourceCompatibility JavaVersion.VERSION_1_8\n targetCompatibility JavaVersion.VERSION_1_8\n}"));
        assertEquals(17, level("compileOptions { sourceCompatibility = JavaVersion.VERSION_17 }"));
        assertEquals(11, level("sourceCompatibility = JavaVersion.VERSION_11"));
        assertEquals(7, level("sourceCompatibility '1.7'"));
        assertEquals(21, level("sourceCompatibility = \"21\""));
        assertEquals(11, level("sourceCompatibility JavaVersion.toVersion(\"11\")"));
        assertEquals(17, level("kotlin { jvmToolchain(17) }"));
        assertEquals(21, level("java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }"));
        assertEquals(0, level("android { namespace 'a.b' }"));
        assertEquals(0, level("// sourceCompatibility JavaVersion.VERSION_17\nandroid {}"));
    }

    @Test
    public void optionsMatchEcjSpelling() {
        assertEquals("1.5", JavaLevels.option(5));
        assertEquals("1.8", JavaLevels.option(8));
        assertEquals("9", JavaLevels.option(9));
        assertEquals("17", JavaLevels.option(17));
        assertEquals("21", JavaLevels.option(21));
        try {
            JavaLevels.option(4);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            JavaLevels.option(22);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void recognisesCompilerRejection() {
        assertTrue(JavaLevels.looksLikeLevelRejection("invalid source release: 21"));
        assertFalse(JavaLevels.looksLikeLevelRejection("Foo.java:3: error: cannot find symbol"));
        assertTrue(JavaLevels.unsupportedHint(21).contains("not 21"));
        assertTrue(JavaLevels.looksLikeLevelRejection("source level should be in '1.1'...'1.8','9'...'15': 17"));
        assertTrue(JavaLevels.usesBootclasspath(8));
        assertFalse(JavaLevels.usesBootclasspath(11));
        assertTrue(JavaLevels.compilerAccepts(15));
        assertFalse(JavaLevels.compilerAccepts(17));
    }
}
