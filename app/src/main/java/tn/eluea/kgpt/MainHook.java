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
package tn.eluea.kgpt;

import android.annotation.SuppressLint;
import android.app.Service;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import tn.eluea.kgpt.hook.HookManager;
import tn.eluea.kgpt.hook.MethodHook;
import tn.eluea.kgpt.hook.TextSelectionHook;
import tn.eluea.kgpt.hook.ClipboardHook;
import tn.eluea.kgpt.provider.XposedConfigReader;
import tn.eluea.kgpt.core.di.ServiceLocator;
import tn.eluea.kgpt.interrupt.InterruptGestureDetector;
import tn.eluea.kgpt.ui.IMSController;
import tn.eluea.kgpt.ui.UiInteractor;

public class MainHook implements IXposedHookLoadPackage {
    private static Context applicationContext = null;

    private KGPTBrain brain;

    private HookManager hookManager;

    private Class<?> inputConnectionClass = null;

    private Class<?> inputMethodServiceClass = null;
    
    // Performance optimization: Cache to avoid redundant re-hooking
    private Class<?> lastHookedInputConnectionClass = null;
    private long lastHookTime = 0;
    private static final long MIN_HOOK_INTERVAL_MS = 500; // Minimum interval between hooks

    // Some IMEs run in secondary processes or override onCreate without calling super().
    // Use multiple entry points and guard to avoid duplicate init/hook.
    private static final Set<String> sHookedImeClasses = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Integer> sCreatedImeInstances = Collections.synchronizedSet(new HashSet<>());


    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
if (lpparam.packageName.equals("tn.eluea.kgpt")) {
            MainHook.log("Hooking own module for status check");
            // Hook the module status check method
            XposedHelpers.findAndHookMethod(
                    "tn.eluea.kgpt.ui.main.fragments.HomeFragment",
                    lpparam.classLoader,
                    "isModuleActiveInternal",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });
            return;
        }

        MainHook.log("Loading KGPT for package " + lpparam.packageName);

        if ("android".equals(lpparam.packageName)) {
            // System framework: only install global clipboard capture
            ClipboardHook.hook(lpparam);
            return;
        }

        // Log XSharedPreferences status early
        MainHook.log("XSharedPreferences available: " + XposedConfigReader.isAvailable());
        MainHook.log(XposedConfigReader.getDebugInfo());

        // Hook text selection for AI actions (works in any app)
        TextSelectionHook.hook(lpparam);

        hookKeyboard(lpparam);
    }

    private void ensureInitialized(Context applicationContext) {
    if (MainHook.applicationContext == null) {
        MainHook.applicationContext = applicationContext;

        SPManager.init(applicationContext);
        UiInteractor.init(applicationContext);

    }
    if (brain == null) brain = new KGPTBrain(applicationContext);
}

private void onImeCreated(InputMethodService ims, String source) {
    if (ims == null) return;
    try {
        int id = System.identityHashCode(ims);
        if (sCreatedImeInstances.contains(id)) {
            return;
        }
        sCreatedImeInstances.add(id);
    } catch (Throwable ignored) {}

    MainHook.log("InputMethodService created via " + source);

    ensureInitialized(ims.getApplicationContext());

    try { IMSController.getInstance().resetShadow(); } catch (Throwable ignored) {}

    UiInteractor.getInstance().onInputMethodCreate(ims);

    inputMethodServiceClass = ims.getClass();
    MainHook.log("InputMethodService : " + inputMethodServiceClass.getName());

    // Hook methods only once per IME class to avoid duplicate hooks
    if (sHookedImeClasses.add(inputMethodServiceClass.getName())) {
        hookMethodService();
    }
}

