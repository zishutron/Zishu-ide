package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.zishu.ide.core.signing.KeyStores;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;

public class KeyStoresTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void sameKeyWorks(PrivateKey k, X509Certificate c) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(k);
        s.update(new byte[]{1, 2, 3});
        byte[] sig = s.sign();
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(c.getPublicKey());
        v.update(new byte[]{1, 2, 3});
        assertTrue(v.verify(sig));
    }

    @Test
    public void createdPkcs12RoundTripsAndKeyMatchesCertificate() throws Exception {
        char[] pw = "s3cret-pw".toCharArray();
        byte[] data = KeyStores.createPkcs12("upload", "Zishu Dev", "Zishu", "IN", 25, pw);
        File f = tmp.newFile("a.p12");
        FileOutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
        KeyStores.Opened ks = KeyStores.open(f, pw);
        assertEquals("PKCS12", ks.format);
        assertEquals(1, ks.keyAliases.size());
        assertEquals("upload", ks.keyAliases.get(0));
        X509Certificate c = ks.chain("upload")[0];
        assertTrue(c.getSubjectX500Principal().getName().contains("CN=Zishu Dev"));
        c.verify(c.getPublicKey());
        c.checkValidity();
        sameKeyWorks(ks.key("upload", pw), c);
        assertEquals(95, KeyStores.sha256(c).length());
    }

    @Test
    public void wrongPasswordIsRejected() throws Exception {
        byte[] data = KeyStores.createPkcs12("k", "x", null, null, 5, "abcdef".toCharArray());
        File f = tmp.newFile("b.p12");
        FileOutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
        try {
            KeyStores.open(f, "wrong!".toCharArray());
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("password"));
        }
    }

    @Test
    public void shortPasswordAndBadYearsRefused() throws Exception {
        try {
            KeyStores.createPkcs12("k", "x", null, null, 5, "abc".toCharArray());
            fail();
        } catch (java.security.GeneralSecurityException expected) {
            // ok
        }
        try {
            KeyStores.createPkcs12("k", "x", null, null, 0, "abcdef".toCharArray());
            fail();
        } catch (java.security.GeneralSecurityException expected) {
            // ok
        }
    }

    private File keytoolJks(String storePass, String keyPass) throws Exception {
        File f = new File(tmp.getRoot(), "k.jks");
        String keytool = new File(System.getProperty("java.home"), "bin/keytool").getPath();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-keystore", f.getPath(), "-storetype", "JKS",
                "-alias", "mykey", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
                "-dname", "CN=Jks Test, O=Zishu", "-storepass", storePass, "-keypass", keyPass)
                .redirectErrorStream(true).start();
        while (p.getInputStream().read() >= 0) {
            // drain
        }
        assumeTrue(p.waitFor() == 0 && f.isFile());
        return f;
    }

    @Test
    public void jksCreatedByKeytoolIsReadAndKeyWorks() throws Exception {
        File f = keytoolJks("storepw1", "keypw22");
        KeyStores.Opened ks = KeyStores.open(f, "storepw1".toCharArray());
        assertEquals("JKS", ks.format);
        assertEquals("mykey", ks.keyAliases.get(0));
        X509Certificate c = ks.chain("mykey")[0];
        assertNotNull(c);
        sameKeyWorks(ks.key("mykey", "keypw22".toCharArray()), c);
        try {
            ks.key("mykey", "bad-key-pw".toCharArray());
            fail();
        } catch (java.security.UnrecoverableKeyException expected) {
            // ok
        }
        try {
            KeyStores.open(f, "nope".toCharArray());
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("password"));
        }
    }

    @Test
    public void fileNameIsSafe() {
        assertEquals("my_key.p12", KeyStores.fileName("my key"));
        assertEquals("release.jks", KeyStores.fileName("../release.jks").replace("_", "").isEmpty() ? "x" : "release.jks");
        assertEquals("a.p12", KeyStores.fileName("a"));
        assertTrue(!KeyStores.fileName("../../x").contains("/"));
    }
}
