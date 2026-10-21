package com.zishu.ide.core.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationReport {
    private final List<Issue> issues = new ArrayList<Issue>();

    public void add(Issue issue) {
        issues.add(issue);
    }

    public void addAll(ValidationReport other) {
        issues.addAll(other.issues);
    }

    public List<Issue> all() {
        return Collections.unmodifiableList(issues);
    }

    public List<Issue> errors() {
        return filter(Issue.Severity.ERROR);
    }

    public List<Issue> warnings() {
        return filter(Issue.Severity.WARNING);
    }

    private List<Issue> filter(Issue.Severity s) {
        List<Issue> out = new ArrayList<Issue>();
        for (Issue i : issues) {
            if (i.severity == s) {
                out.add(i);
            }
        }
        return out;
    }

    public boolean hasErrors() {
        for (Issue i : issues) {
            if (i.severity == Issue.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    public boolean hasCode(String code) {
        for (Issue i : issues) {
            if (code.equals(i.code)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return issues.isEmpty();
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        for (Issue i : issues) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(i.format());
        }
        return sb.toString();
    }
}
