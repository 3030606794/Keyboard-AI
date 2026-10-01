package tn.eluea.kgpt.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.core.ui.StandaloneStatusOverlay;
import tn.eluea.kgpt.util.Logger;

/**
 * Receives generation status updates from hook/IME process and shows a standalone KGPT overlay strip.
 */
public class GeneratingStatusBridgeReceiver extends BroadcastReceiver {
    public static final String ACTION = tn.eluea.kgpt.BuildConfig.APPLICATION_ID + ".action.GENERATING_STATUS_BRIDGE";
    public static final String EXTRA_CMD = "cmd";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_COLOR = "color";

    public static final String CMD_UPDATE = "update";
    public static final String CMD_DONE = "done";
    public static final String CMD_HIDE = "hide";

    @Override
    public void onReceive(Context context, Intent intent) {
            if (!tn.eluea.kgpt.provider.BridgeAuth.verify(context, intent)) return;
        if (intent == null) return;
        try {
            if (!ACTION.equals(intent.getAction())) return;
            Context app = (context != null) ? context.getApplicationContext() : null;
            if (app == null) return;

            boolean enabled = false;
            try {
                enabled = SPManager.getInstance().getGeneratingContentStandaloneFloatEnabled();
            } catch (Throwable ignored) {}

            String cmd = intent.getStringExtra(EXTRA_CMD);
            if (CMD_HIDE.equals(cmd)) {
                StandaloneStatusOverlay.getInstance().hide();
                return;
            }

            if (!enabled) {
                StandaloneStatusOverlay.getInstance().hide();
                return;
            }

            if (CMD_DONE.equals(cmd)) {
                String text = intent.getStringExtra(EXTRA_TEXT);
                StandaloneStatusOverlay.getInstance().showDone(app, text);
                return;
            }

            String text = intent.getStringExtra(EXTRA_TEXT);
            int progress = intent.getIntExtra(EXTRA_PROGRESS, -1);
            int color = intent.getIntExtra(EXTRA_COLOR, 0xFF4CAF50);
            StandaloneStatusOverlay.getInstance().showOrUpdate(app, text, progress, color);
        } catch (Throwable t) {
            Logger.error("GeneratingStatusBridgeReceiver failed: " + t);
            Logger.log(t);
        }
    }
}
