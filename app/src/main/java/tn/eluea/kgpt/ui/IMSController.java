/*
 * Copyright (c) 2025 Amr Aldeeb @Eluea
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 *
 * This file is part of KGPT.
 * Based on original code from KeyboardGPT by Mino260806.
 * Original: https://github.com/Mino260806/KeyboardGPT
 *
 * Licensed under the GPLv3.
 */
package tn.eluea.kgpt.ui;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import tn.eluea.kgpt.BuildConfig;
import tn.eluea.kgpt.listener.InputEventListener;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import tn.eluea.kgpt.util.Logger;
import tn.eluea.kgpt.BuildConfig;

public class IMSController {
    private static final long INPUT_LOCK_TIMEOUT_MS = 15000; // 15 seconds timeout (reduced from 60s)

    private InputMethodService ims = null;
    private String typedText = "";
    private int cursor = 0;
    private volatile boolean inputNotify = false;
    private volatile boolean inputLock = false;
    private volatile long inputLockStartTime = 0;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    // Deferred commit/delete when InputConnection is temporarily null (e.g. IME resets during network calls)
    private static final long DEFERRED_TIMEOUT_MS = 12000; // 12 seconds
    private static final long DEFERRED_RETRY_DELAY_MS = 120; // ms

    private final Handler deferredHandler = new Handler(Looper.getMainLooper());

    // Input-event based updates (for IMEs that override onUpdateSelection without calling super)
    // We debounce updates triggered from InputConnection hooks (commitText / composing / delete) to avoid overhead.
    private final Handler inputEventHandler = new Handler(Looper.getMainLooper());
    private volatile boolean inputEventScheduled = false;
    private volatile InputConnection lastInputEventIC = null;
    private static final int INPUT_EVENT_MAX_BEFORE = 8192;
    private static final long INPUT_EVENT_DEBOUNCE_MS = 20;

    // Shadow buffer: supports editors that return null for getTextBeforeCursor/getExtractedText.
    private final StringBuilder shadow = new StringBuilder();
    private boolean composingActive = false;
    private int composingLen = 0;
    private static final int SHADOW_MAX = 2048;

    // Shadow buffer belongs to a specific target app. Without this, text typed in KGPT's own
    // settings dialogs (or another app) may leak into the next app and break trigger parsing.
    private String shadowPackage = null;

    // ------------------------------------------------------------
    // Self-mutation guard
    // ------------------------------------------------------------
    // When KGPT writes to the editor via InputConnection, InputConnection hooks will see those
    // commit/delete calls. We use this guard to avoid treating our own output as "user input",
    // especially for the "interrupt gesture" detector.
    private static final AtomicInteger SELF_MUTATION_DEPTH = new AtomicInteger(0);

    public void beginSelfMutation() {
        try { SELF_MUTATION_DEPTH.incrementAndGet(); } catch (Throwable ignored) {}
    }

    public void endSelfMutation() {
        try {
            int v = SELF_MUTATION_DEPTH.decrementAndGet();
            if (v < 0) {
                SELF_MUTATION_DEPTH.set(0);
            }
        } catch (Throwable ignored) {
            try { SELF_MUTATION_DEPTH.set(0); } catch (Throwable ignored2) {}
        }
    }

    /** True if KGPT is currently mutating the editor (commit/delete/flush) via InputConnection. */
    public boolean isSelfMutationInProgress() {
        try { return SELF_MUTATION_DEPTH.get() > 0; } catch (Throwable ignored) { return false; }
    }

// Track where the latest typedText snapshot came from.
private static final int BUFFER_SRC_UNKNOWN = 0;
private static final int BUFFER_SRC_BEFORE_AFTER = 1;
private static final int BUFFER_SRC_EXTRACTED = 2;
private static final int BUFFER_SRC_SHADOW = 3;
private volatile int lastBufferSource = BUFFER_SRC_UNKNOWN;

// Track last editor mutation so we can avoid firing UI triggers on stale selection updates
// or composing previews (candidate/clipboard preview).
private volatile long lastEditorMutationMs = 0;
private volatile boolean lastEditorMutationWasComposing = false;

private void markEditorMutation(boolean composing) {
    lastEditorMutationMs = System.currentTimeMillis();
    lastEditorMutationWasComposing = composing;
}

/** True if the most recent editor mutation was a non-composing commit/delete/replace within windowMs. */
public boolean isRecentNonComposingMutation(long windowMs) {
    long t = lastEditorMutationMs;
    if (t == 0) return false;
    long now = System.currentTimeMillis();
    return (now - t) <= windowMs && !lastEditorMutationWasComposing;
}

/** True if IME is currently in composing mode (best-effort). */
public boolean isComposingActive() {
    return composingActive;
}

/** True if our last text snapshot came from the shadow buffer. */
public boolean isLastBufferFromShadow() {
    return lastBufferSource == BUFFER_SRC_SHADOW;
}


