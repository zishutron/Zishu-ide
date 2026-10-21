package com.zishu.ide.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.EditText;

import com.zishu.ide.core.editor.CodeEditing;
import com.zishu.ide.core.editor.FileLanguage;
import com.zishu.ide.core.editor.Highlighter;
import com.zishu.ide.core.editor.TextSearch;
import com.zishu.ide.core.editor.TokenType;
import com.zishu.ide.core.editor.UndoStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A code editor built on EditText: line-number gutter, current-line highlight, error markers,
 * lexical syntax highlighting (computed off the UI thread and applied only to the visible lines),
 * bracket matching, undo/redo history and auto-indent.
 */
public final class CodeEditText extends EditText {

    public interface Listener {
        /** The user (not a programmatic load) changed the text. */
        void onUserEdit();

        void onCaretChanged();
    }

    private static final int MAX_HIGHLIGHT_CHARS = 600000;
    private static final int MARGIN_LINES = 60;

    private static final class SyntaxSpan extends ForegroundColorSpan {
        SyntaxSpan(int color) {
            super(color);
        }
    }

    private static final class MarkSpan extends BackgroundColorSpan {
        MarkSpan(int color) {
            super(color);
        }
    }

    private static final ExecutorService HIGHLIGHT_POOL = Executors.newSingleThreadExecutor();

    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint();
    private final List<SyntaxSpan> syntaxSpans = new ArrayList<SyntaxSpan>();
    private final List<MarkSpan> bracketSpans = new ArrayList<MarkSpan>();
    private final List<MarkSpan> findSpans = new ArrayList<MarkSpan>();

    private Palette pal;
    private Prefs prefs;
    private Listener listener;
    private FileLanguage language = FileLanguage.PLAIN;
    private UndoStack undo = new UndoStack();
    private Set<Integer> errorLines = new HashSet<Integer>();
    private boolean showLineNumbers = true;
    private int gutterWidth;
    private int digits = 3;

    private boolean loading;
    private boolean recording;
    private boolean autoIndenting;
    private int pendingPos;
    private String pendingRemoved = "";
    private int pendingNewline = -1;

    private int textVersion;
    private int tokenVersion = -1;
    private List<Highlighter.Token> tokens = new ArrayList<Highlighter.Token>();
    private boolean[] skipMask;
    private boolean highlightEnabled = true;

    private final Runnable highlightRunnable = new Runnable() {
        @Override
        public void run() {
            computeTokens();
        }
    };
    private final Runnable applyRunnable = new Runnable() {
        @Override
        public void run() {
            applyVisibleHighlight();
        }
    };

    public CodeEditText(Context context) {
        super(context);
        init();
    }

