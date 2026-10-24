package com.zishu.ide.core.ondevice;

import com.zishu.ide.core.maven.Aar;
import com.zishu.ide.core.maven.Coordinate;
import com.zishu.ide.core.maven.MavenDependencies;
import com.zishu.ide.core.maven.Resolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The resources the first-run setup downloads so that the first AndroidX or Kotlin build does not have to: a starter set
 * of AndroidX libraries and the Kotlin compiler. "Ready" is always decided by looking at what is really on the phone.
 */
public final class ResourcePacks {
    /** The AndroidX starter set (their dependencies come with them). */
    public static final String[] ANDROIDX = {
        "androidx.appcompat:appcompat:1.7.0",
        "androidx.core:core-ktx:1.13.1",
        "androidx.constraintlayout:constraintlayout:2.1.4",
        "com.google.android.material:material:1.12.0",
    };

    private ResourcePacks() {
    }

    public static List<String> androidxNames() {
        List<String> l = new ArrayList<String>();
        for (String c : ANDROIDX) {
            l.add(c);
        }
        return l;
    }

    private static List<Resolver.Root> androidxRoots() {
        List<Resolver.Root> roots = new ArrayList<Resolver.Root>();
        for (String c : ANDROIDX) {
            roots.add(new Resolver.Root(Coordinate.parse(c), true, true, null));
        }
        return roots;
    }

    /** True when every AndroidX starter library (and what it needs) is already stored on the phone. Never uses the network. */
    public static boolean androidxReady(MavenDependencies deps) {
        try {
            Resolver.Result r = deps.offline().resolver().resolve(androidxRoots(), new HashMap<String, String>());
            return !r.artifacts.isEmpty();
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Result of {@link #downloadAndroidx}. */
    public static final class Done {
        public int libraries;
        public int aars;
        public int jars;
    }

    /** Downloads (what is missing of) the AndroidX starter set and unpacks the AARs. Progress goes to whatever listener the cache has. */
    public static Done downloadAndroidx(MavenDependencies deps) throws IOException {
        Resolver.Result r;
        try {
            r = deps.resolver().resolve(androidxRoots(), new HashMap<String, String>());
        } catch (java.net.UnknownHostException e) {
            throw new IOException("Could not reach the library servers (" + e.getMessage() + "). Check the internet connection and run it again.");
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("The download timed out. Check the internet connection and run it again.");
        }
        Done d = new Done();
        for (Resolver.Artifact a : r.artifacts) {
            if (a.file == null) {
                continue;
            }
            d.libraries++;
            if (a.aar) {
                d.aars++;
                Aar.open(a.file, deps.unpackDir(a));
            } else {
                d.jars++;
            }
        }
        return d;
    }
}
