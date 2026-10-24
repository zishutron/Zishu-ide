package com.zishu.ide.core.ondevice;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the JDK classes a jar refers to that Android does not have (javax.swing.Icon, java.awt.Color ...) and writes
 * empty stand-in classes for them. The Kotlin compiler is built for a desktop JVM; when it inspects its own classes by
 * reflection Android must be able to load every type in a method signature, even if that type is never used.
 * A stand-in is an empty interface or class, nothing more: code that really calls into it still fails, and says so.
 */
public final class ClassStubs {
    private static final String[] JDK_PREFIXES = {"java/", "javax/", "sun/", "com/sun/", "jdk/"};

    private ClassStubs() {
    }

    /** @return internal class name (java/awt/Color) to true when it must be an interface; sorted by name */
    public static Map<String, Boolean> scan(List<File> jars, File androidJar) throws IOException {
        Set<String> defined = new HashSet<String>();
        Set<String> referenced = new HashSet<String>();
        Set<String> asClass = new HashSet<String>();
        Set<String> asInterface = new HashSet<String>();
        for (File jar : jars) {
            ZipFile z = new ZipFile(jar);
            try {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || !n.endsWith(".class") || n.startsWith("META-INF/") || n.equals("module-info.class")) {
                        continue;
                    }
                    InputStream in = z.getInputStream(e);
                    try {
                        parse(new DataInputStream(new java.io.BufferedInputStream(in, 16 * 1024)), defined, referenced, asClass, asInterface);
                    } catch (IOException bad) {
                        // a class file this reader does not understand is skipped; it only means fewer stand-ins
                    } finally {
                        in.close();
                    }
                }
            } finally {
                z.close();
            }
        }
        ZipFile android = androidJar == null ? null : new ZipFile(androidJar);
        try {
            Map<String, Boolean> out = new TreeMap<String, Boolean>();
            for (String name : referenced) {
                if (defined.contains(name) || !isJdk(name) || android != null && android.getEntry(name + ".class") != null) {
                    continue;
                }
                boolean isInterface = asInterface.contains(name) || !asClass.contains(name);
                out.put(name, isInterface);
            }
            return out;
        } finally {
            if (android != null) {
                android.close();
            }
        }
    }

    private static boolean isJdk(String name) {
        for (String p : JDK_PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static void parse(DataInputStream in, Set<String> defined, Set<String> referenced, Set<String> asClass,
                              Set<String> asInterface) throws IOException {
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf = new String[count];
        int[] classIdx = new int[count];
        int[] refKind = new int[count];
        int[] refClass = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    utf[i] = in.readUTF();
                    break;
                case 3:
                case 4:
                    in.readInt();
                    break;
                case 5:
                case 6:
                    in.readLong();
                    i++;
                    break;
                case 7:
                    classIdx[i] = in.readUnsignedShort();
                    break;
                case 8:
                case 16:
                case 19:
                case 20:
                    in.readUnsignedShort();
                    break;
                case 9:
                case 10:
                case 11:
                    refKind[i] = tag;
                    refClass[i] = in.readUnsignedShort();
                    in.readUnsignedShort();
                    break;
                case 12:
                case 17:
                case 18:
                    in.readInt();
                    break;
                case 15:
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                default:
                    throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        int thisIdx = in.readUnsignedShort();
        int superIdx = in.readUnsignedShort();
        int ifaces = in.readUnsignedShort();
        int[] ifaceIdx = new int[ifaces];
        for (int i = 0; i < ifaces; i++) {
            ifaceIdx[i] = in.readUnsignedShort();
        }
        String self = className(utf, classIdx, thisIdx);
        if (self != null) {
            defined.add(self);
        }
        String sup = className(utf, classIdx, superIdx);
        if (sup != null) {
            asClass.add(sup);
        }
        for (int idx : ifaceIdx) {
            String n = className(utf, classIdx, idx);
            if (n != null) {
                asInterface.add(n);
            }
        }
        for (int i = 1; i < count; i++) {
            if (classIdx[i] != 0) {
                String n = stripArray(utf[classIdx[i]]);
                if (n != null) {
                    referenced.add(n);
                }
            }
            if (refKind[i] != 0) {
                String n = className(utf, classIdx, refClass[i]);
                if (n != null) {
                    (refKind[i] == 11 ? asInterface : asClass).add(n);
                }
            }
            String u = utf[i];
            if (u != null && u.length() > 2 && u.length() < 4000 && u.indexOf(';') > 0 && u.indexOf('L') >= 0) {
                addDescriptorTypes(u, referenced);
            }
        }
    }

    private static String className(String[] utf, int[] classIdx, int idx) {
        if (idx <= 0 || idx >= classIdx.length || classIdx[idx] == 0) {
            return null;
        }
        return stripArray(utf[classIdx[idx]]);
    }

    private static String stripArray(String n) {
        if (n == null) {
            return null;
        }
        int i = 0;
        while (i < n.length() && n.charAt(i) == '[') {
            i++;
        }
        if (i == 0) {
            return n;
        }
        if (i < n.length() && n.charAt(i) == 'L' && n.endsWith(";")) {
            return n.substring(i + 1, n.length() - 1);
        }
        return null;
    }

    /** A class name inside a descriptor or generic signature: L, a package path, then ; (end) or < (type arguments) or . (inner class). */
    private static final java.util.regex.Pattern TYPE =
            java.util.regex.Pattern.compile("L([A-Za-z0-9_$]+(?:/[A-Za-z0-9_$]+)+)[;<.]");

    /**
     * Class names inside a field or method descriptor such as (Ljava/awt/Color;I)Ljavax/swing/Icon; or a generic
     * signature such as Ljava/util/List&lt;Ljava/awt/Color;&gt;; . Only plain names (letters, digits, _ and $) are accepted.
     */
    static void addDescriptorTypes(String d, Set<String> out) {
        java.util.regex.Matcher m = TYPE.matcher(d);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    /** Smallest valid class file: public, not final, extends Object (or an empty interface). */
    public static byte[] stubClass(String internalName, boolean isInterface) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bos);
        o.writeInt(0xCAFEBABE);
        o.writeShort(0);
        o.writeShort(52);
        o.writeShort(5);
        o.writeByte(1);
        o.writeUTF(internalName);
        o.writeByte(7);
        o.writeShort(1);
        o.writeByte(1);
        o.writeUTF("java/lang/Object");
        o.writeByte(7);
        o.writeShort(3);
        o.writeShort(isInterface ? 0x0601 : 0x0021);
        o.writeShort(2);
        o.writeShort(4);
        o.writeShort(0);
        o.writeShort(0);
        o.writeShort(0);
        o.writeShort(0);
        o.flush();
        return bos.toByteArray();
    }

    /** For the on-disk marker: the sorted list as text. */
    public static String describe(Map<String, Boolean> stubs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : stubs.entrySet()) {
            sb.append(e.getValue() ? "I " : "C ").append(e.getKey()).append('\n');
        }
        return sb.toString();
    }
}