    private final StringBuilder pendingCommitBuffer = new StringBuilder();
    private int pendingDeleteBefore = 0;
    private int pendingDeleteAfter = 0;
    private boolean pendingFinishComposing = false;
    private boolean deferredScheduled = false;
    private long deferredStartTimeMs = 0;

    private final Runnable deferredRunnable = new Runnable() {
        @Override
        public void run() {
            deferredScheduled = false;

            if (!hasPending()) {
                deferredStartTimeMs = 0;
                return;
            }

            long now = System.currentTimeMillis();
            if (deferredStartTimeMs == 0) {
                deferredStartTimeMs = now;
            }
            if (now - deferredStartTimeMs > DEFERRED_TIMEOUT_MS) {
                // Timed out - copy the response to clipboard as a fallback
                fallbackToClipboardAndToast();
                clearDeferred();
                return;
            }

            boolean ok = true;

            if (pendingFinishComposing) {
                boolean flushOk = tryFlush();
                if (flushOk) {
                    pendingFinishComposing = false;
                }
                ok = ok && flushOk;
            }

            if (pendingDeleteBefore > 0 || pendingDeleteAfter > 0) {
    boolean delOk;
    if (pendingDeleteAfter > 0) {
        delOk = tryDeleteSurrounding(pendingDeleteBefore, pendingDeleteAfter);
    } else {
        delOk = tryDelete(pendingDeleteBefore);
    }
    if (delOk) {
        pendingDeleteBefore = 0;
        pendingDeleteAfter = 0;
    }
    ok = ok && delOk;
}

            if (pendingCommitBuffer.length() > 0) {
                String toCommit = pendingCommitBuffer.toString();
                boolean comOk = tryCommit(toCommit);
                if (comOk) {
                    pendingCommitBuffer.setLength(0);
                }
                ok = ok && comOk;
            }

            if (!ok) {
                scheduleDeferred();
            } else if (!hasPending()) {
                deferredStartTimeMs = 0;
            }
        }
    };

    private final Runnable lockTimeoutRunnable = () -> {
        if (inputLock) {
            // Force unlock after timeout
            tn.eluea.kgpt.util.Logger.log("Input lock timeout - forcing unlock");
            inputLock = false;
            inputNotify = false;
            inputLockStartTime = 0;
        }
    };

    private List<InputEventListener> mListeners = new ArrayList<>();

    public IMSController() {
    }

    public static IMSController getInstance() {
        return UiInteractor.getInstance().getIMSController();
    }

    public void onUpdateSelection(int oldSelStart,
            int oldSelEnd,
            int newSelStart,
            int newSelEnd,
            int candidatesStart,
            int candidatesEnd) {
        if (inputNotify) {
            return;
        }
        if (ims == null)
            return;

        InputConnection ic = ims.getCurrentInputConnection();
        if (ic == null) {
            return;
        }

        // Primary path: prefer ExtractedText (full buffer when supported by the editor)
        try {
            ExtractedText extractedText = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (extractedText != null && extractedText.text != null) {
                typedText = extractedText.text.toString();
                int selEnd = extractedText.selectionEnd;
                lastBufferSource = BUFFER_SRC_EXTRACTED;
                int candidateCursor = (selEnd >= 0 ? selEnd : newSelEnd);
                cursor = Math.max(0, Math.min(candidateCursor, typedText.length()));
                    lastBufferSource = BUFFER_SRC_EXTRACTED;
                    notifyTextUpdate();
                return;
            }
        } catch (Throwable ignored) {
        }

        // Fallback: some custom editors return null for getExtractedText().
        // Build a local buffer around the cursor using getTextBefore/AfterCursor so triggers still work.
        try {
            final int MAX_BEFORE = 8192;
            final int MAX_AFTER = 1024;

            CharSequence before = ic.getTextBeforeCursor(MAX_BEFORE, 0);
            CharSequence after = ic.getTextAfterCursor(MAX_AFTER, 0);

            if (before == null && after == null) {
                return;
            }

            StringBuilder sb = new StringBuilder();
            if (before != null) sb.append(before);
            int localCursor = sb.length();
            if (after != null) sb.append(after);

            typedText = sb.toString();
            cursor = localCursor;
            lastBufferSource = BUFFER_SRC_BEFORE_AFTER;
            notifyTextUpdate();
        } catch (Throwable ignored) {
        }
    }


