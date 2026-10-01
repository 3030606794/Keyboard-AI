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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;

import java.util.Collections;
import java.util.List;

import tn.eluea.kgpt.instruction.command.Commands;
import tn.eluea.kgpt.instruction.command.GenerativeAICommand;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.listener.ConfigInfoProvider;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.provider.ConfigClient;
import tn.eluea.kgpt.settings.OtherSettingsType;
import tn.eluea.kgpt.text.parse.ParsePattern;
import tn.eluea.kgpt.text.parse.PatternType;


import tn.eluea.kgpt.roles.RoleManager;
/**
 * Unified configuration manager that uses ContentProvider as single source of
 * truth.
 * Works in both KGPT app context and Xposed module (Gboard) context.
 */
public class SPManager implements ConfigInfoProvider {
    protected static final String PREF_MODULE_VERSION = "module_version";
    protected static final String PREF_LANGUAGE_MODEL = "language_model_v2";
    protected static final String PREF_GEN_AI_COMMANDS = "gen_ai_commands";
    protected static final String PREF_PARSE_PATTERNS = "parse_patterns";
    protected static final String PREF_OTHER_SETTING = "other_setting.%s";

    // User-defined quick jump templates (deep-link list)
    protected static final String PREF_QUICK_JUMP_CONFIG = "quick_jump_config";

    // Floating "Screenshot → Ask AI" overlay button
    protected static final String PREF_FLOAT_SS_ASK_ENABLED = "floating_screenshot_ask_enabled";
    protected static final String PREF_FLOAT_SS_ASK_X = "floating_screenshot_ask_x";
    protected static final String PREF_FLOAT_SS_ASK_Y = "floating_screenshot_ask_y";

    private final ConfigClient mClient;
    private List<GenerativeAICommand> generativeAICommands = List.of();
    private static SPManager instance = null;

    // Sticky cache for roles to avoid intermittent provider/Xposed read issues
    private volatile String mLastRolesJson = "";
    private volatile String mLastActiveRoleId = tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID;
    private volatile long mLastRoleSetAtMs = 0L;

    public static void init(Context context) {
        instance = new SPManager(context);
    }

    public static SPManager getInstance() {
        if (instance == null) {
            throw new RuntimeException("Missing call to SPManager.init(Context)");
        }
        return instance;
    }

    public static boolean isReady() {
        return instance != null;
    }

    private SPManager(Context context) {
        mClient = new ConfigClient(context);
        updateVersion();
        initializeDefaultCommands();
        initializeDefaultPatterns();
        updateGenerativeAICommands();
    }

    private void initializeDefaultCommands() {
        String existing = mClient.getString(PREF_GEN_AI_COMMANDS, null);
        if (existing == null || existing.equals("[]")) {
            setGenerativeAICommands(Commands.getDefaultCommands());
        }
    }

    private void initializeDefaultPatterns() {
        String existing = mClient.getString(PREF_PARSE_PATTERNS, null);
        if (existing == null) {
            // Initialize with default patterns
            setParsePatterns(ParsePattern.getDefaultPatterns());
            return;
        }

        // Migration: ensure any newly added PatternType entries exist in the saved list.
        // This keeps updates compatible without forcing users to reset their patterns.
        try {
            List<ParsePattern> patterns = ParsePattern.decode(existing);
            if (patterns == null) patterns = new ArrayList<>();
            boolean changed = false;

            for (PatternType type : PatternType.values()) {
                boolean found = false;
                for (ParsePattern p : patterns) {
                    if (p == null) continue;
                    if (p.getType() == type) { found = true; break; }
                }
                if (!found) {
                    ParsePattern p = new ParsePattern(type, type.defaultPattern);
                    p.setEnabled(true);
                    patterns.add(p);
                    changed = true;
                }
            }

            if (changed) {
                setParsePatterns(patterns);
            }
        } catch (Throwable t) {
            // If parsing fails for any reason, fall back to defaults (better than crashing).
            setParsePatterns(ParsePattern.getDefaultPatterns());
        }
    }

    private void updateVersion() {
        int version = getVersion();
        if (version != BuildConfig.VERSION_CODE) {
            mClient.putInt(PREF_MODULE_VERSION, BuildConfig.VERSION_CODE);
        }
    }

    public int getVersion() {
        return mClient.getInt(PREF_MODULE_VERSION, -1);
    }

    public boolean hasLanguageModel() {
        String value = mClient.getString(PREF_LANGUAGE_MODEL, null);
        return value != null && !value.trim().isEmpty();
    }

    @Override
    public LanguageModel getLanguageModel() {
        String languageModelName = mClient.getString(PREF_LANGUAGE_MODEL, null);
        if (languageModelName == null) {
            languageModelName = LanguageModel.Gemini.name();
        }
        return LanguageModel.valueOf(languageModelName);
    }

    public void setLanguageModel(LanguageModel model) {
        mClient.putString(PREF_LANGUAGE_MODEL, model.name());
    }

    public void clearLanguageModel() {
        mClient.remove(PREF_LANGUAGE_MODEL);
    }

    public void setLanguageModelField(LanguageModel model, LanguageModelField field, String value) {
        if (model == null || field == null) {
            tn.eluea.kgpt.util.Logger.log("setLanguageModelField: model or field is null");
            return;
        }
        String entryName = String.format("%s." + field, model.name());
        mClient.putString(entryName, value);
    }

    public String getLanguageModelField(LanguageModel model, LanguageModelField field) {
        String entryName = String.format("%s." + field, model.name());
        return mClient.getString(entryName, model.getDefault(field));
    }

    public void setApiKey(LanguageModel model, String apiKey) {
        setLanguageModelField(model, LanguageModelField.ApiKey, apiKey);
    }

    public String getApiKey(LanguageModel model) {
        return getLanguageModelField(model, LanguageModelField.ApiKey);
    }

    public void setSubModel(LanguageModel model, String subModel) {
        setLanguageModelField(model, LanguageModelField.SubModel, subModel);
    }

    public String getSubModel(LanguageModel model) {
        return getLanguageModelField(model, LanguageModelField.SubModel);
    }

    public void setBaseUrl(LanguageModel model, String baseUrl) {
        setLanguageModelField(model, LanguageModelField.BaseUrl, baseUrl);
    }

    public String getBaseUrl(LanguageModel model) {
        return getLanguageModelField(model, LanguageModelField.BaseUrl);
    }


// ===== Provider Health (Last Check / Latency / Reachability) =====
// Stored via ConfigProvider so it works both in app and module.
private static final String PREF_PROVIDER_HEALTH_LAST_CHECK_MS = "provider_health.%s.last_check_ms";
private static final String PREF_PROVIDER_HEALTH_LATENCY_MS = "provider_health.%s.latency_ms";
private static final String PREF_PROVIDER_HEALTH_OK = "provider_health.%s.ok";
private static final String PREF_PROVIDER_HEALTH_ERR = "provider_health.%s.err";

public void setProviderHealth(LanguageModel model, boolean ok, long latencyMs, @Nullable String errMsg) {
    if (model == null) return;
    String kCheck = String.format(PREF_PROVIDER_HEALTH_LAST_CHECK_MS, model.name());
    String kLat   = String.format(PREF_PROVIDER_HEALTH_LATENCY_MS, model.name());
    String kOk    = String.format(PREF_PROVIDER_HEALTH_OK, model.name());
    String kErr   = String.format(PREF_PROVIDER_HEALTH_ERR, model.name());
    try {
        mClient.putLong(kCheck, System.currentTimeMillis());
        mClient.putLong(kLat, Math.max(0, latencyMs));
        mClient.putBoolean(kOk, ok);
        mClient.putString(kErr, errMsg == null ? "" : errMsg);
    } catch (Throwable ignored) {
    }
}

public long getProviderHealthLastCheckMs(@Nullable LanguageModel model) {
    if (model == null) return 0L;
    String k = String.format(PREF_PROVIDER_HEALTH_LAST_CHECK_MS, model.name());
    try {
        return mClient.getLong(k, 0L);
    } catch (Throwable ignored) {
        return 0L;
    }
}

public long getProviderHealthLatencyMs(@Nullable LanguageModel model) {
    if (model == null) return 0L;
    String k = String.format(PREF_PROVIDER_HEALTH_LATENCY_MS, model.name());
    try {
        return mClient.getLong(k, 0L);
    } catch (Throwable ignored) {
        return 0L;
    }
}

public boolean getProviderHealthOk(@Nullable LanguageModel model) {
    if (model == null) return false;
    String k = String.format(PREF_PROVIDER_HEALTH_OK, model.name());
    try {
        return mClient.getBoolean(k, false);
    } catch (Throwable ignored) {
        return false;
    }
}

public String getProviderHealthLastError(@Nullable LanguageModel model) {
    if (model == null) return "";
    String k = String.format(PREF_PROVIDER_HEALTH_ERR, model.name());
    try {
        String v = mClient.getString(k, "");
        return v == null ? "" : v;
    } catch (Throwable ignored) {
        return "";
    }
}

public void clearProviderHealth(@Nullable LanguageModel model) {
    if (model == null) return;
    String kCheck = String.format(PREF_PROVIDER_HEALTH_LAST_CHECK_MS, model.name());
    String kLat   = String.format(PREF_PROVIDER_HEALTH_LATENCY_MS, model.name());
    String kOk    = String.format(PREF_PROVIDER_HEALTH_OK, model.name());
    String kErr   = String.format(PREF_PROVIDER_HEALTH_ERR, model.name());
    try {
        mClient.putLong(kCheck, 0L);
        mClient.putLong(kLat, 0L);
        mClient.putBoolean(kOk, false);
        mClient.putString(kErr, "");
    } catch (Throwable ignored) {
    }
}


// ===== Cached Models (for Model Switch) =====
private static final String PREF_CACHED_MODELS_JSON = "cached_models.%s.json";
private static final String PREF_CACHED_MODELS_BASEURL = "cached_models.%s.base_url";

public void setCachedModels(LanguageModel model, String baseUrl, List<String> models) {
    if (model == null) return;

    String keyJson = String.format(PREF_CACHED_MODELS_JSON, model.name());
    String keyUrl  = String.format(PREF_CACHED_MODELS_BASEURL, model.name());

    JSONArray arr = new JSONArray();
    HashSet<String> seen = new HashSet<>();

    if (models != null) {
        for (String s : models) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (seen.add(v)) arr.put(v);
        }
    }

    mClient.putString(keyJson, arr.toString());
    if (baseUrl != null) mClient.putString(keyUrl, baseUrl.trim());
}

public List<String> getCachedModels(LanguageModel model) {
    if (model == null) return Collections.emptyList();

    String keyJson = String.format(PREF_CACHED_MODELS_JSON, model.name());
    String raw = mClient.getString(keyJson, null);
    if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();

    ArrayList<String> out = new ArrayList<>();
    try {
        JSONArray arr = new JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
    } catch (JSONException ignored) {}

    return out;
}

public String getCachedModelsBaseUrl(LanguageModel model) {
    if (model == null) return "";
    String keyUrl = String.format(PREF_CACHED_MODELS_BASEURL, model.name());
    String v = mClient.getString(keyUrl, "");
    return v == null ? "" : v;
}



// ===== Custom Sub Models (User Added) =====
private static final String PREF_CUSTOM_SUB_MODELS_JSON = "custom_sub_models.%s.json";

public List<String> getCustomSubModels(LanguageModel model) {
    if (model == null) return java.util.Collections.emptyList();
    String keyJson = String.format(PREF_CUSTOM_SUB_MODELS_JSON, model.name());
    String raw = mClient.getString(keyJson, null);
    if (raw == null || raw.trim().isEmpty()) return java.util.Collections.emptyList();

    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    try {
        org.json.JSONArray arr = new org.json.JSONArray(raw);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (seen.add(v)) out.add(v);
        }
    } catch (org.json.JSONException ignored) {
    }

    return out;
}

public void setCustomSubModels(LanguageModel model, java.util.List<String> models) {
    if (model == null) return;
    String keyJson = String.format(PREF_CUSTOM_SUB_MODELS_JSON, model.name());

    org.json.JSONArray arr = new org.json.JSONArray();
    java.util.HashSet<String> seen = new java.util.HashSet<>();

    if (models != null) {
        for (String s : models) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (seen.add(v)) arr.put(v);
        }
    }

    mClient.putString(keyJson, arr.toString());
}

/**
 * Append a custom sub-model to the user's list (deduped, insertion order kept).
 */
public void addCustomSubModel(LanguageModel model, String subModel) {
    if (model == null) return;
    if (subModel == null) return;
    String v = subModel.trim();
    if (v.isEmpty()) return;

    java.util.ArrayList<String> cur = new java.util.ArrayList<>(getCustomSubModels(model));
    for (int i = cur.size() - 1; i >= 0; i--) {
        if (v.equals(cur.get(i))) cur.remove(i);
    }
    cur.add(v);
    setCustomSubModels(model, cur);
}


// ===== Starred / Favorite Sub Models =====
// Stored as a JSON array string (set semantics) via ConfigProvider.
private static final String PREF_STARRED_SUB_MODELS_JSON = "starred_sub_models.%s.json";

public java.util.Set<String> getStarredSubModels(LanguageModel model) {
    if (model == null) return java.util.Collections.emptySet();
    String keyJson = String.format(PREF_STARRED_SUB_MODELS_JSON, model.name());
    String raw = mClient.getString(keyJson, null);
    if (raw == null || raw.trim().isEmpty()) return java.util.Collections.emptySet();

    java.util.HashSet<String> out = new java.util.HashSet<>();
    try {
        org.json.JSONArray arr = new org.json.JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s == null) continue;
            String v = s.trim();
            if (!v.isEmpty()) out.add(v);
        }
    } catch (org.json.JSONException ignored) {
    }
    return out;
}

