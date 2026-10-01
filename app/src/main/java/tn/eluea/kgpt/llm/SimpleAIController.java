/*
 * Copyright (C) 2024-2025 Amr Aldeeb @Eluea
 * 
 * This file is part of KGPT - a fork of KeyboardGPT.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 */
package tn.eluea.kgpt.llm;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.util.AiDiagnostics;
import tn.eluea.kgpt.listener.GenerativeAIListener;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.llm.client.LanguageModelClient;
import tn.eluea.kgpt.llm.internet.SimpleInternetProvider;
import tn.eluea.kgpt.llm.publisher.SimpleStringPublisher;

/**
 * Simplified AI Controller for use in app context (not Xposed context).
 * Does not depend on MainHook or UiInteractor.
 */
public class SimpleAIController {
    private static final String TAG = "KGPT_SimpleAI";

    // Local request sequence (used only for Why-panel snapshot grouping in this process)
    private static final java.util.concurrent.atomic.AtomicInteger sReqSeq = new java.util.concurrent.atomic.AtomicInteger(0);


    /**
     * Run all blocking network calls off the main thread.
     * SimpleInternetProvider uses blocking HttpURLConnection.
     */
    private static final java.util.concurrent.ExecutorService REQUEST_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "KGPT-LLM-App");
                t.setDaemon(true);
                return t;
            });
    
    private LanguageModelClient mModelClient = null;
    private volatile int activeRequestId;
    private volatile Subscription activeSubscription;
    private volatile tn.eluea.kgpt.llm.internet.RequestCancellation activeCancellation;
    private final ConversationMemoryStore memory = new ConversationMemoryStore();

    public void cancelRequest() {
        activeRequestId = sReqSeq.incrementAndGet();
        tn.eluea.kgpt.llm.internet.RequestCancellation cancellation = activeCancellation;
        activeCancellation = null;
        if (cancellation != null) cancellation.cancel();
        Subscription subscription = activeSubscription;
        activeSubscription = null;
        if (subscription != null) subscription.cancel();
    }

    private final SPManager mSPManager;
    private final Handler mMainHandler;
    private final List<GenerativeAIListener> mListeners = new ArrayList<>();

    // Some prompt formats include "User:" / "Assistant:" labels. Many models will echo
    // the leading "Assistant:" at the start of the generated answer. Strip it once.
    private static final Pattern LEADING_ASSISTANT_LABEL =
            // Some models echo role tags in different languages.
            // Strip a single leading label once to keep UI + memory clean.
            Pattern.compile("(?is)^\\s*(assistant|\\u52a9\\u624b)\\s*[:：]\\s*");

    public SimpleAIController() {
        mSPManager = SPManager.getInstance();
        mMainHandler = new Handler(Looper.getMainLooper());
        
        if (mSPManager.hasLanguageModel()) {
            setModel(mSPManager.getLanguageModel());
        } else {
            mModelClient = LanguageModelClient.forModel(LanguageModel.Gemini);
        }
        
        // Set internet provider
        if (mModelClient != null) {
            mModelClient.setInternetProvider(new SimpleInternetProvider());
        }
    }

    private void setModel(LanguageModel model) {
        Log.d(TAG, "setModel " + model.label);
        mModelClient = LanguageModelClient.forModel(model);
        for (LanguageModelField field : LanguageModelField.values()) {
            mModelClient.setField(field, mSPManager.getLanguageModelField(model, field));
        }
        mModelClient.setInternetProvider(new SimpleInternetProvider());
    }

    public boolean needModelClient() {
        return mModelClient == null;
    }

    public boolean needApiKey() {
        return mModelClient == null || 
               mModelClient.getApiKey() == null || 
               mModelClient.getApiKey().isEmpty();
    }

    /** Best-effort provider name for diagnostics/UI. */
    public String getProviderNameSafe() {
        try {
            if (mModelClient == null || mModelClient.getLanguageModel() == null) return "";
            return String.valueOf(mModelClient.getLanguageModel());
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Best-effort sub-model name for diagnostics/UI. */
    public String getSubModelNameSafe() {
        try {
            if (mModelClient == null) return "";
            String sm = null;
            try { sm = mModelClient.getSubModel(); } catch (Throwable ignored) {}
            if (sm == null || sm.trim().isEmpty()) {
                try { sm = mModelClient.getField(tn.eluea.kgpt.llm.LanguageModelField.SubModel); } catch (Throwable ignored2) {}
            }
            return sm == null ? "" : sm;
        } catch (Throwable ignored) {
            return "";
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
     * Generate response with optional multi-turn memory.
     */
    public void generateResponse(String prompt, String systemMessage, String roleIdOverride, boolean useConversationMemory) {
        generateResponseInternal(prompt, systemMessage, roleIdOverride, useConversationMemory, null);
    }

    /**
     * Generate response for a prompt + a single attached image (data URI).
     * This is used by the in-app AI Chat page.
     */
    public void generateResponseWithImageDataUri(String prompt, String imageDataUri, boolean useConversationMemory) {
        if (imageDataUri == null || imageDataUri.trim().isEmpty()) {
            generateResponseInternal(prompt, null, null, useConversationMemory, null);
            return;
        }
        generateResponseInternal(prompt, null, null, useConversationMemory, Collections.singletonList(imageDataUri));
    }

    /**
     * Generate response for a prompt + multiple attached images (data URIs).
     * The controller will try to submit a multimodal request for OpenAI-compatible
     * providers; otherwise it will fall back to a plain-text prompt.
     */
    public void generateResponseWithImageDataUris(String prompt, List<String> imageDataUris, boolean useConversationMemory) {
        if (imageDataUris == null || imageDataUris.isEmpty()) {
            generateResponseInternal(prompt, null, null, useConversationMemory, null);
            return;
        }
        generateResponseInternal(prompt, null, null, useConversationMemory, imageDataUris);
    }

    private void generateResponseInternal(String prompt, String systemMessage, String roleIdOverride, boolean useConversationMemory, List<String> imageDataUris) {
        if (prompt == null || prompt.trim().isEmpty()) return;
        cancelRequest();
        final int requestId = sReqSeq.incrementAndGet();
        activeRequestId = requestId;
        final tn.eluea.kgpt.llm.internet.RequestCancellation cancellation =
                new tn.eluea.kgpt.llm.internet.RequestCancellation();
        activeCancellation = cancellation;
        // Each request owns its model fields, so late retries cannot alter another request.
        final LanguageModelClient requestClient = LanguageModelClient.forModel(mSPManager.getLanguageModel());
        for (LanguageModelField field : LanguageModelField.values())
            requestClient.setField(field, mSPManager.getLanguageModelField(requestClient.getLanguageModel(), field));
        requestClient.setInternetProvider(new SimpleInternetProvider());

        // Apply custom role (system prompt) if configured
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
        final List<String> imageDataUrisFinal = imageDataUris;
        try {
            if (useConversationMemory) {
                SPManager sp = SPManager.getInstance();
                int mem = sp.getConversationMemoryLevel();
                if (mem > 0) {
                    String modelLabel = "";
                    try {
                        if (requestClient != null && requestClient.getLanguageModel() != null)
                            modelLabel = requestClient.getLanguageModel().name();
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
                        if ((subModelLabel == null || subModelLabel.trim().isEmpty()) && requestClient != null) {
                            String sm = null;
                            try { sm = requestClient.getSubModel(); } catch (Throwable ignored3) {}
                            if (sm == null || sm.trim().isEmpty()) {
                                try { sm = requestClient.getField(tn.eluea.kgpt.llm.LanguageModelField.SubModel); } catch (Throwable ignored4) {}
                            }
                            if (sm != null) subModelLabel = sm.trim();
                        }
                    } catch (Throwable ignored3) {}

                    String scope = (modelLabel == null ? "" : modelLabel)
                            + "|" + (subModelLabel == null ? "" : subModelLabel)
                            + "|" + (resolvedRoleId == null ? "" : resolvedRoleId);
                    memory.ensureScope(scope);
                    boolean autoSummarize = false;
                    try { autoSummarize = sp.getAutoSummarizeOldContextEnabled(); } catch (Throwable ignored3) {}
                    prompt = memory.buildPromptWithHistory(prompt, mem, autoSummarize);
                }
            }
        } catch (Throwable ignored) {}

        // Notify prepare on main thread
        mMainHandler.post(() -> {
                    if (activeRequestId != requestId || cancellation.isCancelled()) return;
            for (GenerativeAIListener l : mListeners) {
                l.onAIPrepare();
            }
        });

        // Max tokens preset (Short / Medium / Long)
        int maxTokensOverride = 0;
        try { maxTokensOverride = SPManager.getInstance().getMaxTokensLimit(); } catch (Throwable ignored) {}
        final int userSlotTokens = maxTokensOverride;

        // Scheme-2 safety: if we have a cached safe max tokens for this model, clamp to it.
        try {
            if (requestClient != null && SPManager.isReady()) {
                Integer cap = SPManager.getInstance().getCachedSafeMaxTokens(requestClient.getLanguageModel(), requestClient.getSubModel());
                if (cap != null && cap > 0 && maxTokensOverride > cap) {
                    maxTokensOverride = cap;
                }
            }
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

        // Capture final copies for lambda usage (prompt/systemMessage may be reassigned above)
        final String promptFinal = prompt;
        final String systemMessageFinal = systemMessage;
        final int reasoningThinkingModeFinal = reasoningThinkingMode;

        String prevMaxTokens = null;
        try { if (requestClient != null) prevMaxTokens = requestClient.getField(LanguageModelField.MaxTokens); } catch (Throwable ignored) {}
        final String prevMaxTokensFinal = prevMaxTokens;
        String prevTemperature = null;
        try { if (requestClient != null) prevTemperature = requestClient.getField(LanguageModelField.Temperature); } catch (Throwable ignored) {}
        final String prevTemperatureFinal = prevTemperature;
        String prevSubModel = null;
        try { if (requestClient != null) prevSubModel = requestClient.getField(LanguageModelField.SubModel); } catch (Throwable ignored) {}
        final String prevSubModelFinal = prevSubModel;
        // Track the effective max tokens used for scheme-2 retry.
        final int[] maxTokensEffective = new int[]{maxTokensOverride};

        // Pre-clamp by learned cap and provider hard cap to avoid repeated invalid requests.
        try {
            if (requestClient != null && SPManager.isReady() && maxTokensEffective[0] > 0) {
                int req = maxTokensEffective[0];
                int eff = req;
                SPManager spm = SPManager.getInstance();
                Integer learned = spm.getCachedSafeMaxTokens(requestClient.getLanguageModel(), requestClient.getSubModel());
                if (learned != null && learned > 0) eff = Math.min(eff, learned);
                Integer hard = spm.getCachedHardMaxTokens(requestClient.getLanguageModel(), requestClient.getSubModel());
                if (hard != null && hard > 0) eff = Math.min(eff, hard);
                if (eff != req && eff > 0) {
                    maxTokensEffective[0] = eff;
                    try {
                        AiDiagnostics.append("MAXTOK_PRECLAMP", "provider=" + requestClient.getLanguageModel()
                                + ", subModel=" + requestClient.getSubModel()
                                + ", req=" + req + ", eff=" + eff
                                + ", learned=" + String.valueOf(learned) + ", hard=" + String.valueOf(hard));
                    // Record hard-cap hit for management UI.
                    try {
                        if (hard != null && hard > 0 && req > hard) {
                            spm.recordHardCapLastHit(
                                    requestClient.getLanguageModel(),
                                    requestClient.getSubModel(),
                                    "preclamp " + req + " → " + eff + " (hard=" + hard + ")"
                            );
                        }
                    } catch (Throwable ignoredHardHit) {}
                    } catch (Throwable ignored) {}
                }

                // Record last decision snapshot for UI "Why" panel.
                try {
                    String reason = "user";
                    if (eff != req) {
                        boolean usedLearned = (learned != null && learned > 0 && req > learned);
                        boolean usedHard = (hard != null && hard > 0 && req > hard);
                        if (usedLearned) reason = "learned";
                        if (usedHard) reason = (reason.equals("user") ? "hard" : (reason + "+hard"));
                    }
                    spm.recordLastMaxTokensDecision(
                            requestClient.getLanguageModel(),
                            requestClient.getSubModel(),
                            requestId,
                            userSlotTokens,
                            (maxTokensEffective[0] > 0 ? maxTokensEffective[0] : req),
                            learned,
                            hard,
                            reason,
                            false,
                            0
                    );
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}

        try {
            if (requestClient != null && maxTokensEffective[0] > 0) {
                requestClient.setField(LanguageModelField.MaxTokens, String.valueOf(maxTokensEffective[0]));
            }
        } catch (Throwable ignored) {}

        try {
            if (requestClient != null && roleSubModelOverride != null && !roleSubModelOverride.trim().isEmpty()) {
                requestClient.setField(LanguageModelField.SubModel, roleSubModelOverride.trim());
            }
        } catch (Throwable ignored) {}

        try {
            if (requestClient != null && tn.eluea.kgpt.llm.ModelCapabilities.supportsTemperature(requestClient.getLanguageModel(), requestClient.getSubModel())) {
                float v = normalThinking;
                if (v < 0.0f) v = 0.0f;
                if (v > 1.8f) v = 1.8f;
                v = Math.round(v * 10.0f) / 10.0f;
                requestClient.setField(LanguageModelField.Temperature, String.format(Locale.US, "%.1f", v));
            }
        } catch (Throwable ignored) {}

        Publisher<String> publisher;

        final StringBuilder assistantBuffer = new StringBuilder();
        final boolean[] retriedSamplingParams = new boolean[]{false};
        final boolean[] retriedMaxTokens = new boolean[]{false};

        final Runnable[] startRequest = new Runnable[1];
        startRequest[0] = () -> {
            if (activeRequestId != requestId || cancellation.isCancelled()) return;
            tn.eluea.kgpt.llm.internet.RequestCancellation.bind(cancellation);
            try {
            Publisher<String> pub;
            if (needModelClient() || needApiKey()) {
                pub = new SimpleStringPublisher("Missing API Key. Please configure your API key in KGPT settings.");
            } else {
                String effectiveSystemMessage = systemMessageFinal;
                try {
                    if (requestClient != null && tn.eluea.kgpt.llm.ModelCapabilities.supportsReasoningThinking(requestClient.getLanguageModel(), requestClient.getSubModel())) {
                        effectiveSystemMessage = tn.eluea.kgpt.ui.lab.ReasoningModelThinkingOptions.applyToSystemMessage(systemMessageFinal, reasoningThinkingModeFinal);
                    }
                } catch (Throwable ignored) {}
                // Multimodal (single image) is only supported by OpenAI-compatible clients.
                // If not supported, fall back to plain-text prompt.
                if (imageDataUrisFinal != null
                        && !imageDataUrisFinal.isEmpty()
                        && (requestClient instanceof tn.eluea.kgpt.llm.client.ChatGPTClient)) {
                    pub = ((tn.eluea.kgpt.llm.client.ChatGPTClient) requestClient)
                            .submitPromptWithImageDataUris(promptFinal, effectiveSystemMessage, imageDataUrisFinal);
                } else {
                    pub = requestClient.submitPrompt(promptFinal, effectiveSystemMessage);
                }
            }

            pub.subscribe(new Subscriber<String>() {
            boolean completed = false;
            boolean hasError = false;
            boolean checkedLeadingLabel = false;
            final StringBuilder leadingLabelBuffer = new StringBuilder();

            private void restoreOverrides() {
                try {
                    if (requestClient != null && prevSubModelFinal != null) {
                        requestClient.setField(LanguageModelField.SubModel, prevSubModelFinal);
                    }
                } catch (Throwable ignored) {}

                try {
                    if (requestClient != null && prevMaxTokensFinal != null) {
                        requestClient.setField(LanguageModelField.MaxTokens, prevMaxTokensFinal);
                    }
                } catch (Throwable ignored) {}

                try {
                    if (requestClient != null && prevTemperatureFinal != null) {
                        requestClient.setField(LanguageModelField.Temperature, prevTemperatureFinal);
                    }
                } catch (Throwable ignored) {}
            }

@Override
            public void onSubscribe(Subscription s) {
                if (activeRequestId != requestId || cancellation.isCancelled()) { s.cancel(); return; }
                activeSubscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String s) {
                if (activeRequestId != requestId || cancellation.isCancelled() || completed) return;
                if (LanguageModelClient.INTERNAL_KEEPALIVE_MARKER.equals(s)) return;
                if (s == null || s.isEmpty()) {
                    return;
                }

                // Strip a leading "Assistant:" / "助手：" label once.
                // For streaming, the label may be split across chunks, so we buffer a bit
                // until we can decide.
                if (!checkedLeadingLabel) {
                    try {
                        leadingLabelBuffer.append(s);
                        String buf = leadingLabelBuffer.toString();

                        // Wait for ':' / '：' or enough chars.
                        boolean hasColon = buf.contains(":") || buf.contains("：");
                        String candidate = buf.trim().toLowerCase(java.util.Locale.ROOT);
                        boolean possibleLabel = candidate.isEmpty() || "assistant".startsWith(candidate)
                                || "助手".startsWith(candidate) || candidate.matches("(?:assistant|助手)\\s*");
                        if (!hasColon && possibleLabel && leadingLabelBuffer.length() < 16) {
                            return;
                        }

                        checkedLeadingLabel = true;
                        String stripped = LEADING_ASSISTANT_LABEL.matcher(buf).replaceFirst("");
                        if (stripped == null) stripped = "";
                        s = stripped;
                    } catch (Throwable ignored) {
                        checkedLeadingLabel = true;
                    }

                    if (s == null || s.isEmpty()) {
                        return;
                    }
                }

                try { assistantBuffer.append(s); } catch (Throwable ignored) {}

                Log.d(TAG, "onNext: string with length " + s.length());

                // 's' is reassigned above when stripping the role label, so it is not
                // effectively-final. Copy it into a final variable for the UI thread.
                final String chunk = s;

                mMainHandler.post(() -> {
                    if (activeRequestId != requestId || cancellation.isCancelled()) return;
                    for (GenerativeAIListener l : mListeners) {
                        l.onAINext(chunk);
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                if (activeRequestId != requestId || cancellation.isCancelled()) return;
                if (completed || hasError) {
                    Log.d(TAG, "Skipping duplicate onError");
                    return;
                }

                // Auto-downgrade: if the provider rejects sampling params (temperature/top_p),
                // cache that capability as unsupported and retry once without them.
                boolean canRetrySampling = !retriedSamplingParams[0]
                        && assistantBuffer.length() == 0
                        && (tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "temperature")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "top_p")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "topP"));

                if (canRetrySampling) {
                    retriedSamplingParams[0] = true;
                    try {
                        if (requestClient != null && SPManager.isReady()) {
                            SPManager.getInstance().setCachedSupportsTemperature(requestClient.getLanguageModel(), requestClient.getSubModel(), false);
                        }
                    } catch (Throwable ignored) {}

                    Log.w(TAG, "Sampling params rejected by model; retrying without temperature/top_p", t);
                    try {
                        REQUEST_EXECUTOR.execute(startRequest[0]);
                        return;
                    } catch (Throwable ignored) {
                        // Fall through to normal error handling.
                    }
                }

                // Scheme-2 safety: if the provider rejects the requested output token length,
                // retry once with a smaller safe value and cache it for this sub-model.
                boolean canRetryMaxTok = !retriedMaxTokens[0]
                        && assistantBuffer.length() == 0
                        && !tn.eluea.kgpt.llm.ModelCapabilities.isThinkingBudgetParamConflict(t)
                        && (tn.eluea.kgpt.llm.ModelCapabilities.isLikelyMaxTokensConstraintError(t)
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_tokens")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_completion_tokens")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "maxoutputtokens"));

                if (canRetryMaxTok) {
                    retriedMaxTokens[0] = true;

                    int current = maxTokensEffective[0];
                    int safe = current;
                    Integer suggested = tn.eluea.kgpt.llm.ModelCapabilities.extractSuggestedMaxTokens(t);
                    Integer hard = tn.eluea.kgpt.llm.ModelCapabilities.extractCompletionHardCap(t);
                    if (suggested != null && suggested > 0) {
                        // Be conservative: if this is a context-window number, using it directly as output
                        // is still safer than requesting something huge.
                        safe = Math.min(current, suggested);
                    }
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
                    // Ensure we actually reduce.
                    if (safe < current) {
                        maxTokensEffective[0] = safe;
                        try {
                            if (requestClient != null) {
                                requestClient.setField(LanguageModelField.MaxTokens, String.valueOf(safe));
                            }
                        } catch (Throwable ignored) {}
                        try {
                            if (requestClient != null && SPManager.isReady()) {
                                SPManager spm = SPManager.getInstance();
                                LanguageModel _p = requestClient.getLanguageModel();
                                String _m = requestClient.getSubModel();
                                boolean learnedWritten = false;
                                // Record hard cap (compliance) even if auto-learning is off.
                                if (hard != null && hard > 0) {
                                    try { spm.recordCachedHardMaxTokens(_p, _m, hard, "api_error:max_completion_tokens", (t == null ? null : (t.getMessage() != null ? t.getMessage() : t.toString()))); } catch (Throwable ignored2) {}
                                    try { AiDiagnostics.append("OUTLEN_HARD_CAP", "provider=" + _p + ", subModel=" + _m + ", hard=" + hard); } catch (Throwable ignored2) {}
                                }

                                if (spm.shouldAutoLearnOutputCap(_p, _m, false)) {
                                    boolean protectManual = spm.isOutputCapManualProtectEnabled(_p, _m) && spm.hasManualOutputCapResult(_p, _m);
                                    if (protectManual) {
                                        spm.recordAutoObservedSafeMaxTokens(_p, _m, safe, "auto_retry_max_tokens_observe");
                                    } else {
                                        spm.recordCachedSafeMaxTokensLearned(_p, _m, safe, "auto_retry_max_tokens");
                                        learnedWritten = true;
                                    }
                                }
                                try { spm.setCachedSafeMaxTokensLastErrorRaw(_p, _m, String.valueOf(t == null ? null : t.getMessage())); } catch (Throwable ignored2) {}
                                int synced = -1;
                                if (learnedWritten && spm.shouldAutoSyncOutputLengthAfterAutoLearn(_p, _m)) {
                                    int beforeSel = -1;
                                    try { beforeSel = spm.getMaxTokensLimit(); } catch (Throwable ignored2) {}
                                    synced = spm.syncOutputLengthToLearnedCap(_p, _m, safe, "SimpleAIController.retry_max_tokens");
                                    boolean changed = (beforeSel > 0 && synced > 0 && synced != beforeSel);
                                    try {
                                        String r = "retry";
                                        if (suggested != null && suggested > 0) r = "learned";
                                        if (hard != null && hard > 0) r = (r.equals("retry") ? "hard" : (r + "+hard"));
                                        spm.recordLastMaxTokensDecision(_p, _m, requestId, userSlotTokens, safe, safe, hard, r, changed, synced);
                                    } catch (Throwable ignored3) {}
                                } else {
                                    try {
                                        String r = "retry";
                                        if (suggested != null && suggested > 0) r = "learned";
                                        if (hard != null && hard > 0) r = (r.equals("retry") ? "hard" : (r + "+hard"));
                                        spm.recordLastMaxTokensDecision(_p, _m, requestId, userSlotTokens, safe, safe, hard, r, false, 0);
                                    } catch (Throwable ignored3) {}
                                }
                                try {
                                    tn.eluea.kgpt.util.Logger.log("OUTLEN_LEARN", (learnedWritten ? "learned" : "observed") + "+sync=" + synced + " provider=" + _p
                                            + ", subModel=" + _m
                                            + ", safe=" + safe);
                                } catch (Throwable ignoredLog) {}
                            }
                        } catch (Throwable ignored) {}

                        try {
                            AiDiagnostics.append("MAXTOK_FAILSAFE", "provider=" + (requestClient == null ? "null" : requestClient.getLanguageModel())
                                    + ", subModel=" + (requestClient == null ? "null" : requestClient.getSubModel())
                                    + ", current=" + current + ", safe=" + safe
                                    + ", suggested=" + String.valueOf(suggested)
                                    + ", hard=" + String.valueOf(hard));
                        } catch (Throwable ignored) {}

                        Log.w(TAG, "Max tokens rejected by model; retrying with safe max_tokens=" + safe, t);
                        try {
                            REQUEST_EXECUTOR.execute(startRequest[0]);
                            return;
                        } catch (Throwable ignored) {
                            // Fall through to normal error handling.
                        }
                    }
                }

                hasError = true;
                completed = true;

                restoreOverrides();

                Log.e(TAG, "AI request error", t);

                try {
                    if (assistantBuffer.length() == 0
                            && (tn.eluea.kgpt.llm.ModelCapabilities.isLikelyMaxTokensConstraintError(t)
                                || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_tokens")
                                || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_completion_tokens")
                                || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "maxoutputtokens"))) {
                        boolean isCustomSel = false;
                        Integer capHint = null;
                        try { isCustomSel = SPManager.getInstance().getMaxTokensIsCustom(); } catch (Throwable ignored) {}
                        try { if (requestClient != null && SPManager.isReady()) capHint = SPManager.getInstance().getCachedSafeMaxTokens(requestClient.getLanguageModel(), requestClient.getSubModel()); } catch (Throwable ignored) {}
                        final boolean customFinal = isCustomSel;
                        final Integer capFinal = (capHint != null && capHint > 0) ? capHint : null;
                        mMainHandler.post(() -> {
                    if (activeRequestId != requestId || cancellation.isCancelled()) return;
                            try {
                                String msg = customFinal
                                        ? ((capFinal != null) ? ("当前为自定义输出长度，缓存上限约 " + capFinal + " tokens；请手动调低后重试") : "当前为自定义输出长度，模型拒绝该长度；请手动调低后重试")
                                        : ((capFinal != null) ? ("模型拒绝当前输出长度（缓存上限约 " + capFinal + " tokens），请切换更小档位") : "模型拒绝当前输出长度，请切换更小档位");
                                android.widget.Toast.makeText(tn.eluea.kgpt.KGPTApplication.getContext(), msg, android.widget.Toast.LENGTH_LONG).show();
                            } catch (Throwable ignored2) {}
                        });
                    }
                } catch (Throwable ignoredToast) {}

                mMainHandler.post(() -> {
                    if (activeRequestId != requestId || cancellation.isCancelled()) return;
                    for (GenerativeAIListener l : mListeners) {
                        l.onAIError(t);
                    }
                });
            }

            @Override
            public void onComplete() {
                if (activeRequestId != requestId || cancellation.isCancelled()) return;
                if (completed || hasError) {
                    Log.d(TAG, "Skipping duplicate onComplete");
                    return;
                }
                if (!checkedLeadingLabel && leadingLabelBuffer.length() > 0) {
                    checkedLeadingLabel = true;
                    onNext(LEADING_ASSISTANT_LABEL.matcher(leadingLabelBuffer.toString()).replaceFirst(""));
                }
                completed = true;
                restoreOverrides();

                Log.d(TAG, "AI request completed");

                try {
                    if (requestClient != null && SPManager.isReady()) {
                        SPManager spm = SPManager.getInstance();
                        LanguageModel _p = requestClient.getLanguageModel();
                        String _m = requestClient.getSubModel();
                        try { spm.markSubModelLastUsed(_p, _m); } catch (Throwable ignoredMark) {}
                        if (maxTokensEffective[0] > 0 && spm.shouldAutoLearnOutputCap(_p, _m, true)) {
                            boolean protectManual = spm.isOutputCapManualProtectEnabled(_p, _m) && spm.hasManualOutputCapResult(_p, _m);
                            if (protectManual) spm.recordAutoObservedSafeMaxTokensLowerBound(_p, _m, maxTokensEffective[0], "auto_success_lower_bound_observe");
                            else spm.recordCachedSafeMaxTokensLowerBoundLearned(_p, _m, maxTokensEffective[0], "auto_success_lower_bound");
                        }
                    }
                } catch (Throwable ignored) {}

                // Save turn into memory
                try {
                    if (useConversationMemory) {
                        int mem = SPManager.getInstance().getConversationMemoryLevel();
                        if (mem > 0) {
                            String assistant = assistantBuffer.toString();
                            if (assistant != null && !assistant.trim().isEmpty()) {
                                memory.addTurn(originalPrompt, assistant);
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                mMainHandler.post(() -> {
                    if (activeRequestId != requestId || cancellation.isCancelled()) return;
                    for (GenerativeAIListener l : mListeners) {
                        l.onAIComplete();
                    }
                });
            }
        });
            } catch (Throwable error) {
                mMainHandler.post(() -> {
                    if (activeRequestId != requestId || cancellation.isCancelled()) return;
                    for (GenerativeAIListener listener : mListeners) listener.onAIError(error);
                });
            } finally { tn.eluea.kgpt.llm.internet.RequestCancellation.unbind(); }
        };

        // Start first attempt
        REQUEST_EXECUTOR.execute(startRequest[0]);
    }


    public LanguageModel getLanguageModel() {
        return mModelClient != null ? mModelClient.getLanguageModel() : LanguageModel.Gemini;
    }
}