    /**
     * Fallback update path triggered directly from InputConnection hooks.
     * Some keyboards override InputMethodService.onUpdateSelection() and don't call super(),
     * which means our onUpdateSelection hook never runs. In that case we still want keyword
     * triggers (AI 触发器) to work reliably.
     */
    public void requestTextUpdateFromInputEvent(InputConnection ic) {
        if (inputNotify) return;
        if (ims == null) return;
        try {
			android.view.inputmethod.EditorInfo ei = ims.getCurrentInputEditorInfo();
			String pkg = (ei != null) ? ei.packageName : null;
			if (pkg != null) {
				if (tn.eluea.kgpt.BuildConfig.APPLICATION_ID.equals(pkg)) {
					// We're typing inside KGPT's own UI. Clear shadow so it won't leak into other apps.
					shadowPackage = pkg;
					resetShadow();
					return;
				}
				ensureShadowForPackage(pkg);
			}
        } catch (Throwable ignored) {}
        if (ic == null) {
            try {
                ic = ims.getCurrentInputConnection();
            } catch (Throwable ignored) {}
        }
        if (ic == null) return;

        lastInputEventIC = ic;

        if (inputEventScheduled) return;
        inputEventScheduled = true;

        inputEventHandler.postDelayed(() -> {
            inputEventScheduled = false;
            InputConnection current = lastInputEventIC;
            if (current == null) {
                try {
                    current = ims.getCurrentInputConnection();
                } catch (Throwable ignored) {}
            }
            if (current == null) return;

            // Prefer a local buffer around the cursor: it works in many editors even when getExtractedText() returns null.
            // IMPORTANT: include after-cursor text when possible so UI layers (e.g., Quick Jump menu) can read the
            // "current line" even if the cursor is placed at the beginning of the word/line.
            try {
                final int MAX_BEFORE = INPUT_EVENT_MAX_BEFORE;
                final int MAX_AFTER = 2048;

                CharSequence before = current.getTextBeforeCursor(MAX_BEFORE, 0);
                CharSequence after = current.getTextAfterCursor(MAX_AFTER, 0);

                if (before != null || after != null) {
                    String b = before != null ? before.toString() : "";
                    String a = after != null ? after.toString() : "";
                    lastBufferSource = BUFFER_SRC_BEFORE_AFTER;
                    typedText = b + a;
                    cursor = b.length();
                    notifyTextUpdate();
                    return;
                }
            } catch (Throwable ignored) {}

            // Fallback to ExtractedText (some editors only support this)
            try {
                ExtractedText extractedText = current.getExtractedText(new ExtractedTextRequest(), 0);
                if (extractedText != null && extractedText.text != null) {
                    typedText = extractedText.text.toString();
                    int selEnd = extractedText.selectionEnd;
                lastBufferSource = BUFFER_SRC_EXTRACTED;
                    int candidateCursor = (selEnd >= 0 ? selEnd : typedText.length());
                    cursor = Math.max(0, Math.min(candidateCursor, typedText.length()));
                    lastBufferSource = BUFFER_SRC_EXTRACTED;
                    notifyTextUpdate();
                }
            } catch (Throwable ignored) {}


// Fallback to shadow buffer: some editors return null for both methods.
if (shadow.length() > 0) {
    typedText = shadow.toString();
    cursor = typedText.length();
    lastBufferSource = BUFFER_SRC_SHADOW;
    notifyTextUpdate();
}
        }, INPUT_EVENT_DEBOUNCE_MS);
    }



private void trimShadowIfNeeded() {
    if (shadow.length() > SHADOW_MAX) {
        shadow.delete(0, shadow.length() - SHADOW_MAX);
    }
}