public void setStarredSubModels(LanguageModel model, java.util.Set<String> models) {
    if (model == null) return;
    String keyJson = String.format(PREF_STARRED_SUB_MODELS_JSON, model.name());
    org.json.JSONArray arr = new org.json.JSONArray();
    java.util.HashSet<String> seen = new java.util.HashSet<>();
    if (models != null) {
        for (String s : models) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (seen.add(v)) arr.put(v);
        }
    }
    mClient.putString(keyJson, arr.toString());
}
    // ===== Roles (Personas) =====
    private static final String PREF_ROLES_JSON = tn.eluea.kgpt.roles.RoleManager.PREF_ROLES_JSON;
    private static final String PREF_ACTIVE_ROLE_ID = tn.eluea.kgpt.roles.RoleManager.PREF_ACTIVE_ROLE_ID;
    private static final String PREF_DEFAULT_ROLE_CUSTOM_JSON = "default_role_custom_json_v1";
    private static final String PREF_ROLE_STACK_ENABLED = "role_stack_enabled_v1";
    private static final String PREF_ROLE_STACK_JSON = "role_stack_json_v1";
    private static final String PREF_LAST_CUSTOM_ROLE_ID = "last_custom_role_id_v1";
    private static final String PREF_FORCE_DEFAULT_ROLE = "force_default_role_v1";

    public void setRolesJson(String rolesJson) {
        String v = rolesJson != null ? rolesJson : "";
        mLastRolesJson = v;
        mClient.putString(PREF_ROLES_JSON, v);
    }

    public String getRolesJson() {
        String v = mClient.getString(PREF_ROLES_JSON, "");
        if (v == null) v = "";
        // If provider/Xposed returns empty unexpectedly, fall back to last known value
        if (v.isEmpty() && mLastRolesJson != null && !mLastRolesJson.isEmpty()) {
            return mLastRolesJson;
        }
        mLastRolesJson = v;
        return v;
    }

    /** Optional overrides for the built-in default role (stored as a single JSON object string). */
    public void setDefaultRoleCustomJson(String json) {
        String v = json != null ? json : "";
        mClient.putString(PREF_DEFAULT_ROLE_CUSTOM_JSON, v);
    }

    public String getDefaultRoleCustomJson() {
        String v = null;
        try { v = mClient.getString(PREF_DEFAULT_ROLE_CUSTOM_JSON, ""); } catch (Throwable ignored) {}
        if (v == null) v = "";
        return v;
    }

    /** Role stacking mode: enable / disable. */
    public void setRoleStackEnabled(boolean enabled) {
        try { mClient.putBoolean(PREF_ROLE_STACK_ENABLED, enabled); } catch (Throwable t) {
            try { mClient.putString(PREF_ROLE_STACK_ENABLED, String.valueOf(enabled)); } catch (Throwable ignored) {}
        }
    }

    public boolean getRoleStackEnabled() {
        try { return mClient.getBoolean(PREF_ROLE_STACK_ENABLED, false); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_ROLE_STACK_ENABLED, null);
                if (s != null) return Boolean.parseBoolean(s.trim());
            } catch (Throwable ignored2) {}
            return false;
        }
    }

    /** Role stacking list stored as JSON array string: ["roleId1", "roleId2", ...] */
    public void setRoleStackJson(String jsonArray) {
        String v = jsonArray != null ? jsonArray : "";
        mClient.putString(PREF_ROLE_STACK_JSON, v);
    }

    public String getRoleStackJson() {
        String v = null;
        try { v = mClient.getString(PREF_ROLE_STACK_JSON, ""); } catch (Throwable ignored) {}
        if (v == null) v = "";
        return v;
    }

    public void setActiveRoleId(String roleId) {
        String v = roleId != null ? roleId : tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID;
        v = v.trim().isEmpty() ? tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID : v.trim();

        mLastActiveRoleId = v;
        mLastRoleSetAtMs = System.currentTimeMillis();

        // Persist active role
        mClient.putString(PREF_ACTIVE_ROLE_ID, v);

        // Whether the user explicitly forced the DEFAULT role.
        // When true, we must NOT auto-fallback to last custom role.
        mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID.equals(v));

        // Permanent stickiness: remember last custom role until the user explicitly selects default.
        if (tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID.equals(v)) {
            mClient.putString(PREF_LAST_CUSTOM_ROLE_ID, "");
        } else {
            mClient.putString(PREF_LAST_CUSTOM_ROLE_ID, v);
        }
    }

    
    public String getActiveRoleId() {
        // Active role id may occasionally read as DEFAULT due to provider race or cache issues,
        // which makes the assistant "jump back" to the default role after a few messages.
        // To keep the selected role stable, we keep a robust fallback chain:
        // 1) value stored in PREF_ACTIVE_ROLE_ID
        // 2) last custom role id (PREF_LAST_CUSTOM_ROLE_ID)
        // 3) in-memory last known role id
        String v = null;
        boolean forceDefault = false;
        try {
            forceDefault = mClient.getBoolean(PREF_FORCE_DEFAULT_ROLE, false);
        } catch (Throwable ignored) {}
        try {
            v = mClient.getString(PREF_ACTIVE_ROLE_ID, RoleManager.DEFAULT_ROLE_ID);
        } catch (Throwable ignored) {
        }
        if (v != null) v = v.trim();
        if (v == null || v.isEmpty()) {
            // read failure or empty -> prefer last custom
            String lastCustom = null;
            try {
                lastCustom = mClient.getString(PREF_LAST_CUSTOM_ROLE_ID, "");
            } catch (Throwable ignored) {
            }
            if (lastCustom != null) lastCustom = lastCustom.trim();
            if (lastCustom != null && !lastCustom.isEmpty() && !RoleManager.DEFAULT_ROLE_ID.equals(lastCustom)) {
                mLastActiveRoleId = lastCustom;
                // self-heal the active role value so next reads are consistent
                try { mClient.putString(PREF_ACTIVE_ROLE_ID, lastCustom); mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, false); } catch (Throwable ignored) {}
                return lastCustom;
            }
            if (mLastActiveRoleId != null) {
                String mem = mLastActiveRoleId.trim();
                if (!mem.isEmpty() && !RoleManager.DEFAULT_ROLE_ID.equals(mem)) {
                    return mem;
                }
            }
            mLastActiveRoleId = RoleManager.DEFAULT_ROLE_ID;
            return RoleManager.DEFAULT_ROLE_ID;
        }

        // If provider returns default but user previously selected a custom role, keep the custom role
        // UNLESS the user explicitly forced the default role.
        if (RoleManager.DEFAULT_ROLE_ID.equals(v)) {
            if (forceDefault) {
                mLastActiveRoleId = v;
                return v;
            }

            String lastCustom = null;
            try {
                lastCustom = mClient.getString(PREF_LAST_CUSTOM_ROLE_ID, "");
            } catch (Throwable ignored) {
            }
            if (lastCustom != null) lastCustom = lastCustom.trim();
            if (lastCustom != null && !lastCustom.isEmpty() && !RoleManager.DEFAULT_ROLE_ID.equals(lastCustom)) {
                mLastActiveRoleId = lastCustom;
                try { 
                    mClient.putString(PREF_ACTIVE_ROLE_ID, lastCustom);
                    mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, false);
                } catch (Throwable ignored) {}
                return lastCustom;
            }

            // No last custom role -> trust DEFAULT
            mLastActiveRoleId = v;
            return v;
        }
        // Normal non-default role
        mLastActiveRoleId = v;
        // keep last custom role updated (best-effort)
        try { mClient.putString(PREF_LAST_CUSTOM_ROLE_ID, v); mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, false); } catch (Throwable ignored) {}
        return v;
    }




    public void setGenerativeAICommandsRaw(String commands) {
        mClient.putString(PREF_GEN_AI_COMMANDS, commands);
        updateGenerativeAICommands();
    }

    public String getGenerativeAICommandsRaw() {
        return mClient.getString(PREF_GEN_AI_COMMANDS, "[]");
    }

    public void setGenerativeAICommands(List<GenerativeAICommand> commands) {
        setGenerativeAICommandsRaw(Commands.encodeCommands(commands));
    }

    public List<GenerativeAICommand> getGenerativeAICommands() {
        // Always get fresh data
        updateGenerativeAICommands();
        return generativeAICommands;
    }

    public void setParsePatterns(List<ParsePattern> parsePatterns) {
        setParsePatternsRaw(ParsePattern.encode(parsePatterns));
    }

    public void setParsePatternsRaw(String patternsRaw) {
        mClient.putString(PREF_PARSE_PATTERNS, patternsRaw);
    }

    public List<ParsePattern> getParsePatterns() {
        return ParsePattern.decode(getParsePatternsRaw());
    }

    /**
     * Get the current AI trigger keyword/symbol (the one configured for PatternType.CommandAI).
     *
     * This is used as the default trigger for roles when a role-specific trigger is not set.
     */
    public String getAiTriggerSymbol() {
        try {
            List<ParsePattern> patterns = getParsePatterns();
            if (patterns != null) {
                for (ParsePattern p : patterns) {
                    if (p == null) continue;
                    if (p.getType() != PatternType.CommandAI) continue;
                    String sym = null;
                    try {
                        sym = PatternType.regexToSymbol(p.getPattern().pattern());
                    } catch (Throwable ignored) {
                    }
                    if (sym != null) sym = sym.trim();
                    if (sym != null && !sym.isEmpty()) return sym;

                    // Pattern exists but we couldn't extract the symbol for some reason.
                    // Fall back to the type default.
                    return PatternType.CommandAI.defaultSymbol;
                }
            }
        } catch (Throwable ignored) {}
        return PatternType.CommandAI.defaultSymbol;
    }

    public String getParsePatternsRaw() {
        return mClient.getString(PREF_PARSE_PATTERNS, null);
    }

    // ===== AI Trigger (multiline prompt sending) =====
    //
    // This governs how much text is sent when the AI trigger symbol is used (e.g. $ChatGPT).
    // Historically this was a simple boolean:
    //   - true  -> send all text (multiline)
    //   - false -> send only the last cursor line (single-line)
    //
    // v5 adds a *mode* with newline handling options, while keeping the trigger UI as a single switch.
    // The switch maps to: mode != OFF. Detailed tuning is available from 实验室 → 对话设置.

    // Legacy boolean (kept for backward compatibility + external clients).
    // True: send full text (including newlines) when AI trigger symbol is used.
    // False: send only the last line (approximation of "cursor line").
    private static final String PREF_AI_TRIGGER_MULTILINE = "ai_trigger_multiline_enabled_v1";

    // New detailed mode (v5+)
    private static final String PREF_AI_TRIGGER_MULTILINE_MODE = "ai_trigger_multiline_mode_v2";
    private static final String PREF_AI_TRIGGER_MULTILINE_LAST_ON_MODE = "ai_trigger_multiline_last_on_mode_v1";

    // Modes
    public static final int AI_TRIGGER_MULTILINE_OFF = 0;                 // send only cursor line
    public static final int AI_TRIGGER_MULTILINE_KEEP_NEWLINES = 1;       // send all text, keep newlines
    public static final int AI_TRIGGER_MULTILINE_NEWLINES_TO_SPACES = 2;  // send all text, replace newlines with spaces
    public static final int AI_TRIGGER_MULTILINE_REMOVE_NEWLINES = 3;     // send all text, delete newlines

    public int getAiTriggerMultilineMode() {
        int mode = AI_TRIGGER_MULTILINE_KEEP_NEWLINES; // default ON (keep newlines)
        try {
            mode = mClient.getInt(PREF_AI_TRIGGER_MULTILINE_MODE, Integer.MIN_VALUE);
        } catch (Throwable ignored) {
            // Some provider clients may not support int; fall back to string.
            try {
                String s = mClient.getString(PREF_AI_TRIGGER_MULTILINE_MODE, null);
                if (s != null) mode = Integer.parseInt(s);
                else mode = Integer.MIN_VALUE;
            } catch (Throwable ignored2) { mode = Integer.MIN_VALUE; }
        }

        if (mode == Integer.MIN_VALUE) {
            // Not set yet → migrate from legacy boolean
            boolean legacy = true;
            try { legacy = mClient.getBoolean(PREF_AI_TRIGGER_MULTILINE, true); } catch (Throwable ignored) {}
            mode = legacy ? AI_TRIGGER_MULTILINE_KEEP_NEWLINES : AI_TRIGGER_MULTILINE_OFF;
            setAiTriggerMultilineMode(mode);
        }

        // Clamp
        if (mode < AI_TRIGGER_MULTILINE_OFF || mode > AI_TRIGGER_MULTILINE_REMOVE_NEWLINES) {
            mode = AI_TRIGGER_MULTILINE_KEEP_NEWLINES;
            setAiTriggerMultilineMode(mode);
        }
        return mode;
    }

    public void setAiTriggerMultilineMode(int mode) {
        // Clamp
        if (mode < AI_TRIGGER_MULTILINE_OFF) mode = AI_TRIGGER_MULTILINE_OFF;
        if (mode > AI_TRIGGER_MULTILINE_REMOVE_NEWLINES) mode = AI_TRIGGER_MULTILINE_KEEP_NEWLINES;

        // Persist mode (int, fallback string)
        try {
            mClient.putInt(PREF_AI_TRIGGER_MULTILINE_MODE, mode);
        } catch (Throwable ignored) {
            try { mClient.putString(PREF_AI_TRIGGER_MULTILINE_MODE, String.valueOf(mode)); } catch (Throwable ignored2) {}
        }

        // Keep legacy boolean synced for older codepaths / other components
        try { mClient.putBoolean(PREF_AI_TRIGGER_MULTILINE, mode != AI_TRIGGER_MULTILINE_OFF); } catch (Throwable ignored) {}

        // Remember last ON mode (so the trigger UI switch can restore it)
        if (mode != AI_TRIGGER_MULTILINE_OFF) {
            try {
                mClient.putInt(PREF_AI_TRIGGER_MULTILINE_LAST_ON_MODE, mode);
            } catch (Throwable ignored) {
                try { mClient.putString(PREF_AI_TRIGGER_MULTILINE_LAST_ON_MODE, String.valueOf(mode)); } catch (Throwable ignored2) {}
            }
        }
    }

    public int getAiTriggerMultilineLastOnMode() {
        int v = AI_TRIGGER_MULTILINE_KEEP_NEWLINES;
        try {
            v = mClient.getInt(PREF_AI_TRIGGER_MULTILINE_LAST_ON_MODE, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_AI_TRIGGER_MULTILINE_LAST_ON_MODE, null);
                if (s != null) v = Integer.parseInt(s);
            } catch (Throwable ignored2) {}
        }
        if (v < AI_TRIGGER_MULTILINE_KEEP_NEWLINES || v > AI_TRIGGER_MULTILINE_REMOVE_NEWLINES) {
            v = AI_TRIGGER_MULTILINE_KEEP_NEWLINES;
        }
        return v;
    }

    public boolean getAiTriggerMultilineEnabled() {
        return getAiTriggerMultilineMode() != AI_TRIGGER_MULTILINE_OFF;
    }

    public void setAiTriggerMultilineEnabled(boolean enabled) {
        if (!enabled) {
            setAiTriggerMultilineMode(AI_TRIGGER_MULTILINE_OFF);
        } else {
            // Restore last ON mode (default: keep newlines)
            setAiTriggerMultilineMode(getAiTriggerMultilineLastOnMode());
        }
    }

    /**
     * Apply the configured newline policy to the prompt that will be sent by the AI trigger.
     */
    public String applyAiTriggerNewlinePolicy(String prompt) {
        if (prompt == null) return "";
        int mode = getAiTriggerMultilineMode();
        if (mode == AI_TRIGGER_MULTILINE_OFF) return prompt;

        // Normalize CRLF/CR to LF first
        String p = prompt.replace("\r\n", "\n").replace("\r", "\n");

        if (mode == AI_TRIGGER_MULTILINE_KEEP_NEWLINES) {
            return p;
        }
        if (mode == AI_TRIGGER_MULTILINE_NEWLINES_TO_SPACES) {
            // Replace newlines with spaces and collapse extra spaces
            p = p.replace("\n", " ");
            p = p.replaceAll("[\t ]+", " ");
            return p.trim();
        }
        if (mode == AI_TRIGGER_MULTILINE_REMOVE_NEWLINES) {
            // Remove newlines completely
            p = p.replace("\n", "");
            return p.trim();
        }
        return p;
    }

    // ===== Invocation master switches =====
    // Master toggles to quickly enable/disable all invocation commands or triggers.
    // They do NOT change the per-command/per-trigger configuration; they only gate runtime parsing.
    private static final String PREF_INVOCATION_COMMANDS_ENABLED = "invocation_commands_enabled_v1";
    private static final String PREF_INVOCATION_TRIGGERS_ENABLED = "invocation_triggers_enabled_v1";
    // Backup of per-trigger enabled states for the "master switch" bulk toggle (restore previous states).
    private static final String PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP = "invocation_triggers_enabled_states_backup_v1";

    // ===== Streaming output =====
    // When enabled, supported providers will request streaming responses and the keyboard
    // will show the answer as it arrives. When disabled, chunks are buffered and committed
    // only once at completion (and providers may request non-streaming responses).
    private static final String PREF_STREAMING_OUTPUT_ENABLED = "streaming_output_enabled_v1";

    // Streaming output animation speed (0-100). 100 = fastest.
    // This controls how quickly the keyboard commits streamed text chunks.
    private static final String PREF_STREAMING_OUTPUT_SPEED_PERCENT = "streaming_output_speed_percent_v1";
    // If enabled, speed is automatically adjusted based on response length/backlog.
    private static final String PREF_STREAMING_OUTPUT_SPEED_AUTO = "streaming_output_speed_auto_v1";

    // Streaming speed algorithm:
    // - LINEAR: use speed percent + auto pacing (current behavior)
    // - NONLINEAR: use physics/random models (beta; UI will be added later)
    private static final String PREF_STREAMING_OUTPUT_SPEED_ALGO = "streaming_output_speed_algo_v1";

    public static final int STREAM_SPEED_ALGO_LINEAR = 0;
    public static final int STREAM_SPEED_ALGO_NONLINEAR = 1;

    // Non-linear models (used when STREAM_SPEED_ALGO_NONLINEAR is selected)
    private static final String PREF_STREAMING_NONLINEAR_MODEL = "streaming_nonlinear_model_v1";

    public static final int STREAM_NL_MODEL_LINEAR_CONSTANT = 0;
    public static final int STREAM_NL_MODEL_EXPONENTIAL_DECAY = 1;
    public static final int STREAM_NL_MODEL_SINE_WAVE_JITTER = 2;
    public static final int STREAM_NL_MODEL_DAMPED_OSCILLATOR = 3;
    public static final int STREAM_NL_MODEL_SQUARE_WAVE_BURST = 4;
    public static final int STREAM_NL_MODEL_MARKOV_RANDOM_WALK = 5;

    // New geek models (v16+):
    // 6: PERLIN_NOISE (柏林噪声流)
    // 7: PID_CONTROLLER (PID 动态寻航)
    // 8: LOGISTIC_FATIGUE (逻辑斯谛疲劳)
    // 9: RETRO_TYPEWRITER (机械打字机)
    public static final int STREAM_NL_MODEL_PERLIN_NOISE = 6;
    public static final int STREAM_NL_MODEL_PID_CONTROLLER = 7;
    public static final int STREAM_NL_MODEL_LOGISTIC_FATIGUE = 8;
    public static final int STREAM_NL_MODEL_RETRO_TYPEWRITER = 9;

    // Global non-linear parameters
    // sigma: Gaussian noise (ms) applied to each tick delay
    // pauseMultiplier: multiplies delay after punctuation boundaries
    private static final String PREF_STREAMING_NONLINEAR_SIGMA_MS = "streaming_nonlinear_sigma_ms_v1";
    private static final String PREF_STREAMING_NONLINEAR_PAUSE_MULT = "streaming_nonlinear_pause_mult_v1";

    // Prefetch/render buffer (only used for NON-LINEAR streaming output)
    // This smooths network chunk jitter by buffering some text before rendering.
    private static final String PREF_STREAMING_PREFETCH_MODE = "streaming_prefetch_mode_v1";
    private static final String PREF_STREAMING_PREFETCH_START_CHARS = "streaming_prefetch_start_chars_v1";
    private static final String PREF_STREAMING_PREFETCH_LOW_WATERMARK = "streaming_prefetch_low_watermark_v1";
    private static final String PREF_STREAMING_PREFETCH_TOPUP_TARGET = "streaming_prefetch_topup_target_v1";

    // Prefetch modes
    public static final int STREAM_PREFETCH_OFF = 0;
    public static final int STREAM_PREFETCH_DEFAULT = 1;
    public static final int STREAM_PREFETCH_FAST = 2;
    public static final int STREAM_PREFETCH_STABLE = 3;
    public static final int STREAM_PREFETCH_CUSTOM = 4;

    // Per-model parameters (stored as int ms or string double)
    private static final String PREF_NL_LC_TBASE_MS = "streaming_nl_lc_tbase_ms_v1";

    private static final String PREF_NL_EXP_TMAX_MS = "streaming_nl_exp_tmax_ms_v1";
    private static final String PREF_NL_EXP_TMIN_MS = "streaming_nl_exp_tmin_ms_v1";
    private static final String PREF_NL_EXP_LAMBDA = "streaming_nl_exp_lambda_v1";

    private static final String PREF_NL_SINE_TBASE_MS = "streaming_nl_sine_tbase_ms_v1";
    private static final String PREF_NL_SINE_A_MS = "streaming_nl_sine_a_ms_v1";
    private static final String PREF_NL_SINE_OMEGA = "streaming_nl_sine_omega_v1";
    private static final String PREF_NL_SINE_PHI = "streaming_nl_sine_phi_v1";
    private static final String PREF_NL_SINE_PERIOD_N = "streaming_nl_sine_period_n_v1";

    private static final String PREF_NL_DAMP_TBASE_MS = "streaming_nl_damp_tbase_ms_v1";
    private static final String PREF_NL_DAMP_A_MS = "streaming_nl_damp_a_ms_v1";
    private static final String PREF_NL_DAMP_OMEGA = "streaming_nl_damp_omega_v1";
    private static final String PREF_NL_DAMP_ZETA = "streaming_nl_damp_zeta_v1";
    private static final String PREF_NL_DAMP_PHI = "streaming_nl_damp_phi_v1";

    private static final String PREF_NL_SQ_TBASE_MS = "streaming_nl_sq_tbase_ms_v1";
    private static final String PREF_NL_SQ_A_MS = "streaming_nl_sq_a_ms_v1";
    private static final String PREF_NL_SQ_OMEGA = "streaming_nl_sq_omega_v1";

    private static final String PREF_NL_MK_MU_MS = "streaming_nl_mk_mu_ms_v1";
    private static final String PREF_NL_MK_RHO = "streaming_nl_mk_rho_v1";
    private static final String PREF_NL_MK_SIGMA_MS = "streaming_nl_mk_sigma_ms_v1";
    private static final String PREF_NL_MK_TMIN_MS = "streaming_nl_mk_tmin_ms_v1";
    private static final String PREF_NL_MK_TMAX_MS = "streaming_nl_mk_tmax_ms_v1";
    private static final String PREF_NL_MK_PTHINK_PERCENT = "streaming_nl_mk_pthink_percent_v1";


    // v16+ geek models (3 params each)
    // Perlin Noise Flow
    private static final String PREF_NL_PERLIN_BASE_SPEED = "streaming_nl_perlin_base_speed_v1";
    private static final String PREF_NL_PERLIN_OCTAVES = "streaming_nl_perlin_octaves_v1";
    private static final String PREF_NL_PERLIN_FREQUENCY = "streaming_nl_perlin_frequency_v1";

    // PID Controller
    private static final String PREF_NL_PID_TARGET_SPEED = "streaming_nl_pid_target_speed_v1";
    private static final String PREF_NL_PID_P = "streaming_nl_pid_p_v1";
    private static final String PREF_NL_PID_D = "streaming_nl_pid_d_v1";

    // Logistic Fatigue
    private static final String PREF_NL_LOGI_INITIAL_BURST_MS = "streaming_nl_logi_initial_burst_ms_v1";
    private static final String PREF_NL_LOGI_DECAY_HALFLIFE = "streaming_nl_logi_decay_halflife_v1";
    private static final String PREF_NL_LOGI_RECOVERY = "streaming_nl_logi_recovery_v1";

    // Retro Typewriter
    private static final String PREF_NL_RETRO_KEY_SPEED_MS = "streaming_nl_retro_key_speed_ms_v1";
    private static final String PREF_NL_RETRO_CR_DELAY_MS = "streaming_nl_retro_cr_delay_ms_v1";
    private static final String PREF_NL_RETRO_PUNC_DRAG_MS = "streaming_nl_retro_punc_drag_ms_v1";


    // Streaming output mode:
    // - AUTO: Detect by response format (SSE "data:" vs JSON lines)
    // - SSE: Force Server-Sent Events parsing (data: ...)
    // - JSONL: Force JSON Lines parsing (one JSON object per line)
    // - TYPEWRITER: Request non-streaming from backend and render with the local typewriter
    //               (useful when backend doesn't support streaming or format is incompatible)
    private static final String PREF_STREAMING_OUTPUT_MODE = "streaming_output_mode_v1";

    // Last AUTO-detected stream format (for UI hints). Values: STREAM_MODE_SSE / STREAM_MODE_JSONL, or -1 unknown.
    private static final String PREF_STREAMING_OUTPUT_AUTO_DETECT_MODE = "streaming_output_auto_detect_mode_v1";
    private static final String PREF_STREAMING_OUTPUT_AUTO_DETECT_TS = "streaming_output_auto_detect_ts_v1";

    // Output render granularity:
    // - CHARS: commit characters
    // - WORDS: snap to whitespace boundaries
    // - PUNCT: snap to punctuation boundaries
    private static final String PREF_STREAMING_OUTPUT_GRANULARITY = "streaming_output_granularity_v1";

    // If streaming was requested but stream parsing produced no chunks, try to parse
    // a non-streaming OpenAI-compatible JSON response and display it.
    private static final String PREF_STREAMING_OUTPUT_FALLBACK_NON_STREAM = "streaming_output_fallback_non_stream_v1";

    // Public constants for code/UI
    public static final int STREAM_MODE_AUTO = 0;
    public static final int STREAM_MODE_SSE = 1;
    public static final int STREAM_MODE_JSONL = 2;
    public static final int STREAM_MODE_TYPEWRITER = 3;

    public static final int STREAM_GRANULARITY_CHARS = 0;
    public static final int STREAM_GRANULARITY_WORDS = 1;
    public static final int STREAM_GRANULARITY_PUNCT = 2;


    // Per-request overrides (ThreadLocal). Used by auto-downgrade retry logic to
    // force a different streaming mode without changing the user's global setting.
    private static final ThreadLocal<Integer> TL_STREAM_MODE_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<String> TL_REASONING_EFFORT_OVERRIDE = new ThreadLocal<>();

    public static void setThreadStreamingModeOverride(Integer mode) {
        if (mode == null) {
            TL_STREAM_MODE_OVERRIDE.remove();
            return;
        }
        TL_STREAM_MODE_OVERRIDE.set(mode);
    }

    public static void clearThreadStreamingModeOverride() {
        TL_STREAM_MODE_OVERRIDE.remove();
    }

    public static void setThreadReasoningEffortOverride(String effort) {
        if (effort == null) {
            TL_REASONING_EFFORT_OVERRIDE.remove();
            return;
        }
        String v = effort.trim();
        if (v.isEmpty()) {
            TL_REASONING_EFFORT_OVERRIDE.remove();
            return;
        }
        TL_REASONING_EFFORT_OVERRIDE.set(v);
    }

    public static String getThreadReasoningEffortOverride() {
        return TL_REASONING_EFFORT_OVERRIDE.get();
    }

    public static void clearThreadReasoningEffortOverride() {
        TL_REASONING_EFFORT_OVERRIDE.remove();
    }

    public boolean getStreamingOutputEnabled() {
        return mClient.getBoolean(PREF_STREAMING_OUTPUT_ENABLED, false);
    }

    public void setStreamingOutputEnabled(boolean enabled) {
        mClient.putBoolean(PREF_STREAMING_OUTPUT_ENABLED, enabled);
    }

    /** 0..100, 100 = fastest. */
    public int getStreamingOutputSpeedPercent() {
        int v = 45; // default: a bit slower (can adjust in Labs)
        try {
            v = mClient.getInt(PREF_STREAMING_OUTPUT_SPEED_PERCENT, v);
        } catch (Throwable ignored) {
            // Some environments may store it as string/bool; fall back to default.
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_SPEED_PERCENT, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setStreamingOutputSpeedPercent(int percent) {
        int v = percent;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try {
            mClient.putInt(PREF_STREAMING_OUTPUT_SPEED_PERCENT, v);
        } catch (Throwable t) {
            // Fallback to string if putInt is not supported by the underlying client.
            try { mClient.putString(PREF_STREAMING_OUTPUT_SPEED_PERCENT, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Auto speed adjustment based on response length/backlog. */
    public boolean getStreamingOutputSpeedAutoEnabled() {
        return mClient.getBoolean(PREF_STREAMING_OUTPUT_SPEED_AUTO, true); // default ON
    }

    public void setStreamingOutputSpeedAutoEnabled(boolean enabled) {
        mClient.putBoolean(PREF_STREAMING_OUTPUT_SPEED_AUTO, enabled);
    }

    // --- Streaming speed algorithm (Linear / Non-linear) ---

    public int getStreamingOutputSpeedAlgorithm() {
        int v = STREAM_SPEED_ALGO_LINEAR;
        try {
            v = mClient.getInt(PREF_STREAMING_OUTPUT_SPEED_ALGO, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_SPEED_ALGO, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        return (v == STREAM_SPEED_ALGO_NONLINEAR) ? STREAM_SPEED_ALGO_NONLINEAR : STREAM_SPEED_ALGO_LINEAR;
    }

    public void setStreamingOutputSpeedAlgorithm(int algo) {
        int v = (algo == STREAM_SPEED_ALGO_NONLINEAR) ? STREAM_SPEED_ALGO_NONLINEAR : STREAM_SPEED_ALGO_LINEAR;
        try {
            mClient.putInt(PREF_STREAMING_OUTPUT_SPEED_ALGO, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_OUTPUT_SPEED_ALGO, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    public int getStreamingNonLinearModel() {
        int v = STREAM_NL_MODEL_MARKOV_RANDOM_WALK; // default (best realism)
        try {
            v = mClient.getInt(PREF_STREAMING_NONLINEAR_MODEL, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_NONLINEAR_MODEL, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < STREAM_NL_MODEL_LINEAR_CONSTANT) v = STREAM_NL_MODEL_LINEAR_CONSTANT;
        if (v > STREAM_NL_MODEL_RETRO_TYPEWRITER) v = STREAM_NL_MODEL_RETRO_TYPEWRITER;
        return v;
    }

    public void setStreamingNonLinearModel(int model) {
        int v = model;
        if (v < STREAM_NL_MODEL_LINEAR_CONSTANT) v = STREAM_NL_MODEL_LINEAR_CONSTANT;
        if (v > STREAM_NL_MODEL_RETRO_TYPEWRITER) v = STREAM_NL_MODEL_RETRO_TYPEWRITER;
        try {
            mClient.putInt(PREF_STREAMING_NONLINEAR_MODEL, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_NONLINEAR_MODEL, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    // Helpers for doubles stored as strings (ConfigClient has no float/double type)
    private static double safeParseDouble(String s, double def) {
        if (s == null) return def;
        try {
            String t = s.trim();
            if (t.isEmpty()) return def;
            return Double.parseDouble(t);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private double getDouble(String key, double def) {
        try {
            return safeParseDouble(mClient.getString(key, null), def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private void putDouble(String key, double v) {
        try {
            mClient.putString(key, Double.toString(v));
        } catch (Throwable ignored) {}
    }

    /** Non-linear sigma in milliseconds (standard deviation). */
    public int getStreamingNonLinearSigmaMs() {
        int v = 0;
        try {
            v = mClient.getInt(PREF_STREAMING_NONLINEAR_SIGMA_MS, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_NONLINEAR_SIGMA_MS, null);
                if (s != null) v = (int) Math.round(Double.parseDouble(s.trim()));
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 250) v = 250;
        return v;
    }

    public void setStreamingNonLinearSigmaMs(int sigmaMs) {
        int v = sigmaMs;
        if (v < 0) v = 0;
        if (v > 250) v = 250;
        try {
            mClient.putInt(PREF_STREAMING_NONLINEAR_SIGMA_MS, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_NONLINEAR_SIGMA_MS, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Pause multiplier applied after punctuation boundaries. */
    public double getStreamingNonLinearPauseMultiplier() {
        double v = getDouble(PREF_STREAMING_NONLINEAR_PAUSE_MULT, 2.0);
        if (v < 1.0) v = 1.0;
        if (v > 6.0) v = 6.0;
        return v;
    }

    public void setStreamingNonLinearPauseMultiplier(double mult) {
        double v = mult;
        if (v < 1.0) v = 1.0;
        if (v > 6.0) v = 6.0;
        putDouble(PREF_STREAMING_NONLINEAR_PAUSE_MULT, v);
    }

    // --- Prefetch / render buffer (NON-LINEAR only) ---

    /**
     * Prefetch mode controls how the app buffers text before rendering to smooth network chunk jitter.
     * Only applied when streaming algorithm is NON-LINEAR.
     */
    public int getStreamingPrefetchMode() {
        int v = STREAM_PREFETCH_DEFAULT;
        try {
            v = mClient.getInt(PREF_STREAMING_PREFETCH_MODE, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_PREFETCH_MODE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < STREAM_PREFETCH_OFF) v = STREAM_PREFETCH_OFF;
        if (v > STREAM_PREFETCH_CUSTOM) v = STREAM_PREFETCH_CUSTOM;
        return v;
    }

    public void setStreamingPrefetchMode(int mode) {
        int v = mode;
        if (v < STREAM_PREFETCH_OFF) v = STREAM_PREFETCH_OFF;
        if (v > STREAM_PREFETCH_CUSTOM) v = STREAM_PREFETCH_CUSTOM;
        try {
            mClient.putInt(PREF_STREAMING_PREFETCH_MODE, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_PREFETCH_MODE, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Custom: how many chars to prefetch before starting rendering. */
    public int getStreamingPrefetchStartChars() {
        int v = 120;
        try {
            v = mClient.getInt(PREF_STREAMING_PREFETCH_START_CHARS, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_PREFETCH_START_CHARS, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 1200) v = 1200;
        return v;
    }

    public void setStreamingPrefetchStartChars(int startChars) {
        int v = startChars;
        if (v < 0) v = 0;
        if (v > 1200) v = 1200;
        try {
            mClient.putInt(PREF_STREAMING_PREFETCH_START_CHARS, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_PREFETCH_START_CHARS, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Custom: low-water mark for render buffer. */
    public int getStreamingPrefetchLowWatermark() {
        int v = 80;
        try {
            v = mClient.getInt(PREF_STREAMING_PREFETCH_LOW_WATERMARK, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_PREFETCH_LOW_WATERMARK, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 1200) v = 1200;
        return v;
    }

    public void setStreamingPrefetchLowWatermark(int low) {
        int v = low;
        if (v < 0) v = 0;
        if (v > 1200) v = 1200;
        try {
            mClient.putInt(PREF_STREAMING_PREFETCH_LOW_WATERMARK, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_PREFETCH_LOW_WATERMARK, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Custom: top-up target for render buffer when it falls below low-water. */
    public int getStreamingPrefetchTopUpTarget() {
        int v = 260;
        try {
            v = mClient.getInt(PREF_STREAMING_PREFETCH_TOPUP_TARGET, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_PREFETCH_TOPUP_TARGET, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 2400) v = 2400;
        return v;
    }

    public void setStreamingPrefetchTopUpTarget(int target) {
        int v = target;
        if (v < 0) v = 0;
        if (v > 2400) v = 2400;
        try {
            mClient.putInt(PREF_STREAMING_PREFETCH_TOPUP_TARGET, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_PREFETCH_TOPUP_TARGET, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    // --- Per-model defaults + getters (used by AiResponseManager non-linear framework) ---

    public int getNlLinearConstantTBaseMs() {
        int v = 50;
        try { v = mClient.getInt(PREF_NL_LC_TBASE_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 200) v = 200;
        return v;
    }

    public void setNlLinearConstantTBaseMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 200) v = 200;
        try { mClient.putInt(PREF_NL_LC_TBASE_MS, v); } catch (Throwable ignored) { try { mClient.putString(PREF_NL_LC_TBASE_MS, String.valueOf(v)); } catch (Throwable ignored2) {} }
    }

    public int getNlExpTMaxMs() {
        int v = 220;
        try { v = mClient.getInt(PREF_NL_EXP_TMAX_MS, v); } catch (Throwable ignored) {}
        if (v < 20) v = 20;
        if (v > 1200) v = 1200;
        return v;
    }

    public int getNlExpTMinMs() {
        int v = 28;
        try { v = mClient.getInt(PREF_NL_EXP_TMIN_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 800) v = 800;
        return v;
    }

    public double getNlExpLambda() {
        double v = getDouble(PREF_NL_EXP_LAMBDA, 0.045);
        if (v < 0.0001) v = 0.0001;
        if (v > 1.0) v = 1.0;
        return v;
    }

    public void setNlExpTMaxMs(int ms) {
        int v = ms;
        if (v < 20) v = 20;
        if (v > 1200) v = 1200;
        try { mClient.putInt(PREF_NL_EXP_TMAX_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_EXP_TMAX_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlExpTMinMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 800) v = 800;
        try { mClient.putInt(PREF_NL_EXP_TMIN_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_EXP_TMIN_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlExpLambda(double lambda) {
        double v = lambda;
        if (v < 0.0001) v = 0.0001;
        if (v > 1.0) v = 1.0;
        putDouble(PREF_NL_EXP_LAMBDA, v);
    }


    public int getNlSineTBaseMs() {
        int v = 60;
        try { v = mClient.getInt(PREF_NL_SINE_TBASE_MS, v); } catch (Throwable ignored) {}
        if (v < 20) v = 20;
        if (v > 150) v = 150;
        return v;
    }

    public int getNlSineAMs() {
        int v = 30;
        try { v = mClient.getInt(PREF_NL_SINE_A_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 100) v = 100;
        return v;
    }

    public double getNlSineOmega() {
        // Derived from breathing period (N chars per full cycle): ω = 2π / N
        int n = getNlSinePeriodN();
        if (n < 5) n = 5;
        return (2.0 * Math.PI) / (double) n;
    }

    public double getNlSinePhi() {
        double v = getDouble(PREF_NL_SINE_PHI, 0.0);
        if (v < -6.283185) v = -6.283185;
        if (v >  6.283185) v =  6.283185;
        return v;
    }


    public int getNlSinePeriodN() {
        int v = 15;
        try { v = mClient.getInt(PREF_NL_SINE_PERIOD_N, v); } catch (Throwable ignored) {}
        if (v < 5) v = 5;
        if (v > 50) v = 50;
        return v;
    }

    public void setNlSinePeriodN(int n) {
        int v = n;
        if (v < 5) v = 5;
        if (v > 50) v = 50;
        try { mClient.putInt(PREF_NL_SINE_PERIOD_N, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_SINE_PERIOD_N, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlSineTBaseMs(int ms) {
        int v = ms;
        if (v < 20) v = 20;
        if (v > 150) v = 150;
        try { mClient.putInt(PREF_NL_SINE_TBASE_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_SINE_TBASE_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlSineAMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_NL_SINE_A_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_SINE_A_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public int getNlDampTBaseMs() {
        int v = 90;
        try { v = mClient.getInt(PREF_NL_DAMP_TBASE_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 900) v = 900;
        return v;
    }

    public int getNlDampAMs() {
        int v = 85;
        try { v = mClient.getInt(PREF_NL_DAMP_A_MS, v); } catch (Throwable ignored) {}
        if (v < 0) v = 0;
        if (v > 800) v = 800;
        return v;
    }

    public double getNlDampOmega() {
        double v = getDouble(PREF_NL_DAMP_OMEGA, 1.1);
        if (v < 0.01) v = 0.01;
        if (v > 20.0) v = 20.0;
        return v;
    }

    public double getNlDampZeta() {
        double v = getDouble(PREF_NL_DAMP_ZETA, 0.05);
        if (v < 0.0) v = 0.0;
        if (v > 5.0) v = 5.0;
        return v;
    }

    public double getNlDampPhi() {
        double v = getDouble(PREF_NL_DAMP_PHI, 0.0);
        if (v < -6.283185) v = -6.283185;
        if (v >  6.283185) v =  6.283185;
        return v;
    }

    public void setNlDampAMs(int ms) {
        int v = ms;
        if (v < 0) v = 0;
        if (v > 800) v = 800;
        try { mClient.putInt(PREF_NL_DAMP_A_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_DAMP_A_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlDampOmega(double omega) {
        double v = omega;
        if (v < 0.01) v = 0.01;
        if (v > 20.0) v = 20.0;
        putDouble(PREF_NL_DAMP_OMEGA, v);
    }

    public void setNlDampZeta(double zeta) {
        double v = zeta;
        if (v < 0.0) v = 0.0;
        if (v > 5.0) v = 5.0;
        putDouble(PREF_NL_DAMP_ZETA, v);
    }


    public int getNlSquareTBaseMs() {
        int v = 95;
        try { v = mClient.getInt(PREF_NL_SQ_TBASE_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 900) v = 900;
        return v;
    }

    public int getNlSquareAMs() {
        int v = 70;
        try { v = mClient.getInt(PREF_NL_SQ_A_MS, v); } catch (Throwable ignored) {}
        if (v < 0) v = 0;
        if (v > 800) v = 800;
        return v;
    }

    public double getNlSquareOmega() {
        double v = getDouble(PREF_NL_SQ_OMEGA, 0.7);
        if (v < 0.01) v = 0.01;
        if (v > 20.0) v = 20.0;
        return v;
    }

    public void setNlSquareAMs(int ms) {
        int v = ms;
        if (v < 0) v = 0;
        if (v > 800) v = 800;
        try { mClient.putInt(PREF_NL_SQ_A_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_SQ_A_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlSquareOmega(double omega) {
        double v = omega;
        if (v < 0.01) v = 0.01;
        if (v > 20.0) v = 20.0;
        putDouble(PREF_NL_SQ_OMEGA, v);
    }


    public int getNlMarkovMuMs() {
        int v = 80;
        try { v = mClient.getInt(PREF_NL_MK_MU_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 200) v = 200;
        return v;
    }

    public void setNlMarkovMuMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 200) v = 200;
        try { mClient.putInt(PREF_NL_MK_MU_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_MK_MU_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public double getNlMarkovRho() {
        double v = getDouble(PREF_NL_MK_RHO, 0.90);
        if (v < 0.0) v = 0.0;
        if (v > 0.99) v = 0.99;
        return v;
    }

    public void setNlMarkovRho(double rho) {
        double v = rho;
        if (v < 0.0) v = 0.0;
        if (v > 0.99) v = 0.99;
        putDouble(PREF_NL_MK_RHO, v);
    }

    public int getNlMarkovSigmaMs() {
        int v = 25;
        try { v = mClient.getInt(PREF_NL_MK_SIGMA_MS, v); } catch (Throwable ignored) {}
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setNlMarkovSigmaMs(int ms) {
        int v = ms;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_NL_MK_SIGMA_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_MK_SIGMA_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public int getNlMarkovTMinMs() {
        int v = 30;
        try { v = mClient.getInt(PREF_NL_MK_TMIN_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 100) v = 100;
        return v;
    }

    public void setNlMarkovTMinMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_NL_MK_TMIN_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_MK_TMIN_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public int getNlMarkovTMaxMs() {
        int v = 450;
        try { v = mClient.getInt(PREF_NL_MK_TMAX_MS, v); } catch (Throwable ignored) {}
        if (v < 100) v = 100;
        if (v > 1000) v = 1000;
        return v;
    }

    public void setNlMarkovTMaxMs(int ms) {
        int v = ms;
        if (v < 100) v = 100;
        if (v > 1000) v = 1000;
        try { mClient.putInt(PREF_NL_MK_TMAX_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_MK_TMAX_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    /** Percent in [0, 10]. */
    public double getNlMarkovPThinkPercent() {
        double v = getDouble(PREF_NL_MK_PTHINK_PERCENT, 2.0);
        if (v < 0.0) v = 0.0;
        if (v > 10.0) v = 10.0;
        return v;
    }

    /** Probability in [0, 0.10]. */
    public double getNlMarkovPThinkProbability() {
        return getNlMarkovPThinkPercent() / 100.0;
    }

    public void setNlMarkovPThinkPercent(double percent) {
        double v = percent;
        if (v < 0.0) v = 0.0;
        if (v > 10.0) v = 10.0;
        putDouble(PREF_NL_MK_PTHINK_PERCENT, v);
    }


    // ===== v16+ geek models (Perlin / PID / Logistic / Retro) =====

    // --- Perlin Noise Flow ---

    /** Base flow speed (unitless). Higher = faster. */
    public double getNlPerlinBaseSpeed() {
        double v = getDouble(PREF_NL_PERLIN_BASE_SPEED, 1.25);
        if (v < 0.40) v = 0.40;
        if (v > 3.00) v = 3.00;
        return v;
    }

    public void setNlPerlinBaseSpeed(double speed) {
        double v = speed;
        if (v < 0.40) v = 0.40;
        if (v > 3.00) v = 3.00;
        putDouble(PREF_NL_PERLIN_BASE_SPEED, v);
    }

    /** Octaves (roughness). Higher = more detail/rough. */
    public int getNlPerlinOctaves() {
        int v = 3;
        try { v = mClient.getInt(PREF_NL_PERLIN_OCTAVES, v); } catch (Throwable ignored) {}
        if (v < 1) v = 1;
        if (v > 8) v = 8;
        return v;
    }

    public void setNlPerlinOctaves(int octaves) {
        int v = octaves;
        if (v < 1) v = 1;
        if (v > 8) v = 8;
        try { mClient.putInt(PREF_NL_PERLIN_OCTAVES, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_PERLIN_OCTAVES, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    /**
     * Evolution frequency knob (unitless). UI is "bigger = slower".
     * Internally we invert it when applying to the phase step.
     */
    public double getNlPerlinFrequency() {
        double v = getDouble(PREF_NL_PERLIN_FREQUENCY, 1.10);
        if (v < 0.20) v = 0.20;
        if (v > 4.00) v = 4.00;
        return v;
    }

    public void setNlPerlinFrequency(double freq) {
        double v = freq;
        if (v < 0.20) v = 0.20;
        if (v > 4.00) v = 4.00;
        putDouble(PREF_NL_PERLIN_FREQUENCY, v);
    }

    // --- PID Controller ---

    /** Target cruise speed (unitless). Higher = faster. */
    public double getNlPidTargetSpeed() {
        double v = getDouble(PREF_NL_PID_TARGET_SPEED, 1.35);
        if (v < 0.40) v = 0.40;
        if (v > 3.50) v = 3.50;
        return v;
    }

    public void setNlPidTargetSpeed(double speed) {
        double v = speed;
        if (v < 0.40) v = 0.40;
        if (v > 3.50) v = 3.50;
        putDouble(PREF_NL_PID_TARGET_SPEED, v);
    }

    /** P (proportional) gain. Higher = more sensitive. */
    public double getNlPidP() {
        double v = getDouble(PREF_NL_PID_P, 0.22);
        if (v < 0.02) v = 0.02;
        if (v > 2.00) v = 2.00;
        return v;
    }

    public void setNlPidP(double p) {
        double v = p;
        if (v < 0.02) v = 0.02;
        if (v > 2.00) v = 2.00;
        putDouble(PREF_NL_PID_P, v);
    }

    /** D (derivative) damping. Higher = more cautious / less overshoot. */
    public double getNlPidD() {
        double v = getDouble(PREF_NL_PID_D, 0.12);
        if (v < 0.0) v = 0.0;
        if (v > 2.00) v = 2.00;
        return v;
    }

    public void setNlPidD(double d) {
        double v = d;
        if (v < 0.0) v = 0.0;
        if (v > 2.00) v = 2.00;
        putDouble(PREF_NL_PID_D, v);
    }

    // --- Logistic Fatigue ---

    /** Initial burst delay (ms). Lower = faster burst. */
    public int getNlLogisticInitialBurstMs() {
        int v = 24;
        try { v = mClient.getInt(PREF_NL_LOGI_INITIAL_BURST_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 260) v = 260;
        return v;
    }

    public void setNlLogisticInitialBurstMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 260) v = 260;
        try { mClient.putInt(PREF_NL_LOGI_INITIAL_BURST_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_LOGI_INITIAL_BURST_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    /**
     * Fatigue half-life knob (unitless ~ticks). Smaller = burns out faster.
     * Internally we convert to a decay factor.
     */
    public double getNlLogisticDecayHalfLife() {
        double v = getDouble(PREF_NL_LOGI_DECAY_HALFLIFE, 40.0);
        if (v < 6.0) v = 6.0;
        if (v > 220.0) v = 220.0;
        return v;
    }

    public void setNlLogisticDecayHalfLife(double halfLife) {
        double v = halfLife;
        if (v < 6.0) v = 6.0;
        if (v > 220.0) v = 220.0;
        putDouble(PREF_NL_LOGI_DECAY_HALFLIFE, v);
    }

    /** Recovery rate (0..1). Higher = recovers faster after pauses. */
    public double getNlLogisticRecovery() {
        double v = getDouble(PREF_NL_LOGI_RECOVERY, 0.35);
        if (v < 0.0) v = 0.0;
        if (v > 1.0) v = 1.0;
        return v;
    }

    public void setNlLogisticRecovery(double rec) {
        double v = rec;
        if (v < 0.0) v = 0.0;
        if (v > 1.0) v = 1.0;
        putDouble(PREF_NL_LOGI_RECOVERY, v);
    }

    // --- Retro Typewriter ---

    /** Key speed delay (ms). Lower = faster tapping. */
    public int getNlRetroKeySpeedMs() {
        int v = 38;
        try { v = mClient.getInt(PREF_NL_RETRO_KEY_SPEED_MS, v); } catch (Throwable ignored) {}
        if (v < 12) v = 12;
        if (v > 240) v = 240;
        return v;
    }

    public void setNlRetroKeySpeedMs(int ms) {
        int v = ms;
        if (v < 12) v = 12;
        if (v > 240) v = 240;
        try { mClient.putInt(PREF_NL_RETRO_KEY_SPEED_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_RETRO_KEY_SPEED_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    /** Carriage return extra delay (ms) when hitting newline. */
    public int getNlRetroCrDelayMs() {
        int v = 240;
        try { v = mClient.getInt(PREF_NL_RETRO_CR_DELAY_MS, v); } catch (Throwable ignored) {}
        if (v < 0) v = 0;
        if (v > 1600) v = 1600;
        return v;
    }

    public void setNlRetroCrDelayMs(int ms) {
        int v = ms;
        if (v < 0) v = 0;
        if (v > 1600) v = 1600;
        try { mClient.putInt(PREF_NL_RETRO_CR_DELAY_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_RETRO_CR_DELAY_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    /** Extra drag after punctuation (ms). */
    public int getNlRetroPuncDragMs() {
        int v = 80;
        try { v = mClient.getInt(PREF_NL_RETRO_PUNC_DRAG_MS, v); } catch (Throwable ignored) {}
        if (v < 0) v = 0;
        if (v > 600) v = 600;
        return v;
    }

    public void setNlRetroPuncDragMs(int ms) {
        int v = ms;
        if (v < 0) v = 0;
        if (v > 600) v = 600;
        try { mClient.putInt(PREF_NL_RETRO_PUNC_DRAG_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_RETRO_PUNC_DRAG_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }


    /** Streaming mode preference. Default: AUTO. */
    public int getStreamingOutputMode() {
        int v = STREAM_MODE_AUTO;
        try {
            v = mClient.getInt(PREF_STREAMING_OUTPUT_MODE, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_MODE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {
            }
        }
        if (v < STREAM_MODE_AUTO || v > STREAM_MODE_TYPEWRITER) v = STREAM_MODE_AUTO;
        return v;
    }

    /** Streaming mode for the current request. Can be overridden via ThreadLocal. */
    public int getStreamingOutputModeForRequest() {
        Integer o = TL_STREAM_MODE_OVERRIDE.get();
        if (o != null) return o;
        return getStreamingOutputMode();
    }


    public void setStreamingOutputMode(int mode) {
        int v = mode;
        if (v < STREAM_MODE_AUTO || v > STREAM_MODE_TYPEWRITER) v = STREAM_MODE_AUTO;
        try {
            mClient.putInt(PREF_STREAMING_OUTPUT_MODE, v);
        } catch (Throwable t) {
            try {
                mClient.putString(PREF_STREAMING_OUTPUT_MODE, String.valueOf(v));
            } catch (Throwable ignored) {
            }
        }
    }


    /** Last AUTO-detected stream format for UI hints. Returns STREAM_MODE_SSE/JSONL, or -1 if unknown. */
    public int getStreamingOutputAutoDetectedMode() {
        int v = -1;
        try {
            v = mClient.getInt(PREF_STREAMING_OUTPUT_AUTO_DETECT_MODE, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_AUTO_DETECT_MODE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {
            }
        }
        if (v != STREAM_MODE_SSE && v != STREAM_MODE_JSONL) v = -1;
        return v;
    }

    /** Timestamp (epoch ms) when AUTO-detect was last updated. 0 if unknown. */
    public long getStreamingOutputAutoDetectedAtMs() {
        long v = 0L;
        try {
            v = mClient.getLong(PREF_STREAMING_OUTPUT_AUTO_DETECT_TS, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_AUTO_DETECT_TS, null);
                if (s != null) v = Long.parseLong(s.trim());
            } catch (Throwable ignored2) {
            }
        }
        if (v < 0) v = 0;
        return v;
    }

    /** Update last AUTO-detected stream format for UI hints. Accepts STREAM_MODE_SSE/JSONL; other values set unknown (-1). */
    public void setStreamingOutputAutoDetectedMode(int mode) {
        int v = mode;
        if (v != STREAM_MODE_SSE && v != STREAM_MODE_JSONL) v = -1;
        try {
            mClient.putInt(PREF_STREAMING_OUTPUT_AUTO_DETECT_MODE, v);
        } catch (Throwable t) {
            try {
                mClient.putString(PREF_STREAMING_OUTPUT_AUTO_DETECT_MODE, String.valueOf(v));
            } catch (Throwable ignored) {
            }
        }
        try {
            long ts = System.currentTimeMillis();
            mClient.putLong(PREF_STREAMING_OUTPUT_AUTO_DETECT_TS, ts);
        } catch (Throwable t) {
            try {
                mClient.putString(PREF_STREAMING_OUTPUT_AUTO_DETECT_TS, String.valueOf(System.currentTimeMillis()));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Output render granularity. Default: CHARS. */
    public int getStreamingOutputGranularity() {
        int v = STREAM_GRANULARITY_CHARS;
        try {
            v = mClient.getInt(PREF_STREAMING_OUTPUT_GRANULARITY, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_GRANULARITY, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {
            }
        }
        if (v < STREAM_GRANULARITY_CHARS || v > STREAM_GRANULARITY_PUNCT) v = STREAM_GRANULARITY_CHARS;
        return v;
    }

    public void setStreamingOutputGranularity(int granularity) {
        int v = granularity;
        if (v < STREAM_GRANULARITY_CHARS || v > STREAM_GRANULARITY_PUNCT) v = STREAM_GRANULARITY_CHARS;
        try {
            mClient.putInt(PREF_STREAMING_OUTPUT_GRANULARITY, v);
        } catch (Throwable t) {
            try {
                mClient.putString(PREF_STREAMING_OUTPUT_GRANULARITY, String.valueOf(v));
            } catch (Throwable ignored) {
            }
        }
    }

    /** If enabled, fallback to non-stream response parsing when stream parsing yields nothing. */
    public boolean getStreamingOutputFallbackNonStreamEnabled() {
        return mClient.getBoolean(PREF_STREAMING_OUTPUT_FALLBACK_NON_STREAM, true);
    }

    public void setStreamingOutputFallbackNonStreamEnabled(boolean enabled) {
        mClient.putBoolean(PREF_STREAMING_OUTPUT_FALLBACK_NON_STREAM, enabled);
    }

    public boolean getInvocationCommandsEnabled() {
        return mClient.getBoolean(PREF_INVOCATION_COMMANDS_ENABLED, true);
    }

    public void setInvocationCommandsEnabled(boolean enabled) {
        mClient.putBoolean(PREF_INVOCATION_COMMANDS_ENABLED, enabled);
    }

    public boolean getInvocationTriggersEnabled() {
        return mClient.getBoolean(PREF_INVOCATION_TRIGGERS_ENABLED, true);
    }

    public void setInvocationTriggersEnabled(boolean enabled) {
        mClient.putBoolean(PREF_INVOCATION_TRIGGERS_ENABLED, enabled);
    }

    /**
     * Bulk-disable all invocation triggers while saving a snapshot of each trigger's enabled state.
     * This allows restoring previous states later (master switch ON).
     */
    public void disableAllInvocationTriggersWithBackup() {
        try {
            // IMPORTANT:
            // Do not overwrite the backup snapshot if the master switch is already OFF.
            // Otherwise, repeated "disable" calls can save an "all disabled" snapshot,
            // and later restore will keep everything disabled.
            boolean masterWasEnabled = true;
            try { masterWasEnabled = getInvocationTriggersEnabled(); } catch (Throwable ignored) {}

            List<ParsePattern> ps = getParsePatterns();
            if (ps == null) {
                setInvocationTriggersEnabled(false);
                return;
            }

            // Already disabled: never overwrite the existing snapshot (if any).
            // If there is no snapshot, we still avoid creating an "all disabled" snapshot.
            if (!masterWasEnabled) {
                boolean changed = false;
                for (ParsePattern p : ps) {
                    if (p == null) continue;
                    if (p.isEnabled()) {
                        p.setEnabled(false);
                        changed = true;
                    }
                }
                if (changed) setParsePatterns(ps);
                setInvocationTriggersEnabled(false);
                return;
            }
            // Save snapshot
            JSONArray arr = new JSONArray();
            for (ParsePattern p : ps) {
                if (p == null || p.getType() == null) continue;
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("type", p.getType().name());
                o.put("enabled", p.isEnabled());
                arr.put(o);
            }
            mClient.putString(PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP, arr.toString());
            // Disable all
            for (ParsePattern p : ps) {
                if (p == null) continue;
                p.setEnabled(false);
            }
            setParsePatterns(ps);
            // Keep the UI switch state
            setInvocationTriggersEnabled(false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Restore invocation triggers enabled states from the last saved snapshot (if any).
     * If there is no snapshot, it simply marks the master switch as enabled.
     */
    public void restoreInvocationTriggersFromBackup() {
        try {
            String raw = mClient.getString(PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP, null);
            List<ParsePattern> ps = getParsePatterns();
            if (ps == null) {
                setInvocationTriggersEnabled(true);
                return;
            }
            java.util.HashMap<String, Boolean> map = new java.util.HashMap<>();
            if (raw != null && !raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String type = o.optString("type", null);
                        if (type == null) continue;
                        map.put(type, o.optBoolean("enabled", true));
                    } catch (Throwable ignored) {}
                }
            }

            // Heuristic recovery:
            // If the snapshot exists but ALL saved values are "false", it is very likely the snapshot
            // was overwritten while everything was already disabled (a known bug scenario).
            // Treat this as "no snapshot" so we can recover to a usable state.
            if (!map.isEmpty()) {
                boolean anyTrue = false;
                for (Boolean b : map.values()) {
                    if (b != null && b) { anyTrue = true; break; }
                }
                if (!anyTrue) {
                    map.clear();
                }
            }
            boolean restoredAny = false;
            if (!map.isEmpty()) {
                for (ParsePattern p : ps) {
                    if (p == null || p.getType() == null) continue;
                    Boolean en = map.get(p.getType().name());
                    if (en != null) {
                        p.setEnabled(en);
                        restoredAny = true;
                    }
                }
                if (restoredAny) setParsePatterns(ps);
            }

            // Fallback: if we have no snapshot (or it was empty) and ALL patterns are disabled,
            // re-enable them to avoid leaving the user in a "stuck" state.
            if (!restoredAny) {
                boolean anyEnabled = false;
                for (ParsePattern p : ps) {
                    if (p == null) continue;
                    if (p.isEnabled()) { anyEnabled = true; break; }
                }
                if (!anyEnabled) {
                    for (ParsePattern p : ps) {
                        if (p == null) continue;
                        p.setEnabled(true);
                    }
                    setParsePatterns(ps);
                }
            }
            // Clear snapshot after restore
            mClient.putString(PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP, "");
            setInvocationTriggersEnabled(true);
        } catch (Throwable ignored) {
            try { setInvocationTriggersEnabled(true); } catch (Throwable ignored2) {}
        }
    }


    private void updateGenerativeAICommands() {
        String raw = mClient.getString(PREF_GEN_AI_COMMANDS, "[]");
        generativeAICommands = Collections.unmodifiableList(Commands.decodeCommands(raw));
    }

    @Override
    public Bundle getConfigBundle() {
        Bundle bundle = new Bundle();
        for (LanguageModel model : LanguageModel.values()) {
            Bundle configBundle = new Bundle();
            for (LanguageModelField field : LanguageModelField.values()) {
                configBundle.putString(field.name, getLanguageModelField(model, field));
            }
            bundle.putBundle(model.name(), configBundle);
        }
        return bundle;
    }

    @Override
    public Bundle getOtherSettings() {
        Bundle otherSettings = new Bundle();
        for (OtherSettingsType type : OtherSettingsType.values()) {
            switch (type.nature) {
                case Boolean:
                    otherSettings.putBoolean(type.name(), (Boolean) getOtherSetting(type));
                    break;
                case String:
                    otherSettings.putString(type.name(), (String) getOtherSetting(type));
                    break;
                case Integer:
                    otherSettings.putInt(type.name(), (Integer) getOtherSetting(type));
                    break;
            }
        }
        return otherSettings;
    }

    public void setOtherSetting(OtherSettingsType type, Object value) {
        String key = String.format(PREF_OTHER_SETTING, type.name());
        switch (type.nature) {
            case Boolean:
                mClient.putBoolean(key, (Boolean) value);
                break;
            case String:
                mClient.putString(key, (String) value);
                break;
            case Integer:
                mClient.putInt(key, (Integer) value);
                break;
        }
    }

    public Object getOtherSetting(OtherSettingsType type) {
        String key = String.format(PREF_OTHER_SETTING, type.name());
        switch (type.nature) {
            case Boolean:
                return mClient.getBoolean(key, (Boolean) type.defaultValue);
            case String:
                return mClient.getString(key, (String) type.defaultValue);
            case Integer:
                return mClient.getInt(key, (Integer) type.defaultValue);
            default:
                return type.defaultValue;
        }
    }

    public Boolean getEnableLogs() {
        return (Boolean) getOtherSetting(OtherSettingsType.EnableLogs);
    }

    public Boolean getEnableExternalInternet() {
        return (Boolean) getOtherSetting(OtherSettingsType.EnableExternalInternet);
    }

    public void setSearchEngine(String searchEngine) {
        setOtherSetting(OtherSettingsType.SearchEngine, searchEngine);
    }

    public String getSearchEngine() {
        return (String) getOtherSetting(OtherSettingsType.SearchEngine);
    }

    public String getSearchUrl(String query) {
        String engine = getSearchEngine();
        return buildSearchUrl(engine, query);
    }

    // Update Settings
    public boolean getUpdateCheckEnabled() {
        return (Boolean) getOtherSetting(OtherSettingsType.UpdateCheckEnabled);
    }

    public void setUpdateCheckEnabled(boolean enabled) {
        setOtherSetting(OtherSettingsType.UpdateCheckEnabled, enabled);
    }

    public int getUpdateCheckInterval() {
        return (Integer) getOtherSetting(OtherSettingsType.UpdateCheckInterval);
    }

    public void setUpdateCheckInterval(int hours) {
        setOtherSetting(OtherSettingsType.UpdateCheckInterval, hours);
    }

    public String getUpdateDownloadPath() {
        return (String) getOtherSetting(OtherSettingsType.UpdateDownloadPath);
    }

    // -----------------------------
    // Quick Jump (deep-link templates)
    // -----------------------------
    public String getQuickJumpConfig() {
        return mClient.getString(PREF_QUICK_JUMP_CONFIG, "");
    }

    public void setQuickJumpConfig(String value) {
        if (value == null) value = "";
        mClient.putString(PREF_QUICK_JUMP_CONFIG, value);
    }

    public void setUpdateDownloadPath(String path) {
        setOtherSetting(OtherSettingsType.UpdateDownloadPath, path);
    }

    public static String getSearchUrlFromKGPT(Context context, String query) {
        // IMPORTANT:
        // This method is used from places where SPManager may not be fully initialized
        // (e.g. Xposed / different process). Do NOT hardcode an engine here.
        // Always try to read the latest preference from the shared ConfigProvider.
        String engine = "duckduckgo";
        try {
            if (context != null) {
                // Same key used by setOtherSetting(OtherSettingsType.SearchEngine, ...)
                tn.eluea.kgpt.provider.ConfigClient client = new tn.eluea.kgpt.provider.ConfigClient(context);
                String key = String.format(PREF_OTHER_SETTING, tn.eluea.kgpt.settings.OtherSettingsType.SearchEngine.name());
                String v = client.getString(key, "duckduckgo");
                if (v != null && !v.trim().isEmpty()) engine = v.trim();
            } else if (SPManager.isReady()) {
                String v = SPManager.getInstance().getSearchEngine();
                if (v != null && !v.trim().isEmpty()) engine = v.trim();
            }
        } catch (Throwable ignored) {
            // fall back to default
        }
        return buildSearchUrl(engine, query);
    }

    private static String buildSearchUrl(String engine, String query) {
        String encodedQuery;
        try {
            encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            encodedQuery = query;
        }

        switch (engine) {
            case "google":
                return "https://www.google.com/search?q=" + encodedQuery;
            case "bing":
                return "https://www.bing.com/search?q=" + encodedQuery;
            case "yahoo":
                return "https://search.yahoo.com/search?p=" + encodedQuery;
            case "yandex":
                return "https://yandex.com/search/?text=" + encodedQuery;
            case "brave":
                return "https://search.brave.com/search?q=" + encodedQuery;
            case "ecosia":
                return "https://www.ecosia.org/search?q=" + encodedQuery;
            case "qwant":
                return "https://www.qwant.com/?q=" + encodedQuery;
            case "startpage":
                return "https://www.startpage.com/do/dsearch?query=" + encodedQuery;
            case "perplexity":
                return "https://www.perplexity.ai/?q=" + encodedQuery;
            case "phind":
                return "https://www.phind.com/search?q=" + encodedQuery;
            case "duckduckgo":
            default:
                return "https://duckduckgo.com/?q=" + encodedQuery;
        }
    }

    /**
     * Register a listener for config changes
     */
    public void registerConfigChangeListener(ConfigClient.OnConfigChangeListener listener) {
        mClient.registerGlobalListener(listener);
    }

    /**
     * Get the underlying ConfigClient for advanced usage
     */
    public ConfigClient getConfigClient() {
        return mClient;
    }

    public boolean isAmoledTheme() {
        // Use raw key "amoled_mode" to match SettingsFragment implementation
        return mClient.getBoolean("amoled_mode", false);
    }

    // ===== AI Clipboard Groups =====
    private static final String PREF_AI_CLIPBOARD_GROUPS_JSON = "ai_clipboard.groups.json.v1";
    private static final int AI_CLIPBOARD_MAX_GROUPS = 10;

    /** Returns the user-created clipboard groups (max 10). */
    public List<String> getAiClipboardGroups() {
        String raw = mClient.getString(PREF_AI_CLIPBOARD_GROUPS_JSON, "[]");
        if (raw == null || raw.trim().isEmpty()) raw = "[]";

        ArrayList<String> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (s == null) continue;
                String v = s.trim();
                if (v.isEmpty()) continue;
                String key = v.toLowerCase();
                if (seen.add(key)) {
                    out.add(v);
                    if (out.size() >= AI_CLIPBOARD_MAX_GROUPS) break;
                }
            }
        } catch (JSONException ignored) {}

        return out;
    }

    /** Overwrites the user-created clipboard groups (max 10). */
    public void setAiClipboardGroups(List<String> groups) {
        JSONArray arr = new JSONArray();
        HashSet<String> seen = new HashSet<>();

        if (groups != null) {
            for (String s : groups) {
                if (s == null) continue;
                String v = s.trim();
                if (v.isEmpty()) continue;
                String key = v.toLowerCase();
                if (!seen.add(key)) continue;
                arr.put(v);
                if (arr.length() >= AI_CLIPBOARD_MAX_GROUPS) break;
            }
        }

        mClient.putString(PREF_AI_CLIPBOARD_GROUPS_JSON, arr.toString());
    }



    // =============================
    // Conversation memory & normal model thinking (Labs)
    // =============================
    private static final String PREF_CONVERSATION_MEMORY_LEVEL = "conversation_memory_level_v1";

    // Legacy key kept only for one-time migration.
    private static final String PREF_THINKING_DEPTH_LEVEL_LEGACY = "thinking_depth_level_v1";

    private static final String PREF_NORMAL_MODEL_THINKING = "normal_model_thinking_v1";
    private static final float NORMAL_MODEL_THINKING_DEFAULT = 0.7f;

    // Reasoning model thinking (推理模型思考)
    private static final String PREF_REASONING_MODEL_THINKING = "reasoning_model_thinking_v1";
    public static final int REASONING_MODEL_THINKING_DIVERGENT = 0;
    public static final int REASONING_MODEL_THINKING_CONVERGENT = 1;
    public static final int REASONING_MODEL_THINKING_AUTO = 2; // default
    public static final int REASONING_MODEL_THINKING_LOW = 3;
    public static final int REASONING_MODEL_THINKING_MEDIUM = 4;
    public static final int REASONING_MODEL_THINKING_HIGH = 5;

    // =============================
    // Per-subModel capability cache (auto-learned)
    // =============================
    // Tri-state: key absent = unknown, 0 = false, 1 = true
    private static final String PREF_MODEL_CAP_TEMP = "model_cap.temp.%s.%s";      // provider, subModelKey
    private static final String PREF_MODEL_CAP_REASON = "model_cap.reason.%s.%s";  // provider, subModelKey
    // Integer cache: key absent = unknown, value > 0 = safe max output tokens to use (auto-learned)
    private static final String PREF_MODEL_CAP_MAXTOK = "model_cap.maxtok.%s.%s";  // provider, subModelKey
    private static final String PREF_MODEL_CAP_MAXTOK_SRC = "model_cap.maxtok.src.%s.%s";  // source: auto_retry/manual_*
    private static final String PREF_MODEL_CAP_MAXTOK_AT = "model_cap.maxtok.at.%s.%s";   // learned timestamp ms (string)
    private static final String PREF_MODEL_CAP_MAXTOK_ERR = "model_cap.maxtok.err.%s.%s";  // last probe error summary
    private static final String PREF_MODEL_CAP_MAXTOK_ERR_RAW = "model_cap.maxtok.errraw.%s.%s";  // last raw error detail
    private static final String PREF_MODEL_CAP_MAXTOK_LB = "model_cap.maxtok.lb.%s.%s";  // known safe lower-bound (not exact cap)

    // Provider/API compliance constraint (NOT "learning"): hard completion-token cap inferred from API errors.
    // Example: OpenRouter may return "supports at most 32768 completion tokens".
    private static final String PREF_MODEL_CAP_MAXTOK_HARD = "model_cap.maxtok.hard.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_HARD_SRC = "model_cap.maxtok.hard.src.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_HARD_AT = "model_cap.maxtok.hard.at.%s.%s";

    // Hard-cap management: global index + last hit metadata
    private static final String PREF_MODEL_CAP_MAXTOK_HARD_INDEX = "model_cap.maxtok.hard.index_v1";
    private static final String PREF_MODEL_CAP_MAXTOK_HARD_LAST_REASON = "model_cap.maxtok.hard.last_reason.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_HARD_LAST_HIT_AT = "model_cap.maxtok.hard.last_hit_at.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_HARD_HIT_COUNT = "model_cap.maxtok.hard.hit_count.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_HARD_TOAST_ENABLED = "model_cap.maxtok.hard.toast_enabled_v1";


    // Output-length selection source (global UI mode)
    private static final String PREF_MAX_TOKENS_SELECTION_SOURCE = "max_tokens_selection_source_v1";
    public static final int MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED = 0;
    public static final int MAX_TOKENS_SELECTION_SOURCE_USER_FIXED = 1;

    // Lightweight one-shot notice when a weaker auto observation is ignored by stronger manual result
    private static final String PREF_OUTPUT_CAP_CONFLICT_NOTICE = "output_cap_conflict_notice_v1";
    // ===== Output-cap auto-learning strategy (global + per-model override) =====
    private static final String PREF_OUTPUT_CAP_AUTO_LEARN_MODE = "output_cap.auto_learn.mode_v1";
    public static final int OUTPUT_CAP_AUTO_LEARN_OFF = 0;
    public static final int OUTPUT_CAP_AUTO_LEARN_FAIL_ONLY = 1;   // default
    public static final int OUTPUT_CAP_AUTO_LEARN_SUCCESS_AND_FAIL = 2;

    private static final String PREF_OUTPUT_CAP_AUTO_SYNC_PRESET = "output_cap.auto_sync_preset_v1";
    private static final String PREF_OUTPUT_CAP_AUTO_LEARN_CHAT_ONLY = "output_cap.auto_learn.chat_only_v1";
    private static final String PREF_OUTPUT_CAP_MANUAL_PROTECT_DEFAULT = "output_cap.manual_protect.default_v1";
    // tri-state per model: -1/absent=inherit, 0=off, 1=on
    private static final String PREF_OUTPUT_CAP_MANUAL_PROTECT_OVERRIDE = "output_cap.manual_protect.override.%s.%s";

    // Auto observations (record-only, does NOT override protected manual result)
    private static final String PREF_MODEL_CAP_MAXTOK_AUTOOBS = "model_cap.maxtok.auto_obs.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_AUTOOBS_LB = "model_cap.maxtok.auto_obs.lb.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_AUTOOBS_SRC = "model_cap.maxtok.auto_obs.src.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_AUTOOBS_AT = "model_cap.maxtok.auto_obs.at.%s.%s";

    // ===== Last max_tokens decision snapshot (for Why panel) =====
    // Stores the most recent effective max_tokens decision for a provider+subModel.
    // This is used by UI to explain why a request used a certain max_tokens.
    private static final String PREF_MODEL_CAP_MAXTOK_LAST_REQID = "model_cap.maxtok.last.reqid.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_LAST_REQ = "model_cap.maxtok.last.req.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_LAST_EFF = "model_cap.maxtok.last.eff.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_LAST_REASON = "model_cap.maxtok.last.reason.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_LAST_LEARNED = "model_cap.maxtok.last.learned.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_LAST_HARD = "model_cap.maxtok.last.hard.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_LAST_SYNCED = "model_cap.maxtok.last.synced.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_LAST_SYNCED_TO = "model_cap.maxtok.last.synced_to.%s.%s";
    private static final String PREF_MODEL_CAP_MAXTOK_LAST_AT = "model_cap.maxtok.last.at.%s.%s";

    // Sort helper: provider+subModel last-used timestamp (updated when request succeeds)
    private static final String PREF_SUBMODEL_LAST_USED_AT = "submodel.last_used_at.%s.%s";

    private static String sanitizeSubModelKey(String subModel) {
        if (subModel == null) return "";
        String s = subModel.trim();
        if (s.isEmpty()) return "";
        // SharedPreferences/Provider keys are strings, but keep it conservative for DB/provider backends.
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String capKey(String fmt, LanguageModel provider, String subModel) {
        String p = (provider == null ? "" : provider.name());
        String m = sanitizeSubModelKey(subModel);
        return String.format(fmt, p, m);
    }

    /** Snapshot of the most recent max_tokens decision (for Why panel). */
    public static final class MaxTokensDecisionSnapshot {
        public int requestId;
        public int requested;
        public int effective;
        @Nullable public Integer learned;
        @Nullable public Integer hard;
        @Nullable public String reason;
        public boolean synced;
        public int syncedTo;
        public long atMs;
    }

    /**
     * Record the most recent effective max_tokens decision for a provider+subModel.
     *
     * @param requestId A per-request id. When retries happen within the same request, callers should pass the
     *                  same id so we can preserve "synced" status across retry restarts.
     */
    public void recordLastMaxTokensDecision(LanguageModel provider,
                                           String subModel,
                                           int requestId,
                                           int requested,
                                           int effective,
                                           @Nullable Integer learned,
                                           @Nullable Integer hard,
                                           @Nullable String reason,
                                           boolean synced,
                                           int syncedTo) {
        try {
            if (provider == null || TextUtils.isEmpty(subModel)) return;
            String m = subModel;

            // Preserve an earlier "synced" marker for the same requestId (retry restart should not reset it).
            boolean finalSynced = synced;
            int finalSyncedTo = syncedTo;
            try {
                MaxTokensDecisionSnapshot old = getLastMaxTokensDecision(provider, m);
                if (old != null && old.requestId == requestId && old.synced) {
                    if (!finalSynced) {
                        finalSynced = true;
                        finalSyncedTo = (old.syncedTo > 0 ? old.syncedTo : finalSyncedTo);
                    }
                }
            } catch (Throwable ignored) {}

            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_REQID, provider, m), requestId); } catch (Throwable ignored) {}
            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_REQ, provider, m), Math.max(0, requested)); } catch (Throwable ignored) {}
            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_EFF, provider, m), Math.max(0, effective)); } catch (Throwable ignored) {}
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_LAST_REASON, provider, m), reason == null ? "" : reason); } catch (Throwable ignored) {}
            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_LEARNED, provider, m), learned == null ? 0 : Math.max(0, learned)); } catch (Throwable ignored) {}
            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_HARD, provider, m), hard == null ? 0 : Math.max(0, hard)); } catch (Throwable ignored) {}
            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_SYNCED, provider, m), finalSynced ? 1 : 0); } catch (Throwable ignored) {}
            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_SYNCED_TO, provider, m), finalSynced ? Math.max(0, finalSyncedTo) : 0); } catch (Throwable ignored) {}
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_LAST_AT, provider, m), String.valueOf(System.currentTimeMillis())); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    @Nullable
    public MaxTokensDecisionSnapshot getLastMaxTokensDecision(LanguageModel provider, String subModel) {
        try {
            if (provider == null || TextUtils.isEmpty(subModel)) return null;
            String keyReq = capKey(PREF_MODEL_CAP_MAXTOK_LAST_REQ, provider, subModel);
            String keyEff = capKey(PREF_MODEL_CAP_MAXTOK_LAST_EFF, provider, subModel);
            if (!mClient.contains(keyReq) && !mClient.contains(keyEff)) return null;

            MaxTokensDecisionSnapshot s = new MaxTokensDecisionSnapshot();
            try { s.requestId = mClient.getInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_REQID, provider, subModel), 0); } catch (Throwable ignored) {}
            try { s.requested = mClient.getInt(keyReq, 0); } catch (Throwable ignored) {}
            try { s.effective = mClient.getInt(keyEff, 0); } catch (Throwable ignored) {}

            try {
                int l = mClient.getInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_LEARNED, provider, subModel), 0);
                s.learned = l > 0 ? l : null;
            } catch (Throwable ignored) { s.learned = null; }
            try {
                int h = mClient.getInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_HARD, provider, subModel), 0);
                s.hard = h > 0 ? h : null;
            } catch (Throwable ignored) { s.hard = null; }

            try {
                String r = mClient.getString(capKey(PREF_MODEL_CAP_MAXTOK_LAST_REASON, provider, subModel), null);
                if (r != null) r = r.trim();
                s.reason = (r == null || r.isEmpty()) ? null : r;
            } catch (Throwable ignored) { s.reason = null; }

            try {
                int v = mClient.getInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_SYNCED, provider, subModel), 0);
                s.synced = (v == 1);
            } catch (Throwable ignored) { s.synced = false; }
            try {
                s.syncedTo = mClient.getInt(capKey(PREF_MODEL_CAP_MAXTOK_LAST_SYNCED_TO, provider, subModel), 0);
            } catch (Throwable ignored) { s.syncedTo = 0; }

            try {
                String at = mClient.getString(capKey(PREF_MODEL_CAP_MAXTOK_LAST_AT, provider, subModel), null);
                if (at != null) at = at.trim();
                s.atMs = (at == null || at.isEmpty()) ? 0L : Long.parseLong(at);
            } catch (Throwable ignored) { s.atMs = 0L; }
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ===== Output-cap auto-learning strategy =====
    public int getOutputCapAutoLearnMode() {
        int v = OUTPUT_CAP_AUTO_LEARN_FAIL_ONLY;
        try { v = mClient.getInt(PREF_OUTPUT_CAP_AUTO_LEARN_MODE, v); }
        catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_OUTPUT_CAP_AUTO_LEARN_MODE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < OUTPUT_CAP_AUTO_LEARN_OFF || v > OUTPUT_CAP_AUTO_LEARN_SUCCESS_AND_FAIL) {
            v = OUTPUT_CAP_AUTO_LEARN_FAIL_ONLY;
        }
        return v;
    }

    public void setOutputCapAutoLearnMode(int mode) {
        int v = mode;
        if (v < OUTPUT_CAP_AUTO_LEARN_OFF || v > OUTPUT_CAP_AUTO_LEARN_SUCCESS_AND_FAIL) {
            v = OUTPUT_CAP_AUTO_LEARN_FAIL_ONLY;
        }
        try { mClient.putInt(PREF_OUTPUT_CAP_AUTO_LEARN_MODE, v); }
        catch (Throwable t) { try { mClient.putString(PREF_OUTPUT_CAP_AUTO_LEARN_MODE, String.valueOf(v)); } catch (Throwable ignored) {} }
    }

    public boolean getOutputCapAutoSyncPresetEnabled() {
        try { return mClient.getBoolean(PREF_OUTPUT_CAP_AUTO_SYNC_PRESET, true); } catch (Throwable ignored) { return true; }
    }

    public void setOutputCapAutoSyncPresetEnabled(boolean enabled) {
        try { mClient.putBoolean(PREF_OUTPUT_CAP_AUTO_SYNC_PRESET, enabled); } catch (Throwable ignored) {}
    }

    public boolean getOutputCapAutoLearnChatOnlyEnabled() {
        try { return mClient.getBoolean(PREF_OUTPUT_CAP_AUTO_LEARN_CHAT_ONLY, true); } catch (Throwable ignored) { return true; }
    }

    public void setOutputCapAutoLearnChatOnlyEnabled(boolean enabled) {
        try { mClient.putBoolean(PREF_OUTPUT_CAP_AUTO_LEARN_CHAT_ONLY, enabled); } catch (Throwable ignored) {}
    }

    public boolean getOutputCapManualProtectDefaultEnabled() {
        try { return mClient.getBoolean(PREF_OUTPUT_CAP_MANUAL_PROTECT_DEFAULT, true); } catch (Throwable ignored) { return true; }
    }

    public void setOutputCapManualProtectDefaultEnabled(boolean enabled) {
        try { mClient.putBoolean(PREF_OUTPUT_CAP_MANUAL_PROTECT_DEFAULT, enabled); } catch (Throwable ignored) {}
    }

    @Nullable
    public Boolean getOutputCapManualProtectOverride(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_OUTPUT_CAP_MANUAL_PROTECT_OVERRIDE, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, -1);
            if (v == 1) return Boolean.TRUE;
            if (v == 0) return Boolean.FALSE;
        } catch (Throwable ignored) {
            try {
                String key = capKey(PREF_OUTPUT_CAP_MANUAL_PROTECT_OVERRIDE, provider, subModel);
                String s = mClient.getString(key, null);
                if (s == null || s.trim().isEmpty()) return null;
                int v = Integer.parseInt(s.trim());
                if (v == 1) return Boolean.TRUE;
                if (v == 0) return Boolean.FALSE;
            } catch (Throwable ignored2) {}
        }
        return null;
    }

    public void setOutputCapManualProtectOverride(LanguageModel provider, String subModel, @Nullable Boolean enabled) {
        try {
            String key = capKey(PREF_OUTPUT_CAP_MANUAL_PROTECT_OVERRIDE, provider, subModel);
            if (enabled == null) {
                try { mClient.putInt(key, -1); } catch (Throwable ignored) {}
                try { mClient.putString(key, ""); } catch (Throwable ignored) {}
                return;
            }
            int v = enabled ? 1 : 0;
            try { mClient.putInt(key, v); }
            catch (Throwable t) { try { mClient.putString(key, String.valueOf(v)); } catch (Throwable ignored) {} }
        } catch (Throwable ignored) {}
    }

    public boolean isOutputCapManualProtectEnabled(LanguageModel provider, String subModel) {
        try {
            Boolean o = getOutputCapManualProtectOverride(provider, subModel);
            return o != null ? o : getOutputCapManualProtectDefaultEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public boolean hasManualOutputCapResult(LanguageModel provider, String subModel) {
        try {
            String src = getCachedSafeMaxTokensSource(provider, subModel);
            if (TextUtils.isEmpty(src)) return false;
            return src.toLowerCase(java.util.Locale.US).contains("manual");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean shouldAutoLearnOutputCap(LanguageModel provider, String subModel, boolean successPath) {
        int mode = getOutputCapAutoLearnMode();
        if (mode == OUTPUT_CAP_AUTO_LEARN_OFF) return false;
        if (successPath && mode != OUTPUT_CAP_AUTO_LEARN_SUCCESS_AND_FAIL) return false;
        if (getOutputCapAutoLearnChatOnlyEnabled()) {
            try {
                if (!tn.eluea.kgpt.llm.ModelCapabilities.isChatCapabilityTestable(provider, subModel)) return false;
            } catch (Throwable ignored) {}
        }
        return true;
    }

    public boolean shouldAutoSyncOutputLengthAfterAutoLearn(LanguageModel provider, String subModel) {
        if (!getOutputCapAutoSyncPresetEnabled()) return false;
        if (getOutputCapAutoLearnChatOnlyEnabled()) {
            try {
                if (!tn.eluea.kgpt.llm.ModelCapabilities.isChatCapabilityTestable(provider, subModel)) return false;
            } catch (Throwable ignored) {}
        }
        return true;
    }

    // Record-only auto observation (used when manual-result protection is ON)
    public void recordAutoObservedSafeMaxTokens(LanguageModel provider, String subModel, int safeMaxTokens, @Nullable String source) {
        if (safeMaxTokens <= 0) return;
        try {
            Integer old = null;
            try {
                String key = capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS, provider, subModel);
                if (mClient.contains(key)) old = mClient.getInt(key, 0);
            } catch (Throwable ignored) {}
            int v = (old != null && old > 0) ? Math.max(old, safeMaxTokens) : safeMaxTokens;
            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS, provider, subModel), v); } catch (Throwable ignored) {}
            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_LB, provider, subModel), v); } catch (Throwable ignored) {}
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_SRC, provider, subModel), source == null ? "auto_observation" : source); } catch (Throwable ignored) {}
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_AT, provider, subModel), String.valueOf(System.currentTimeMillis())); } catch (Throwable ignored) {}
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    public void recordAutoObservedSafeMaxTokensLowerBound(LanguageModel provider, String subModel, int safeLowerBound, @Nullable String source) {
        if (safeLowerBound <= 0) return;
        try {
            Integer old = null;
            try {
                String key = capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_LB, provider, subModel);
                if (mClient.contains(key)) old = mClient.getInt(key, 0);
            } catch (Throwable ignored) {}
            int v = (old != null && old > 0) ? Math.max(old, safeLowerBound) : safeLowerBound;
            try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_LB, provider, subModel), v); } catch (Throwable ignored) {}
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_SRC, provider, subModel), source == null ? "auto_observation_lower" : source); } catch (Throwable ignored) {}
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_AT, provider, subModel), String.valueOf(System.currentTimeMillis())); } catch (Throwable ignored) {}
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    @Nullable
    public Integer getAutoObservedSafeMaxTokens(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);
            return v > 0 ? v : null;
        } catch (Throwable ignored) { return null; }
    }

    @Nullable
    public Integer getAutoObservedSafeMaxTokensLowerBound(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_LB, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);
            return v > 0 ? v : null;
        } catch (Throwable ignored) { return null; }
    }

    @Nullable
    public String getAutoObservedSafeMaxTokensSource(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_SRC, provider, subModel);
            String s = mClient.getString(key, null);
            if (s == null) return null;
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable ignored) { return null; }
    }

    public long getAutoObservedSafeMaxTokensUpdatedAt(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_AT, provider, subModel);
            String s = mClient.getString(key, null);
            if (s == null || s.trim().isEmpty()) return 0L;
            return Long.parseLong(s.trim());
        } catch (Throwable ignored) { return 0L; }
    }

    public void clearAutoObservedSafeMaxTokens(LanguageModel provider, String subModel) {
        try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS, provider, subModel), 0); } catch (Throwable ignored) {}
        try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_LB, provider, subModel), 0); } catch (Throwable ignored) {}
        try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_SRC, provider, subModel), ""); } catch (Throwable ignored) {}
        try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_AUTOOBS_AT, provider, subModel), ""); } catch (Throwable ignored) {}
        try { notifyUiCapabilityCacheChanged(); } catch (Throwable ignored) {}
    }

    public void markSubModelLastUsed(LanguageModel provider, String subModel) {
        try {
            if (provider == null || TextUtils.isEmpty(subModel)) return;
            String key = capKey(PREF_SUBMODEL_LAST_USED_AT, provider, subModel);
            long now = System.currentTimeMillis();
            try { mClient.putString(key, String.valueOf(now)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public long getSubModelLastUsedAt(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_SUBMODEL_LAST_USED_AT, provider, subModel);
            try {
                String s = mClient.getString(key, null);
                if (s != null && !s.trim().isEmpty()) return Long.parseLong(s.trim());
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return 0L;
    }


private int getOutputCapSourcePriority(@Nullable String source) {
    String s = source == null ? "" : source.toLowerCase(java.util.Locale.US);
    if (s.contains("manual") && s.contains("precise")) return 400;
    if (s.contains("manual") && (s.contains("conservative") || s.contains("_lower"))) return 300;
    if ((s.contains("auto") || s.contains("retry")) && !s.contains("lower")) return 200;
    if (s.contains("auto") || s.contains("retry") || s.contains("lower")) return 100;
    return 0;
}

private void saveOutputCapConflictNotice(@Nullable String msg) {
    try {
        if (TextUtils.isEmpty(msg)) return;
        mClient.putString(PREF_OUTPUT_CAP_CONFLICT_NOTICE, msg);
    } catch (Throwable ignored) {}
}

@Nullable
public String consumeOutputCapConflictNotice() {
    try {
        String msg = mClient.getString(PREF_OUTPUT_CAP_CONFLICT_NOTICE, null);
        if (!TextUtils.isEmpty(msg)) mClient.putString(PREF_OUTPUT_CAP_CONFLICT_NOTICE, "");
        return TextUtils.isEmpty(msg) ? null : msg;
    } catch (Throwable ignored) {
        return null;
    }
}

    /**
     * Returns cached support for temperature/sampling parameters.
     * @return Boolean.TRUE/Boolean.FALSE if known, null if unknown.
     */
    public Boolean getCachedSupportsTemperature(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_TEMP, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);
            if (v < 0) return null;
            return v == 1;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setCachedSupportsTemperature(LanguageModel provider, String subModel, boolean supported) {
        try {
            String key = capKey(PREF_MODEL_CAP_TEMP, provider, subModel);
            mClient.putInt(key, supported ? 1 : 0);
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    /**
     * Returns cached support for reasoning-thinking controls.
     * @return Boolean.TRUE/Boolean.FALSE if known, null if unknown.
     */
    public Boolean getCachedSupportsReasoningThinking(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_REASON, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);
            if (v < 0) return null;
            return v == 1;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setCachedSupportsReasoningThinking(LanguageModel provider, String subModel, boolean supported) {
        try {
            String key = capKey(PREF_MODEL_CAP_REASON, provider, subModel);
            mClient.putInt(key, supported ? 1 : 0);
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    /**
     * Returns cached safe max output tokens for the given model.
     * @return Integer (>=1) if known, null if unknown.
     */
    public Integer getCachedSafeMaxTokens(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);

            // Guard: older builds could accidentally cache HTTP status codes (400/422/429/5xx) as token limits
            // because error messages often contain those numbers.
            if (v == 400 || v == 401 || v == 403 || v == 404 || v == 408
                    || v == 413 || v == 414 || v == 422 || v == 429
                    || v == 500 || v == 502 || v == 503 || v == 504) {
                try { mClient.putInt(key, 0); } catch (Throwable ignored2) {}
                return null;
            }

            return v > 0 ? v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Provider/API compliance constraint: hard completion-token cap inferred from API errors.
     * This is NOT treated as "learning"; it is used to avoid repeated invalid requests.
     */
    public Integer getCachedHardMaxTokens(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_HARD, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);
            return v > 0 ? v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public String getCachedHardMaxTokensSource(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_HARD_SRC, provider, subModel);
            String v = mClient.getString(key, null);
            if (v == null) return null;
            v = v.trim();
            return v.isEmpty() ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public long getCachedHardMaxTokensUpdatedAt(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_HARD_AT, provider, subModel);
            String v = mClient.getString(key, null);
            if (v == null) return 0L;
            v = v.trim();
            if (v.isEmpty()) return 0L;
            return Long.parseLong(v);
        } catch (Throwable ignored) {
            return 0L;
        }
    }


    /** Whether to show a toast when a request is clamped by a cached hard cap. Default: enabled. */
    public boolean getHardCapToastEnabled() {
        try {
            return mClient.getBoolean(PREF_MODEL_CAP_MAXTOK_HARD_TOAST_ENABLED, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public void setHardCapToastEnabled(boolean enabled) {
        try {
            mClient.putBoolean(PREF_MODEL_CAP_MAXTOK_HARD_TOAST_ENABLED, enabled);
        } catch (Throwable ignored) {}
    }

    /** Metadata for hard-cap management UI. */
    public static final class HardCapEntry {
        @Nullable public LanguageModel provider;
        @Nullable public String subModel;
        @Nullable public Integer hardCap;
        @Nullable public String source;
        public long updatedAtMs;
        @Nullable public String lastReason;
        public long lastHitAtMs;
        public int hitCount;
    }

    private static String encodeIndexPart(String s) {
        try {
            if (s == null) return "";
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable ignored) {
            return s == null ? "" : s;
        }
    }

    private static String decodeIndexPart(String s) {
        try {
            if (s == null) return "";
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable ignored) {
            return s == null ? "" : s;
        }
    }

    private String hardCapIndexEntry(LanguageModel provider, String subModel) {
        String p = (provider == null ? "" : provider.name());
        String m = (subModel == null ? "" : subModel);
        return p + "|" + encodeIndexPart(m);
    }

    private java.util.LinkedHashSet<String> readHardCapIndex() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        try {
            String raw = mClient.getString(PREF_MODEL_CAP_MAXTOK_HARD_INDEX, "");
            if (raw == null) raw = "";
            raw = raw.trim();
            if (raw.isEmpty()) return set;
            String[] parts = raw.split("\n");
            for (String it : parts) {
                if (it == null) continue;
                String v = it.trim();
                if (!v.isEmpty()) set.add(v);
            }
        } catch (Throwable ignored) {}
        return set;
    }

    private void writeHardCapIndex(java.util.Set<String> set) {
        try {
            if (set == null || set.isEmpty()) {
                mClient.putString(PREF_MODEL_CAP_MAXTOK_HARD_INDEX, "");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String it : set) {
                if (it == null) continue;
                String v = it.trim();
                if (v.isEmpty()) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append(v);
            }
            mClient.putString(PREF_MODEL_CAP_MAXTOK_HARD_INDEX, sb.toString());
        } catch (Throwable ignored) {}
    }

    private void addToHardCapIndex(LanguageModel provider, String subModel) {
        try {
            java.util.LinkedHashSet<String> set = readHardCapIndex();
            set.add(hardCapIndexEntry(provider, subModel));
            writeHardCapIndex(set);
        } catch (Throwable ignored) {}
    }

    private void removeFromHardCapIndex(LanguageModel provider, String subModel) {
        try {
            java.util.LinkedHashSet<String> set = readHardCapIndex();
            set.remove(hardCapIndexEntry(provider, subModel));
            writeHardCapIndex(set);
        } catch (Throwable ignored) {}
    }

    @Nullable
    public String getCachedHardMaxTokensLastReason(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_HARD_LAST_REASON, provider, subModel);
            String v = mClient.getString(key, null);
            if (v == null) return null;
            v = v.trim();
            return v.isEmpty() ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public long getCachedHardMaxTokensLastHitAt(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_HARD_LAST_HIT_AT, provider, subModel);
            String v = mClient.getString(key, null);
            if (v == null) return 0L;
            v = v.trim();
            if (v.isEmpty()) return 0L;
            return Long.parseLong(v);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public int getCachedHardMaxTokensHitCount(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_HARD_HIT_COUNT, provider, subModel);
            if (!mClient.contains(key)) return 0;
            int v = mClient.getInt(key, 0);
            return Math.max(0, v);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Record a hard-cap hit event (used for management UI: last reason / last hit time / count). */
    public void recordHardCapLastHit(LanguageModel provider, String subModel, @Nullable String reason) {
        try {
            if (provider == null || TextUtils.isEmpty(subModel)) return;
            String r = reason == null ? "" : reason.trim();
            if (r.length() > 500) r = r.substring(0, 500);
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_HARD_LAST_REASON, provider, subModel), r); } catch (Throwable ignored) {}
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_HARD_LAST_HIT_AT, provider, subModel), String.valueOf(System.currentTimeMillis())); } catch (Throwable ignored) {}
            try {
                String k = capKey(PREF_MODEL_CAP_MAXTOK_HARD_HIT_COUNT, provider, subModel);
                int old = 0;
                try { old = mClient.getInt(k, 0); } catch (Throwable ignored2) {}
                int next = old + 1;
                if (next < 0) next = Integer.MAX_VALUE;
                mClient.putInt(k, next);
            } catch (Throwable ignored) {}
            addToHardCapIndex(provider, subModel);
        } catch (Throwable ignored) {}
    }

    /** Enumerate all hard-cap entries for management UI. */
    public java.util.ArrayList<HardCapEntry> getAllCachedHardMaxTokensEntries() {
        java.util.ArrayList<HardCapEntry> out = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> idx = readHardCapIndex();
        if (idx.isEmpty()) return out;

        java.util.ArrayList<String> toRemove = new java.util.ArrayList<>();
        for (String entry : idx) {
            if (entry == null) continue;
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int bar = e.indexOf('|');
            if (bar <= 0) { toRemove.add(e); continue; }
            String pName = e.substring(0, bar);
            String mEnc = e.substring(bar + 1);
            LanguageModel p = null;
            try { p = LanguageModel.valueOf(pName); } catch (Throwable ignored) { p = null; }
            String m = decodeIndexPart(mEnc);
            if (p == null || TextUtils.isEmpty(m)) { toRemove.add(e); continue; }
            Integer cap = getCachedHardMaxTokens(p, m);
            if (cap == null || cap <= 0) { toRemove.add(e); continue; }

            HardCapEntry it = new HardCapEntry();
            it.provider = p;
            it.subModel = m;
            it.hardCap = cap;
            it.source = getCachedHardMaxTokensSource(p, m);
            it.updatedAtMs = getCachedHardMaxTokensUpdatedAt(p, m);
            it.lastReason = getCachedHardMaxTokensLastReason(p, m);
            it.lastHitAtMs = getCachedHardMaxTokensLastHitAt(p, m);
            it.hitCount = getCachedHardMaxTokensHitCount(p, m);
            out.add(it);
        }

        if (!toRemove.isEmpty()) {
            try {
                java.util.LinkedHashSet<String> newSet = new java.util.LinkedHashSet<>(idx);
                for (String r : toRemove) newSet.remove(r);
                writeHardCapIndex(newSet);
            } catch (Throwable ignored) {}
        }

        try {
            java.util.Collections.sort(out, (a, b) -> {
                long ta = a.lastHitAtMs > 0 ? a.lastHitAtMs : a.updatedAtMs;
                long tb = b.lastHitAtMs > 0 ? b.lastHitAtMs : b.updatedAtMs;
                if (ta == tb) return 0;
                return (ta < tb) ? 1 : -1;
            });
        } catch (Throwable ignored) {}
        return out;
    }

    /** Clear all cached hard-cap entries (does NOT clear learned caps). */
    public void clearAllCachedHardMaxTokens() {
        try {
            java.util.LinkedHashSet<String> idx = readHardCapIndex();
            if (idx.isEmpty()) {
                mClient.putString(PREF_MODEL_CAP_MAXTOK_HARD_INDEX, "");
                notifyUiCapabilityCacheChanged();
                return;
            }
            for (String entry : idx) {
                if (entry == null) continue;
                String e = entry.trim();
                if (e.isEmpty()) continue;
                int bar = e.indexOf('|');
                if (bar <= 0) continue;
                String pName = e.substring(0, bar);
                String mEnc = e.substring(bar + 1);
                LanguageModel p = null;
                try { p = LanguageModel.valueOf(pName); } catch (Throwable ignored) { p = null; }
                String m = decodeIndexPart(mEnc);
                if (p == null || TextUtils.isEmpty(m)) continue;
                clearCachedHardMaxTokens(p, m);
            }
            mClient.putString(PREF_MODEL_CAP_MAXTOK_HARD_INDEX, "");
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    
    public void recordCachedHardMaxTokens(LanguageModel provider, String subModel, int hardCap, @Nullable String source) {
        recordCachedHardMaxTokens(provider, subModel, hardCap, source, null);
    }

    /**
     * Provider/API compliance constraint: hard completion-token cap inferred from API errors.
     * This is NOT treated as "learning"; it is used to avoid repeated invalid requests.
     *
     * @param reason Optional: last trigger reason summary (stored for management UI).
     */
    public void recordCachedHardMaxTokens(LanguageModel provider, String subModel, int hardCap, @Nullable String source, @Nullable String reason) {
        try {
            if (provider == null || TextUtils.isEmpty(subModel)) return;
            int v = hardCap;
            if (v <= 0) return;
            // Same guard as exact cap: avoid common HTTP codes being accidentally parsed.
            if (v == 400 || v == 401 || v == 403 || v == 404 || v == 408
                    || v == 413 || v == 414 || v == 422 || v == 429
                    || v == 500 || v == 502 || v == 503 || v == 504) {
                return;
            }
            String key = capKey(PREF_MODEL_CAP_MAXTOK_HARD, provider, subModel);
            Integer old = null;
            try { old = getCachedHardMaxTokens(provider, subModel); } catch (Throwable ignored) {}
            // Keep the smaller (stricter) hard cap.
            int toStore = (old != null && old > 0) ? Math.min(old, v) : v;
            try { mClient.putInt(key, toStore); } catch (Throwable ignored) {}
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_HARD_SRC, provider, subModel), source == null ? "api_constraint" : source); } catch (Throwable ignored) {}
            try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_HARD_AT, provider, subModel), String.valueOf(System.currentTimeMillis())); } catch (Throwable ignored) {}

            // Update management metadata (reason/time/count + index)
            try {
                if (reason != null && !reason.trim().isEmpty()) {
                    recordHardCapLastHit(provider, subModel, reason);
                } else {
                    addToHardCapIndex(provider, subModel);
                }
            } catch (Throwable ignored) {}

            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    public void clearCachedHardMaxTokens(LanguageModel provider, String subModel) {
        try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_HARD, provider, subModel), 0); } catch (Throwable ignored) {}
        try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_HARD_SRC, provider, subModel), ""); } catch (Throwable ignored) {}
        try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_HARD_AT, provider, subModel), ""); } catch (Throwable ignored) {}
        // Management metadata
        try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_HARD_LAST_REASON, provider, subModel), ""); } catch (Throwable ignored) {}
        try { mClient.putString(capKey(PREF_MODEL_CAP_MAXTOK_HARD_LAST_HIT_AT, provider, subModel), ""); } catch (Throwable ignored) {}
        try { mClient.putInt(capKey(PREF_MODEL_CAP_MAXTOK_HARD_HIT_COUNT, provider, subModel), 0); } catch (Throwable ignored) {}
        try { removeFromHardCapIndex(provider, subModel); } catch (Throwable ignored) {}
        try { notifyUiCapabilityCacheChanged(); } catch (Throwable ignored) {}
    }


    /**
     * Cache a safe max output tokens value for the given model.
     * Any value <= 0 clears the cache entry.
     */
    public void setCachedSafeMaxTokens(LanguageModel provider, String subModel, int safeMaxTokens) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK, provider, subModel);
            int v = safeMaxTokens;
            // ConfigClient has no remove(); store 0 to represent "unknown/cleared".
            if (v <= 0) v = 0;
            mClient.putInt(key, v);
            try {
                tn.eluea.kgpt.util.Logger.log("OUTLEN_CACHE", "set cap provider=" + (provider == null ? "null" : provider.name())
                        + ", subModel=" + String.valueOf(subModel)
                        + ", cap=" + v + ", key=" + key);
            } catch (Throwable ignoredLog) {}
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

        /** Clears all output-cap related caches (exact / lower-bound / metadata / errors) for all models. */
    public void clearAllCachedSafeMaxTokens() {
        try {
            android.content.Context ctx = null;
            try { ctx = mClient.getContext(); } catch (Throwable ignored) {}
            if (ctx == null) return;
            android.database.Cursor c = null;
            java.util.ArrayList<String> keys = new java.util.ArrayList<>();
            try {
                c = ctx.getContentResolver().query(
                        tn.eluea.kgpt.provider.ConfigProvider.CONTENT_URI,
                        null, null, null, null);
                if (c != null) {
                    int keyIdx = -1;
                    try { keyIdx = c.getColumnIndexOrThrow(tn.eluea.kgpt.provider.ConfigProvider.COLUMN_KEY); }
                    catch (Throwable ignored) { keyIdx = c.getColumnIndex("key"); }
                    while (c.moveToNext()) {
                        String key = null;
                        try { key = c.getString(keyIdx); } catch (Throwable ignored) {}
                        if (key == null) continue;
                        // NOTE: keys are stored with dot-separated segments (e.g. model_cap.maxtok.OpenRouter.gpt_4_1).
                        // Do NOT use the formatted constants directly for prefix matching.
                        if (key.startsWith("model_cap.maxtok.")
                                || key.startsWith("model_cap.maxtok.lb.")
                                || key.startsWith("model_cap.maxtok.src.")
                                || key.startsWith("model_cap.maxtok.at.")
                                || key.startsWith("model_cap.maxtok.err.")
                                || key.startsWith("model_cap.maxtok.errraw.")
                                || key.startsWith("model_cap.maxtok.auto_obs.")
                                || key.startsWith("model_cap.maxtok.auto_obs.lb.")
                                || key.startsWith("model_cap.maxtok.auto_obs.src.")
                                || key.startsWith("model_cap.maxtok.auto_obs.at.")
                                || key.startsWith("model_cap.maxtok.hard.")
                                || key.startsWith("model_cap.maxtok.hard.src.")
                                || key.startsWith("model_cap.maxtok.hard.at.")) {
                            keys.add(key);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (c != null) c.close(); } catch (Throwable ignored) {}
            }

            for (String key : keys) {
                try {
                    android.net.Uri uri = android.net.Uri.withAppendedPath(
                            tn.eluea.kgpt.provider.ConfigProvider.CONTENT_URI, key);
                    ctx.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                    // Fallback: overwrite with empty/zero to invalidate cache even if delete fails.
                    try { mClient.putString(key, ""); } catch (Throwable ignored2) {}
                }
            }
            if (!keys.isEmpty()) notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    public void setCachedSafeMaxTokensSource(LanguageModel provider, String subModel, @Nullable String source) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_SRC, provider, subModel);
            mClient.putString(key, source == null ? "" : source);
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    @Nullable
    public String getCachedSafeMaxTokensSource(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_SRC, provider, subModel);
            String v = mClient.getString(key, null);
            if (v == null) return null;
            v = v.trim();
            return v.isEmpty() ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setCachedSafeMaxTokensUpdatedAt(LanguageModel provider, String subModel, long whenMs) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_AT, provider, subModel);
            if (whenMs <= 0L) {
                mClient.putString(key, "");
            } else {
                mClient.putString(key, String.valueOf(whenMs));
            }
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    public long getCachedSafeMaxTokensUpdatedAt(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_AT, provider, subModel);
            String v = mClient.getString(key, null);
            if (v == null) return 0L;
            v = v.trim();
            if (v.isEmpty()) return 0L;
            return Long.parseLong(v);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public void setCachedSafeMaxTokensLastError(LanguageModel provider, String subModel, @Nullable String errorSummary) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_ERR, provider, subModel);
            String v = errorSummary == null ? "" : errorSummary.trim();
            if (v.length() > 400) v = v.substring(0, 400);
            mClient.putString(key, v);
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    public void setCachedSafeMaxTokensLastErrorRaw(LanguageModel provider, String subModel, @Nullable String rawError) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_ERR_RAW, provider, subModel);
            String v = rawError == null ? "" : rawError.trim();
            if (v.length() > 4000) v = v.substring(0, 4000);
            mClient.putString(key, v);
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    @Nullable
    public String getCachedSafeMaxTokensLastErrorRaw(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_ERR_RAW, provider, subModel);
            String v = mClient.getString(key, null);
            if (v == null) return null;
            v = v.trim();
            return v.isEmpty() ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public String getCachedSafeMaxTokensLastError(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_ERR, provider, subModel);
            String v = mClient.getString(key, null);
            if (v == null) return null;
            v = v.trim();
            return v.isEmpty() ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public Integer getCachedSafeMaxTokensLowerBound(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_LB, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);
            return v > 0 ? v : null;
        } catch (Throwable ignored) {
            try {
                String key = capKey(PREF_MODEL_CAP_MAXTOK_LB, provider, subModel);
                String s = mClient.getString(key, null);
                if (s == null) return null;
                int v = Integer.parseInt(s.trim());
                return v > 0 ? v : null;
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    public void setCachedSafeMaxTokensLowerBound(LanguageModel provider, String subModel, int safeLowerBound) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK_LB, provider, subModel);
            int v = safeLowerBound > 0 ? safeLowerBound : 0;
            mClient.putInt(key, v);
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    /** Convenience: write cap + source + timestamp and clear last probe error on success. */
    public void recordCachedSafeMaxTokensLearned(LanguageModel provider, String subModel, int safeMaxTokens, @Nullable String source) {
        if (safeMaxTokens <= 0) return;
        try {
            Integer oldExact = getCachedSafeMaxTokens(provider, subModel);
            Integer oldLb = getCachedSafeMaxTokensLowerBound(provider, subModel);
            String oldSrc = getCachedSafeMaxTokensSource(provider, subModel);
            int oldPri = getOutputCapSourcePriority(oldSrc);
            int newPri = getOutputCapSourcePriority(source);

            if (oldExact != null && oldExact > 0 && oldPri > newPri) {
                if (safeMaxTokens < oldExact) {
                    saveOutputCapConflictNotice("自动观测到较低上限 " + safeMaxTokens + "，已保留更高优先级结果 " + oldExact);
                }
                try {
                    tn.eluea.kgpt.util.Logger.log("OUTCAP_MERGE", "ignore exact weaker-source overwrite, provider="
                            + (provider == null ? "null" : provider.name())
                            + ", subModel=" + String.valueOf(subModel)
                            + ", oldExact=" + oldExact + ", oldSrc=" + String.valueOf(oldSrc)
                            + ", newExact=" + safeMaxTokens + ", newSrc=" + String.valueOf(source));
                } catch (Throwable ignoredLog) {}
                return;
            }

            int exactToStore = safeMaxTokens;
            if (oldExact != null && oldExact > 0 && oldPri == newPri) exactToStore = Math.max(oldExact, safeMaxTokens);
            int lbToStore = exactToStore;
            if (oldLb != null && oldLb > 0) lbToStore = Math.max(lbToStore, oldLb);

            try { setCachedSafeMaxTokens(provider, subModel, exactToStore); } catch (Throwable ignored) {}
            try { setCachedSafeMaxTokensLowerBound(provider, subModel, Math.min(lbToStore, exactToStore)); } catch (Throwable ignored) {}
            try { setCachedSafeMaxTokensSource(provider, subModel, source); } catch (Throwable ignored) {}
            try { setCachedSafeMaxTokensUpdatedAt(provider, subModel, System.currentTimeMillis()); } catch (Throwable ignored) {}
            try { setCachedSafeMaxTokensLastError(provider, subModel, null); } catch (Throwable ignored) {}
            try { setCachedSafeMaxTokensLastErrorRaw(provider, subModel, null); } catch (Throwable ignored) {}

            try {
                tn.eluea.kgpt.util.Logger.log("OUTCAP_MERGE", "store exact provider=" + (provider == null ? "null" : provider.name())
                        + ", subModel=" + String.valueOf(subModel)
                        + ", oldExact=" + String.valueOf(oldExact) + ", oldLb=" + String.valueOf(oldLb) + ", oldSrc=" + String.valueOf(oldSrc)
                        + ", newExact=" + safeMaxTokens + ", newSrc=" + String.valueOf(source)
                        + ", finalExact=" + exactToStore);
            } catch (Throwable ignoredLog) {}
        } catch (Throwable ignored) {}
    }

    /** Lower-bound only (not exact cap): record "at least this many tokens works". */
    public void recordCachedSafeMaxTokensLowerBoundLearned(LanguageModel provider, String subModel, int safeLowerBound, @Nullable String source) {
        if (safeLowerBound <= 0) return;
        try {
            Integer oldExact = getCachedSafeMaxTokens(provider, subModel);
            Integer oldLb = getCachedSafeMaxTokensLowerBound(provider, subModel);
            String oldSrc = getCachedSafeMaxTokensSource(provider, subModel);
            int oldPri = getOutputCapSourcePriority(oldSrc);
            int newPri = getOutputCapSourcePriority(source);

            if (((oldExact != null && oldExact > 0) || (oldLb != null && oldLb > 0)) && oldPri > newPri) {
                int oldShown = (oldExact != null && oldExact > 0) ? oldExact : (oldLb == null ? 0 : oldLb);
                if (safeLowerBound < oldShown) {
                    saveOutputCapConflictNotice("自动观测到较低上限 " + safeLowerBound + "，已保留更高优先级结果 " + oldShown);
                }
                try {
                    tn.eluea.kgpt.util.Logger.log("OUTCAP_MERGE", "ignore lower weaker-source overwrite, provider="
                            + (provider == null ? "null" : provider.name())
                            + ", subModel=" + String.valueOf(subModel)
                            + ", oldExact=" + String.valueOf(oldExact) + ", oldLb=" + String.valueOf(oldLb) + ", oldSrc=" + String.valueOf(oldSrc)
                            + ", newLb=" + safeLowerBound + ", newSrc=" + String.valueOf(source));
                } catch (Throwable ignoredLog) {}
                return;
            }

            int lbToStore = safeLowerBound;
            if (oldLb != null && oldLb > 0 && oldPri >= newPri) lbToStore = Math.max(oldLb, safeLowerBound);
            if (oldExact != null && oldExact > 0) lbToStore = Math.min(lbToStore, oldExact);

            try { setCachedSafeMaxTokensLowerBound(provider, subModel, lbToStore); } catch (Throwable ignored) {}
            try { setCachedSafeMaxTokensSource(provider, subModel, source); } catch (Throwable ignored) {}
            try { setCachedSafeMaxTokensUpdatedAt(provider, subModel, System.currentTimeMillis()); } catch (Throwable ignored) {}
            try { setCachedSafeMaxTokensLastError(provider, subModel, null); } catch (Throwable ignored) {}
            try { setCachedSafeMaxTokensLastErrorRaw(provider, subModel, null); } catch (Throwable ignored) {}

            try {
                tn.eluea.kgpt.util.Logger.log("OUTCAP_MERGE", "store lower provider=" + (provider == null ? "null" : provider.name())
                        + ", subModel=" + String.valueOf(subModel)
                        + ", oldExact=" + String.valueOf(oldExact) + ", oldLb=" + String.valueOf(oldLb) + ", oldSrc=" + String.valueOf(oldSrc)
                        + ", newLb=" + safeLowerBound + ", newSrc=" + String.valueOf(source)
                        + ", finalLb=" + lbToStore);
            } catch (Throwable ignoredLog) {}
        } catch (Throwable ignored) {}
    }

    public void clearCachedSupportsTemperature(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_TEMP, provider, subModel);
            mClient.putInt(key, -1);
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    public void clearCachedSupportsReasoningThinking(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_REASON, provider, subModel);
            mClient.putInt(key, -1);
            notifyUiCapabilityCacheChanged();
        } catch (Throwable ignored) {}
    }

    public void clearCachedSafeMaxTokens(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK, provider, subModel);
            mClient.putInt(key, 0);
        } catch (Throwable ignored) {}
        try {
            String keySrc = capKey(PREF_MODEL_CAP_MAXTOK_SRC, provider, subModel);
            mClient.putString(keySrc, "");
        } catch (Throwable ignored) {}
        try {
            String keyAt = capKey(PREF_MODEL_CAP_MAXTOK_AT, provider, subModel);
            mClient.putString(keyAt, "");
        } catch (Throwable ignored) {}
        try {
            String keyErr = capKey(PREF_MODEL_CAP_MAXTOK_ERR, provider, subModel);
            mClient.putString(keyErr, "");
        } catch (Throwable ignored) {}
        try {
            String keyErrRaw = capKey(PREF_MODEL_CAP_MAXTOK_ERR_RAW, provider, subModel);
            mClient.putString(keyErrRaw, "");
        } catch (Throwable ignored) {}
        try {
            String keyLb = capKey(PREF_MODEL_CAP_MAXTOK_LB, provider, subModel);
            mClient.putInt(keyLb, 0);
        } catch (Throwable ignored) {}
        // Also clear API hard-cap constraint for this model.
        try { clearCachedHardMaxTokens(provider, subModel); } catch (Throwable ignored) {}
        try { notifyUiCapabilityCacheChanged(); } catch (Throwable ignored) {}
    }

    public void clearCachedModelCapabilityHints(LanguageModel provider, String subModel) {
        try { clearCachedSupportsTemperature(provider, subModel); } catch (Throwable ignored) {}
        try { clearCachedSupportsReasoningThinking(provider, subModel); } catch (Throwable ignored) {}
        try { clearCachedSafeMaxTokens(provider, subModel); } catch (Throwable ignored) {}
        try { clearAutoObservedSafeMaxTokens(provider, subModel); } catch (Throwable ignored) {}
        try { notifyUiCapabilityCacheChanged(); } catch (Throwable ignored) {}
    }

    private void notifyUiCapabilityCacheChanged() {
        try {
            Context ctx = null;
            try { ctx = mClient != null ? mClient.getContext() : null; } catch (Throwable ignored) {}
            if (ctx == null) {
                try { ctx = tn.eluea.kgpt.KGPTApplication.getContext(); } catch (Throwable ignored) {}
            }
            if (ctx == null) return;
            Intent i = new Intent(tn.eluea.kgpt.ui.UiInteractor.ACTION_DIALOG_RESULT);
            i.putExtra("kgpt_cap_cache_changed", true);
            tn.eluea.kgpt.provider.BridgeAuth.send(ctx, i);
        } catch (Throwable ignored) {}
    }

    /** 0..20. 0 = stateless (no context), 1..20 = include that many previous turns. Default = 1. */
    public int getConversationMemoryLevel() {
        int v = 1;
        try {
            v = mClient.getInt(PREF_CONVERSATION_MEMORY_LEVEL, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_CONVERSATION_MEMORY_LEVEL, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 20) v = 20;
        return v;
    }

    public void setConversationMemoryLevel(int level) {
        int v = level;
        if (v < 0) v = 0;
        if (v > 20) v = 20;
        try {
            mClient.putInt(PREF_CONVERSATION_MEMORY_LEVEL, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_CONVERSATION_MEMORY_LEVEL, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /**
     * Normal model thinking (temperature-like). Range: 0.0 .. 1.8. Default = 0.7.
     * Stored as float.
     */
    public float getNormalModelThinking() {
        // One-time migration: if new key is absent but legacy key exists, map 0..10 -> 0.0..2.0.
        try {
            boolean hasNew = mClient.contains(PREF_NORMAL_MODEL_THINKING);
            if (!hasNew && mClient.contains(PREF_THINKING_DEPTH_LEVEL_LEGACY)) {
                int legacy = 1;
                try { legacy = mClient.getInt(PREF_THINKING_DEPTH_LEVEL_LEGACY, 1); } catch (Throwable ignored) {}
                if (legacy < 0) legacy = 0;
                if (legacy > 10) legacy = 10;
                float mapped = legacy / 5.0f; // 0..2
                mapped = round1(clamp(mapped, 0.0f, 1.8f));
                setNormalModelThinking(mapped);
                return mapped;
            }
        } catch (Throwable ignored) {}

        float v = NORMAL_MODEL_THINKING_DEFAULT;
        try {
            v = mClient.getFloat(PREF_NORMAL_MODEL_THINKING, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_NORMAL_MODEL_THINKING, null);
                if (s != null) v = Float.parseFloat(s.trim());
            } catch (Throwable ignored2) {}
        }
        v = clamp(v, 0.0f, 1.8f);
        v = round1(v);
        return v;
    }

    public void setNormalModelThinking(float value) {
        float v = clamp(value, 0.0f, 1.8f);
        v = round1(v);
        try {
            mClient.putFloat(PREF_NORMAL_MODEL_THINKING, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_NORMAL_MODEL_THINKING, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /**
     * Reasoning model thinking mode. Range: 0..5. Default = AUTO.
     */
    public int getReasoningModelThinkingMode() {
        int v = REASONING_MODEL_THINKING_AUTO;
        try {
            v = mClient.getInt(PREF_REASONING_MODEL_THINKING, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_REASONING_MODEL_THINKING, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < REASONING_MODEL_THINKING_DIVERGENT) v = REASONING_MODEL_THINKING_AUTO;
        if (v > REASONING_MODEL_THINKING_HIGH) v = REASONING_MODEL_THINKING_AUTO;
        return v;
    }

    public void setReasoningModelThinkingMode(int mode) {
        int v = mode;
        if (v < REASONING_MODEL_THINKING_DIVERGENT) v = REASONING_MODEL_THINKING_AUTO;
        if (v > REASONING_MODEL_THINKING_HIGH) v = REASONING_MODEL_THINKING_AUTO;
        try {
            mClient.putInt(PREF_REASONING_MODEL_THINKING, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_REASONING_MODEL_THINKING, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static float round1(float v) {
        return Math.round(v * 10.0f) / 10.0f;
    }

    // =============================
    // Output length fixed preset helpers (shared by UI + controllers)
    // =============================
    private static final int[] OUTPUT_LENGTH_FIXED_PRESETS = new int[]{
            64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384,
            32768, 49152, 65536, 81920, 100000, 131072
    };

    /** Returns the highest fixed output-length preset <= modelCap; -1 if none. */
    public int findOutputLengthPresetFloor(int modelCap) {
        if (modelCap <= 0) return -1;
        int best = -1;
        for (int t : OUTPUT_LENGTH_FIXED_PRESETS) {
            if (t <= modelCap && t > best) best = t;
        }
        return best;
    }

    /**
     * Sync current output-length selection to the fixed preset bucket implied by a learned model cap.
     * Only active when current output-length mode is AUTO_MAPPED; USER_FIXED mode is preserved across model switches.
     *
     * @return final stored max-tokens value after sync (or current value if skipped/no cap).
     */
    public int syncOutputLengthToLearnedCap(LanguageModel provider, String subModel, int learnedCap, @Nullable String reason) {
        int current = 1024;
        boolean currentCustom = false;
        try { current = getMaxTokensLimit(); } catch (Throwable ignored) {}
        try { currentCustom = getMaxTokensIsCustom(); } catch (Throwable ignored) {}
        int selSource = MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED;
        try { selSource = getMaxTokensSelectionSource(); } catch (Throwable ignored) {}

        if (learnedCap <= 0) {
            try {
                tn.eluea.kgpt.util.Logger.log("OUTLEN_SYNC", "skip sync (unknown cap) provider=" + (provider == null ? "null" : provider.name())
                        + ", subModel=" + String.valueOf(subModel)
                        + ", cur=" + current + ", custom=" + currentCustom + ", mode=" + selSource
                        + ", reason=" + String.valueOf(reason));
            } catch (Throwable ignoredLog) {}
            return current;
        }

        if (selSource == MAX_TOKENS_SELECTION_SOURCE_USER_FIXED) {
            try {
                tn.eluea.kgpt.util.Logger.log("OUTLEN_SYNC", "skip sync (USER_FIXED) provider=" + (provider == null ? "null" : provider.name())
                        + ", subModel=" + String.valueOf(subModel)
                        + ", cap=" + learnedCap
                        + ", cur=" + current + (currentCustom ? "(custom)" : "(fixed)")
                        + ", reason=" + String.valueOf(reason));
            } catch (Throwable ignoredLog) {}
            return current;
        }

        int target = findOutputLengthPresetFloor(learnedCap);
        boolean targetCustom = false;
        if (target <= 0) {
            target = learnedCap;
            targetCustom = true;
        }

        boolean changed = false;
        try {
            if (current != target) {
                setMaxTokensLimit(target);
                changed = true;
            }
            if (currentCustom != targetCustom) {
                setMaxTokensIsCustom(targetCustom);
                changed = true;
            }
            setMaxTokensSelectionSource(MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED);
        } catch (Throwable ignored) {}

        try {
            tn.eluea.kgpt.util.Logger.log("OUTLEN_SYNC", "sync by cap provider=" + (provider == null ? "null" : provider.name())
                    + ", subModel=" + String.valueOf(subModel)
                    + ", cap=" + learnedCap
                    + ", floorPreset=" + findOutputLengthPresetFloor(learnedCap)
                    + ", from=" + current + (currentCustom ? "(custom)" : "(fixed)")
                    + ", to=" + target + (targetCustom ? "(custom)" : "(fixed)")
                    + ", changed=" + changed
                    + ", mode=AUTO_MAPPED"
                    + ", reason=" + String.valueOf(reason));
        } catch (Throwable ignoredLog) {}

        return target;
    }

    // =============================
    // Labs: Output length (Max tokens)
    // =============================
    private static final String PREF_MAX_TOKENS_PRESET = "max_tokens_preset_v1";
    private static final String PREF_MAX_TOKENS_LIMIT = "max_tokens_limit_v2";
    private static final String PREF_MAX_TOKENS_IS_CUSTOM = "max_tokens_is_custom_v1";
    public static final int MAX_TOKENS_PRESET_SHORT = 0;
    public static final int MAX_TOKENS_PRESET_MEDIUM = 1;
    public static final int MAX_TOKENS_PRESET_LONG = 2;

    /** Legacy preset: 0=Short, 1=Medium, 2=Long. Default=Medium. */
    public int getMaxTokensPreset() {
        int v = MAX_TOKENS_PRESET_MEDIUM;
        try {
            v = mClient.getInt(PREF_MAX_TOKENS_PRESET, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_MAX_TOKENS_PRESET, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < MAX_TOKENS_PRESET_SHORT) v = MAX_TOKENS_PRESET_MEDIUM;
        if (v > MAX_TOKENS_PRESET_LONG) v = MAX_TOKENS_PRESET_MEDIUM;
        return v;
    }

    public void setMaxTokensPreset(int preset) {
        int v = preset;
        if (v < MAX_TOKENS_PRESET_SHORT) v = MAX_TOKENS_PRESET_SHORT;
        if (v > MAX_TOKENS_PRESET_LONG) v = MAX_TOKENS_PRESET_LONG;
        try {
            mClient.putInt(PREF_MAX_TOKENS_PRESET, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_MAX_TOKENS_PRESET, String.valueOf(v)); } catch (Throwable ignored) {}
        }
        try { setMaxTokensIsCustom(false); } catch (Throwable ignored) {}
    }

    /** Legacy mapping for presets (recommended defaults): Short=512, Medium=1024, Long=2048. */
    private int getMaxTokensFromPresetLegacy() {
        int p = getMaxTokensPreset();
        switch (p) {
            case MAX_TOKENS_PRESET_SHORT:
                return 512;
            case MAX_TOKENS_PRESET_LONG:
                return 2048;
            case MAX_TOKENS_PRESET_MEDIUM:
            default:
                return 1024;
        }
    }

    /**
     * Max tokens limit (integer).
     *
     * Backward compatible: if limit is not set, falls back to the legacy preset mapping.
     */
    public int getMaxTokensLimit() {
        int v = 0;
        try {
            v = mClient.getInt(PREF_MAX_TOKENS_LIMIT, 0);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_MAX_TOKENS_LIMIT, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v <= 0) v = getMaxTokensFromPresetLegacy();
        return v;
    }

    public void setMaxTokensLimit(int tokens) {
        int v = tokens;
        if (v <= 0) v = 1024;
        try {
            mClient.putInt(PREF_MAX_TOKENS_LIMIT, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_MAX_TOKENS_LIMIT, String.valueOf(v)); } catch (Throwable ignored) {}
        }
        // Keep legacy preset loosely in sync for older code paths.
        if (v == 512) setMaxTokensPreset(MAX_TOKENS_PRESET_SHORT);
        else if (v == 1024) setMaxTokensPreset(MAX_TOKENS_PRESET_MEDIUM);
        else if (v == 2048) setMaxTokensPreset(MAX_TOKENS_PRESET_LONG);
    }

    /** True when the current output-length selection came from the Custom entry. */
    public boolean getMaxTokensIsCustom() {
        try {
            return mClient.getBoolean(PREF_MAX_TOKENS_IS_CUSTOM, false);
        } catch (Throwable ignored) {
            try {
                String raw = mClient.getString(PREF_MAX_TOKENS_IS_CUSTOM, null);
                if (raw != null) return Boolean.parseBoolean(raw.trim());
            } catch (Throwable ignored2) {}
            return false;
        }
    }

    public void setMaxTokensSelectionSource(int source) {
        try {
            mClient.putInt(PREF_MAX_TOKENS_SELECTION_SOURCE, source);
        } catch (Throwable t) {
            try { mClient.putString(PREF_MAX_TOKENS_SELECTION_SOURCE, String.valueOf(source)); } catch (Throwable ignored) {}
        }
    }

    public int getMaxTokensSelectionSource() {
        try {
            if (mClient.contains(PREF_MAX_TOKENS_SELECTION_SOURCE)) {
                int v = mClient.getInt(PREF_MAX_TOKENS_SELECTION_SOURCE, MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED);
                return v == MAX_TOKENS_SELECTION_SOURCE_USER_FIXED ? v : MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED;
            }
        } catch (Throwable ignored) {
            try {
                String raw = mClient.getString(PREF_MAX_TOKENS_SELECTION_SOURCE, null);
                if (raw != null) {
                    int v = Integer.parseInt(raw.trim());
                    return v == MAX_TOKENS_SELECTION_SOURCE_USER_FIXED ? v : MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED;
                }
            } catch (Throwable ignored2) {}
        }
        return MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED;
    }

    public void resetMaxTokensSelectionToAutoMapped() {
        setMaxTokensSelectionSource(MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED);
    }

    public void setMaxTokensIsCustom(boolean isCustom) {
        try {
            mClient.putBoolean(PREF_MAX_TOKENS_IS_CUSTOM, isCustom);
        } catch (Throwable t) {
            try { mClient.putString(PREF_MAX_TOKENS_IS_CUSTOM, String.valueOf(isCustom)); } catch (Throwable ignored) {}
        }
    }

    /** Backward-compat alias: previously returned the legacy preset mapping. */
    public int getMaxTokensFromPreset() {
        return getMaxTokensLimit();
    }


    // =============================
    // Labs: Auto summarize older context (memory compression)
    // =============================
    private static final String PREF_AUTO_SUMMARIZE_OLD_CONTEXT = "auto_summarize_old_context_v1";

    public boolean getAutoSummarizeOldContextEnabled() {
        // Default ON: improves stability & reduces token usage when memory is high.
        return mClient.getBoolean(PREF_AUTO_SUMMARIZE_OLD_CONTEXT, true);
    }

    public void setAutoSummarizeOldContextEnabled(boolean enabled) {
        mClient.putBoolean(PREF_AUTO_SUMMARIZE_OLD_CONTEXT, enabled);
    }

    // =============================
    // Labs: Auto downgrade strategy (Stream / BaseURL / Model)
    // =============================
    private static final String PREF_AUTO_DOWNGRADE_FLAGS = "auto_downgrade_flags_v1";
    private static final String PREF_AUTO_DOWNGRADE_BACKUP_BASEURL = "auto_downgrade_backup_baseurl_v1";
    private static final String PREF_AUTO_DOWNGRADE_BACKUP_MODEL = "auto_downgrade_backup_model_v1";

    public static final int DOWNGRADE_FLAG_STREAM = 1;
    public static final int DOWNGRADE_FLAG_BASEURL = 2;
    public static final int DOWNGRADE_FLAG_MODEL = 4;

    /** Bitmask of DOWNGRADE_FLAG_* . Default enables all (7). */
    public int getAutoDowngradeFlags() {
        int v = (DOWNGRADE_FLAG_STREAM | DOWNGRADE_FLAG_BASEURL | DOWNGRADE_FLAG_MODEL);
        try {
            v = mClient.getInt(PREF_AUTO_DOWNGRADE_FLAGS, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_AUTO_DOWNGRADE_FLAGS, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 7) v = 7;
        return v;
    }

    public void setAutoDowngradeFlags(int flags) {
        int v = flags;
        if (v < 0) v = 0;
        if (v > 7) v = 7;
        try {
            mClient.putInt(PREF_AUTO_DOWNGRADE_FLAGS, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_AUTO_DOWNGRADE_FLAGS, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    public String getAutoDowngradeBackupBaseUrl() {
        try {
            String s = mClient.getString(PREF_AUTO_DOWNGRADE_BACKUP_BASEURL, "");
            return s == null ? "" : s.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public void setAutoDowngradeBackupBaseUrl(String baseUrl) {
        String v = baseUrl == null ? "" : baseUrl.trim();
        mClient.putString(PREF_AUTO_DOWNGRADE_BACKUP_BASEURL, v);
    }

    public LanguageModel getAutoDowngradeBackupModel() {
        try {
            String s = mClient.getString(PREF_AUTO_DOWNGRADE_BACKUP_MODEL, "");
            if (s == null) return null;
            s = s.trim();
            if (s.isEmpty()) return null;
            try {
                return LanguageModel.valueOf(s);
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setAutoDowngradeBackupModel(LanguageModel model) {
        String v = (model == null) ? "" : model.name();
        mClient.putString(PREF_AUTO_DOWNGRADE_BACKUP_MODEL, v);
    }

    // =============================
    // Labs: Request cancel / concurrency policy
    // =============================
    private static final String PREF_REQUEST_CONCURRENCY_POLICY = "request_concurrency_policy_v1";
    public static final int REQUEST_POLICY_CANCEL_PREVIOUS = 0;
    public static final int REQUEST_POLICY_IGNORE_NEW = 1;
    public static final int REQUEST_POLICY_QUEUE_LATEST = 2;

    /** 0=Cancel previous, 1=Ignore new, 2=Queue latest. Default=Cancel previous. */
    public int getRequestConcurrencyPolicy() {
        int v = REQUEST_POLICY_CANCEL_PREVIOUS;
        try {
            v = mClient.getInt(PREF_REQUEST_CONCURRENCY_POLICY, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_REQUEST_CONCURRENCY_POLICY, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < REQUEST_POLICY_CANCEL_PREVIOUS) v = REQUEST_POLICY_CANCEL_PREVIOUS;
        if (v > REQUEST_POLICY_QUEUE_LATEST) v = REQUEST_POLICY_CANCEL_PREVIOUS;
        return v;
    }

    public void setRequestConcurrencyPolicy(int policy) {
        int v = policy;
        if (v < REQUEST_POLICY_CANCEL_PREVIOUS) v = REQUEST_POLICY_CANCEL_PREVIOUS;
        if (v > REQUEST_POLICY_QUEUE_LATEST) v = REQUEST_POLICY_QUEUE_LATEST;
        try {
            mClient.putInt(PREF_REQUEST_CONCURRENCY_POLICY, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_REQUEST_CONCURRENCY_POLICY, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }



    // =============================
    // Labs: Watchdog timeouts (first chunk / stall)
    // =============================
    private static final String PREF_WATCHDOG_FIRST_CHUNK_TIMEOUT_MS = "watchdog_first_chunk_timeout_ms_v1";
    private static final String PREF_WATCHDOG_STALL_TIMEOUT_MS = "watchdog_stall_timeout_ms_v1";

    /** Defaults used when the user has not customized the values. */
    public static final long WATCHDOG_FIRST_CHUNK_TIMEOUT_DEFAULT_MS = 30_000L; // 30s
    public static final long WATCHDOG_STALL_TIMEOUT_DEFAULT_MS = 180_000L;     // 180s

    /** Minimum/maximum bounds to avoid extreme values that lock the trigger pipeline. */
    private static final long WATCHDOG_FIRST_CHUNK_TIMEOUT_MIN_MS = 5_000L;    // 5s
    private static final long WATCHDOG_FIRST_CHUNK_TIMEOUT_MAX_MS = 300_000L;  // 5min
    private static final long WATCHDOG_STALL_TIMEOUT_MIN_MS = 15_000L;         // 15s
    private static final long WATCHDOG_STALL_TIMEOUT_MAX_MS = 900_000L;        // 15min

    public long getWatchdogFirstChunkTimeoutMs() {
        long v = WATCHDOG_FIRST_CHUNK_TIMEOUT_DEFAULT_MS;
        try {
            v = mClient.getLong(PREF_WATCHDOG_FIRST_CHUNK_TIMEOUT_MS, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_WATCHDOG_FIRST_CHUNK_TIMEOUT_MS, null);
                if (s != null) v = Long.parseLong(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < WATCHDOG_FIRST_CHUNK_TIMEOUT_MIN_MS) v = WATCHDOG_FIRST_CHUNK_TIMEOUT_MIN_MS;
        if (v > WATCHDOG_FIRST_CHUNK_TIMEOUT_MAX_MS) v = WATCHDOG_FIRST_CHUNK_TIMEOUT_MAX_MS;
        return v;
    }

    public void setWatchdogFirstChunkTimeoutMs(long timeoutMs) {
        long v = timeoutMs;
        if (v < WATCHDOG_FIRST_CHUNK_TIMEOUT_MIN_MS) v = WATCHDOG_FIRST_CHUNK_TIMEOUT_MIN_MS;
        if (v > WATCHDOG_FIRST_CHUNK_TIMEOUT_MAX_MS) v = WATCHDOG_FIRST_CHUNK_TIMEOUT_MAX_MS;
        try {
            mClient.putLong(PREF_WATCHDOG_FIRST_CHUNK_TIMEOUT_MS, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_WATCHDOG_FIRST_CHUNK_TIMEOUT_MS, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    public long getWatchdogStallTimeoutMs() {
        long v = WATCHDOG_STALL_TIMEOUT_DEFAULT_MS;
        try {
            v = mClient.getLong(PREF_WATCHDOG_STALL_TIMEOUT_MS, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_WATCHDOG_STALL_TIMEOUT_MS, null);
                if (s != null) v = Long.parseLong(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < WATCHDOG_STALL_TIMEOUT_MIN_MS) v = WATCHDOG_STALL_TIMEOUT_MIN_MS;
        if (v > WATCHDOG_STALL_TIMEOUT_MAX_MS) v = WATCHDOG_STALL_TIMEOUT_MAX_MS;
        return v;
    }

    public void setWatchdogStallTimeoutMs(long timeoutMs) {
        long v = timeoutMs;
        if (v < WATCHDOG_STALL_TIMEOUT_MIN_MS) v = WATCHDOG_STALL_TIMEOUT_MIN_MS;
        if (v > WATCHDOG_STALL_TIMEOUT_MAX_MS) v = WATCHDOG_STALL_TIMEOUT_MAX_MS;
        try {
            mClient.putLong(PREF_WATCHDOG_STALL_TIMEOUT_MS, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_WATCHDOG_STALL_TIMEOUT_MS, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    public void resetWatchdogTimeoutsToDefault() {
        try { setWatchdogFirstChunkTimeoutMs(WATCHDOG_FIRST_CHUNK_TIMEOUT_DEFAULT_MS); } catch (Throwable ignored) {}
        try { setWatchdogStallTimeoutMs(WATCHDOG_STALL_TIMEOUT_DEFAULT_MS); } catch (Throwable ignored) {}
    }


    // =============================
    // Labs: Generating Content placeholder (pre/post keywords) + haptic on reply
    // =============================
    private static final String PREF_GENERATING_CONTENT_ENABLED = "generating_content_enabled_v1";
    /** Empty => use the localized default resource string. */
    private static final String PREF_GENERATING_CONTENT_PREFIX = "generating_content_prefix_v1";
    /** Optional trailing keyword inserted AFTER the cursor (so AI output is inserted BEFORE it). */
    private static final String PREF_GENERATING_CONTENT_SUFFIX = "generating_content_suffix_v1";
    private static final String PREF_AI_REPLY_VIBRATE = "ai_reply_vibrate_v1";

    // Default keywords for "Generating Content" placeholders.
    // Locale-aware so non-Chinese users don't get Chinese defaults.
    private static String defaultGeneratingContentPrefix() {
        try {
            String lang = java.util.Locale.getDefault() != null ? java.util.Locale.getDefault().getLanguage() : "";
            if (lang != null && lang.startsWith("zh")) {
                return "AI 正在输入";
            }
        } catch (Throwable ignored) {}
        return "AI is typing";
    }

    private static String defaultGeneratingContentSuffix() {
        try {
            String lang = java.util.Locale.getDefault() != null ? java.util.Locale.getDefault().getLanguage() : "";
            if (lang != null && lang.startsWith("zh")) {
                return "AI正在回复";
            }
        } catch (Throwable ignored) {}
        return "AI is replying";
    }

    /** Default ON to preserve existing behavior. */
    public boolean getGeneratingContentEnabled() {
        // NOTE: In Xposed/IME processes, ContentObserver callbacks may be missed on some ROMs.
        // Bypass the in-memory cache so toggles take effect without requiring force-stop.
        return mClient.getBooleanNoCache(PREF_GENERATING_CONTENT_ENABLED, true);
    }

    public void setGeneratingContentEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GENERATING_CONTENT_ENABLED, enabled);
    }

    /** Returns custom prefix; empty means "use default". */
    public String getGeneratingContentPrefix() {
        String v = null;
        try {
            v = mClient.getString(PREF_GENERATING_CONTENT_PREFIX, null);
        } catch (Throwable ignored) {}
        // Empty => use default (leave blank to use default).
        // IMPORTANT: do NOT trim here.
        // Users may intentionally want leading/trailing spaces (e.g., "AI 正在思考 ")
        // and an all-space value should be treated as a real custom value.
        if (v == null || v.isEmpty()) {
            return defaultGeneratingContentPrefix();
        }
        return v;
    }

    public void setGeneratingContentPrefix(String prefix) {
        String v = prefix == null ? "" : prefix;
        mClient.putString(PREF_GENERATING_CONTENT_PREFIX, v);
    }

    /** Returns custom suffix inserted after cursor; empty means "use default". */
    public String getGeneratingContentSuffix() {
        String v = null;
        try {
            v = mClient.getString(PREF_GENERATING_CONTENT_SUFFIX, null);
        } catch (Throwable ignored) {}
        // Empty => use default.
        // IMPORTANT: do NOT trim here (see getGeneratingContentPrefix).
        if (v == null || v.isEmpty()) {
            return defaultGeneratingContentSuffix();
        }
        return v;
    }

    public void setGeneratingContentSuffix(String suffix) {
        String v = suffix == null ? "" : suffix;
        mClient.putString(PREF_GENERATING_CONTENT_SUFFIX, v);
    }

    // Backward-compatible alias (some UI components still call this name)
    public String getGeneratingContentSuffixAfterCursor() {
        return getGeneratingContentSuffix();
    }

    // Backward-compatible alias (some UI components still call this name)
    public void setGeneratingContentSuffixAfterCursor(String suffix) {
        setGeneratingContentSuffix(suffix);
    }


    /** 默认开启：与“生成中提示”默认体验一致。 */
    public boolean getAiReplyVibrateEnabled() {
        return mClient.getBoolean(PREF_AI_REPLY_VIBRATE, true);
    }

    public void setAiReplyVibrateEnabled(boolean enabled) {
        mClient.putBoolean(PREF_AI_REPLY_VIBRATE, enabled);
    }


    // ===== Generating Content: Toast / Sound / Vibration strength+frequency / Marker style =====
    private static final String PREF_GEN_TOAST_ENABLED = "generating_content_toast_enabled_v1";
    // v3: input box dynamic role marker (plain text, no toast/overlays)
    private static final String PREF_GEN_INPUT_ROLE_MARKER_ENABLED = "generating_content_input_role_marker_enabled_v1";
    // v6: per-position switches for role marker (after-cursor / before-cursor)
    private static final String PREF_GEN_INPUT_ROLE_MARKER_APPLY_TO_SUFFIX = "generating_content_input_role_marker_apply_to_suffix_v1";
    // v5: when role marker is enabled, optionally also apply to prefix (thinking placeholder)
    private static final String PREF_GEN_INPUT_ROLE_MARKER_APPLY_TO_PREFIX = "generating_content_input_role_marker_apply_to_prefix_v1";
    // v2: advanced status banner features
    private static final String PREF_GEN_DYNAMIC_PREFIX_ENABLED = "generating_content_dynamic_prefix_enabled_v1";
    private static final String PREF_GEN_TOKEN_BURNER_ENABLED = "generating_content_token_burner_enabled_v1";
    private static final String PREF_GEN_STANDALONE_FLOAT_ENABLED = "generating_content_standalone_float_enabled_v1";
    private static final String PREF_GEN_COMPLETE_SOUND = "generating_content_complete_sound_v1";
    private static final String PREF_GEN_TYPING_SOUND_ENABLED = "generating_content_typing_sound_enabled_v1";
    private static final String PREF_GEN_TYPING_SOUND_STYLE = "generating_content_typing_sound_style_v1";
    private static final String PREF_GEN_THINKING_ELAPSED_ENABLED = "generating_content_thinking_elapsed_enabled_v1";
    private static final String PREF_GEN_REPLY_TOKEN_COUNTER_ENABLED = "generating_content_reply_token_counter_enabled_v1";
    private static final String PREF_GEN_REPLY_TOKEN_COUNTER_AUTO_REMOVE = "generating_content_reply_token_counter_auto_remove_v1";
    // v7: delay (ms) before auto-removing the final token counter marker (0-10s)
    private static final String PREF_GEN_REPLY_TOKEN_COUNTER_AUTO_REMOVE_DELAY_MS = "generating_content_reply_token_counter_auto_remove_delay_ms_v1";
    private static final String PREF_GEN_VIB_INTENSITY = "ai_reply_vibrate_intensity_v1";
    private static final String PREF_GEN_VIB_FREQUENCY = "ai_reply_vibrate_frequency_v1";
    private static final String PREF_GEN_MARKER_STYLE = "generating_content_marker_style_v1";
    private static final String PREF_GEN_MARKER_COLOR = "generating_content_marker_color_v1";
    private static final String PREF_GEN_MARKER_ANIM_LEN = "generating_content_marker_anim_len_v1";
    private static final String PREF_GEN_MARKER_ANIM_SPEED = "generating_content_marker_anim_speed_v1";

    // ===== Interrupt AI (Stop/Pause streaming output) =====
    // Stored in ConfigProvider so it works reliably across Xposed/host processes.
    private static final String PREF_INTERRUPT_AI_ENABLED = "interrupt_ai_enabled_v1";
    private static final String PREF_INTERRUPT_AI_GESTURE = "interrupt_ai_gesture_v1";
    private static final String PREF_INTERRUPT_AI_MODE = "interrupt_ai_mode_v1";
    private static final String PREF_INTERRUPT_AI_CUSTOM_TRIGGER = "interrupt_ai_custom_trigger_v1";

    // Gesture types
    public static final int INTERRUPT_GESTURE_BANGBANG = 0;          // "!!"
    public static final int INTERRUPT_GESTURE_STOP = 1;              // "/stop"
    public static final int INTERRUPT_GESTURE_DOUBLE_BACKSPACE = 2;  // double backspace
    public static final int INTERRUPT_GESTURE_DOUBLE_SPACE = 4;      // double space
    public static final int INTERRUPT_GESTURE_CUSTOM = 3;            // custom text sequence

    // Interrupt modes
    public static final int INTERRUPT_MODE_SOFT_BUFFER = 0;          // pause writing + buffer
    public static final int INTERRUPT_MODE_HARD_CANCEL = 1;          // cancel request
    public static final int INTERRUPT_MODE_BOTH = 2;                 // do both

    public static final int GEN_SOUND_NONE = 0;
    public static final int GEN_SOUND_SYSTEM_NOTIFICATION = 1;
    public static final int GEN_SOUND_BEEP = 2;
    public static final int GEN_SOUND_CLICK = 3;

    public static final int GEN_TYPING_SOUND_STYLE_CLICK = 0;
    public static final int GEN_TYPING_SOUND_STYLE_SOFT_TICK = 1;
    public static final int GEN_TYPING_SOUND_STYLE_MECH_KEY = 2;
    public static final int GEN_TYPING_SOUND_STYLE_PULSE = 3;
    public static final int GEN_TYPING_SOUND_STYLE_BEEP = 4;

    public static final int GEN_MARKER_STYLE_PLAIN = 0;
    public static final int GEN_MARKER_STYLE_COLOR_TAG = 1;
    public static final int GEN_MARKER_STYLE_RAINBOW_ANIM = 2;
    public static final int GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM = 3;

    public static final int GEN_MARKER_COLOR_BLUE = 0;
    public static final int GEN_MARKER_COLOR_RED = 1;
    public static final int GEN_MARKER_COLOR_GREEN = 2;
    public static final int GEN_MARKER_COLOR_YELLOW = 3;
    public static final int GEN_MARKER_COLOR_PURPLE = 4;
    public static final int GEN_MARKER_COLOR_RANDOM = 5;

    /** Toast提示：默认开启。 */
    public boolean getGeneratingContentToastEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_TOAST_ENABLED, true);
    }

    public void setGeneratingContentToastEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_TOAST_ENABLED, enabled);
    }

    /**
     * 输入框内动态显示角色名（纯文本）。
     * 开启后：
     *  - 占位符文本支持模板 {role} / ${role}，用于显示“本次请求命中的角色名称”。
     *  - 若占位符中未包含 {role}，运行时会自动把角色名加到最前面。
     *  - 为兼容多数 IME-host 应用的纯文本输入框，运行时会过滤掉 emoji/富文本等非 BMP 字符。
     * 默认关闭。
     */
    public boolean getGeneratingContentInputRoleMarkerEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_INPUT_ROLE_MARKER_ENABLED, false);
    }

    public void setGeneratingContentInputRoleMarkerEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_INPUT_ROLE_MARKER_ENABLED, enabled);
    }

    /** 是否在“光标后（回复中关键词）”占位符中应用角色名（模板 {role}/${role} 与自动拼接）。默认开启。 */
    public boolean getGeneratingContentInputRoleMarkerApplyToSuffixEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_INPUT_ROLE_MARKER_APPLY_TO_SUFFIX, true);
    }

    public void setGeneratingContentInputRoleMarkerApplyToSuffixEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_INPUT_ROLE_MARKER_APPLY_TO_SUFFIX, enabled);
    }

    /** 是否在“AI 回复前（占位提示/思考提示）”占位符中应用角色名（模板 {role}/${role} 与自动拼接）。默认关闭。 */
    public boolean getGeneratingContentInputRoleMarkerApplyToPrefixEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_INPUT_ROLE_MARKER_APPLY_TO_PREFIX, false);
    }

    public void setGeneratingContentInputRoleMarkerApplyToPrefixEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_INPUT_ROLE_MARKER_APPLY_TO_PREFIX, enabled);
    }

    /** 动态状态前缀（推理/代码嗅探）：默认开启。 */
    public boolean getGeneratingContentDynamicPrefixEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_DYNAMIC_PREFIX_ENABLED, true);
    }

    public void setGeneratingContentDynamicPrefixEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_DYNAMIC_PREFIX_ENABLED, enabled);
    }

    /** Token 算力燃烧条：默认开启（仅在顶部悬浮提示可用时显示）。 */
    public boolean getGeneratingContentTokenBurnerEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_TOKEN_BURNER_ENABLED, true);
    }

    public void setGeneratingContentTokenBurnerEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_TOKEN_BURNER_ENABLED, enabled);
    }

    /** 独立悬浮条（KGPT 主进程 Overlay）：默认关闭，避免未授权时误判。 */
    public boolean getGeneratingContentStandaloneFloatEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_STANDALONE_FLOAT_ENABLED, false);
    }

    public void setGeneratingContentStandaloneFloatEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_STANDALONE_FLOAT_ENABLED, enabled);
    }

    /** 回复完成提示音：默认“咚”(Click)。 */
    public int getGeneratingContentCompleteSound() {
        int v = GEN_SOUND_CLICK;
        try { v = mClient.getInt(PREF_GEN_COMPLETE_SOUND, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_COMPLETE_SOUND, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < GEN_SOUND_NONE || v > GEN_SOUND_CLICK) v = GEN_SOUND_CLICK;
        return v;
    }

    public void setGeneratingContentCompleteSound(int soundType) {
        int v = soundType;
        if (v < GEN_SOUND_NONE) v = GEN_SOUND_NONE;
        if (v > GEN_SOUND_CLICK) v = GEN_SOUND_CLICK;
        try { mClient.putInt(PREF_GEN_COMPLETE_SOUND, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_COMPLETE_SOUND, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 输出时打字音（流式/模拟流式）：默认关闭，避免打扰。 */
    public boolean getGeneratingContentTypingSoundEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_TYPING_SOUND_ENABLED, false);
    }

    public void setGeneratingContentTypingSoundEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_TYPING_SOUND_ENABLED, enabled);
    }

    /** 输出时打字音样式。默认 Click。 */
    public int getGeneratingContentTypingSoundStyle() {
        int v = GEN_TYPING_SOUND_STYLE_CLICK;
        try { v = mClient.getInt(PREF_GEN_TYPING_SOUND_STYLE, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_TYPING_SOUND_STYLE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < GEN_TYPING_SOUND_STYLE_CLICK || v > GEN_TYPING_SOUND_STYLE_BEEP) v = GEN_TYPING_SOUND_STYLE_CLICK;
        return v;
    }

    public void setGeneratingContentTypingSoundStyle(int style) {
        int v = style;
        if (v < GEN_TYPING_SOUND_STYLE_CLICK) v = GEN_TYPING_SOUND_STYLE_CLICK;
        if (v > GEN_TYPING_SOUND_STYLE_BEEP) v = GEN_TYPING_SOUND_STYLE_BEEP;
        try { mClient.putInt(PREF_GEN_TYPING_SOUND_STYLE, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_TYPING_SOUND_STYLE, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 思考占位词后显示耗时。默认开启。 */
    public boolean getGeneratingContentThinkingElapsedEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_THINKING_ELAPSED_ENABLED, true);
    }

    public void setGeneratingContentThinkingElapsedEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_THINKING_ELAPSED_ENABLED, enabled);
    }

    /** 回复中 Token 计数（独立计数标记）。默认关闭。 */
    public boolean getGeneratingContentReplyTokenCounterEnabled() {
        return mClient.getBooleanNoCache(PREF_GEN_REPLY_TOKEN_COUNTER_ENABLED, false);
    }

    public void setGeneratingContentReplyTokenCounterEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_REPLY_TOKEN_COUNTER_ENABLED, enabled);
    }

    /** 回复完成后是否自动删除 Token 计数标记。默认开启。 */
    public boolean getGeneratingContentReplyTokenCounterAutoRemove() {
        return mClient.getBooleanNoCache(PREF_GEN_REPLY_TOKEN_COUNTER_AUTO_REMOVE, true);
    }

    public void setGeneratingContentReplyTokenCounterAutoRemove(boolean enabled) {
        mClient.putBoolean(PREF_GEN_REPLY_TOKEN_COUNTER_AUTO_REMOVE, enabled);
    }

    /**
     * 回复完成后自动删除 Token 计数的延迟（毫秒）。范围：0-10000。
     * 0 表示立即删除。
     */
    public long getGeneratingContentReplyTokenCounterAutoRemoveDelayMs() {
        long v = 0L;
        try {
            // Prefer int storage for compatibility with older config clients.
            v = (long) mClient.getInt(PREF_GEN_REPLY_TOKEN_COUNTER_AUTO_REMOVE_DELAY_MS, 0);
        } catch (Throwable t) {
            try {
                String s = mClient.getString(PREF_GEN_REPLY_TOKEN_COUNTER_AUTO_REMOVE_DELAY_MS, null);
                if (s != null) v = Long.parseLong(s.trim());
            } catch (Throwable ignored) {
            }
        }
        if (v < 0L) v = 0L;
        if (v > 10_000L) v = 10_000L;
        return v;
    }

    public void setGeneratingContentReplyTokenCounterAutoRemoveDelayMs(long delayMs) {
        long v = delayMs;
        if (v < 0L) v = 0L;
        if (v > 10_000L) v = 10_000L;
        try {
            mClient.putInt(PREF_GEN_REPLY_TOKEN_COUNTER_AUTO_REMOVE_DELAY_MS, (int) v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_REPLY_TOKEN_COUNTER_AUTO_REMOVE_DELAY_MS, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 震动强度（0-100）。默认100。 */
    public int getAiReplyVibrateIntensityPercent() {
        int v = 100;
        try { v = mClient.getInt(PREF_GEN_VIB_INTENSITY, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_VIB_INTENSITY, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setAiReplyVibrateIntensityPercent(int percent) {
        int v = percent;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_GEN_VIB_INTENSITY, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_VIB_INTENSITY, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 震动频率（0-100）。默认100。 */
    public int getAiReplyVibrateFrequencyPercent() {
        int v = 100;
        try { v = mClient.getInt(PREF_GEN_VIB_FREQUENCY, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_VIB_FREQUENCY, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setAiReplyVibrateFrequencyPercent(int percent) {
        int v = percent;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_GEN_VIB_FREQUENCY, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_VIB_FREQUENCY, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 回复中标记样式。默认彩虹动画。 */
    public int getGeneratingContentMarkerStyle() {
        int v = GEN_MARKER_STYLE_RAINBOW_ANIM;
        try { v = mClient.getInt(PREF_GEN_MARKER_STYLE, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_MARKER_STYLE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < GEN_MARKER_STYLE_PLAIN || v > GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) v = GEN_MARKER_STYLE_RAINBOW_ANIM;
        return v;
    }

    public void setGeneratingContentMarkerStyle(int style) {
        int v = style;
        if (v < GEN_MARKER_STYLE_PLAIN) v = GEN_MARKER_STYLE_PLAIN;
        if (v > GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) v = GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM;
        try { mClient.putInt(PREF_GEN_MARKER_STYLE, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_MARKER_STYLE, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 色块颜色（用于色块前缀/随机/彩虹动画）。默认随机。 */
    public int getGeneratingContentMarkerColor() {
        int v = GEN_MARKER_COLOR_RANDOM;
        try { v = mClient.getInt(PREF_GEN_MARKER_COLOR, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_MARKER_COLOR, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < GEN_MARKER_COLOR_BLUE || v > GEN_MARKER_COLOR_RANDOM) v = GEN_MARKER_COLOR_RANDOM;
        return v;
    }

    public void setGeneratingContentMarkerColor(int color) {
        int v = color;
        if (v < GEN_MARKER_COLOR_BLUE) v = GEN_MARKER_COLOR_BLUE;
        if (v > GEN_MARKER_COLOR_RANDOM) v = GEN_MARKER_COLOR_RANDOM;
        try { mClient.putInt(PREF_GEN_MARKER_COLOR, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_MARKER_COLOR, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }


    /** 彩虹动画 marker 长度（格数），仅对 彩虹动画 样式生效。默认 3（可选 3/6/10）。 */
    public int getGeneratingContentMarkerAnimLength() {
        int v = 3;
        try { v = mClient.getInt(PREF_GEN_MARKER_ANIM_LEN, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_MARKER_ANIM_LEN, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v != 3 && v != 6 && v != 10) v = 3;
        return v;
    }

    public void setGeneratingContentMarkerAnimLength(int len) {
        int v = len;
        if (v != 3 && v != 6 && v != 10) v = 3;
        try { mClient.putInt(PREF_GEN_MARKER_ANIM_LEN, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_MARKER_ANIM_LEN, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 彩虹动画 marker 速度（0-100），越大越快。默认 50。 */
    public int getGeneratingContentMarkerAnimSpeedPercent() {
        int v = 50;
        try { v = mClient.getInt(PREF_GEN_MARKER_ANIM_SPEED, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_MARKER_ANIM_SPEED, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setGeneratingContentMarkerAnimSpeedPercent(int percent) {
        int v = percent;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_GEN_MARKER_ANIM_SPEED, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_MARKER_ANIM_SPEED, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }


    // ============================================================
    // Interrupt AI (Stop / Pause) settings
    // ============================================================

    /** 总开关：启用“打断 AI”手势（在 AI 输出期间也可生效）。默认关闭。 */
    public boolean getInterruptAiEnabled() {
        return mClient.getBooleanNoCache(PREF_INTERRUPT_AI_ENABLED, false);
    }

    public void setInterruptAiEnabled(boolean enabled) {
        mClient.putBoolean(PREF_INTERRUPT_AI_ENABLED, enabled);
    }

    /** 手势类型：!! / /stop / 双击退格 / 双击空格 / 自定义。默认 !!。 */
    public int getInterruptAiGesture() {
        int v = INTERRUPT_GESTURE_BANGBANG;
        try { v = mClient.getInt(PREF_INTERRUPT_AI_GESTURE, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_INTERRUPT_AI_GESTURE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < INTERRUPT_GESTURE_BANGBANG || v > INTERRUPT_GESTURE_DOUBLE_SPACE) {
            v = INTERRUPT_GESTURE_BANGBANG;
        }
        return v;
    }

    public void setInterruptAiGesture(int gesture) {
        int v = gesture;
        if (v < INTERRUPT_GESTURE_BANGBANG) v = INTERRUPT_GESTURE_BANGBANG;
        if (v > INTERRUPT_GESTURE_DOUBLE_SPACE) v = INTERRUPT_GESTURE_DOUBLE_SPACE;
        try { mClient.putInt(PREF_INTERRUPT_AI_GESTURE, v); } catch (Throwable t) {
            try { mClient.putString(PREF_INTERRUPT_AI_GESTURE, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 自定义手势触发串（仅当 gesture=INTERRUPT_GESTURE_CUSTOM 时使用）。默认 "!!"。 */
    public String getInterruptAiCustomTrigger() {
        String s = null;
        try { s = mClient.getString(PREF_INTERRUPT_AI_CUSTOM_TRIGGER, null); } catch (Throwable ignored) {}
        if (s == null) s = "";
        s = s.trim();
        if (s.isEmpty()) s = "!!";
        // Keep it small and IME-safe: avoid newlines.
        s = s.replace("\n", "").replace("\r", "");
        if (s.length() > 12) s = s.substring(0, 12);
        return s;
    }

    public void setInterruptAiCustomTrigger(String trigger) {
        String s = trigger == null ? "" : trigger;
        s = s.replace("\n", "").replace("\r", "").trim();
        if (s.length() > 12) s = s.substring(0, 12);
        if (s.isEmpty()) s = "!!";
        try { mClient.putString(PREF_INTERRUPT_AI_CUSTOM_TRIGGER, s); } catch (Throwable ignored) {}
    }

    /** 打断模式：软打断缓存 / 硬打断取消请求 / 两者都做。默认“软打断缓存”。 */
    public int getInterruptAiMode() {
        int v = INTERRUPT_MODE_SOFT_BUFFER;
        try { v = mClient.getInt(PREF_INTERRUPT_AI_MODE, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_INTERRUPT_AI_MODE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < INTERRUPT_MODE_SOFT_BUFFER || v > INTERRUPT_MODE_BOTH) {
            v = INTERRUPT_MODE_SOFT_BUFFER;
        }
        return v;
    }

    public void setInterruptAiMode(int mode) {
        int v = mode;
        if (v < INTERRUPT_MODE_SOFT_BUFFER) v = INTERRUPT_MODE_SOFT_BUFFER;
        if (v > INTERRUPT_MODE_BOTH) v = INTERRUPT_MODE_BOTH;
        try { mClient.putInt(PREF_INTERRUPT_AI_MODE, v); } catch (Throwable t) {
            try { mClient.putString(PREF_INTERRUPT_AI_MODE, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    // ----------------------------------------------------------------------
    // Floating "Screenshot → Ask AI" overlay button
    // ----------------------------------------------------------------------

    public boolean getFloatingScreenshotAskEnabled() {
        try {
            return mClient.getBoolean(PREF_FLOAT_SS_ASK_ENABLED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void setFloatingScreenshotAskEnabled(boolean enabled) {
        try {
            mClient.putBoolean(PREF_FLOAT_SS_ASK_ENABLED, enabled);
        } catch (Throwable ignored) {
            try { mClient.putString(PREF_FLOAT_SS_ASK_ENABLED, String.valueOf(enabled)); } catch (Throwable ignored2) {}
        }
    }

    public int getFloatingScreenshotAskX() {
        try {
            return mClient.getInt(PREF_FLOAT_SS_ASK_X, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public int getFloatingScreenshotAskY() {
        try {
            return mClient.getInt(PREF_FLOAT_SS_ASK_Y, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public void setFloatingScreenshotAskPosition(int x, int y) {
        try {
            mClient.putInt(PREF_FLOAT_SS_ASK_X, x);
            mClient.putInt(PREF_FLOAT_SS_ASK_Y, y);
        } catch (Throwable ignored) {
            try { mClient.putString(PREF_FLOAT_SS_ASK_X, String.valueOf(x)); } catch (Throwable ignored2) {}
            try { mClient.putString(PREF_FLOAT_SS_ASK_Y, String.valueOf(y)); } catch (Throwable ignored2) {}
        }
    }

}