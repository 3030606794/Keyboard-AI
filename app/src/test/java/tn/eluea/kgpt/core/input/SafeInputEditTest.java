package tn.eluea.kgpt.core.input;

import org.junit.Test;
import static org.junit.Assert.*;

public class SafeInputEditTest {
    private static final class Editor implements SafeInputEdit.Editor {
        String text = "before prompt gpt after";
        int cursor = 17;
        boolean connected = true;
        int writes;
        public boolean matches(String original, int start, int end) {
            return connected && text.equals(original) && cursor == start && cursor == end;
        }
        public boolean replace(int start, int end, String replacement) {
            writes++;
            text = text.substring(0, start) + replacement + text.substring(end);
            return true;
        }
        SafeInputEdit snapshot() { return new SafeInputEdit(this, text, cursor, cursor, 7, 17); }
    }
    @Test public void replacesOnlyTargetAfterSuccessAndOnlyOnce() {
        Editor editor = new Editor(); SafeInputEdit edit = editor.snapshot();
        assertEquals("before prompt gpt after", editor.text);
        assertTrue(edit.complete("reply"));
        assertEquals("before reply after", editor.text);
        assertFalse(edit.complete("duplicate")); assertEquals(1, editor.writes);
    }
    @Test public void cancellationLeavesOriginalUntouched() {
        Editor editor = new Editor(); SafeInputEdit edit = editor.snapshot();
        edit.cancel(); assertFalse(edit.complete("late reply")); assertEquals(0, editor.writes);
        assertEquals("before prompt gpt after", editor.text);
    }
    @Test public void cursorMovePreventsWrite() {
        Editor editor = new Editor(); SafeInputEdit edit = editor.snapshot();
        editor.cursor--; assertFalse(edit.complete("reply")); assertEquals(0, editor.writes);
    }
    @Test public void editedTextPreventsWrite() {
        Editor editor = new Editor(); SafeInputEdit edit = editor.snapshot();
        editor.text += "typed"; assertFalse(edit.complete("reply")); assertEquals(0, editor.writes);
    }
    @Test public void changedConnectionPreventsWrite() {
        Editor editor = new Editor(); SafeInputEdit edit = editor.snapshot();
        editor.connected = false; assertFalse(edit.complete("reply")); assertEquals(0, editor.writes);
    }
    @Test public void emptyResponseDoesNotEraseInput() {
        Editor editor = new Editor(); assertFalse(editor.snapshot().complete("")); assertEquals(0, editor.writes);
    }
}
