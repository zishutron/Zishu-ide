package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.ondevice.ClassStubs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public class ClassStubsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File compileToJar() throws Exception {
        final String src = "package p; public class Uses extends java.awt.Component implements java.beans.PropertyChangeListener {"
                + " public javax.swing.Icon icon(java.awt.Color c) { return null; }"
                + " public Object make() { return new java.awt.Point(); }"
                + " public void propertyChange(java.beans.PropertyChangeEvent e) {}"
                + " public String s() { return \"Lnot/a/Type;\"; } }";
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        File out = tmp.newFolder("classes");
        StandardJavaFileManager fm = c.getStandardFileManager(null, null, null);
        JavaFileObject f = new SimpleJavaFileObject(URI.create("string:///p/Uses.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return src;
            }
        };
        assertTrue(c.getTask(null, fm, null, Arrays.asList("-d", out.getPath(), "--release", "8"), null, Arrays.asList(f)).call());
        File jar = tmp.newFile("uses.jar");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar));
        zos.putNextEntry(new ZipEntry("p/Uses.class"));
        zos.write(java.nio.file.Files.readAllBytes(new File(out, "p/Uses.class").toPath()));
        zos.closeEntry();
        zos.close();
        return jar;
    }

    @Test
    public void findsJdkClassesInSignaturesAndKindsFromUsage() throws Exception {
        Map<String, Boolean> stubs = ClassStubs.scan(Arrays.asList(compileToJar()), null);
        assertEquals(stubs.toString(), Boolean.TRUE, stubs.get("javax/swing/Icon"));          // only in a descriptor
        assertEquals(stubs.toString(), Boolean.TRUE, stubs.get("java/beans/PropertyChangeListener")); // implemented
        assertEquals(stubs.toString(), Boolean.FALSE, stubs.get("java/awt/Component"));       // extended
        assertEquals(stubs.toString(), Boolean.FALSE, stubs.get("java/awt/Point"));           // constructed
        assertTrue(stubs.toString(), stubs.containsKey("java/awt/Color"));
        assertTrue(stubs.toString(), stubs.containsKey("java/beans/PropertyChangeEvent"));
        assertFalse("a string that only looks like a descriptor is not a class", stubs.containsKey("not/a/Type"));
        assertFalse("classes of the jar itself are never stubbed", stubs.containsKey("p/Uses"));
    }

    @Test
    public void classesPresentInAndroidJarAreSkipped() throws Exception {
        File android = tmp.newFile("android.jar");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(android));
        zos.putNextEntry(new ZipEntry("java/beans/PropertyChangeListener.class"));
        zos.write(1);
        zos.closeEntry();
        zos.close();
        Map<String, Boolean> stubs = ClassStubs.scan(Arrays.asList(compileToJar()), android);
        assertFalse(stubs.containsKey("java/beans/PropertyChangeListener"));
        assertTrue(stubs.containsKey("javax/swing/Icon"));
    }

    @Test
    public void generatedStubsAreLoadableClassFiles() throws Exception {
        final byte[] iface = ClassStubs.stubClass("zz/Face", true);
        final byte[] cls = ClassStubs.stubClass("zz/Thing", false);
        ClassLoader l = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("zz.Face")) {
                    return defineClass(name, iface, 0, iface.length);
                }
                if (name.equals("zz.Thing")) {
                    return defineClass(name, cls, 0, cls.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        assertTrue(l.loadClass("zz.Face").isInterface());
        Class<?> t = l.loadClass("zz.Thing");
        assertFalse(t.isInterface());
        assertFalse(java.lang.reflect.Modifier.isFinal(t.getModifiers()));
        assertEquals(Object.class, t.getSuperclass());
    }
}
