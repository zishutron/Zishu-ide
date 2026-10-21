package com.zishu.ide.ondevice;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.zishu.ide.core.util.IoUtil;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The key that signs APKs built on this phone. It is generated on first use, unique to this install (nothing
 * is embedded in the app), and its private part is stored encrypted with an AES key held by the Android
 * Keystore. No password exists. Uninstalling Zishu IDE deletes it.
 */
final class DebugKey {
    private static final String ALIAS = "zishu_debug_signing_wrap_key";
    private static final String KEY_FILE = "debug-key.bin";
    private static final String CERT_FILE = "debug-cert.der";

    final PrivateKey privateKey;
    final X509Certificate certificate;

    private DebugKey(PrivateKey k, X509Certificate c) {
        privateKey = k;
        certificate = c;
    }

    static synchronized DebugKey loadOrCreate(Context ctx) throws GeneralSecurityException, IOException {
        if (Build.VERSION.SDK_INT < 23) {
            throw new GeneralSecurityException("Secure key storage needs Android 6 or newer.");
        }
        File dir = new File(ctx.getFilesDir(), "signing");
        File keyFile = new File(dir, KEY_FILE);
        File certFile = new File(dir, CERT_FILE);
        if (keyFile.isFile() && certFile.isFile()) {
            try {
                return read(keyFile, certFile);
            } catch (GeneralSecurityException e) {
                // Wrapping key was lost (e.g. lock screen change). Make a fresh key below.
                keyFile.delete();
                certFile.delete();
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X500Name name = new X500Name("CN=Zishu IDE Debug, O=Zishu IDE");
        Date from = new Date(System.currentTimeMillis() - 24L * 3600 * 1000);
        Date to = new Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000);
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, new BigInteger(64, new java.security.SecureRandom()),
                from, to, name, kp.getPublic());
        ContentSigner signer;
        try {
            signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        } catch (org.bouncycastle.operator.OperatorCreationException e) {
            throw new GeneralSecurityException("Cannot create the certificate signer: " + e.getMessage(), e);
        }
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(signer));

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, wrapKey());
        byte[] ct = c.doFinal(kp.getPrivate().getEncoded());
        byte[] iv = c.getIV();
        byte[] blob = new byte[1 + iv.length + ct.length];
        blob[0] = (byte) iv.length;
        System.arraycopy(iv, 0, blob, 1, iv.length);
        System.arraycopy(ct, 0, blob, 1 + iv.length, ct.length);
        IoUtil.writeAtomic(keyFile, blob);
        IoUtil.writeAtomic(certFile, cert.getEncoded());
        return new DebugKey(kp.getPrivate(), cert);
    }

    private static DebugKey read(File keyFile, File certFile) throws GeneralSecurityException, IOException {
        byte[] blob = IoUtil.readFile(keyFile, 1024 * 1024);
        int ivLen = blob[0] & 0xff;
        byte[] iv = new byte[ivLen];
        System.arraycopy(blob, 1, iv, 0, ivLen);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, iv));
        byte[] pkcs8 = c.doFinal(blob, 1 + ivLen, blob.length - 1 - ivLen);
        PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(IoUtil.readFile(certFile, 1024 * 1024)));
        return new DebugKey(pk, cert);
    }

    private static SecretKey wrapKey() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }
}
