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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.listener.ConfigChangeListener;
import tn.eluea.kgpt.listener.ConfigInfoProvider;
import tn.eluea.kgpt.listener.DialogDismissListener;
import tn.eluea.kgpt.core.ui.dialog.DialogType;
import tn.eluea.kgpt.llm.LanguageModelField;

public class UiInteractor {
    public static final String ACTION_DIALOG_RESULT = "tn.eluea.kgpt.DIALOG_RESULT";

    public static final String EXTRA_DIALOG_TYPE = "tn.eluea.kgpt.overlay.DIALOG_TYPE";

    // Optional: request a specific start screen for multi-screen dialogs (e.g. ChooseSubModel hub).
    public static final String EXTRA_START_SCREEN = "tn.eluea.kgpt.overlay.START_SCREEN";

    public static final String EXTRA_CONFIG_SELECTED_MODEL = "tn.eluea.kgpt.config.SELECTED_MODEL";

    public static final String EXTRA_CONFIG_LANGUAGE_MODEL = "tn.eluea.kgpt.config.model";

    public static final String EXTRA_CONFIG_LANGUAGE_MODEL_FIELD = "tn.eluea.kgpt.config.model.%s";

    public static final String EXTRA_WEBVIEW_TITLE = "tn.eluea.kgpt.webview.TITLE";

    public static final String EXTRA_WEBVIEW_URL = "tn.eluea.kgpt.webview.URL";

    public static final String EXTRA_SEARCH_ENGINE = "tn.eluea.kgpt.webview.SEARCH_ENGINE";

    public static final String EXTRA_COMMAND_LIST = "tn.eluea.kgpt.command.LIST";

    public static final String EXTRA_COMMAND_INDEX = "tn.eluea.kgpt.command.INDEX";

    public static final String EXTRA_PATTERN_LIST = "tn.eluea.kgpt.pattern.LIST";

    public static final String EXTRA_OTHER_SETTINGS = "tn.eluea.kgpt.other_settings";

    private Context mContext = null;
    private ConfigInfoProvider mConfigInfoProvider = null;
    private final ArrayList<ConfigChangeListener> mConfigChangeListeners = new ArrayList<>();
    private final ArrayList<DialogDismissListener> mOnDismissListeners = new ArrayList<>();

    private long mLastDialogLaunch = 0L;

    private InputMethodService mInputMethodService;

    private IMSController mIMSController;

    // Main/UI thread handler for safely dispatching UI callbacks.
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // ===== Quick Jump (paste trigger bridge) =====
    public static final String ACTION_QJ_PASTE_TRIGGER = tn.eluea.kgpt.BuildConfig.APPLICATION_ID + ".action.QJ_PASTE_TRIGGER";
    public static final String EXTRA_QJ_TRIGGER = "extra_qj_trigger";

    public static final String ACTION_AI_CLIPBOARD_PASTE_TEXT = tn.eluea.kgpt.BuildConfig.APPLICATION_ID + ".action.AI_CLIPBOARD_PASTE_TEXT";
    public static final String EXTRA_AI_CLIPBOARD_TEXT = "extra_ai_clipboard_text";

    private BroadcastReceiver mQuickJumpPasteReceiver;
    private boolean mQuickJumpPasteReceiverRegistered = false;

    private BroadcastReceiver mAiClipboardPasteReceiver;
    private boolean mAiClipboardPasteReceiverRegistered = false;

    private static UiInteractor instance = null;

    public static UiInteractor getInstance() {
        if (instance == null) {
            throw new RuntimeException("Missing call to UiInteracter.init(Context)");
        }
        return instance;
    }

    private UiInteractor(Context context, ConfigInfoProvider configInfoProvider) {
        mContext = context;
        mConfigInfoProvider = configInfoProvider;
        mIMSController = new IMSController();
    }

    public static void init(Context context) {
        instance = new UiInteractor(context, SPManager.getInstance());
    }

    public Context getContext() {
        return mContext;
    }

