package tn.eluea.kgpt.ui.roles;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;

import tn.eluea.kgpt.text.parse.ParsePattern;
import tn.eluea.kgpt.text.parse.PatternType;
import tn.eluea.kgpt.ui.UiInteractor;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.ui.lab.OutputLengthOption;
import tn.eluea.kgpt.ui.lab.OutputLengthOptionAdapter;
import tn.eluea.kgpt.ui.lab.OutputLengthOptions;
import tn.eluea.kgpt.ui.lab.NormalModelThinkingOption;
import tn.eluea.kgpt.ui.lab.NormalModelThinkingOptions;
import tn.eluea.kgpt.llm.LanguageModel;

/**
 * Full-screen role editor.
 *
 * - If EXTRA_ROLE_ID is absent: create a new role.
 * - If EXTRA_ROLE_ID == "default": editable, but role name cannot be changed.
 */
public class RoleEditActivity extends AppCompatActivity {

    public static final String EXTRA_ROLE_ID = "extra_role_id";

    private SPManager sp;

    private String roleId;
    private boolean isDefaultRole;
    private boolean isEditMode;

    // Keep DEFAULT role trigger field in sync with global AI trigger symbol
    private BroadcastReceiver patternsChangedReceiver;

    // UI
    private MaterialToolbar toolbar;
    private NestedScrollView scrollRoleEdit;
    private TextView tvEmoji;
    private TextInputEditText etName;
    private TextInputEditText etTrigger;
    private TextInputEditText etPrompt;
    private TextView tvToken;
    private LinearLayout llChips;

    private TextInputEditText etExampleUser;
    private TextInputEditText etExampleAssistant;

    private SwitchCompat swPreset;
    private View presetPanel;
    private View rowPresetMemory;
    private TextView tvPresetMemoryValue;
    private View rowPresetMaxTokens;
    private TextView tvPresetMaxTokensValue;

    private SwitchCompat swOverride;
    private View overridePanel;
    private View rowOverrideModel;
    private TextView tvOverrideModelValue;
    private Slider sliderOverrideTemp;
    private TextView tvOverrideTempValue;

    private boolean dirty = false;

    // Snapshot for dirty detection
    private String initialEmoji = "🤖";
    private String initialName = "";
    private String initialTrigger = "";
    private String initialPrompt = "";
    private String initialExUser = "";
    private String initialExAsst = "";
    private boolean initialPresetEnabled = false;
    private int initialPresetMemory = -1;
    private int initialPresetMaxTokens = 0;
    private boolean initialOverrideEnabled = false;
    private String initialOverrideSubModel = "";
    private float initialOverrideTemp = -1f;

    // Current preset values (mirrored from UI)
    private int presetMemoryLevel = -1; // -1 follow global
    private int presetMaxTokens = 0;    // <=0 follow global