    public CodeEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setTypeface(Typeface.MONOSPACE);
        setGravity(Gravity.TOP | Gravity.START);
        setBackground(null);
        setIncludeFontPadding(false);
        setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        setHorizontallyScrolling(true);
        setVerticalScrollBarEnabled(true);
        setOverScrollMode(OVER_SCROLL_NEVER);
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (recording && !loading) {
                    pendingPos = start;
                    pendingRemoved = s.subSequence(start, start + count).toString();
                }
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                textVersion++;
                if (recording && !loading) {
                    String inserted = s.subSequence(start, start + count).toString();
                    undo.record(pendingPos, pendingRemoved, inserted, System.currentTimeMillis());
                    if (!autoIndenting && prefs != null && prefs.autoIndent() && count == 1 && before == 0 && inserted.equals("\n")) {
                        pendingNewline = start;
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (loading) {
                    return;
                }
                updateGutterSize();
                if (pendingNewline >= 0) {
                    int nl = pendingNewline;
                    pendingNewline = -1;
                    applyAutoIndent(e, nl);
                }
                scheduleHighlight(180);
                if (recording && listener != null) {
                    listener.onUserEdit();
                }
            }
        });
    }

    // ------------------------------------------------------------------ configuration

    public void configure(Palette palette, Prefs preferences) {
        this.pal = palette;
        this.prefs = preferences;
        setTextColor(palette.text);
        setHighlightColor(palette.selection);
        setTextSize(preferences.fontSizeSp());
        gutterPaint.setTypeface(Typeface.MONOSPACE);
        setWordWrap(preferences.wordWrap());
        showLineNumbers = preferences.lineNumbers();
        highlightEnabled = preferences.highlighting();
        updateGutterSize();
        invalidate();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setWordWrap(boolean wrap) {
        setHorizontallyScrolling(!wrap);
    }

    public void setShowLineNumbers(boolean show) {
        showLineNumbers = show;
        updateGutterSize();
        invalidate();
    }

    @Override
    public void setTextSize(float sp) {
        super.setTextSize(sp);
        gutterPaint.setTextSize(getTextSize() * 0.85f);
        updateGutterSize();
    }

    public void setLanguage(FileLanguage l) {
        language = l;
        tokens = new ArrayList<Highlighter.Token>();
        skipMask = null;
        tokenVersion = -1;
        clearSyntaxSpans();
    }

    public FileLanguage getLanguage() {
        return language;
    }

    public void bindUndo(UndoStack stack) {
        this.undo = stack;
    }

    /** Replaces the whole text without recording history or reporting an edit. */
    public void loadText(String text, int caret) {
        loading = true;
        recording = false;
        try {
            clearAllSpans();
            setText(text);
            int c = Math.max(0, Math.min(caret, text.length()));
            setSelection(c);
            updateGutterSize();
        } finally {
            loading = false;
            recording = true;
        }
        textVersion++;
        scheduleHighlight(0);
    }

    public void startRecording() {
        recording = true;
    }

    // ------------------------------------------------------------------ undo / redo

    public boolean undoEdit() {
        UndoStack.Result r = undo.undo(getText().toString());
        if (r == null) {
            return false;
        }
        applyHistory(r);
        return true;
    }

    public boolean redoEdit() {
        UndoStack.Result r = undo.redo(getText().toString());
        if (r == null) {
            return false;
        }
        applyHistory(r);
        return true;
    }

    private void applyHistory(UndoStack.Result r) {
        boolean prev = recording;
        recording = false;
        loading = false;
        try {
            Editable e = getText();
            clearAllSpans();
            e.replace(0, e.length(), r.text);
            setSelection(Math.min(r.caret, getText().length()));
        } finally {
            recording = prev;
        }
        updateGutterSize();
        scheduleHighlight(0);
        if (listener != null) {
            listener.onUserEdit();
        }
    }

    // ------------------------------------------------------------------ editing helpers

    private void applyAutoIndent(Editable e, int nlPos) {
        CodeEditing.Enter enter = CodeEditing.indentAfterNewline(e, nlPos, prefs.tabSize(), prefs.useTabs());
        if (enter.insert.length() == 0) {
            return;
        }
        autoIndenting = true;
        try {
            e.insert(nlPos + 1, enter.insert);
            setSelection(nlPos + 1 + enter.caretOffset);
        } finally {
            autoIndenting = false;
        }
    }

    /** Inserts text at the caret, replacing any selection (used by the symbol bar). */
    public void insertAtCaret(String s, int caretBackFromEnd) {
        Editable e = getText();
        int a = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
        int b = Math.max(0, Math.max(getSelectionStart(), getSelectionEnd()));
        e.replace(a, b, s);
        setSelection(Math.max(0, a + s.length() - caretBackFromEnd));
    }

    public void insertIndent() {
        insertAtCaret(CodeEditing.unit(prefs.tabSize(), prefs.useTabs()), 0);
    }

    // ------------------------------------------------------------------ horizontal drag

    private float dragDownX;
    private float dragDownY;
    private int dragStartScrollX;
    private boolean hDrag;
    private int widthCacheVersion = -1;
    private float widthCacheSize;
    private int widthCacheMax;

    /** The widest line plus padding, minus the view width: how far the text can be scrolled sideways. */
    private int maxScrollX() {
        Layout l = getLayout();
        if (l == null) {
            return 0;
        }
        if (widthCacheVersion != textVersion || widthCacheSize != getTextSize()) {
            float max = 0;
            for (int i = 0; i < l.getLineCount(); i++) {
                max = Math.max(max, l.getLineMax(i));
            }
            widthCacheMax = (int) Math.ceil(max);
            widthCacheVersion = textVersion;
            widthCacheSize = getTextSize();
        }
        return Math.max(0, widthCacheMax + getPaddingLeft() + getPaddingRight() - getWidth());
    }

    /**
     * A sideways drag scrolls the text. EditText's own handling often treats it as a cursor move
     * instead, so once a drag is clearly horizontal the touch is taken over here.
     */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragDownX = ev.getX();
                dragDownY = ev.getY();
                dragStartScrollX = getScrollX();
                hDrag = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!hDrag && ev.getPointerCount() == 1) {
                    float dx = ev.getX() - dragDownX;
                    float dy = ev.getY() - dragDownY;
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f && maxScrollX() > 0) {
                        hDrag = true;
                        MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.onTouchEvent(cancel);
                        cancel.recycle();
                        dragDownX = ev.getX();
                        dragStartScrollX = getScrollX();
                    }
                }
                if (hDrag) {
                    int nx = Math.round(dragStartScrollX - (ev.getX() - dragDownX));
                    scrollTo(Math.max(0, Math.min(nx, maxScrollX())), getScrollY());
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (hDrag) {
                    hDrag = false;
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_TAB && prefs != null) {
            insertIndent();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void goToLine(int line) {
        int off = CodeEditing.offsetOfLine(getText(), line);
        setSelection(off);
        bringPointIntoView(off);
        requestFocus();
    }

    public int currentLine() {
        return CodeEditing.lineOfOffset(getText(), getSelectionStart());
    }

    public int currentColumn() {
        int pos = getSelectionStart();
        int start = pos;
        CharSequence t = getText();
        while (start > 0 && t.charAt(start - 1) != '\n') {
            start--;
        }
        return pos - start + 1;
    }

    // ------------------------------------------------------------------ markers

    public void setErrorLines(Set<Integer> lines) {
        errorLines = lines;
        invalidate();
    }

    /** Highlights search matches; current is the index of the active match or -1. */
    public void setFindMatches(List<TextSearch.Match> matches, int current) {
        Editable e = getText();
        for (MarkSpan s : findSpans) {
            e.removeSpan(s);
        }
        findSpans.clear();
        if (matches == null || pal == null) {
            return;
        }
        int n = Math.min(matches.size(), 3000);
        for (int i = 0; i < n; i++) {
            TextSearch.Match m = matches.get(i);
            if (m.end > e.length()) {
                continue;
            }
            MarkSpan s = new MarkSpan(i == current ? pal.findCurrentBg : pal.findBg);
            e.setSpan(s, m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            findSpans.add(s);
        }
        if (current >= 0 && current < matches.size()) {
            TextSearch.Match m = matches.get(current);
            setSelection(m.start, m.end);
            bringPointIntoView(m.start);
        }
    }

    // ------------------------------------------------------------------ highlighting

    private void scheduleHighlight(long delayMs) {
        removeCallbacks(highlightRunnable);
        if (!highlightEnabled || language == FileLanguage.PLAIN || getText().length() > MAX_HIGHLIGHT_CHARS) {
            clearSyntaxSpans();
            return;
        }
        postDelayed(highlightRunnable, delayMs);
    }

    private void computeTokens() {
        final String snapshot = getText().toString();
        final int version = textVersion;
        final FileLanguage lang = language;
        HIGHLIGHT_POOL.execute(new Runnable() {
            @Override
            public void run() {
                final List<Highlighter.Token> result = Highlighter.highlight(lang, snapshot);
                final boolean[] mask = new boolean[snapshot.length()];
                for (Highlighter.Token t : result) {
                    if (t.type == TokenType.STRING || t.type == TokenType.COMMENT) {
                        for (int i = t.start; i < t.end && i < mask.length; i++) {
                            mask[i] = true;
                        }
                    }
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (version != textVersion || lang != language) {
                            return; // text changed again meanwhile; a newer pass is already scheduled
                        }
                        tokens = result;
                        skipMask = mask;
                        tokenVersion = version;
                        applyVisibleHighlight();
                        updateBracketMatch();
                    }
                });
            }
        });
    }

    private void clearSyntaxSpans() {
        Editable e = getText();
        for (SyntaxSpan s : syntaxSpans) {
            e.removeSpan(s);
        }
        syntaxSpans.clear();
    }

    private void clearAllSpans() {
        clearSyntaxSpans();
        Editable e = getText();
        for (MarkSpan s : bracketSpans) {
            e.removeSpan(s);
        }
        bracketSpans.clear();
        for (MarkSpan s : findSpans) {
            e.removeSpan(s);
        }
        findSpans.clear();
    }

    private int colorFor(TokenType t) {
        switch (t) {
            case KEYWORD: return pal.keyword;
            case TYPE: return pal.type;
            case STRING: return pal.string;
            case COMMENT: return pal.comment;
            case NUMBER: return pal.number;
            case ANNOTATION: return pal.annotation;
            case TAG: return pal.tag;
            case ATTRIBUTE: return pal.attribute;
            case PROPERTY: return pal.property;
            default: return pal.heading;
        }
    }

    private void applyVisibleHighlight() {
        Layout layout = getLayout();
        if (layout == null || pal == null || tokenVersion != textVersion) {
            return;
        }
        Editable e = getText();
        int lines = layout.getLineCount();
        if (lines == 0) {
            return;
        }
        int first = layout.getLineForVertical(getScrollY());
        int last = layout.getLineForVertical(getScrollY() + getHeight());
        int from = layout.getLineStart(Math.max(0, first - MARGIN_LINES));
        int to = layout.getLineEnd(Math.min(lines - 1, last + MARGIN_LINES));
        clearSyntaxSpans();
        for (Highlighter.Token t : tokens) {
            if (t.end <= from) {
                continue;
            }
            if (t.start >= to) {
                break;
            }
            if (t.end > e.length()) {
                break;
            }
            SyntaxSpan s = new SyntaxSpan(colorFor(t.type));
            e.setSpan(s, t.start, t.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            syntaxSpans.add(s);
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (t != oldt && !tokens.isEmpty()) {
            removeCallbacks(applyRunnable);
            postDelayed(applyRunnable, 60);
        }
    }

    // ------------------------------------------------------------------ brackets

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (pal == null) {
            return;
        }
        updateBracketMatch();
        if (listener != null && !loading) {
            listener.onCaretChanged();
        }
    }

    private void updateBracketMatch() {
        Editable e = getText();
        for (MarkSpan s : bracketSpans) {
            e.removeSpan(s);
        }
        bracketSpans.clear();
        if (pal == null || !language.usesBrackets() && language != FileLanguage.XML && language != FileLanguage.HTML
                && language != FileLanguage.JSON || e.length() > 400000 || getSelectionStart() != getSelectionEnd()) {
            return;
        }
        boolean[] mask = tokenVersion == textVersion ? skipMask : null;
        int caret = getSelectionStart();
        int match = CodeEditing.matchingBracket(e, caret, mask);
        if (match < 0) {
            return;
        }
        int here = (caret < e.length() && isBracket(e.charAt(caret))) ? caret : caret - 1;
        if (here < 0) {
            return;
        }
        MarkSpan a = new MarkSpan(pal.matchBg);
        MarkSpan b = new MarkSpan(pal.matchBg);
        e.setSpan(a, here, here + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        e.setSpan(b, match, match + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        bracketSpans.add(a);
        bracketSpans.add(b);
    }

    private static boolean isBracket(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}';
    }

    // ------------------------------------------------------------------ drawing

    private void updateGutterSize() {
        if (pal == null) {
            return;
        }
        if (!showLineNumbers) {
            gutterWidth = 0;
            setPadding(Ui.dp(getContext(), 10), Ui.dp(getContext(), 8), Ui.dp(getContext(), 16), Ui.dp(getContext(), 8));
            return;
        }
        int lines = Math.max(1, getLineCount());
        int d = Math.max(3, String.valueOf(lines).length());
        digits = d;
        float charW = gutterPaint.measureText("0");
        int newWidth = Math.round(charW * d) + Ui.dp(getContext(), 22);
        if (newWidth != gutterWidth || getPaddingLeft() != newWidth + Ui.dp(getContext(), 6)) {
            gutterWidth = newWidth;
            setPadding(gutterWidth + Ui.dp(getContext(), 6), Ui.dp(getContext(), 8), Ui.dp(getContext(), 16), Ui.dp(getContext(), 8));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (pal != null && layout != null) {
            int scrollX = getScrollX();
            // current line background
            if (getSelectionStart() == getSelectionEnd()) {
                int line = layout.getLineForOffset(getSelectionStart());
                fillPaint.setColor(pal.currentLine);
                canvas.drawRect(scrollX, getExtendedPaddingTop() + layout.getLineTop(line), scrollX + getWidth(),
                        getExtendedPaddingTop() + layout.getLineBottom(line), fillPaint);
            }
        }
        super.onDraw(canvas);
        if (pal == null || layout == null || !showLineNumbers) {
            return;
        }
        int scrollX = getScrollX();
        int scrollY = getScrollY();
        int top = getExtendedPaddingTop();
        // gutter background over the text that has scrolled underneath it
        fillPaint.setColor(pal.gutterBg);
        canvas.drawRect(scrollX, scrollY, scrollX + gutterWidth, scrollY + getHeight(), fillPaint);
        fillPaint.setColor(pal.outline);
        canvas.drawRect(scrollX + gutterWidth - 1, scrollY, scrollX + gutterWidth, scrollY + getHeight(), fillPaint);

        int first = layout.getLineForVertical(scrollY);
        int last = layout.getLineForVertical(scrollY + getHeight());
        CharSequence text = getText();
        gutterPaint.setTextAlign(Paint.Align.RIGHT);
        int caretLine = layout.getLineForOffset(getSelectionStart());
        int logical = CodeEditing.lineOfOffset(text, layout.getLineStart(first));
        for (int i = first; i <= last; i++) {
            int start = layout.getLineStart(i);
            boolean startsLogicalLine = start == 0 || text.charAt(start - 1) == '\n';
            if (!startsLogicalLine) {
                continue; // wrapped continuation: no number
            }
            if (i > first) {
                logical++;
            }
            int baseline = top + layout.getLineBaseline(i);
            boolean isError = errorLines.contains(Integer.valueOf(logical));
            gutterPaint.setColor(i == caretLine ? pal.text : pal.gutterText);
            canvas.drawText(String.valueOf(logical), scrollX + gutterWidth - Ui.dp(getContext(), 10), baseline, gutterPaint);
            if (isError) {
                fillPaint.setColor(pal.error);
                float cy = top + (layout.getLineTop(i) + layout.getLineBottom(i)) / 2f;
                canvas.drawCircle(scrollX + Ui.dp(getContext(), 6), cy, Ui.dp(getContext(), 3), fillPaint);
            }
        }
    }
}