private void hookKeyboard(XC_LoadPackage.LoadPackageParam lpparam) {
        hookManager = new HookManager();

        // Robust entry-point: Service.attach() is always called for services, even if onCreate is overridden.
        XposedBridge.hookAllMethods(Service.class, "attach", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!(param.thisObject instanceof InputMethodService)) return;
                InputMethodService ims = (InputMethodService) param.thisObject;
                onImeCreated(ims, "Service.attach");
            }
        });

        XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                InputMethodService ims = (InputMethodService) param.thisObject;
                onImeCreated(ims, "InputMethodService.onCreate");
            }
        });

        XposedHelpers.findAndHookMethod(InputMethodService.class, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                MainHook.log("InputMethodService onDestroy");
                InputMethodService ims = (InputMethodService) param.thisObject;
                UiInteractor.getInstance().onInputMethodDestroy(ims);
                
                // Clean up brain resources
                if (brain != null) {
                    brain.destroy();
                    brain = null;
                }
                
                sCreatedImeInstances.remove(System.identityHashCode(ims));

                // Reset hook cache
                lastHookedInputConnectionClass = null;
                lastHookTime = 0;
            }
        });

        XposedHelpers.findAndHookMethod(InputMethodService.class, "onStartInput", EditorInfo.class, boolean.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (brain != null) brain.onEditorFinished();
                        IMSController.getInstance().invalidateEditor();
                    }
                });

        XposedHelpers.findAndHookMethod(InputMethodService.class, "onFinishInput", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (brain != null) brain.onEditorFinished();
                IMSController.getInstance().invalidateEditor();
                MainHook.log("InputMethodService onFinishInput");
            }
        });

        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                Application.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Application app = (Application) param.args[0];
                        ensureInitialized(app.getApplicationContext());
                    }
                });
    }

    private void hookMethodService() {
        hookManager.hook(inputMethodServiceClass, "onUpdateSelection",
                new Class<?>[] { int.class, int.class, int.class, int.class, int.class, int.class },
                MethodHook.after(param -> {
                    InputMethodService ims = (InputMethodService) param.thisObject;
                    EditorInfo editor = ims.getCurrentInputEditorInfo();
                    if (editor == null) return;
                    String packageName = editor.packageName;
                    if (BuildConfig.APPLICATION_ID.equals(packageName)) {
                        return;
                    }

                    int oldSelStart = (int) param.args[0];
                    int oldSelEnd = (int) param.args[1];
                    int newSelStart = (int) param.args[2];
                    int newSelEnd = (int) param.args[3];

                    // Notify IMSController for text parsing
                    IMSController.getInstance().onUpdateSelection(
                            oldSelStart,
                            oldSelEnd,
                            newSelStart,
                            newSelEnd,
                            (int) param.args[4],
                            (int) param.args[5]);

                    // Notify SelectionHandler for text actions
                    if (brain != null && brain.getSelectionHandler() != null) {
                        brain.getSelectionHandler().onSelectionChanged(
                                ims, oldSelStart, oldSelEnd, newSelStart, newSelEnd);
                    }
                }));

        hookManager.hook(inputMethodServiceClass, "onStartInput",
                new Class<?>[] { EditorInfo.class, boolean.class }, MethodHook.after(param -> {
                    InputMethodService ims = (InputMethodService) param.thisObject;
                        
                    // Performance optimization: Skip if InputConnection hasn't changed
                    if (ims.getCurrentInputConnection() == null) {
                        return;
                    }
                    
                    Class<?> newInputConnectionClass = ims.getCurrentInputConnection().getClass();
                    long currentTime = System.currentTimeMillis();
                    
                    // Skip re-hooking if same class and within minimum interval
                    if (newInputConnectionClass.equals(lastHookedInputConnectionClass) 
                            && (currentTime - lastHookTime) < MIN_HOOK_INTERVAL_MS) {
                        return;
                    }
                    
                    // Only unhook and rehook if the class actually changed
                    if (!newInputConnectionClass.equals(lastHookedInputConnectionClass)) {
                        hookManager.unhook(m -> inputConnectionClass != null
                                && m.getDeclaringClass().isAssignableFrom(inputConnectionClass));
                        
                        MainHook.log("InputMethodService onStartInput");
                        inputMethodServiceClass = ims.getClass();
                        inputConnectionClass = newInputConnectionClass;
                        lastHookedInputConnectionClass = newInputConnectionClass;
                        MainHook.log("InputMethodService InputConnection : " + inputConnectionClass.getName());
                        
                        hookInputConnection();
                    }
                    
                    lastHookTime = currentTime;
                }));
        MainHook.log("Done hooking InputMethodService : " + inputMethodServiceClass.getName());
    }

    @SuppressLint("ObsoleteSdkInt")
    private void hookInputConnection() {
        // IMPORTANT:
        // Do NOT forcefully cancel InputConnection methods when "input lock" is enabled.
        // Some IMEs (e.g. Baidu) rely on these calls for their internal state machine.
        // Returning false / short-circuiting can crash the IME process or hide the keyboard.
        // The lock is used only to suppress our own trigger parsing / expensive text updates.
        XC_MethodHook gateOnly = new MethodHook(null, null);

        // Trigger parsing directly from InputConnection events.
        // Some keyboards override InputMethodService.onUpdateSelection() without calling super(),
        // so relying only on onUpdateSelection can break keyword triggers (AI 触发器).
        XC_MethodHook gateAndUpdate = new MethodHook(null, param -> {
            // When input is locked (AI is writing), we MUST NOT run expensive parsing.
            // However, we still allow a very lightweight "interrupt gesture" to stop/pause output.
            if (IMSController.getInstance().isInputLocked()
                    || ServiceLocator.getInstance().getGenerativeAIController().isRequestRunning()) {
                try {
                    // Avoid false positives: ignore our own commits/deletes.
                    if (IMSController.getInstance().isSelfMutationInProgress()) {
                        return;
                    }
                } catch (Throwable ignored) {}

                try {
                    SPManager sp = SPManager.getInstance();
                    if (!sp.getInterruptAiEnabled()) {
                        return;
                    }

                    final int gesture = sp.getInterruptAiGesture();
                    final int mode = sp.getInterruptAiMode();
                    final String customTrig = (gesture == SPManager.INTERRUPT_GESTURE_CUSTOM)
                            ? sp.getInterruptAiCustomTrigger() : null;
                    final String m = param.method.getName();

                    InterruptGestureDetector.Hit hit = null;
                    if ("commitText".equals(m) || "setComposingText".equals(m)) {
                        CharSequence cs = (CharSequence) param.args[0];
                        boolean composing = "setComposingText".equals(m);
                        hit = InterruptGestureDetector.onTextEvent(cs, composing, gesture, customTrig);
                        if (gesture == SPManager.INTERRUPT_GESTURE_DOUBLE_SPACE && hit != null && hit.stage == 1) {
                            try {
                                tn.eluea.kgpt.util.AiDiagnostics.append("INTERRUPT_TRACE",
                                        "space stage=1 source=" + m + " textLen=" + (cs == null ? 0 : cs.length()));
                            } catch (Throwable ignored3) {}
                        }

                    } else if ("finishComposingText".equals(m)) {
                        InterruptGestureDetector.onFinishComposing();
                    } else if ("deleteSurroundingText".equals(m) || "deleteSurroundingTextInCodePoints".equals(m)) {
                        int before = (int) param.args[0];
                        int after = (int) param.args[1];
                        hit = InterruptGestureDetector.onDeleteEvent(before, after, gesture);
                        // If user reports "double backspace doesn't work", we need to know whether
                        // the IME actually calls deleteSurroundingText under locked state.
                        if (gesture == SPManager.INTERRUPT_GESTURE_DOUBLE_BACKSPACE && hit != null && hit.stage == 1) {
                            try {
                                tn.eluea.kgpt.util.AiDiagnostics.append("INTERRUPT_TRACE",
                                        "bksp stage=1 source=" + m + " before=" + before + " after=" + after);
                            } catch (Throwable ignored3) {}
                        }
                    } else if ("sendKeyEvent".equals(m)) {
                        try {
                            android.view.KeyEvent ev = (android.view.KeyEvent) param.args[0];
                            hit = InterruptGestureDetector.onKeyEvent(ev, gesture);
                            if (gesture == SPManager.INTERRUPT_GESTURE_DOUBLE_SPACE && hit != null && hit.stage == 1) {
                                tn.eluea.kgpt.util.AiDiagnostics.append("INTERRUPT_TRACE",
                                        "space stage=1 source=sendKeyEvent key=" + (ev == null ? "null" : String.valueOf(ev.getKeyCode())));
                            }

                            if (gesture == SPManager.INTERRUPT_GESTURE_DOUBLE_BACKSPACE && hit != null && hit.stage == 1) {
                                tn.eluea.kgpt.util.AiDiagnostics.append("INTERRUPT_TRACE",
                                        "bksp stage=1 source=sendKeyEvent key=" + (ev == null ? "null" : String.valueOf(ev.getKeyCode())));
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    if (hit != null && hit.hit) {
                        try {
                            tn.eluea.kgpt.util.AiDiagnostics.append("INTERRUPT_GESTURE",
                                    "mode=" + mode + " gesture=" + gesture + " trig=" + String.valueOf(hit.triggerText));
                            tn.eluea.kgpt.util.Logger.log("[INTERRUPT] gesture hit mode=" + mode + " trig=" + hit.triggerText);
                        } catch (Throwable ignored3) {}
                        try {
                            ServiceLocator.getInstance().getAiResponseManager()
                                    .requestUserInterrupt(mode, hit.deleteBeforeCursor, hit.triggerText);
                        } catch (Throwable ignored2) {}
                    }
                } catch (Throwable ignored) {}
                return;
            }
try {
    String m = param.method.getName();
    if ("commitText".equals(m) || "setComposingText".equals(m)) {
        CharSequence cs = (CharSequence) param.args[0];
        boolean composing = "setComposingText".equals(m);
        IMSController.getInstance().onInputEventText(cs, composing);
    } else if ("finishComposingText".equals(m)) {
        IMSController.getInstance().onInputEventFinishComposing();
    } else if ("deleteSurroundingText".equals(m) || "deleteSurroundingTextInCodePoints".equals(m)) {
        int before = (int) param.args[0];
        int after = (int) param.args[1];
        IMSController.getInstance().onInputEventDelete(before, after);
    } else if (Build.VERSION.SDK_INT >= 34 && "replaceText".equals(m)) {
        int start = (int) param.args[0];
        int end = (int) param.args[1];
        CharSequence cs = (CharSequence) param.args[2];
        IMSController.getInstance().onInputEventReplace(start, end, cs);
    }

    IMSController.getInstance().requestTextUpdateFromInputEvent(
            (android.view.inputmethod.InputConnection) param.thisObject);
} catch (Throwable ignored) {
}
        });

        hookManager.hook(inputConnectionClass, "commitText",
                new Class<?>[] { CharSequence.class, int.class }, gateAndUpdate);
        hookManager.hook(inputConnectionClass, "commitCorrection",
                new Class<?>[] { android.view.inputmethod.CorrectionInfo.class }, gateOnly);
        hookManager.hook(inputConnectionClass, "commitCompletion",
                new Class<?>[] { android.view.inputmethod.CompletionInfo.class }, gateOnly);
        hookManager.hook(inputConnectionClass, "setComposingText",
                new Class<?>[] { CharSequence.class, int.class }, gateAndUpdate);
        hookManager.hook(inputConnectionClass, "finishComposingText",
                new Class<?>[] {}, gateAndUpdate);
        hookManager.hook(inputConnectionClass, "deleteSurroundingText",
                new Class<?>[] { int.class, int.class }, gateAndUpdate);

        // Some IMEs dispatch backspace via sendKeyEvent(KEYCODE_DEL) instead of deleteSurroundingText.
        try {
            hookManager.hook(inputConnectionClass, "sendKeyEvent",
                    new Class<?>[] { android.view.KeyEvent.class }, gateAndUpdate);
        } catch (Throwable t) {
            MainHook.log("InputConnection has no sendKeyEvent(KeyEvent): " + (t == null ? "" : t.getMessage()));
        }

if (Build.VERSION.SDK_INT >= 24) {
            hookManager.hook(inputConnectionClass, "deleteSurroundingTextInCodePoints",
                    new Class<?>[] { int.class, int.class }, gateAndUpdate);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            hookManager.hook(inputConnectionClass, "commitText",
                    new Class<?>[] { CharSequence.class, int.class,
                            android.view.inputmethod.TextAttribute.class },
                    gateAndUpdate);
        }
        if (Build.VERSION.SDK_INT >= 34) {
            hookManager.hook(inputConnectionClass, "replaceText",
                    new Class<?>[] { int.class, int.class, CharSequence.class, int.class,
                            android.view.inputmethod.TextAttribute.class },
                    gateAndUpdate);
        }

        MainHook.log("Done hooking InputConnection : " + inputConnectionClass.getName());
    }

    // Flag to check if we're in Xposed context
    private static final boolean IS_XPOSED_CONTEXT;
    static {
        boolean xposedAvailable = false;
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            xposedAvailable = true;
        } catch (ClassNotFoundException e) {
            xposedAvailable = false;
        }
        IS_XPOSED_CONTEXT = xposedAvailable;
    }

    public static void logST() {
        log(Log.getStackTraceString(new Throwable()));
    }

    public static void log(String message) {
        tn.eluea.kgpt.util.Logger.log(message);
    }

    public static void log(Throwable t) {
        // In app context, use Android Log
        if (!IS_XPOSED_CONTEXT) {
            Log.e("KGPT", "Error", t);
            return;
        }

        // In Xposed context, use XposedBridge.log
        XposedBridge.log(t);

        UiInteractor.getInstance().post(
                () -> UiInteractor.getInstance().toastLong(t.getClass().getSimpleName() + " : " + t.getMessage()));
    }
}
