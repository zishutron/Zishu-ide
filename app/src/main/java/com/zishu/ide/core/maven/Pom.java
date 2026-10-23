package com.zishu.ide.core.maven;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** The parts of a Maven POM that dependency resolution needs. Values are raw (not yet interpolated). */
public final class Pom {
    public static final class Dep {
        public String group;
        public String artifact;
        public String version;
        public String scope;
        public String type;
        public boolean optional;
        public final List<String> exclusions = new ArrayList<String>();
    }

    public String group;
    public String artifact;
    public String version;
    public String packaging;
    public String parentGroup;
    public String parentArtifact;
    public String parentVersion;
    public final Map<String, String> properties = new LinkedHashMap<String, String>();
    public final List<Dep> dependencies = new ArrayList<Dep>();
    public final List<Dep> managed = new ArrayList<Dep>();

    public static Pom parse(byte[] xml) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setExpandEntityReferences(false);
            trySet(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder b = f.newDocumentBuilder();
            doc = b.parse(new InputSource(new ByteArrayInputStream(xml)));
        } catch (Exception e) {
            throw new IOException("Unreadable POM: " + e.getMessage());
        }
        Element root = doc.getDocumentElement();
        if (root == null || !"project".equals(root.getNodeName())) {
            throw new IOException("Not a POM file");
        }
        Pom p = new Pom();
        p.group = text(root, "groupId");
        p.artifact = text(root, "artifactId");
        p.version = text(root, "version");
        p.packaging = text(root, "packaging");
        Element parent = child(root, "parent");
        if (parent != null) {
            p.parentGroup = text(parent, "groupId");
            p.parentArtifact = text(parent, "artifactId");
            p.parentVersion = text(parent, "version");
        }
        Element props = child(root, "properties");
        if (props != null) {
            for (Node n = props.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element) {
                    p.properties.put(n.getNodeName(), n.getTextContent().trim());
                }
            }
        }
        Element dm = child(root, "dependencyManagement");
        if (dm != null) {
            readDeps(child(dm, "dependencies"), p.managed);
        }
        readDeps(child(root, "dependencies"), p.dependencies);
        return p;
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // not every XML parser knows every feature; entity expansion is also switched off above
        }
    }

    private static void readDeps(Element deps, List<Dep> out) {
        if (deps == null) {
            return;
        }
        for (Node n = deps.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && "dependency".equals(n.getNodeName())) {
                Element e = (Element) n;
                Dep d = new Dep();
                d.group = text(e, "groupId");
                d.artifact = text(e, "artifactId");
                d.version = text(e, "version");
                d.scope = text(e, "scope");
                d.type = text(e, "type");
                d.optional = "true".equalsIgnoreCase(text(e, "optional"));
                Element ex = child(e, "exclusions");
                if (ex != null) {
                    NodeList l = ex.getElementsByTagName("exclusion");
                    for (int i = 0; i < l.getLength(); i++) {
                        Element x = (Element) l.item(i);
                        String g = text(x, "groupId");
                        String a = text(x, "artifactId");
                        d.exclusions.add((g == null ? "*" : g) + ":" + (a == null ? "*" : a));
                    }
                }
                out.add(d);
            }
        }
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        if (e == null) {
            return null;
        }
        String t = e.getTextContent().trim();
        return t.length() == 0 ? null : t;
    }
}
