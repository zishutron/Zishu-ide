package com.zishu.ide.signing;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.zishu.ide.core.ondevice.ApkPackager;
import com.zishu.ide.core.signing.KeyStores;

import java.io.File;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** Signs and verifies APKs with Google's apksig library. Everything reported comes from the verifier, not from assumptions. */
public final class ApkSigning {
    private ApkSigning() {
    }

    public static final class Report {
        public boolean verified;
        public boolean v1;
        public boolean v2;
        public boolean v3;
        public String certSubject = "";
        public String sha256 = "";
        public final List<String> errors = new ArrayList<String>();
        public final List<String> warnings = new ArrayList<String>();
    }

    /**
     * Re-aligns the input (so zip alignment is correct even for APKs from elsewhere) and signs it with
     * v1 + v2 signatures. The input is not modified.
     */
    public static void sign(File in, File out, PrivateKey key, X509Certificate[] chain) throws Exception {
        File aligned = new File(out.getAbsolutePath() + ".aligned.tmp");
        try {
            ApkPackager.assemble(in, new HashMap<String, File>(), aligned);
            ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("zishu-release", key, Arrays.asList(chain)).build();
            File tmpOut = new File(out.getAbsolutePath() + ".signing.tmp");
            new ApkSigner.Builder(Collections.singletonList(sc))
                    .setInputApk(aligned)
                    .setOutputApk(tmpOut)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(false)
                    .setCreatedBy("Zishu IDE")
                    .build()
                    .sign();
            if (out.exists() && !out.delete()) {
                throw new IOException("Cannot replace " + out.getName());
            }
            if (!tmpOut.renameTo(out)) {
                throw new IOException("Cannot write " + out.getName());
            }
        } finally {
            aligned.delete();
            new File(out.getAbsolutePath() + ".signing.tmp").delete();
        }
    }

    public static Report verify(File apk) throws Exception {
        ApkVerifier.Result r = new ApkVerifier.Builder(apk).build().verify();
        Report rep = new Report();
        rep.verified = r.isVerified();
        rep.v1 = r.isVerifiedUsingV1Scheme();
        rep.v2 = r.isVerifiedUsingV2Scheme();
        rep.v3 = r.isVerifiedUsingV3Scheme();
        List<X509Certificate> certs = r.getSignerCertificates();
        if (certs != null && !certs.isEmpty()) {
            rep.certSubject = certs.get(0).getSubjectX500Principal().getName();
            rep.sha256 = KeyStores.sha256(certs.get(0));
        }
        for (ApkVerifier.IssueWithParams e : r.getErrors()) {
            rep.errors.add(e.toString());
        }
        for (ApkVerifier.IssueWithParams w : r.getWarnings()) {
            rep.warnings.add(w.toString());
        }
        return rep;
    }
}
