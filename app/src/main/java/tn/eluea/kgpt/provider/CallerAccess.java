package tn.eluea.kgpt.provider;

import android.content.Context;
import android.os.Process;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

/** Binder UID is the authority; package names supplied in an Intent are never trusted. */
public final class CallerAccess {
    private CallerAccess() { }
    public static boolean isModuleOrEnabledIme(Context context, int uid) {
        if (uid == Process.myUid()) return true;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        InputMethodManager manager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager == null) return false;
        for (InputMethodInfo ime : manager.getEnabledInputMethodList())
            for (String name : packages) if (ime.getPackageName().equals(name)) return true;
        return false;
    }
    public static void enforce(Context context, int uid, String key) {
        if (isModuleOrEnabledIme(context, uid)) return;
        // The framework clipboard hook only needs the clipboard history, never credentials.
        if (uid == Process.SYSTEM_UID && "ai_clipboard_history_v1".equals(key)) return;
        throw new SecurityException("Caller cannot access KGPT configuration");
    }
}
