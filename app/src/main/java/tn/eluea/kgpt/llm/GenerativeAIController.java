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
package tn.eluea.kgpt.llm;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.listener.GenerativeAIListener;
import tn.eluea.kgpt.llm.client.LanguageModelClient;
import tn.eluea.kgpt.listener.ConfigChangeListener;
import tn.eluea.kgpt.llm.internet.InternetProvider;
import tn.eluea.kgpt.llm.internet.SimpleInternetProvider;
import tn.eluea.kgpt.llm.publisher.SimpleStringPublisher;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.llm.service.ExternalInternetProvider;
import tn.eluea.kgpt.settings.OtherSettingsType;
import tn.eluea.kgpt.ui.UiInteractor;
import tn.eluea.kgpt.util.Logger;
import tn.eluea.kgpt.util.AiDiagnostics;

public class GenerativeAIController implements ConfigChangeListener {
    private static final String TAG = "KGPT-GenAI";

    /** Special marker for user-initiated cancellation. Listeners should treat it as a silent stop. */
    public static final String USER_CANCELLED_MARKER = "__KGPT_USER_CANCEL__";

    // Avoid spamming clamp toasts on every request when a model has a known cap.
    // Keyed by provider|subModel|reason.
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> sClampNoticeShown =
            new java.util.concurrent.ConcurrentHashMap<>();
    private LanguageModelClient mModelClient = null;

    private final SPManager mSPManager;
    private final UiInteractor mInteractor;
    private ExternalInternetProvider mExternalClient = null;

    private List<GenerativeAIListener> mListeners = new ArrayList<>();
    private InternetProvider mInternetProvider = new SimpleInternetProvider();

