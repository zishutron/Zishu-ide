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
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ClassStubsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * A hand-written class file (unit tests are compiled against android.jar, which has no javax.tools): extends
     * java.awt.Component, implements java.beans.PropertyChangeListener, a method descriptor with Color and Icon, a call of
     * java.awt.Point.&lt;init&gt;, a plain class reference, and a string that only looks like a descriptor.
     */
    private File compileToJar() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bos);
        o.writeInt(0xCAFEBABE);
        o.writeShort(0);
        o.writeShort(52);
        o.writeShort(17);
        String[] utf = {"p/Uses", null, "java/awt/Component", null, "java/beans/PropertyChangeListener", null,
            "(Ljava/awt/Color;)Ljavax/swing/Icon;", "java/awt/Point", null, "<init>", "()V", null, null, "Lnot/a/Type;",
            "java/beans/PropertyChangeEvent", null};
        // 1 Utf8, 2 Class, 3 Utf8, 4 Class, 5 Utf8, 6 Class, 7 Utf8, 8 Utf8, 9 Class, 10 Utf8, 11 Utf8,
        // 12 NameAndType, 13 Methodref, 14 Utf8, 15 Utf8, 16 Class
        for (int i = 0; i < utf.length; i++) {
            int slot = i + 1;
            if (slot == 2 || slot == 4 || slot == 6 || slot == 9 || slot == 16) {
                o.writeByte(7);
                o.writeShort(slot - 1);
            } else if (slot == 12) {
                o.writeByte(12);
                o.writeShort(10);
                o.writeShort(11);
            } else if (slot == 13) {
                o.writeByte(10);
                o.writeShort(9);
                o.writeShort(12);
            } else {
                o.writeByte(1);
                o.writeUTF(utf[i]);
            }
        }
        o.writeShort(0x21);
        o.writeShort(2);
        o.writeShort(4);
        o.writeShort(1);
        o.writeShort(6);
        o.writeShort(0);
        o.writeShort(0);
        o.writeShort(0);
        o.flush();
        File jar = tmp.newFile("uses.jar");
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar));
        zos.putNextEntry(new ZipEntry("p/Uses.class"));
        zos.write(bos.toByteArray());
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
