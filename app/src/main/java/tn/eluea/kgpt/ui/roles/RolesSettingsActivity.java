package tn.eluea.kgpt.ui.roles;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;

import tn.eluea.kgpt.text.parse.ParsePattern;
import tn.eluea.kgpt.text.parse.PatternType;
import tn.eluea.kgpt.ui.UiInteractor;

import org.json.JSONArray;
import org.json.JSONObject;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.roles.RoleManager;

/**
 * Full-screen Role management page.
 *
 * - Tap a role: select as current role
 * - Tap "…": edit / clone / delete
 * - FAB: add role
 */
public class RolesSettingsActivity extends AppCompatActivity {

    private ActivityResultLauncher<Intent> editRoleLauncher;

    private SPManager sp;
    private List<RoleManager.Role> rolesAll = new ArrayList<>();
    private List<RoleManager.Role> rolesFiltered = new ArrayList<>();
    private String activeRoleId;

    private boolean stackingEnabled = false;
    private List<String> stackingRoleIds = new ArrayList<>();

    private EditText etSearch;
    private TextView tvSearchCancel;
    private TextView tvSummary;
    private TextView tvEmpty;
    private RolesAdapter adapter;

    // Listen for trigger symbol changes (InvocationPatternsFragment sync broadcast)
    private BroadcastReceiver patternsChangedReceiver;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_roles_settings);

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        View content = findViewById(R.id.content_roles);
        if (content != null) {
            final int padLeft = content.getPaddingLeft();
            final int padTop = content.getPaddingTop();
            final int padRight = content.getPaddingRight();
            final int padBottom = content.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                v.setPadding(padLeft, padTop, padRight, padBottom + bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(content);
            try {
                int surface = MaterialColors.getColor(content, com.google.android.material.R.attr.colorSurface);
                content.setBackgroundColor(surface);
            } catch (Throwable ignored) {}
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar_roles);
        if (toolbar != null) {
            final int padLeft = toolbar.getPaddingLeft();
            final int padTop = toolbar.getPaddingTop();
            final int padRight = toolbar.getPaddingRight();
            final int padBottom = toolbar.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(padLeft, padTop + top, padRight, padBottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(toolbar);
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            toolbar.setNavigationOnClickListener(v -> finish());

            // Toolbar menu
            try {
                toolbar.inflateMenu(R.menu.menu_roles_settings);
                toolbar.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    if (id == R.id.action_role_stacking) {
                        showStackingDialog();
                        return true;
                    }
                    if (id == R.id.action_role_import) {
                        showImportDialog();
                        return true;
                    }
                    if (id == R.id.action_role_export) {
                        showExportDialog();
                        return true;
                    }
                    return false;
                });
            } catch (Throwable ignored) {}
        }

        try {
            sp = SPManager.getInstance();
        } catch (Throwable t) {
            finish();
            return;
        }

        editRoleLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        loadAndRender();
                        // Let parent screen refresh summary.
                        setResult(Activity.RESULT_OK);
                    }
                }
        );

        etSearch = findViewById(R.id.et_role_search);
        tvSearchCancel = findViewById(R.id.tv_role_search_cancel);
        tvSummary = findViewById(R.id.tv_roles_summary);
        tvEmpty = findViewById(R.id.tv_roles_empty);

        RecyclerView rv = findViewById(R.id.rv_roles_manage);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
        }
        adapter = new RolesAdapter();
        if (rv != null) rv.setAdapter(adapter);

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    String q = s == null ? "" : s.toString();
                    if (tvSearchCancel != null) {
                        tvSearchCancel.setVisibility(q.trim().isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    filterAndRender(q);
                }
            });
        }

        if (tvSearchCancel != null && etSearch != null) {
            tvSearchCancel.setOnClickListener(v -> {
                try { etSearch.setText(""); } catch (Throwable ignored) {}
            });
        }

        ExtendedFloatingActionButton fab = findViewById(R.id.fab_add_role);
        if (fab != null) {
            fab.setOnClickListener(v -> startAddRole());
        }

        loadAndRender();
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Real-time sync: when AI trigger symbol is modified elsewhere, update DEFAULT role chip immediately.
        if (patternsChangedReceiver == null) {
            patternsChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    // We only care about pattern updates (AI trigger symbol lives there)
                    if (!intent.hasExtra(UiInteractor.EXTRA_PATTERN_LIST)) return;
                    try {
                        if (adapter != null) adapter.notifyDataSetChanged();
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

    private void loadAndRender() {
        String rolesJson = "";
        try { rolesJson = sp.getRolesJson(); } catch (Throwable ignored) {}
        try { activeRoleId = sp.getActiveRoleId(); } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(activeRoleId)) activeRoleId = RoleManager.DEFAULT_ROLE_ID;

        // Load stacking state
        try {
            stackingEnabled = sp.getRoleStackEnabled();
            stackingRoleIds = RoleManager.parseRoleIdArray(sp.getRoleStackJson());
        } catch (Throwable ignored) {
            stackingEnabled = false;
            stackingRoleIds = new ArrayList<>();
        }

        rolesAll = RoleManager.loadRoles(rolesJson, sp);
        String q = etSearch != null && etSearch.getText() != null ? etSearch.getText().toString() : "";
        filterAndRender(q);
    }

    private void filterAndRender(@Nullable String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        rolesFiltered.clear();

        if (q.isEmpty()) {
            rolesFiltered.addAll(rolesAll);
        } else {
            for (RoleManager.Role r : rolesAll) {
                if (r == null) continue;
                String name = r.name == null ? "" : r.name;
                String trig = r.trigger == null ? "" : r.trigger;
                String prompt = r.prompt == null ? "" : r.prompt;
                String hay = (name + "\n" + trig + "\n" + prompt).toLowerCase(Locale.US);
                if (hay.contains(q)) rolesFiltered.add(r);
            }
        }

        if (adapter != null) {
            adapter.setData(rolesFiltered, activeRoleId, stackingEnabled, stackingRoleIds);
        }

        // Summary
        String currentName = RoleManager.DEFAULT_ROLE_NAME;
        int currentIndex = 1;
        for (int i = 0; i < rolesAll.size(); i++) {
            RoleManager.Role r = rolesAll.get(i);
            if (r != null && activeRoleId != null && activeRoleId.equals(r.id)) {
                currentName = r.name;
                currentIndex = i + 1;
                break;
            }
        }
        if (tvSummary != null) {
            String stackInfo = "";
            if (stackingEnabled && stackingRoleIds != null && !stackingRoleIds.isEmpty()) {
                stackInfo = String.format(Locale.getDefault(), " · 叠加：开启(%d/%d)", stackingRoleIds.size(), 3);
            }
            tvSummary.setText(String.format(Locale.getDefault(), "共 %d 个角色 · 当前 #%d：%s%s", rolesAll.size(), currentIndex, currentName, stackInfo));
        }
        if (tvEmpty != null) {
            tvEmpty.setVisibility(rolesFiltered.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void startAddRole() {
        Intent it = new Intent(this, RoleEditActivity.class);
        editRoleLauncher.launch(it);
    }

    private void startEditRole(@NonNull String roleId) {
        Intent it = new Intent(this, RoleEditActivity.class);
        it.putExtra(RoleEditActivity.EXTRA_ROLE_ID, roleId);
        editRoleLauncher.launch(it);
    }

    private void onSelectRole(@NonNull RoleManager.Role role) {
        try {
            sp.setActiveRoleId(role.id);
            activeRoleId = role.id;
            setResult(Activity.RESULT_OK);

            Toast.makeText(this, "已选择角色：" + role.name, Toast.LENGTH_SHORT).show();


            filterAndRender(etSearch != null && etSearch.getText() != null ? etSearch.getText().toString() : "");
        } catch (Throwable t) {
            Toast.makeText(this, "切换失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void onCloneRole(@NonNull RoleManager.Role role) {
        if (RoleManager.DEFAULT_ROLE_ID.equals(role.id)) {
            Toast.makeText(this, "默认角色无需复制", Toast.LENGTH_SHORT).show();
            return;
        }
        String id = "r_" + System.currentTimeMillis();
        String name = (role.name == null ? "" : role.name) + " 副本";
        String prompt = role.prompt == null ? "" : role.prompt;
        String trigger = role.trigger == null ? "" : role.trigger;
        String emoji = role.emoji == null ? "🤖" : role.emoji;
        boolean overrideEnabled = role.overrideEnabled;
        String overrideSubModel = role.overrideSubModel;
        float overrideTemp = role.overrideTemperature;

        rolesAll.add(new RoleManager.Role(id, name, prompt, trigger, emoji, overrideEnabled, overrideSubModel, overrideTemp));
        sp.setRolesJson(RoleManager.serializeCustomRoles(rolesAll));
        loadAndRender();
        setResult(Activity.RESULT_OK);
        Toast.makeText(this, "已复制：" + name, Toast.LENGTH_SHORT).show();
    }

    private void onQuickChangeEmoji(@NonNull RoleManager.Role role) {
        // Grid picker: 6 columns (consistent with RoleEditActivity).
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 6));
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);

        final String[] emojis = new String[]{
                // Robot / tech
                "🤖","🧠","💻","🖥️","⌨️","🖱️","📱","🛰️","🧬","🔬","🧪","⚙️",
                // Writing / study
                "📝","✍️","📚","📖","📌","📎","🗂️","🧾","🧩","🧮","📐","🧷",
                // Art / design
                "🎨","🖌️","🧵","🎭","🎬","📸","🎧","🎼","🎹","🎻","🎷","🕺",
                // Business / data
                "📊","📈","📉","💡","🎯","🏷️","💰","🧱","🧲","🧰","🛠️","🔍",
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
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                TextView tv = new TextView(parent.getContext());
                int size = dp(44);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(size, size);
                tv.setLayoutParams(lp);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextSize(18);
                tv.setBackgroundResource(android.R.drawable.list_selector_background);
                return new RecyclerView.ViewHolder(tv) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                TextView tv = (TextView) holder.itemView;
                String e = emojis[position];
                tv.setText(e);
                tv.setOnClickListener(v -> {
                    applyRoleUpdate(role, buildRoleWithEmoji(role, e));
                    try { if (dialogRef[0] != null) dialogRef[0].dismiss(); } catch (Throwable ignored) {}
                });
            }

            @Override
            public int getItemCount() {
                return emojis.length;
            }
        });

        int pad = dp(10);
        rv.setPadding(pad, pad, pad, pad);
        rv.setClipToPadding(false);

        dialogRef[0] = new MaterialAlertDialogBuilder(this)
                .setTitle("选择头像")
                .setView(rv)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogRef[0].show();
    }

    private void onQuickRename(@NonNull RoleManager.Role role) {
        if (RoleManager.DEFAULT_ROLE_ID.equals(role.id)) {
            Toast.makeText(this, "默认角色名称不可修改", Toast.LENGTH_SHORT).show();
            return;
        }

        final TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setHint("请输入角色名称");
        input.setText(role.name);

        new MaterialAlertDialogBuilder(this)
                .setTitle("修改角色名称")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = input.getText() == null ? "" : input.getText().toString().trim();
                    if (v.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyRoleUpdate(role, buildRoleWithName(role, v));
                })
                .show();
    }

    private void onQuickEditTrigger(@NonNull RoleManager.Role role) {
        // DEFAULT role trigger is the global AI trigger symbol. Editing here should edit the global symbol.
        if (RoleManager.DEFAULT_ROLE_ID.equals(role.id)) {
            showEditGlobalAiTriggerSymbolDialog();
            return;
        }

        final TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        String trig = role.trigger == null ? "" : role.trigger;
        input.setText(trig);
        input.setHint("可选，最多10字");

        String trigSymbol = "";
        try { trigSymbol = sp.getAiTriggerSymbol(); } catch (Throwable ignored) {}

        new MaterialAlertDialogBuilder(this)
                .setTitle("修改触发词")
                .setMessage("触发示例：" + trigSymbol + "xxxx")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = input.getText() == null ? "" : input.getText().toString().trim();
                    if (v.length() > 10) {
                        Toast.makeText(this, "触发词最多10字", Toast.LENGTH_SHORT).show();
                        v = v.substring(0, 10);
                    }
                    applyRoleUpdate(role, buildRoleWithTrigger(role, v));
                })
                .show();
    }

    private void showEditGlobalAiTriggerSymbolDialog() {
        final TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

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

                        // Broadcast to IME + other UIs
                        try {
                            Intent i = new Intent(UiInteractor.ACTION_DIALOG_RESULT);
                            String raw = sp.getParsePatternsRaw();
                            if (raw != null) i.putExtra(UiInteractor.EXTRA_PATTERN_LIST, raw);
                            tn.eluea.kgpt.provider.BridgeAuth.send(this, i);
                        } catch (Throwable ignored) {}

                        // Refresh UI
                        try { if (adapter != null) adapter.notifyDataSetChanged(); } catch (Throwable ignored) {}
                        setResult(Activity.RESULT_OK);
                    } catch (Throwable t) {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void applyRoleUpdate(@NonNull RoleManager.Role oldRole, @NonNull RoleManager.Role newRole) {
        try {
            if (RoleManager.DEFAULT_ROLE_ID.equals(oldRole.id)) {
                // Update default role custom json (partial update, preserve other fields).
                JSONObject o;
                try {
                    String raw = sp.getDefaultRoleCustomJson();
                    o = (raw == null || raw.trim().isEmpty()) ? new JSONObject() : new JSONObject(raw);
                } catch (Throwable t) {
                    o = new JSONObject();
                }
                o.put("emoji", newRole.emoji);
                o.put("trigger", newRole.trigger);
                o.put("prompt", newRole.prompt);
                o.put("override_enabled", newRole.overrideEnabled);
                o.put("override_sub_model", newRole.overrideSubModel);
                o.put("override_temp", newRole.overrideTemperature);
                o.put("preset_enabled", newRole.presetEnabled);
                o.put("preset_memory_level", newRole.presetMemoryLevel);
                o.put("preset_max_tokens", newRole.presetMaxTokens);
                o.put("example_user", newRole.exampleUser);
                o.put("example_assistant", newRole.exampleAssistant);
                // Name is fixed for default role.
                sp.setDefaultRoleCustomJson(o.toString());
            } else {
                for (int i = 0; i < rolesAll.size(); i++) {
                    RoleManager.Role r = rolesAll.get(i);
                    if (r != null && oldRole.id.equals(r.id)) {
                        rolesAll.set(i, newRole);
                        break;
                    }
                }
                sp.setRolesJson(RoleManager.serializeCustomRoles(rolesAll));
            }
            loadAndRender();
            setResult(Activity.RESULT_OK);
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static RoleManager.Role buildRoleWithEmoji(@NonNull RoleManager.Role base, @NonNull String emoji) {
        return new RoleManager.Role(
                base.id,
                base.name,
                base.prompt,
                base.trigger,
                emoji,
                base.overrideEnabled,
                base.overrideSubModel,
                base.overrideTemperature,
                base.presetEnabled,
                base.presetMemoryLevel,
                base.presetMaxTokens,
                base.exampleUser,
                base.exampleAssistant
        );
    }

    private static RoleManager.Role buildRoleWithName(@NonNull RoleManager.Role base, @NonNull String name) {
        return new RoleManager.Role(
                base.id,
                name,
                base.prompt,
                base.trigger,
                base.emoji,
                base.overrideEnabled,
                base.overrideSubModel,
                base.overrideTemperature,
                base.presetEnabled,
                base.presetMemoryLevel,
                base.presetMaxTokens,
                base.exampleUser,
                base.exampleAssistant
        );
    }

    private static RoleManager.Role buildRoleWithTrigger(@NonNull RoleManager.Role base, @NonNull String trigger) {
        return new RoleManager.Role(
                base.id,
                base.name,
                base.prompt,
                trigger,
                base.emoji,
                base.overrideEnabled,
                base.overrideSubModel,
                base.overrideTemperature,
                base.presetEnabled,
                base.presetMemoryLevel,
                base.presetMaxTokens,
                base.exampleUser,
                base.exampleAssistant
        );
    }

    private static RoleManager.Role buildRoleWithOverrideEnabled(@NonNull RoleManager.Role base, boolean enabled) {
        return new RoleManager.Role(
                base.id,
                base.name,
                base.prompt,
                base.trigger,
                base.emoji,
                enabled,
                base.overrideSubModel,
                base.overrideTemperature,
                base.presetEnabled,
                base.presetMemoryLevel,
                base.presetMaxTokens,
                base.exampleUser,
                base.exampleAssistant
        );
    }

    private int dp(float dp) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (dp * d + 0.5f);
    }

    private void onDeleteRole(@NonNull RoleManager.Role role) {
        if (RoleManager.DEFAULT_ROLE_ID.equals(role.id)) {
            Toast.makeText(this, getString(R.string.role_default_not_deletable), Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.delete_role))
                .setMessage(getString(R.string.delete_role_confirm, role.name))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    RoleManager.Role toRemove = null;
                    for (RoleManager.Role r : rolesAll) {
                        if (r != null && role.id.equals(r.id)) {
                            toRemove = r;
                            break;
                        }
                    }
                    if (toRemove != null) rolesAll.remove(toRemove);

                    if (role.id.equals(activeRoleId)) {
                        activeRoleId = RoleManager.DEFAULT_ROLE_ID;
                        try { sp.setActiveRoleId(activeRoleId); } catch (Throwable ignored) {}
                    }

                    sp.setRolesJson(RoleManager.serializeCustomRoles(rolesAll));
                    loadAndRender();
                    setResult(Activity.RESULT_OK);
                    Toast.makeText(this, getString(R.string.role_deleted), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ===== Toolbar actions =====

    private void showStackingDialog() {
        // Build candidate list (exclude default; default is always the base when stacking is enabled).
        final List<RoleManager.Role> candidates = new ArrayList<>();
        for (RoleManager.Role r : rolesAll) {
            if (r == null) continue;
            if (RoleManager.DEFAULT_ROLE_ID.equals(r.id)) continue;
            candidates.add(r);
        }

        if (candidates.isEmpty()) {
            Toast.makeText(this, "暂无可叠加的角色（请先添加角色）", Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] names = new String[candidates.size()];
        final boolean[] checked = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            RoleManager.Role r = candidates.get(i);
            names[i] = (r.emoji == null ? "🤖" : r.emoji) + "  " + (r.name == null ? "" : r.name);
            checked[i] = stackingEnabled && stackingRoleIds != null && r.id != null && stackingRoleIds.contains(r.id);
        }

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle("叠加模式（最多 3 个）")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                    if (!isChecked) return;
                    // Enforce max 3
                    int count = 0;
                    for (boolean c : checked) if (c) count++;
                    if (count > 3) {
                        checked[which] = false;
                        try {
                            ((androidx.appcompat.app.AlertDialog) dialog).getListView().setItemChecked(which, false);
                        } catch (Throwable ignored) {}
                        Toast.makeText(this, "最多只能叠加 3 个角色", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("关闭叠加", (d, w) -> {
                    try {
                        sp.setRoleStackEnabled(false);
                        sp.setRoleStackJson("[]");
                    } catch (Throwable ignored) {}
                    stackingEnabled = false;
                    stackingRoleIds = new ArrayList<>();
                    filterAndRender(etSearch != null && etSearch.getText() != null ? etSearch.getText().toString() : "");
                    Toast.makeText(this, "已关闭叠加模式", Toast.LENGTH_SHORT).show();
                    setResult(Activity.RESULT_OK);
                })
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<String> selected = new ArrayList<>();
                    for (int i = 0; i < candidates.size(); i++) {
                        if (!checked[i]) continue;
                        RoleManager.Role r = candidates.get(i);
                        if (r != null && !TextUtils.isEmpty(r.id)) selected.add(r.id);
                    }
                    // Require at least 2 roles to be meaningful.
                    if (selected.size() < 2) {
                        try {
                            sp.setRoleStackEnabled(false);
                            sp.setRoleStackJson("[]");
                        } catch (Throwable ignored) {}
                        stackingEnabled = false;
                        stackingRoleIds = new ArrayList<>();
                        filterAndRender(etSearch != null && etSearch.getText() != null ? etSearch.getText().toString() : "");
                        Toast.makeText(this, "叠加至少选择 2 个角色，已自动关闭叠加", Toast.LENGTH_SHORT).show();
                        setResult(Activity.RESULT_OK);
                        return;
                    }

                    JSONArray arr = new JSONArray();
                    for (String id : selected) arr.put(id);

                    try {
                        sp.setRoleStackEnabled(true);
                        sp.setRoleStackJson(arr.toString());
                    } catch (Throwable ignored) {}

                    stackingEnabled = true;
                    stackingRoleIds = selected;
                    filterAndRender(etSearch != null && etSearch.getText() != null ? etSearch.getText().toString() : "");
                    Toast.makeText(this, "叠加已开启：" + selected.size() + " 个角色", Toast.LENGTH_SHORT).show();
                    setResult(Activity.RESULT_OK);
                });

        b.show();
    }

    private void showImportDialog() {
        final EditText et = new EditText(this);
        et.setHint("粘贴角色 JSON，或粘贴包含 ```json ... ``` 的 Markdown");
        et.setMinLines(6);
        et.setTextSize(13f);

        new MaterialAlertDialogBuilder(this)
                .setTitle("导入角色")
                .setView(et)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("导入", (d, w) -> {
                    String raw = et.getText() == null ? "" : et.getText().toString();
                    int imported = importRolesFromText(raw);
                    if (imported > 0) {
                        Toast.makeText(this, "已导入 " + imported + " 个角色", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showExportDialog() {
        final String[] options = new String[] {
                "导出当前角色（JSON）",
                "导出当前角色（Markdown）",
                "导出全部角色（JSON）",
                "导出全部角色（Markdown）",
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("导出/分享")
                .setItems(options, (d, which) -> {
                    boolean all = (which == 2 || which == 3);
                    boolean md = (which == 1 || which == 3);
                    String text = buildExportText(all, md);
                    showExportResult(text);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int importRolesFromText(@Nullable String raw) {
        String src = raw == null ? "" : raw.trim();
        if (src.isEmpty()) {
            Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show();
            return 0;
        }

        // Extract JSON from Markdown code fence if present.
        String json = src;
        try {
            int fenceStart = src.indexOf("```");
            if (fenceStart >= 0) {
                int fenceEnd = src.indexOf("```", fenceStart + 3);
                if (fenceEnd > fenceStart) {
                    String inside = src.substring(fenceStart + 3, fenceEnd).trim();
                    // Optional "json" language
                    if (inside.toLowerCase(Locale.US).startsWith("json")) {
                        int nl = inside.indexOf('\n');
                        if (nl > 0) inside = inside.substring(nl + 1).trim();
                    }
                    if (inside.startsWith("{") || inside.startsWith("[")) json = inside;
                }
            }
        } catch (Throwable ignored) {}

        int importedCount = 0;
        boolean importedDefault = false;

        try {
            if (json.startsWith("[")) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    importedCount += importSingleRoleJson(o) ? 1 : 0;
                    if (RoleManager.DEFAULT_ROLE_ID.equals(o.optString("id", "").trim())) importedDefault = true;
                }
            } else {
                JSONObject o = new JSONObject(json);
                importedCount += importSingleRoleJson(o) ? 1 : 0;
                if (RoleManager.DEFAULT_ROLE_ID.equals(o.optString("id", "").trim())) importedDefault = true;
            }
        } catch (Throwable t) {
            Toast.makeText(this, "解析失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            return 0;
        }

        if (importedCount > 0 || importedDefault) {
            // Persist custom roles
            try {
                sp.setRolesJson(RoleManager.serializeCustomRoles(rolesAll));
            } catch (Throwable ignored) {}
            loadAndRender();
            setResult(Activity.RESULT_OK);
        }

        return importedCount;
    }

    private boolean importSingleRoleJson(@NonNull JSONObject o) {
        String id = o.optString("id", "").trim();
        if (RoleManager.DEFAULT_ROLE_ID.equals(id)) {
            // Import default role customization (except name)
            try {
                sp.setDefaultRoleCustomJson(o.toString());
                return true;
            } catch (Throwable ignored) {}
            return false;
        }

        RoleManager.Role r = RoleManager.roleFromJson(o);
        if (r == null) return false;

        // Ensure unique ID
        String newId = r.id;
        boolean conflict = false;
        for (RoleManager.Role existing : rolesAll) {
            if (existing == null) continue;
            if (newId != null && newId.equals(existing.id)) { conflict = true; break; }
        }
        if (conflict || TextUtils.isEmpty(newId)) {
            newId = "r_" + System.currentTimeMillis();
            r = new RoleManager.Role(newId, r.name, r.prompt, r.trigger, r.emoji,
                    r.overrideEnabled, r.overrideSubModel, r.overrideTemperature,
                    r.presetEnabled, r.presetMemoryLevel, r.presetMaxTokens,
                    r.exampleUser, r.exampleAssistant);
        }

        rolesAll.add(r);
        return true;
    }

    private String buildExportText(boolean all, boolean markdown) {
        try {
            if (all) {
                JSONArray arr = new JSONArray();
                for (RoleManager.Role r : rolesAll) {
                    if (r == null) continue;
                    // Include default too.
                    arr.put(RoleManager.roleToJson(r));
                }
                String json = arr.toString(2);
                if (!markdown) return json;
                return "```json\n" + json + "\n```";
            }

            RoleManager.Role current = null;
            for (RoleManager.Role r : rolesAll) {
                if (r == null) continue;
                if (activeRoleId != null && activeRoleId.equals(r.id)) {
                    current = r;
                    break;
                }
            }
            if (current == null) current = rolesAll.isEmpty() ? null : rolesAll.get(0);
            if (current == null) return "";
            String json = RoleManager.roleToJson(current).toString(2);
            if (!markdown) return json;
            return "```json\n" + json + "\n```";
        } catch (Throwable t) {
            return "";
        }
    }

    private void showExportResult(@NonNull String text) {
        TextView tv = new TextView(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setText(text);

        new MaterialAlertDialogBuilder(this)
                .setTitle("导出内容")
                .setView(tv)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("复制", (d, w) -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("roles", text));
                        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                })
                .setPositiveButton("分享", (d, w) -> {
                    try {
                        Intent it = new Intent(Intent.ACTION_SEND);
                        it.setType("text/plain");
                        it.putExtra(Intent.EXTRA_TEXT, text);
                        startActivity(Intent.createChooser(it, "分享"));
                    } catch (Throwable ignored) {}
                })
                .show();
    }

    private final class RolesAdapter extends RecyclerView.Adapter<RolesAdapter.VH> {

        private List<RoleManager.Role> data = new ArrayList<>();
        private String activeId = RoleManager.DEFAULT_ROLE_ID;
        private boolean stackEnabled = false;
        private List<String> stackIds = new ArrayList<>();

        void setData(@NonNull List<RoleManager.Role> roles, @Nullable String activeRoleId, boolean stackingEnabled, @Nullable List<String> stackingRoleIds) {
            data = roles;
            activeId = TextUtils.isEmpty(activeRoleId) ? RoleManager.DEFAULT_ROLE_ID : activeRoleId;
            stackEnabled = stackingEnabled;
            stackIds = stackingRoleIds == null ? new ArrayList<>() : new ArrayList<>(stackingRoleIds);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_role_manage, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            RoleManager.Role role = data.get(position);
            if (role == null) return;

            h.tvEmoji.setText(role.emoji == null ? "🤖" : role.emoji);
            h.tvName.setText(role.name);
            h.tvPrompt.setText(role.prompt == null ? "" : role.prompt.trim());

            // Role-specific parameter override switch (moved from RoleEdit screen).
            // This toggles role.overrideEnabled without changing global temperature settings.
            if (h.swOverrideEnable != null) {
                try { h.swOverrideEnable.setOnCheckedChangeListener(null); } catch (Throwable ignored) {}
                try { h.swOverrideEnable.setChecked(role.overrideEnabled); } catch (Throwable ignored) {}
                h.swOverrideEnable.setOnCheckedChangeListener((btn, checked) -> {
                    // Avoid accidental role selection when toggling.
                    applyRoleUpdate(role, buildRoleWithOverrideEnabled(role, checked));
                });
            }

            boolean isActive = role.id != null && role.id.equals(activeId);
            // v8: Always show a selection affordance.
            // When multiple roles use the default AI trigger (empty role trigger), the check icon
            // becomes the primary selection control for choosing which role the default trigger uses.
            h.ivSelected.setVisibility(View.VISIBLE);
            try {
                h.ivSelected.setImageResource(isActive ? R.drawable.ic_check_circle_filled : R.drawable.ic_check_circle_outline);
                h.ivSelected.setAlpha(isActive ? 1.0f : 0.35f);
            } catch (Throwable ignored) {}

            // Trigger chip
            String trigSymbol = "";
            try { trigSymbol = sp.getAiTriggerSymbol(); } catch (Throwable ignored) {}
            String trig = role.trigger == null ? "" : role.trigger.trim();
            boolean isDefaultRole = RoleManager.DEFAULT_ROLE_ID.equals(role.id);
            if (isDefaultRole) {
                // DEFAULT role trigger is always the global AI trigger symbol.
                h.chipTrigger.setText("触发: " + trigSymbol);
                h.chipTrigger.setVisibility(View.VISIBLE);
                try { h.chipTrigger.setAlpha(1.0f); } catch (Throwable ignored) {}
            } else if (!trig.isEmpty()) {
                // v8: Show ONLY the role-specific trigger keyword.
                // The global AI trigger symbol is configured elsewhere and may be used as a generic trigger.
                // Showing the prefix here is redundant and confusing (e.g. "$GPT$fy").
                h.chipTrigger.setText("触发: " + trig);
                h.chipTrigger.setVisibility(View.VISIBLE);
                try { h.chipTrigger.setAlpha(1.0f); } catch (Throwable ignored) {}
            } else {
                // Empty trigger means: this role is invoked via the DEFAULT trigger when it is selected.
                h.chipTrigger.setText("触发: 默认");
                h.chipTrigger.setVisibility(View.VISIBLE);
                try { h.chipTrigger.setAlpha(0.75f); } catch (Throwable ignored) {}
            }

            // Override chip
            if (role.overrideEnabled) {
                h.chipOverride.setVisibility(View.VISIBLE);
                if (!TextUtils.isEmpty(role.overrideSubModel)) {
                    h.chipOverride.setText("专属参数: " + role.overrideSubModel);
                } else {
                    h.chipOverride.setText("专属参数");
                }
            } else {
                h.chipOverride.setVisibility(View.GONE);
            }

            // Preset chip
            if (role.presetEnabled) {
                h.chipPreset.setVisibility(View.VISIBLE);
                String mem = role.presetMemoryLevel < 0 ? "跟随" : (role.presetMemoryLevel + "轮");
                String mt = role.presetMaxTokens <= 0 ? "跟随" : String.valueOf(role.presetMaxTokens);
                h.chipPreset.setText("档案: 记忆 " + mem + " · " + mt);
            } else {
                h.chipPreset.setVisibility(View.GONE);
            }

            // Stack chip
            if (stackEnabled && stackIds != null && role.id != null && stackIds.contains(role.id)) {
                h.chipStack.setVisibility(View.VISIBLE);
                int idx = stackIds.indexOf(role.id);
                h.chipStack.setText("叠加#" + (idx + 1));
            } else {
                h.chipStack.setVisibility(View.GONE);
            }

            h.itemRoot.setOnClickListener(v -> onSelectRole(role));
            // Also allow tapping the check icon to select.
            h.ivSelected.setOnClickListener(v -> onSelectRole(role));

            // Quick edit actions (do not trigger role selection)
            h.tvEmoji.setOnClickListener(v -> onQuickChangeEmoji(role));
            // Quick rename: tap the role name.
            // The name view is wrap-content (see item_role_manage.xml) so the hit area is small,
            // avoiding accidental triggers when selecting the role.
            h.tvName.setOnClickListener(v -> onQuickRename(role));
            h.chipTrigger.setOnClickListener(v -> onQuickEditTrigger(role));

            h.btnMore.setOnClickListener(v -> {
                PopupMenu pm = new PopupMenu(RolesSettingsActivity.this, h.btnMore);
                pm.getMenuInflater().inflate(R.menu.menu_role_item_actions, pm.getMenu());
                if (RoleManager.DEFAULT_ROLE_ID.equals(role.id)) {
                    pm.getMenu().findItem(R.id.action_delete).setVisible(false);
                    pm.getMenu().findItem(R.id.action_clone).setVisible(false);
                }

                pm.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    if (id == R.id.action_edit) {
                        startEditRole(role.id);
                        return true;
                    }
                    if (id == R.id.action_clone) {
                        onCloneRole(role);
                        return true;
                    }
                    if (id == R.id.action_delete) {
                        onDeleteRole(role);
                        return true;
                    }
                    return false;
                });
                pm.show();
            });
        }

        @Override
        public int getItemCount() {
            return data == null ? 0 : data.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final View itemRoot;
            final TextView tvEmoji;
            final TextView tvName;
            final SwitchCompat swOverrideEnable;
            final TextView tvPrompt;
            final com.google.android.material.chip.Chip chipTrigger;
            final com.google.android.material.chip.Chip chipOverride;
            final com.google.android.material.chip.Chip chipPreset;
            final com.google.android.material.chip.Chip chipStack;
            final ImageView ivSelected;
            final ImageButton btnMore;

            VH(@NonNull View itemView) {
                super(itemView);
                View inner = null;
                try {
                    if (itemView instanceof ViewGroup) {
                        inner = ((ViewGroup) itemView).getChildAt(0);
                    }
                } catch (Throwable ignored) {}
                itemRoot = inner != null ? inner : itemView;

                tvEmoji = itemView.findViewById(R.id.tv_role_emoji);
                tvName = itemView.findViewById(R.id.tv_role_name);
                swOverrideEnable = itemView.findViewById(R.id.sw_role_override_enable);
                tvPrompt = itemView.findViewById(R.id.tv_role_prompt);
                chipTrigger = itemView.findViewById(R.id.chip_trigger);
                chipOverride = itemView.findViewById(R.id.chip_override);
                chipPreset = itemView.findViewById(R.id.chip_preset);
                chipStack = itemView.findViewById(R.id.chip_stack);
                ivSelected = itemView.findViewById(R.id.iv_selected);
                btnMore = itemView.findViewById(R.id.btn_more);
            }
        }
    }
}