    /**
     * All model/network work must run off the IME main thread.
     * Many providers in this codebase use blocking HttpURLConnection calls.
     * Running them on the input method (keyboard) main thread can ANR/kill the IME ("keyboard crash").
     */
    private static final java.util.concurrent.ExecutorService REQUEST_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "KGPT-LLM");
                t.setDaemon(true);
                return t;
            });



    // =============================
    // Request lifecycle (cancel / concurrency / auto-downgrade)
    // =============================
    private final Object mRequestLock = new Object();
    private volatile Subscription mCurrentSubscription = null;
    private volatile boolean mRequestInFlight = false;
    private volatile PendingRequest mPendingRequest = null;

    private final java.util.concurrent.atomic.AtomicInteger mRequestSeq = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile int mActiveRequestId = 0;
    private volatile tn.eluea.kgpt.llm.internet.RequestCancellation networkCancellation;
    private final ConversationMemoryStore editorMemory = new ConversationMemoryStore();
    public void clearEditorMemory() {
        synchronized (mRequestLock) { editorMemory.clear(); }
    }
    public boolean isRequestRunning() { synchronized (mRequestLock) { return mRequestInFlight; } }


    // Fail-safe watchdog: some provider/client paths may fail before triggering
    // onError/onComplete (e.g., synchronous submitPrompt()/subscribe() exceptions or hangs).
    // If lifecycle callbacks never arrive, the IME trigger pipeline can remain locked.
    private final Object mWatchdogLock = new Object();
    private volatile java.util.concurrent.ScheduledFuture<?> mWatchdogFuture = null;
    private volatile long mWatchdogLastProgressAtMs = 0L;
    private volatile boolean mWatchdogSawFirstChunk = false;

    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "KGPT-LLM-Watchdog");
                t.setDaemon(true);
                return t;
            });

    // First-token timeout is shorter to avoid leaving the trigger pipeline locked on unsupported models.
    // Once the first chunk arrives, stall timeout stays generous for long reasoning runs.
    private static final long DEFAULT_REQUEST_FIRST_CHUNK_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_REQUEST_STALL_TIMEOUT_MS = 180_000L;

    private static final class PendingRequest {
        final String prompt;
        final String systemMessage;
        final String roleIdOverride;
        final boolean useConversationMemory;

        PendingRequest(String prompt, String systemMessage, String roleIdOverride, boolean useConversationMemory) {
            this.prompt = prompt;
            this.systemMessage = systemMessage;
            this.roleIdOverride = roleIdOverride;
            this.useConversationMemory = useConversationMemory;
        }
    }

    private static final class Attempt {
        final LanguageModelClient client;
        final Integer streamModeOverride; // null = no override
        final String baseUrlOverride;     // null/empty = no override

        Attempt(LanguageModelClient client, Integer streamModeOverride, String baseUrlOverride) {
            this.client = client;
            this.streamModeOverride = streamModeOverride;
            this.baseUrlOverride = baseUrlOverride;
        }
    }

    /**
     * Per-user-request auto-downgrade state.
     * Used to retry once when the provider rejects certain optional parameters.
     */
    private static final class ParamDowngradeState {
        boolean retriedSamplingParams = false;
        boolean retriedReasoning = false;
        boolean retriedMaxTokens = false;
        boolean retriedStreamEmptyNonStream = false;
        boolean retriedStreamErrorNonStream = false;
    }

    private void armRequestWatchdog(final int requestId) {
        synchronized (mWatchdogLock) {
            try {
                if (mWatchdogFuture != null) {
                    mWatchdogFuture.cancel(false);
                }
            } catch (Throwable ignored) {}
            mWatchdogLastProgressAtMs = System.currentTimeMillis();
            mWatchdogSawFirstChunk = false;
            mWatchdogFuture = WATCHDOG_EXECUTOR.scheduleAtFixedRate(() -> {
                try {
                    // Only monitor the currently active request.
                    if (mActiveRequestId != requestId) return;
                    if (!mRequestInFlight) return;

                    long now = System.currentTimeMillis();
                    long last = mWatchdogLastProgressAtMs;
                    boolean sawFirst = mWatchdogSawFirstChunk;
                    long firstTimeoutMs = DEFAULT_REQUEST_FIRST_CHUNK_TIMEOUT_MS;
                    long stallTimeoutMs = DEFAULT_REQUEST_STALL_TIMEOUT_MS;
                    try {
                        if (tn.eluea.kgpt.SPManager.isReady()) {
                            tn.eluea.kgpt.SPManager sp = tn.eluea.kgpt.SPManager.getInstance();
                            firstTimeoutMs = sp.getWatchdogFirstChunkTimeoutMs();
                            stallTimeoutMs = sp.getWatchdogStallTimeoutMs();
                        }
                    } catch (Throwable ignored) {}
                    long timeout = sawFirst ? stallTimeoutMs : firstTimeoutMs;
                    if (last <= 0L || (now - last) < timeout) return;

                    // Cancel current subscription (best effort) and surface a timeout error once.
                    synchronized (mRequestLock) {
                        if (mActiveRequestId != requestId || !mRequestInFlight) return;
                        try {
                            if (mCurrentSubscription != null) {
                                mCurrentSubscription.cancel();
                            }
                        } catch (Throwable ignored) {}
                        mCurrentSubscription = null;
                    }

                    boolean streamEnabled = false;
                    boolean fallbackNonStream = false;
                    int streamMode = SPManager.STREAM_MODE_AUTO;
                    try { streamEnabled = SPManager.getInstance().getStreamingOutputEnabled(); } catch (Throwable ignored) {}
                    try { fallbackNonStream = SPManager.getInstance().getStreamingOutputFallbackNonStreamEnabled(); } catch (Throwable ignored) {}
                    try { streamMode = SPManager.getInstance().getStreamingOutputModeForRequest(); } catch (Throwable ignored) {}

                    String diag = buildAiDiagnosticSnapshot(mModelClient, null, requestId, 0,
                            safeGetMaxTokensLimit(), safeGetReasoningThinkingMode(), safeGetNormalThinking());
                    String wdMsg = "req=" + requestId
                            + " firstChunk=" + sawFirst
                            + " idleMs=" + (now - last)
                            + " firstTimeoutMs=" + firstTimeoutMs
                            + " stallTimeoutMs=" + stallTimeoutMs
                            + " streamEnabled=" + streamEnabled
                            + " streamMode=" + streamModeLabel(streamMode)
                            + " fallbackNonStream=" + fallbackNonStream
                            + " | " + diag;
                    tn.eluea.kgpt.util.Logger.error("[AI_DIAG][WATCHDOG_TIMEOUT] " + wdMsg);
                    diag("WATCHDOG_TIMEOUT", wdMsg);

                    String timeoutMsg = sawFirst
                            ? "模型响应超时（流式输出中断）"
                            : "模型响应超时（未返回任何内容）";
                    if (!sawFirst) {
                        timeoutMsg += "；可能是模型不可用、Base URL 不兼容、或流式协议不兼容";
                        if (fallbackNonStream) {
                            timeoutMsg += "（已开启流式失败回退，若仍失败请改用打字机/关闭流式或更换模型）";
                        } else {
                            timeoutMsg += "（建议尝试打字机/关闭流式或更换模型）";
                        }
                    }
                    finishWithError(requestId, new RuntimeException(timeoutMsg));
                } catch (Throwable t) {
                    tn.eluea.kgpt.util.Logger.log(t);
                    try { diag("WATCHDOG_EXCEPTION", String.valueOf(t == null ? null : t.getMessage())); } catch (Throwable ignoredDiag) {}
                }
            }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private void markRequestProgress(int requestId, boolean firstChunk) {
        if (mActiveRequestId != requestId) return;
        mWatchdogLastProgressAtMs = System.currentTimeMillis();
        if (firstChunk) mWatchdogSawFirstChunk = true;
    }

    private void cancelRequestWatchdog(int requestId) {
        synchronized (mWatchdogLock) {
            if (mActiveRequestId != requestId && requestId != 0) {
                return;
            }
            try {
                if (mWatchdogFuture != null) {
                    mWatchdogFuture.cancel(false);
                }
            } catch (Throwable ignored) {}
            mWatchdogFuture = null;
            mWatchdogLastProgressAtMs = 0L;
            mWatchdogSawFirstChunk = false;
        }
    }

    private void diag(String category, String message) {
        try { AiDiagnostics.append(category, message); } catch (Throwable ignored) {}
    }

    private String safeDiagnosticString(String s) {
        if (s == null) return "-";
        String v = s.replace("\n", " ").replace("\r", " ").trim();
        return v.isEmpty() ? "-" : v;
    }

    private String presetLabel(int p) {
        switch (p) {
            case SPManager.MAX_TOKENS_PRESET_SHORT: return "short";
            case SPManager.MAX_TOKENS_PRESET_LONG: return "long";
            case SPManager.MAX_TOKENS_PRESET_MEDIUM:
            default: return "medium";
        }
    }

    private String streamModeLabel(int m) {
        switch (m) {
            case SPManager.STREAM_MODE_SSE: return "SSE";
            case SPManager.STREAM_MODE_JSONL: return "JSONL";
            case SPManager.STREAM_MODE_TYPEWRITER: return "TYPEWRITER";
            case SPManager.STREAM_MODE_AUTO:
            default: return "AUTO";
        }
    }

    private boolean isStreamLikeAttempt(Attempt attempt, boolean streamEnabled, int streamMode) {
        if (!streamEnabled) return false;
        try {
            int mode = (attempt != null && attempt.streamModeOverride != null)
                    ? attempt.streamModeOverride
                    : streamMode;
            return mode != SPManager.STREAM_MODE_TYPEWRITER;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean looksLikeStreamParseFailure(Throwable t) {
        if (t == null) return false;
        String msg;
        try { msg = String.valueOf(t.getMessage()); } catch (Throwable ignored) { msg = String.valueOf(t); }
        if (msg == null) msg = "";
        String lc = msg.toLowerCase(java.util.Locale.ROOT);
        return lc.contains("sse")
                || lc.contains("jsonl")
                || lc.contains("stream parse")
                || lc.contains("streaming parse")
                || lc.contains("stream response ended")
                || (lc.contains("stream") && (lc.contains("parse") || lc.contains("protocol") || lc.contains("chunk")));
    }

    private String buildAiDiagnosticSnapshot(LanguageModelClient client,
                                             Attempt attempt,
                                             int requestId,
                                             int attemptIndex,
                                             int maxTokensOverride,
                                             int reasoningThinkingMode,
                                             float normalThinking) {
        try {
            SPManager sp = SPManager.getInstance();
            String provider = "-";
            String subModel = "-";
            String baseUrl = "-";
            try { if (client != null && client.getLanguageModel() != null) provider = safeDiagnosticString(client.getLanguageModel().label); } catch (Throwable ignored) {}
            try { if (client != null) subModel = safeDiagnosticString(client.getSubModel()); } catch (Throwable ignored) {}
            try { if (client != null) baseUrl = safeDiagnosticString(client.getBaseUrl()); } catch (Throwable ignored) {}
            int streamMode = SPManager.STREAM_MODE_AUTO;
            boolean streamEnabled = false;
            boolean streamFallback = false;
            try { streamEnabled = sp.getStreamingOutputEnabled(); } catch (Throwable ignored) {}
            try { streamMode = sp.getStreamingOutputModeForRequest(); } catch (Throwable ignored) {}
            try { streamFallback = sp.getStreamingOutputFallbackNonStreamEnabled(); } catch (Throwable ignored) {}

            return "req=" + requestId
                    + " attempt=" + (attemptIndex + 1)
                    + " provider=" + provider
                    + " subModel=" + subModel
                    + " baseUrl=" + baseUrl
                    + " streamEnabled=" + streamEnabled
                    + " streamMode=" + streamModeLabel(streamMode)
                    + (attempt != null && attempt.streamModeOverride != null ? "(override)" : "")
                    + " fallbackNonStream=" + streamFallback
                    + " maxTokens=" + maxTokensOverride
                    + " maxTokensPreset=" + presetLabel(sp.getMaxTokensPreset())
                    + " normalThinking=" + String.format(java.util.Locale.US, "%.1f", normalThinking)
                    + " reasoningMode=" + reasoningThinkingMode;
        } catch (Throwable t) {
            return "req=" + requestId + " attempt=" + (attemptIndex + 1) + " diag_error=" + safeDiagnosticString(t.getMessage());
        }
    }

    private int safeGetMaxTokensLimit() {
        try { return SPManager.getInstance().getMaxTokensLimit(); } catch (Throwable ignored) { return 0; }
    }

    private int safeGetReasoningThinkingMode() {
        try { return SPManager.getInstance().getReasoningModelThinkingMode(); } catch (Throwable ignored) { return SPManager.REASONING_MODEL_THINKING_AUTO; }
    }

    private float safeGetNormalThinking() {
        try { return SPManager.getInstance().getNormalModelThinking(); } catch (Throwable ignored) { return 0.7f; }
    }

    public GenerativeAIController() {
        mSPManager = SPManager.getInstance();
        mInteractor = UiInteractor.getInstance();

        mInteractor.registerConfigChangeListener(this);
        if (mSPManager.hasLanguageModel()) {
            setModel(mSPManager.getLanguageModel());
        } else {
            mModelClient = LanguageModelClient.forModel(LanguageModel.Gemini);
        }

        updateInternetProvider();
    }

    private void updateInternetProvider() {
        updateInternetProvider(null);
    }

    private void updateInternetProvider(Boolean enableExternalInternet) {
        // Always use SimpleInternetProvider for now
        // ExternalInternetProvider has issues on Android 12+
        tn.eluea.kgpt.util.Logger.log("Using SimpleInternetProvider");
        mInternetProvider = new SimpleInternetProvider();

        if (mModelClient != null) {
            mModelClient.setInternetProvider(mInternetProvider);
        }
    }

    public boolean needModelClient() {
        return mModelClient == null;
    }

    public boolean needApiKey() {
        return mModelClient.getApiKey() == null || mModelClient.getApiKey().isEmpty();
    }

    private void setModel(LanguageModel model) {
        tn.eluea.kgpt.util.Logger.log("setModel " + model.label);
        mModelClient = LanguageModelClient.forModel(model);
        for (LanguageModelField field : LanguageModelField.values()) {
            mModelClient.setField(field, mSPManager.getLanguageModelField(model, field));
        }
        mModelClient.setInternetProvider(mInternetProvider);
    }

    @Override
    public void onLanguageModelChange(LanguageModel model) {
        if (mModelClient == null || mModelClient.getLanguageModel() != model) {
            setModel(model);
        }
    }

    @Override
    public void onLanguageModelFieldChange(LanguageModel model, LanguageModelField field, String value) {
        if (mModelClient != null && mModelClient.getLanguageModel() == model) {
            mModelClient.setField(field, value);
        }
    }

    @Override
    public void onCommandsChange(String commandsRaw) {
    }

    @Override
    public void onPatternsChange(String patternsRaw) {

    }

    @Override
    public void onOtherSettingsChange(Bundle otherSettings) {
        String enableInternetKey = OtherSettingsType.EnableExternalInternet.name();
        if (otherSettings.containsKey(enableInternetKey)) {
            boolean enableExternalInternet = otherSettings.getBoolean(enableInternetKey);
            updateInternetProvider(enableExternalInternet);
        }
    }

    public void addListener(GenerativeAIListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(GenerativeAIListener listener) {
        mListeners.remove(listener);
    }

    public void generateResponse(String prompt) {
        generateResponse(prompt, null);
    }

    public void generateResponse(String prompt, String systemMessage) {
        generateResponse(prompt, systemMessage, null);
    }

    /**
     * Generate response with optional role id override.
     */
    public void generateResponse(String prompt, String systemMessage, String roleIdOverride) {
        // Default behaviour (stateless). Use the 4-arg overload to enable memory.
        generateResponse(prompt, systemMessage, roleIdOverride, false);
    }

    /**
     * Generate response with optional role id override, and optional multi-turn memory.
     * When memory is enabled, a small number of previous turns will be attached to the prompt
     * based on the "Conversation memory" level in Labs.
     */
    public void generateResponse(String prompt, String systemMessage, String roleIdOverride, boolean useConversationMemory) {
        // Ensure we don't block UI thread. Network work is already off main thread.
        Log.d(TAG, "Getting response for text length: " + (prompt == null ? 0 : prompt.length()));

        if (prompt == null || prompt.isEmpty()) {
            return;
        }

        // Apply request concurrency policy (cancel / ignore / queue).
        final int myRequestId;
        synchronized (mRequestLock) {
            int policy = SPManager.REQUEST_POLICY_CANCEL_PREVIOUS;
            try {
                policy = mSPManager.getRequestConcurrencyPolicy();
            } catch (Throwable ignored) {}

            if (mRequestInFlight) {
                if (policy == SPManager.REQUEST_POLICY_IGNORE_NEW) {
                    return;
                }
                if (policy == SPManager.REQUEST_POLICY_QUEUE_LATEST) {
                    mPendingRequest = new PendingRequest(prompt, systemMessage, roleIdOverride, useConversationMemory);
                    return;
                }

                // Cancel previous (default).
                try {
                    if (mCurrentSubscription != null) {
                        mCurrentSubscription.cancel();
                    }
                } catch (Throwable ignored) {}
                mCurrentSubscription = null;
            }

            if (networkCancellation != null) networkCancellation.cancel();
            networkCancellation = new tn.eluea.kgpt.llm.internet.RequestCancellation();
            mRequestInFlight = true;
            mPendingRequest = null;

            myRequestId = mRequestSeq.incrementAndGet();
            mActiveRequestId = myRequestId;
        }

        armRequestWatchdog(myRequestId);

        // Resolve active role / system message
        String resolvedRoleId = null;
        String rolesJsonSnapshot = null;
        try {
            SPManager sp = SPManager.getInstance();
            String rid = (roleIdOverride != null && !roleIdOverride.trim().isEmpty())
                    ? roleIdOverride.trim()
                    : sp.getActiveRoleId();
            resolvedRoleId = rid;
            rolesJsonSnapshot = sp.getRolesJson();
            systemMessage = RoleManager.resolveSystemMessage(rid, rolesJsonSnapshot, systemMessage);
        } catch (Exception ignored) {}

        // Build conversation-aware prompt (provider-agnostic)
        final String originalPrompt = prompt;
        try {
            if (useConversationMemory) {
                SPManager sp = SPManager.getInstance();
                int mem = sp.getConversationMemoryLevel();
                if (mem > 0) {
                    String modelLabel = "";
                    try {
                        if (mModelClient != null && mModelClient.getLanguageModel() != null)
                            modelLabel = mModelClient.getLanguageModel().name();
                    } catch (Throwable ignored) {}

                    // Scope: provider | subModel | roleId (avoid memory leaking across sub-model switches)
                    String subModelLabel = "";
                    try {
                        tn.eluea.kgpt.roles.RoleManager.RoleOverride ro =
                                tn.eluea.kgpt.roles.RoleManager.resolveRoleOverride(SPManager.getInstance(), resolvedRoleId, rolesJsonSnapshot);
                        if (ro != null && ro.enabled && ro.subModel != null && !ro.subModel.trim().isEmpty()) {
                            subModelLabel = ro.subModel.trim();
                        }
                    } catch (Throwable ignored2) {}
                    try {
                        if ((subModelLabel == null || subModelLabel.trim().isEmpty()) && mModelClient != null) {
                            String sm = null;
                            try { sm = mModelClient.getSubModel(); } catch (Throwable ignored3) {}
                            if (sm == null || sm.trim().isEmpty()) {
                                try { sm = mModelClient.getField(tn.eluea.kgpt.llm.LanguageModelField.SubModel); } catch (Throwable ignored4) {}
                            }
                            if (sm != null) subModelLabel = sm.trim();
                        }
                    } catch (Throwable ignored3) {}

                    String scope = (modelLabel == null ? "" : modelLabel)
                            + "|" + (subModelLabel == null ? "" : subModelLabel)
                            + "|" + (resolvedRoleId == null ? "" : resolvedRoleId);
                    editorMemory.ensureScope(scope);

                    boolean autoSummarize = false;
                    try { autoSummarize = sp.getAutoSummarizeOldContextEnabled(); } catch (Throwable ignored) {}
                    prompt = editorMemory.buildPromptWithHistory(prompt, mem, autoSummarize);
                }
            }
        } catch (Throwable ignored) {}

        // Max tokens preset (Short / Medium / Long)
        int maxTokensOverride = 0;
        try {
            maxTokensOverride = SPManager.getInstance().getMaxTokensLimit();
        } catch (Throwable ignored) {}

        // Normal model thinking (temperature-like override)
        float normalThinking = 0.7f;
        try { normalThinking = SPManager.getInstance().getNormalModelThinking(); } catch (Throwable ignored) {}

        // Role-specific parameter overrides (do NOT mutate global settings)
        String roleSubModelOverride = null;
        try {
            RoleManager.RoleOverride ro = RoleManager.resolveRoleOverride(SPManager.getInstance(), resolvedRoleId, rolesJsonSnapshot);
            if (ro != null && ro.enabled) {
                if (ro.subModel != null && !ro.subModel.trim().isEmpty()) {
                    roleSubModelOverride = ro.subModel.trim();
                }
                if (ro.temperature >= 0f) {
                    float t = ro.temperature;
                    if (t < 0f) t = 0f;
                    if (t > 2.0f) t = 2.0f;
                    normalThinking = t;
                }
            }
        } catch (Throwable ignored) {}

        // Reasoning model thinking (推理模型思考)
        int reasoningThinkingMode = SPManager.REASONING_MODEL_THINKING_AUTO;
        try { reasoningThinkingMode = SPManager.getInstance().getReasoningModelThinkingMode(); } catch (Throwable ignored) {}

        // Build auto-downgrade attempts list
        final ArrayList<Attempt> attempts = new ArrayList<>();
        final LanguageModelClient primaryClient = snapshotClient(mModelClient);
        attempts.add(new Attempt(primaryClient, null, null));

        int flags = 0;
        try { flags = SPManager.getInstance().getAutoDowngradeFlags(); } catch (Throwable ignored) {}

        // 1) Stream -> non-stream (force TYPEWRITER mode), only if user isn't already on TYPEWRITER.
        try {
            if ((flags & SPManager.DOWNGRADE_FLAG_STREAM) != 0) {
                int mode = SPManager.getInstance().getStreamingOutputMode();
                if (mode != SPManager.STREAM_MODE_TYPEWRITER) {
                    attempts.add(new Attempt(primaryClient, SPManager.STREAM_MODE_TYPEWRITER, null));
                }
            }
        } catch (Throwable ignored) {}

        // 2) BaseURL fallback (same model)
        try {
            if ((flags & SPManager.DOWNGRADE_FLAG_BASEURL) != 0) {
                String backupUrl = SPManager.getInstance().getAutoDowngradeBackupBaseUrl();
                if (backupUrl != null && !backupUrl.trim().isEmpty()) {
                    attempts.add(new Attempt(primaryClient, null, backupUrl.trim()));
                }
            }
        } catch (Throwable ignored) {}

        // 3) Model fallback (use the user's stored config for that model)
        try {
            if ((flags & SPManager.DOWNGRADE_FLAG_MODEL) != 0) {
                LanguageModel backupModel = SPManager.getInstance().getAutoDowngradeBackupModel();
                if (backupModel != null && primaryClient != null && primaryClient.getLanguageModel() != backupModel) {
                    LanguageModelClient backupClient = LanguageModelClient.forModel(backupModel);
                    for (LanguageModelField field : LanguageModelField.values()) {
                        backupClient.setField(field, mSPManager.getLanguageModelField(backupModel, field));
                    }
                    backupClient.setInternetProvider(mInternetProvider);
                    attempts.add(new Attempt(backupClient, null, null));
                }
            }
        } catch (Throwable ignored) {}

        // Notify prepare (once per user request)
        if (mInteractor != null) {
            mInteractor.runOnUiThread(() -> {
                if (mActiveRequestId != myRequestId) return;
                for (GenerativeAIListener l : mListeners) {
                    try {
                        l.onAIPrepare();
                    } catch (Throwable t) {
                        tn.eluea.kgpt.util.Logger.log(t);
                    }
                }
            });
        }

        final String finalPrompt = prompt;
        final String finalSystemMessage = systemMessage;
        // Capture non-effectively-final locals for lambda
        final String finalResolvedRoleId = resolvedRoleId;
        final boolean finalUseConversationMemory = useConversationMemory;
        final int finalMaxTokensOverride = maxTokensOverride;
        final float finalNormalThinking = normalThinking;
        final int finalReasoningThinkingMode = reasoningThinkingMode;
        final String finalRoleSubModelOverride = roleSubModelOverride;
        final StringBuilder assistantBuffer = new StringBuilder();

        final ParamDowngradeState paramState = new ParamDowngradeState();

        markRequestProgress(myRequestId, false);

        // Start the first attempt
        REQUEST_EXECUTOR.execute(() -> {
            try {
                startAttemptInternal(
                        myRequestId,
                        attempts,
                        0,
                        finalPrompt,
                        finalSystemMessage,
                        originalPrompt,
                        finalResolvedRoleId,
                        finalUseConversationMemory,
                        assistantBuffer,
                        finalMaxTokensOverride,
                        finalNormalThinking,
                        finalReasoningThinkingMode,
                        finalRoleSubModelOverride,
                        paramState
                );
            } catch (Throwable t) {
                tn.eluea.kgpt.util.Logger.log(t);
                finishWithError(myRequestId, t);
            }
        });
    }

    private void startAttemptInternal(
            final int requestId,
            final ArrayList<Attempt> attempts,
            final int attemptIndex,
            final String prompt,
            final String systemMessage,
            final String originalPrompt,
            final String resolvedRoleId,
            final boolean useConversationMemory,
            final StringBuilder assistantBuffer,
            final int maxTokensOverride,
            final float normalThinking,
            final int reasoningThinkingMode,
            final String roleSubModelOverride,
            final ParamDowngradeState paramState
    ) {
        if (attempts == null || attempts.isEmpty()) {
            finishWithError(requestId, new RuntimeException("No request attempt available"));
            return;
        }
        if (attemptIndex < 0 || attemptIndex >= attempts.size()) {
            finishWithError(requestId, new RuntimeException("All downgrade attempts failed"));
            return;
        }

        // Ignore if a newer request has started.
        if (mActiveRequestId != requestId) {
            return;
        }

        final Attempt attempt = attempts.get(attemptIndex);
        final LanguageModelClient client = attempt.client;

        Publisher<String> publisher;

        // Apply temporary overrides (sub_model / max_tokens / base_url / streaming mode).
        String _prevSubModel = null;
        try { if (client != null) _prevSubModel = client.getField(LanguageModelField.SubModel); } catch (Throwable ignored) {}
        final String prevSubModel = _prevSubModel;

        String _prevMaxTokens = null;
        String _prevBaseUrl = null;
        try { if (client != null) _prevMaxTokens = client.getField(LanguageModelField.MaxTokens); } catch (Throwable ignored) {}
        try { if (client != null) _prevBaseUrl = client.getField(LanguageModelField.BaseUrl); } catch (Throwable ignored) {}
        final String prevMaxTokens = _prevMaxTokens;
        final String prevBaseUrl = _prevBaseUrl;

        String _prevTemperature = null;
        try { if (client != null) _prevTemperature = client.getField(LanguageModelField.Temperature); } catch (Throwable ignored) {}
        final String prevTemperature = _prevTemperature;

        try {
            if (client != null && roleSubModelOverride != null && !roleSubModelOverride.trim().isEmpty()) {
                client.setField(LanguageModelField.SubModel, roleSubModelOverride.trim());
            }
        } catch (Throwable ignored) {}

        // Scheme-2 safety: clamp to cached safe max tokens (learned) and/or provider hard cap (API compliance).
        int requestedMaxTokens = maxTokensOverride;
        int effectiveMaxTokens = maxTokensOverride;
        Integer learnedCap = null;
        Integer hardCap = null;
        String clampReason = null;
        try {
            if (client != null && SPManager.isReady() && effectiveMaxTokens > 0) {
                SPManager spm = SPManager.getInstance();
                learnedCap = spm.getCachedSafeMaxTokens(client.getLanguageModel(), client.getSubModel());
                if (learnedCap != null && learnedCap > 0 && effectiveMaxTokens > learnedCap) {
                    effectiveMaxTokens = learnedCap;
                    clampReason = "learned";
                }
                hardCap = spm.getCachedHardMaxTokens(client.getLanguageModel(), client.getSubModel());
                if (hardCap != null && hardCap > 0 && effectiveMaxTokens > hardCap) {
                    effectiveMaxTokens = hardCap;
                    clampReason = (clampReason == null) ? "hard" : (clampReason + "+hard");
                }
            }
        } catch (Throwable ignored) {}

        // Record last decision snapshot for UI "Why" panel.
        try {
            if (client != null && SPManager.isReady() && requestedMaxTokens > 0 && effectiveMaxTokens > 0) {
                SPManager.getInstance().recordLastMaxTokensDecision(
                        client.getLanguageModel(),
                        client.getSubModel(),
                        requestId,
                        requestedMaxTokens,
                        effectiveMaxTokens,
                        learnedCap,
                        hardCap,
                        (clampReason == null ? "user" : clampReason),
                        false,
                        0
                );
            }
        } catch (Throwable ignored) {}

        // UI hint + diagnostics: when we clamp silently, it can look like the model "stopped halfway".
        try {
            if (mInteractor != null && requestedMaxTokens > 0 && effectiveMaxTokens > 0 && requestedMaxTokens != effectiveMaxTokens) {
                final int _req = requestedMaxTokens;
                final int _eff = effectiveMaxTokens;
                final String _reason = (clampReason == null ? "-" : clampReason);
                final Integer _learned = learnedCap;
                final Integer _hard = hardCap;
                diag("MAXTOK_PRECLAMP", "provider=" + (client == null ? "null" : client.getLanguageModel())
                        + ", subModel=" + (client == null ? "null" : client.getSubModel())
                        + ", req=" + _req + ", eff=" + _eff
                        + ", learned=" + String.valueOf(_learned) + ", hard=" + String.valueOf(_hard)
                        + ", reason=" + _reason);
                // Record hard-cap hit for management UI (last reason / last hit time / count).
                try {
                    if (client != null && SPManager.isReady() && _reason != null && _reason.contains("hard")
                            && _hard != null && _hard > 0) {
                        SPManager.getInstance().recordHardCapLastHit(
                                client.getLanguageModel(),
                                client.getSubModel(),
                                "preclamp " + _req + " → " + _eff + " (hard=" + _hard + ")"
                        );
                    }
                } catch (Throwable ignoredHardHit) {}
                // Show this notice at most once per model+reason per process.
                String key = (client == null ? "null" : String.valueOf(client.getLanguageModel()))
                        + "|" + (client == null ? "null" : String.valueOf(client.getSubModel()))
                        + "|" + _reason;
                boolean first = (sClampNoticeShown.putIfAbsent(key, Boolean.TRUE) == null);
                if (first) {
                    mInteractor.runOnUiThread(() -> {
                if (mActiveRequestId != requestId) return;
                        try {
                            if (_reason.contains("hard")) {
                                try {
                                    if (SPManager.isReady() && !SPManager.getInstance().getHardCapToastEnabled()) {
                                        return;
                                    }
                                } catch (Throwable ignoredPref) {}
                                mInteractor.toastShort("接口限制输出上限，已将 " + _req + " → " + _eff + " tokens");
                            } else {
                                mInteractor.toastShort("该模型已学习到输出上限，已将 " + _req + " → " + _eff + " tokens");
                            }
                        } catch (Throwable ignoredToast) {}
                    });
                }
            }
        } catch (Throwable ignoredToastOuter) {}
try {
            if (client != null && effectiveMaxTokens > 0) {
                client.setField(LanguageModelField.MaxTokens, String.valueOf(effectiveMaxTokens));
            }
        } catch (Throwable ignored) {}

        try {
            if (client != null && attempt.baseUrlOverride != null && !attempt.baseUrlOverride.isEmpty()) {
                client.setField(LanguageModelField.BaseUrl, attempt.baseUrlOverride);
            }
        } catch (Throwable ignored) {}

        try {
            if (attempt.streamModeOverride != null) {
                SPManager.setThreadStreamingModeOverride(attempt.streamModeOverride);
            } else {
                SPManager.clearThreadStreamingModeOverride();
            }
        } catch (Throwable ignored) {}

        final String diagStart = buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking);
        Logger.log("AI request start | " + diagStart);
        Logger.log("[AI_DIAG][REQ_START] " + diagStart);
        diag("REQ_START", diagStart);
        try { AiDiagnostics.beginNewRequest(diagStart); } catch (Throwable ignored) {}

        // Apply normal model thinking (temperature) override if the selected model supports it.
        try {
            if (client != null && tn.eluea.kgpt.llm.ModelCapabilities.supportsTemperature(client.getLanguageModel(), client.getSubModel())) {
                float v = normalThinking;
                if (v < 0.0f) v = 0.0f;
                if (v > 1.8f) v = 1.8f;
                v = Math.round(v * 10.0f) / 10.0f;
                client.setField(LanguageModelField.Temperature, String.format(Locale.US, "%.1f", v));
            }
        } catch (Throwable ignored) {}

        boolean samplingParamsAttempted = false;
        try {
            samplingParamsAttempted = client != null
                    && tn.eluea.kgpt.llm.ModelCapabilities.supportsTemperature(client.getLanguageModel(), client.getSubModel());
        } catch (Throwable ignored) {}
        final boolean samplingParamsAttemptedFinal = samplingParamsAttempted;

        // Hybrid reasoning control: depth modes try native parameter first (for OpenAI-compatible clients),
        // and fall back to prompt hint when native reasoning is unsupported.
        final String nativeReasoningEffort = tn.eluea.kgpt.ui.lab.ReasoningModelThinkingOptions.toNativeReasoningEffort(reasoningThinkingMode);
        final boolean reasoningDepthMode = tn.eluea.kgpt.ui.lab.ReasoningModelThinkingOptions.isDepthMode(reasoningThinkingMode);
        final boolean reasoningStyleMode = tn.eluea.kgpt.ui.lab.ReasoningModelThinkingOptions.isStyleHintMode(reasoningThinkingMode);
        boolean nativeReasoningAllowedByCache = true;
        try {
            if (client != null && SPManager.isReady()) {
                Boolean cachedReasoning = SPManager.getInstance().getCachedSupportsReasoningThinking(client.getLanguageModel(), client.getSubModel());
                if (cachedReasoning != null && !cachedReasoning.booleanValue()) nativeReasoningAllowedByCache = false;
            }
        } catch (Throwable ignored) {}
        final boolean nativeReasoningAttempted = client instanceof tn.eluea.kgpt.llm.client.ChatGPTClient
                && reasoningDepthMode
                && nativeReasoningEffort != null
                && nativeReasoningAllowedByCache;
        try {
            if (nativeReasoningAttempted) SPManager.setThreadReasoningEffortOverride(nativeReasoningEffort);
            else SPManager.clearThreadReasoningEffortOverride();
        } catch (Throwable ignored) {}

        // Apply reasoning model thinking prompt hint when style mode is chosen, or when depth mode is forced to fallback.
        String effectiveSystemMessage = systemMessage;
        try {
            boolean applyReasoningPromptHint = reasoningStyleMode || (reasoningDepthMode && !nativeReasoningAttempted);
            if (client != null && applyReasoningPromptHint) {
                effectiveSystemMessage = tn.eluea.kgpt.ui.lab.ReasoningModelThinkingOptions.applyToSystemMessage(systemMessage, reasoningThinkingMode);
            }
        } catch (Throwable ignored) {}

        // Choose publisher
        if (client == null) {
            publisher = new SimpleStringPublisher("Missing model client. Please configure your model in settings.");
        } else if (client.getApiKey() == null || client.getApiKey().isEmpty()) {
            publisher = new SimpleStringPublisher("Missing API Key. Please configure your API key in KeyboardGPT settings.");
        } else {
            try {
                tn.eluea.kgpt.llm.internet.RequestCancellation cancellation;
                synchronized (mRequestLock) {
                    if (mActiveRequestId != requestId) return;
                    cancellation = networkCancellation;
                }
                tn.eluea.kgpt.llm.internet.RequestCancellation.bind(cancellation);
                try { publisher = client.submitPrompt(prompt, effectiveSystemMessage); }
                finally { tn.eluea.kgpt.llm.internet.RequestCancellation.unbind(); }
            } catch (Throwable t) {
                tn.eluea.kgpt.util.Logger.log(t);
                try { SPManager.clearThreadStreamingModeOverride(); } catch (Throwable ignored) {}
                try { SPManager.clearThreadReasoningEffortOverride(); } catch (Throwable ignored) {}
                try { if (client != null && prevSubModel != null) client.setField(LanguageModelField.SubModel, prevSubModel); } catch (Throwable ignored) {}
                try { if (client != null && prevMaxTokens != null) client.setField(LanguageModelField.MaxTokens, prevMaxTokens); } catch (Throwable ignored) {}
                try { if (client != null && prevBaseUrl != null) client.setField(LanguageModelField.BaseUrl, prevBaseUrl); } catch (Throwable ignored) {}
                try { if (client != null && prevTemperature != null) client.setField(LanguageModelField.Temperature, prevTemperature); } catch (Throwable ignored) {}
                finishWithError(requestId, t);
                return;
            }
        }

        try {
            publisher.subscribe(new Subscriber<String>() {
            boolean completed = false;
            boolean hasError = false;

            private void cleanupOverrides() {
                try { SPManager.clearThreadStreamingModeOverride(); } catch (Throwable ignored) {}
                try { SPManager.clearThreadReasoningEffortOverride(); } catch (Throwable ignored) {}
                try {
                    if (client != null && prevSubModel != null) {
                        client.setField(LanguageModelField.SubModel, prevSubModel);
                    }
                } catch (Throwable ignored) {}
                try {
                    if (client != null && prevMaxTokens != null) {
                        client.setField(LanguageModelField.MaxTokens, prevMaxTokens);
                    }
                } catch (Throwable ignored) {}
                try {
                    if (client != null && prevBaseUrl != null) {
                        client.setField(LanguageModelField.BaseUrl, prevBaseUrl);
                    }
                } catch (Throwable ignored) {}
                try {
                    if (client != null && prevTemperature != null) {
                        client.setField(LanguageModelField.Temperature, prevTemperature);
                    }
                } catch (Throwable ignored) {}
            }

            @Override
            public void onSubscribe(Subscription s) {
                // Store subscription for cancellation/concurrency
                synchronized (mRequestLock) {
                    if (mActiveRequestId != requestId) { s.cancel(); return; }
                    if (mActiveRequestId == requestId) {
                        mCurrentSubscription = s;
                    }
                }
                markRequestProgress(requestId, false);
                try {
                    s.request(Long.MAX_VALUE);
                } catch (Throwable ignored) {}
            }

            @Override
            public void onNext(String s) {
                if (mActiveRequestId != requestId || completed || hasError) return;


                if (LanguageModelClient.INTERNAL_KEEPALIVE_MARKER.equals(s)) {
                    // Stream keep-alive marker: count as progress but do not append/insert into UI.
                    markRequestProgress(requestId, true);
                    return;
                }

                if (s == null || s.isEmpty()) {
                    return;
                }

                boolean wasEmptyBefore = assistantBuffer.length() == 0;
                try { assistantBuffer.append(s); } catch (Throwable ignored) {}
                markRequestProgress(requestId, true);
                if (wasEmptyBefore) {
                    try {
                        String fcDiag = buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking);
                        Logger.log("[AI_DIAG][FIRST_CHUNK] " + fcDiag);
                        diag("FIRST_CHUNK", fcDiag);
                    } catch (Throwable ignored) {}
                }

                if (mInteractor != null) {
                    final String chunk = s;
                    mInteractor.runOnUiThread(() -> {
                if (mActiveRequestId != requestId) return;
                        for (GenerativeAIListener l : mListeners) {
                            try {
                                l.onAINext(chunk);
                            } catch (Throwable t) {
                                tn.eluea.kgpt.util.Logger.log(t);
                            }
                        }
                    });
                }
            }

            @Override
            public void onError(Throwable t) {
                if (mActiveRequestId != requestId) return;
                if (completed || hasError) return;
                hasError = true;
                completed = true;

                Logger.error("AI request error | " + buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking)
                        + " | emittedChars=" + assistantBuffer.length() + " | error=" + String.valueOf(t == null ? null : t.getMessage()));
                try {
                    String errDiag = buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking) + " | emittedChars=" + assistantBuffer.length() + " | error=" + String.valueOf(t == null ? null : t.getMessage());
                    Logger.error("[AI_DIAG][REQ_ERROR] " + errDiag);
                    diag("REQ_ERROR", errDiag);
                } catch (Throwable ignored) {}
                try {
                    boolean streamEnabledForThisAttempt = false;
                    int streamModeForThisAttempt = SPManager.STREAM_MODE_AUTO;
                    boolean fallbackToNonStreamEnabled = false;
                    try { streamEnabledForThisAttempt = SPManager.getInstance().getStreamingOutputEnabled(); } catch (Throwable ignored) {}
                    try { streamModeForThisAttempt = SPManager.getInstance().getStreamingOutputModeForRequest(); } catch (Throwable ignored) {}
                    try { fallbackToNonStreamEnabled = SPManager.getInstance().getStreamingOutputFallbackNonStreamEnabled(); } catch (Throwable ignored) {}

                    boolean currentAttemptIsStreamLike = isStreamLikeAttempt(attempt, streamEnabledForThisAttempt, streamModeForThisAttempt);
                    boolean streamParseFailure = looksLikeStreamParseFailure(t);
                    if (assistantBuffer.length() == 0
                            && currentAttemptIsStreamLike
                            && fallbackToNonStreamEnabled
                            && paramState != null
                            && !paramState.retriedStreamErrorNonStream
                            && streamParseFailure) {
                        paramState.retriedStreamErrorNonStream = true;
                        String retryDiag = buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking)
                                + " | reason=stream_parse_error | error=" + String.valueOf(t == null ? null : t.getMessage());
                        Logger.log("AI stream parse error -> retry non-stream | " + retryDiag);
                        Logger.log("[AI_DIAG][STREAM_ERROR_RETRY_NONSTREAM] " + retryDiag);
                        diag("STREAM_ERROR_RETRY_NONSTREAM", retryDiag);
                        cleanupOverrides();

                        ArrayList<Attempt> retryAttempts = new ArrayList<>();
                        try { retryAttempts.add(new Attempt(attempt.client, SPManager.STREAM_MODE_TYPEWRITER, attempt.baseUrlOverride)); } catch (Throwable ignored) {}
                        for (int i = attemptIndex + 1; i < attempts.size(); i++) {
                            try { retryAttempts.add(attempts.get(i)); } catch (Throwable ignored) {}
                        }
                        if (retryAttempts.isEmpty()) retryAttempts = attempts;

                        startAttemptInternal(requestId, retryAttempts, 0, prompt, systemMessage,
                                originalPrompt, resolvedRoleId, useConversationMemory, assistantBuffer,
                                maxTokensOverride, normalThinking, reasoningThinkingMode, roleSubModelOverride, paramState);
                        return;
                    } else if (assistantBuffer.length() == 0 && currentAttemptIsStreamLike && streamParseFailure) {
                        String skipDiag = buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking)
                                + " | reason=stream_parse_error_no_fallback | fallbackEnabled=" + fallbackToNonStreamEnabled;
                        Logger.error("[AI_DIAG][STREAM_ERROR_NO_FALLBACK] " + skipDiag);
                        diag("STREAM_ERROR_NO_FALLBACK", skipDiag);
                    }
                } catch (Throwable ignored) {}

                // 0) Native reasoning parameter unsupported -> cache false and retry same attempt with prompt-hint fallback.
                boolean canRetryReasoningNative = paramState != null
                        && !paramState.retriedReasoning
                        && nativeReasoningAttempted
                        && assistantBuffer.length() == 0
                        && (tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "reasoning")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "reasoning_effort")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "reasoning.effort"));

                if (canRetryReasoningNative) {
                    paramState.retriedReasoning = true;
                    try {
                        if (client != null && SPManager.isReady()) {
                            SPManager.getInstance().setCachedSupportsReasoningThinking(client.getLanguageModel(), client.getSubModel(), false);
                        }
                    } catch (Throwable ignored) {}

                    cleanupOverrides();
                    startAttemptInternal(requestId, attempts, attemptIndex, prompt, systemMessage,
                            originalPrompt, resolvedRoleId, useConversationMemory, assistantBuffer,
                            maxTokensOverride, normalThinking, reasoningThinkingMode, roleSubModelOverride, paramState);
                    return;
                }

                // 1) Parameter downgrade retry (max tokens) - retry same attempt once.
                // 1) Parameter downgrade retry (max tokens) - retry same attempt once.
                boolean canRetryMaxTok = paramState != null
                        && !paramState.retriedMaxTokens
                        && assistantBuffer.length() == 0
                        && !tn.eluea.kgpt.llm.ModelCapabilities.isThinkingBudgetParamConflict(t)
                        && (tn.eluea.kgpt.llm.ModelCapabilities.isLikelyMaxTokensConstraintError(t)
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_tokens")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_completion_tokens")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "maxoutputtokens"));

                if (canRetryMaxTok) {
                    paramState.retriedMaxTokens = true;

                    int current = maxTokensOverride;
                    int safe = current;
                    Integer suggested = tn.eluea.kgpt.llm.ModelCapabilities.extractSuggestedMaxTokens(t);
                    Integer hard = tn.eluea.kgpt.llm.ModelCapabilities.extractCompletionHardCap(t);
                    if (suggested != null && suggested > 0) {
                        safe = Math.min(current, suggested);
                    }

                    // Hard cap (API compliance) should win.
                    if (hard != null && hard > 0) {
                        safe = Math.min(safe, hard);
                    }

                    // If we couldn't infer a number (or failed to reduce), fall back to a sane conservative output limit.
                    if (safe <= 0 || safe >= current) {
                        if (current > 8192) safe = 8192;
                        else if (current > 4096) safe = 4096;
                        else if (current > 2048) safe = 2048;
                        else if (current > 1024) safe = 1024;
                        else safe = Math.max(256, current / 2);
                    }
                    try {
                        Logger.log("MAXTOK_RETRY", "provider=" + (client == null ? "null" : client.getLanguageModel())
                                + ", subModel=" + (client == null ? "null" : client.getSubModel())
                                + ", current=" + current + ", suggested=" + String.valueOf(suggested)
                                + ", hard=" + String.valueOf(hard)
                                + ", safe=" + safe + ", err=" + String.valueOf(t == null ? null : t.getMessage()));
                    } catch (Throwable ignored) {}
                    if (safe < current) {
                        // Persist provider hard cap (compliance constraint), even if auto-learning is disabled.
                        try {
                            if (client != null && SPManager.isReady() && hard != null && hard > 0) {
                                SPManager.getInstance().recordCachedHardMaxTokens(client.getLanguageModel(), client.getSubModel(), hard,
                                        "api_error:max_completion_tokens",
                                        (t == null ? null : (t.getMessage() != null ? t.getMessage() : t.toString())));
                                diag("OUTLEN_HARD_CAP", "provider=" + client.getLanguageModel() + ", subModel=" + client.getSubModel()
                                        + ", hard=" + hard);
                            }
                        } catch (Throwable ignored) {}

                        try {
                            if (mInteractor != null) {
                                final int _safeTok = safe;
                                mInteractor.runOnUiThread(() -> {
                if (mActiveRequestId != requestId) return;
                                    try {
                                        String suffix = "";
                                        try {
                                            if (client != null && SPManager.isReady()) {
                                                SPManager spm = SPManager.getInstance();
                                                LanguageModel _p = client.getLanguageModel();
                                                String _m = client.getSubModel();
                                                boolean allowLearn = spm.shouldAutoLearnOutputCap(_p, _m, false);
                                                if (!allowLearn) {
                                                    suffix = "（自动学习已关闭）";
                                                } else {
                                                    boolean protectManual = spm.isOutputCapManualProtectEnabled(_p, _m) && spm.hasManualOutputCapResult(_p, _m);
                                                    if (protectManual) suffix = "（已保护手动结果）";
                                                }
                                            }
                                        } catch (Throwable ignored2) {}
                                        mInteractor.toastShort("本次请求因模型限制临时降至 " + _safeTok + " tokens" + suffix);
                                    } catch (Throwable ignoredToast) {}
                                });
                            }
                        } catch (Throwable ignoredToastOuter) {}

                        try {
                            if (client != null && SPManager.isReady()) {
                                SPManager spm = SPManager.getInstance();
                                LanguageModel _p = client.getLanguageModel();
                                String _m = client.getSubModel();
                                boolean learnedWritten = false;
                                boolean allowLearn = spm.shouldAutoLearnOutputCap(_p, _m, false);
                                if (allowLearn) {
                                    boolean protectManual = spm.isOutputCapManualProtectEnabled(_p, _m) && spm.hasManualOutputCapResult(_p, _m);
                                    if (protectManual) {
                                        spm.recordAutoObservedSafeMaxTokens(_p, _m, safe, "auto_retry_max_tokens_observe");
                                    } else {
                                        spm.recordCachedSafeMaxTokensLearned(_p, _m, safe, "auto_retry_max_tokens");
                                        learnedWritten = true;
                                    }
                                }
                                try { spm.setCachedSafeMaxTokensLastErrorRaw(_p, _m, safeDiagnosticString(t == null ? null : t.getMessage())); } catch (Throwable ignored2) {}
                                int synced = -1;
                                if (learnedWritten && spm.shouldAutoSyncOutputLengthAfterAutoLearn(_p, _m)) {
                                    int beforeSel = -1;
                                    try { beforeSel = spm.getMaxTokensLimit(); } catch (Throwable ignored2) {}
                                    synced = spm.syncOutputLengthToLearnedCap(_p, _m, safe, "GenerativeAIController.retry_max_tokens");
                                    boolean changed = (beforeSel > 0 && synced > 0 && synced != beforeSel);
                                    try {
                                        String r = "retry";
                                        if (suggested != null && suggested > 0) r = "learned";
                                        if (hard != null && hard > 0) r = (r.equals("retry") ? "hard" : (r + "+hard"));
                                        spm.recordLastMaxTokensDecision(_p, _m, requestId, current, safe, safe, hard, r, changed, synced);
                                    } catch (Throwable ignored3) {}
                                } else {
                                    try {
                                        String r = "retry";
                                        if (suggested != null && suggested > 0) r = "learned";
                                        if (hard != null && hard > 0) r = (r.equals("retry") ? "hard" : (r + "+hard"));
                                        spm.recordLastMaxTokensDecision(_p, _m, requestId, current, safe, safe, hard, r, false, 0);
                                    } catch (Throwable ignored3) {}
                                }
                                try {
                                    Logger.log("OUTLEN_LEARN", (learnedWritten ? "learned" : "observed") + "+sync=" + synced + " provider=" + _p
                                            + ", subModel=" + _m
                                            + ", safe=" + safe);
                                } catch (Throwable ignoredLog) {}
                            }
                        } catch (Throwable ignored) {}

                        try {
                            boolean allowLearn2 = false;
                            boolean protect2 = false;
                            try {
                                if (client != null && SPManager.isReady()) {
                                    SPManager spm2 = SPManager.getInstance();
                                    allowLearn2 = spm2.shouldAutoLearnOutputCap(client.getLanguageModel(), client.getSubModel(), false);
                                    protect2 = spm2.isOutputCapManualProtectEnabled(client.getLanguageModel(), client.getSubModel())
                                            && spm2.hasManualOutputCapResult(client.getLanguageModel(), client.getSubModel());
                                }
                            } catch (Throwable ignored2) {}
                            diag("MAXTOK_FAILSAFE", "provider=" + (client == null ? "null" : client.getLanguageModel())
                                    + ", subModel=" + (client == null ? "null" : client.getSubModel())
                                    + ", current=" + current + ", safe=" + safe
                                    + ", suggested=" + String.valueOf(suggested)
                                    + ", hard=" + String.valueOf(hard)
                                    + ", allowLearn=" + allowLearn2 + ", protectManual=" + protect2);
                        } catch (Throwable ignored) {}

                        cleanupOverrides();
                        startAttemptInternal(requestId, attempts, attemptIndex, prompt, systemMessage,
                                originalPrompt, resolvedRoleId, useConversationMemory, assistantBuffer,
                                safe, normalThinking, reasoningThinkingMode, roleSubModelOverride, paramState);
                        return;
                    }
                }

                // 2) Parameter downgrade retry (sampling params) - retry same attempt once.
                boolean canRetrySampling = paramState != null
                        && !paramState.retriedSamplingParams
                        && assistantBuffer.length() == 0
                        && (tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "temperature")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "top_p")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "topP"));

                if (canRetrySampling) {
                    paramState.retriedSamplingParams = true;
                    try {
                        if (client != null && SPManager.isReady()) {
                            SPManager.getInstance().setCachedSupportsTemperature(client.getLanguageModel(), client.getSubModel(), false);
                        }
                    } catch (Throwable ignored) {}

                    cleanupOverrides();

                    // Retry the SAME attempt (same stream/baseUrl/model), but now the client will omit sampling params.
                    startAttemptInternal(requestId, attempts, attemptIndex, prompt, systemMessage,
                            originalPrompt, resolvedRoleId, useConversationMemory, assistantBuffer,
                            maxTokensOverride, normalThinking, reasoningThinkingMode, roleSubModelOverride, paramState);
                    return;
                }

                cleanupOverrides();

                // 3) Existing auto-downgrade retry only if we haven't emitted anything yet.
                boolean canRetry = (assistantBuffer.length() == 0) && (attemptIndex + 1 < attempts.size());
                if (canRetry) {
                    startAttemptInternal(requestId, attempts, attemptIndex + 1, prompt, systemMessage,
                            originalPrompt, resolvedRoleId, useConversationMemory, assistantBuffer, maxTokensOverride, normalThinking, reasoningThinkingMode, roleSubModelOverride, paramState);
                    return;
                }

                // Token-limit failures that still bubble up (e.g., fixed first档位/自定义值/供应商特殊校验) 给出更明确提示
                try {
                    if (assistantBuffer.length() == 0 && mInteractor != null
                            && (tn.eluea.kgpt.llm.ModelCapabilities.isLikelyMaxTokensConstraintError(t)
                                || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_tokens")
                                || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_completion_tokens")
                                || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "maxoutputtokens"))) {
                        boolean isCustomSel = false;
                        Integer capHint = null;
                        try { isCustomSel = SPManager.getInstance().getMaxTokensIsCustom(); } catch (Throwable ignored) {}
                        try { if (client != null && SPManager.isReady()) capHint = SPManager.getInstance().getCachedSafeMaxTokens(client.getLanguageModel(), client.getSubModel()); } catch (Throwable ignored) {}
                        final boolean customFinal = isCustomSel;
                        final Integer capFinal = (capHint != null && capHint > 0) ? capHint : null;
                        mInteractor.runOnUiThread(() -> {
                if (mActiveRequestId != requestId) return;
                            try {
                                String msg;
                                if (customFinal) {
                                    msg = (capFinal != null)
                                            ? ("当前为自定义输出长度，且该模型缓存上限约 " + capFinal + " tokens；请手动调低后重试")
                                            : "当前为自定义输出长度，模型拒绝该长度；请手动调低后重试";
                                } else {
                                    msg = (capFinal != null)
                                            ? ("模型拒绝当前输出长度（缓存上限约 " + capFinal + " tokens），请切换更小档位后重试")
                                            : "模型拒绝当前输出长度，请切换更小档位后重试";
                                }
                                mInteractor.toastLong(msg);
                            } catch (Throwable ignored2) {}
                        });
                    }
                } catch (Throwable ignoredToastOuter2) {}

                finishWithError(requestId, t);
            }

            @Override
            public void onComplete() {
                if (mActiveRequestId != requestId) return;
                if (completed || hasError) return;
                completed = true;

                boolean hasAssistantText = assistantBuffer.length() > 0;
                boolean streamEnabledForThisAttempt = false;
                int streamModeForThisAttempt = SPManager.STREAM_MODE_AUTO;
                boolean fallbackToNonStreamEnabled = false;
                try { streamEnabledForThisAttempt = SPManager.getInstance().getStreamingOutputEnabled(); } catch (Throwable ignored) {}
                try { streamModeForThisAttempt = SPManager.getInstance().getStreamingOutputModeForRequest(); } catch (Throwable ignored) {}
                try { fallbackToNonStreamEnabled = SPManager.getInstance().getStreamingOutputFallbackNonStreamEnabled(); } catch (Throwable ignored) {}

                if (!hasAssistantText) {
                    boolean currentAttemptIsStreamLike = isStreamLikeAttempt(attempt, streamEnabledForThisAttempt, streamModeForThisAttempt);

                    if (currentAttemptIsStreamLike && fallbackToNonStreamEnabled
                            && paramState != null && !paramState.retriedStreamEmptyNonStream) {
                        paramState.retriedStreamEmptyNonStream = true;
                        String emptyFallbackDiag = buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking);
                        Logger.log("AI empty stream completion -> retry non-stream | " + emptyFallbackDiag);
                        diag("EMPTY_STREAM_RETRY_NONSTREAM", emptyFallbackDiag);
                        cleanupOverrides();

                        ArrayList<Attempt> retryAttempts = new ArrayList<>();
                        try { retryAttempts.add(new Attempt(attempt.client, SPManager.STREAM_MODE_TYPEWRITER, attempt.baseUrlOverride)); } catch (Throwable ignored) {}
                        for (int i = attemptIndex + 1; i < attempts.size(); i++) {
                            try { retryAttempts.add(attempts.get(i)); } catch (Throwable ignored) {}
                        }
                        if (retryAttempts.isEmpty()) retryAttempts = attempts;

                        startAttemptInternal(requestId, retryAttempts, 0, prompt, systemMessage,
                                originalPrompt, resolvedRoleId, useConversationMemory, assistantBuffer,
                                maxTokensOverride, normalThinking, reasoningThinkingMode, roleSubModelOverride, paramState);
                        return;
                    }

                    RuntimeException emptyErr = new RuntimeException(currentAttemptIsStreamLike
                            ? "模型未返回可解析内容（已无输出）"
                            : "模型未返回内容");
                    Logger.error("AI request empty completion | " + buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking));
                    try {
                        String emptyDiag = buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking);
                        Logger.error("[AI_DIAG][EMPTY_COMPLETION] " + emptyDiag);
                        diag("EMPTY_COMPLETION", emptyDiag);
                    } catch (Throwable ignored) {}
                    cleanupOverrides();
                    finishWithError(requestId, emptyErr);
                    return;
                }

                try {
                    if (client != null && SPManager.isReady()) {
                        if (samplingParamsAttemptedFinal) {
                            SPManager.getInstance().setCachedSupportsTemperature(client.getLanguageModel(), client.getSubModel(), true);
                        }
                        if (nativeReasoningAttempted) {
                            SPManager.getInstance().setCachedSupportsReasoningThinking(client.getLanguageModel(), client.getSubModel(), true);
                        }
                    }
                } catch (Throwable ignored) {}
                try {
                    if (client != null && SPManager.isReady()) {
                        SPManager spm = SPManager.getInstance();
                        LanguageModel _p = client.getLanguageModel();
                        String _m = client.getSubModel();
                        try { spm.markSubModelLastUsed(_p, _m); } catch (Throwable ignoredMark) {}
                        if (maxTokensOverride > 0 && spm.shouldAutoLearnOutputCap(_p, _m, true)) {
                            boolean protectManual = spm.isOutputCapManualProtectEnabled(_p, _m) && spm.hasManualOutputCapResult(_p, _m);
                            if (protectManual) spm.recordAutoObservedSafeMaxTokensLowerBound(_p, _m, maxTokensOverride, "auto_success_lower_bound_observe");
                            else spm.recordCachedSafeMaxTokensLowerBoundLearned(_p, _m, maxTokensOverride, "auto_success_lower_bound");
                        }
                    }
                } catch (Throwable ignored) {}

                cleanupOverrides();

                // Save turn into memory
                try {
                    if (useConversationMemory) {
                        int mem = SPManager.getInstance().getConversationMemoryLevel();
                        if (mem > 0) {
                            String assistant = assistantBuffer.toString();
                            if (assistant != null && !assistant.trim().isEmpty()) {
                                synchronized (mRequestLock) {
                                    if (mActiveRequestId == requestId) editorMemory.addTurn(originalPrompt, assistant);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                Logger.log("AI request complete | " + buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking)
                        + " | chars=" + assistantBuffer.length());
                try {
                    String completeDiag = buildAiDiagnosticSnapshot(client, attempt, requestId, attemptIndex, maxTokensOverride, reasoningThinkingMode, normalThinking) + " | chars=" + assistantBuffer.length();
                    Logger.log("[AI_DIAG][REQ_COMPLETE] " + completeDiag);
                    diag("REQ_COMPLETE", completeDiag);
                } catch (Throwable ignored) {}

                // Notify complete
                if (mInteractor != null) {
                    mInteractor.runOnUiThread(() -> {
                if (mActiveRequestId != requestId) return;
                        for (GenerativeAIListener l : mListeners) {
                            try {
                                l.onAIComplete();
                            } catch (Throwable t) {
                                tn.eluea.kgpt.util.Logger.log(t);
                            }
                        }
                    });
                }

                finishAndMaybeRunPending(requestId);
            }
        });
        } catch (Throwable t) {
            tn.eluea.kgpt.util.Logger.log(t);
            try { diag("SUBSCRIBE_THROW", String.valueOf(t == null ? null : t.getMessage())); } catch (Throwable ignoredDiag) {}
            try { SPManager.clearThreadStreamingModeOverride(); } catch (Throwable ignored) {}
            try {
                if (client != null && prevSubModel != null) client.setField(LanguageModelField.SubModel, prevSubModel);
            } catch (Throwable ignored) {}
            try {
                if (client != null && prevMaxTokens != null) client.setField(LanguageModelField.MaxTokens, prevMaxTokens);
            } catch (Throwable ignored) {}
            try {
                if (client != null && prevBaseUrl != null) client.setField(LanguageModelField.BaseUrl, prevBaseUrl);
            } catch (Throwable ignored) {}
            try {
                if (client != null && prevTemperature != null) client.setField(LanguageModelField.Temperature, prevTemperature);
            } catch (Throwable ignored) {}
            finishWithError(requestId, t);
        }
    }

    private LanguageModelClient snapshotClient(LanguageModelClient source) {
        if (source == null) return null;
        LanguageModelClient copy = LanguageModelClient.forModel(source.getLanguageModel());
        for (LanguageModelField field : LanguageModelField.values()) copy.setField(field, source.getField(field));
        copy.setInternetProvider(mInternetProvider);
        return copy;
    }

    private void finishWithError(final int requestId, final Throwable t) {
        final tn.eluea.kgpt.llm.internet.RequestCancellation cancellation;
        final Subscription subscription;
        synchronized (mRequestLock) {
            if (mActiveRequestId != requestId) return;
            cancellation = networkCancellation;
            subscription = mCurrentSubscription;
        }
        if (cancellation != null) cancellation.cancel();
        if (subscription != null) subscription.cancel();
        // Notify error
        if (mInteractor != null) {
            mInteractor.runOnUiThread(() -> {
                if (mActiveRequestId != requestId) return;
                for (GenerativeAIListener l : mListeners) {
                    try {
                        l.onAIError(t);
                    } catch (Throwable t2) {
                        tn.eluea.kgpt.util.Logger.log(t2);
                    }
                }
            });
        }
        finishAndMaybeRunPending(requestId);
    }

    private void finishAndMaybeRunPending(final int requestId) {
        PendingRequest pending = null;
        synchronized (mRequestLock) {
            if (mActiveRequestId != requestId) {
                return;
            }
            mRequestInFlight = false;
            mCurrentSubscription = null;
            pending = mPendingRequest;
            mPendingRequest = null;
        }
        cancelRequestWatchdog(requestId);

        if (pending != null) {
            generateResponse(pending.prompt, pending.systemMessage, pending.roleIdOverride, pending.useConversationMemory);
        }
    }


    /**
     * User-initiated cancellation (best-effort).
     *
     * We intentionally make this "hard":
     * - Immediately invalidate the active request id so any late chunks/errors are ignored.
     * - Cancel the current subscription.
     * - Notify listeners with a special marker error that should be treated as silent.
     */
    public void cancelActiveRequestByUser() {
        final Subscription sub;
        final tn.eluea.kgpt.llm.internet.RequestCancellation cancellation;
        final int cancellationId;
        synchronized (mRequestLock) {
            if (!mRequestInFlight) {
                return;
            }
            sub = mCurrentSubscription;
            mCurrentSubscription = null;
            mRequestInFlight = false;
            mPendingRequest = null;
            // Invalidate current request id so late publisher callbacks are ignored.
            mActiveRequestId = mRequestSeq.incrementAndGet();
            cancellationId = mActiveRequestId;
            cancellation = networkCancellation;
            networkCancellation = null;
        }
        if (cancellation != null) cancellation.cancel();

        // Cancel watchdog regardless of request id.
        cancelRequestWatchdog(0);

        try {
            if (sub != null) {
                sub.cancel();
            }
        } catch (Throwable ignored) {}

        // Silent stop marker.
        if (mInteractor != null) {
            mInteractor.runOnUiThread(() -> {
                if (mActiveRequestId != cancellationId) return;
                for (GenerativeAIListener l : mListeners) {
                    try {
                        l.onAIError(new RuntimeException(USER_CANCELLED_MARKER));
                    } catch (Throwable t) {
                        tn.eluea.kgpt.util.Logger.log(t);
                    }
                }
            });
        }
    }


    public LanguageModel getLanguageModel() {
        return mModelClient.getLanguageModel();
    }

    public LanguageModelClient getModelClient() {
        return mModelClient;
    }
}
