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
package tn.eluea.kgpt.ui.main.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import androidx.core.widget.NestedScrollView;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.ViewCompat;
import androidx.core.graphics.Insets;
import android.view.ViewTreeObserver;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import tn.eluea.kgpt.roles.RoleManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.ui.main.BottomSheetHelper;
import tn.eluea.kgpt.ui.main.FloatingBottomSheet;
import tn.eluea.kgpt.ui.main.adapters.ModelsAdapter;
import tn.eluea.kgpt.util.PrefsCompat;

public class ModelsFragment extends Fragment implements ModelsAdapter.OnModelSelectedListener {

    private static final String PREF_AMOLED = "amoled_mode";
    private static final String PREF_THEME = "theme_mode";

    private RecyclerView rvModels;
    private ChipGroup chipGroupSubmodels;
    private TextInputEditText etSubModel;
    private TextInputEditText etBaseUrl;
    private MaterialButton btnSave;
    private MaterialButton btnFetchModels;
    private View rootView;

    // Keyboard avoidance helpers (IME + OEM fallback)
    private ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener;
    private int lastSystemBarsBottomPx = 0;
    private int lastKeyboardHeightFallbackPx = 0;
    private int lastVisibleFrameBottomPx = 0;


    private ModelsAdapter adapter;
    private LanguageModel selectedModel;

    // Sub model presets for each provider (validated and working models)
    private static final Map<LanguageModel, String[]> SUB_MODEL_PRESETS = new HashMap<>();

    // All valid model names for validation
    private static final Map<LanguageModel, java.util.Set<String>> VALID_MODELS = new HashMap<>();

    static {
        // Gemini models - validated from Google API
        SUB_MODEL_PRESETS.put(LanguageModel.Gemini, new String[] {
                "gemini-2.5-flash",
                "gemini-2.5-pro",
                "gemini-2.5-flash-lite",
                "gemini-3-flash-preview",
                "gemini-3-pro-preview",
                "gemini-2.0-flash",
                "gemini-2.0-flash-lite"
        });
        VALID_MODELS.put(LanguageModel.Gemini, new java.util.HashSet<>(java.util.Arrays.asList(
                "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite",
                "gemini-3-flash-preview", "gemini-3-pro-preview", "gemini-3-pro-image-preview",
                "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-2.0-flash-001",
                "gemini-2.0-flash-exp", "gemini-2.0-flash-lite-001",
                "gemini-2.5-flash-preview-09-2025", "gemini-2.5-flash-lite-preview-09-2025",
                "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-pro-latest")));

        // ChatGPT models
        SUB_MODEL_PRESETS.put(LanguageModel.ChatGPT, new String[] {
                "gpt-5",
                "gpt-4o",
                "gpt-4.1",
                "o3-mini",
                "o4-mini"
        });
        VALID_MODELS.put(LanguageModel.ChatGPT, new java.util.HashSet<>(java.util.Arrays.asList(
                "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo",
                "gpt-4-turbo-preview", "gpt-4-0125-preview", "gpt-4-1106-preview")));

        // Groq models
        SUB_MODEL_PRESETS.put(LanguageModel.Groq, new String[] {
                "llama-3.3-70b-versatile",
                "meta-llama/llama-4-maverick-17b-128e-instruct",
                "groq/compound"
        });
        VALID_MODELS.put(LanguageModel.Groq, new java.util.HashSet<>(java.util.Arrays.asList(
                "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "llama3-70b-8192",
                "llama3-8b-8192", "mixtral-8x7b-32768", "gemma2-9b-it", "gemma-7b-it")));

        // OpenRouter models
        SUB_MODEL_PRESETS.put(LanguageModel.OpenRouter, new String[] {
                "google/gemini-2.0-flash-exp:free",
                "meta-llama/llama-3.2-3b-instruct:free",
                "mistralai/mistral-7b-instruct:free",
                "openai/gpt-4o-mini"
        });
        VALID_MODELS.put(LanguageModel.OpenRouter, null); // Allow any for OpenRouter

        // Claude models
        SUB_MODEL_PRESETS.put(LanguageModel.Claude, new String[] {
                "claude-opus-4-5-20250630",
                "claude-sonnet-4-5-20250630",
                "claude-haiku-4-5-20250630"
        });
        VALID_MODELS.put(LanguageModel.Claude, new java.util.HashSet<>(java.util.Arrays.asList(
                "claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022",
                "claude-3-opus-20240229", "claude-3-sonnet-20240229", "claude-3-haiku-20240307")));

        // Mistral models
        SUB_MODEL_PRESETS.put(LanguageModel.Mistral, new String[] {
                "magistral-medium-2507",
                "mistral-small-latest",
                "devstral-small-2505",
                "codestral-latest"
        });
        VALID_MODELS.put(LanguageModel.Mistral, new java.util.HashSet<>(java.util.Arrays.asList(
                "mistral-large-latest", "mistral-medium-latest", "mistral-small-latest",
                "open-mistral-7b", "open-mixtral-8x7b", "open-mixtral-8x22b")));


// Chutes models
SUB_MODEL_PRESETS.put(LanguageModel.Chutes, new String[] {
        "deepseek-ai/DeepSeek-R1",
        "deepseek-ai/DeepSeek-V3",
        "meta-llama/Llama-3.3-70B-Instruct",
        "Qwen/Qwen2.5-72B-Instruct",
        "nous-research/hermes-3-llama-3.1-405b",
        "gryphe/mythomax-l2-13b",
        "mistralai/Mistral-7B-Instruct-v0.3",
        "mistralai/Mistral-Small-24B-Instruct-2501",
        "deepseek-ai/DeepSeek-R1-Distill-Llama-70B"
});
VALID_MODELS.put(LanguageModel.Chutes, null); // Allow any model

// Perplexity models
SUB_MODEL_PRESETS.put(LanguageModel.Perplexity, new String[] {
        "sonar-pro",
        "sonar",
        "sonar-reasoning-pro",
        "sonar-reasoning",
        "r1-1776"
});
VALID_MODELS.put(LanguageModel.Perplexity, new java.util.HashSet<>(java.util.Arrays.asList(
        "sonar-pro", "sonar", "sonar-reasoning-pro", "sonar-reasoning", "r1-1776")));

// GLM (ZhipuAI) models
SUB_MODEL_PRESETS.put(LanguageModel.GLM, new String[] {
        "glm-4",
        "glm-4-plus",
        "glm-4-flash",
        "glm-4-air",
        "glm-3-turbo"
});
VALID_MODELS.put(LanguageModel.GLM, new java.util.HashSet<>(java.util.Arrays.asList(
        "glm-4", "glm-4-plus", "glm-4-air", "glm-4-airx", "glm-4-long",
        "glm-4-flashx", "glm-4-flash", "glm-4-9b",
        "glm-4-0520", "glm-3-turbo")));

    }

    /**
     * Check if a model name is valid for the given provider
     */
    private boolean isValidModelName(LanguageModel model, String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            return false;
        }