    /**
     * Run the given runnable on the main (UI) thread.
     * This is used by controllers that may execute from background threads.
     */
    public void runOnUiThread(Runnable r) {
        if (r == null) return;

        // Never allow an exception thrown from UI callbacks to crash the IME process.
        // This can happen when the focused editor/InputConnection changes mid-request.
        final Runnable safe = () -> {
            try {
                r.run();
            } catch (Throwable t) {
                tn.eluea.kgpt.util.Logger.error("UI callback crashed: " + t);
                tn.eluea.kgpt.util.Logger.log(t);
            }
        };

        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                safe.run();
            } else {
                mMainHandler.post(safe);
            }
        } catch (Throwable ignored) {
        }
    }

    public void onInputMethodCreate(InputMethodService inputMethodService) {
        registerService(inputMethodService);
        mIMSController.registerService(inputMethodService);

        // Register a local (runtime) receiver inside the active IME process.
        // The QuickJump menu UI may be rendered from a different process on some devices,
        // so it can't directly access the IME's InputConnection. This bridge lets the UI
        // ask the IME process to commit the trigger word.
        registerQuickJumpPasteReceiverIfNeeded();
        registerAiClipboardPasteReceiverIfNeeded();
    }

    public void onInputMethodDestroy(InputMethodService inputMethodService) {
        unregisterService(inputMethodService);
        mIMSController.unregisterService(inputMethodService);

        unregisterQuickJumpPasteReceiverIfNeeded();
        unregisterAiClipboardPasteReceiverIfNeeded();
    }

    private void registerQuickJumpPasteReceiverIfNeeded() {
        try {
            if (mQuickJumpPasteReceiverRegistered) return;
            final Context appCtx = (mInputMethodService != null) ? mInputMethodService.getApplicationContext() : mContext;
            if (appCtx == null) return;

            mQuickJumpPasteReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
            if (!tn.eluea.kgpt.provider.BridgeAuth.verify(context, intent)) return;
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (action == null || !ACTION_QJ_PASTE_TRIGGER.equals(action)) return;

                    String trig = null;
                    try { trig = intent.getStringExtra(EXTRA_QJ_TRIGGER); } catch (Throwable ignored) {}
                    if (trig == null) return;
                    trig = trig.trim();
                    if (trig.isEmpty()) return;

                    // Security: accept requests only from KGPT app (action is exported on Android 13+).
                    try {
                        int sendingUid = -1;
                        try {
                            java.lang.reflect.Method mGetUid = android.content.BroadcastReceiver.class.getMethod("getSendingUid");
                            Object v = mGetUid.invoke(this);
                            if (v instanceof Integer) sendingUid = (Integer) v;
                        } catch (Throwable ignoredUid) {
                        }
                        if (sendingUid != -1) {
                            String[] pkgs = null;
                            try { pkgs = context.getPackageManager().getPackagesForUid(sendingUid); } catch (Throwable ignoredPkgs) {}
                            boolean ok = false;
                            if (pkgs != null) {
                                for (String p : pkgs) {
                                    if (tn.eluea.kgpt.BuildConfig.APPLICATION_ID.equals(p)) { ok = true; break; }
                                }
                            }
                            if (!ok) return;
                        }
                    } catch (Throwable ignoredSec) {}

                    // Commit the trigger (NO spaces). We retry briefly because the floating UI may temporarily
                        // detach the InputConnection while it is on top.
                        try {
                            final String finalTrig = trig;
                            try { android.util.Log.i("KGPT-QJ", "QuickJump paste receiver got trig='" + finalTrig + "'"); } catch (Throwable ignoredLog2) {}
                            final Handler h = new Handler(Looper.getMainLooper());
                            final long deadline = SystemClock.uptimeMillis() + 1500;

                            Runnable r = new Runnable() {
                                @Override public void run() {
                                    boolean ok = false;
                                    try {
                                        ok = commitQuickJumpTriggerNoSpace(finalTrig);
                                    } catch (Throwable ignored) {}
                                    if (ok) return;

                                    if (SystemClock.uptimeMillis() < deadline) {
                                        h.postDelayed(this, 60);
                                    }
                                }
                            };
                            h.post(r);
                        } catch (Throwable ignored) {}
                }
            };

            IntentFilter f = new IntentFilter(ACTION_QJ_PASTE_TRIGGER);
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(mQuickJumpPasteReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                appCtx.registerReceiver(mQuickJumpPasteReceiver, f);
            }
            mQuickJumpPasteReceiverRegistered = true;
            try { android.util.Log.i("KGPT-QJ", "QuickJump paste receiver registered (exported)"); } catch (Throwable ignoredLog) {}

        } catch (Throwable ignored) {}
    }

    private void unregisterQuickJumpPasteReceiverIfNeeded() {
        try {
            if (!mQuickJumpPasteReceiverRegistered) return;
            final Context appCtx = (mInputMethodService != null) ? mInputMethodService.getApplicationContext() : mContext;
            if (appCtx == null) return;
            if (mQuickJumpPasteReceiver != null) {
                try { appCtx.unregisterReceiver(mQuickJumpPasteReceiver); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally {
            mQuickJumpPasteReceiver = null;
            mQuickJumpPasteReceiverRegistered = false;
        }
    }

    // ===== AI Clipboard (paste text bridge) =====
    private void registerAiClipboardPasteReceiverIfNeeded() {
        try {
            if (mAiClipboardPasteReceiverRegistered) return;
            final Context appCtx = (mInputMethodService != null) ? mInputMethodService.getApplicationContext() : mContext;
            if (appCtx == null) return;

            mAiClipboardPasteReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
            if (!tn.eluea.kgpt.provider.BridgeAuth.verify(context, intent)) return;
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (action == null || !ACTION_AI_CLIPBOARD_PASTE_TEXT.equals(action)) return;

                    String text = null;
                    try { text = intent.getStringExtra(EXTRA_AI_CLIPBOARD_TEXT); } catch (Throwable ignored) {}
                    if (text == null) text = "";

                    // Security: accept requests only from KGPT app.
                    try {
                        int sendingUid = -1;
                        try {
                            java.lang.reflect.Method mGetUid = android.content.BroadcastReceiver.class.getMethod("getSendingUid");
                            Object v = mGetUid.invoke(this);
                            if (v instanceof Integer) sendingUid = (Integer) v;
                        } catch (Throwable ignoredUid) { }
                        if (sendingUid != -1) {
                            String[] pkgs = null;
                            try { pkgs = context.getPackageManager().getPackagesForUid(sendingUid); } catch (Throwable ignoredPkgs) {}
                            boolean ok = false;
                            if (pkgs != null) {
                                for (String p : pkgs) {
                                    if (tn.eluea.kgpt.BuildConfig.APPLICATION_ID.equals(p)) { ok = true; break; }
                                }
                            }
                            if (!ok) return;
                        }
                    } catch (Throwable ignoredSec) {}

                    // Commit the text. We retry briefly because the overlay may temporarily detach InputConnection.
                    try {
                        final String finalText = text;
                        final Handler h = new Handler(Looper.getMainLooper());
                        final long deadline = SystemClock.uptimeMillis() + 2000;

                        Runnable r = new Runnable() {
                            @Override public void run() {
                                boolean ok = false;
                                try {
                                    ok = commitAiClipboardText(finalText);
                                } catch (Throwable ignored) {}
                                if (ok) return;

                                if (SystemClock.uptimeMillis() < deadline) {
                                    h.postDelayed(this, 80);
                                }
                            }
                        };
                        h.post(r);
                    } catch (Throwable ignoredOuter) {}
                }
            };

            IntentFilter f = new IntentFilter(ACTION_AI_CLIPBOARD_PASTE_TEXT);
            // Receiver lives inside IME process; registered at runtime.
            try {
                appCtx.registerReceiver(mAiClipboardPasteReceiver, f);
            } catch (Throwable e) {
                // Android 13+ may require exported flags in some contexts; try both.
                try {
                    appCtx.registerReceiver(mAiClipboardPasteReceiver, f, Context.RECEIVER_EXPORTED);
                } catch (Throwable ignored) {
                    try {
                        appCtx.registerReceiver(mAiClipboardPasteReceiver, f, Context.RECEIVER_NOT_EXPORTED);
                    } catch (Throwable ignored2) {}
                }
            }
            mAiClipboardPasteReceiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void unregisterAiClipboardPasteReceiverIfNeeded() {
        try {
            if (!mAiClipboardPasteReceiverRegistered) return;
            final Context appCtx = (mInputMethodService != null) ? mInputMethodService.getApplicationContext() : mContext;
            if (appCtx == null) return;
            try { appCtx.unregisterReceiver(mAiClipboardPasteReceiver); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        } finally {
            mAiClipboardPasteReceiverRegistered = false;
            mAiClipboardPasteReceiver = null;
        }
    }

    private boolean commitAiClipboardText(String text) {
        if (text == null) text = "";
        if (mInputMethodService == null) return false;

        try {
            mInputMethodService.requestShowSelf(0);
        } catch (Throwable ignored) {}

        // Prefer deferred commit
        try {
            if (mIMSController != null) {
                mIMSController.commit(text);
                return true;
            }
        } catch (Throwable e) {
            tn.eluea.kgpt.util.Logger.error("KGPT-AICB: commitAiClipboardText via IMSController failed: " + e);
        }

        // Fallback direct commit
        try {
            InputConnection ic = mInputMethodService.getCurrentInputConnection();
            if (ic == null) return false;
            ic.commitText(text, 1);
            return true;
        } catch (Throwable e) {
            tn.eluea.kgpt.util.Logger.error("KGPT-AICB: commitAiClipboardText direct commit failed: " + e);
            return false;
        }
    }

    /**
     * Commit QuickJump URL trigger word into the currently focused editor without adding spaces.
     * We try to place the cursor at the end of the current line (before trailing whitespace).
     */
    private boolean commitQuickJumpTriggerNoSpace(String trig) {
        if (trig == null) return false;
        trig = trig.trim();
        if (trig.isEmpty()) return false;

        if (mInputMethodService == null) return false;

        // When QuickJump is shown as an Activity, Android may hide the IME.
        // Ask the IME service to show itself again before committing.
        try {
            mInputMethodService.requestShowSelf(0);
        } catch (Throwable ignored) {
        }

        // Use IMSController's deferred commit mechanism so we can handle the case where
        // the InputConnection is not immediately available right after focus returns.
        try {
            if (mIMSController != null) {
                mIMSController.commit(trig); // commit WITHOUT adding a space
                return true;
            }
        } catch (Throwable e) {
            tn.eluea.kgpt.util.Logger.error("KGPT-QJ: commitQuickJumpTriggerNoSpace via IMSController failed: " + e);
        }

        // Fallback: commit directly if InputConnection is already available.
        try {
            InputConnection ic = mInputMethodService.getCurrentInputConnection();
            if (ic == null) return false;
            ic.commitText(trig, 1);
            return true;
        } catch (Throwable e) {
            tn.eluea.kgpt.util.Logger.error("KGPT-QJ: commitQuickJumpTriggerNoSpace direct commit failed: " + e);
            return false;
        }
    }

    private final BroadcastReceiver mDialogResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!tn.eluea.kgpt.provider.BridgeAuth.verify(context, intent)) return;
            if (ACTION_DIALOG_RESULT.equals(intent.getAction())) {
                tn.eluea.kgpt.util.Logger.log("Got result");
                boolean isPrompt = false;
                boolean isCommand = false;
                boolean isPattern = false;

                try {
                    if (!mConfigChangeListeners.isEmpty() && intent.getExtras() != null) {
                        for (String key : intent.getExtras().keySet()) {
                            if (key == null)
                                continue;

                            switch (key) {
                                case EXTRA_CONFIG_SELECTED_MODEL:
                                    String selectedModelStr = intent.getStringExtra(EXTRA_CONFIG_SELECTED_MODEL);
                                    if (selectedModelStr != null) {
                                        try {
                                            LanguageModel selectedLanguageModel = LanguageModel
                                                    .valueOf(selectedModelStr);
                                            mConfigChangeListeners
                                                    .forEach((l) -> l.onLanguageModelChange(selectedLanguageModel));
                                            isPrompt = true;
                                        } catch (IllegalArgumentException e) {
                                            tn.eluea.kgpt.util.Logger
                                                    .log("Invalid language model: " + selectedModelStr);
                                        }
                                    }
                                    break;
                                case EXTRA_CONFIG_LANGUAGE_MODEL:
                                    Bundle bundle = intent.getBundleExtra(EXTRA_CONFIG_LANGUAGE_MODEL);
                                    if (bundle != null) {
                                        for (String modelName : bundle.keySet()) {
                                            if (modelName == null)
                                                continue;
                                            try {
                                                LanguageModel configuredlanguageModel = LanguageModel
                                                        .valueOf(modelName);
                                                Bundle languageModelBundle = bundle.getBundle(modelName);
                                                if (languageModelBundle == null)
                                                    continue;

                                                for (LanguageModelField field : LanguageModelField.values()) {
                                                    if (languageModelBundle.containsKey(field.name)) {
                                                        String fieldValue = languageModelBundle.getString(field.name);
                                                        mConfigChangeListeners
                                                                .forEach(l -> l.onLanguageModelFieldChange(
                                                                        configuredlanguageModel,
                                                                        field, fieldValue));
                                                    }
                                                }
                                            } catch (IllegalArgumentException e) {
                                                tn.eluea.kgpt.util.Logger.log("Invalid model name: " + modelName);
                                            }
                                        }
                                        isPrompt = true;
                                    }
                                    break;
                                case EXTRA_COMMAND_LIST:
                                    String commandsRaw = intent.getStringExtra(EXTRA_COMMAND_LIST);
                                    if (commandsRaw != null) {
                                        mConfigChangeListeners.forEach((l) -> l.onCommandsChange(commandsRaw));
                                        isCommand = true;
                                    }
                                    break;
                                case EXTRA_PATTERN_LIST:
                                    String patternsRaw = intent.getStringExtra(EXTRA_PATTERN_LIST);
                                    if (patternsRaw != null) {
                                        mConfigChangeListeners.forEach((l) -> l.onPatternsChange(patternsRaw));
                                        isPattern = true;
                                    }
                                    break;
                                case EXTRA_OTHER_SETTINGS:
                                    tn.eluea.kgpt.util.Logger.log("Got other result");
                                    Bundle otherSettings = intent.getBundleExtra(EXTRA_OTHER_SETTINGS);
                                    if (otherSettings != null) {
                                        mConfigChangeListeners.forEach((l) -> l.onOtherSettingsChange(otherSettings));
                                    }
                                    break;
                            }
                        }
                    }
                } catch (Exception e) {
                    tn.eluea.kgpt.util.Logger.log("Error processing dialog result: " + e.getMessage());
                }

                final boolean finalIsPrompt = isPrompt;
                final boolean finalIsCommand = isCommand;
                final boolean finalIsPattern = isPattern;
                mOnDismissListeners.forEach((l) -> l.onDismiss(finalIsPrompt, finalIsCommand, finalIsPattern));
            }
        }
    };

    public boolean showChoseModelDialog() {
        if (isDialogOnCooldown()) {
            return false;
        }

        tn.eluea.kgpt.util.Logger.log("Launching configure dialog");
        mContext.startActivity(getOverlayIntent(DialogType.ChoseModel));
        return true;
    }

    public boolean showWebSearchDialog(String title, String url) {
        if (isDialogOnCooldown()) {
            return false;
        }

        Intent intent = getOverlayIntent(DialogType.WebSearch, false);
        intent.putExtra(EXTRA_WEBVIEW_TITLE, title);
        intent.putExtra(EXTRA_WEBVIEW_URL, url);
        // Send search engine preference so WebSearchActivity doesn't need SPManager
        intent.putExtra(EXTRA_SEARCH_ENGINE, SPManager.getInstance().getSearchEngine());

        tn.eluea.kgpt.util.Logger.log("Launching web search");
        mContext.startActivity(intent);

        return true;
    }

    public boolean showEditCommandsDialog() {
        if (isDialogOnCooldown()) {
            return false;
        }

        tn.eluea.kgpt.util.Logger.log("Launching commands edit");
        mContext.startActivity(getOverlayIntent(DialogType.EditCommandsList));

        return true;
    }

    public boolean showSettingsDialog() {
        if (isDialogOnCooldown()) {
            return false;
        }

        tn.eluea.kgpt.util.Logger.log("Launching settings");
        mContext.startActivity(getOverlayIntent(DialogType.Settings));

        return true;
    }
    public boolean showChooseRoleDialog() {
        if (isDialogOnCooldown()) return false;
        tn.eluea.kgpt.util.Logger.log("Launching choose role dialog");
        mContext.startActivity(getOverlayIntent(tn.eluea.kgpt.core.ui.dialog.DialogType.ChooseRole, false));
        return true;
    }

    public boolean showChooseSubModelDialog() {
        if (isDialogOnCooldown()) return false;
        tn.eluea.kgpt.util.Logger.log("Launching choose model dialog");
        mContext.startActivity(getOverlayIntent(tn.eluea.kgpt.core.ui.dialog.DialogType.ChooseSubModel, false));
        return true;
    }


    /**
     * Open the ChooseSubModel hub directly on the AI Clipboard screen.
     * Used by the AIClipboard trigger.
     */
    public boolean showAiClipboardDialog() {
        if (isDialogOnCooldown()) return false;
        tn.eluea.kgpt.util.Logger.log("Launching AI Clipboard dialog");
        android.content.Intent intent = getOverlayIntent(tn.eluea.kgpt.core.ui.dialog.DialogType.ChooseSubModel, false);
        intent.putExtra(EXTRA_START_SCREEN, "AI_CLIPBOARD");
        mContext.startActivity(intent);
        return true;
    }

    /**
     * Open the ChooseSubModel hub directly on the Quick Jump screen.
     * Used by the QuickJumpMenu trigger.
     */
    public boolean showQuickJumpDialog() {
        if (isDialogOnCooldown()) return false;
        tn.eluea.kgpt.util.Logger.log("Launching Quick Jump dialog");
        android.content.Intent intent = getOverlayIntent(tn.eluea.kgpt.core.ui.dialog.DialogType.ChooseSubModel, false);
        intent.putExtra(EXTRA_START_SCREEN, "QUICK_JUMP");
        mContext.startActivity(intent);
        return true;
    }






    private Intent getOverlayIntent(DialogType dialogType) {
        return getOverlayIntent(dialogType, true);
    }

    private Intent getOverlayIntent(DialogType dialogType, boolean includeSp) {
        Intent intent = new Intent("tn.eluea.kgpt.OVERLAY");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_DIALOG_TYPE, dialogType.name());
        if (includeSp) {
            String rawCommands = SPManager.getInstance().getGenerativeAICommandsRaw();
            String rawPatterns = SPManager.getInstance().getParsePatternsRaw();

            intent.putExtra(EXTRA_COMMAND_LIST, rawCommands);
            intent.putExtra(EXTRA_PATTERN_LIST, rawPatterns);
            intent.putExtra(EXTRA_CONFIG_LANGUAGE_MODEL, mConfigInfoProvider.getConfigBundle());
            intent.putExtra(EXTRA_CONFIG_SELECTED_MODEL,
                    mConfigInfoProvider.getLanguageModel().name());
            intent.putExtra(EXTRA_OTHER_SETTINGS,
                    mConfigInfoProvider.getOtherSettings());
        }

        return intent;
    }

    public void registerConfigChangeListener(ConfigChangeListener listener) {
        mConfigChangeListeners.add(listener);
    }

    public void unregisterConfigChangeListener(ConfigChangeListener listener) {
        mConfigChangeListeners.remove(listener);
    }

    public void registerOnDismissListener(DialogDismissListener listener) {
        mOnDismissListeners.add(listener);
    }

    public void unregisterOnDismissListener(DialogDismissListener listener) {
        mOnDismissListeners.remove(listener);
    }

    public void registerService(InputMethodService inputMethodService) {
        mInputMethodService = inputMethodService;
        IntentFilter filter = new IntentFilter(ACTION_DIALOG_RESULT);
        ContextCompat.registerReceiver(inputMethodService.getApplicationContext(), mDialogResultReceiver,
                filter, ContextCompat.RECEIVER_EXPORTED);
    }

    public void unregisterService(InputMethodService inputMethodService) {
        inputMethodService.getApplicationContext().unregisterReceiver(mDialogResultReceiver);
        if (inputMethodService != mInputMethodService) {
            tn.eluea.kgpt.util.Logger.log("[W] inputMethodService do not correspond in unregisterService");
            mInputMethodService = null;
        }
    }

    private boolean isDialogOnCooldown() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - mLastDialogLaunch < 3000) {
            tn.eluea.kgpt.util.Logger
                    .log("Preventing spam dialog launch. (" + currentTime + " ~ " + mLastDialogLaunch + ")");
            return true;
        }
        mLastDialogLaunch = currentTime;
        return false;
    }

    public void toastShort(String message) {
        Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
    }

    public void toastLong(String message) {
        Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
    }

    public void post(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    public InputConnection getInputConnection() {
        return mInputMethodService.getCurrentInputConnection();
    }

    public IMSController getIMSController() {
        return mIMSController;
    }

    public InputMethodService getIMS() {
        return mInputMethodService;
    }

    /**
     * Launch an app using ComponentName for reliable launching.
     * Uses the same approach as ActivityLauncher for maximum compatibility.
     * 
     * @param packageName  The package name of the app
     * @param activityName The activity class name (can be null for fallback)
     */
    public boolean launchApp(String packageName, String activityName) {
        android.util.Log.d("KGPT_AppTrigger", "launchApp() called with: " + packageName + "/" + activityName);

        if (packageName == null || packageName.isEmpty()) {
            android.util.Log.e("KGPT_AppTrigger", "launchApp: packageName is null or empty");
            tn.eluea.kgpt.util.Logger.log("launchApp: packageName is null or empty");
            return false;
        }

        android.util.Log.d("KGPT_AppTrigger", "launchApp: Attempting to launch " + packageName);
        tn.eluea.kgpt.util.Logger.log("launchApp: Attempting to launch " + packageName + "/" + activityName);

        try {
            // Method 1: Use ComponentName directly (ActivityLauncher approach)
            if (activityName != null && !activityName.isEmpty()) {
                android.util.Log.d("KGPT_AppTrigger", "Trying ComponentName: " + packageName + "/" + activityName);
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName(packageName, activityName));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mContext.startActivity(intent);
                android.util.Log.d("KGPT_AppTrigger", "SUCCESS via ComponentName");
                tn.eluea.kgpt.util.Logger.log("launchApp: Success via ComponentName");
                return true;
            }
        } catch (Exception e1) {
            android.util.Log.d("KGPT_AppTrigger", "ComponentName failed: " + e1.getMessage());
            tn.eluea.kgpt.util.Logger.log("launchApp: ComponentName failed: " + e1.getMessage());
        }

        try {
            // Method 2: Try getLaunchIntentForPackage
            android.util.Log.d("KGPT_AppTrigger", "Trying getLaunchIntentForPackage");
            PackageManager pm = mContext.getPackageManager();
            Intent defaultIntent = pm.getLaunchIntentForPackage(packageName);

            if (defaultIntent != null) {
                defaultIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mContext.startActivity(defaultIntent);
                android.util.Log.d("KGPT_AppTrigger", "SUCCESS via getLaunchIntentForPackage");
                tn.eluea.kgpt.util.Logger.log("launchApp: Success via getLaunchIntentForPackage");
                return true;
            }
        } catch (Exception e2) {
            android.util.Log.e("KGPT_AppTrigger", "getLaunchIntentForPackage failed: " + e2.getMessage());
        }

        try {
            // Method 3: Try direct intent with ACTION_MAIN and CATEGORY_LAUNCHER
            android.util.Log.d("KGPT_AppTrigger", "Trying ACTION_MAIN intent");
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(packageName);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mContext.startActivity(intent);
            android.util.Log.d("KGPT_AppTrigger", "SUCCESS via ACTION_MAIN");
            tn.eluea.kgpt.util.Logger.log("launchApp: Success via ACTION_MAIN");
            return true;
        } catch (Exception e3) {
            android.util.Log.e("KGPT_AppTrigger", "ACTION_MAIN failed: " + e3.getMessage());
        }

        android.util.Log.e("KGPT_AppTrigger", "All methods failed for " + packageName);
        return false;
    }

    /**
     * Launch an app by package name only (legacy method for backward compatibility)
     */
    public boolean launchApp(String packageName) {
        return launchApp(packageName, null);
    }
}