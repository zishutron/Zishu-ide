package com.zishu.ide.core.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Undo/redo history for one document, stored as text edits (position, removed text, inserted text).
 * Consecutive single-character typing is merged so one undo removes a typed word, not a letter.
 * Memory use is bounded by {@link #MAX_CHARS}.
 */
public final class UndoStack {
    static final int MAX_CHARS = 2_000_000;

    private static final class Edit {
        final int pos;
        String removed;
        String inserted;
        final long time;

        Edit(int pos, String removed, String inserted, long time) {
            this.pos = pos;
            this.removed = removed;
            this.inserted = inserted;
            this.time = time;
        }

        int size() {
            return removed.length() + inserted.length();
        }
    }

    /** Result of undo/redo: the new text and where to put the caret. */
    public static final class Result {
        public final String text;
        public final int caret;

        Result(String text, int caret) {
            this.text = text;
            this.caret = caret;
        }
    }

    private final List<Edit> undo = new ArrayList<Edit>();
    private final List<Edit> redo = new ArrayList<Edit>();
    private int chars;
    private int savedDepth;
    private boolean savePointLost;

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Records an edit that has already been applied to the document. */
    public void record(int pos, String removed, String inserted, long now) {
        if (removed.length() == 0 && inserted.length() == 0) {
            return;
        }
        if (!redo.isEmpty()) {
            redo.clear();
            if (savedDepth > undo.size()) {
                savePointLost = true;
            }
        }
        if (!undo.isEmpty()) {
            Edit last = undo.get(undo.size() - 1);
            boolean typing = removed.length() == 0 && last.removed.length() == 0 && inserted.length() == 1
                    && pos == last.pos + last.inserted.length() && now - last.time < 1000
                    && inserted.charAt(0) != '\n' && undo.size() != savedDepth;
            if (typing) {
                last.inserted = last.inserted + inserted;
                chars += inserted.length();
                touch(last, now);
                return;
            }
            boolean backspacing = inserted.length() == 0 && last.inserted.length() == 0 && removed.length() == 1
                    && pos + 1 == last.pos && now - last.time < 1000 && undo.size() != savedDepth;
            if (backspacing) {
                undo.set(undo.size() - 1, new Edit(pos, removed + last.removed, "", now));
                chars += removed.length();
                trim();
                return;
            }
        }
        Edit e = new Edit(pos, removed, inserted, now);
        undo.add(e);
        chars += e.size();
        trim();
    }

    private void touch(Edit e, long now) {
        undo.set(undo.size() - 1, new Edit(e.pos, e.removed, e.inserted, now));
        trim();
    }

    private void trim() {
        while (chars > MAX_CHARS && undo.size() > 1) {
            Edit dropped = undo.remove(0);
            chars -= dropped.size();
            if (savedDepth > 0) {
                savedDepth--;
            } else {
                savePointLost = true;
            }
        }
    }

    public Result undo(String text) {
        if (undo.isEmpty()) {
            return null;
        }
        Edit e = undo.remove(undo.size() - 1);
        chars -= e.size();
        String out = text.substring(0, e.pos) + e.removed + text.substring(e.pos + e.inserted.length());
        redo.add(e);
        return new Result(out, e.pos + e.removed.length());
    }

    public Result redo(String text) {
        if (redo.isEmpty()) {
            return null;
        }
        Edit e = redo.remove(redo.size() - 1);
        String out = text.substring(0, e.pos) + e.inserted + text.substring(e.pos + e.removed.length());
        undo.add(e);
        chars += e.size();
        return new Result(out, e.pos + e.inserted.length());
    }

    public void markSaved() {
        savedDepth = undo.size();
        savePointLost = false;
    }

    /**
     * True when the document text equals the last saved text. Redo entries do not matter: after
     * undoing back to the saved state the text is identical even though redo is still possible.
     */
    public boolean isAtSavePoint() {
        return !savePointLost && undo.size() == savedDepth;
    }

    public void clear() {
        undo.clear();
        redo.clear();
        chars = 0;
        savedDepth = 0;
        savePointLost = false;
    }
}