    private View rootRoleEdit;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener imeLayoutListener;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role_edit);

        // IMPORTANT: This screen is a heavy text editor. Prior versions had unstable IME behavior
        // (caret hidden behind keyboard, editors hard to scroll) with edge-to-edge + manual insets.
        // We prefer stable framework resize (windowSoftInputMode=adjustResize) here.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        scrollRoleEdit = findViewById(R.id.scroll_role_edit);
        rootRoleEdit = findViewById(R.id.root_role_edit);

        try {
            sp = SPManager.getInstance();
        } catch (Throwable t) {
            finish();
            return;
        }

        roleId = getIntent() != null ? getIntent().getStringExtra(EXTRA_ROLE_ID) : null;
        if (TextUtils.isEmpty(roleId)) roleId = null;
        isEditMode = roleId != null;
        isDefaultRole = RoleManager.DEFAULT_ROLE_ID.equals(roleId);

        bindViews();
        bindToolbar();
        bindEditorHelpers();
        loadRoleIntoUi();

        installImeAutoScrollWatcher();

        // Back gesture / up navigation: protect unsaved changes
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                maybeConfirmDiscard();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (rootRoleEdit != null && imeLayoutListener != null) {
                rootRoleEdit.getViewTreeObserver().removeOnGlobalLayoutListener(imeLayoutListener);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (!isDefaultRole) return;

        if (patternsChangedReceiver == null) {
            patternsChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (!intent.hasExtra(UiInteractor.EXTRA_PATTERN_LIST)) return;
                    try {
                        if (etTrigger != null) {
                            etTrigger.setText(sp.getAiTriggerSymbol());
                        }
                    } catch (Throwable ignored) {}
                }
            };
        }

        try {
            IntentFilter f = new IntentFilter(UiInteractor.ACTION_DIALOG_RESULT);
            ContextCompat.registerReceiver(getApplicationContext(), patternsChangedReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            if (patternsChangedReceiver != null) {
                getApplicationContext().unregisterReceiver(patternsChangedReceiver);
            }
        } catch (Throwable ignored) {
        }
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar_role_edit);
        if (scrollRoleEdit == null) scrollRoleEdit = findViewById(R.id.scroll_role_edit);

        // Reused from dialog_add_role.xml
        tvEmoji = findViewById(R.id.tv_role_emoji);
        etName = findViewById(R.id.et_role_name);
        etTrigger = findViewById(R.id.et_role_trigger);
        etPrompt = findViewById(R.id.et_role_prompt);
        tvToken = findViewById(R.id.tv_token_count);
        llChips = findViewById(R.id.ll_role_var_chips);

        etExampleUser = findViewById(R.id.et_role_example_user);
        etExampleAssistant = findViewById(R.id.et_role_example_assistant);

        swPreset = findViewById(R.id.sw_role_preset);
        presetPanel = findViewById(R.id.ll_role_preset_panel);
        rowPresetMemory = findViewById(R.id.row_role_preset_memory);
        tvPresetMemoryValue = findViewById(R.id.tv_role_preset_memory_value);
        rowPresetMaxTokens = findViewById(R.id.row_role_preset_max_tokens);
        tvPresetMaxTokensValue = findViewById(R.id.tv_role_preset_max_tokens_value);

        swOverride = findViewById(R.id.sw_role_override);
        overridePanel = findViewById(R.id.ll_role_override_panel);
        rowOverrideModel = findViewById(R.id.row_role_override_model);
        tvOverrideModelValue = findViewById(R.id.tv_role_override_model_value);
        sliderOverrideTemp = findViewById(R.id.slider_role_override_temp);
        tvOverrideTempValue = findViewById(R.id.tv_role_override_temp_value);
    }

    private void bindToolbar() {
        if (toolbar == null) return;

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }

        // Make the Up button behave like system back (with discard confirmation).
        toolbar.setNavigationOnClickListener(v -> maybeConfirmDiscard());

        if (isDefaultRole) {
            toolbar.setTitle("默认角色");
        } else if (isEditMode) {
            toolbar.setTitle("编辑角色");
        } else {
            toolbar.setTitle("添加角色");
        }

        // Some devices/themes still draw the toolbar too close to the status bar.
        // Apply status-bar inset as top padding (safe even when inset is 0).
        final int pL = toolbar.getPaddingLeft();
        final int pT = toolbar.getPaddingTop();
        final int pR = toolbar.getPaddingRight();
        final int pB = toolbar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(pL, pT + top, pR, pB);
            return insets;
        });
    }

    private void installImeAutoScrollWatcher() {
        if (rootRoleEdit == null) return;
        imeLayoutListener = () -> {
            try {
                Rect r = new Rect();
                rootRoleEdit.getWindowVisibleDisplayFrame(r);
                int screenHeight = rootRoleEdit.getRootView().getHeight();
                int keypadHeight = screenHeight - r.bottom;
                boolean keyboardOpen = keypadHeight > screenHeight * 0.15;
                if (!keyboardOpen) return;

                View f = getCurrentFocus();
                if (f == null) return;
                if (f == etPrompt || f == etExampleUser || f == etExampleAssistant || f == etTrigger) {
                    ensureVisibleInScroll(f);
                }
            } catch (Throwable ignored) {}
        };
        try {
            rootRoleEdit.getViewTreeObserver().addOnGlobalLayoutListener(imeLayoutListener);
        } catch (Throwable ignored) {}
    }

    private void bindEditorHelpers() {
        // Emoji picker
        bindEmojiPicker(this, tvEmoji);

        // Chips: templates + preview + variables
        bindEditorChips(this, llChips, etPrompt);

        // Token probe
        bindTokenEstimator(etPrompt, tvToken);

        // Nested scrolling inside multi-line editors (prompt & few-shot). Without this, the parent
        // NestedScrollView often intercepts gestures and the editor becomes hard to scroll.
        wireEditorNestedScroll(etPrompt);
        wireEditorNestedScroll(etExampleUser);
        wireEditorNestedScroll(etExampleAssistant);

        // Preset UI
        if (tvPresetMemoryValue != null) tvPresetMemoryValue.setText("跟随全局");
        if (tvPresetMaxTokensValue != null) tvPresetMaxTokensValue.setText("跟随全局");

        if (swPreset != null && presetPanel != null) {
            swPreset.setOnCheckedChangeListener((btn, checked) -> {
                presetPanel.setVisibility(checked ? View.VISIBLE : View.GONE);
                markDirtyIfNeeded();
            });
        }

        if (rowPresetMemory != null) {
            rowPresetMemory.setOnClickListener(v -> {
                showMemoryPicker();
            });
        }
        if (rowPresetMaxTokens != null) {
            rowPresetMaxTokens.setOnClickListener(v -> {
                showMaxTokensPicker();
            });
        }

        // Override UI
        if (tvOverrideModelValue != null) tvOverrideModelValue.setText("跟随全局");
        updateOverrideTempDesc(0.7f);
        if (sliderOverrideTemp != null) {
            try { sliderOverrideTemp.setValue(0.7f); } catch (Throwable ignored) {}
            sliderOverrideTemp.addOnChangeListener((s, value, fromUser) -> {
                updateOverrideTempDesc(value);
                markDirtyIfNeeded();
            });
        }

        if (swOverride != null && overridePanel != null) {
            swOverride.setOnCheckedChangeListener((btn, checked) -> {
                overridePanel.setVisibility(checked ? View.VISIBLE : View.GONE);
                markDirtyIfNeeded();
            });
        }

        if (rowOverrideModel != null && tvOverrideModelValue != null) {
            rowOverrideModel.setOnClickListener(v -> {
                showOverrideModelInputDialog(tvOverrideModelValue);
                markDirtyIfNeeded();
            });
        }

        // Dirty detection
        TextWatcher dirtyWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { markDirtyIfNeeded(); }
        };
        if (etName != null) etName.addTextChangedListener(dirtyWatcher);
        if (etTrigger != null) etTrigger.addTextChangedListener(dirtyWatcher);
        if (etPrompt != null) etPrompt.addTextChangedListener(dirtyWatcher);
        if (etExampleUser != null) etExampleUser.addTextChangedListener(dirtyWatcher);
        if (etExampleAssistant != null) etExampleAssistant.addTextChangedListener(dirtyWatcher);
        if (tvEmoji != null) tvEmoji.addTextChangedListener(dirtyWatcher);

        // Cursor/IME visibility: keep the caret above the keyboard while typing.
        TextWatcher editorScrollWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                View f = getCurrentFocus();
                if (f == etPrompt || f == etExampleUser || f == etExampleAssistant) {
                    ensureVisibleInScroll(f);
                }
            }
        };
        if (etPrompt != null) etPrompt.addTextChangedListener(editorScrollWatcher);
        if (etExampleUser != null) etExampleUser.addTextChangedListener(editorScrollWatcher);
        if (etExampleAssistant != null) etExampleAssistant.addTextChangedListener(editorScrollWatcher);

        // Ensure focused editor is visible above IME.
        View.OnFocusChangeListener focusScroller = (v, hasFocus) -> {
            if (hasFocus) ensureVisibleInScroll(v);
        };
        if (etTrigger != null) etTrigger.setOnFocusChangeListener(focusScroller);
        if (etPrompt != null) etPrompt.setOnFocusChangeListener(focusScroller);
        if (etExampleUser != null) etExampleUser.setOnFocusChangeListener(focusScroller);
        if (etExampleAssistant != null) etExampleAssistant.setOnFocusChangeListener(focusScroller);
    }

    private void ensureVisibleInScroll(@Nullable View target) {
        if (target == null || scrollRoleEdit == null) return;
        try {
            scrollRoleEdit.postDelayed(() -> {
                try {
                    if (target instanceof TextInputEditText) {
                        Rect caret = getCaretRect((TextInputEditText) target);
                        if (caret != null) {
                            Rect rr = new Rect(caret);
                            int pad = dp(RoleEditActivity.this, 24);
                            rr.top = Math.max(0, rr.top - pad);
                            rr.bottom = rr.bottom + pad;
                            target.requestRectangleOnScreen(rr, true);
                            return;
                        }
                    }
                    Rect r = new Rect(0, 0, target.getWidth(), target.getHeight());
                    target.requestRectangleOnScreen(r, true);
                } catch (Throwable ignored) {}
            }, 60);
        } catch (Throwable ignored) {}
    }

    private void wireEditorNestedScroll(@Nullable TextInputEditText et) {
        if (et == null) return;
        try {
            et.setOnTouchListener((v, event) -> {
                try {
                    boolean canScroll = v.canScrollVertically(1) || v.canScrollVertically(-1);
                    if (scrollRoleEdit != null) {
                        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                            scrollRoleEdit.requestDisallowInterceptTouchEvent(canScroll);
                        } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                                || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                            scrollRoleEdit.requestDisallowInterceptTouchEvent(false);
                        }
                    }
                    if (canScroll) v.getParent().requestDisallowInterceptTouchEvent(true);
                } catch (Throwable ignored) {}
                return false;
            });
        } catch (Throwable ignored) {}
    }

    @Nullable
    private static Rect getCaretRect(@NonNull TextInputEditText et) {
        try {
            int pos = et.getSelectionStart();
            if (pos < 0) return null;
            android.text.Layout layout = et.getLayout();
            if (layout == null) return null;
            int line = layout.getLineForOffset(pos);
            int top = layout.getLineTop(line);
            int bottom = layout.getLineBottom(line);
            float x = layout.getPrimaryHorizontal(pos);
            Rect r = new Rect((int) x, top, (int) x + 2, bottom);
            // Convert from layout coords to view coords.
            r.offset(et.getTotalPaddingLeft(), et.getTotalPaddingTop());
            // Add a comfortable margin.
            r.inset(-dp(et.getContext(), 24), -dp(et.getContext(), 24));
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private void updateOverrideTempDesc(float value) {
        if (tvOverrideTempValue == null) return;
        try {
            float v = Math.max(0f, Math.min(2.0f, value));
            NormalModelThinkingOption opt = NormalModelThinkingOptions.findByValue(v);
            if (opt != null) {
                tvOverrideTempValue.setText(opt.title + "\n" + opt.subtitle);
            } else {
                tvOverrideTempValue.setText(String.format(Locale.US, "%.1f", v));
            }
        } catch (Throwable ignored) {
            try { tvOverrideTempValue.setText(String.format(Locale.US, "%.1f", value)); } catch (Throwable ignored2) {}
        }
    }

    private void loadRoleIntoUi() {
        // Defaults for add mode
        if (tvEmoji != null) tvEmoji.setText("🤖");
        if (etName != null) etName.setText("");
        if (etTrigger != null) etTrigger.setText("");
        if (etPrompt != null) etPrompt.setText("");
        if (etExampleUser != null) etExampleUser.setText("");
        if (etExampleAssistant != null) etExampleAssistant.setText("");

        if (swPreset != null) swPreset.setChecked(false);
        if (presetPanel != null) presetPanel.setVisibility(View.GONE);
        presetMemoryLevel = -1;
        presetMaxTokens = 0;
        if (tvPresetMemoryValue != null) tvPresetMemoryValue.setText("跟随全局");
        if (tvPresetMaxTokensValue != null) tvPresetMaxTokensValue.setText("跟随全局");

        if (swOverride != null) swOverride.setChecked(false);
        if (overridePanel != null) overridePanel.setVisibility(View.GONE);
        if (tvOverrideModelValue != null) tvOverrideModelValue.setText("跟随全局");
        if (sliderOverrideTemp != null) {
            try { sliderOverrideTemp.setValue(0.7f); } catch (Throwable ignored) {}
        }
        updateOverrideTempDesc(0.7f);

        RoleManager.Role role = null;
        if (isEditMode) {
            String rolesJson = "";
            try { rolesJson = sp.getRolesJson(); } catch (Throwable ignored) {}
            List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson, sp);
            for (RoleManager.Role r : roles) {
                if (r != null && roleId.equals(r.id)) {
                    role = r;
                    break;
                }
            }
        }

        if (role != null) {
            if (tvEmoji != null) tvEmoji.setText(TextUtils.isEmpty(role.emoji) ? "🤖" : role.emoji);
            if (etName != null) etName.setText(role.name);
            if (etTrigger != null) etTrigger.setText(role.trigger);
            if (etPrompt != null) etPrompt.setText(role.prompt);
            if (etExampleUser != null) etExampleUser.setText(role.exampleUser);
            if (etExampleAssistant != null) etExampleAssistant.setText(role.exampleAssistant);

            if (swPreset != null) swPreset.setChecked(role.presetEnabled);
            if (presetPanel != null) presetPanel.setVisibility(role.presetEnabled ? View.VISIBLE : View.GONE);
            presetMemoryLevel = role.presetMemoryLevel;
            presetMaxTokens = role.presetMaxTokens;
            refreshPresetLabels();

            if (swOverride != null) swOverride.setChecked(role.overrideEnabled);
            if (overridePanel != null) overridePanel.setVisibility(role.overrideEnabled ? View.VISIBLE : View.GONE);
            if (tvOverrideModelValue != null) {
                if (!TextUtils.isEmpty(role.overrideSubModel)) {
                    tvOverrideModelValue.setText(role.overrideSubModel);
                } else {
                    tvOverrideModelValue.setText("跟随全局");
                }
            }
            float t = role.overrideTemperature;
            if (t < 0f || t > 2.0f) t = 0.7f;
            if (sliderOverrideTemp != null) {
                try { sliderOverrideTemp.setValue(t); } catch (Throwable ignored) {}
            }
            updateOverrideTempDesc(t);
        }

        // Default role: lock name only
        if (isDefaultRole) {
            if (etName != null) {
                etName.setEnabled(false);
                etName.setAlpha(0.7f);
            }

            // DEFAULT role trigger is the global AI trigger symbol.
            // Show it here and let the user tap to edit the global symbol.
            if (etTrigger != null) {
                try { etTrigger.setText(sp.getAiTriggerSymbol()); } catch (Throwable ignored) {}
                try {
                    etTrigger.setCursorVisible(false);
                    etTrigger.setFocusable(false);
                    etTrigger.setFocusableInTouchMode(false);
                } catch (Throwable ignored) {}
                etTrigger.setOnClickListener(v -> showEditGlobalAiTriggerSymbolDialog());
            }
        }

        // Snapshot for dirty detection
        initialEmoji = tvEmoji != null ? String.valueOf(tvEmoji.getText()).trim() : "🤖";
        initialName = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
        // DEFAULT role does not persist a role-specific trigger.
        initialTrigger = isDefaultRole ? "" : (etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "");
        initialPrompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";
        initialExUser = etExampleUser != null && etExampleUser.getText() != null ? etExampleUser.getText().toString() : "";
        initialExAsst = etExampleAssistant != null && etExampleAssistant.getText() != null ? etExampleAssistant.getText().toString() : "";
        initialPresetEnabled = swPreset != null && swPreset.isChecked();
        initialPresetMemory = presetMemoryLevel;
        initialPresetMaxTokens = presetMaxTokens;
        initialOverrideEnabled = swOverride != null && swOverride.isChecked();
        initialOverrideSubModel = (tvOverrideModelValue != null) ? String.valueOf(tvOverrideModelValue.getText()).trim() : "";
        if ("跟随全局".equals(initialOverrideSubModel)) initialOverrideSubModel = "";
        initialOverrideTemp = sliderOverrideTemp != null ? sliderOverrideTemp.getValue() : -1f;

        dirty = false;
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_role_edit, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_save_role) {
            onSaveWithLint();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onSaveWithLint() {
        final RoleDraft draft = captureDraftFromUi();
        if (!draft.isValid()) {
            Toast.makeText(this, getString(R.string.role_name_or_prompt_empty), Toast.LENGTH_SHORT).show();
            return;
        }

        // Lint: trigger conflict
        if (!TextUtils.isEmpty(draft.trigger)) {
            String rolesJson = "";
            try { rolesJson = sp.getRolesJson(); } catch (Throwable ignored) {}
            List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson, sp);
            for (RoleManager.Role r : roles) {
                if (r == null) continue;
                if (draft.roleId != null && draft.roleId.equals(r.id)) continue;
                String t = r.trigger == null ? "" : r.trigger.trim();
                if (t.isEmpty()) continue;
                if (t.equalsIgnoreCase(draft.trigger.trim())) {
                    String conflictName = r.name == null ? r.id : r.name;
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("触发词冲突")
                            .setMessage("当前触发词与角色『" + conflictName + "』重复。仍然保存吗？")
                            .setNegativeButton("返回修改", null)
                            .setPositiveButton("仍然保存", (d, w) -> doSave(draft))
                            .show();
                    return;
                }
            }
        }

        doSave(draft);
    }

    private void doSave(@NonNull RoleDraft draft) {
        try {
            if (isDefaultRole) {
                // Save default role overrides as a single JSON object
                JSONObject o = new JSONObject();
                o.put("prompt", draft.prompt);
                o.put("trigger", draft.trigger);
                o.put("emoji", draft.emoji);
                o.put("override_enabled", draft.overrideEnabled);
                o.put("override_sub_model", draft.overrideSubModel);
                o.put("override_temp", draft.overrideTemp);
                o.put("preset_enabled", draft.presetEnabled);
                o.put("preset_memory_level", draft.presetMemoryLevel);
                o.put("preset_max_tokens", draft.presetMaxTokens);
                o.put("example_user", draft.exampleUser);
                o.put("example_assistant", draft.exampleAssistant);
                sp.setDefaultRoleCustomJson(o.toString());
                Toast.makeText(this, getString(R.string.role_saved), Toast.LENGTH_SHORT).show();
                setResult(Activity.RESULT_OK);
                finish();
                return;
            }

            String rolesJson = "";
            try { rolesJson = sp.getRolesJson(); } catch (Throwable ignored) {}
            List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson, sp);

            if (!isEditMode) {
                String newId = "r_" + System.currentTimeMillis();
                RoleManager.Role newRole = new RoleManager.Role(
                        newId,
                        draft.name,
                        draft.prompt,
                        draft.trigger,
                        draft.emoji,
                        draft.overrideEnabled,
                        draft.overrideSubModel,
                        draft.overrideTemp,
                        draft.presetEnabled,
                        draft.presetMemoryLevel,
                        draft.presetMaxTokens,
                        draft.exampleUser,
                        draft.exampleAssistant
                );
                roles.add(newRole);
                sp.setRolesJson(RoleManager.serializeCustomRoles(roles));
                try { sp.setActiveRoleId(newId); } catch (Throwable ignored) {}
            } else {
                boolean replaced = false;
                for (int i = 0; i < roles.size(); i++) {
                    RoleManager.Role r = roles.get(i);
                    if (r != null && roleId.equals(r.id)) {
                        roles.set(i, new RoleManager.Role(
                                roleId,
                                draft.name,
                                draft.prompt,
                                draft.trigger,
                                draft.emoji,
                                draft.overrideEnabled,
                                draft.overrideSubModel,
                                draft.overrideTemp,
                                draft.presetEnabled,
                                draft.presetMemoryLevel,
                                draft.presetMaxTokens,
                                draft.exampleUser,
                                draft.exampleAssistant
                        ));
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    roles.add(new RoleManager.Role(
                            roleId,
                            draft.name,
                            draft.prompt,
                            draft.trigger,
                            draft.emoji,
                            draft.overrideEnabled,
                            draft.overrideSubModel,
                            draft.overrideTemp,
                            draft.presetEnabled,
                            draft.presetMemoryLevel,
                            draft.presetMaxTokens,
                            draft.exampleUser,
                            draft.exampleAssistant
                    ));
                }
                sp.setRolesJson(RoleManager.serializeCustomRoles(roles));
            }

            Toast.makeText(this, getString(R.string.role_saved), Toast.LENGTH_SHORT).show();
            setResult(Activity.RESULT_OK);
            finish();
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * DEFAULT role trigger is the global AI trigger symbol (PatternType.CommandAI).
     * Editing it here must update parse patterns and broadcast the change so other UIs/IME can sync.
     */
    private void showEditGlobalAiTriggerSymbolDialog() {
        final TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));

        String current = "$";
        try { current = sp.getAiTriggerSymbol(); } catch (Throwable ignored) {}
        input.setText(current);
        input.setHint("例如：$GPT（最多32字符）");

        new MaterialAlertDialogBuilder(this)
                .setTitle("修改默认触发符号")
                .setMessage("这个设置会同步到：AI 触发器（触发器列表）以及默认角色。")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String sym = input.getText() == null ? "" : input.getText().toString();
                    if (sym.trim().isEmpty()) {
                        Toast.makeText(this, "触发符号不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (sym.length() > 32) {
                        sym = sym.substring(0, 32);
                    }

                    try {
                        // Update PatternType.CommandAI symbol in parse patterns
                        List<ParsePattern> patterns = sp.getParsePatterns();
                        boolean found = false;
                        if (patterns != null) {
                            for (int i = 0; i < patterns.size(); i++) {
                                ParsePattern p = patterns.get(i);
                                if (p == null) continue;
                                if (p.getType() != PatternType.CommandAI) continue;
                                String newRegex = PatternType.symbolToRegex(sym, p.getType().groupCount);
                                ParsePattern updated = new ParsePattern(p.getType(), newRegex, p.getExtras());
                                updated.setEnabled(p.isEnabled());
                                patterns.set(i, updated);
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            if (patterns == null) patterns = new ArrayList<>();
                            String newRegex = PatternType.symbolToRegex(sym, PatternType.CommandAI.groupCount);
                            ParsePattern updated = new ParsePattern(PatternType.CommandAI, newRegex);
                            updated.setEnabled(true);
                            patterns.add(updated);
                        }

                        sp.setParsePatterns(patterns);

                        // Update this screen immediately (read-only display)
                        try {
                            if (etTrigger != null) etTrigger.setText(sym);
                        } catch (Throwable ignored) {}

                        // Broadcast to IME + other UIs
                        try {
                            android.content.Intent i = new android.content.Intent(UiInteractor.ACTION_DIALOG_RESULT);
                            String raw = sp.getParsePatternsRaw();
                            if (raw != null) i.putExtra(UiInteractor.EXTRA_PATTERN_LIST, raw);
                            tn.eluea.kgpt.provider.BridgeAuth.send(this, i);
                        } catch (Throwable ignored) {}

                        // Let parent refresh if needed
                        try { setResult(Activity.RESULT_OK); } catch (Throwable ignored) {}
                    } catch (Throwable t) {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private RoleDraft captureDraftFromUi() {
        String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
        String trigger = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "";
        String prompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";
        String emoji = tvEmoji != null ? String.valueOf(tvEmoji.getText()).trim() : "🤖";

        String exUser = etExampleUser != null && etExampleUser.getText() != null ? etExampleUser.getText().toString() : "";
        String exAsst = etExampleAssistant != null && etExampleAssistant.getText() != null ? etExampleAssistant.getText().toString() : "";

        boolean presetEnabled = swPreset != null && swPreset.isChecked();
        int mem = presetEnabled ? presetMemoryLevel : -1;
        int mt = presetEnabled ? presetMaxTokens : 0;

        boolean overrideEnabled = swOverride != null && swOverride.isChecked();
        String overrideSubModel = "";
        if (overrideEnabled && tvOverrideModelValue != null) {
            String v = String.valueOf(tvOverrideModelValue.getText()).trim();
            if (!TextUtils.isEmpty(v) && !"跟随全局".equals(v)) overrideSubModel = v;
        }
        float overrideTemp = -1f;
        if (overrideEnabled && sliderOverrideTemp != null) {
            try { overrideTemp = sliderOverrideTemp.getValue(); } catch (Throwable ignored) {}
        }

        // Default role name is locked
        if (isDefaultRole) name = RoleManager.DEFAULT_ROLE_NAME;

        // DEFAULT role trigger is global; do not persist a per-role trigger.
        if (isDefaultRole) trigger = "";

        return new RoleDraft(roleId, name, trigger, prompt, emoji,
                exUser, exAsst,
                presetEnabled, mem, mt,
                overrideEnabled, overrideSubModel, overrideTemp);
    }

    private void maybeConfirmDiscard() {
        if (!dirty) {
            finish();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("放弃修改？")
                .setMessage("你有未保存的修改，确定要放弃吗？")
                .setNegativeButton("继续编辑", null)
                .setNeutralButton("保存并退出", (d, w) -> onSaveWithLint())
                .setPositiveButton("放弃", (d, w) -> finish())
                .show();
    }

    private void markDirtyIfNeeded() {
        String emoji = tvEmoji != null ? String.valueOf(tvEmoji.getText()).trim() : "🤖";
        String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
        String trigger = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "";
        String prompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";
        String exUser = etExampleUser != null && etExampleUser.getText() != null ? etExampleUser.getText().toString() : "";
        String exAsst = etExampleAssistant != null && etExampleAssistant.getText() != null ? etExampleAssistant.getText().toString() : "";

        boolean presetEnabled = swPreset != null && swPreset.isChecked();
        int mem = presetEnabled ? presetMemoryLevel : -1;
        int mt = presetEnabled ? presetMaxTokens : 0;

        boolean overrideEnabled = swOverride != null && swOverride.isChecked();
        String overrideSubModel = tvOverrideModelValue != null ? String.valueOf(tvOverrideModelValue.getText()).trim() : "";
        if ("跟随全局".equals(overrideSubModel)) overrideSubModel = "";
        float overrideTemp = sliderOverrideTemp != null ? sliderOverrideTemp.getValue() : -1f;

        if (isDefaultRole) trigger = "";

        boolean changed = !safeEq(emoji, initialEmoji)
                || !safeEq(name, initialName)
                || !safeEq(trigger, initialTrigger)
                || !safeEq(prompt, initialPrompt)
                || !safeEq(exUser, initialExUser)
                || !safeEq(exAsst, initialExAsst)
                || presetEnabled != initialPresetEnabled
                || mem != initialPresetMemory
                || mt != initialPresetMaxTokens
                || overrideEnabled != initialOverrideEnabled
                || !safeEq(overrideSubModel, initialOverrideSubModel)
                || Math.abs(overrideTemp - initialOverrideTemp) > 0.0001f;

        if (changed != dirty) {
            dirty = changed;
        }
    }

    private void refreshPresetLabels() {
        if (tvPresetMemoryValue != null) {
            tvPresetMemoryValue.setText(presetMemoryLevel < 0 ? "跟随全局" : String.format(Locale.getDefault(), "%d 轮", presetMemoryLevel));
        }
        if (tvPresetMaxTokensValue != null) {
            tvPresetMaxTokensValue.setText(presetMaxTokens <= 0 ? "跟随全局" : String.valueOf(presetMaxTokens));
        }
    }


    private void showMemoryPicker() {
        // Use the same console-style dialog as the Lab memory setting UI (matrix + slider),
        // and share the same UI animation preferences so changes take effect here too.
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_conversation_memory_console, null, false);

        TextView tvStatus = content.findViewById(R.id.tv_memory_status);
        LinearLayout llBlocks = content.findViewById(R.id.ll_memory_blocks);
        android.widget.FrameLayout flBlocksContainer = content.findViewById(R.id.fl_memory_blocks_container);
        View vSweepLight = content.findViewById(R.id.v_sweep_light);
        SeekBar seekBar = content.findViewById(R.id.seek_memory_rounds);
        TextView tvRight = content.findViewById(R.id.tv_memory_right_label);
        TextView tvEst = content.findViewById(R.id.tv_token_estimation);
        TextView tvDefaultHint = content.findViewById(R.id.tv_memory_default_hint);
        TextView tvCurrent = content.findViewById(R.id.tv_current_value);
        TextView tvWarn = content.findViewById(R.id.tv_attention_warning);
        android.widget.Button btnAnimMode = content.findViewById(R.id.btn_memory_animation_mode);
        android.widget.Button btnAnimToggle = content.findViewById(R.id.btn_memory_animation_toggle);
        TextView tvAnimSpeedValue = content.findViewById(R.id.tv_memory_anim_speed_value);
        SeekBar seekAnimSpeed = content.findViewById(R.id.seek_memory_anim_speed);

        final int maxRounds = 20;
        final int warningThreshold = 16;

        if (seekBar != null) seekBar.setMax(maxRounds);
        if (tvRight != null) tvRight.setText(maxRounds + " 轮");

        // Default hint: global memory level (since "跟随全局" means follow that)
        if (tvDefaultHint != null) {
            int def = safeGlobalMemoryLevel();
            tvDefaultHint.setText("默认值：" + def + " 轮");
        }

        // Determine current selection (follow global if presetMemoryLevel < 0)
        final int[] selected = new int[]{Math.max(0, Math.min(maxRounds, (presetMemoryLevel < 0 ? safeGlobalMemoryLevel() : presetMemoryLevel)))};

        // Load persisted animation settings (shared with LabFragment)
        final int[] animMode = new int[]{getSavedMemoryAnimMode()};
        final boolean[] animEnabled = new boolean[]{getSavedMemoryAnimEnabled()};
        final int[] animSpeedPercent = new int[]{getSavedMemoryAnimSpeedPercent()};

        final ValueAnimator[] fillAnimatorRef = new ValueAnimator[1];
        final ValueAnimator[] sweepAnimatorRef = new ValueAnimator[1];

        Runnable refreshAnimControlTexts = () -> {
            if (btnAnimMode != null) {
                btnAnimMode.setText("动画选择：" + getMemoryAnimationModeLabel(animMode[0]));
            }
            if (btnAnimToggle != null) {
                btnAnimToggle.setText(animEnabled[0] ? "关闭动画" : "开启动画");
            }
            if (tvAnimSpeedValue != null) {
                tvAnimSpeedValue.setText(getAnimSpeedPercentLabel(animSpeedPercent[0]));
            }
            if (seekAnimSpeed != null && seekAnimSpeed.getProgress() != clampPercent(animSpeedPercent[0])) {
                seekAnimSpeed.setProgress(clampPercent(animSpeedPercent[0]));
            }
        };

        Runnable renderStatic = () -> {
            updateMemoryConsoleLabels(tvStatus, tvEst, tvWarn, tvCurrent, selected[0]);
            cancelMemoryFillAnimator(fillAnimatorRef);
            cancelMemorySweepAnimator(sweepAnimatorRef);
            renderMemoryBlockMatrix(llBlocks, selected[0], getMemorySpectrumColor(selected[0]), maxRounds);
            setSweepLightHidden(vSweepLight);
        };

        final Runnable[] restartVisualAnimatorRef = new Runnable[1];
        restartVisualAnimatorRef[0] = () -> {
            cancelMemoryFillAnimator(fillAnimatorRef);
            cancelMemorySweepAnimator(sweepAnimatorRef);
            if (!animEnabled[0]) {
                renderMemoryBlockMatrix(llBlocks, selected[0], getMemorySpectrumColor(selected[0]), maxRounds);
                setSweepLightHidden(vSweepLight);
                return;
            }
            startMemoryVisualAnimatorByMode(fillAnimatorRef,
                    sweepAnimatorRef,
                    llBlocks,
                    flBlocksContainer,
                    vSweepLight,
                    selected[0],
                    maxRounds,
                    animMode[0],
                    animSpeedPercent[0]);
        };

        // Initial render
        updateMemoryConsoleLabels(tvStatus, tvEst, tvWarn, tvCurrent, selected[0]);
        renderMemoryBlockMatrix(llBlocks, selected[0], getMemorySpectrumColor(selected[0]), maxRounds);
        setSweepLightHidden(vSweepLight);
        refreshAnimControlTexts.run();
        if (animEnabled[0]) {
            restartVisualAnimatorRef[0].run();
        }


        if (seekBar != null) {
            seekBar.setProgress(selected[0]);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (progress < 0) progress = 0;
                    if (progress > maxRounds) progress = maxRounds;
                    selected[0] = progress;
                    updateMemoryConsoleLabels(tvStatus, tvEst, tvWarn, tvCurrent, selected[0]);
                    if (fromUser) {
                        cancelMemoryFillAnimator(fillAnimatorRef);
                        cancelMemorySweepAnimator(sweepAnimatorRef);
                        renderMemoryBlockMatrix(llBlocks, selected[0], getMemorySpectrumColor(selected[0]), maxRounds);
                        setSweepLightHidden(vSweepLight);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                    cancelMemoryFillAnimator(fillAnimatorRef);
                    cancelMemorySweepAnimator(sweepAnimatorRef);
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    if (animEnabled[0]) {
                        restartVisualAnimatorRef[0].run();
                    } else {
                        renderStatic.run();
                    }
                }
            });
        }

        if (btnAnimMode != null) {
            btnAnimMode.setOnClickListener(v -> {
                final String[] items = new String[]{"逐格充能循环", "三段式机械节", "流光扫掠"};
                new MaterialAlertDialogBuilder(this)
                        .setTitle("动画选择")
                        .setSingleChoiceItems(items, animMode[0], (d, which) -> {
                            if (which < 0 || which > 2) return;
                            animMode[0] = which;
                            putUiAnimInt(PREF_MEMORY_ANIM_MODE, animMode[0]);
                            refreshAnimControlTexts.run();
                            if (animEnabled[0]) restartVisualAnimatorRef[0].run();
                            else renderStatic.run();
                            d.dismiss();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }

        if (btnAnimToggle != null) {
            btnAnimToggle.setOnClickListener(v -> {
                animEnabled[0] = !animEnabled[0];
                putUiAnimBool(PREF_MEMORY_ANIM_ENABLED, animEnabled[0]);
                refreshAnimControlTexts.run();
                if (animEnabled[0]) {
                    restartVisualAnimatorRef[0].run();
                } else {
                    renderStatic.run();
                }
            });
        }

        if (seekAnimSpeed != null) {
            seekAnimSpeed.setMax(100);
            seekAnimSpeed.setProgress(clampPercent(animSpeedPercent[0]));
            seekAnimSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    animSpeedPercent[0] = clampPercent(progress);
                    if (tvAnimSpeedValue != null) tvAnimSpeedValue.setText(getAnimSpeedPercentLabel(animSpeedPercent[0]));
                    putUiAnimInt(PREF_MEMORY_ANIM_SPEED, animSpeedPercent[0]);
                }

                @Override public void onStartTrackingTouch(SeekBar sb) { }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    if (animEnabled[0]) {
                        restartVisualAnimatorRef[0].run();
                    }
                }
            });
        }

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ui_dialogue_memory)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("跟随全局", (d, w) -> {
                    presetMemoryLevel = -1;
                    refreshPresetLabels();
                    markDirtyIfNeeded();
                })
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    presetMemoryLevel = selected[0];
                    refreshPresetLabels();
                    markDirtyIfNeeded();
                })
                .create();

        dialog.setOnDismissListener(d -> {
            cancelMemoryFillAnimator(fillAnimatorRef);
            cancelMemorySweepAnimator(sweepAnimatorRef);
            setSweepLightHidden(vSweepLight);
        });
        dialog.show();
    }

    private int safeGlobalMemoryLevel() {
        try {
            int v = sp != null ? sp.getConversationMemoryLevel() : 0;
            if (v < 0) v = 0;
            if (v > 20) v = 20;
            return v;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int safeGlobalMaxTokens() {
        try {
            int v = sp != null ? sp.getMaxTokensLimit() : 2048;
            if (v < 0) v = 0;
            return v;
        } catch (Throwable ignored) {
            return 2048;
        }
    }

    private void initMemoryBlocks(@Nullable LinearLayout ll, int maxRounds) {
        if (ll == null) return;
        if (ll.getChildCount() >= maxRounds) return;
        ll.removeAllViews();
        for (int i = 0; i < maxRounds; i++) {
            View b = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT);
            lp.weight = 1f;
            lp.setMarginEnd(dp(this, 3));
            b.setLayoutParams(lp);
            ll.addView(b);
        }
    }

    private void renderMemoryBlocks(@Nullable LinearLayout ll, int rounds, int activeColor) {
        if (ll == null) return;
        int n = ll.getChildCount();
        for (int i = 0; i < n; i++) {
            View b = ll.getChildAt(i);
            if (b == null) continue;
            boolean on = i < rounds;
            b.setBackgroundColor(on ? activeColor : 0xFF1E1E1E);
            b.setAlpha(on ? 1f : 0.22f);
        }
    }

    private int getMemorySpectrumColor(int rounds) {
        // Rough spectrum mapping; keeps the "console" looking vivid.
        if (rounds <= 0) return 0xFF9E9E9E;
        if (rounds <= 3) return 0xFF4CAF50;
        if (rounds <= 6) return 0xFF2196F3;
        if (rounds <= 10) return 0xFF9C27B0;
        if (rounds <= 15) return 0xFFFF9800;
        return 0xFFE53935;
    }

    private void updateMemoryConsoleLabels(@Nullable TextView tvStatus,
                                           @Nullable TextView tvEst,
                                           @Nullable TextView tvWarn,
                                           @Nullable TextView tvCurrent,
                                           int rounds) {
        if (tvStatus != null) {
            String tag;
            if (rounds <= 1) tag = "[🧊 极简快答]";
            else if (rounds <= 5) tag = "[✨ 日常活跃]";
            else if (rounds <= 10) tag = "[📚 深度对话]";
            else tag = "[🔥 高记忆消耗]";
            tvStatus.setText("当前记忆轮数：" + rounds + " 轮  ·  " + tag);
        }

        if (tvCurrent != null) {
            tvCurrent.setText("当前设定：" + rounds + " 轮");
        }

        if (tvEst != null) {
            int est = 800 + rounds * 400;
            tvEst.setText("[📊 算力估值] 携带上下文将消耗 ~" + est + " Tokens/次。");
        }

        if (tvWarn != null) {
            tvWarn.setVisibility(rounds >= 16 ? View.VISIBLE : View.GONE);
        }
    }


    // ===== Conversation memory console animations (shared with Lab screen) =====

    private static final String PREFS_UI_ANIM = "lab_ui_anim_prefs";
    private static final int DEFAULT_UI_ANIM_SPEED_PERCENT = 70;

    private static final int MEMORY_ANIM_MODE_SEQ_LOOP = 0;
    private static final int MEMORY_ANIM_MODE_TRI_PHASE = 1;
    private static final int MEMORY_ANIM_MODE_SWEEP = 2;

    private static final String PREF_MEMORY_ANIM_MODE = "memory_anim_mode";
    private static final String PREF_MEMORY_ANIM_ENABLED = "memory_anim_enabled";
    private static final String PREF_MEMORY_ANIM_SPEED = "memory_anim_speed";

    @Nullable
    private android.content.SharedPreferences getUiAnimPrefs() {
        try {
            return getSharedPreferences(PREFS_UI_ANIM, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int clampPercent(int percent) {
        int p = percent;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        return p;
    }

    private int getUiAnimInt(@NonNull String key, int defValue) {
        try {
            android.content.SharedPreferences p = getUiAnimPrefs();
            if (p == null) return defValue;
            return p.getInt(key, defValue);
        } catch (Throwable ignored) {
            return defValue;
        }
    }

    private boolean getUiAnimBool(@NonNull String key, boolean defValue) {
        try {
            android.content.SharedPreferences p = getUiAnimPrefs();
            if (p == null) return defValue;
            return p.getBoolean(key, defValue);
        } catch (Throwable ignored) {
            return defValue;
        }
    }

    private void putUiAnimInt(@NonNull String key, int value) {
        try {
            android.content.SharedPreferences p = getUiAnimPrefs();
            if (p != null) p.edit().putInt(key, value).apply();
        } catch (Throwable ignored) {}
    }

    private void putUiAnimBool(@NonNull String key, boolean value) {
        try {
            android.content.SharedPreferences p = getUiAnimPrefs();
            if (p != null) p.edit().putBoolean(key, value).apply();
        } catch (Throwable ignored) {}
    }

    private int getSavedMemoryAnimMode() {
        int mode = getUiAnimInt(PREF_MEMORY_ANIM_MODE, MEMORY_ANIM_MODE_SEQ_LOOP);
        if (mode < 0 || mode > 2) mode = MEMORY_ANIM_MODE_SEQ_LOOP;
        return mode;
    }

    private boolean getSavedMemoryAnimEnabled() {
        return getUiAnimBool(PREF_MEMORY_ANIM_ENABLED, true);
    }

    private int getSavedMemoryAnimSpeedPercent() {
        return clampPercent(getUiAnimInt(PREF_MEMORY_ANIM_SPEED, DEFAULT_UI_ANIM_SPEED_PERCENT));
    }

    private String getAnimSpeedPercentLabel(int percent) {
        return clampPercent(percent) + "%（周期倍率）";
    }

    private String getMemoryAnimationModeLabel(int mode) {
        if (mode == MEMORY_ANIM_MODE_TRI_PHASE) return "三段机械节";
        if (mode == MEMORY_ANIM_MODE_SWEEP) return "流光扫掠";
        return "逐格充能循环";
    }

    private long scaleAnimDurationByPercent(long baseMs, int speedPercent, long minMs, long maxMs) {
        long base = Math.max(1L, baseMs);
        int p = clampPercent(speedPercent);
        // 100% = base speed, 0% = slower (~2x duration)
        float scale = 2.0f - (p / 100f);
        long out = (long) (base * scale);
        if (minMs > 0 && out < minMs) out = minMs;
        if (maxMs > 0 && out > maxMs) out = maxMs;
        return out;
    }

    private void cancelMemoryFillAnimator(@Nullable ValueAnimator[] animatorRef) {
        if (animatorRef == null || animatorRef.length == 0) return;
        try { if (animatorRef[0] != null) animatorRef[0].cancel(); } catch (Throwable ignored) {}
        animatorRef[0] = null;
    }

    private void cancelMemorySweepAnimator(@Nullable ValueAnimator[] animatorRef) {
        if (animatorRef == null || animatorRef.length == 0) return;
        try { if (animatorRef[0] != null) animatorRef[0].cancel(); } catch (Throwable ignored) {}
        animatorRef[0] = null;
    }

    private void setSweepLightHidden(@Nullable View vSweepLight) {
        if (vSweepLight == null) return;
        try {
            vSweepLight.setVisibility(View.GONE);
            vSweepLight.setTranslationX(0f);
        } catch (Throwable ignored) {}
    }

    private void startMemoryVisualAnimatorByMode(@Nullable ValueAnimator[] fillAnimatorRef,
                                                 @Nullable ValueAnimator[] sweepAnimatorRef,
                                                 @Nullable LinearLayout llBlocks,
                                                 @Nullable android.widget.FrameLayout flBlocksContainer,
                                                 @Nullable View vSweepLight,
                                                 int targetValue,
                                                 int maxRounds,
                                                 int mode,
                                                 int speedPercent) {
        if (mode == MEMORY_ANIM_MODE_SWEEP) {
            playMemorySweepLightAnimation(sweepAnimatorRef, llBlocks, flBlocksContainer, vSweepLight, targetValue, maxRounds, speedPercent);
            return;
        }
        setSweepLightHidden(vSweepLight);
        if (mode == MEMORY_ANIM_MODE_TRI_PHASE) {
            playTriPhaseMemoryBreathAnimation(fillAnimatorRef, llBlocks, targetValue, maxRounds, speedPercent);
        } else {
            playSequentialMemoryFillAnimation(fillAnimatorRef, llBlocks, targetValue, maxRounds, speedPercent);
        }
    }

    private void playSequentialMemoryFillAnimation(@Nullable ValueAnimator[] animatorRef,
                                                   @Nullable LinearLayout llBlocks,
                                                   int targetValue,
                                                   int maxRounds,
                                                   int speedPercent) {
        int target = targetValue;
        if (target < 0) target = 0;
        if (target > maxRounds) target = maxRounds;

        cancelMemoryFillAnimator(animatorRef);

        if (llBlocks == null) return;

        if (target <= 0) {
            renderMemoryBlockMatrix(llBlocks, 0, getMemorySpectrumColor(0), maxRounds);
            return;
        }

        long duration = Math.max(800L, Math.min(2000L, target * 100L));
        ValueAnimator animator = ValueAnimator.ofInt(0, target);
        animator.setDuration(scaleAnimDurationByPercent(duration, speedPercent, 800L, 5000L));
        animator.setInterpolator(new DecelerateInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        final int finalTarget = target;
        final int finalActiveColor = getMemorySpectrumColor(finalTarget);
        animator.addUpdateListener(animation -> {
            Object value = animation.getAnimatedValue();
            int animated = 0;
            if (value instanceof Integer) animated = (Integer) value;
            if (animated < 0) animated = 0;
            if (animated > finalTarget) animated = finalTarget;
            renderMemoryBlockMatrix(llBlocks, animated, finalActiveColor, maxRounds);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                    animatorRef[0] = null;
                }
            }
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                    animatorRef[0] = null;
                }
            }
        });
        if (animatorRef != null && animatorRef.length > 0) animatorRef[0] = animator;
        animator.start();
    }

    private void playTriPhaseMemoryBreathAnimation(@Nullable ValueAnimator[] animatorRef,
                                                   @Nullable LinearLayout llBlocks,
                                                   int targetValue,
                                                   int maxRounds,
                                                   int speedPercent) {
        int target = targetValue;
        if (target < 0) target = 0;
        if (target > maxRounds) target = maxRounds;

        cancelMemoryFillAnimator(animatorRef);

        if (llBlocks == null) return;
        if (target <= 0) {
            renderMemoryBlockMatrix(llBlocks, 0, getMemorySpectrumColor(0), maxRounds);
            return;
        }

        final int finalTarget = target;
        final int finalActiveColor = getMemorySpectrumColor(finalTarget);
        final long duration = 1800L;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(scaleAnimDurationByPercent(duration, speedPercent, 1000L, 6000L));
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(animation -> {
            Object value = animation.getAnimatedValue();
            float phase = (value instanceof Float) ? (Float) value : 0f;
            if (phase < 0f) phase = 0f;
            if (phase > 1f) phase = 1f;

            float ratio;
            if (phase <= 0.58f) {
                float t = phase / 0.58f;
                float eased = 1f - (1f - t) * (1f - t);
                ratio = eased;
            } else if (phase <= 0.78f) {
                ratio = 1f;
            } else {
                float t = (phase - 0.78f) / 0.22f;
                if (t <= 0.45f) {
                    ratio = 1f - (0.18f * (t / 0.45f));
                } else {
                    float b = (t - 0.45f) / 0.55f;
                    ratio = 0.82f + (0.08f * (float) Math.sin(b * Math.PI));
                }
            }

            int lit = Math.round(finalTarget * ratio);
            if (lit < 1) lit = 1;
            if (lit > finalTarget) lit = finalTarget;
            renderMemoryBlockMatrix(llBlocks, lit, finalActiveColor, maxRounds);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                    animatorRef[0] = null;
                }
            }
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                    animatorRef[0] = null;
                }
            }
        });
        if (animatorRef != null && animatorRef.length > 0) animatorRef[0] = animator;
        animator.start();
    }

    private void playMemorySweepLightAnimation(@Nullable ValueAnimator[] animatorRef,
                                               @Nullable LinearLayout llBlocks,
                                               @Nullable android.widget.FrameLayout flBlocksContainer,
                                               @Nullable View vSweepLight,
                                               int targetValue,
                                               int maxRounds,
                                               int speedPercent) {
        int target = targetValue;
        if (target < 0) target = 0;
        if (target > maxRounds) target = maxRounds;

        cancelMemorySweepAnimator(animatorRef);

        if (llBlocks == null || vSweepLight == null) {
            renderMemoryBlockMatrix(llBlocks, target, getMemorySpectrumColor(target), maxRounds);
            setSweepLightHidden(vSweepLight);
            return;
        }

        renderMemoryBlockMatrix(llBlocks, target, getMemorySpectrumColor(target), maxRounds);

        if (target <= 0) {
            setSweepLightHidden(vSweepLight);
            return;
        }

        final int finalTarget = target;
        final int finalMaxRounds = Math.max(1, maxRounds);
        final View finalSweepLight = vSweepLight;
        llBlocks.post(() -> {
            int blocksWidth = llBlocks.getWidth();
            if (blocksWidth <= 0 && flBlocksContainer != null) {
                blocksWidth = flBlocksContainer.getWidth();
            }
            if (blocksWidth <= 0) {
                setSweepLightHidden(finalSweepLight);
                return;
            }
            float activeWidth = (finalTarget / (float) finalMaxRounds) * blocksWidth;
            if (activeWidth <= 0f) {
                setSweepLightHidden(finalSweepLight);
                return;
            }
            finalSweepLight.setVisibility(View.VISIBLE);
            finalSweepLight.setAlpha(0.92f);
            int sweepWidthPx = finalSweepLight.getWidth();
            if (sweepWidthPx <= 0) {
                sweepWidthPx = dp(this, 20);
                try {
                    ViewGroup.LayoutParams lp = finalSweepLight.getLayoutParams();
                    if (lp != null) {
                        lp.width = sweepWidthPx;
                        finalSweepLight.setLayoutParams(lp);
                    }
                } catch (Throwable ignored) { }
            }
            float startX = -sweepWidthPx;
            float endX = Math.max(0f, activeWidth);
            ValueAnimator animator = ValueAnimator.ofFloat(startX, endX);
            animator.setDuration(scaleAnimDurationByPercent(1400L, speedPercent, 700L, 6000L));
            animator.setInterpolator(new android.view.animation.LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.addUpdateListener(animation -> {
                Object value = animation.getAnimatedValue();
                float x = (value instanceof Float) ? (Float) value : startX;
                finalSweepLight.setTranslationX(x);
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    setSweepLightHidden(finalSweepLight);
                    if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                        animatorRef[0] = null;
                    }
                }
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                        animatorRef[0] = null;
                    }
                }
            });
            if (animatorRef != null && animatorRef.length > 0) animatorRef[0] = animator;
            animator.start();
        });
    }

    private void renderMemoryBlockMatrix(@Nullable LinearLayout llBlocks,
                                         int litBlocks,
                                         int activeColor,
                                         int maxRounds) {
        if (llBlocks == null) return;

        int totalBlocks = 20;
        int n = litBlocks;
        if (n < 0) n = 0;
        if (n > totalBlocks) n = totalBlocks;

        final int inactiveTrackColor = 0xFF2F2F2F;
        Context ctx = llBlocks.getContext();
        llBlocks.setOrientation(LinearLayout.HORIZONTAL);

        int blockHeight = dp(this, 16);
        int margin = dp(this, 1);

        if (llBlocks.getChildCount() != totalBlocks) {
            llBlocks.removeAllViews();
            for (int i = 0; i < totalBlocks; i++) {
                View block = new View(ctx);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                bg.setCornerRadius(dp(this, 3));
                bg.setColor(inactiveTrackColor);
                block.setBackground(bg);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, blockHeight, 1f);
                if (i > 0) lp.leftMargin = margin;
                if (i < totalBlocks - 1) lp.rightMargin = margin;
                block.setLayoutParams(lp);
                llBlocks.addView(block);
            }
        }

        for (int i = 0; i < totalBlocks; i++) {
            View block = llBlocks.getChildAt(i);
            if (block == null) continue;
            android.graphics.drawable.Drawable drawable = block.getBackground();
            int blockColor = (i < n) ? activeColor : inactiveTrackColor;
            if (drawable instanceof android.graphics.drawable.GradientDrawable) {
                try {
                    ((android.graphics.drawable.GradientDrawable) drawable).setColor(blockColor);
                } catch (Throwable ignored) {
                    block.setBackgroundColor(blockColor);
                }
            } else {
                block.setBackgroundColor(blockColor);
            }
        }
    }


    private void showMaxTokensPicker() {
        // Use the same rich option list UI as the Lab "输出长度" dialog.
        final List<OutputLengthOption> options = new ArrayList<>(
                OutputLengthOptions.buildOptions(
                        getString(R.string.ui_output_length_custom),
                        getString(R.string.ui_output_length_custom_subtitle))
        );

        final int customIndex = options.size() - 1;

        // Determine initial selection.
        int initialIndex = -1;
        int curTokens = presetMaxTokens;
        if (curTokens <= 0) {
            // Follow global -> preselect current global bucket for better UX.
            curTokens = safeGlobalMaxTokens();
        }
        for (int i = 0; i < options.size(); i++) {
            OutputLengthOption o = options.get(i);
            if (o != null && !o.isCustom && o.tokens == curTokens) {
                initialIndex = i;
                break;
            }
        }
        if (initialIndex < 0) initialIndex = customIndex;

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_output_length, null);
        RecyclerView rv = content.findViewById(R.id.rv_output_length_options);
        TextInputLayout tilCustom = content.findViewById(R.id.til_custom_tokens);
        TextInputEditText etCustom = content.findViewById(R.id.et_custom_tokens);
        TextView tvHint = content.findViewById(R.id.tv_custom_tokens_hint);
        TextView tvDialogNotice = content.findViewById(R.id.tv_output_length_notice);
        TextView tvCustomCapNotice = content.findViewById(R.id.tv_custom_tokens_cap_notice);

        if (tvDialogNotice != null) {
            tvDialogNotice.setText("为该角色覆盖输出长度。点『跟随全局』可恢复为全局设置。");
        }
        if (tvCustomCapNotice != null) tvCustomCapNotice.setVisibility(View.GONE);

        final int[] selected = new int[]{initialIndex};
        final int initialCustomTokens = (selected[0] == customIndex) ? Math.max(0, curTokens) : 0;

        final OutputLengthOptionAdapter[] adapterRef = new OutputLengthOptionAdapter[1];
        adapterRef[0] = new OutputLengthOptionAdapter(options, initialIndex, initialCustomTokens, pos -> {
            selected[0] = pos;
            if (adapterRef[0] != null) adapterRef[0].setSelectedIndex(pos);
            boolean isCustom = (pos == customIndex);
            if (tilCustom != null) tilCustom.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            if (tvHint != null) tvHint.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            if (isCustom && etCustom != null) etCustom.requestFocus();
        });

        if (rv != null) {
            rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
            rv.setAdapter(adapterRef[0]);
        }

        boolean startCustom = (initialIndex == customIndex);
        if (tilCustom != null) tilCustom.setVisibility(startCustom ? View.VISIBLE : View.GONE);
        if (tvHint != null) tvHint.setVisibility(startCustom ? View.VISIBLE : View.GONE);

        if (etCustom != null) {
            if (startCustom && curTokens > 0) {
                etCustom.setText(String.valueOf(curTokens));
                try { etCustom.setSelection(etCustom.getText() == null ? 0 : etCustom.getText().length()); } catch (Throwable ignored) {}
            }
            if (tvHint != null) updateCustomApproxText(tvHint, etCustom.getText() == null ? "" : etCustom.getText().toString());

            etCustom.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (tvHint != null) updateCustomApproxText(tvHint, s == null ? "" : s.toString());
                    int parsed = 0;
                    try {
                        String raw = s == null ? "" : s.toString();
                        if (!raw.trim().isEmpty()) parsed = Integer.parseInt(raw.trim());
                    } catch (Throwable ignored) { parsed = 0; }
                    if (adapterRef[0] != null) adapterRef[0].setCustomTokensValue(parsed);
                    if (tilCustom != null) {
                        try { tilCustom.setError(null); } catch (Throwable ignored) {}
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ui_output_length)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("跟随全局", null)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialog.setOnShowListener(dlg -> {
            android.widget.Button ok = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            android.widget.Button follow = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);

            if (follow != null) {
                follow.setOnClickListener(v -> {
                    presetMaxTokens = 0;
                    refreshPresetLabels();
                    markDirtyIfNeeded();
                    dialog.dismiss();
                });
            }

            if (ok != null) {
                ok.setOnClickListener(v -> {
                    int chosen;
                    if (selected[0] == customIndex) {
                        String raw = (etCustom == null || etCustom.getText() == null) ? "" : etCustom.getText().toString();
                        int parsed = 0;
                        try {
                            if (!raw.trim().isEmpty()) parsed = Integer.parseInt(raw.trim());
                        } catch (Throwable ignored) { parsed = 0; }
                        if (parsed <= 0) {
                            try {
                                if (tilCustom != null) tilCustom.setError(getString(R.string.ui_invalid_value));
                            } catch (Throwable ignored) {}
                            Toast.makeText(RoleEditActivity.this, getString(R.string.ui_invalid_value), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        chosen = parsed;
                    } else {
                        if (selected[0] < 0 || selected[0] >= options.size()) return;
                        OutputLengthOption opt = options.get(selected[0]);
                        chosen = opt == null ? 0 : opt.tokens;
                    }

                    presetMaxTokens = chosen;
                    refreshPresetLabels();
                    markDirtyIfNeeded();
                    dialog.dismiss();
                });
            }
        });

        dialog.show();
    }

    private void updateCustomApproxText(@NonNull TextView tvHint, @NonNull String raw) {
        int tokens = 0;
        try {
            if (!raw.trim().isEmpty()) tokens = Integer.parseInt(raw.trim());
        } catch (Throwable ignored) { tokens = 0; }

        if (tokens <= 0) {
            tvHint.setText("请输入一个正整数 Token 值");
            return;
        }

        // Rough approx: align with the labels in OutputLengthOptions.
        int approxZh = (int) Math.max(1, Math.round(tokens * 0.78f));
        int approxEn = (int) Math.max(1, Math.round(tokens * 0.74f));
        tvHint.setText("约 " + approxZh + " 字 / " + approxEn + " 词（粗略估算）");
    }

    private void showPreviewDialog() {
        RoleDraft d = captureDraftFromUi();

        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(d.prompt)) {
            sb.append(d.prompt.trim());
        }
        if (!TextUtils.isEmpty(d.exampleUser) || !TextUtils.isEmpty(d.exampleAssistant)) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("## Few-shot Examples（参考）\n");
            sb.append("以下示例用于引导回答风格/格式（不要求逐字照搬）：\n");
            if (!TextUtils.isEmpty(d.exampleUser)) sb.append("- 用户：").append(d.exampleUser.trim()).append("\n");
            if (!TextUtils.isEmpty(d.exampleAssistant)) sb.append("- 助手：").append(d.exampleAssistant.trim()).append("\n");
        }
        if (sb.length() > 0) sb.append("\n\n### Task\n");
        sb.append("（这里是系统 Task 占位，发送请求时会替换为真实任务）");

        String rendered = RoleManager.renderTimeDatePlaceholders(sb.toString());

        TextView tv = new TextView(this);
        tv.setText(rendered);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new MaterialAlertDialogBuilder(this)
                .setTitle("预览：最终系统提示词")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showTemplatePicker() {
        final String[] titles = new String[]{
                "翻译助手",
                "代码审查",
                "润色助手",
                "学习教练",
                "结构化 JSON 输出"
        };
        final String[] templates = new String[]{
                "你是专业翻译助手。保持原意、语气自然。\n- 输入是中文：翻译成英文\n- 输入是英文：翻译成中文\n只输出翻译结果，不要解释。",
                "你是严谨的代码审查助手。\n请指出：潜在 bug、性能问题、可读性、边界条件，并给出改进建议与更优实现。\n输出用要点列表。",
                "你是专业写作润色助手。\n请在不改变含义的前提下提升：表达清晰度、逻辑、语气一致性。\n输出：润色后的正文（不需要解释）。",
                "你是耐心的学习教练。\n请用：概念解释 → 例子 → 小练习 → 纠错 的方式教学。\n默认用中文。",
                "你是结构化输出助手。\n请严格以 JSON 输出，字段包含：summary, key_points, action_items。\n不要输出多余文本。"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("选择模板")
                .setItems(titles, (d, which) -> {
                    if (etPrompt != null) {
                        etPrompt.setText(templates[which]);
                        try { etPrompt.setSelection(etPrompt.getText() != null ? etPrompt.getText().length() : 0); } catch (Throwable ignored) {}
                    }
                    markDirtyIfNeeded();
                })
                .show();
    }

    private static boolean safeEq(@Nullable String a, @Nullable String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    // ===== Reused helpers from old RolesManagerUi (kept local for Activity use) =====

    private static void bindEmojiPicker(@NonNull Context context, @Nullable TextView tvEmoji) {
        if (tvEmoji == null) return;
        tvEmoji.setOnClickListener(v -> {
            // Grid picker: 6 columns.
            RecyclerView rv = new RecyclerView(context);
            rv.setLayoutManager(new GridLayoutManager(context, 6));
            rv.setOverScrollMode(View.OVER_SCROLL_NEVER);

            final String[] emojis = new String[]{
                    // Robot / tech
                    "🤖","🧠","💻","🖥️","⌨️","🖱️","📱","🛰️","🧬","🔬","🧪","⚙️",
                    // Writing / study
                    "📝","✍️","📚","📖","📌","📎","🗂️","🧾","🧠","🧩","🧮","📐",
                    // Art / design
                    "🎨","🖌️","🧵","🎭","🎬","📸","🎧","🎼","🎹","🎻","🎷","🕺",
                    // Business / data
                    "📊","📈","📉","💡","🎯","🏷️","🧾","💰","🧱","🧲","🧰","🛠️",
                    // Fun / mood
                    "😎","🤩","🥳","🤔","🫠","😴","🫶","🔥","⚡","🌈","🌙","🌌",
                    // Roles / professions
                    "🕵️","🧙","🧑‍💻","👩‍💻","👨‍💻","👨‍🏫","👩‍🏫","👨‍⚕️","👩‍⚕️","👨‍🍳","👩‍🍳","🧑‍🚀",
                    // Targets
                    "🧭","🗺️","🚀","🛸","🏁","🏆","🥇","🧗","🏋️","🏄","🚴","🧘",
                    // Symbols
                    "✅","🟣","🔵","🟢","🟡","🟠","🔴","⭐","✨","💎","🎁","🎲"
            };

            final androidx.appcompat.app.AlertDialog[] dialogRef = new androidx.appcompat.app.AlertDialog[1];
            rv.setAdapter(new EmojiGridAdapter(emojis, picked -> {
                try { tvEmoji.setText(picked); } catch (Throwable ignored) {}
            }, () -> {
                try { if (dialogRef[0] != null) dialogRef[0].dismiss(); } catch (Throwable ignored) {}
            }));

            int pad = dp(context, 10);
            rv.setPadding(pad, pad, pad, pad);
            rv.setClipToPadding(false);

            dialogRef[0] = new MaterialAlertDialogBuilder(context)
                    .setTitle("选择头像")
                    .setView(rv)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialogRef[0].show();
        });
    }

    private interface EmojiPickListener { void onPick(@NonNull String emoji); }

    private static class EmojiGridAdapter extends RecyclerView.Adapter<EmojiGridAdapter.VH> {

        private final String[] items;
        private final EmojiPickListener listener;
        private final Runnable dismiss;

        EmojiGridAdapter(String[] items, EmojiPickListener listener, Runnable dismiss) {
            this.items = items == null ? new String[0] : items;
            this.listener = listener;
            this.dismiss = dismiss;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            int size = dp(parent.getContext(), 44);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
            tv.setLayoutParams(lp);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextSize(18);
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String e = items[position];
            holder.tv.setText(e);
            holder.tv.setOnClickListener(v -> {
                if (listener != null) listener.onPick(e);
                try { if (dismiss != null) dismiss.run(); } catch (Throwable ignored) {}
            });
        }

        @Override
        public int getItemCount() {
            return items.length;
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tv;
            VH(@NonNull View itemView) {
                super(itemView);
                tv = (TextView) itemView;
            }
        }
    }

    private void bindEditorChips(@NonNull Context context,
                                @Nullable LinearLayout container,
                                @Nullable TextInputEditText etPrompt) {
        if (container == null || etPrompt == null) return;
        container.removeAllViews();

        addActionChip(context, container, "📚 模板", this::showTemplatePicker);
        addActionChip(context, container, "👁 预览", this::showPreviewDialog);

        addVarChip(context, container, "✂️ 剪贴板", "%clipboard%", etPrompt);
        addVarChip(context, container, "📝 选中文本", "%selected%", etPrompt);
        addVarChip(context, container, "⏱️ 时间", "[time]", etPrompt);
        addVarChip(context, container, "📅 日期", "[date]", etPrompt);
    }

    private static void addActionChip(@NonNull Context context,
                                     @NonNull LinearLayout container,
                                     @NonNull String label,
                                     @NonNull Runnable action) {
        TextView tv = createChipView(context, label);
        tv.setOnClickListener(v -> {
            try { action.run(); } catch (Throwable ignored) {}
        });
        container.addView(tv);
    }

    private static void addVarChip(@NonNull Context context,
                                   @NonNull LinearLayout container,
                                   @NonNull String label,
                                   @NonNull String token,
                                   @NonNull TextInputEditText etPrompt) {
        TextView tv = createChipView(context, label);
        tv.setOnClickListener(v -> {
            try {
                Editable e = etPrompt.getText();
                if (e == null) return;
                int start = etPrompt.getSelectionStart();
                if (start < 0) start = e.length();
                e.insert(start, token);
                etPrompt.setSelection(Math.min(start + token.length(), e.length()));
            } catch (Throwable ignored) {}
        });
        container.addView(tv);
    }

    private static TextView createChipView(@NonNull Context context, @NonNull String label) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);

        int fg = 0xFF607D8B;
        int bg = 0x1A607D8B;
        tv.setTextColor(fg);

        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(context, 999));
        tv.setBackground(gd);
        tv.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMarginEnd(dp(context, 8));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void showOverrideModelInputDialog(@NonNull TextView tvValue) {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(this, 18), dp(this, 12), dp(this, 18), dp(this, 4));

        final TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setHint("留空表示跟随全局");
        input.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
        String cur = String.valueOf(tvValue.getText()).trim();
        if (!TextUtils.isEmpty(cur) && !"跟随全局".equals(cur)) input.setText(cur);
        root.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        final MaterialButton btnPick = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnPick.setText("子模型");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        blp.topMargin = dp(this, 10);
        btnPick.setLayoutParams(blp);
        root.addView(btnPick);

        final TextView hint = new TextView(this);
        hint.setText("提示：子模型列表来自本地缓存（实验室 → 子模型）。若为空，请先在实验室打开一次子模型弹窗。\n你也可以直接手动输入。");
        hint.setTextSize(12);
        try { hint.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666)); } catch (Throwable ignored) {}
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        hlp.topMargin = dp(this, 6);
        hint.setLayoutParams(hlp);
        root.addView(hint);

        btnPick.setOnClickListener(v -> {
            showLocalCachedSubModelPicker(input);
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle("专属大模型")
                .setView(root)
                .setNeutralButton("跟随全局", (d, w) -> {
                    try { tvValue.setText("跟随全局"); } catch (Throwable ignored) {}
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = input.getText() != null ? input.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(v)) {
                        tvValue.setText("跟随全局");
                    } else {
                        tvValue.setText(v);
                    }
                })
                .show();
    }

    private void showLocalCachedSubModelPicker(@NonNull TextInputEditText targetInput) {
        LanguageModel lm;
        try { lm = sp != null ? sp.getLanguageModel() : SPManager.getInstance().getLanguageModel(); } catch (Throwable t) { lm = null; }
        if (lm == null) {
            Toast.makeText(this, "无法获取当前模型提供商", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> all = new ArrayList<>();
        try {
            List<String> cached = sp.getCachedModels(lm);
            if (cached != null) all.addAll(cached);
        } catch (Throwable ignored) {}
        try {
            List<String> custom = sp.getCustomSubModels(lm);
            if (custom != null) all.addAll(custom);
        } catch (Throwable ignored) {}

        // Dedup while keeping order
        ArrayList<String> dedup = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String s : all) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (seen.add(v)) dedup.add(v);
        }

        if (dedup.isEmpty()) {
            Toast.makeText(this, "本地没有缓存的子模型列表（请先在实验室→子模型打开一次）", Toast.LENGTH_LONG).show();
            return;
        }

        CharSequence[] items = new CharSequence[dedup.size()];
        for (int i = 0; i < dedup.size(); i++) items[i] = dedup.get(i);

        new MaterialAlertDialogBuilder(this)
                .setTitle("选择子模型（本地缓存）")
                .setItems(items, (d, which) -> {
                    try {
                        String sel = String.valueOf(items[which]);
                        targetInput.setText(sel);
                        targetInput.setSelection(sel.length());
                    } catch (Throwable ignored) {}
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void bindTokenEstimator(@Nullable TextInputEditText etPrompt, @Nullable TextView tvToken) {
        if (etPrompt == null || tvToken == null) return;
        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateTokenProbe(tvToken, s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {
                updateTokenProbe(tvToken, s == null ? "" : s.toString());
            }
        };
        etPrompt.addTextChangedListener(tw);
        try {
            CharSequence cur = etPrompt.getText();
            updateTokenProbe(tvToken, cur == null ? "" : cur.toString());
        } catch (Throwable ignored) {}
    }

    private static final Pattern EN_WORD = Pattern.compile("[A-Za-z0-9]+");

    private static void updateTokenProbe(@NonNull TextView tv, @NonNull String text) {
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
            Matcher m = EN_WORD.matcher(text);
            while (m.find()) words++;
        } catch (Throwable ignored) {}

        int est = 0;
        try { est = Math.round((cjk * 1.5f) + words); } catch (Throwable ignored) {}

        try { tv.setText(chars + " 字 / 约 " + est + " Tokens"); } catch (Throwable ignored) {}
    }

    private static int dp(@NonNull Context context, float dp) {
        float d = context.getResources().getDisplayMetrics().density;
        return (int) (dp * d + 0.5f);
    }

    // ===== Draft =====
    private static final class RoleDraft {
        final String roleId;
        final String name;
        final String trigger;
        final String prompt;
        final String emoji;
        final String exampleUser;
        final String exampleAssistant;
        final boolean presetEnabled;
        final int presetMemoryLevel;
        final int presetMaxTokens;
        final boolean overrideEnabled;
        final String overrideSubModel;
        final float overrideTemp;

        RoleDraft(String roleId,
                  String name,
                  String trigger,
                  String prompt,
                  String emoji,
                  String exampleUser,
                  String exampleAssistant,
                  boolean presetEnabled,
                  int presetMemoryLevel,
                  int presetMaxTokens,
                  boolean overrideEnabled,
                  String overrideSubModel,
                  float overrideTemp) {
            this.roleId = roleId;
            this.name = name;
            this.trigger = trigger;
            this.prompt = prompt;
            this.emoji = emoji;
            this.exampleUser = exampleUser;
            this.exampleAssistant = exampleAssistant;
            this.presetEnabled = presetEnabled;
            this.presetMemoryLevel = presetMemoryLevel;
            this.presetMaxTokens = presetMaxTokens;
            this.overrideEnabled = overrideEnabled;
            this.overrideSubModel = overrideSubModel;
            this.overrideTemp = overrideTemp;
        }

        boolean isValid() {
            return !TextUtils.isEmpty(name) && !TextUtils.isEmpty(prompt);
        }
    }
}
