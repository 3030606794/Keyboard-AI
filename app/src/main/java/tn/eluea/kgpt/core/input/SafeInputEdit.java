package tn.eluea.kgpt.core.input;

/** A one-shot replacement that leaves the original untouched until generation succeeds. */
public final class SafeInputEdit {
    public interface Editor {
        boolean matches(String text, int selectionStart, int selectionEnd);
        boolean replace(int start, int end, String replacement);
    }
    private final Editor editor;
    private final String text;
    private final int selectionStart, selectionEnd, start, end;
    private boolean finished;
    public SafeInputEdit(Editor editor, String text, int selectionStart, int selectionEnd,
                         int start, int end) {
        if (editor == null || text == null || start < 0 || end < start || end > text.length())
            throw new IllegalArgumentException("Invalid editor snapshot");
        this.editor = editor;
        this.text = text;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.start = start;
        this.end = end;
    }
    public synchronized boolean complete(String replacement) {
        if (finished) return false;
        finished = true;
        return replacement != null && !replacement.isEmpty()
                && editor.matches(text, selectionStart, selectionEnd)
                && editor.replace(start, end, replacement);
    }
    public synchronized void cancel() { finished = true; }
}
