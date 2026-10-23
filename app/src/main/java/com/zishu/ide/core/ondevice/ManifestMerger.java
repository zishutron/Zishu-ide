package com.zishu.ide.core.ondevice;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * A small manifest merger for library manifests: permissions, features and the components (activities,
 * services, providers, receivers, meta-data) that libraries declare are added to the app's manifest unless the
 * app already declares the same name. Placeholders ${applicationId} are filled in. It does not implement the
 * full Android Gradle Plugin merge rules (tools:node, tools:replace ...): those attributes are ignored.
 */
public final class ManifestMerger {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static final Set<String> TOP = new HashSet<String>(Arrays.asList(
            "uses-permission", "uses-permission-sdk-23", "uses-feature", "permission", "queries"));
    private static final Set<String> APP = new HashSet<String>(Arrays.asList(
            "activity", "activity-alias", "service", "receiver", "provider", "meta-data", "uses-library",
            "uses-native-library", "property"));
    private static final Set<String> COMPONENTS = new HashSet<String>(Arrays.asList(
            "activity", "activity-alias", "service", "receiver", "provider"));

    public static final class Lib {
        public final String name;
        public final String manifestXml;

        public Lib(String name, String manifestXml) {
            this.name = name;
            this.manifestXml = manifestXml;
        }
    }

    private ManifestMerger() {
    }