        java.util.Set<String> validSet = VALID_MODELS.get(model);
        if (validSet == null) {
            // Allow any model name for providers without validation (like OpenRouter)
            return true;
        }

        return validSet.contains(modelName.trim());
    }

    /**
     * Get suggested model name if the entered one is invalid
     */
    private String getSuggestedModel(LanguageModel model, String invalidName) {
        if (invalidName == null)
            return model.getDefault(LanguageModelField.SubModel);

        String[] presets = SUB_MODEL_PRESETS.get(model);
        if (presets == null || presets.length == 0) {
            return model.getDefault(LanguageModelField.SubModel);
        }

        // Try to find a similar model name
        String lowerInvalid = invalidName.toLowerCase();
        for (String preset : presets) {
            if (preset.toLowerCase().contains(lowerInvalid) ||
                    lowerInvalid.contains(preset.toLowerCase().replace("-preview", ""))) {
                return preset;
            }
        }

        // Return first preset as default
        return presets[0];
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_models, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rootView = view;
        initViews(view);
        setupKeyboardHandling(view);
        applyAmoledIfNeeded();
        setupRecyclerView();
        setupSaveButton();
        setupFetchModelsButton();
        loadCurrentSettings();

        // Apply candy colors when Material You is disabled
        // Candy colors removed
        // tn.eluea.kgpt.util.CandyColorHelper.applyToViewHierarchy(requireContext(),
        // rootView);
    }

    private void initViews(View view) {
        rvModels = view.findViewById(R.id.rv_models);
        chipGroupSubmodels = view.findViewById(R.id.chip_group_submodels);
        etSubModel = view.findViewById(R.id.et_sub_model);
        etBaseUrl = view.findViewById(R.id.et_base_url);
        btnSave = view.findViewById(R.id.btn_save);
        btnFetchModels = view.findViewById(R.id.btn_fetch_models);
}

    private void applyAmoledIfNeeded() {
        SharedPreferences prefs = requireContext().getSharedPreferences("keyboard_gpt_ui", Context.MODE_PRIVATE);
        boolean isAmoled = prefs.getBoolean(PREF_AMOLED, false);
        boolean isDarkMode = PrefsCompat.getThemeIsDark(requireContext(), prefs, PREF_THEME, false);

        if (isDarkMode && isAmoled) {
            if (rootView instanceof ViewGroup) {
                View scrollContent = ((ViewGroup) rootView).getChildAt(0);
                if (scrollContent != null) {
                    scrollContent
                            .setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.background_amoled));
                }
            }
            rootView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.background_amoled));
            applyAmoledToCards(rootView);
        }
    }

    private void applyAmoledToCards(View view) {
        if (view instanceof MaterialCardView) {
            ((MaterialCardView) view).setCardBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.surface_amoled));
            ((MaterialCardView) view).setStrokeColor(
                    ContextCompat.getColor(requireContext(), R.color.divider_dark));
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyAmoledToCards(group.getChildAt(i));
            }
        }
    }

    private void setupRecyclerView() {
        List<LanguageModel> models = Arrays.asList(LanguageModel.values());

        if (SPManager.isReady()) {
            selectedModel = SPManager.getInstance().getLanguageModel();
        } else {
            selectedModel = LanguageModel.Gemini;
        }

        adapter = new ModelsAdapter(models, selectedModel, this);
        rvModels.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        rvModels.setAdapter(adapter);
    }

    private void setupSubModelChips(LanguageModel model) {
        chipGroupSubmodels.removeAllViews();

        String[] presets = SUB_MODEL_PRESETS.get(model);
        if (presets == null)
            return;

        String currentSubModel = "";
        if (SPManager.isReady()) {
            currentSubModel = SPManager.getInstance().getSubModel(model);
        }
        if (currentSubModel == null || currentSubModel.isEmpty()) {
            currentSubModel = model.getDefault(LanguageModelField.SubModel);
        }

        int colorPrimary = com.google.android.material.color.MaterialColors.getColor(rootView,
                androidx.appcompat.R.attr.colorPrimary);
        int colorOnPrimary = com.google.android.material.color.MaterialColors.getColor(rootView,
                com.google.android.material.R.attr.colorOnPrimary);
        int colorSurfaceContainerHigh = com.google.android.material.color.MaterialColors.getColor(rootView,
                com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int colorOnSurface = com.google.android.material.color.MaterialColors.getColor(rootView,
                com.google.android.material.R.attr.colorOnSurface);
        int colorDivider = ContextCompat.getColor(requireContext(), R.color.divider_color);

        int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] {}
        };

        android.content.res.ColorStateList bgStateList = new android.content.res.ColorStateList(
                states,
                new int[] {
                        colorPrimary,
                        colorSurfaceContainerHigh
                });

        android.content.res.ColorStateList textStateList = new android.content.res.ColorStateList(
                states,
                new int[] {
                        colorOnPrimary,
                        colorOnSurface
                });

        for (String preset : presets) {
            Chip chip = new Chip(requireContext());
            chip.setText(preset);
            chip.setCheckable(true);

            // Dynamic Colors
            chip.setChipBackgroundColor(bgStateList);
            chip.setTextColor(textStateList);

            // Stroke
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(colorDivider));
            chip.setChipStrokeWidth(1f);

            if (preset.equals(currentSubModel)) {
                chip.setChecked(true);
            }

            chip.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    etSubModel.setText(preset);
                }
            });

            chipGroupSubmodels.addView(chip);
        }
    }

    private void setupSaveButton() {
        btnSave.setOnClickListener(v -> saveConfiguration());
    }

    private void loadCurrentSettings() {
        if (selectedModel != null) {
            loadModelSettings(selectedModel);
        }
    }

    private void loadModelSettings(LanguageModel model) {
        setupSubModelChips(model);

        String defaultSubModel = model.getDefault(LanguageModelField.SubModel);
        String defaultBaseUrl = model.getDefault(LanguageModelField.BaseUrl);

        if (!SPManager.isReady()) {
            etSubModel.setText(defaultSubModel);
            if (etBaseUrl != null) {
                etBaseUrl.setText(defaultBaseUrl);
            }
            return;
        }

        SPManager sp = SPManager.getInstance();

        String subModel = sp.getSubModel(model);
        if (subModel == null || subModel.isEmpty()) {
            subModel = defaultSubModel;
        }
        etSubModel.setText(subModel);

        if (etBaseUrl != null) {
            String baseUrl = sp.getBaseUrl(model);
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = defaultBaseUrl;
            }
            etBaseUrl.setText(baseUrl);
        }
    }

    @Override
    public void onModelSelected(LanguageModel model) {
        selectedModel = model;
        loadModelSettings(model);
    }

    private void saveConfiguration() {
        if (selectedModel == null) {
            Toast.makeText(requireContext(), "Please select a model", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!SPManager.isReady()) {
            Toast.makeText(requireContext(), "Settings not available", Toast.LENGTH_SHORT).show();
            return;
        }

        SPManager sp = SPManager.getInstance();

        // Save selected model
        sp.setLanguageModel(selectedModel);

        // Validate and save sub model
        String subModel = etSubModel.getText() != null ? etSubModel.getText().toString().trim() : "";

        if (subModel.isEmpty()) {
            subModel = selectedModel.getDefault(LanguageModelField.SubModel);
        }

        final String finalSubModel = subModel;


        // Validate and save Base URL (proxy/relay server address)
        String baseUrlInput = (etBaseUrl != null && etBaseUrl.getText() != null)
                ? etBaseUrl.getText().toString().trim()
                : "";

        if (baseUrlInput.isEmpty()) {
            baseUrlInput = selectedModel.getDefault(LanguageModelField.BaseUrl);
        }

        final String finalBaseUrl = normalizeBaseUrl(selectedModel, baseUrlInput);

        // Validate model name
        if (!isValidModelName(selectedModel, finalSubModel)) {
            String suggested = getSuggestedModel(selectedModel, finalSubModel);

            // Show warning dialog
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Invalid Model Name")
                    .setMessage("The model \"" + finalSubModel + "\" may not be valid.\n\nDid you mean: " + suggested
                            + "?\n\nUsing an invalid model name will cause API errors.")
                    .setPositiveButton("Use Suggested", (dialog, which) -> {
                        etSubModel.setText(suggested);
                        sp.setSubModel(selectedModel, suggested);
                        sp.setBaseUrl(selectedModel, finalBaseUrl);
                        sendConfigBroadcast();
                        Toast.makeText(requireContext(), "Configuration saved with " + suggested, Toast.LENGTH_SHORT)
                                .show();
                    })
                    .setNegativeButton("Use Anyway", (dialog, which) -> {
                        sp.setSubModel(selectedModel, finalSubModel);
                        sp.setBaseUrl(selectedModel, finalBaseUrl);
                        sendConfigBroadcast();
                        Toast.makeText(requireContext(), "Configuration saved (model may not work)", Toast.LENGTH_SHORT)
                                .show();
                    })
                    .setNeutralButton("Cancel", null)
                    .show();
            return;
        }

        sp.setSubModel(selectedModel, finalSubModel);
        sp.setBaseUrl(selectedModel, finalBaseUrl);

        // Send broadcast to Xposed module to sync settings
        sendConfigBroadcast();

        Toast.makeText(requireContext(), "Configuration saved", Toast.LENGTH_SHORT).show();
    }

    
    /**
     * Normalize the base URL (proxy/relay server address).
     * If the user enters only a host (no path), we append the default path for the selected provider
     * (e.g. /v1, /api/v1, /v1beta).
     */
    private String normalizeBaseUrl(LanguageModel model, String input) {
        String defaultUrl = model.getDefault(LanguageModelField.BaseUrl);
        if (input == null) return defaultUrl;

        String s = input.trim();
        if (s.isEmpty()) return defaultUrl;

        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }

        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }

        try {
            Uri uri = Uri.parse(s);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                String host = uri.getHost();
                String appendPath;

                if (model == LanguageModel.OpenRouter) {
                    // OpenRouter official endpoint uses /api/v1,
                    // but most OpenAI-compatible relay servers use /v1.
                    if (host != null && (host.equalsIgnoreCase("openrouter.ai") || host.endsWith(".openrouter.ai"))) {
                        appendPath = "/api/v1";
                    } else {
                        appendPath = "/v1";
                    }
                } else {
                    Uri defUri = Uri.parse(defaultUrl);
                    appendPath = defUri.getPath();
                }

                if (appendPath != null && !appendPath.isEmpty() && !"/".equals(appendPath)) {
                    if (!appendPath.startsWith("/")) appendPath = "/" + appendPath;
                    s = s + appendPath;
                }
            }
        } catch (Exception ignored) {
        }

        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }


    private void setupFetchModelsButton() {
        if (btnFetchModels == null) return;
        btnFetchModels.setOnClickListener(v -> {
            if (!SPManager.isReady()) {
                Toast.makeText(requireContext(), "SPManager not ready", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selectedModel == null) {
                Toast.makeText(requireContext(), "Select a provider first", Toast.LENGTH_SHORT).show();
                return;
            }

            // Claude doesn't provide a public models-list endpoint
            if (selectedModel == LanguageModel.Claude) {
                Toast.makeText(requireContext(), "Claude 不支持自动获取模型列表", Toast.LENGTH_SHORT).show();
                return;
            }

            SPManager sp = SPManager.getInstance();
            String baseUrlInput = (etBaseUrl != null && etBaseUrl.getText() != null)
                    ? etBaseUrl.getText().toString().trim()
                    : "";
            String baseUrl = normalizeBaseUrl(selectedModel, baseUrlInput);

            String apiKey = sp.getApiKey(selectedModel);
            if (apiKey == null || apiKey.trim().isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.fetch_models_no_key), Toast.LENGTH_SHORT).show();
            }

            btnFetchModels.setEnabled(false);
            Toast.makeText(requireContext(), "正在获取模型列表…", Toast.LENGTH_SHORT).show();

            new Thread(() -> {
                try {
                    List<String> models = fetchModelsFromEndpoint(selectedModel, baseUrl, apiKey);
                    requireActivity().runOnUiThread(() -> {
                        btnFetchModels.setEnabled(true);
                        if (models == null || models.isEmpty()) {
                            Toast.makeText(requireContext(), getString(R.string.fetch_models_empty), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // Reuse the SPManager instance created in the click handler (must remain effectively-final)
                        sp.setCachedModels(selectedModel, baseUrl, models);
                        Toast.makeText(requireContext(), "已缓存 " + models.size() + " 个模型", Toast.LENGTH_SHORT).show();
                        showModelPicker(models);
                    });
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() -> {
                        btnFetchModels.setEnabled(true);
                        Toast.makeText(requireContext(), getString(R.string.fetch_models_failed) + ": " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });
    }

    private List<String> fetchModelsFromEndpoint(LanguageModel model, String baseUrl, String apiKey) throws Exception {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return new ArrayList<>();

        String url = baseUrl;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        url = url + "/models";

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestProperty("Accept", "application/json");

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            if (model == LanguageModel.Gemini) {
                con.setRequestProperty("x-goog-api-key", apiKey.trim());
            } else {
                con.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
        }

        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String body = readAll(is);

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + body);
        }

        return parseModelsFromResponse(model, body);
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }

    private List<String> parseModelsFromResponse(LanguageModel model, String body) {
        List<String> list = new ArrayList<>();
        if (body == null) return list;

        try {
            JSONObject root = new JSONObject(body);

            // OpenAI / OpenRouter style: { data: [ { id: ... }, ... ] }
            if (root.has("data") && root.optJSONArray("data") != null) {
                JSONArray arr = root.optJSONArray("data");
                for (int i = 0; i < arr.length(); i++) {
                    Object it = arr.opt(i);
                    if (it instanceof JSONObject) {
                        String id = ((JSONObject) it).optString("id", "");
                        if (id.isEmpty()) id = ((JSONObject) it).optString("name", "");
                        if (!id.isEmpty()) list.add(id);
                    } else if (it instanceof String) {
                        String name = (String) it;
                    if (model == LanguageModel.Gemini && name.startsWith("models/")) {
                        name = name.substring("models/".length());
                    }
                    list.add(name);
                    }
                }
            }

            // Gemini style: { models: [ { name: "models/xxx" }, ... ] }
            if (list.isEmpty() && root.has("models") && root.optJSONArray("models") != null) {
                JSONArray arr = root.optJSONArray("models");
                for (int i = 0; i < arr.length(); i++) {
                    Object it = arr.opt(i);
                    if (it instanceof JSONObject) {
                        String name = ((JSONObject) it).optString("name", "");
                        if (!name.isEmpty()) {
                            if (model == LanguageModel.Gemini && name.startsWith("models/")) {
                                name = name.substring("models/".length());
                            }
                            list.add(name);
                        }
                    } else if (it instanceof String) {
                        list.add((String) it);
                    }
                }
            }

            // Some relays: { result: [ ... ] }
            if (list.isEmpty() && root.has("result") && root.optJSONArray("result") != null) {
                JSONArray arr = root.optJSONArray("result");
                for (int i = 0; i < arr.length(); i++) {
                    Object it = arr.opt(i);
                    if (it instanceof JSONObject) {
                        String id = ((JSONObject) it).optString("id", "");
                        if (id.isEmpty()) id = ((JSONObject) it).optString("name", "");
                        if (!id.isEmpty()) list.add(id);
                    } else if (it instanceof String) {
                        list.add((String) it);
                    }
                }
            }
        } catch (Exception ignored) {}

        // Normalize Gemini names
        if (model == LanguageModel.Gemini) {
            List<String> normalized = new ArrayList<>();
            for (String s : list) {
                if (s == null) continue;
                String x = s.trim();
                if (x.startsWith("models/")) x = x.substring("models/".length());
                normalized.add(x);
            }
            list = normalized;
        }

        // Sort and remove duplicates
        Collections.sort(list);
        List<String> unique = new ArrayList<>();
        String last = null;
        for (String s : list) {
            if (s == null) continue;
            if (last == null || !last.equals(s)) {
                unique.add(s);
                last = s;
            }
        }
        return unique;
    }

    private void showModelPicker(List<String> models) {
        FloatingBottomSheet sheet = BottomSheetHelper.showFloating(requireContext(), R.layout.bottom_sheet_model_picker);
        View view = sheet.findViewById(android.R.id.content);
        // In FloatingBottomSheet, content view is root; so find directly from sheet.getContentView()
        View content = sheet.getContentView();
        if (content == null) content = view;

        TextInputEditText etSearch = content.findViewById(R.id.et_search);
        RecyclerView rv = content.findViewById(R.id.rv_list);
        MaterialButton btnClose = content.findViewById(R.id.btn_close);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        ModelListAdapter adapter = new ModelListAdapter(models, selected -> {
            // Update UI
            etSubModel.setText(selected);

            // Persist immediately so the next request uses the selected model even if user doesn't press "Save Configuration"
            SPManager sp = SPManager.getInstance();
            sp.setSubModel(selectedModel, selected);

            // Also persist baseUrl if user typed it
            String baseUrlInput = (etBaseUrl != null && etBaseUrl.getText() != null)
                    ? etBaseUrl.getText().toString().trim()
                    : "";
            String baseUrlFinal = normalizeBaseUrl(selectedModel, baseUrlInput);
            sp.setBaseUrl(selectedModel, baseUrlFinal);

            // Notify services to reload config
            sendConfigBroadcast();

            Toast.makeText(requireContext(), getString(R.string.selected) + ": " + selected, Toast.LENGTH_SHORT).show();
            sheet.dismiss();
        });
        rv.setAdapter(adapter);

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    adapter.filter(s != null ? s.toString() : "");
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        if (btnClose != null) btnClose.setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }

    private void showRolesManager() {
        SPManager sp = SPManager.getInstance();
        String rolesJson = sp.getRolesJson();
        String activeRoleId = sp.getActiveRoleId();

        List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);

        FloatingBottomSheet sheet = BottomSheetHelper.showFloating(requireContext(), R.layout.bottom_sheet_roles);
        View content = sheet.getContentView();

        RecyclerView rv = content.findViewById(R.id.rv_roles);
        MaterialButton btnAdd = content.findViewById(R.id.btn_add_role);
        MaterialButton btnClose = content.findViewById(R.id.btn_close_roles);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        final RolesAdapter[] adapterRef = new RolesAdapter[1];
        RolesAdapter.OnRoleActionListener listener = new RolesAdapter.OnRoleActionListener() {
            @Override
            public void onSelect(RoleManager.Role role) {
                sp.setActiveRoleId(role.id);
                if (adapterRef[0] != null) {
                    adapterRef[0].setActiveRoleId(role.id);
                    adapterRef[0].notifyDataSetChanged();
                }
                Toast.makeText(requireContext(), getString(R.string.role_selected), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onEdit(RoleManager.Role role) {
                if (adapterRef[0] == null) return;
                showEditRoleDialog(role, roles, adapterRef[0], sp);
            }

            @Override
            public void onDelete(RoleManager.Role role) {
                if (adapterRef[0] == null) return;
                confirmDeleteRole(role, roles, adapterRef[0], sp);
            }
        };

        RolesAdapter adapter = new RolesAdapter(roles, activeRoleId, listener);
        adapterRef[0] = adapter;
        rv.setAdapter(adapter);

        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> showAddRoleDialog(roles, adapter, sp));
        }

        if (btnClose != null) btnClose.setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }

    private void showAddRoleDialog(List<RoleManager.Role> roles, RolesAdapter adapter, SPManager sp) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_role, null);

        TextView tvEmoji = dialogView.findViewById(R.id.tv_role_emoji);
        TextInputEditText etName = dialogView.findViewById(R.id.et_role_name);
        TextInputEditText etTrigger = dialogView.findViewById(R.id.et_role_trigger);
        TextInputEditText etPrompt = dialogView.findViewById(R.id.et_role_prompt);
        TextView tvToken = dialogView.findViewById(R.id.tv_token_count);
        LinearLayout llChips = dialogView.findViewById(R.id.ll_role_var_chips);

        androidx.appcompat.widget.SwitchCompat swOverride = dialogView.findViewById(R.id.sw_role_override);
        View panel = dialogView.findViewById(R.id.ll_role_override_panel);
        View rowOverrideModel = dialogView.findViewById(R.id.row_role_override_model);
        TextView tvOverrideModelValue = dialogView.findViewById(R.id.tv_role_override_model_value);
        com.google.android.material.slider.Slider sliderOverrideTemp = dialogView.findViewById(R.id.slider_role_override_temp);
        TextView tvOverrideTempValue = dialogView.findViewById(R.id.tv_role_override_temp_value);

        if (tvEmoji != null) tvEmoji.setText("🤖");
        if (tvOverrideModelValue != null) tvOverrideModelValue.setText("跟随全局");
        if (panel != null) panel.setVisibility(View.GONE);
        if (swOverride != null) swOverride.setChecked(false);

        // Emoji picker
        if (tvEmoji != null) {
            tvEmoji.setOnClickListener(v -> {
                final String[] emojis = new String[]{"🤖", "🧠", "💻", "📝", "🎨", "📊", "🧪", "🎯", "⚡", "🌌", "😎", "🕵️", "🧙"};
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("选择头像")
                        .setItems(emojis, (d, which) -> {
                            try { tvEmoji.setText(emojis[which]); } catch (Throwable ignored) {}
                        })
                        .show();
            });
        }

        // Variable chips (insert at cursor)
        if (llChips != null && etPrompt != null) {
            llChips.removeAllViews();
            final float density = getResources().getDisplayMetrics().density;

            java.util.function.BiConsumer<String, String> addChip = (label, token) -> {
                TextView tv = new TextView(requireContext());
                tv.setText(label);
                tv.setTextSize(12);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);

                int fg = 0xFF607D8B;
                int bg = 0x1A607D8B;
                tv.setTextColor(fg);

                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(bg);
                gd.setCornerRadius((int) (999 * density));
                tv.setBackground(gd);
                int ph = (int) (10 * density + 0.5f);
                int pv = (int) (6 * density + 0.5f);
                tv.setPadding(ph, pv, ph, pv);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                lp.setMarginEnd((int) (8 * density + 0.5f));
                tv.setLayoutParams(lp);

                tv.setOnClickListener(v -> {
                    try {
                        Editable e = etPrompt.getText();
                        if (e == null) return;
                        int startPos = etPrompt.getSelectionStart();
                        if (startPos < 0) startPos = e.length();
                        e.insert(startPos, token);
                        etPrompt.setSelection(Math.min(startPos + token.length(), e.length()));
                    } catch (Throwable ignored) {}
                });

                llChips.addView(tv);
            };

            addChip.accept("✂️ 剪贴板", "%clipboard%");
            addChip.accept("📝 选中文本", "%selected%");
            addChip.accept("⏱️ 时间", "[time]");
            addChip.accept("📅 日期", "[date]");
        }

        // Token probe
        if (etPrompt != null && tvToken != null) {
            final java.util.regex.Pattern EN_WORD = java.util.regex.Pattern.compile("[A-Za-z0-9]+");
            etPrompt.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update(s); }
                @Override public void afterTextChanged(Editable s) { update(s); }

                private void update(CharSequence s) {
                    String text = s == null ? "" : s.toString();
                    int chars = text.length();
                    int cjk = 0;
                    try {
                        for (int i = 0; i < text.length(); i++) {
                            char ch = text.charAt(i);
                            if (ch > 0xFF) cjk++;
                        }
                    } catch (Throwable ignored) {}

                    int words = 0;
                    try {
                        java.util.regex.Matcher m = EN_WORD.matcher(text);
                        while (m.find()) words++;
                    } catch (Throwable ignored) {}

                    int est = 0;
                    try { est = Math.round((cjk * 1.5f) + words); } catch (Throwable ignored) {}
                    try { tvToken.setText(chars + " 字 / 约 " + est + " Tokens"); } catch (Throwable ignored) {}
                }
            });
            try {
                CharSequence cur = etPrompt.getText();
                tvToken.setText((cur == null ? 0 : cur.length()) + " 字 / 约 0 Tokens");
            } catch (Throwable ignored) {}
        }

        // Override panel
        if (swOverride != null && panel != null) {
            swOverride.setOnCheckedChangeListener((btn, checked) -> panel.setVisibility(checked ? View.VISIBLE : View.GONE));
        }

        if (sliderOverrideTemp != null && tvOverrideTempValue != null) {
            sliderOverrideTemp.setValue(0.7f);
            tvOverrideTempValue.setText("0.7");
            sliderOverrideTemp.addOnChangeListener((s, value, fromUser) -> {
                try { tvOverrideTempValue.setText(String.format(java.util.Locale.US, "%.1f", value)); } catch (Throwable ignored) {}
            });
        }

        if (rowOverrideModel != null && tvOverrideModelValue != null) {
            rowOverrideModel.setOnClickListener(v -> {
                final TextInputEditText input = new TextInputEditText(requireContext());
                input.setBackgroundResource(R.drawable.bg_input_field);
                input.setHint("留空表示跟随全局");
                int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
                int pv = (int) (12 * getResources().getDisplayMetrics().density + 0.5f);
                input.setPadding(pad, pv, pad, pv);

                String cur = String.valueOf(tvOverrideModelValue.getText()).trim();
                if (!android.text.TextUtils.isEmpty(cur) && !"跟随全局".equals(cur)) input.setText(cur);

                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("专属大模型")
                        .setView(input)
                        .setNeutralButton("跟随全局", (d, w) -> {
                            try { tvOverrideModelValue.setText("跟随全局"); } catch (Throwable ignored) {}
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            String v2 = input.getText() != null ? input.getText().toString().trim() : "";
                            if (android.text.TextUtils.isEmpty(v2)) {
                                tvOverrideModelValue.setText("跟随全局");
                            } else {
                                tvOverrideModelValue.setText(v2);
                            }
                        })
                        .show();
            });
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.add_role))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save_config), (d, w) -> {
                    String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
                    String trigger = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "";
                    String prompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";

                    if (name.isEmpty() || prompt.isEmpty()) {
                        Toast.makeText(requireContext(), "名称和提示词不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (trigger.length() > 10) trigger = trigger.substring(0, 10);

                    // Ensure unique name
                    String base = name;
                    java.util.HashSet<String> existing = new java.util.HashSet<>();
                    for (RoleManager.Role r : roles) {
                        if (r == null || r.name == null) continue;
                        existing.add(r.name.trim());
                    }
                    if (existing.contains(base)) {
                        int idx = 1;
                        while (idx < 1000) {
                            String candidate = base + String.format(java.util.Locale.getDefault(), "%03d", idx);
                            if (!existing.contains(candidate)) { name = candidate; break; }
                            idx++;
                        }
                    }

                    String emoji = tvEmoji != null ? String.valueOf(tvEmoji.getText()).trim() : "🤖";
                    if (android.text.TextUtils.isEmpty(emoji)) emoji = "🤖";

                    boolean overrideEnabled = swOverride != null && swOverride.isChecked();
                    String overrideSubModel = "";
                    if (overrideEnabled && tvOverrideModelValue != null) {
                        String v = String.valueOf(tvOverrideModelValue.getText()).trim();
                        if (!v.isEmpty() && !"跟随全局".equals(v)) overrideSubModel = v;
                    }
                    float overrideTemp = -1f;
                    if (overrideEnabled && sliderOverrideTemp != null) {
                        try { overrideTemp = sliderOverrideTemp.getValue(); } catch (Throwable ignored) {}
                    }

                    String id = "r_" + System.currentTimeMillis();
                    roles.add(new RoleManager.Role(id, name, prompt, trigger, emoji, overrideEnabled, overrideSubModel, overrideTemp));
                    sp.setRolesJson(RoleManager.serializeCustomRoles(roles));
                    sp.setActiveRoleId(id);

                    adapter.setActiveRoleId(id);
                    adapter.notifyDataSetChanged();
                    Toast.makeText(requireContext(), getString(R.string.role_saved), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


private void showEditRoleDialog(RoleManager.Role role, List<RoleManager.Role> roles, RolesAdapter adapter, SPManager sp) {
        if (role == null || RoleManager.DEFAULT_ROLE_ID.equals(role.id)) {
            Toast.makeText(requireContext(), "默认角色不可编辑，请新建一个自定义角色", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_role, null);

        TextView tvEmoji = dialogView.findViewById(R.id.tv_role_emoji);
        TextInputEditText etName = dialogView.findViewById(R.id.et_role_name);
        TextInputEditText etTrigger = dialogView.findViewById(R.id.et_role_trigger);
        TextInputEditText etPrompt = dialogView.findViewById(R.id.et_role_prompt);
        TextView tvToken = dialogView.findViewById(R.id.tv_token_count);
        LinearLayout llChips = dialogView.findViewById(R.id.ll_role_var_chips);

        androidx.appcompat.widget.SwitchCompat swOverride = dialogView.findViewById(R.id.sw_role_override);
        View panel = dialogView.findViewById(R.id.ll_role_override_panel);
        View rowOverrideModel = dialogView.findViewById(R.id.row_role_override_model);
        TextView tvOverrideModelValue = dialogView.findViewById(R.id.tv_role_override_model_value);
        com.google.android.material.slider.Slider sliderOverrideTemp = dialogView.findViewById(R.id.slider_role_override_temp);
        TextView tvOverrideTempValue = dialogView.findViewById(R.id.tv_role_override_temp_value);

        if (tvEmoji != null) tvEmoji.setText(role.emoji == null || role.emoji.trim().isEmpty() ? "🤖" : role.emoji);
        if (etName != null) etName.setText(role.name != null ? role.name : "");
        if (etTrigger != null) etTrigger.setText(role.trigger != null ? role.trigger : "");
        if (etPrompt != null) etPrompt.setText(role.prompt != null ? role.prompt : "");

        // Emoji picker
        if (tvEmoji != null) {
            tvEmoji.setOnClickListener(v -> {
                final String[] emojis = new String[]{"🤖", "🧠", "💻", "📝", "🎨", "📊", "🧪", "🎯", "⚡", "🌌", "😎", "🕵️", "🧙"};
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("选择头像")
                        .setItems(emojis, (d, which) -> {
                            try { tvEmoji.setText(emojis[which]); } catch (Throwable ignored) {}
                        })
                        .show();
            });
        }

        // Variable chips (insert at cursor)
        if (llChips != null && etPrompt != null) {
            llChips.removeAllViews();
            final float density = getResources().getDisplayMetrics().density;

            java.util.function.BiConsumer<String, String> addChip = (label, token) -> {
                TextView tv = new TextView(requireContext());
                tv.setText(label);
                tv.setTextSize(12);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);

                int fg = 0xFF607D8B;
                int bg = 0x1A607D8B;
                tv.setTextColor(fg);

                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(bg);
                gd.setCornerRadius((int) (999 * density));
                tv.setBackground(gd);
                int ph = (int) (10 * density + 0.5f);
                int pv = (int) (6 * density + 0.5f);
                tv.setPadding(ph, pv, ph, pv);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                lp.setMarginEnd((int) (8 * density + 0.5f));
                tv.setLayoutParams(lp);

                tv.setOnClickListener(v -> {
                    try {
                        Editable e = etPrompt.getText();
                        if (e == null) return;
                        int startPos = etPrompt.getSelectionStart();
                        if (startPos < 0) startPos = e.length();
                        e.insert(startPos, token);
                        etPrompt.setSelection(Math.min(startPos + token.length(), e.length()));
                    } catch (Throwable ignored) {}
                });

                llChips.addView(tv);
            };

            addChip.accept("✂️ 剪贴板", "%clipboard%");
            addChip.accept("📝 选中文本", "%selected%");
            addChip.accept("⏱️ 时间", "[time]");
            addChip.accept("📅 日期", "[date]");
        }

        // Token probe
        if (etPrompt != null && tvToken != null) {
            final java.util.regex.Pattern EN_WORD = java.util.regex.Pattern.compile("[A-Za-z0-9]+");
            etPrompt.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update(s); }
                @Override public void afterTextChanged(Editable s) { update(s); }

                private void update(CharSequence s) {
                    String text = s == null ? "" : s.toString();
                    int chars = text.length();
                    int cjk = 0;
                    try {
                        for (int i = 0; i < text.length(); i++) {
                            char ch = text.charAt(i);
                            if (ch > 0xFF) cjk++;
                        }
                    } catch (Throwable ignored) {}

                    int words = 0;
                    try {
                        java.util.regex.Matcher m = EN_WORD.matcher(text);
                        while (m.find()) words++;
                    } catch (Throwable ignored) {}

                    int est = 0;
                    try { est = Math.round((cjk * 1.5f) + words); } catch (Throwable ignored) {}
                    try { tvToken.setText(chars + " 字 / 约 " + est + " Tokens"); } catch (Throwable ignored) {}
                }
            });
            try {
                CharSequence cur = etPrompt.getText();
                tvToken.setText((cur == null ? 0 : cur.length()) + " 字 / 约 0 Tokens");
            } catch (Throwable ignored) {}
        }

        // Override panel
        if (swOverride != null && panel != null) {
            swOverride.setChecked(role.overrideEnabled);
            panel.setVisibility(role.overrideEnabled ? View.VISIBLE : View.GONE);
            swOverride.setOnCheckedChangeListener((btn, checked) -> panel.setVisibility(checked ? View.VISIBLE : View.GONE));
        }
        if (tvOverrideModelValue != null) {
            String v = role.overrideSubModel != null ? role.overrideSubModel.trim() : "";
            tvOverrideModelValue.setText(android.text.TextUtils.isEmpty(v) ? "跟随全局" : v);
        }
        if (sliderOverrideTemp != null && tvOverrideTempValue != null) {
            float t = role.overrideTemperature;
            if (t < 0f || t > 2.0f) t = 0.7f;
            sliderOverrideTemp.setValue(t);
            tvOverrideTempValue.setText(String.format(java.util.Locale.US, "%.1f", t));
            sliderOverrideTemp.addOnChangeListener((s, value, fromUser) -> {
                try { tvOverrideTempValue.setText(String.format(java.util.Locale.US, "%.1f", value)); } catch (Throwable ignored) {}
            });
        }

        if (rowOverrideModel != null && tvOverrideModelValue != null) {
            rowOverrideModel.setOnClickListener(v -> {
                final TextInputEditText input = new TextInputEditText(requireContext());
                input.setBackgroundResource(R.drawable.bg_input_field);
                input.setHint("留空表示跟随全局");
                int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
                int pv = (int) (12 * getResources().getDisplayMetrics().density + 0.5f);
                input.setPadding(pad, pv, pad, pv);

                String cur = String.valueOf(tvOverrideModelValue.getText()).trim();
                if (!android.text.TextUtils.isEmpty(cur) && !"跟随全局".equals(cur)) input.setText(cur);

                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("专属大模型")
                        .setView(input)
                        .setNeutralButton("跟随全局", (d, w) -> {
                            try { tvOverrideModelValue.setText("跟随全局"); } catch (Throwable ignored) {}
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            String v2 = input.getText() != null ? input.getText().toString().trim() : "";
                            if (android.text.TextUtils.isEmpty(v2)) {
                                tvOverrideModelValue.setText("跟随全局");
                            } else {
                                tvOverrideModelValue.setText(v2);
                            }
                        })
                        .show();
            });
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("编辑角色")
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save_config), (d, w) -> {
                    String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
                    String trigger = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "";
                    String prompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";
                    if (name.isEmpty() || prompt.isEmpty()) {
                        Toast.makeText(requireContext(), "名称和提示词不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (trigger.length() > 10) trigger = trigger.substring(0, 10);

                    // Ensure unique name (exclude current role)
                    String base = name;
                    java.util.HashSet<String> existing = new java.util.HashSet<>();
                    for (RoleManager.Role r : roles) {
                        if (r == null || r.name == null) continue;
                        if (r.id != null && r.id.equals(role.id)) continue;
                        existing.add(r.name.trim());
                    }
                    if (existing.contains(base)) {
                        int idx = 1;
                        while (idx < 1000) {
                            String candidate = base + String.format(java.util.Locale.getDefault(), "%03d", idx);
                            if (!existing.contains(candidate)) { name = candidate; break; }
                            idx++;
                        }
                    }

                    String emoji = tvEmoji != null ? String.valueOf(tvEmoji.getText()).trim() : "🤖";
                    if (android.text.TextUtils.isEmpty(emoji)) emoji = "🤖";

                    boolean overrideEnabled = swOverride != null && swOverride.isChecked();
                    String overrideSubModel = "";
                    if (overrideEnabled && tvOverrideModelValue != null) {
                        String v = String.valueOf(tvOverrideModelValue.getText()).trim();
                        if (!v.isEmpty() && !"跟随全局".equals(v)) overrideSubModel = v;
                    }
                    float overrideTemp = -1f;
                    if (overrideEnabled && sliderOverrideTemp != null) {
                        try { overrideTemp = sliderOverrideTemp.getValue(); } catch (Throwable ignored) {}
                    }

                    for (int i = 0; i < roles.size(); i++) {
                        RoleManager.Role r = roles.get(i);
                        if (r != null && r.id != null && r.id.equals(role.id)) {
                            roles.set(i, new RoleManager.Role(role.id, name, prompt, trigger, emoji, overrideEnabled, overrideSubModel, overrideTemp));
                            break;
                        }
                    }
                    sp.setRolesJson(RoleManager.serializeCustomRoles(roles));
                    adapter.notifyDataSetChanged();
                    Toast.makeText(requireContext(), "已保存修改", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


private void confirmDeleteRole(RoleManager.Role role, List<RoleManager.Role> roles, RolesAdapter adapter, SPManager sp) {
        if (role == null || RoleManager.DEFAULT_ROLE_ID.equals(role.id)) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除角色")
                .setMessage("确定要删除角色「" + (role.name != null ? role.name : "") + "」吗？")
                .setPositiveButton("删除", (d, w) -> {
                    String active = sp.getActiveRoleId();
                    for (int i = roles.size() - 1; i >= 0; i--) {
                        RoleManager.Role r = roles.get(i);
                        if (r != null && r.id != null && r.id.equals(role.id)) {
                            roles.remove(i);
                        }
                    }
                    sp.setRolesJson(RoleManager.serializeCustomRoles(roles));
                    if (active != null && active.equals(role.id)) {
                        sp.setActiveRoleId(RoleManager.DEFAULT_ROLE_ID);
                        adapter.setActiveRoleId(RoleManager.DEFAULT_ROLE_ID);
                    }
                    adapter.notifyDataSetChanged();
                    Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ===== Adapters =====

    private static class ModelListAdapter extends RecyclerView.Adapter<ModelListAdapter.VH> {
        interface OnPickListener { void onPick(String model); }

        private final List<String> all;
        private final List<String> filtered;
        private final OnPickListener listener;

        ModelListAdapter(List<String> models, OnPickListener l) {
            this.all = models != null ? models : new ArrayList<>();
            this.filtered = new ArrayList<>(this.all);
            this.listener = l;
        }

        void filter(String q) {
            String query = q != null ? q.trim().toLowerCase() : "";
            filtered.clear();
            if (query.isEmpty()) {
                filtered.addAll(all);
            } else {
                for (String s : all) {
                    if (s != null && s.toLowerCase().contains(query)) filtered.add(s);
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_text_option, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String item = filtered.get(position);
            holder.tv.setText(item);
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onPick(item);
            });
        }

        @Override
        public int getItemCount() {
            return filtered.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(@NonNull View itemView) {
                super(itemView);
                tv = itemView.findViewById(R.id.tv_text);
            }
        }
    }

    
    private static class RolesAdapter extends RecyclerView.Adapter<RolesAdapter.VH> {

        interface OnRoleActionListener {
            void onSelect(RoleManager.Role role);
            void onEdit(RoleManager.Role role);
            void onDelete(RoleManager.Role role);
        }

        private final List<RoleManager.Role> roles;
        private String activeRoleId;
        private final OnRoleActionListener listener;

        RolesAdapter(List<RoleManager.Role> roles, String activeRoleId, OnRoleActionListener l) {
            this.roles = roles != null ? roles : new ArrayList<>();
            this.activeRoleId = activeRoleId;
            this.listener = l;
        }

        void setActiveRoleId(String id) { this.activeRoleId = id; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_role, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            RoleManager.Role r = roles.get(position);
            if (r == null) return;

            holder.name.setText(r.name != null ? r.name : "");
            holder.prompt.setText(r.prompt != null ? r.prompt : "");

            // Show trigger keyword (inherits global AI trigger if blank)
            try {
                String global = SPManager.getInstance() != null ? SPManager.getInstance().getAiTriggerSymbol() : "$";
                if (global == null || global.trim().isEmpty()) global = "$";
                String trigRaw = (r.trigger != null) ? r.trigger.trim() : "";
                String trig = !trigRaw.isEmpty() ? trigRaw : global;
                boolean isDefaultTrig = trigRaw.isEmpty();
                if (holder.trigger != null) {
                    String fmt = holder.itemView.getContext().getString(
                            isDefaultTrig ? R.string.role_trigger_display_default : R.string.role_trigger_display,
                            trig
                    );
                    holder.trigger.setText(fmt);
                }
            } catch (Throwable ignored) {}

            boolean selected = r.id != null && r.id.equals(activeRoleId);
            holder.selected.setVisibility(selected ? View.VISIBLE : View.GONE);

            boolean isDefault = RoleManager.DEFAULT_ROLE_ID.equals(r.id);

            // Default role is built-in (not persisted), so we don't allow edit/delete for it.
            holder.btnEdit.setVisibility(isDefault ? View.GONE : View.VISIBLE);
            holder.btnDelete.setVisibility(isDefault ? View.GONE : View.VISIBLE);

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onSelect(r);
            });

            holder.btnEdit.setOnClickListener(v -> {
                if (listener != null) listener.onEdit(r);
            });

            holder.btnDelete.setOnClickListener(v -> {
                if (listener != null) listener.onDelete(r);
            });
        }

        @Override
        public int getItemCount() {
            return roles.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView name;
            TextView prompt;
            TextView trigger;
            View selected;
            android.widget.ImageButton btnEdit;
            android.widget.ImageButton btnDelete;

            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tv_role_name);
                prompt = itemView.findViewById(R.id.tv_role_prompt);
                trigger = itemView.findViewById(R.id.tv_role_trigger);
                selected = itemView.findViewById(R.id.iv_selected);
                btnEdit = itemView.findViewById(R.id.btn_edit);
                btnDelete = itemView.findViewById(R.id.btn_delete);
            }
        }
    }

/**
     * Send broadcast to Xposed module to sync configuration
     */
    private void sendConfigBroadcast() {
        if (!SPManager.isReady())
            return;

        SPManager sp = SPManager.getInstance();

        Intent broadcastIntent = new Intent("tn.eluea.kgpt.DIALOG_RESULT");

        // Add selected model
        broadcastIntent.putExtra("tn.eluea.kgpt.config.SELECTED_MODEL", selectedModel.name());

        // Add all model configurations
        broadcastIntent.putExtra("tn.eluea.kgpt.config.model", sp.getConfigBundle());

        tn.eluea.kgpt.provider.BridgeAuth.send(requireContext(), broadcastIntent);
    }


    private void setupKeyboardHandling(View view) {
        // Keep focused input visible above keyboard (IME insets + OEM fallback like vivo 安全键盘)
        if (!(view instanceof NestedScrollView)) return;

        NestedScrollView scrollView = (NestedScrollView) view;
        View content = scrollView.getChildAt(0);
        if (content == null) return;

        float density = view.getContext().getResources().getDisplayMetrics().density;
        final int basePaddingPx = (int) (110 * density); // bottom dock space
        final int extraLiftPx = (int) (96 * density);    // extra gap so you can see what you type
        final int focusMarginPx = (int) (32 * density);  // keep cursor above keyboard

        // 1) Standard IME insets
        ViewCompat.setOnApplyWindowInsetsListener(scrollView, (v, windowInsets) -> {
            Insets imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

            lastSystemBarsBottomPx = systemBars.bottom;

            int bottomInset = Math.max(Math.max(imeInsets.bottom, systemBars.bottom), lastKeyboardHeightFallbackPx);
            boolean keyboardVisible = imeInsets.bottom > 0 || lastKeyboardHeightFallbackPx > 0;

            int bottomPadding = basePaddingPx + bottomInset + (keyboardVisible ? extraLiftPx : 0);
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), bottomPadding);

            if (keyboardVisible) {
                scrollView.post(() -> ensureFocusVisible(scrollView, focusMarginPx));
            }
            return windowInsets;
        });

        // 2) OEM fallback (GlobalLayout)
        keyboardLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            private int lastAppliedPadding = -1;

            @Override
            public void onGlobalLayout() {
                if (rootView == null) return;

                Rect r = new Rect();
                scrollView.getWindowVisibleDisplayFrame(r);
                lastVisibleFrameBottomPx = r.bottom;

                int screenHeight = scrollView.getRootView().getHeight();
                int keyboardHeight = Math.max(0, screenHeight - r.bottom);

                boolean keyboardVisible = keyboardHeight > screenHeight * 0.15f;
                lastKeyboardHeightFallbackPx = keyboardVisible ? keyboardHeight : 0;

                int bottomInset = Math.max(lastSystemBarsBottomPx, lastKeyboardHeightFallbackPx);
                int bottomPadding = basePaddingPx + bottomInset + (keyboardVisible ? extraLiftPx : 0);

                if (bottomPadding != lastAppliedPadding) {
                    lastAppliedPadding = bottomPadding;
                    content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), bottomPadding);
                }

                if (keyboardVisible) {
                    scrollView.post(() -> ensureFocusVisible(scrollView, focusMarginPx, r.bottom));
                }
            }
        };
        scrollView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayoutListener);

        // Focus listeners for the model inputs
        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            if (hasFocus) scrollView.postDelayed(() -> ensureFocusVisible(scrollView, focusMarginPx), 150);
        };
        if (etSubModel != null) etSubModel.setOnFocusChangeListener(focusListener);
        if (etBaseUrl != null) etBaseUrl.setOnFocusChangeListener(focusListener);
    }

    private void ensureFocusVisible(@NonNull NestedScrollView scrollView, int marginPx) {
        ensureFocusVisible(scrollView, marginPx, lastVisibleFrameBottomPx);
    }

    private void ensureFocusVisible(@NonNull NestedScrollView scrollView, int marginPx, int visibleFrameBottomPx) {
        View focus = null;
        if (getActivity() != null) focus = getActivity().getCurrentFocus();
        if (focus == null) focus = scrollView.findFocus();
        if (focus == null) return;

        int[] loc = new int[2];
        focus.getLocationInWindow(loc);
        int focusBottom = loc[1] + focus.getHeight();

        int keyboardTop = visibleFrameBottomPx > 0 ? visibleFrameBottomPx : (loc[1] + scrollView.getHeight());
        int targetBottom = keyboardTop - marginPx;

        if (focusBottom > targetBottom) {
            int delta = focusBottom - targetBottom;
            if (delta > 0) scrollView.smoothScrollBy(0, delta);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (keyboardLayoutListener != null && rootView instanceof NestedScrollView) {
            ((NestedScrollView) rootView).getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayoutListener);
        }
        keyboardLayoutListener = null;
        rootView = null;
        rvModels = null;
        chipGroupSubmodels = null;
        etSubModel = null;
        etBaseUrl = null;
        btnSave = null;
        btnFetchModels = null;
}

}