    private String getCurrentTargetPackageName() {
        try {
            if (ims == null) return null;
            EditorInfo ei = ims.getCurrentInputEditorInfo();
            return ei != null ? ei.packageName : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isKgptPackage(String pkg) {
        return pkg != null && BuildConfig.APPLICATION_ID.equals(pkg);
    }

    private void ensureShadowForPackage(String pkg) {
        if (pkg == null) return;
        if (shadowPackage == null) {
            shadowPackage = pkg;
            return;
        }
        if (!pkg.equals(shadowPackage)) {
            resetShadow();
            shadowPackage = pkg;
        }
    }

    private void shadowDeleteFromEnd(int count) {
        if (count <= 0) return;
        try {
            String pkg = getCurrentTargetPackageName();
            ensureShadowForPackage(pkg);

            int len = shadow.length();
            if (len <= 0) return;

            int start = Math.max(0, len - count);
            shadow.delete(start, len);
        } catch (Exception ignored) {
        }
    }

/**
 * Update shadow buffer from InputConnection events. These events are available even when editors
 * deny access to getTextBeforeCursor()/getExtractedText().
 * Note: these methods DO NOT notify listeners directly. Notification is done in requestTextUpdateFromInputEvent()
 * (debounced) to avoid duplicate trigger firing.
 */
public void onInputEventText(CharSequence cs, boolean composing) {
    if (inputNotify) return;
    if (cs == null) return;
    String s = cs.toString();
    if (s.isEmpty()) return;

	String pkg = getCurrentTargetPackageName();
	if (isKgptPackage(pkg)) {
		shadowPackage = pkg;
		resetShadow();
		return;
	}
	ensureShadowForPackage(pkg);

    markEditorMutation(composing);

    // If we were composing, replace the previous composing tail with the new one
    if (composingActive && composingLen > 0 && shadow.length() >= composingLen) {
        int start = Math.max(0, shadow.length() - composingLen);
        shadow.delete(start, shadow.length());
        composingLen = 0;
    }

    shadow.append(s);
    trimShadowIfNeeded();

    if (composing) {
        composingActive = true;
        composingLen = s.length();
    } else {
        composingActive = false;
        composingLen = 0;
    }
}

public void onInputEventFinishComposing() {
    if (inputNotify) return;

    String pkg = getCurrentTargetPackageName();
    if (isKgptPackage(pkg)) {
        shadowPackage = pkg;
        resetShadow();
        return;
    }
    ensureShadowForPackage(pkg);

    markEditorMutation(true);

    composingActive = false;
    composingLen = 0;
}

public void onInputEventDelete(int before, int after) {
    if (inputNotify) return;
    before = Math.max(0, before);
    after = Math.max(0, after);

    String pkg = getCurrentTargetPackageName();
    if (isKgptPackage(pkg)) {
        shadowPackage = pkg;
        resetShadow();
        return;
    }
    ensureShadowForPackage(pkg);

    markEditorMutation(false);

    if ((before > 0) && (shadow.length() > 0)) {
        int start = Math.max(0, shadow.length() - before);
        shadow.delete(start, shadow.length());
    }
    // We don't track cursor-after deletions accurately in tail-mode; ignore 'after'.

    composingActive = false;
    composingLen = 0;
}

public void onInputEventReplace(int start, int end, CharSequence cs) {
    if (inputNotify) return;

    String pkg = getCurrentTargetPackageName();
    if (isKgptPackage(pkg)) {
        shadowPackage = pkg;
        resetShadow();
        return;
    }
    ensureShadowForPackage(pkg);

    markEditorMutation(false);

    composingActive = false;
    composingLen = 0;
    if (cs != null) {
        String s = cs.toString();
        if (!s.isEmpty()) {
            shadow.append(s);
            trimShadowIfNeeded();
        }
    }
}

    

public void resetShadow() {
    try { shadow.setLength(0); } catch (Throwable ignored) {}
    composingActive = false;
    composingLen = 0;
}

/**
 * Snapshot getters for UI layers (e.g., Quick Jump menu) that need the latest input keyword.
 * These are best-effort and may lag slightly depending on editor callbacks.
 */
public String getTypedTextSnapshot() {
    try { return typedText != null ? typedText : ""; } catch (Throwable t) { return ""; }
}

public int getCursorSnapshot() {
    try { return cursor; } catch (Throwable t) { return 0; }
}


public void addListener(InputEventListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(InputEventListener listener) {
        mListeners.remove(listener);
    }

    private void notifyTextUpdate() {
        for (InputEventListener listener : mListeners) {
            listener.onTextUpdate(typedText, cursor);
        }
    }

    private long editorGeneration;

    public void invalidateEditor() {
        editorGeneration++;
        clearDeferred();
        forceResetLock();
        resetShadow();
    }

    public tn.eluea.kgpt.core.input.SafeInputEdit captureSafeEdit(String expected, int start, int end) {
        final InputConnection connection = getIC();
        if (connection == null || ims == null) return null;
        android.view.inputmethod.EditorInfo info = ims.getCurrentInputEditorInfo();
        if (info == null) return null;
        int variation = info.inputType & android.text.InputType.TYPE_MASK_VARIATION;
        int type = info.inputType & android.text.InputType.TYPE_MASK_CLASS;
        if ((type == android.text.InputType.TYPE_CLASS_TEXT &&
                (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                 variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                 variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (type == android.text.InputType.TYPE_CLASS_NUMBER &&
                 variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)) return null;
        final ExtractedText snapshot;
        try { snapshot = connection.getExtractedText(new ExtractedTextRequest(), 0); }
        catch (RuntimeException e) { return null; }
        if (snapshot == null || snapshot.text == null) return null;
        String text = snapshot.text.toString();
        if (expected != null && !expected.equals(text)) return null;
        int from = start < 0 ? Math.min(snapshot.selectionStart, snapshot.selectionEnd) : start;
        int to = end < 0 ? Math.max(snapshot.selectionStart, snapshot.selectionEnd) : end;
        if (from < 0 || to < from || to > text.length()) return null;
        final long generation = editorGeneration;
        return new tn.eluea.kgpt.core.input.SafeInputEdit(new tn.eluea.kgpt.core.input.SafeInputEdit.Editor() {
            public boolean matches(String original, int selStart, int selEnd) {
                if (generation != editorGeneration || connection != getIC()) return false;
                try {
                    ExtractedText current = connection.getExtractedText(new ExtractedTextRequest(), 0);
                    return current != null && current.text != null && original.contentEquals(current.text)
                            && current.startOffset == snapshot.startOffset
                            && current.selectionStart == selStart && current.selectionEnd == selEnd;
                } catch (RuntimeException e) { return false; }
            }
            public boolean replace(int from, int to, String replacement) {
                stopNotifyInput();
                beginSelfMutation();
                try {
                    connection.beginBatchEdit();
                    if (!connection.setSelection(snapshot.startOffset + from, snapshot.startOffset + to)) return false;
                    boolean committed = connection.commitText(replacement, 1);
                    if (!committed) connection.setSelection(snapshot.startOffset + snapshot.selectionStart,
                            snapshot.startOffset + snapshot.selectionEnd);
                    return committed;
                } catch (RuntimeException e) {
                    try { connection.setSelection(snapshot.startOffset + snapshot.selectionStart,
                            snapshot.startOffset + snapshot.selectionEnd); } catch (RuntimeException ignored) { }
                    return false;
                } finally {
                    try { connection.endBatchEdit(); } catch (RuntimeException ignored) { }
                    endSelfMutation();
                    startNotifyInput();
                }
            }
        }, text, snapshot.selectionStart, snapshot.selectionEnd, from, to);
    }

    public void registerService(InputMethodService ims) {
        invalidateEditor();
        this.ims = ims;
    }

    public void unregisterService(InputMethodService ims) {
        invalidateEditor();
        this.ims = null;
        try { shadow.setLength(0); } catch (Throwable ignored) {}
        composingActive = false;
        composingLen = 0;
    }

    public void delete(int count) {
        if (count <= 0) {
            return;
        }
        if (tryDelete(count)) {
            shadowDeleteFromEnd(count);
            return;
        }
        // Queue delete until InputConnection becomes available again
        pendingDeleteBefore += count;
        scheduleDeferred();
    }


    public void deleteSurrounding(int before, int after) {
        if (before <= 0 && after <= 0) {
            return;
        }
        before = Math.max(0, before);
        after = Math.max(0, after);

        if (tryDeleteSurrounding(before, after)) {
            return;
        }

        // Queue delete until InputConnection becomes available again
        // Avoid accumulating relative deletes across cursor moves (can delete wrong text)
        pendingDeleteBefore = Math.max(pendingDeleteBefore, before);
        pendingDeleteAfter = Math.max(pendingDeleteAfter, after);
        scheduleDeferred();
    }

    /**
     * Delete a text range using absolute offsets (safer than relative deleteSurrounding in some editors).
     * This will NOT delete the line break if you pass end before the newline character.
     *
     * @return true if deleted immediately, false if InputConnection is unavailable.
     */
    public boolean deleteRange(int start, int end) {
        start = Math.max(0, start);
        end = Math.max(0, end);
        if (end <= start) return true;

        InputConnection ic = getIC();
        if (ic == null) return false;

        try {
            beginSelfMutation();
            ic.beginBatchEdit();
            boolean selOk = true;
            try {
                selOk = ic.setSelection(start, end);
            } catch (Throwable ignored) {
            }
            if (!selOk) {
                try { ic.endBatchEdit(); } catch (Throwable ignored) {}
                return false;
            }
            ic.commitText("", 1);
            try { ic.endBatchEdit(); } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            try { ic.endBatchEdit(); } catch (Throwable ignored) {}
            Logger.error("IMS deleteRange failed: " + t.getMessage());
            return false;
        } finally {
            endSelfMutation();
        }
    }





    public void commit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (tryCommit(text)) {
            return;
        }
        // Queue commit until InputConnection becomes available again
        pendingCommitBuffer.append(text);
        scheduleDeferred();
    }

    /**
     * Commit text with a custom cursor positioning behavior.
     *
     * Android InputConnection.commitText takes {@code newCursorPosition} where:
     *  - 1 means place cursor after inserted text (default behavior)
     *  - 0 means keep cursor at the insertion point (i.e., the inserted text ends up AFTER the cursor)
     */
    public void commitWithCursor(String text, int newCursorPosition) {
        if (text == null || text.isEmpty()) return;
        if (tryCommit(text, newCursorPosition)) return;
        // We intentionally DO NOT queue this operation because the cursor semantics are critical
        // (queued commits may run at a different cursor position).
    }

    /**
     * Commit CharSequence (supports spans) with a custom cursor positioning behavior.
     *
     * @return true if commit succeeded, false otherwise.
     */
    public boolean commitWithCursor(CharSequence text, int newCursorPosition) {
        if (text == null || text.length() == 0) return false;
        return tryCommit(text, newCursorPosition);
    }

    /** Convenience: inserts text AFTER the cursor (cursor stays before the inserted text). */
    public void commitAfterCursor(String text) {
        commitWithCursor(text, 0);
    }

    /** Convenience: inserts CharSequence AFTER the cursor (cursor stays before the inserted text). */
    public boolean commitAfterCursor(CharSequence text) {
        return commitWithCursor(text, 0);
    }

    /**
     * Insert a composing (spannable) suffix after cursor while keeping cursor before it.
     * This is used to render animated / colored reply markers in editors that support composing spans.
     */
    public boolean setComposingAfterCursor(CharSequence composingText) {
        InputConnection ic = getIC();
        if (ic == null) return false;

        beginSelfMutation();
        try {
            // newCursorPosition=0 keeps cursor at the beginning of composing text (i.e., before the suffix),
            // so subsequent commits will be inserted before it.
            ic.setComposingText(composingText, 0);
            return true;
        } catch (Throwable tr) {
            // Never crash the IME pipeline; just log and fail gracefully.
            Logger.log(tr);
            return false;
        } finally {
            endSelfMutation();
        }
    }

    public void finishComposingSafe() {
        InputConnection ic = getIC();
        if (ic == null) return;
        beginSelfMutation();
        try {
            ic.finishComposingText();
        } catch (Throwable tr) {
            // ignore
        } finally {
            endSelfMutation();
        }
    }


    /**
     * Delete {@code expected} after cursor ONLY if the text after cursor matches it.
     * Used to safely remove the "AI 回复中" trailing keyword after output finishes.
     */

    public boolean tryDeleteAfterCursorIfMatches(String expected) {
        if (expected == null || expected.isEmpty()) return false;
        InputConnection ic = getIC();
        if (ic == null) return false;
        beginSelfMutation();
        try {
            CharSequence after = ic.getTextAfterCursor(expected.length(), 0);
            if (after != null && expected.contentEquals(after)) {
                ic.deleteSurroundingText(0, expected.length());
                markEditorMutation(false);
                return true;
            }
        } catch (Throwable ignored) {
        } finally {
            endSelfMutation();
        }
        return false;
    }

    /**
     * Delete expected after cursor ONLY if the text after cursor matches it.
     * Used to safely remove the "AI 回复中" trailing keyword after output finishes.
     */
    public void deleteAfterCursorIfMatches(String expected) {
        if (expected == null || expected.isEmpty()) return;
        InputConnection ic = getIC();
        if (ic == null) return;

        // First try verified delete
        if (tryDeleteAfterCursorIfMatches(expected)) {
            return;
        }

        // Fallback: best-effort delete even if we can't verify.
        beginSelfMutation();
        try {
            ic.deleteSurroundingText(0, expected.length());
            markEditorMutation(false);
        } catch (Throwable ignored) {
        } finally {
            endSelfMutation();
        }
    }

    /**
     * Replace text after cursor ONLY if it matches expectedOld.
     * Cursor position is preserved (replacement will remain after cursor).
     */
    public boolean replaceAfterCursorIfMatches(String expectedOld, String replacement) {
        if (expectedOld == null) expectedOld = "";
        if (replacement == null) replacement = "";
        InputConnection ic = getIC();
        if (ic == null) return false;
        beginSelfMutation();
        try {
            CharSequence after = ic.getTextAfterCursor(expectedOld.length(), 0);
            if (after != null && expectedOld.contentEquals(after)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(0, expectedOld.length());
                ic.commitText(replacement, 0);
                ic.endBatchEdit();
                markEditorMutation(false);
                return true;
            }
        } catch (Throwable t) {
            try { ic.endBatchEdit(); } catch (Throwable ignored) {}
        } finally {
            endSelfMutation();
        }
        return false;
    }


    public boolean replaceAfterCursorIfMatches(String expectedOld, CharSequence replacement) {
        if (expectedOld == null) expectedOld = "";
        if (replacement == null) replacement = "";
        InputConnection ic = getIC();
        if (ic == null) return false;

        beginSelfMutation();
        try {
            CharSequence after = ic.getTextAfterCursor(expectedOld.length(), 0);
            if (after == null || !expectedOld.contentEquals(after)) return false;

            ic.beginBatchEdit();
            boolean ok = ic.deleteSurroundingText(0, expectedOld.length());
            if (ok) {
                ok = ic.commitText(replacement, 0);
            }
            ic.endBatchEdit();
            if (ok) markEditorMutation(false);
            return ok;
        } catch (Throwable t) {
            try { ic.endBatchEdit(); } catch (Throwable ignored) {}
            return false;
        } finally {
            endSelfMutation();
        }
    }

    /**
     * Delete text immediately before cursor ONLY if it matches the expected string.
     * Useful for removing our own temporary placeholders without harming user content.
     */
    public boolean deleteBeforeCursorIfMatches(@androidx.annotation.NonNull String expected) {
        if (expected == null || expected.length() == 0) return false;

        beginSelfMutation();
        try {
            // Always fetch the latest InputConnection (it can be recreated by the system).
            InputConnection ic = getIC();
            if (ic == null) return false;

            // Exact match for the last N chars.
            CharSequence before = ic.getTextBeforeCursor(expected.length(), 0);
            if (before != null && expected.contentEquals(before)) {
                boolean ok = ic.deleteSurroundingText(expected.length(), 0);
                if (ok) {
                    markEditorMutation(false);
                }
                return ok;
            }

            // Sometimes IMEs return shorter spans; ask a little more and check suffix.
            CharSequence before2 = ic.getTextBeforeCursor(expected.length() + 8, 0);
            if (before2 != null) {
                String s = before2.toString();
                if (s.endsWith(expected)) {
                    boolean ok = ic.deleteSurroundingText(expected.length(), 0);
                    if (ok) {
                        markEditorMutation(false);
                    }
                    return ok;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            endSelfMutation();
        }
        return false;
    }


    /**
     * Attempt an immediate commit to the current InputConnection.
     * Returns true if the text was committed, false if InputConnection is unavailable.
     *
     * This is used by the floating UI (e.g., AI clipboard) where we must NOT queue
     * a deferred commit when the keyboard is not active.
     */
    public boolean commitToInputNow(String text) {
        if (text == null || text.isEmpty()) return true;
        try {
            return tryCommit(text);
        } catch (Throwable t) {
            return false;
        }
    }

    public void stopNotifyInput() {
        inputNotify = true;
    }

    public void startNotifyInput() {
        inputNotify = false;
    }

    public void flush() {
        if (tryFlush()) {
            return;
        }
        pendingFinishComposing = true;
        scheduleDeferred();
    }

    public boolean isInputLocked() {
        // Auto-unlock if timeout exceeded
        if (inputLock && inputLockStartTime > 0) {
            long elapsed = System.currentTimeMillis() - inputLockStartTime;
            if (elapsed > INPUT_LOCK_TIMEOUT_MS) {
                inputLock = false;
                inputNotify = false;
                inputLockStartTime = 0;
                timeoutHandler.removeCallbacks(lockTimeoutRunnable);
            }
        }
        return inputLock;
    }

    public void startInputLock() {
        inputLock = true;
        inputLockStartTime = System.currentTimeMillis();
        // Schedule timeout
        timeoutHandler.removeCallbacks(lockTimeoutRunnable);
        timeoutHandler.postDelayed(lockTimeoutRunnable, INPUT_LOCK_TIMEOUT_MS);
    }

    public void endInputLock() {
        inputLock = false;
        inputLockStartTime = 0;
        timeoutHandler.removeCallbacks(lockTimeoutRunnable);
    }

    /**
     * Force reset the input lock state. Use this to recover from stuck states.
     */
    public void forceResetLock() {
        inputLock = false;
        inputNotify = false;
        inputLockStartTime = 0;
        timeoutHandler.removeCallbacks(lockTimeoutRunnable);
    }
    private boolean hasPending() {
        return pendingFinishComposing || pendingDeleteBefore > 0 || pendingDeleteAfter > 0 || pendingCommitBuffer.length() > 0;
    }

    private void clearDeferred() {
        pendingFinishComposing = false;
        pendingDeleteBefore = 0;
        pendingDeleteAfter = 0;
        pendingCommitBuffer.setLength(0);
        deferredStartTimeMs = 0;
        deferredHandler.removeCallbacks(deferredRunnable);
        deferredScheduled = false;
    }

    private void scheduleDeferred() {
        if (!hasPending()) {
            return;
        }
        if (!deferredScheduled) {
            deferredScheduled = true;
            deferredHandler.postDelayed(deferredRunnable, DEFERRED_RETRY_DELAY_MS);
        }
    }

    private android.view.inputmethod.InputConnection getIC() {
        if (ims == null) {
            return null;
        }
        try {
            return ims.getCurrentInputConnection();
        } catch (Throwable t) {
            Logger.error("getCurrentInputConnection failed: " + t.getMessage());
            return null;
        }
    }

    private boolean tryDelete(int count) {
        android.view.inputmethod.InputConnection ic = getIC();
        if (ic == null) {
            return false;
        }
        beginSelfMutation();
        try {
            return ic.deleteSurroundingText(count, 0);
        } catch (Throwable t) {
            Logger.error("IMS delete failed: " + t.getMessage());
            return false;
        } finally {
            endSelfMutation();
        }
    }

    private boolean tryDeleteSurrounding(int before, int after) {
        android.view.inputmethod.InputConnection ic = getIC();
        if (ic == null) {
            return false;
        }
        beginSelfMutation();
        try {
            return ic.deleteSurroundingText(Math.max(0, before), Math.max(0, after));
        } catch (Throwable t) {
            Logger.error("IMS deleteSurrounding failed: " + t.getMessage());
            return false;
        } finally {
            endSelfMutation();
        }
    }


    private boolean tryCommit(String text) {
        return tryCommit(text, 1);
    }

    private boolean tryCommit(String text, int newCursorPosition) {
        android.view.inputmethod.InputConnection ic = getIC();
        if (ic == null) {
            return false;
        }
        beginSelfMutation();
        try {
            return ic.commitText(text, newCursorPosition);
        } catch (Throwable t) {
            Logger.error("IMS commit failed: " + t.getMessage());
            return false;
        } finally {
            endSelfMutation();
        }
    }

    private boolean tryCommit(CharSequence text) {
        return tryCommit(text, 1);
    }

    private boolean tryCommit(CharSequence text, int newCursorPosition) {
        android.view.inputmethod.InputConnection ic = getIC();
        if (ic == null) {
            return false;
        }
        beginSelfMutation();
        try {
            ic.commitText(text, newCursorPosition);
            return true;
        } catch (Throwable t) {
            Logger.error("IMS commit failed: " + t.getMessage());
            return false;
        } finally {
            endSelfMutation();
        }
    }

    private boolean tryFlush() {
        android.view.inputmethod.InputConnection ic = getIC();
        if (ic == null) {
            return false;
        }
        beginSelfMutation();
        try {
            ic.finishComposingText();
            return true;
        } catch (Throwable t) {
            Logger.error("IMS flush failed: " + t.getMessage());
            return false;
        } finally {
            endSelfMutation();
        }
    }

    private void fallbackToClipboardAndToast() {
        if (pendingCommitBuffer.length() == 0) {
            return;
        }
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx == null) {
                return;
            }
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("KGPT", pendingCommitBuffer.toString()));
            }
            UiInteractor.getInstance().toastLong("已复制 AI 结果到剪贴板（无法自动写入输入框，请手动粘贴）");
        } catch (Throwable t) {
            Logger.error("Clipboard fallback failed: " + t.getMessage());
        }
    }

}
