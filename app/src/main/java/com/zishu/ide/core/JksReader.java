package com.zishu.ide.core.signing;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Java KeyStore (JKS) files. Android has no JKS provider, but JKS is what Android Studio and keytool
 * produce by default, so people arrive with such files. Format and key protection are the documented
 * Sun JKS ones: integrity digest SHA-1(password as UTF-16BE + "Mighty Aphrodite" + file), keys encrypted
 * with a SHA-1 keystream (salt 20 bytes, check 20 bytes).
 */
public final class JksReader {
    private static final int MAGIC = 0xFEEDFEED;

    public static final class Entry {
        public final String alias;
        public final boolean isKey;
        public final X509Certificate[] chain;
        final byte[] encryptedKey;

        Entry(String alias, boolean isKey, X509Certificate[] chain, byte[] encryptedKey) {
            this.alias = alias;
            this.isKey = isKey;
            this.chain = chain;
            this.encryptedKey = encryptedKey;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

    private JksReader() {
    }

    public static boolean looksLikeJks(byte[] head) {
        return head.length >= 4 && (((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8)
                | (head[3] & 0xff)) == MAGIC;
    }

    /** @throws IOException when the file is damaged or the password is wrong (the integrity check fails) */
    public static JksReader load(byte[] data, char[] storePassword) throws IOException {
        if (data.length < 4 + 4 + 4 + 20 || !looksLikeJks(data)) {
            throw new IOException("This is not a JKS keystore.");
        }
        byte[] body = Arrays.copyOf(data, data.length - 20);
        byte[] stored = Arrays.copyOfRange(data, data.length - 20, data.length);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(passwordBytes(storePassword));
            md.update("Mighty Aphrodite".getBytes("UTF-8"));
            md.update(body);
            if (!MessageDigest.isEqual(md.digest(), stored)) {
                throw new IOException("Wrong keystore password, or the file is damaged.");
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("SHA-1 is not available: " + e.getMessage());
        }
        JksReader r = new JksReader();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        in.readInt();
        int version = in.readInt();
        if (version != 1 && version != 2) {
            throw new IOException("Unsupported JKS version " + version);
        }
        int count = in.readInt();
        if (count < 0 || count > 10000) {
            throw new IOException("Damaged keystore.");
        }
        for (int i = 0; i < count; i++) {
            int tag = in.readInt();
            String alias = in.readUTF();
            in.readLong();
            if (tag == 1) {
                byte[] enc = readBytes(in, body.length);
                int n = in.readInt();
                if (n < 0 || n > 100) {
                    throw new IOException("Damaged keystore.");
                }
                List<X509Certificate> chain = new ArrayList<X509Certificate>();
                for (int c = 0; c < n; c++) {
                    chain.add(readCert(in, version, body.length));
                }
                r.entries.put(alias, new Entry(alias, true, chain.toArray(new X509Certificate[0]), enc));
            } else if (tag == 2) {
                r.entries.put(alias, new Entry(alias, false, new X509Certificate[]{readCert(in, version, body.length)}, null));
            } else {
                throw new IOException("Damaged keystore (unknown entry type).");
            }
        }
        return r;
    }

    public List<Entry> entries() {
        return new ArrayList<Entry>(entries.values());
    }

    public Entry get(String alias) {
        return entries.get(alias);
    }

    /** Decrypts a private key entry. Throws UnrecoverableKeyException for a wrong key password. */
    public PrivateKey getKey(String alias, char[] keyPassword) throws GeneralSecurityException {
        Entry e = entries.get(alias);
        if (e == null || !e.isKey) {
            throw new GeneralSecurityException("No private key with alias \"" + alias + "\"");
        }
        byte[] pkcs8 = decrypt(e.encryptedKey, keyPassword);
        for (String alg : new String[]{"RSA", "EC", "DSA"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            } catch (GeneralSecurityException next) {
                // try the next algorithm
            }
        }
        throw new GeneralSecurityException("The private key uses an unsupported algorithm.");
    }

    // ---------------------------------------------------------------- internals

    private static byte[] decrypt(byte[] encryptedPrivateKeyInfo, char[] password) throws GeneralSecurityException {
        byte[] data = octetStringOfEncryptedInfo(encryptedPrivateKeyInfo);
        if (data.length < 40) {
            throw new GeneralSecurityException("Damaged key entry.");
        }
        byte[] salt = Arrays.copyOfRange(data, 0, 20);
        int len = data.length - 40;
        byte[] enc = Arrays.copyOfRange(data, 20, 20 + len);
        byte[] check = Arrays.copyOfRange(data, 20 + len, data.length);
        byte[] pw = passwordBytes(password);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] plain = new byte[len];
        byte[] digest = salt;
        int pos = 0;
        while (pos < len) {
            md.update(pw);
            md.update(digest);
            digest = md.digest();
            int n = Math.min(20, len - pos);
            for (int i = 0; i < n; i++) {
                plain[pos + i] = (byte) (enc[pos + i] ^ digest[i]);
            }
            pos += n;
        }
        md.update(pw);
        md.update(plain);
        byte[] expect = md.digest();
        Arrays.fill(pw, (byte) 0);
        if (!MessageDigest.isEqual(expect, check)) {
            throw new UnrecoverableKeyException("Wrong key password.");
        }
        return plain;
    }

    /** EncryptedPrivateKeyInfo ::= SEQUENCE { AlgorithmIdentifier SEQUENCE, encryptedData OCTET STRING }. */
    private static byte[] octetStringOfEncryptedInfo(byte[] der) throws GeneralSecurityException {
        int[] p = {0};
        int outerLen = header(der, p, 0x30);
        int end = p[0] + outerLen;
        int algLen = header(der, p, 0x30);
        p[0] += algLen;
        int octLen = header(der, p, 0x04);
        if (p[0] + octLen > end || end > der.length) {
            throw new GeneralSecurityException("Damaged key entry.");
        }
        return Arrays.copyOfRange(der, p[0], p[0] + octLen);
    }

    private static int header(byte[] d, int[] p, int tag) throws GeneralSecurityException {
        if (p[0] + 2 > d.length || (d[p[0]] & 0xff) != tag) {
            throw new GeneralSecurityException("Damaged key entry.");
        }
        p[0]++;
        int b = d[p[0]++] & 0xff;
        if (b < 0x80) {
            return b;
        }
        int n = b & 0x7f;
        if (n < 1 || n > 3 || p[0] + n > d.length) {
            throw new GeneralSecurityException("Damaged key entry.");
        }
        int len = 0;
        for (int i = 0; i < n; i++) {
            len = (len << 8) | (d[p[0]++] & 0xff);
        }
        return len;
    }

    private static byte[] passwordBytes(char[] pw) {
        byte[] b = new byte[pw.length * 2];
        for (int i = 0; i < pw.length; i++) {
            b[i * 2] = (byte) (pw[i] >> 8);
            b[i * 2 + 1] = (byte) pw[i];
        }
        return b;
    }

    private static byte[] readBytes(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) {
            throw new IOException("Damaged keystore.");
        }
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    private static X509Certificate readCert(DataInputStream in, int version, int max) throws IOException {
        if (version == 2) {
            String type = in.readUTF();
            if (!"X.509".equals(type)) {
                throw new IOException("Unsupported certificate type " + type);
            }
        }
        byte[] der = readBytes(in, max);
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (GeneralSecurityException e) {
            throw new IOException("Unreadable certificate in keystore: " + e.getMessage());
        }
    }
}
