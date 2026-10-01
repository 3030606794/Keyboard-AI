package tn.eluea.kgpt.provider;

import android.content.Context;
import android.content.Intent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Authenticates broadcasts crossing from the module to its hooked processes. */
public final class BridgeAuth {
    public static final String KEY = "internal_bridge_token_v1";
    private static final String EXTRA = "tn.eluea.kgpt.internal.AUTH";
    private BridgeAuth() { }
    private static ConfigClient client;
    private static synchronized ConfigClient client(Context context) {
        if (client == null) client = new ConfigClient(context.getApplicationContext());
        return client;
    }
    public static void send(Context context, Intent intent) {
        String action = intent.getAction();
        if (action != null && action.startsWith("tn.eluea.kgpt")) {
            String token = client(context).getString(KEY, "");
            if (token.isEmpty()) return;
            intent.putExtra(EXTRA, token);
            if (intent.getPackage() == null && intent.getComponent() == null) {
                java.util.Set<String> targets = new java.util.HashSet<>();
                targets.add("tn.eluea.kgpt");
                android.view.inputmethod.InputMethodManager manager = (android.view.inputmethod.InputMethodManager)
                        context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) for (android.view.inputmethod.InputMethodInfo ime : manager.getEnabledInputMethodList())
                    targets.add(ime.getPackageName());
                for (String target : targets) context.sendBroadcast(new Intent(intent).setPackage(target));
                return;
            }
        }
        context.sendBroadcast(intent);
    }
    public static boolean verify(Context context, Intent intent) {
        if (context == null || intent == null) return false;
        String expected = client(context).getString(KEY, "");
        String received = intent.getStringExtra(EXTRA);
        return !expected.isEmpty() && received != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }
}
