package com.zishu.ide.core.signing;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Keystore files of a project (<project>/keystore/): listing, opening PKCS12 and JKS files, creating a new
 * PKCS12 keystore with a self-signed RSA 2048 key. Passwords are char arrays that callers wipe; nothing here
 * stores or logs them.
 */
public final class KeyStores {
    public static final String DIR = "keystore";
    private static final long MAX_FILE = 4L * 1024 * 1024;

    private KeyStores() {
    }

    public static File dir(File project) {
        return new File(project, DIR);
    }

    /** Keystore-looking files in the project's keystore folder, sorted by name. */
    public static List<File> list(File project) {
        List<File> out = new ArrayList<File>();
        File[] kids = dir(project).listFiles();
        if (kids != null) {
            for (File f : kids) {
                String n = f.getName().toLowerCase(Locale.US);
                if (f.isFile() && (n.endsWith(".jks") || n.endsWith(".p12") || n.endsWith(".pfx") || n.endsWith(".keystore")
                        || n.endsWith(".bks"))) {
                    out.add(f);
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** A safe keystore file name: letters, digits, dot, dash, underscore; ends with .p12 unless it has a known extension. */
    public static String fileName(String requested) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < requested.length(); i++) {
            char c = requested.charAt(i);
            sb.append(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        String n = sb.toString();
        while (n.startsWith(".")) {
            n = n.substring(1);
        }
        if (n.length() == 0) {
            n = "release";
        }
        String l = n.toLowerCase(Locale.US);
        if (!(l.endsWith(".p12") || l.endsWith(".pfx") || l.endsWith(".jks") || l.endsWith(".keystore"))) {
            n = n + ".p12";
        }
        return n;
    }

    /** An opened keystore. */
    public static final class Opened {
        public final List<String> keyAliases = new ArrayList<String>();
        private final KeyStore pkcs12;
        private final JksReader jks;
        public final String format;

        Opened(KeyStore ks) throws GeneralSecurityException {
            pkcs12 = ks;
            jks = null;
            format = "PKCS12";
            for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
                String a = e.nextElement();
                if (ks.isKeyEntry(a)) {
                    keyAliases.add(a);
                }
            }
        }

        Opened(JksReader r) {
            pkcs12 = null;
            jks = r;
            format = "JKS";
            for (JksReader.Entry e : r.entries()) {
                if (e.isKey) {
                    keyAliases.add(e.alias);
                }
            }
        }

        public PrivateKey key(String alias, char[] keyPassword) throws GeneralSecurityException {
            if (jks != null) {
                return jks.getKey(alias, keyPassword);
            }
            java.security.Key k = pkcs12.getKey(alias, keyPassword);
            if (!(k instanceof PrivateKey)) {
                throw new GeneralSecurityException("No private key with alias \"" + alias + "\"");
            }
            return (PrivateKey) k;
        }

        public X509Certificate[] chain(String alias) throws GeneralSecurityException {
            if (jks != null) {
                JksReader.Entry e = jks.get(alias);
                if (e == null) {
                    throw new GeneralSecurityException("No alias \"" + alias + "\"");
                }
                return e.chain;
            }
            Certificate[] c = pkcs12.getCertificateChain(alias);
            if (c == null || c.length == 0) {
                throw new GeneralSecurityException("The entry \"" + alias + "\" has no certificate");
            }
            X509Certificate[] out = new X509Certificate[c.length];
            for (int i = 0; i < c.length; i++) {
                out[i] = (X509Certificate) c[i];
            }
            return out;
        }
    }

    /** @throws IOException with a user-readable reason (wrong password, unsupported format, damaged file) */
    public static Opened open(File file, char[] storePassword) throws IOException {
        byte[] data = readFile(file);
        try {
            if (JksReader.looksLikeJks(data)) {
                return new Opened(JksReader.load(data, storePassword));
            }
            if (data.length >= 4 && (data[0] & 0xff) == 0xCE && (data[1] & 0xff) == 0xCE && (data[2] & 0xff) == 0xCE
                    && (data[3] & 0xff) == 0xCE) {
                throw new IOException("This is a JCEKS keystore, which is not supported. Convert it with keytool: "
                        + "keytool -importkeystore -srckeystore old.jceks -destkeystore new.p12 -deststoretype PKCS12");
            }
            KeyStore ks = KeyStore.getInstance("PKCS12", new BouncyCastleProvider());
            ks.load(new ByteArrayInputStream(data), storePassword);
            return new Opened(ks);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("JCEKS") || m.contains("Wrong keystore") || m.contains("Unsupported")) {
                throw e;
            }
            throw new IOException("Cannot open the keystore: wrong password, or this is not a PKCS12/JKS file.");
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot open the keystore: " + e.getMessage());
        }
    }

    private static byte[] readFile(File f) throws IOException {
        if (f.length() > MAX_FILE) {
            throw new IOException("The keystore file is unexpectedly large.");
        }
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Creates a PKCS12 keystore with one self-signed RSA 2048 key. Returns the file bytes. */
    public static byte[] createPkcs12(String alias, String commonName, String organisation, String country,
                                      int validYears, char[] password) throws GeneralSecurityException, IOException {
        if (password.length < 6) {
            throw new GeneralSecurityException("Use a password of at least 6 characters.");
        }
        if (validYears < 1 || validYears > 100) {
            throw new GeneralSecurityException("Validity must be between 1 and 100 years.");
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate cert = selfSigned(kp, commonName, organisation, country, validYears);
        KeyStore ks = KeyStore.getInstance("PKCS12", new BouncyCastleProvider());
        ks.load(null, null);
        ks.setKeyEntry(alias, kp.getPrivate(), password, new Certificate[]{cert});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, password);
        return out.toByteArray();
    }

    static X509Certificate selfSigned(KeyPair kp, String cn, String org, String country, int years)
            throws GeneralSecurityException, IOException {
        X500NameBuilder nb = new X500NameBuilder(BCStyle.INSTANCE);
        if (cn != null && cn.trim().length() > 0) {
            nb.addRDN(BCStyle.CN, cn.trim());
        }
        if (org != null && org.trim().length() > 0) {
            nb.addRDN(BCStyle.O, org.trim());
        }
        if (country != null && country.trim().length() == 2) {
            nb.addRDN(BCStyle.C, country.trim().toUpperCase(Locale.US));
        }
        X500Name name = nb.build();
        AlgorithmIdentifier sigAlg = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);
        long now = System.currentTimeMillis();
        V3TBSCertificateGenerator g = new V3TBSCertificateGenerator();
        g.setSerialNumber(new ASN1Integer(new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE)));
        g.setSignature(sigAlg);
        g.setIssuer(name);
        g.setStartDate(new Time(new Date(now - 24L * 3600 * 1000)));
        g.setEndDate(new Time(new Date(now + years * 365L * 24 * 3600 * 1000)));
        g.setSubject(name);
        g.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded()));
        TBSCertificate tbs = g.generateTBSCertificate();
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs.getEncoded(ASN1Encoding.DER));
        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(tbs);
        v.add(sigAlg);
        v.add(new DERBitString(s.sign()));
        byte[] der = new DERSequence(v).getEncoded(ASN1Encoding.DER);
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    /** Colon separated upper-case SHA-256 fingerprint. */
    public static String sha256(X509Certificate c) throws GeneralSecurityException {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format(Locale.US, "%02X", d[i] & 0xff));
        }
        return sb.toString();
    }

    public static void wipe(char[] c) {
        if (c != null) {
            java.util.Arrays.fill(c, '\0');
        }
    }
}