    /** @throws ToolException when a library needs a higher minSdk than the app declares or a manifest is unreadable */
    public static String merge(String appManifest, List<Lib> libs, String applicationId, int appMinSdk,
                               List<String> notes) throws ToolException {
        StringBuilder topAdd = new StringBuilder();
        StringBuilder appAdd = new StringBuilder();
        Set<String> seen = new HashSet<String>();
        for (String tag : TOP) {
            for (String n : existing(appManifest, tag)) {
                seen.add(tag + "|" + n);
            }
        }
        for (String tag : APP) {
            for (String n : existing(appManifest, tag)) {
                seen.add(tag + "|" + n);
            }
        }
        int added = 0;
        for (Lib lib : libs) {
            Document doc = parse(lib);
            Element root = doc.getDocumentElement();
            String pkg = root.getAttribute("package");
            for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) {
                    continue;
                }
                Element e = (Element) n;
                String tag = e.getTagName();
                if ("uses-sdk".equals(tag)) {
                    String min = e.getAttributeNS(ANDROID_NS, "minSdkVersion");
                    if (min.length() > 0) {
                        try {
                            if (Integer.parseInt(min) > appMinSdk) {
                                throw new ToolException("The library " + lib.name + " needs minSdk " + min
                                        + " but the app declares minSdk " + appMinSdk
                                        + ". Raise minSdk in app/build.gradle to " + min + " or use an older version of the library.");
                            }
                        } catch (NumberFormatException ignored) {
                            // a codename such as "S": left for aapt2 to judge
                        }
                    }
                } else if (TOP.contains(tag)) {
                    String key = tag + "|" + key(e);
                    if (seen.add(key)) {
                        write(e, pkg, applicationId, 1, topAdd);
                        added++;
                    }
                } else if ("application".equals(tag)) {
                    for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                        if (c instanceof Element && APP.contains(((Element) c).getTagName())) {
                            Element ce = (Element) c;
                            String name = expandName(ce.getAttributeNS(ANDROID_NS, "name"), pkg, COMPONENTS.contains(ce.getTagName()));
                            String key = ce.getTagName() + "|" + (name.length() > 0 ? name : key(ce));
                            if (seen.add(key)) {
                                write(ce, pkg, applicationId, 2, appAdd);
                                added++;
                            }
                        }
                    }
                }
            }
        }
        if (added == 0) {
            return appManifest;
        }
        notes.add("Merged " + added + " manifest entr" + (added == 1 ? "y" : "ies") + " from libraries.");
        String out = appManifest;
        if (appAdd.length() > 0) {
            out = insertIntoApplication(out, appAdd.toString());
        }
        if (topAdd.length() > 0) {
            out = insertBeforeApplication(out, topAdd.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ insertion

    private static String insertIntoApplication(String xml, String block) throws ToolException {
        Matcher open = Pattern.compile("<application\\b[^>]*?(/?)>", Pattern.DOTALL).matcher(xml);
        if (!open.find()) {
            int end = xml.lastIndexOf("</manifest>");
            if (end < 0) {
                throw new ToolException("The app's AndroidManifest.xml has no closing </manifest>.");
            }
            return xml.substring(0, end) + "    <application>\n" + block + "    </application>\n" + xml.substring(end);
        }
        if (open.group(1).length() > 0) { // <application ... />
            String tag = xml.substring(open.start(), open.end() - 2).trim();
            return xml.substring(0, open.start()) + tag + ">\n" + block + "    </application>" + xml.substring(open.end());
        }
        int close = xml.indexOf("</application>", open.end());
        if (close < 0) {
            throw new ToolException("The app's AndroidManifest.xml has an <application> without </application>.");
        }
        return xml.substring(0, close) + block + "    " + xml.substring(close);
    }

    private static String insertBeforeApplication(String xml, String block) throws ToolException {
        Matcher open = Pattern.compile("<application\\b").matcher(xml);
        int at;
        if (open.find()) {
            at = open.start();
        } else {
            at = xml.lastIndexOf("</manifest>");
            if (at < 0) {
                throw new ToolException("The app's AndroidManifest.xml has no closing </manifest>.");
            }
        }
        return xml.substring(0, at) + block.replaceFirst("^    ", "") + "    " + xml.substring(at).replaceFirst("^\\s+", "");
    }

    // ------------------------------------------------------------------ helpers

    private static Set<String> existing(String xml, String tag) {
        Set<String> out = new HashSet<String>();
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "\\b([^>]*)>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            Matcher a = Pattern.compile("android:name\\s*=\\s*\"([^\"]*)\"").matcher(m.group(1));
            if (a.find()) {
                out.add(a.group(1));
            } else {
                Matcher g = Pattern.compile("android:glEsVersion\\s*=\\s*\"([^\"]*)\"").matcher(m.group(1));
                if (g.find()) {
                    out.add(g.group(1));
                }
            }
        }
        return out;
    }

    private static String key(Element e) {
        String n = e.getAttributeNS(ANDROID_NS, "name");
        if (n.length() > 0) {
            return n;
        }
        String g = e.getAttributeNS(ANDROID_NS, "glEsVersion");
        return g.length() > 0 ? g : e.getTagName() + "#" + e.hashCode();
    }

    private static String expandName(String name, String pkg, boolean component) {
        if (!component || name.length() == 0 || pkg == null || pkg.length() == 0) {
            return name;
        }
        if (name.startsWith(".")) {
            return pkg + name;
        }
        return name.indexOf('.') < 0 ? pkg + "." + name : name;
    }

    private static Document parse(Lib lib) throws ToolException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // parser without that feature: entities are not expanded below anyway
            }
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new InputSource(new StringReader(lib.manifestXml)));
        } catch (Exception e) {
            throw new ToolException("The manifest of library " + lib.name + " could not be read: " + e.getMessage());
        }
    }

    private static void write(Element e, String pkg, String applicationId, int level, StringBuilder out) {
        String indent = "    ";
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < level; i++) {
            pad.append(indent);
        }
        String tag = e.getTagName();
        out.append(pad).append('<').append(tag);
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String ns = a.getNamespaceURI();
            String local = a.getLocalName() == null ? a.getNodeName() : a.getLocalName();
            String value = a.getNodeValue();
            String qname;
            if (ANDROID_NS.equals(ns)) {
                qname = "android:" + local;
                if (COMPONENTS.contains(tag) && (local.equals("name") || local.equals("targetActivity"))) {
                    value = expandName(value, pkg, true);
                }
            } else if (ns == null && !a.getNodeName().startsWith("xmlns")) {
                qname = a.getNodeName();
            } else {
                continue; // tools:* and namespace declarations are not copied
            }
            out.append(' ').append(qname).append("=\"").append(escape(fill(value, pkg, applicationId))).append('"');
        }
        List<Element> kids = new ArrayList<Element>();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element) {
                kids.add((Element) c);
            }
        }
        if (kids.isEmpty()) {
            out.append(" />\n");
            return;
        }
        out.append(">\n");
        for (Element k : kids) {
            write(k, pkg, applicationId, level + 1, out);
        }
        out.append(pad).append("</").append(tag).append(">\n");
    }

    private static String fill(String v, String pkg, String applicationId) {
        return v.replace("${applicationId}", applicationId).replace("${packageName}", pkg == null ? applicationId : pkg);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
