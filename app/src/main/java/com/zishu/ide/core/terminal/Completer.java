package com.zishu.ide.core.terminal;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Tab completion for the terminal input line: IDE commands, project names and file names. */
public final class Completer {
    public static final class Result {
        /** The line after completion (unchanged when nothing matched). */
        public final String line;
        /** All candidates when more than one matches; empty otherwise. */
        public final List<String> options;

        Result(String line, List<String> options) {
            this.line = line;
            this.options = options;
        }
    }

    private Completer() {
    }

    public static Result complete(String line, List<String> projectNames, File cwd) {
        int sp = line.lastIndexOf(' ');
        String head = sp < 0 ? "" : line.substring(0, sp + 1);
        String word = sp < 0 ? line : line.substring(sp + 1);
        String[] before = head.trim().isEmpty() ? new String[0] : head.trim().split("\\s+");

        List<String> pool = new ArrayList<String>();
        boolean files = false;
        if (before.length == 0) {
            pool.addAll(Arrays.asList("zishu", "build", "clear", "help", "ls", "cd", "cat", "cp", "mv", "rm", "mkdir", "pwd", "echo"));
        } else if (isIde(before[0]) && before.length == 1) {
            pool.addAll(Arrays.asList(IdeCommands.subcommands()));
        } else if (isIde(before[0]) && before.length == 2) {
            String sub = before[1];
            if ("open".equals(sub)) {
                pool.addAll(projectNames);
            } else if ("engine".equals(sub)) {
                pool.addAll(Arrays.asList("auto", "device", "cloud"));
            } else if ("build".equals(sub)) {
                pool.addAll(Arrays.asList("--device", "--cloud"));
            } else if ("cloud".equals(sub)) {
                pool.addAll(Arrays.asList("status", "repo", "workflow", "public", "settings"));
            } else if ("help".equals(sub)) {
                pool.addAll(Arrays.asList("cloud", "build"));
            }
        } else if ("build".equals(before[0]) && before.length == 1) {
            pool.addAll(Arrays.asList("--device", "--cloud"));
        } else if (!isIde(before[0])) {
            files = true;
        }

        List<String> matches = new ArrayList<String>();
        if (files) {
            addFiles(matches, word, cwd);
        } else {
            for (String p : pool) {
                if (p.startsWith(word)) {
                    matches.add(p);
                }
            }
        }
        Collections.sort(matches);
        if (matches.isEmpty()) {
            return new Result(line, new ArrayList<String>());
        }
        if (matches.size() == 1) {
            String m = matches.get(0);
            return new Result(head + m + (m.endsWith("/") ? "" : " "), new ArrayList<String>());
        }
        String common = matches.get(0);
        for (String m : matches) {
            int i = 0;
            while (i < common.length() && i < m.length() && common.charAt(i) == m.charAt(i)) {
                i++;
            }
            common = common.substring(0, i);
        }
        return new Result(head + common, matches);
    }

    private static boolean isIde(String w) {
        return "zishu".equals(w) || "z".equals(w);
    }

    private static void addFiles(List<String> out, String word, File cwd) {
        if (cwd == null) {
            return;
        }
        int slash = word.lastIndexOf('/');
        String dirPart = slash < 0 ? "" : word.substring(0, slash + 1);
        String namePart = slash < 0 ? word : word.substring(slash + 1);
        File dir = dirPart.isEmpty() ? cwd : dirPart.startsWith("/") ? new File(dirPart) : new File(cwd, dirPart);
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.getName().startsWith(namePart) && (namePart.startsWith(".") || !k.getName().startsWith("."))) {
                out.add(dirPart + k.getName() + (k.isDirectory() ? "/" : ""));
            }
        }
    }
}
