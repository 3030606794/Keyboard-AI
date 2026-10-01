/*
 * ChooseSubModelDialogBox: shown when the user triggers ModelSwitch pattern.
 *
 * Acts as a management hub:
 * 1) Role Management: choose saved role
 * 2) Model Management: choose cached model (submodel) for current provider
 * 3) Key Management: choose provider with saved API key (switch provider), then optionally choose model
 *
 * NOTE (UX):
 * - Selecting a role/model/key DOES NOT immediately dismiss the dialog.
 * - Selections are saved immediately when tapped (so they survive process kill / recent-app swipe).
 */
package tn.eluea.kgpt.core.ui.dialog.box;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.ColorDrawable;
import android.widget.PopupWindow;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;

import android.text.InputType;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.clipboard.AIClipboardStore;
import tn.eluea.kgpt.core.data.ConfigContainer;
import tn.eluea.kgpt.core.ui.dialog.DialogBoxManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.core.quickjump.QuickJumpEntry;
import tn.eluea.kgpt.core.quickjump.QuickJumpManager;
import tn.eluea.kgpt.ui.UiInteractor;
import tn.eluea.kgpt.ui.IMSController;
import tn.eluea.kgpt.ui.main.FloatingBottomSheet;
import tn.eluea.kgpt.util.RootShell;
import tn.eluea.kgpt.ui.common.MaxTokensWhySheet;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.widget.PopupMenu;
import android.view.Menu;
import android.view.MenuItem;
import tn.eluea.kgpt.core.ui.dialog.DialogType;
import tn.eluea.kgpt.instruction.command.GenerativeAICommand;
import tn.eluea.kgpt.text.parse.ParsePattern;
import tn.eluea.kgpt.text.parse.PatternType;

import android.text.Editable;
import android.text.TextWatcher;
import com.google.android.material.materialswitch.MaterialSwitch;
import tn.eluea.kgpt.instruction.command.SimpleGenerativeAICommand;

public class ChooseSubModelDialogBox extends DialogBox {

    // AI Clipboard view mode
    private boolean clipboardShowFavorites = false;

    // AI Clipboard: multi-select mode
    private boolean clipboardSelectionMode = false;
    private java.util.HashSet<Integer> clipboardSelectedItems = new java.util.HashSet<>();

    // AI Clipboard item expansion state (per stored index)
    private java.util.HashSet<Integer> clipboardExpanded = new java.util.HashSet<>();

    private void ensureClipboardSets() {
        if (clipboardSelectedItems == null) clipboardSelectedItems = new java.util.HashSet<>();
        if (clipboardExpanded == null) clipboardExpanded = new java.util.HashSet<>();
    }


    // AI Clipboard: auto-append current system clipboard once when entering this screen
    // (prevents delete/clear from re-adding the current clipboard on every re-render)
    private boolean clipboardAutoAppendDone = false;

    // AI Clipboard: time sort order. false = newest-first (default), true = oldest-first
    private boolean clipboardSortAsc = false;

    // AI Clipboard: search query for filtering entries
    private String clipboardSearchQuery = "";

    // Models: search query for filtering cached models (local only)
    private String modelSearchQuery = "";

    // AI Clipboard: selected group filter. Empty = All.
    private String clipboardSelectedGroup = "";

    // AI Clipboard: remember group filter before entering favorites view
    // so leaving favorites can restore the previous group.
    private String clipboardSelectedGroupBeforeFav = null;

    // AI Clipboard: capture clipboard changes while this dialog is open.
    // Many users don't enable/aren't using LSPosed clipboard hook, so we also
    // listen in-app while the UI is visible.
    private ClipboardManager clipboardUiManager = null;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardUiListener = null;
    private Runnable clipboardUiRenderList = null;

    // Prevent immediate re-adding of a just-deleted/just-cleared clipboard text.
    private String clipboardIgnoreText = null;

    // Broadcast current config so the IME/TextParser side can refresh cached state.
    //
    // Why this exists:
    // The floating "invocation hub" (this dialog) edits patterns/commands/settings using SPManager,
    // but TextParser only updates when UiInteractor receives a broadcast containing
    // EXTRA_PATTERN_LIST / EXTRA_COMMAND_LIST / EXTRA_OTHER_SETTINGS.
    // Without these extras, changes appear "saved" but won't take effect until the user opens
    // the in-app settings (which does broadcast them). So we include them here.
    private void broadcastConfigOnly(Context ctx, SPManager sp) {
        if (ctx == null || sp == null) return;
        try {
            Intent i = new Intent(UiInteractor.ACTION_DIALOG_RESULT);
            i.putExtra(UiInteractor.EXTRA_CONFIG_LANGUAGE_MODEL, sp.getConfigBundle());
            // Also include currently selected provider when available (some listeners depend on it)
            LanguageModel cur = sp.getLanguageModel();
            if (cur != null) {
                i.putExtra(UiInteractor.EXTRA_CONFIG_SELECTED_MODEL, cur.name());
            }

            // Notify TextParser about updated triggers/commands immediately
            String patternsRaw = sp.getParsePatternsRaw();
            if (patternsRaw != null) {
                i.putExtra(UiInteractor.EXTRA_PATTERN_LIST, patternsRaw);
            }
            String commandsRaw = sp.getGenerativeAICommandsRaw();
            if (commandsRaw != null) {
                i.putExtra(UiInteractor.EXTRA_COMMAND_LIST, commandsRaw);
            }

            // Other settings bundle (e.g., AI trigger multiline toggle)
            i.putExtra(UiInteractor.EXTRA_OTHER_SETTINGS, sp.getOtherSettings());
            tn.eluea.kgpt.provider.BridgeAuth.send(ctx, i);
        } catch (Exception ignored) {
        }
    }

    private enum Screen {
        MENU,
        ROLES,
        MODELS,
        KEYS,
        QUICK_JUMP,
        AI_SETTINGS,
        AI_COMMANDS,
        AI_TRIGGERS,
        AI_CLIPBOARD
    }

    public ChooseSubModelDialogBox(DialogBoxManager dialogManager, Activity parent,
                                   android.os.Bundle inputBundle, ConfigContainer configContainer) {
        super(dialogManager, parent, inputBundle, configContainer);
    }

    @Override
    protected Dialog build() {
        Context ctx = getParent();
        FloatingBottomSheet sheet = new FloatingBottomSheet(ctx);

        View root = LayoutInflater.from(sheet.getContext()).inflate(R.layout.dialog_choose_submodel, null);
        sheet.setContentView(root);

        // Make sure we cleanup clipboard listeners when the sheet is dismissed.
        try {
            sheet.setOnDismissListener(d -> disableClipboardUiListener());
        } catch (Throwable ignored) {}

        // --- Make this bottom sheet full-width and bottom-aligned (adaptive for 1080p/2K)
        // FloatingBottomSheet uses 16dp horizontal margins by default. For the invocation hub,
        // the user wants edge-to-edge width.
        try {
            ViewGroup.LayoutParams p = root.getLayoutParams();
            if (p instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p;
                lp.gravity = Gravity.BOTTOM;
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.setMargins(0, 0, 0, 0);
                root.setLayoutParams(lp);
            }
        } catch (Exception ignored) {}

        // Limit the sheet height so the top stays around the middle (a bit lower), while still
        // being anchored to the bottom.
        root.post(() -> {
            try {
                int screenH = root.getResources().getDisplayMetrics().heightPixels;
                // User wants a lower sheet (keep bottom-aligned), so reduce height a bit.
                int maxH = (int) (screenH * 0.50f); // top ~50% of screen
                ViewGroup.LayoutParams lp = root.getLayoutParams();
                if (lp != null) {
                    lp.height = maxH;
                    root.setLayoutParams(lp);
                }
            } catch (Exception ignored) {}
        });


        final LinearLayout container = root.findViewById(R.id.models_container);
        final LinearLayout fixedContainer = root.findViewById(R.id.fixed_container);
        final TextView tvTitle = root.findViewById(R.id.tv_title);
        final TextView tvSubtitle = root.findViewById(R.id.tv_subtitle);
        final ImageView ivTitleIcon = root.findViewById(R.id.iv_title_icon);
        final ScrollView svList = root.findViewById(R.id.sv_list);
        final MaterialButton btnLocate = root.findViewById(R.id.btn_locate);
        final MaterialButton btnAction = root.findViewById(R.id.btn_action);

        Screen initialScreen = Screen.MENU;
        try {
            android.os.Bundle in = getInput();
            if (in != null) {
                String start = in.getString(tn.eluea.kgpt.ui.UiInteractor.EXTRA_START_SCREEN);
                if (start != null && !start.trim().isEmpty()) {
                    try {
                        initialScreen = Screen.valueOf(start);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        final Screen[] screen = new Screen[]{initialScreen};

        final SPManager sp = SPManager.getInstance();

        // ---- Current selections (saved immediately on tap; kept here for UI state)
        final String[] pendingRoleId = new String[]{sp.getActiveRoleId()};
        final LanguageModel[] pendingProvider = new LanguageModel[]{sp.getLanguageModel()};
        final String[] pendingSubModel = new String[]{sp.getSubModel(pendingProvider[0])};

        // IMPORTANT: "render" needs to be self-referential (to allow nested screens).
        // In Java, a local variable can't safely reference itself during initialization,
        // so we use an array holder to avoid "might not have been initialized" errors.
        final Runnable[] renderRef = new Runnable[1];
        renderRef[0] = () -> {
            if (container == null) return;
            container.removeAllViews();

            if (fixedContainer != null) {
                fixedContainer.removeAllViews();
                fixedContainer.setVisibility(View.GONE);
            }

            // Keep capturing clipboard changes for the whole dialog lifecycle (not only inside the
            // AI Clipboard screen). This fixes: clipboard count/history not refreshing unless the
            // user enters the "AI Clipboard" (advanced) screen.
            // We still reset the one-time auto-append flag so that entering AI Clipboard can
            // import once per entry if needed.
            if (screen[0] != Screen.AI_CLIPBOARD) {
                clipboardAutoAppendDone = false;
                // While NOT in the clipboard screen, refresh the whole dialog when clipboard changes
                // so the menu counters (e.g., "AI剪贴板 共X条") update immediately.
                clipboardUiRenderList = renderRef[0];
            }

            // Locate button:
            // - Roles/Models/Keys: locate checked item
            // - AI Clipboard: (moved to search bar)
            if (btnLocate != null) {
                boolean showLocate = (screen[0] == Screen.ROLES || screen[0] == Screen.MODELS || screen[0] == Screen.KEYS);
                btnLocate.setVisibility(showLocate ? View.VISIBLE : View.GONE);
                btnLocate.setIconResource(R.drawable.ic_locate);
                btnLocate.setIconTintResource(R.color.primary);
            }

            // Action button (contextual): add role / add key / add quick jump / etc.
            if (btnAction != null) {
                btnAction.setVisibility(View.GONE);
                btnAction.setOnClickListener(null);
                btnAction.setIconResource(R.drawable.ic_add);

                if (screen[0] == Screen.ROLES) {
                    btnAction.setVisibility(View.VISIBLE);
                    btnAction.setText(R.string.ui_add_role);
                    btnAction.setIconResource(R.drawable.ic_add);
                    btnAction.setOnClickListener(v -> showAddOrEditRoleDialog(root.getContext(), sp, pendingRoleId, null, renderRef[0]));
                } else if (screen[0] == Screen.KEYS) {
                    btnAction.setVisibility(View.VISIBLE);
                    btnAction.setText(R.string.ui_add_key);
                    btnAction.setIconResource(R.drawable.ic_add);
                    btnAction.setOnClickListener(v -> showAddOrEditKeyFlow(root.getContext(), sp, null, pendingProvider, pendingSubModel, renderRef[0]));
                } else if (screen[0] == Screen.QUICK_JUMP) {
                    btnAction.setVisibility(View.VISIBLE);
                    btnAction.setText(R.string.ui_add_quick_jump);
                    btnAction.setIconResource(R.drawable.ic_add);
                    btnAction.setOnClickListener(v -> showAddOrEditQuickJumpDialog(root.getContext(), sp, null, renderRef[0]));
                } else if (screen[0] == Screen.AI_COMMANDS) {
                    // Move "Edit" into each list row (right side). Keep top-right as "Add".
                    btnAction.setVisibility(View.VISIBLE);
                    btnAction.setText(R.string.ui_add);
                    btnAction.setIconResource(R.drawable.ic_add);
                    btnAction.setOnClickListener(v -> showAddOrEditAiCommandDialog(root.getContext(), sp, -1, null, renderRef[0]));
                } else if (screen[0] == Screen.AI_TRIGGERS) {
                    // Move "Edit" into each list row (right side). No top-right action.
                    btnAction.setVisibility(View.GONE);
                } else if (screen[0] == Screen.AI_CLIPBOARD) {
                    // Top-right action: Clear clipboard history
                    btnAction.setVisibility(View.VISIBLE);
                    btnAction.setText(R.string.ui_clipboard_clear);
                    btnAction.setIconResource(R.drawable.ic_trash_filled);
                    btnAction.setOnClickListener(v -> {
                        try {
                            new AlertDialog.Builder(root.getContext())
                                    .setTitle(R.string.ui_clipboard_clear)
                                    .setMessage(R.string.ui_clipboard_clear_confirm)
                                    .setNegativeButton(R.string.cancel, null)
                                    .setPositiveButton(R.string.ok, (d, w) -> {
                                        // Prevent immediate re-adding of the current system clipboard.
                                        clipboardIgnoreText = readSystemClipboardText(root.getContext());
                                        // User-requested: do NOT clear favorited entries.
                                        AIClipboardStore.clearNonFavoritesInGroup(root.getContext(), clipboardSelectedGroup);
                                        clipboardExpanded.clear();
                                        if (renderRef[0] != null) renderRef[0].run();
                                    })
                                    .show();
                        } catch (Exception ignored) {
                            clipboardIgnoreText = readSystemClipboardText(root.getContext());
                            AIClipboardStore.clearNonFavoritesInGroup(root.getContext(), clipboardSelectedGroup);
                            clipboardExpanded.clear();
                            if (renderRef[0] != null) renderRef[0].run();
                        }
                    });
                }
            }


            switch (screen[0]) {
                case ROLES:
                    renderRoles(root, container, tvTitle, tvSubtitle, ivTitleIcon, sp, pendingRoleId, renderRef[0]);
                    break;
                case MODELS:
                    renderModels(root, fixedContainer, container, tvTitle, tvSubtitle, ivTitleIcon, sp, pendingProvider, pendingSubModel, renderRef[0]);
                    break;
                case KEYS:
                    renderKeys(root, container, tvTitle, tvSubtitle, ivTitleIcon, sp, pendingProvider, pendingSubModel, screen, renderRef[0]);
                    break;
                case QUICK_JUMP:
                    renderQuickJump(root, container, tvTitle, tvSubtitle, ivTitleIcon, sp, renderRef[0]);
                    break;
                case AI_SETTINGS:
                    renderAiSettings(root, container, tvTitle, tvSubtitle, ivTitleIcon, sp, pendingProvider, pendingSubModel, screen, renderRef[0]);
                    break;
                case AI_COMMANDS:
                    renderAiCommands(root, fixedContainer, container, tvTitle, tvSubtitle, ivTitleIcon, sp, renderRef[0]);
                    break;
                case AI_TRIGGERS:
                    renderAiTriggers(root, fixedContainer, container, tvTitle, tvSubtitle, ivTitleIcon, sp, renderRef[0]);
                    break;
                case AI_CLIPBOARD:
                    renderAiClipboard(root, fixedContainer, container, tvTitle, tvSubtitle, ivTitleIcon, sp, renderRef[0]);
                    break;
                case MENU:
                default:
                    renderMenu(root, container, tvTitle, tvSubtitle, ivTitleIcon, sp, pendingRoleId, pendingProvider, pendingSubModel, screen, renderRef[0]);
                    break;
            }
        };

        // Locate button behavior:
        // - Roles/Models/Keys: scroll to checked item
        // - AI Clipboard: toggle Favorites view
        if (btnLocate != null) {
            btnLocate.setOnClickListener(v -> {
                if (screen[0] == Screen.MENU) return;
                locateToChecked(container, svList);
            });
        }

        // Back button: always dismiss the whole sheet.
        // Users expect a single "back" to return to the original app/keyboard, not bounce back to the assistant menu.
        View btnBack = root.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                // Restore original back behavior:
                // - if we're inside a sub-screen, go back to the main menu
                // - otherwise, close the overlay
                if (screen[0] != Screen.MENU) {
                    screen[0] = Screen.MENU;
                    try { renderRef[0].run(); } catch (Throwable ignored) {}
                    return;
                }
                try { sheet.dismiss(); } catch (Throwable ignored) {}
            });

            // Handle hardware BACK key similarly (only intercept when not in MENU)
            try {
                sheet.setOnKeyListener((dialog, keyCode, event) -> {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event != null && event.getAction() == KeyEvent.ACTION_UP) {
                        if (screen[0] != Screen.MENU) {
                            screen[0] = Screen.MENU;
                            try { renderRef[0].run(); } catch (Throwable ignored) {}
                            return true;
                        }
                    }
                    return false;
                });
            } catch (Throwable ignored) {}
}

        // Save/Apply button
        View btnSave = root.findViewById(R.id.btn_save);
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                LanguageModel provider = pendingProvider[0] != null ? pendingProvider[0] : sp.getLanguageModel();

                // Commit pending selections
                if (pendingRoleId[0] != null && !pendingRoleId[0].trim().isEmpty()) {
                    sp.setActiveRoleId(pendingRoleId[0]);
                }
                if (provider != null) {
                    sp.setLanguageModel(provider);
                    if (pendingSubModel[0] != null && !pendingSubModel[0].trim().isEmpty()) {
                        sp.setSubModel(provider, pendingSubModel[0].trim());
                    }
                }

                // Broadcast changes once (so keyboard returns to input only after saving)
                try {
                    Intent broadcastIntent = new Intent(UiInteractor.ACTION_DIALOG_RESULT);
                    if (provider != null) {
                        broadcastIntent.putExtra(UiInteractor.EXTRA_CONFIG_SELECTED_MODEL, provider.name());
                    }
                    broadcastIntent.putExtra(UiInteractor.EXTRA_CONFIG_LANGUAGE_MODEL, sp.getConfigBundle());
                    tn.eluea.kgpt.provider.BridgeAuth.send(root.getContext(), broadcastIntent);
                } catch (Exception ignored) {}

                Toast.makeText(root.getContext(), root.getContext().getString(R.string.ui_done), Toast.LENGTH_SHORT).show();
                sheet.dismiss();
            });
        }

        // Start clipboard listening for the whole dialog session.
        // Import current clipboard once so counts are fresh even if the user doesn't enter
        // the AI Clipboard screen.
        try {
            // Don't auto-render during init; we'll do the first render explicitly below.
            clipboardUiRenderList = null;
            enableClipboardUiListener(root.getContext());
            tryAppendCurrentClipboard(root.getContext());
            // After init, allow clipboard changes to re-render the dialog.
            clipboardUiRenderList = renderRef[0];
        } catch (Throwable ignored) {}

        // initial render
        renderRef[0].run();
        return sheet;
    }

    private void setSubtitle(TextView tvSubtitle, String text) {
        if (tvSubtitle == null) return;
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) {
            tvSubtitle.setText("");
            tvSubtitle.setVisibility(View.GONE);
        } else {
            tvSubtitle.setText(text);
            tvSubtitle.setVisibility(View.VISIBLE);
        }
    }

    // -----------------------------
    // Screen: MENU (hub)
    // -----------------------------
    private void renderMenu(View root, LinearLayout container, TextView tvTitle, TextView tvSubtitle, ImageView ivTitleIcon,
                            SPManager sp,
                            String[] pendingRoleId, LanguageModel[] pendingProvider, String[] pendingSubModel,
                            Screen[] screen, Runnable render) {
        if (tvTitle != null) tvTitle.setText(R.string.ui_model_role_key);
        if (ivTitleIcon != null) ivTitleIcon.setImageResource(R.drawable.ic_ai_invocation_filled);
        setSubtitle(tvSubtitle, "");

        LayoutInflater inflater = LayoutInflater.from(root.getContext());

        // Build summaries
        String roleSummary = buildRolesSummary(root, sp, pendingRoleId);
        String modelSummary = buildModelsSummary(root, sp, pendingProvider, pendingSubModel);
        String keySummary = buildKeysSummary(root, sp, pendingProvider);

        // Role Management
        View roleItem = inflater.inflate(R.layout.item_model_option, container, false);
        bindMenuItem(roleItem,
                root.getContext().getString(R.string.ui_manage_roles),
                roleSummary,
                R.drawable.ic_command_filled);
        roleItem.setOnClickListener(v -> {
            screen[0] = Screen.ROLES;
            render.run();
        });
        container.addView(roleItem);

        // Model Management
        View modelItem = inflater.inflate(R.layout.item_model_option, container, false);
        bindMenuItem(modelItem,
                root.getContext().getString(R.string.ui_manage_models),
                modelSummary,
                R.drawable.ic_ai_invocation_filled);
        modelItem.setOnClickListener(v -> {
            screen[0] = Screen.MODELS;
            render.run();
        });
        container.addView(modelItem);

        // Key Management
        View keyItem = inflater.inflate(R.layout.item_model_option, container, false);
        bindMenuItem(keyItem,
                root.getContext().getString(R.string.ui_manage_keys),
                keySummary,
                R.drawable.ic_key_filled);
        keyItem.setOnClickListener(v -> {
            screen[0] = Screen.KEYS;
            render.run();
        });
        container.addView(keyItem);

        // AI Settings (quick provider selection: Gemini/ChatGPT/Groq/OpenRouter/Claude/Mistral/Chutes/Perplexity/ZhipuAI)
        View aiItem = inflater.inflate(R.layout.item_model_option, container, false);
        bindMenuItem(aiItem,
                root.getContext().getString(R.string.ui_ai_settings),
                root.getContext().getString(R.string.ui_ai_settings_desc),
                R.drawable.ic_settings_filled);
        aiItem.setOnClickListener(v -> {
            screen[0] = Screen.AI_SETTINGS;
            render.run();
        });
        container.addView(aiItem);

        // AI Commands
        int cmdCount = 0;
        try {
            List<GenerativeAICommand> cmds = sp.getGenerativeAICommands();
            cmdCount = cmds != null ? cmds.size() : 0;
        } catch (Exception ignored) {}
        View cmdItem = inflater.inflate(R.layout.item_model_option, container, false);
        bindMenuItem(cmdItem,
                root.getContext().getString(R.string.ui_ai_commands),
                root.getContext().getString(R.string.ui_ai_commands_desc, cmdCount),
                R.drawable.ic_command_filled);
        cmdItem.setOnClickListener(v -> {
            screen[0] = Screen.AI_COMMANDS;
            render.run();
        });
        container.addView(cmdItem);

        // AI Triggers
        int patCount = 0;
        try {
            List<ParsePattern> pats = sp.getParsePatterns();
            patCount = pats != null ? pats.size() : 0;
        } catch (Exception ignored) {}
        String multi = sp.getAiTriggerMultilineEnabled()
                ? root.getContext().getString(R.string.ui_on)
                : root.getContext().getString(R.string.ui_off);

        View trigItem = inflater.inflate(R.layout.item_model_option, container, false);
        bindMenuItem(trigItem,
                root.getContext().getString(R.string.ui_ai_triggers),
                root.getContext().getString(R.string.ui_ai_triggers_desc, patCount, multi),
                R.drawable.ic_document_code_filled);
        trigItem.setOnClickListener(v -> {
            screen[0] = Screen.AI_TRIGGERS;
            render.run();
        });
        container.addView(trigItem);

        // AI Clipboard
        int clipCount = 0;
        try {
            clipCount = AIClipboardStore.getCount(root.getContext());
        } catch (Exception ignored) {
        }

        View clipItem = inflater.inflate(R.layout.item_model_option, container, false);
        bindMenuItem(clipItem,
                root.getContext().getString(R.string.ui_ai_clipboard),
                root.getContext().getString(R.string.ui_ai_clipboard_desc, clipCount),
                R.drawable.ic_copy);
        clipItem.setOnClickListener(v -> {
            screen[0] = Screen.AI_CLIPBOARD;
            render.run();
        });
        container.addView(clipItem);
        // Quick Jump (URL triggers)
        String qjSummary;
        try {
            java.util.List<tn.eluea.kgpt.core.quickjump.QuickJumpEntry> items =
                    tn.eluea.kgpt.core.quickjump.QuickJumpManager.load(sp.getQuickJumpConfig());
            int count = items != null ? items.size() : 0;
            qjSummary = (count <= 0)
                    ? root.getContext().getString(R.string.ui_quick_jump_desc_empty)
                    : root.getContext().getString(R.string.ui_quick_jump_desc_count, count);
        } catch (Exception e) {
            qjSummary = root.getContext().getString(R.string.ui_quick_jump_desc_empty);
        }

        View qjItem = inflater.inflate(R.layout.item_model_option, container, false);
        bindMenuItem(qjItem,
                root.getContext().getString(R.string.ui_quick_jump),
                qjSummary,
                R.drawable.ic_open_in_new_filled);
        qjItem.setOnClickListener(v -> {
            screen[0] = Screen.QUICK_JUMP;
            render.run();
        });
        container.addView(qjItem);

    }

    private void bindMenuItem(View item, String title, String desc, int iconRes) {
        TextView tv = item.findViewById(R.id.tv_model_name);
        TextView tvDesc = item.findViewById(R.id.tv_model_desc);
        ImageView iv = item.findViewById(R.id.iv_icon);
        ImageView ivEdit = item.findViewById(R.id.iv_edit);
        CheckBox cb = item.findViewById(R.id.cb_selected);

        if (tv != null) tv.setText(title);
        if (tvDesc != null) {
            tvDesc.setVisibility(View.VISIBLE);
            tvDesc.setText(desc != null ? desc : "");
        }
        if (iv != null) iv.setImageResource(iconRes);
        if (cb != null) cb.setVisibility(View.GONE);
        if (ivEdit != null) ivEdit.setVisibility(View.GONE);
    }

    private String buildRolesSummary(View root, SPManager sp, String[] pendingRoleId) {
        String rolesJson = sp.getRolesJson();
        List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);
        int total = roles != null ? roles.size() : 0;

        String activeId = pendingRoleId != null ? pendingRoleId[0] : sp.getActiveRoleId();
        int idx = 0;
        String name = "-";
        if (roles != null && !roles.isEmpty()) {
            for (int i = 0; i < roles.size(); i++) {
                RoleManager.Role r = roles.get(i);
                if (r == null) continue;
                if (activeId != null && activeId.equals(r.id)) {
                    idx = i + 1;
                    name = (r.name != null && !r.name.trim().isEmpty()) ? r.name : "-";
                    break;
                }
            }
            if (idx == 0) {
                // fallback: show first role
                RoleManager.Role r0 = roles.get(0);
                idx = 1;
                name = (r0 != null && r0.name != null && !r0.name.trim().isEmpty()) ? r0.name : "-";
            }
        }
        return root.getContext().getString(R.string.ui_roles_summary, total, idx, name);
    }

    private String buildModelsSummary(View root, SPManager sp, LanguageModel[] pendingProvider, String[] pendingSubModel) {
        LanguageModel provider = (pendingProvider != null && pendingProvider[0] != null) ? pendingProvider[0] : sp.getLanguageModel();
        List<String> models = sp.getCachedModels(provider);
        int total = models != null ? models.size() : 0;

        String active = (pendingSubModel != null && pendingSubModel[0] != null) ? pendingSubModel[0] : sp.getSubModel(provider);
        int idx = 0;
        String name = "-";
        if (models != null && !models.isEmpty()) {
            for (int i = 0; i < models.size(); i++) {
                String m = models.get(i);
                if (m == null) continue;
                String mm = m.trim();
                if (mm.isEmpty()) continue;
                if (active != null && active.equals(mm)) {
                    idx = i + 1;
                    name = mm;
                    break;
                }
            }
            if (idx == 0) {
                idx = 1;
                name = models.get(0) != null ? models.get(0).trim() : "-";
            }
        }
        return root.getContext().getString(R.string.ui_models_summary, total, idx, name);
    }

    private String buildKeysSummary(View root, SPManager sp, LanguageModel[] pendingProvider) {

        LanguageModel current = (pendingProvider != null && pendingProvider[0] != null) ? pendingProvider[0] : sp.getLanguageModel();
        List<LanguageModel> providers = new ArrayList<>();
        for (LanguageModel m : LanguageModel.values()) {
            if (m == null) continue;
            String key = sp.getApiKey(m);
            boolean hasKey = key != null && !key.trim().isEmpty();
            if (hasKey || m == current) providers.add(m);
        }

        int total = providers.size();
        int idx = 0;
        if (current != null) {
            for (int i = 0; i < providers.size(); i++) {
                if (providers.get(i) == current) {
                    idx = i + 1;
                    break;
                }
            }
        }
        if (idx == 0 && total > 0) idx = 1;

        String name = (current != null) ? current.label : "-";
        return root.getContext().getString(R.string.ui_keys_summary, total, idx, name);
    }

    // -----------------------------
    // Screen: AI_SETTINGS (choose provider quickly, auto-switch key)
    // -----------------------------
    private void renderAiSettings(View root, LinearLayout container, TextView tvTitle, TextView tvSubtitle, ImageView ivTitleIcon,
                                  SPManager sp,
                                  LanguageModel[] pendingProvider, String[] pendingSubModel,
                                  Screen[] screen, Runnable render) {
        if (tvTitle != null) tvTitle.setText(R.string.ui_ai_settings);
        if (ivTitleIcon != null) ivTitleIcon.setImageResource(R.drawable.ic_settings_filled);
        setSubtitle(tvSubtitle, root.getContext().getString(R.string.ui_ai_settings_desc));

        final LanguageModel current = (pendingProvider != null && pendingProvider[0] != null)
                ? pendingProvider[0]
                : sp.getLanguageModel();

        final LanguageModel[] order = new LanguageModel[]{
                LanguageModel.Gemini,
                LanguageModel.ChatGPT,
                LanguageModel.Groq,
                LanguageModel.OpenRouter,
                LanguageModel.Claude,
                LanguageModel.Mistral,
                LanguageModel.Chutes,
                LanguageModel.Perplexity,
                LanguageModel.GLM
        };

        LayoutInflater inflater = LayoutInflater.from(root.getContext());
        for (LanguageModel m : order) {
            if (m == null) continue;
            String key = sp.getApiKey(m);
            boolean hasKey = key != null && !key.trim().isEmpty();

            View item = inflater.inflate(R.layout.item_ai_provider_entry, container, false);
            TextView tv = item.findViewById(R.id.tv_model_name);
            TextView tvDesc = item.findViewById(R.id.tv_model_desc);
            ImageView iv = item.findViewById(R.id.iv_icon);
            ImageView ivCheck = item.findViewById(R.id.iv_check);

            if (tv != null) tv.setText(m.label);
            if (tvDesc != null) {
                tvDesc.setVisibility(View.VISIBLE);
                tvDesc.setText(hasKey ? root.getContext().getString(R.string.ui_key_status_set)
                        : root.getContext().getString(R.string.ui_key_status_missing));
            }
            if (iv != null) iv.setImageResource(R.drawable.ic_settings_filled);
            if (ivCheck != null) ivCheck.setVisibility(m == current ? View.VISIBLE : View.INVISIBLE);

            item.setAlpha(hasKey ? 1.0f : 0.6f);

            item.setOnClickListener(v -> {
                if (!hasKey) {
                    Toast.makeText(root.getContext(),
                            root.getContext().getString(R.string.key_not_set_toast, m.label),
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                // Select provider immediately (persist now)
                pendingProvider[0] = m;
                pendingSubModel[0] = sp.getSubModel(m);

                try {
                    sp.setLanguageModel(m);
                    if (pendingSubModel[0] != null && !pendingSubModel[0].trim().isEmpty()) {
                        sp.setSubModel(m, pendingSubModel[0].trim());
                    }
                    broadcastConfigOnly(root.getContext(), sp);
                } catch (Throwable ignored) {}

                Toast.makeText(root.getContext(),
                        root.getContext().getString(R.string.msg_config_saved),
                        Toast.LENGTH_SHORT).show();

                // If this provider has no cached models, prompt for proxy/base URL and fetch models,
                // then jump to Model Management. Otherwise jump directly.
                ensureModelsCachedThen(root.getContext(), sp, m, pendingSubModel, () -> {
                    screen[0] = Screen.MODELS;
                    render.run();
                });
            });

            container.addView(item);
       
        }
    }




// -----------------------------
// Screen: QUICK_JUMP
// -----------------------------
private void renderQuickJump(View root, LinearLayout container, TextView tvTitle, TextView tvSubtitle, ImageView ivTitleIcon,
                             SPManager sp, Runnable render) {
    if (tvTitle != null) tvTitle.setText(R.string.ui_quick_jump);
    if (ivTitleIcon != null) ivTitleIcon.setImageResource(R.drawable.ic_open_in_new_filled);

    final ArrayList<QuickJumpEntry> items = new ArrayList<>();
    try {
        List<QuickJumpEntry> loaded = QuickJumpManager.load(sp.getQuickJumpConfig());
        if (loaded != null) items.addAll(loaded);
    } catch (Throwable ignored) {}

    int total = items.size();
    setSubtitle(tvSubtitle, root.getContext().getString(R.string.ui_count_simple, total));

    // Fixed row: global enable/disable switch for all QuickJump URLs
    try {
        final LinearLayout fixed = root.findViewById(R.id.fixed_container);
        if (fixed != null) {
            fixed.removeAllViews();
            fixed.setVisibility(View.VISIBLE);

            boolean allEnabled = !items.isEmpty();
            if (allEnabled) {
                for (QuickJumpEntry e : items) {
                    if (e == null) continue;
                    if (!e.enabled) { allEnabled = false; break; }
                }
            }

            LinearLayout row = new LinearLayout(root.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(root.getContext(), 8), dp(root.getContext(), 2), dp(root.getContext(), 8), dp(root.getContext(), 2));

            TextView tv = new TextView(root.getContext());
            tv.setText(R.string.ui_quick_jump_global_switch);
            tv.setTextSize(14f);
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(tvLp);

            MaterialSwitch sw = new MaterialSwitch(root.getContext());
            sw.setChecked(allEnabled);
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    for (QuickJumpEntry e : items) {
                        if (e == null) continue;
                        e.enabled = isChecked;
                    }
                    sp.setQuickJumpConfig(QuickJumpManager.save(items));
                    broadcastConfigOnly(root.getContext(), sp);
                } catch (Throwable ignored) {}
                if (render != null) render.run();
            });

            row.addView(tv);
            row.addView(sw);
            fixed.addView(row);
        }
    } catch (Throwable ignored) {}

    if (items.isEmpty()) {
        TextView tv = new TextView(root.getContext());
        tv.setText(R.string.ui_quick_jump_empty);
        tv.setTextSize(16f);
        tv.setPadding(0, 8, 0, 8);
        container.addView(tv);
        return;
    }

    LayoutInflater inflater = LayoutInflater.from(root.getContext());
    for (int i = 0; i < items.size(); i++) {
        QuickJumpEntry e = items.get(i);
        if (e == null) continue;

        View item = inflater.inflate(R.layout.item_model_option, container, false);
        TextView tv = item.findViewById(R.id.tv_model_name);
        TextView tvDesc = item.findViewById(R.id.tv_model_desc);
        ImageView iv = item.findViewById(R.id.iv_icon);
        ImageView ivEdit = item.findViewById(R.id.iv_edit);
        ImageView ivDelete = item.findViewById(R.id.iv_action_delete);
        CheckBox cb = item.findViewById(R.id.cb_selected);

        String name = e.name != null ? e.name.trim() : "";
        if (name.isEmpty()) {
            // Fallback: show URL host / first chars
            String u = e.urlTemplate != null ? e.urlTemplate.trim() : "";
            if (u.length() > 28) name = u.substring(0, 28) + "...";
            else name = u.isEmpty() ? root.getContext().getString(R.string.ui_quick_jump) : u;
        }

        String trig = e.trigger != null ? e.trigger.trim() : "";
        String url = e.urlTemplate != null ? e.urlTemplate.trim() : "";
        String status = root.getContext().getString(e.enabled ? R.string.ui_quick_jump_url_status_enabled
                : R.string.ui_quick_jump_url_status_disabled);

        String desc;
        if (!trig.isEmpty()) {
            desc = root.getContext().getString(R.string.ui_trigger_symbol_fmt, trig) + "  " + status + "\n" + url;
        } else {
            desc = status + "\n" + url;
        }

        if (tv != null) tv.setText(name);
        if (tvDesc != null) {
            tvDesc.setVisibility(View.VISIBLE);
            tvDesc.setText(desc);
        }
        if (iv != null) iv.setImageResource(R.drawable.ic_open_in_new_filled);

        // Remove the old "check" enable toggle in the list row (tick icon),
        // because delete is now on the far right and enable/disable is controlled by the global switch / edit dialog.
        if (cb != null) cb.setVisibility(View.GONE);

        // Edit icon (pencil) - keep
        if (ivEdit != null) {
            ivEdit.setVisibility(View.VISIBLE);
            final QuickJumpEntry editing = e;
            ivEdit.setOnClickListener(v -> showAddOrEditQuickJumpDialog(root.getContext(), sp, editing, render));
        }

        // Delete icon on the far right (replaces the old tick)
        if (ivDelete != null) {
            ivDelete.setVisibility(View.VISIBLE);
            ivDelete.setOnClickListener(v -> {
                try {
                    new AlertDialog.Builder(root.getContext())
                            .setTitle(R.string.ui_delete)
                            .setMessage(R.string.ui_quick_jump_delete_confirm)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.ui_delete, (d, w) -> {
                                try {
                                    items.remove(e);
                                    sp.setQuickJumpConfig(QuickJumpManager.save(items));
                                    broadcastConfigOnly(root.getContext(), sp);
                                } catch (Throwable ignored) {}
                                if (render != null) render.run();
                            })
                            .show();
                } catch (Throwable ignored) {}
            });
        }


	    // Click row: on Android 15 some cross-window operations from this floating UI may be restricted.
	    // With root (Magisk), we can reliably open the deep link via "su -c am start ...".
	    // This avoids fragile "paste into editor" behaviour while keeping the original trigger logic
	    // for users who type the trigger word manually.
	    item.setOnClickListener(v -> {
	        if (!e.enabled) {
	            Toast.makeText(root.getContext(), R.string.ui_quick_jump_toast_disabled, Toast.LENGTH_SHORT).show();
	            return;
	        }

	        String q = getQuickJumpQueryFromIme();
	        if (q == null) q = "";
	        q = q.trim();

	        String tpl = e.urlTemplate != null ? e.urlTemplate : "";
	        boolean needsQ = tpl.contains("{q}") || tpl.contains("%s") || tpl.contains("😂");
	        if (needsQ && q.isEmpty()) {
	            Toast.makeText(root.getContext(), R.string.ui_quick_jump_toast_need_keyword, Toast.LENGTH_SHORT).show();
	            return;
	        }

	        String finalUrl = QuickJumpManager.buildUrl(tpl, q);
	        openQuickJumpUrlFromImeContext(root.getContext().getApplicationContext(), finalUrl);

	        // Close UI.
	        returnToKeyboard();
	});
        // Disable long-press delete (removed per request)
        item.setOnLongClickListener(null);

        container.addView(item);
    }
}

private String getQuickJumpQueryFromIme() {
    String full = null;
    int cur = -1;

    // Best effort: if the user has selected text in the target editor, use that directly.
    // This fixes cases where the cursor is placed at the beginning of a word/line and
    // some editors do not provide reliable after-cursor text.
    try {
        android.view.inputmethod.InputConnection ic0 = UiInteractor.getInstance().getInputConnection();
        if (ic0 != null) {
            CharSequence sel = null;
            try { sel = ic0.getSelectedText(0); } catch (Throwable ignored) {}
            if (sel != null) {
                String s = sel.toString().trim();
                if (!s.isEmpty()) return s;
            }
        }
    } catch (Throwable ignored) {}

    // Try InputConnection first (most accurate / real-time)
    try {
        android.view.inputmethod.InputConnection ic = UiInteractor.getInstance().getInputConnection();
        if (ic != null) {
            // Some editors only return ExtractedText if hints are provided.
            android.view.inputmethod.ExtractedTextRequest req = new android.view.inputmethod.ExtractedTextRequest();
            req.hintMaxChars = 100000;
            req.hintMaxLines = 200;
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);
            if (et != null && et.text != null) {
                full = et.text.toString();
                cur = et.selectionEnd >= 0 ? et.selectionEnd : full.length();
            } else {
                CharSequence before = ic.getTextBeforeCursor(8192, 0);
                CharSequence after = ic.getTextAfterCursor(8192, 0);
                String b = before != null ? before.toString() : "";
                String a = after != null ? after.toString() : "";
                if (!b.isEmpty() || !a.isEmpty()) {
                    full = b + a;
                    cur = b.length();
                }
            }
        }
    } catch (Throwable ignored) {}

    // Fallback: IMSController snapshot
    if (full == null || cur < 0) {
        try {
            IMSController ims = UiInteractor.getInstance().getIMSController();
            if (ims != null) {
                full = ims.getTypedTextSnapshot();
                cur = ims.getCursorSnapshot();
            }
        } catch (Throwable ignored) {}
    }

    if (full == null) full = "";
    if (cur < 0) cur = full.length();
    if (cur > full.length()) cur = full.length();

    // Prefer the CURRENT line at cursor (uses after-cursor content when available).
    // This fixes the case where the cursor is placed at the beginning of the keyword line.
    try {
        int lineStart = Math.max(full.lastIndexOf('\n', Math.max(0, cur - 1)), full.lastIndexOf('\r', Math.max(0, cur - 1)));
        lineStart = (lineStart >= 0) ? (lineStart + 1) : 0;

        int n1 = full.indexOf('\n', cur);
        int n2 = full.indexOf('\r', cur);
        int lineEnd;
        if (n1 >= 0 && n2 >= 0) lineEnd = Math.min(n1, n2);
        else lineEnd = Math.max(n1, n2);
        if (lineEnd < 0) lineEnd = full.length();

        if (lineStart >= 0 && lineStart <= lineEnd && lineEnd <= full.length()) {
            String line = full.substring(lineStart, lineEnd);
            if (line != null) {
                String s = line.trim();
                if (!s.isEmpty()) return s;
            }
        }
    } catch (Throwable ignored) {}

    String beforeCursor = full.substring(0, cur);

    // Fallback: last non-empty line before cursor (works when cursor is on a new empty line)
    try {
        String[] lines = beforeCursor.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i] != null ? lines[i].trim() : "";
            if (!s.isEmpty()) return s;
        }
    } catch (Throwable ignored) {}

    return beforeCursor.trim();
}



/**
 * Paste the QuickJump trigger word into the target editor at the end of the CURRENT line.
 * This is used when the user taps a QuickJump entry in the menu: instead of launching the URL directly,
 * we insert the trigger so the existing QuickJump parsing/dispatching pipeline runs.
 *
 * @return true if we were able to commit the trigger to the input connection.
 */
private boolean pasteQuickJumpTriggerToIme(String triggerWord) {
    if (triggerWord == null) return false;
    triggerWord = triggerWord.trim();
    if (triggerWord.isEmpty()) return false;

    try {
        tn.eluea.kgpt.ui.UiInteractor ui = tn.eluea.kgpt.ui.UiInteractor.getInstance();
        android.view.inputmethod.InputConnection ic = null;
        try {
            ic = (ui != null) ? ui.getInputConnection() : null;
        } catch (Throwable ignored) {
            ic = null;
        }

        // If we can't access the InputConnection from this UI process (common on some ROMs),
        // ask the active IME process to commit the trigger via a local broadcast bridge.
        if (ic == null) {
            try {
                Context ctx = null;
                try { ctx = getContext(); } catch (Throwable ignored2) {}
                if (ctx == null) ctx = (ui != null && ui.getContext() != null) ? ui.getContext() : null;
                if (ctx != null) {
                    Intent base = new Intent(tn.eluea.kgpt.ui.UiInteractor.ACTION_QJ_PASTE_TRIGGER);
                    base.putExtra(tn.eluea.kgpt.ui.UiInteractor.EXTRA_QJ_TRIGGER, triggerWord);

                    java.util.LinkedHashSet<String> pkgs = new java.util.LinkedHashSet<>();

                    // 1) Try to resolve current default IME from secure settings (may fail on some ROMs).
                    try {
                        String imeId = android.provider.Settings.Secure.getString(
                                ctx.getContentResolver(),
                                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
                        );
                        if (imeId != null) {
                            int slash = imeId.indexOf('/');
                            String pkg = slash >= 0 ? imeId.substring(0, slash) : imeId;
                            if (pkg != null && !pkg.trim().isEmpty()) pkgs.add(pkg.trim());
                        }
                    } catch (Throwable ignored) {
                    }

                    // 2) Broadcast to all enabled IMEs as a robust fallback.
                    try {
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager) ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            java.util.List<android.view.inputmethod.InputMethodInfo> list = imm.getEnabledInputMethodList();
                            if (list != null) {
                                for (android.view.inputmethod.InputMethodInfo imi : list) {
                                    if (imi != null && imi.getPackageName() != null) {
                                        pkgs.add(imi.getPackageName());
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }

                    if (pkgs.isEmpty()) {
                        // Last resort: implicit broadcast (works only if the receiver is already registered in the running IME process).
                        tn.eluea.kgpt.provider.BridgeAuth.send(ctx, base);
                        return true;
                    }

                    boolean sent = false;
                    for (String pkg : pkgs) {
                        try {
                            Intent it = new Intent(base);
                            it.setPackage(pkg);
                            tn.eluea.kgpt.provider.BridgeAuth.send(ctx, it);
                            sent = true;
                        } catch (Throwable ignored) {
                        }
                    }
                    return sent;
                }
            } catch (Throwable ignored3) {}
            return false;
        }

        String full = null;
        int cur = -1;

        try {
            android.view.inputmethod.ExtractedTextRequest req = new android.view.inputmethod.ExtractedTextRequest();
            req.hintMaxChars = 100000;
            req.hintMaxLines = 200;
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);
            if (et != null && et.text != null) {
                full = et.text.toString();
                cur = et.selectionEnd >= 0 ? et.selectionEnd : full.length();
            }
        } catch (Throwable ignored) {}

        if (full == null || cur < 0) {
            // Fallback: before/after cursor snapshot
            try {
                CharSequence before = ic.getTextBeforeCursor(8192, 0);
                CharSequence after = ic.getTextAfterCursor(8192, 0);
                String b = before != null ? before.toString() : "";
                String a = after != null ? after.toString() : "";
                full = b + a;
                cur = b.length();
            } catch (Throwable ignored) {}
        }

        if (full == null) full = "";
        if (cur < 0) cur = full.length();
        if (cur > full.length()) cur = full.length();

        // Compute current line [lineStart, lineEnd)
        int lineStart = Math.max(full.lastIndexOf('\n', Math.max(0, cur - 1)),
                full.lastIndexOf('\r', Math.max(0, cur - 1)));
        lineStart = (lineStart >= 0) ? (lineStart + 1) : 0;

        int n1 = full.indexOf('\n', cur);
        int n2 = full.indexOf('\r', cur);
        int lineEnd;
        if (n1 >= 0 && n2 >= 0) lineEnd = Math.min(n1, n2);
        else lineEnd = Math.max(n1, n2);
        if (lineEnd < 0) lineEnd = full.length();

        // Insert at the end of the line, but before trailing spaces.
        int insertPos = lineEnd;
        while (insertPos > lineStart && insertPos <= full.length()) {
            char ch = full.charAt(insertPos - 1);
            if (ch == '\n' || ch == '\r') break;
            if (!Character.isWhitespace(ch)) break;
            insertPos--;
        }

        // Move cursor to the insert position (best effort)
        try { ic.setSelection(insertPos, insertPos); } catch (Throwable ignored) {}

        // Add a separating space if needed (so "苹果 xy" looks nicer)
        ic.commitText(triggerWord, 1);
        return true;
    } catch (Throwable t) {
        return false;
    }
}

/**
 * Open the given URL using the same (IME/service) context and posting mechanism as the inline trigger path.
 * This is used as a fallback when trigger pasting isn't possible.
 */
private void openQuickJumpUrlFromImeContext(Context dialogContext, String url) {
	    if (url == null) return;
	    url = url.trim();
	    if (url.isEmpty()) return;

	    final String u = url;
	    final tn.eluea.kgpt.ui.UiInteractor ui = tn.eluea.kgpt.ui.UiInteractor.getInstance();
	    final Context ctx = (ui != null && ui.getContext() != null) ? ui.getContext() : dialogContext;
	    final Handler main = new Handler(Looper.getMainLooper());

	    final Runnable startByIntent = () -> {
	        try {
	            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
	            intent.addCategory(Intent.CATEGORY_BROWSABLE);
	            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
	            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
	            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
	            ctx.startActivity(intent);
	        } catch (android.content.ActivityNotFoundException ex) {
	            try { tn.eluea.kgpt.ui.UiInteractor.getInstance().toastShort(dialogContext.getString(R.string.ui_quick_jump_toast_no_app)); } catch (Throwable ignored) {}
	        } catch (Throwable t) {
	            try { tn.eluea.kgpt.ui.UiInteractor.getInstance().toastShort(dialogContext.getString(R.string.ui_quick_jump_toast_open_failed)); } catch (Throwable ignored) {}
	        }
	    };

	    final Runnable tryRootThenStart = () -> {
	        new Thread(() -> {
	            boolean ok = false;
	            try {
	                ok = RootShell.startViewUrl(u);
	            } catch (Throwable ignored) {}
	            if (ok) return;
	            main.post(startByIntent);
	        }, "kgpt-qj-root").start();
	    };

	    try {
	        if (ui != null) {
	            ui.post(tryRootThenStart);
	        } else {
	            tryRootThenStart.run();
	        }
	    } catch (Throwable ignored) {}
}


private void showAddOrEditQuickJumpDialog(Context ctx, SPManager sp, QuickJumpEntry editing, Runnable render) {
    if (ctx == null || sp == null) return;

    final ArrayList<QuickJumpEntry> items = new ArrayList<>();
    try {
        List<QuickJumpEntry> loaded = QuickJumpManager.load(sp.getQuickJumpConfig());
        if (loaded != null) items.addAll(loaded);
    } catch (Throwable t) {}
    final boolean isEdit = (editing != null);
    final QuickJumpEntry target;
    if (isEdit) {
        // Use the same instance from the list if possible
        QuickJumpEntry found = null;
        for (QuickJumpEntry it : items) {
            if (it == null) continue;
            if (editing.id != null && editing.id.equals(it.id)) { found = it; break; }
        }
        target = found != null ? found : editing;
    } else {
        target = new QuickJumpEntry();
        target.enabled = true;
    }

    View view = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_quick_jump, null, false);
    TextInputEditText etName = view.findViewById(R.id.et_qj_name);
    TextInputEditText etTrigger = view.findViewById(R.id.et_qj_trigger);
    TextInputEditText etUrl = view.findViewById(R.id.et_qj_url);
    CheckBox cbEnabled = view.findViewById(R.id.cb_qj_enabled);

    if (etName != null) etName.setText(target.name != null ? target.name : "");
    if (etTrigger != null) etTrigger.setText(target.trigger != null ? target.trigger : "");
    if (etUrl != null) etUrl.setText(target.urlTemplate != null ? target.urlTemplate : "");
    if (cbEnabled != null) cbEnabled.setChecked(target.enabled);

    AlertDialog.Builder b = new AlertDialog.Builder(ctx)
            .setTitle(isEdit ? R.string.ui_edit : R.string.ui_add)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, (d, w) -> {
                String name = etName != null && etName.getText() != null ? etName.getText().toString() : "";
                String trig = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString() : "";
                String url = etUrl != null && etUrl.getText() != null ? etUrl.getText().toString() : "";

                if (name == null) name = "";
                if (trig == null) trig = "";
                if (url == null) url = "";

                target.name = name.trim();
                target.trigger = trig.trim();
                target.urlTemplate = url.trim();
                target.enabled = (cbEnabled != null) ? cbEnabled.isChecked() : true;

                if (!isEdit) {
                    items.add(target);
                }

                try {
                    sp.setQuickJumpConfig(QuickJumpManager.save(items));
                    broadcastConfigOnly(ctx, sp);
                } catch (Throwable ignored) {}

                if (render != null) render.run();
            });

    if (isEdit) {
        b.setNeutralButton(R.string.ui_delete, (d, w) -> {
            try {
                QuickJumpEntry remove = null;
                for (QuickJumpEntry it : items) {
                    if (it == null) continue;
                    if (target.id != null && target.id.equals(it.id)) { remove = it; break; }
                }
                if (remove != null) items.remove(remove);
                sp.setQuickJumpConfig(QuickJumpManager.save(items));
                broadcastConfigOnly(ctx, sp);
            } catch (Throwable ignored) {}
            if (render != null) render.run();
        });
    }

    b.show();
}

// -----------------------------
// Screen: AI_COMMANDS
// -----------------------------
private void renderAiCommands(View root, LinearLayout fixedContainer, LinearLayout container, TextView tvTitle, TextView tvSubtitle, ImageView ivTitleIcon,
                              SPManager sp, Runnable render) {
    if (tvTitle != null) tvTitle.setText(R.string.ui_ai_commands);
    if (ivTitleIcon != null) ivTitleIcon.setImageResource(R.drawable.ic_command_filled);

    List<GenerativeAICommand> cmds = null;
    try {
        cmds = sp.getGenerativeAICommands();
    } catch (Exception ignored) {}

    int total = cmds != null ? cmds.size() : 0;
    boolean masterOn = sp.getInvocationCommandsEnabled();
    int enabledCount = masterOn ? total : 0;
    int disabledCount = masterOn ? 0 : total;
    setSubtitle(tvSubtitle, root.getContext().getString(R.string.ui_ai_commands_desc_status, total, enabledCount, disabledCount));
    try { if (tvSubtitle != null) tvSubtitle.setMaxLines(2); } catch (Exception ignored) {}


    // Pinned header: master switch
    LinearLayout target = fixedContainer != null ? fixedContainer : container;
    if (fixedContainer != null) {
        fixedContainer.setVisibility(View.VISIBLE);
        fixedContainer.removeAllViews();
    }

    LinearLayout rowMaster = new LinearLayout(root.getContext());
    rowMaster.setOrientation(LinearLayout.HORIZONTAL);
    rowMaster.setGravity(Gravity.CENTER_VERTICAL);
    rowMaster.setPadding(0, 6, 0, 12);

    TextView labelMaster = new TextView(root.getContext());
    labelMaster.setText(R.string.ui_enable_all_commands);
    labelMaster.setTextSize(15f);
    LinearLayout.LayoutParams lpLabelMaster = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    labelMaster.setLayoutParams(lpLabelMaster);

    SwitchCompat swMaster = new SwitchCompat(root.getContext());
    swMaster.setChecked(sp.getInvocationCommandsEnabled());
    swMaster.setOnCheckedChangeListener((buttonView, isChecked) -> {
        sp.setInvocationCommandsEnabled(isChecked);
        try {
            Toast.makeText(root.getContext(),
                    root.getContext().getString(R.string.ui_enable_all_commands) + "：" +
                            (isChecked ? root.getContext().getString(R.string.ui_on) : root.getContext().getString(R.string.ui_off)),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
        if (render != null) render.run();
    });

    rowMaster.addView(labelMaster);
    rowMaster.addView(swMaster);
    target.addView(rowMaster);

    if (cmds == null || cmds.isEmpty()) {
        TextView tv = new TextView(root.getContext());
        tv.setText(R.string.no_commands_found);
        tv.setTextSize(16f);
        tv.setPadding(0, 8, 0, 8);
        container.addView(tv);
        return;
    }

    LayoutInflater inflater = LayoutInflater.from(root.getContext());
    for (int i = 0; i < cmds.size(); i++) {
        GenerativeAICommand c = cmds.get(i);
        if (c == null) continue;

        View item = inflater.inflate(R.layout.item_model_option, container, false);
        TextView tv = item.findViewById(R.id.tv_model_name);
        TextView tvDesc = item.findViewById(R.id.tv_model_desc);
        ImageView iv = item.findViewById(R.id.iv_icon);
        ImageView ivEdit = item.findViewById(R.id.iv_edit);
        CheckBox cb = item.findViewById(R.id.cb_selected);

        String prefix = c.getCommandPrefix() != null ? c.getCommandPrefix().trim() : "";
        if (!prefix.startsWith("/")) prefix = "/" + prefix;

        if (tv != null) tv.setText(prefix);
        if (tvDesc != null) {
            String msg = "";
            try { msg = c.getTweakMessage(); } catch (Exception ignored) {}
            if (msg == null) msg = "";
            msg = msg.trim();
            if (msg.length() > 60) msg = msg.substring(0, 60) + "...";
            tvDesc.setVisibility(View.VISIBLE);
            tvDesc.setText(msg.isEmpty() ? root.getContext().getString(R.string.ui_command_no_desc) : msg);
        }
        if (iv != null) iv.setImageResource(R.drawable.ic_command_filled);
        if (cb != null) cb.setVisibility(View.GONE);

        if (ivEdit != null) {
            ivEdit.setVisibility(View.VISIBLE);
            final int editIndex = i;
            final GenerativeAICommand editing = c;
            ivEdit.setOnClickListener(v -> showAddOrEditAiCommandDialog(root.getContext(), sp, editIndex, editing, render));
        }

        container.addView(item);
    }


    }

// -----------------------------
// Screen: AI_TRIGGERS
// -----------------------------
private void renderAiTriggers(View root, LinearLayout fixedContainer, LinearLayout container, TextView tvTitle, TextView tvSubtitle, ImageView ivTitleIcon,
                              SPManager sp, Runnable render) {
    if (tvTitle != null) tvTitle.setText(R.string.ui_ai_triggers);
    if (ivTitleIcon != null) ivTitleIcon.setImageResource(R.drawable.ic_document_code_filled);

    List<ParsePattern> patterns = null;
    try { patterns = sp.getParsePatterns(); } catch (Exception ignored) {}
    int total = patterns != null ? patterns.size() : 0;

    String multi = sp.getAiTriggerMultilineEnabled()
            ? root.getContext().getString(R.string.ui_on)
            : root.getContext().getString(R.string.ui_off);

    int enabledCount = 0;
    if (patterns != null) {
        for (ParsePattern p : patterns) {
            if (p != null && p.isEnabled()) enabledCount++;
        }
    }
    int disabledCount = Math.max(0, total - enabledCount);
    setSubtitle(tvSubtitle, root.getContext().getString(R.string.ui_ai_triggers_desc_status, total, multi, enabledCount, disabledCount));
    try { if (tvSubtitle != null) tvSubtitle.setMaxLines(2); } catch (Exception ignored) {}

    // Pinned header: master switch + multiline toggle
    LinearLayout target = fixedContainer != null ? fixedContainer : container;
    if (fixedContainer != null) {
        fixedContainer.setVisibility(View.VISIBLE);
        fixedContainer.removeAllViews();
    }


    // Master switch: enable/disable all invocation triggers
    LinearLayout rowMaster = new LinearLayout(root.getContext());
    rowMaster.setOrientation(LinearLayout.HORIZONTAL);
    rowMaster.setGravity(Gravity.CENTER_VERTICAL);
    rowMaster.setPadding(0, 6, 0, 12);

    TextView labelMaster = new TextView(root.getContext());
    labelMaster.setText(R.string.ui_enable_all_triggers);
    labelMaster.setTextSize(15f);
    LinearLayout.LayoutParams lpLabelMaster = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    labelMaster.setLayoutParams(lpLabelMaster);

    SwitchCompat swMaster = new SwitchCompat(root.getContext());
    swMaster.setChecked(sp.getInvocationTriggersEnabled());
    swMaster.setOnCheckedChangeListener((buttonView, isChecked) -> {
        if (isChecked) {
            sp.restoreInvocationTriggersFromBackup();
        } else {
            sp.disableAllInvocationTriggersWithBackup();
        }
        try {
            Toast.makeText(root.getContext(),
                    root.getContext().getString(R.string.ui_enable_all_triggers) + "：" +
                            (isChecked ? root.getContext().getString(R.string.ui_on) : root.getContext().getString(R.string.ui_off)),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
        if (render != null) render.run();
    });

    rowMaster.addView(labelMaster);
    rowMaster.addView(swMaster);
    target.addView(rowMaster);

    // Toggle row: multiline prompt sending
    LinearLayout row = new LinearLayout(root.getContext());
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, 6, 0, 12);

    TextView label = new TextView(root.getContext());
    label.setText(R.string.ui_ai_trigger_multiline);
    label.setTextSize(15f);
    LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    label.setLayoutParams(lpLabel);

    SwitchCompat sw = new SwitchCompat(root.getContext());
    sw.setChecked(sp.getAiTriggerMultilineEnabled());
    sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
        sp.setAiTriggerMultilineEnabled(isChecked);
        int mode = SPManager.AI_TRIGGER_MULTILINE_KEEP_NEWLINES;
        try { mode = sp.getAiTriggerMultilineMode(); } catch (Throwable ignored) {}
        int msgRes;
        if (!isChecked || mode == SPManager.AI_TRIGGER_MULTILINE_OFF) msgRes = R.string.ui_ai_multiline_mode_off;
        else if (mode == SPManager.AI_TRIGGER_MULTILINE_NEWLINES_TO_SPACES) msgRes = R.string.ui_ai_multiline_mode_space;
        else if (mode == SPManager.AI_TRIGGER_MULTILINE_REMOVE_NEWLINES) msgRes = R.string.ui_ai_multiline_mode_remove;
        else msgRes = R.string.ui_ai_multiline_mode_keep;
        Toast.makeText(root.getContext(), root.getContext().getString(msgRes), Toast.LENGTH_SHORT).show();
        if (render != null) render.run();
    });

    row.addView(label);
    row.addView(sw);
    target.addView(row);

    if (patterns == null || patterns.isEmpty()) {
        TextView tv = new TextView(root.getContext());
        tv.setText(R.string.no_patterns_found);
        tv.setTextSize(16f);
        tv.setPadding(0, 8, 0, 8);
        container.addView(tv);
        return;
    }

    LayoutInflater inflater = LayoutInflater.from(root.getContext());
    for (int i = 0; i < patterns.size(); i++) {
        ParsePattern p = patterns.get(i);
        if (p == null) continue;

        View item = inflater.inflate(R.layout.item_model_option, container, false);
        TextView tv = item.findViewById(R.id.tv_model_name);
        TextView tvDesc = item.findViewById(R.id.tv_model_desc);
        ImageView iv = item.findViewById(R.id.iv_icon);
        ImageView ivEdit = item.findViewById(R.id.iv_edit);
        CheckBox cb = item.findViewById(R.id.cb_selected);

        String title = p.getType() != null ? p.getType().title : "Pattern";
        if (!p.isEnabled()) title = title + " (" + root.getContext().getString(R.string.ui_disabled) + ")";

        String symbol = "";
        try {
            symbol = PatternType.regexToSymbol(p.getPattern().pattern());
        } catch (Exception ignored) {}
        if (symbol == null) symbol = "";

        if (tv != null) tv.setText(title);
        if (tvDesc != null) {
            tvDesc.setVisibility(View.VISIBLE);
            String desc = (symbol.isEmpty())
                    ? (p.getType() != null ? p.getType().description : "")
                    : root.getContext().getString(R.string.ui_trigger_symbol_fmt, symbol);
            tvDesc.setText(desc);
        }
        if (iv != null) iv.setImageResource(R.drawable.ic_document_code_filled);
        if (cb != null) cb.setVisibility(View.GONE);

        if (ivEdit != null) {
            boolean editable = (p.getType() == null) || p.getType().editable;
            if (editable) {
                ivEdit.setVisibility(View.VISIBLE);
                final int editIndex = i;
                final ParsePattern editing = p;
                ivEdit.setOnClickListener(v -> showEditAiTriggerDialog(root.getContext(), sp, editIndex, editing, render));
            } else {
                ivEdit.setVisibility(View.GONE);
            }
        }

        container.addView(item);
    }
}

// -----------------------------
// Screen: AI_CLIPBOARD
// -----------------------------
private void renderAiClipboard(View root, LinearLayout fixedContainer, LinearLayout container, TextView tvTitle, TextView tvSubtitle, ImageView ivTitleIcon,
                               SPManager sp, Runnable render) {
    if (tvTitle != null) tvTitle.setText(R.string.ui_ai_clipboard);
    if (ivTitleIcon != null) ivTitleIcon.setImageResource(R.drawable.ic_copy);
    ensureClipboardSets();

    final Context ctx = root.getContext();
    final LayoutInflater inflater = LayoutInflater.from(ctx);

    // Pinned header: left/right action button groups + group selector + search (under title)
    final MaterialButton[] btnGroupSelectRef = new MaterialButton[1];
    final MaterialButton[] btnSelectModeRef = new MaterialButton[1];
    final MaterialButton[] btnGroupAddRef = new MaterialButton[1];
    final MaterialButton[] btnSortRef = new MaterialButton[1];
    final MaterialButton[] btnFavRef = new MaterialButton[1];
    final TextInputEditText[] etSearchRef = new TextInputEditText[1];
    final TextView[] tvGroupStatsRef = new TextView[1];

    if (fixedContainer != null) {
        fixedContainer.setVisibility(View.VISIBLE);
        fixedContainer.removeAllViews();

        // Single header view: Groups + actions + search
        View header = inflater.inflate(R.layout.view_clipboard_header, fixedContainer, false);

        // NOTE: Do NOT use negative margins to "cancel" the bottom-sheet padding.
        // Some devices/skins enable outline clipping on the sheet container, which will cut off
        // the left/right button columns. Keep the header within the sheet bounds so it is
        // adaptive across 1080p/2K screens.

        MaterialButton btnGroupSelect = header.findViewById(R.id.btn_group_select);
        MaterialButton btnSelectMode = header.findViewById(R.id.btn_select_mode);
        MaterialButton btnGroupAdd = header.findViewById(R.id.btn_group_add);
        TextView tvGroupStats = header.findViewById(R.id.tv_group_stats);

        MaterialButton btnSort = header.findViewById(R.id.btn_sort_time);
        MaterialButton btnFav = header.findViewById(R.id.btn_fav_toggle);
        TextInputLayout til = header.findViewById(R.id.til_clipboard_search);
        TextInputEditText et = header.findViewById(R.id.et_clipboard_search);

        btnGroupSelectRef[0] = btnGroupSelect;
        btnSelectModeRef[0] = btnSelectMode;
        btnGroupAddRef[0] = btnGroupAdd;
        tvGroupStatsRef[0] = tvGroupStats;

        btnSortRef[0] = btnSort;
        btnFavRef[0] = btnFav;
        etSearchRef[0] = et;

        if (btnGroupSelect != null) {
            updateClipboardGroupSelectButtonStyle(ctx, btnGroupSelect);
            btnGroupSelect.setText(getClipboardGroupLabel(ctx));
        }
        if (btnGroupAdd != null) updateClipboardGroupAddButtonStyle(ctx, btnGroupAdd);
        if (btnSelectMode != null) updateClipboardSelectModeButtonStyle(ctx, btnSelectMode);

        if (btnSort != null) updateClipboardSortButtonStyle(ctx, btnSort);
        if (til != null) {
            // Search is live while typing; no explicit search button.
            til.setEndIconMode(TextInputLayout.END_ICON_NONE);
        }
        if (btnFav != null) updateClipboardFavButtonStyle(ctx, btnFav);
        if (et != null) {
            et.setSingleLine(true);
            et.setInputType(InputType.TYPE_CLASS_TEXT);
            et.setText(clipboardSearchQuery != null ? clipboardSearchQuery : "");
            try { et.setSelection(et.getText() != null ? et.getText().length() : 0); } catch (Exception ignored) {}
        }

        fixedContainer.addView(header);
    }

    final Runnable[] renderListRef = new Runnable[1];

    renderListRef[0] = () -> {
        if (container == null) return;
        container.removeAllViews();

        // No auto-import here. Auto-import + listener are initialized once per screen entry.

        // Current selected group: empty string means "All"
        String selGroup = clipboardSelectedGroup != null ? clipboardSelectedGroup.trim() : "";

        List<AIClipboardStore.Entry> items = null;
        try {
            items = clipboardShowFavorites
                    ? AIClipboardStore.getFavoriteEntries(ctx)
                    : AIClipboardStore.getEntries(ctx);
            // In "All" view (no group filter), hide: (1) favorited items, (2) items already put into a group.
            // This matches user-requested behavior: grouped items should disappear from "All" and only show inside their group.
            if (!clipboardShowFavorites && items != null && !items.isEmpty() && selGroup.isEmpty()) {
                ArrayList<AIClipboardStore.Entry> filtered = new ArrayList<>();
                for (AIClipboardStore.Entry it : items) {
                    if (it == null) continue;
                    if (it.favorite) continue;
                    String g = it.group != null ? it.group.trim() : "";
                    if (!g.isEmpty()) continue;
                    filtered.add(it);
                }
                items = filtered;
            }

            // Group stats (right side of group bar). This ignores the search query.
            List<AIClipboardStore.Entry> itemsForStats = items;
            // Favorites view is global: ignore group filter for stats.
            if (!clipboardShowFavorites && itemsForStats != null && !itemsForStats.isEmpty() && !selGroup.isEmpty()) {
                ArrayList<AIClipboardStore.Entry> filteredStats = new ArrayList<>();
                for (AIClipboardStore.Entry it : itemsForStats) {
                    if (it == null) continue;
                    String g = it.group != null ? it.group.trim() : "";
                    if (selGroup.equalsIgnoreCase(g)) {
                        filteredStats.add(it);
                    }
                }
                itemsForStats = filteredStats;
            }
            updateClipboardGroupBarStats(ctx, sp, tvGroupStatsRef[0], itemsForStats);

            // Search filter
            String q = clipboardSearchQuery != null ? clipboardSearchQuery.trim() : "";
            if (items != null && !items.isEmpty() && !q.isEmpty()) {
                String qLower = q.toLowerCase(Locale.ROOT);
                ArrayList<AIClipboardStore.Entry> filtered = new ArrayList<>();
                for (AIClipboardStore.Entry it : items) {
                    if (it == null || it.text == null) continue;
                    String t = it.text;
                    if (t.toLowerCase(Locale.ROOT).contains(qLower)) {
                        filtered.add(it);
                    }
                }
                items = filtered;
            }

            // Group filter (favorites view is global: ignore group filter)
            if (!clipboardShowFavorites && items != null && !items.isEmpty() && !selGroup.isEmpty()) {
                ArrayList<AIClipboardStore.Entry> filtered = new ArrayList<>();
                for (AIClipboardStore.Entry it : items) {
                    if (it == null) continue;
                    String g = it.group != null ? it.group.trim() : "";
                    if (selGroup.equalsIgnoreCase(g)) {
                        filtered.add(it);
                    }
                }
                items = filtered;
            }

            // Time sort: default newest-first; toggle to oldest-first
            if (clipboardSortAsc && items != null && items.size() > 1) {
                java.util.Collections.reverse(items);
            }
        } catch (Exception ignored) {}

        // Subtitle: show total entries (including favorites) + max capacity.
        // When in selection mode, also show how many items are currently selected.
        int totalAll = 0;
        int maxItems = 0;
        try { totalAll = AIClipboardStore.getCount(ctx); } catch (Throwable ignored) {}
        try { maxItems = AIClipboardStore.getMaxItems(); } catch (Throwable ignored) {}
        if (maxItems <= 0) maxItems = 10_000;

        String subtitleText;
        if (clipboardSelectionMode) {
            int selected = clipboardSelectedItems != null ? clipboardSelectedItems.size() : 0;
            subtitleText = ctx.getString(R.string.ui_ai_clipboard_desc_full_sel, selected, totalAll, maxItems);
        } else {
            subtitleText = ctx.getString(R.string.ui_ai_clipboard_desc_full, totalAll, maxItems);
        }
        setSubtitle(tvSubtitle, subtitleText);

        if (items.isEmpty()) {
            TextView tvEmpty = new TextView(ctx);
            tvEmpty.setText(R.string.ui_clipboard_empty);
            tvEmpty.setTextSize(16f);
            tvEmpty.setPadding(0, 8, 0, 8);
            container.addView(tvEmpty);
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            AIClipboardStore.Entry e = items.get(i);
            if (e == null) continue;

            View item = inflater.inflate(R.layout.item_clipboard_entry, container, false);
            View iconContainer = item.findViewById(R.id.icon_container);
            View moveContainer = item.findViewById(R.id.icon_move_container);
            ImageView ivMove = item.findViewById(R.id.iv_move);
            TextView tv = item.findViewById(R.id.tv_model_name);
            TextView tvDesc = item.findViewById(R.id.tv_model_desc);
            TextView tvCharCount = item.findViewById(R.id.tv_clip_char_count);
            ImageView iv = item.findViewById(R.id.iv_icon);
            ImageView ivEdit = item.findViewById(R.id.iv_edit);
            ImageView ivExpand = item.findViewById(R.id.iv_action_expand);
            ImageView ivDelete = item.findViewById(R.id.iv_action_delete);
            CheckBox cb = item.findViewById(R.id.cb_selected);

            final String fullText = e.text != null ? e.text : "";
            final int storeIndex = e.storeIndex;

            String title = twoLinePreview(e.text);
            String desc = formatTime(ctx, e.timeMs);

            if (tv != null) {
                tv.setSingleLine(false);
                boolean expanded = clipboardExpanded.contains(e.storeIndex);
                tv.setMaxLines(expanded ? 5 : 2);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setText(title);
            }
            if (tvDesc != null) {
                tvDesc.setVisibility(View.VISIBLE);
                tvDesc.setText(desc);
                // User-requested: time text shown in red.
                try { tvDesc.setTextColor(ContextCompat.getColor(ctx, R.color.error)); } catch (Throwable ignored) {}
            }

            // Time right side: show clipboard text length (character count)
            if (tvCharCount != null) {
                int charCount = 0;
                try {
                    if (fullText != null) {
                        // Use Unicode code points so emoji count as 1.
                        charCount = fullText.codePointCount(0, fullText.length());
                    }
                } catch (Throwable ignored) {}
                tvCharCount.setVisibility(View.VISIBLE);
                tvCharCount.setText(ctx.getString(R.string.ui_clipboard_chars_fmt, charCount));
                // Keep the same red style as time for visual consistency.
                try { tvCharCount.setTextColor(ContextCompat.getColor(ctx, R.color.error)); } catch (Throwable ignored) {}
            }

            // Left icon = move (folder)
            if (ivMove != null) {
                ivMove.setImageResource(R.drawable.ic_folder_open_filled);
                try { ivMove.setColorFilter(ContextCompat.getColor(ctx, R.color.primary)); } catch (Throwable ignored) {}
            }

            // Left icon = favorite (star)
            if (iv != null) {
                iv.setImageResource(e.favorite ? R.drawable.ic_star_filled : R.drawable.ic_star);
                if (e.favorite) {
                    try { iv.setColorFilter(ContextCompat.getColor(ctx, R.color.primary)); } catch (Throwable ignored) {}
                } else {
                    try { iv.setColorFilter(null); } catch (Throwable ignored) {}
                }
            }

            // Multi-select checkbox (only visible in selection mode)
            if (cb != null) {
                boolean selMode = clipboardSelectionMode;
                cb.setVisibility(selMode ? View.VISIBLE : View.GONE);
                // IMPORTANT: some Material themes apply a default buttonTint which will tint the
                // entire checkbox drawable (including our white check icon) to the primary color,
                // making the ✓ effectively invisible on a primary-colored box.
                // Force-disable tint so checkbox_square.xml renders as designed.
                try { cb.setButtonTintList(null); } catch (Throwable ignored) {}
                try {
                    cb.setOnCheckedChangeListener(null);
                } catch (Throwable ignored) {}
                try {
                    cb.setChecked(selMode && clipboardSelectedItems.contains(storeIndex));
                } catch (Throwable ignored) {}
                if (selMode) {
                    cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        if (isChecked) {
                            clipboardSelectedItems.add(storeIndex);
                        } else {
                            clipboardSelectedItems.remove(storeIndex);
                        }
                        if (renderListRef[0] != null) renderListRef[0].run();
                    });
                }
            }
            if (ivEdit != null) ivEdit.setVisibility(View.GONE);

            // Right actions: expand + delete
            if (ivExpand != null) {
                ivExpand.setVisibility(View.VISIBLE);
                ivExpand.setImageResource(R.drawable.ic_expand_more);
                boolean expanded = clipboardExpanded.contains(storeIndex);
                ivExpand.setRotation(expanded ? 180f : 0f);
                ivExpand.setOnClickListener(v -> {
                    if (clipboardExpanded.contains(storeIndex)) {
                        clipboardExpanded.remove(storeIndex);
                    } else {
                        clipboardExpanded.add(storeIndex);
                    }
                    if (renderListRef[0] != null) renderListRef[0].run();
                });
            }
            if (ivDelete != null) {
                ivDelete.setVisibility(View.VISIBLE);
                ivDelete.setImageResource(R.drawable.ic_delete);
                // User-requested: trash icon shown in red.
                try { ivDelete.setColorFilter(ContextCompat.getColor(ctx, R.color.error)); } catch (Throwable ignored) {}
                ivDelete.setOnClickListener(v -> {
                    // Prevent immediate re-add if the current system clipboard still equals this text.
                    clipboardIgnoreText = fullText;
                    AIClipboardStore.deleteAt(ctx, storeIndex);
                    Toast.makeText(ctx, R.string.ui_clipboard_deleted, Toast.LENGTH_SHORT).show();
                    if (renderListRef[0] != null) renderListRef[0].run();
                });
            }

            // Tap row:
            // - Normal mode: paste/insert into the current input field
            // - Selection mode: toggle selection
            item.setOnClickListener(v -> {
                if (clipboardSelectionMode) {
                    if (clipboardSelectedItems.contains(storeIndex)) {
                        clipboardSelectedItems.remove(storeIndex);
                    } else {
                        clipboardSelectedItems.add(storeIndex);
                    }
                    if (renderListRef[0] != null) renderListRef[0].run();
                    return;
                }

                handleAiClipboardItemClick(ctx, fullText);

            });

            // Long-press:
            // - Normal mode: edit clipboard entry
            // - Selection mode: toggle selection
            item.setOnLongClickListener(v -> {
                if (clipboardSelectionMode) {
                    if (clipboardSelectedItems.contains(storeIndex)) {
                        clipboardSelectedItems.remove(storeIndex);
                    } else {
                        clipboardSelectedItems.add(storeIndex);
                    }
                    if (renderListRef[0] != null) renderListRef[0].run();
                    return true;
                }
                showEditClipboardEntryDialog(ctx, storeIndex, fullText, () -> {
                    if (renderListRef[0] != null) renderListRef[0].run();
                });
                return true;
            });

            // Tap left move button:
            // - Normal mode: move this entry to a group
            // - Selection mode: move ALL selected entries to a group
            if (moveContainer != null) {
                moveContainer.setOnClickListener(v -> {
                    if (clipboardSelectionMode) {
                        if (clipboardSelectedItems.isEmpty()) {
                            clipboardSelectedItems.add(storeIndex);
                        }
                        showClipboardMoveSelectedToGroupMenu(ctx, v, sp, new java.util.ArrayList<>(clipboardSelectedItems), () -> {
                            // After a bulk move, exit selection mode (user-requested)
                            clipboardSelectedItems.clear();
                            clipboardSelectionMode = false;
                            try { if (btnSelectModeRef[0] != null) updateClipboardSelectModeButtonStyle(ctx, btnSelectModeRef[0]); } catch (Throwable ignored) {}
                            if (renderListRef[0] != null) renderListRef[0].run();
                        });
                        if (renderListRef[0] != null) renderListRef[0].run();
                        return;
                    }
                    showClipboardMoveToGroupMenu(ctx, v, sp, storeIndex, () -> {
                        if (renderListRef[0] != null) renderListRef[0].run();
                    });
                });
            }

            // Tap left star:
            // - Normal mode: toggle favorite for this entry
            // - Selection mode: add ALL selected entries to favorites
            View favClickTarget = iconContainer != null ? iconContainer : iv;
            if (favClickTarget != null) {
                favClickTarget.setOnClickListener(v -> {
                    if (clipboardSelectionMode) {
                        if (clipboardSelectedItems.isEmpty()) {
                            clipboardSelectedItems.add(storeIndex);
                        }

                        // In Favorites view: star means "remove from favorites".
                        // In Normal view: star means "add to favorites".
                        final boolean toFavorite = !clipboardShowFavorites;
                        int n = 0;
                        try {
                            for (Integer idx : clipboardSelectedItems) {
                                if (idx == null) continue;
                                AIClipboardStore.setFavorite(ctx, idx, toFavorite);
                                n++;
                            }
                        } catch (Throwable ignored) {}

                        // After bulk favorite/unfavorite, exit selection mode (user-requested)
                        clipboardSelectedItems.clear();
                        clipboardSelectionMode = false;
                        try { if (btnSelectModeRef[0] != null) updateClipboardSelectModeButtonStyle(ctx, btnSelectModeRef[0]); } catch (Throwable ignored) {}

                        try {
                            Toast.makeText(
                                    ctx,
                                    ctx.getString(toFavorite ? R.string.msg_clipboard_favorited_n : R.string.msg_clipboard_unfavorited_n, n),
                                    Toast.LENGTH_SHORT
                            ).show();
                        } catch (Throwable ignored) {
                            Toast.makeText(ctx, R.string.ui_clipboard_favorite_toggled, Toast.LENGTH_SHORT).show();
                        }

                        if (renderListRef[0] != null) renderListRef[0].run();
                        return;
                    }
                    AIClipboardStore.toggleFavorite(ctx, storeIndex);
                    Toast.makeText(ctx, R.string.ui_clipboard_favorite_toggled, Toast.LENGTH_SHORT).show();
                    if (renderListRef[0] != null) renderListRef[0].run();
                });
            }

            container.addView(item);
        }
    };

    // Wire up search/sort interactions (update list only, keep the search bar focused)
    // Group bar interactions
    if (btnGroupSelectRef[0] != null) {
        btnGroupSelectRef[0].setOnClickListener(v -> {
	        // Changing group filter should not keep an old multi-selection.
	        if (clipboardSelectionMode) {
	            clipboardSelectedItems.clear();
	        }
            showClipboardGroupFilterMenu(ctx, v, sp, () -> {
                try { btnGroupSelectRef[0].setText(getClipboardGroupLabel(ctx)); } catch (Throwable ignored) {}
                try { updateClipboardGroupSelectButtonStyle(ctx, btnGroupSelectRef[0]); } catch (Throwable ignored) {}
                if (renderListRef[0] != null) renderListRef[0].run();
            });
        });
    }
	if (btnSelectModeRef[0] != null) {
	    btnSelectModeRef[0].setOnClickListener(v -> {
	        clipboardSelectionMode = !clipboardSelectionMode;
	        if (!clipboardSelectionMode) {
	            clipboardSelectedItems.clear();
	        }
	        try { updateClipboardSelectModeButtonStyle(ctx, btnSelectModeRef[0]); } catch (Throwable ignored) {}
	        if (renderListRef[0] != null) renderListRef[0].run();
	    });
	}
    if (btnGroupAddRef[0] != null) {
        btnGroupAddRef[0].setOnClickListener(v -> {
            showCreateClipboardGroupDialog(ctx, sp, () -> {
                try { if (btnGroupSelectRef[0] != null) btnGroupSelectRef[0].setText(getClipboardGroupLabel(ctx)); } catch (Throwable ignored) {}
                try { if (btnGroupSelectRef[0] != null) updateClipboardGroupSelectButtonStyle(ctx, btnGroupSelectRef[0]); } catch (Throwable ignored) {}
                if (renderListRef[0] != null) renderListRef[0].run();
            });
        });
    }

    if (btnSortRef[0] != null) {
        btnSortRef[0].setOnClickListener(v -> {
            clipboardSortAsc = !clipboardSortAsc;
            updateClipboardSortButtonStyle(ctx, btnSortRef[0]);
            if (renderListRef[0] != null) renderListRef[0].run();
        });
    }

    if (btnFavRef[0] != null) {
        btnFavRef[0].setOnClickListener(v -> {
	        // Switching view should not keep an old multi-selection.
	        if (clipboardSelectionMode) {
	            clipboardSelectedItems.clear();
	        }
            final boolean toFavoritesView = !clipboardShowFavorites;
            // Favorites list should be global by default. If the user toggles favorites while
            // browsing inside a specific group, reset the group filter so favorites are visible.
            // When leaving favorites, restore the previous group filter.
            if (toFavoritesView) {
                clipboardSelectedGroupBeforeFav = clipboardSelectedGroup;
                clipboardSelectedGroup = "";
            } else {
                if (clipboardSelectedGroupBeforeFav != null) {
                    clipboardSelectedGroup = clipboardSelectedGroupBeforeFav;
                }
                clipboardSelectedGroupBeforeFav = null;
            }

            clipboardShowFavorites = toFavoritesView;
            updateClipboardFavButtonStyle(ctx, btnFavRef[0]);
            try {
                if (btnGroupSelectRef[0] != null) {
                    btnGroupSelectRef[0].setText(getClipboardGroupLabel(ctx));
                    updateClipboardGroupSelectButtonStyle(ctx, btnGroupSelectRef[0]);
                }
            } catch (Throwable ignored) {}
            if (renderListRef[0] != null) renderListRef[0].run();
        });
    }

    if (etSearchRef[0] != null) {
        etSearchRef[0].addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                String q = editable != null ? editable.toString() : "";
                if (q == null) q = "";
                if (!q.equals(clipboardSearchQuery)) {
                    clipboardSearchQuery = q;
                    if (renderListRef[0] != null) renderListRef[0].run();
                }
            }
        });
    }

    // Init clipboard capture (once per screen entry)
    clipboardUiRenderList = renderListRef[0];
    enableClipboardUiListener(ctx);
    if (!clipboardAutoAppendDone) {
        clipboardAutoAppendDone = true;
        tryAppendCurrentClipboard(ctx);
    }

    // Initial list render
    if (renderListRef[0] != null) renderListRef[0].run();
}

private void updateClipboardSortButtonStyle(Context ctx, MaterialButton btnSort) {
    if (ctx == null || btnSort == null) return;
    try {
        int primary = ContextCompat.getColor(ctx, R.color.primary);
	        int transparent = 0x00000000;        // clipboardSortAsc: true = oldest->newest ("顺"), false = newest->oldest ("倒")
	        // Remove the clock icon: keep only the text + highlight states
        btnSort.setIcon(null);
	        // Square button (border comes from the parent group)
	        try { btnSort.setCornerRadius(dp(ctx, 0)); } catch (Throwable ignored) {}
        btnSort.setIconPadding(0);
        try { btnSort.setIconSize(0); } catch (Throwable ignored) {}
        try { btnSort.setCompoundDrawablePadding(0); } catch (Throwable ignored) {}
        btnSort.setText(clipboardSortAsc ? ctx.getString(R.string.ui_sort_asc) : ctx.getString(R.string.ui_sort_desc));
        btnSort.setTextColor(primary);
        btnSort.setRotation(0f);
	        btnSort.setStrokeWidth(0);
	        btnSort.setStrokeColor(ColorStateList.valueOf(transparent));

        // Highlight state: give a soft filled background when "顺" (asc)
        if (clipboardSortAsc) {
            int bg = (0x22 << 24) | (primary & 0x00FFFFFF);
            btnSort.setBackgroundTintList(ColorStateList.valueOf(bg));
        } else {
            btnSort.setBackgroundTintList(ColorStateList.valueOf(transparent));
        }
    } catch (Throwable ignored) {
    }
}

private void updateClipboardFavButtonStyle(Context ctx, MaterialButton btnFav) {
    if (ctx == null || btnFav == null) return;
    try {
        int primary = ContextCompat.getColor(ctx, R.color.primary);
        btnFav.setIconResource(clipboardShowFavorites ? R.drawable.ic_star_filled : R.drawable.ic_star);
        btnFav.setIconTint(ColorStateList.valueOf(primary));
	    // Square button (border comes from the parent group)
	    try { btnFav.setCornerRadius(dp(ctx, 0)); } catch (Throwable ignored) {}
	    btnFav.setStrokeWidth(0);
	    btnFav.setStrokeColor(ColorStateList.valueOf(0x00000000));
        // Highlight when favorites view is active
        if (clipboardShowFavorites) {
            int bg = (0x22 << 24) | (primary & 0x00FFFFFF);
            btnFav.setBackgroundTintList(ColorStateList.valueOf(bg));
        } else {
            btnFav.setBackgroundTintList(ColorStateList.valueOf(0x00000000));
        }
    } catch (Throwable ignored) {
    }
}



private void updateClipboardGroupSelectButtonStyle(Context ctx, MaterialButton btn) {
    if (ctx == null || btn == null) return;
    try {
        int primary = ContextCompat.getColor(ctx, R.color.primary);
        btn.setIconResource(R.drawable.ic_expand_more);
        btn.setIconTint(ColorStateList.valueOf(primary));
        try { btn.setIconSize(dp(ctx, 18)); } catch (Throwable ignored) {}
        try { btn.setIconPadding(dp(ctx, 6)); } catch (Throwable ignored) {}
        // User request: remove the rounded pill border. The header panel now provides a
        // unified rectangular border, so this button should be flat/rectangular.
        try { btn.setCornerRadius(dp(ctx, 0)); } catch (Throwable ignored) {}
        btn.setTextColor(primary);

        boolean inGroup = clipboardSelectedGroup != null && !clipboardSelectedGroup.trim().isEmpty();
        // Keep a subtle highlight when a specific group is selected, but do not draw an outline.
        btn.setStrokeWidth(0);
        btn.setStrokeColor(ColorStateList.valueOf(0x00000000));
        if (inGroup) {
            int bg = (0x22 << 24) | (primary & 0x00FFFFFF);
            btn.setBackgroundTintList(ColorStateList.valueOf(bg));
        } else {
            btn.setBackgroundTintList(ColorStateList.valueOf(0x00000000));
        }
    } catch (Throwable ignored) {}
}

private void updateClipboardGroupAddButtonStyle(Context ctx, MaterialButton btn) {
    if (ctx == null || btn == null) return;
    try {
        int primary = ContextCompat.getColor(ctx, R.color.primary);
        btn.setIconResource(R.drawable.ic_add);
        btn.setIconTint(ColorStateList.valueOf(primary));
        // Square button (border comes from the parent group)
        try { btn.setCornerRadius(dp(ctx, 0)); } catch (Throwable ignored) {}
        btn.setStrokeWidth(0);
        btn.setStrokeColor(ColorStateList.valueOf(0x00000000));
        btn.setBackgroundTintList(ColorStateList.valueOf(0x00000000));
    } catch (Throwable ignored) {}
}

	private void updateClipboardSelectModeButtonStyle(Context ctx, MaterialButton btn) {
	    if (ctx == null || btn == null) return;
	    try {
	        int primary = ContextCompat.getColor(ctx, R.color.primary);
	        // Keep icon consistent; use highlight state to show whether selection mode is active.
	        btn.setIconResource(R.drawable.ic_text_selection_filled);
	        btn.setIconTint(ColorStateList.valueOf(primary));
	        // Square button (border comes from the parent group)
	        try { btn.setCornerRadius(dp(ctx, 0)); } catch (Throwable ignored) {}
	        btn.setStrokeWidth(0);
	        btn.setStrokeColor(ColorStateList.valueOf(0x00000000));

	        if (clipboardSelectionMode) {
	            int bg = (0x22 << 24) | (primary & 0x00FFFFFF);
	            btn.setBackgroundTintList(ColorStateList.valueOf(bg));
	        } else {
	            btn.setBackgroundTintList(ColorStateList.valueOf(0x00000000));
	        }
	    } catch (Throwable ignored) {}
	}

private void updateClipboardGroupBarStats(Context ctx, SPManager sp, TextView tv, List<AIClipboardStore.Entry> itemsInCurrentGroup) {
    if (ctx == null || tv == null) return;

    int groupCount = 1; // +1 for "All"
    try {
        List<String> groups = sp != null ? sp.getAiClipboardGroups() : null;
        groupCount = 1 + (groups != null ? groups.size() : 0);
    } catch (Throwable ignored) {}

    int itemCount = itemsInCurrentGroup != null ? itemsInCurrentGroup.size() : 0;
    long charSum = 0;
    if (itemsInCurrentGroup != null && !itemsInCurrentGroup.isEmpty()) {
        for (AIClipboardStore.Entry e : itemsInCurrentGroup) {
            if (e == null || e.text == null) continue;
            String t = e.text;
            try {
                charSum += t.codePointCount(0, t.length());
            } catch (Throwable ignored) {
                try { charSum += t.length(); } catch (Throwable ignored2) {}
            }
        }
    }

    try {
        tv.setText(ctx.getString(R.string.ui_clipboard_group_stats_fmt, groupCount, itemCount, charSum));
    } catch (Throwable ignored) {
        tv.setText(groupCount + " · " + itemCount + " · " + charSum);
    }
    tv.setVisibility(View.VISIBLE);
}


private void updateClipboardGroupPopupStats(Context ctx, TextView tv) {
    if (ctx == null || tv == null) return;

    String selGroup = clipboardSelectedGroup != null ? clipboardSelectedGroup.trim() : "";

    // Label should reflect the current view mode:
    // - Normal view: "All" or specific group name
    // - Favorites view: "Favorites" (or "Favorites / <group>" when a group filter is active)
    String label;
    try {
        if (clipboardShowFavorites) {
            label = ctx.getString(R.string.ui_favorites);
        } else {
            label = selGroup.isEmpty() ? ctx.getString(R.string.ui_group_all) : selGroup;
        }
    } catch (Throwable ignored) {
        if (clipboardShowFavorites) {
            label = "Favorites";
        } else {
            label = selGroup.isEmpty() ? "All" : selGroup;
        }
    }

    List<AIClipboardStore.Entry> items = null;
    try {
        items = clipboardShowFavorites
                ? AIClipboardStore.getFavoriteEntries(ctx)
                : AIClipboardStore.getEntries(ctx);

        // Match "All" behavior in the list: only show ungrouped + non-favorite when not in favorites view.
        if (!clipboardShowFavorites && items != null && !items.isEmpty() && selGroup.isEmpty()) {
            ArrayList<AIClipboardStore.Entry> filtered = new ArrayList<>();
            for (AIClipboardStore.Entry it : items) {
                if (it == null) continue;
                if (it.favorite) continue;
                String g = it.group != null ? it.group.trim() : "";
                if (!g.isEmpty()) continue;
                filtered.add(it);
            }
            items = filtered;
        }

        // Group filter (stats should ignore search). Favorites view is global, so ignore group filter.
        if (!clipboardShowFavorites && items != null && !items.isEmpty() && !selGroup.isEmpty()) {
            ArrayList<AIClipboardStore.Entry> filtered = new ArrayList<>();
            for (AIClipboardStore.Entry it : items) {
                if (it == null) continue;
                String g = it.group != null ? it.group.trim() : "";
                if (selGroup.equalsIgnoreCase(g)) {
                    filtered.add(it);
                }
            }
            items = filtered;
        }
    } catch (Throwable ignored) {}

    int itemCount = items != null ? items.size() : 0;
    long charSum = 0;
    if (items != null && !items.isEmpty()) {
        for (AIClipboardStore.Entry e : items) {
            if (e == null || e.text == null) continue;
            String t = e.text;
            try {
                charSum += t.codePointCount(0, t.length());
            } catch (Throwable ignored) {
                try { charSum += t.length(); } catch (Throwable ignored2) {}
            }
        }
    }

    try {
        tv.setText(ctx.getString(R.string.ui_group_current_stats_fmt, label, itemCount, charSum));
    } catch (Throwable ignored) {
        tv.setText(label + " · " + itemCount + " · " + charSum);
    }
    tv.setVisibility(View.VISIBLE);
}


private String getClipboardGroupLabel(Context ctx) {
    if (ctx == null) return "";
    String sel = clipboardSelectedGroup != null ? clipboardSelectedGroup.trim() : "";

    final String prefix = ctx.getString(R.string.ui_clipboard_groups) + ": ";

    // Favorites view is global: always show "Favorites" (do not append group).
    if (clipboardShowFavorites) {
        return prefix + ctx.getString(R.string.ui_favorites);
    }


    if (sel.isEmpty()) {
        return prefix + ctx.getString(R.string.ui_group_all);
    }
    return prefix + sel;
}

private void showClipboardGroupFilterMenu(Context ctx, View anchor, SPManager sp, Runnable afterSelect) {
    if (ctx == null || anchor == null || sp == null) return;

    List<String> groups = null;
    try { groups = sp.getAiClipboardGroups(); } catch (Throwable ignored) {}
    if (groups == null) groups = new ArrayList<>();

    final ArrayList<String> groupList = new ArrayList<>(groups);

    View content = LayoutInflater.from(ctx).inflate(R.layout.popup_clipboard_group_manager, null);
    RecyclerView rv = content != null ? content.findViewById(R.id.rv_groups) : null;
    if (rv == null) return;

    // Width: align with anchor and keep a minimum so long group names can fit.
    final int width = Math.max(anchor.getWidth(), dp(ctx, 240));

    rv.setLayoutManager(new LinearLayoutManager(ctx));

    final ClipboardGroupManageAdapter adapter = new ClipboardGroupManageAdapter(ctx, groupList, clipboardShowFavorites);
    rv.setAdapter(adapter);

    // Keep the popup looking like the original (wrap-content), but when there are many groups,
    // cap the RecyclerView height so it becomes scrollable instead of pushing content off-screen.
    try {
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int maxPopupH = (int) (screenH * 0.55f);
        if (maxPopupH < dp(ctx, 260)) maxPopupH = dp(ctx, 260);

        int pad = dp(ctx, 10);
        int titleH = 0, hintH = 0;
        TextView tvTitle = content.findViewById(R.id.tv_title);
        TextView tvHint = content.findViewById(R.id.tv_hint);

        int textW = Math.max(0, width - pad * 2);
        int wSpec = View.MeasureSpec.makeMeasureSpec(textW, View.MeasureSpec.AT_MOST);
        if (tvTitle != null) {
            tvTitle.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            titleH = tvTitle.getMeasuredHeight();
        }
        if (tvHint != null) {
            tvHint.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            hintH = tvHint.getMeasuredHeight();
        }

        int headerH = pad * 2 + titleH + hintH + dp(ctx, 4) + dp(ctx, 8);
        int maxRvH = maxPopupH - headerH;
        if (maxRvH < dp(ctx, 180)) maxRvH = dp(ctx, 180);

        // Estimate one row height and size the list accordingly.
        int rowH = dp(ctx, 56);
        View tmp = LayoutInflater.from(ctx).inflate(R.layout.item_clipboard_group_manage, rv, false);
        if (tmp != null) {
            tmp.measure(
                    View.MeasureSpec.makeMeasureSpec(textW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            int mh = tmp.getMeasuredHeight();
            if (mh > 0) rowH = mh;
        }

        int totalItems = groupList.size() + 1; // +"All"
        int desired = rowH * totalItems;
        int finalH = Math.min(desired, maxRvH);
        ViewGroup.LayoutParams lp = rv.getLayoutParams();
        if (lp != null) {
            lp.height = finalH;
            rv.setLayoutParams(lp);
        }
    } catch (Throwable ignored) {}

    final PopupWindow[] popupRef = new PopupWindow[1];

    // Drag reorder: only for user groups (pos >= 1). "All" (pos 0) is fixed.
    final ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
        @Override public boolean isLongPressDragEnabled() { return false; } // long-press is used for rename
        @Override public boolean isItemViewSwipeEnabled() { return false; }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            if (recyclerView == null || viewHolder == null) return 0;
            int pos = viewHolder.getBindingAdapterPosition();
            if (pos <= 0) return 0; // "All" can't be moved
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
            return makeMovementFlags(dragFlags, 0);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
            if (viewHolder == null || target == null) return false;
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from <= 0 || to <= 0) return false;

            int fromIdx = from - 1;
            int toIdx = to - 1;
            if (fromIdx < 0 || toIdx < 0 || fromIdx >= groupList.size() || toIdx >= groupList.size()) return false;

            java.util.Collections.swap(groupList, fromIdx, toIdx);
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {}

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            try { sp.setAiClipboardGroups(groupList); } catch (Throwable ignored) {}
            if (afterSelect != null) afterSelect.run();
        }
    });
    helper.attachToRecyclerView(rv);

    adapter.setItemTouchHelper(helper);
    adapter.setSelectedGroup(clipboardSelectedGroup);

    adapter.setListener(new ClipboardGroupManageAdapter.Listener() {
        @Override public void onSelect(String groupName) {
            // Favorites view is global: keep group filter cleared so favorites are always visible.
            if (clipboardShowFavorites) {
                clipboardSelectedGroup = "";
            } else {
                clipboardSelectedGroup = groupName != null ? groupName.trim() : "";
            }
            try {
                if (popupRef[0] != null) popupRef[0].dismiss();
            } catch (Throwable ignored) {}
            if (afterSelect != null) afterSelect.run();
        }

        @Override public void onRenameRequested(int groupIndex) {
            showRenameClipboardGroupDialog(ctx, sp, groupList, groupIndex, adapter, afterSelect);
        }

        @Override public void onDeleteRequested(int groupIndex) {
            showDeleteClipboardGroupDialog(ctx, sp, groupList, groupIndex, adapter, afterSelect);
        }

        @Override public void onStartDrag(RecyclerView.ViewHolder vh) {
            try { helper.startDrag(vh); } catch (Throwable ignored) {}
        }
    });

    PopupWindow pw = new PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
    pw.setOutsideTouchable(true);
    pw.setFocusable(true);
    pw.setBackgroundDrawable(new ColorDrawable(0x00000000));
    try { pw.setElevation(dp(ctx, 8)); } catch (Throwable ignored) {}
    popupRef[0] = pw;

    try { pw.showAsDropDown(anchor, 0, dp(ctx, 6)); } catch (Throwable ignored) {}
}

private void showRenameClipboardGroupDialog(Context ctx, SPManager sp, ArrayList<String> groups, int index,
                                           ClipboardGroupManageAdapter adapter, Runnable afterChange) {
    if (ctx == null || sp == null || groups == null) return;
    if (index < 0 || index >= groups.size()) return;

    final String oldName = groups.get(index) != null ? groups.get(index).trim() : "";
    if (oldName.isEmpty()) return;

    final EditText et = new EditText(ctx);
    et.setSingleLine(true);
    et.setImeOptions(EditorInfo.IME_ACTION_DONE);
    et.setInputType(InputType.TYPE_CLASS_TEXT);
    et.setText(oldName);
    try { et.setSelection(oldName.length()); } catch (Throwable ignored) {}
    int p = dp(ctx, 12);
    et.setPadding(p, p, p, p);

    new AlertDialog.Builder(ctx)
            .setTitle(R.string.ui_rename_group)
            .setView(et)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (d, w) -> {
                String name = et.getText() != null ? et.getText().toString() : "";
                if (name == null) name = "";
                name = name.trim();

                if (name.isEmpty()) {
                    Toast.makeText(ctx, R.string.msg_group_name_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (name.length() > 80) name = name.substring(0, 80);

                // Unique check (case-insensitive), ignore self
                for (int i = 0; i < groups.size(); i++) {
                    if (i == index) continue;
                    String g = groups.get(i);
                    if (g == null) continue;
                    if (g.trim().equalsIgnoreCase(name)) {
                        Toast.makeText(ctx, R.string.msg_group_name_exists, Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                groups.set(index, name);
                try { sp.setAiClipboardGroups(groups); } catch (Throwable ignored) {}

                // Rename the group on all existing clipboard entries
                try { AIClipboardStore.renameGroup(ctx, oldName, name); } catch (Throwable ignored) {}
                try { if (adapter != null) adapter.rebuildStats(); } catch (Throwable ignored) {}

                // Keep current filter in sync if it was the renamed one
                if (clipboardSelectedGroup != null && clipboardSelectedGroup.trim().equalsIgnoreCase(oldName)) {
                    clipboardSelectedGroup = name;
                }

                Toast.makeText(ctx, ctx.getString(R.string.msg_group_renamed, name), Toast.LENGTH_SHORT).show();
                try { if (adapter != null) adapter.setSelectedGroup(clipboardSelectedGroup); } catch (Throwable ignored) {}
                try { if (adapter != null) adapter.notifyItemChanged(index + 1); } catch (Throwable ignored) {}
                if (afterChange != null) afterChange.run();
            })
            .show();
}

private void showDeleteClipboardGroupDialog(Context ctx, SPManager sp, ArrayList<String> groups, int index,
                                           ClipboardGroupManageAdapter adapter, Runnable afterChange) {
    if (ctx == null || sp == null || groups == null) return;
    if (index < 0 || index >= groups.size()) return;

    final String name = groups.get(index) != null ? groups.get(index).trim() : "";
    if (name.isEmpty()) return;

    new AlertDialog.Builder(ctx)
            .setTitle(R.string.ui_delete_group)
            .setMessage(ctx.getString(R.string.msg_delete_group_confirm, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (d, w) -> {
                // 1) Move all clipboard entries under this group back to "All"
                try { AIClipboardStore.clearGroup(ctx, name); } catch (Throwable ignored) {}

                try { if (adapter != null) adapter.rebuildStats(); } catch (Throwable ignored) {}

                // 2) Remove group from list and persist
                try { groups.remove(index); } catch (Throwable ignored) {}
                try { sp.setAiClipboardGroups(groups); } catch (Throwable ignored) {}

                // 3) If current filter is this group, switch back to All
                if (clipboardSelectedGroup != null && clipboardSelectedGroup.trim().equalsIgnoreCase(name)) {
                    clipboardSelectedGroup = "";
                }

                try { if (adapter != null) adapter.setSelectedGroup(clipboardSelectedGroup); } catch (Throwable ignored) {}
                try { if (adapter != null) adapter.notifyDataSetChanged(); } catch (Throwable ignored) {}

                Toast.makeText(ctx, ctx.getString(R.string.msg_group_deleted, name), Toast.LENGTH_SHORT).show();
                if (afterChange != null) afterChange.run();
            })
            .show();
}

private static class ClipboardGroupManageAdapter extends RecyclerView.Adapter<ClipboardGroupManageAdapter.VH> {

    interface Listener {
        void onSelect(String groupName); // "" means All
        void onRenameRequested(int groupIndex); // index in groups list
        void onDeleteRequested(int groupIndex); // index in groups list
        void onStartDrag(RecyclerView.ViewHolder vh);
    }

    private final Context ctx;
    private final ArrayList<String> groups;
    private final boolean favoritesMode;
    // key = lowercased group name, value = [count, charSum]
    private final HashMap<String, long[]> groupStatsLower = new HashMap<>();
    // "All" row stats: [count, charSum]
    private final long[] allStats = new long[]{0L, 0L};
    private Listener listener;
    private ItemTouchHelper helper;
    private String selectedGroup = "";

    ClipboardGroupManageAdapter(Context ctx, ArrayList<String> groups, boolean favoritesMode) {
        this.ctx = ctx;
        this.groups = groups != null ? groups : new ArrayList<>();
        this.favoritesMode = favoritesMode;
        rebuildStats();
    }

    void rebuildStats() {
        groupStatsLower.clear();
        allStats[0] = 0L;
        allStats[1] = 0L;

        if (ctx == null) return;

        List<AIClipboardStore.Entry> items;
        try {
            items = favoritesMode ? AIClipboardStore.getFavoriteEntries(ctx) : AIClipboardStore.getEntries(ctx);
        } catch (Throwable ignored) {
            items = null;
        }
        if (items == null || items.isEmpty()) return;

        for (AIClipboardStore.Entry e : items) {
            if (e == null) continue;

            long chars = countChars(e.text);
            String g = e.group != null ? e.group.trim() : "";

            // "All" row semantics should match what selecting "All" would show in the main list.
            if (favoritesMode) {
                // Favorites view: "All" = all favorites (regardless of group)
                allStats[0] += 1;
                allStats[1] += chars;
            } else {
                // Normal view: "All" shows only non-favorite + ungrouped items.
                if (!e.favorite && (g == null || g.isEmpty())) {
                    allStats[0] += 1;
                    allStats[1] += chars;
                }
            }

            if (g != null && !g.isEmpty()) {
                String key = g.toLowerCase(Locale.ROOT);
                long[] st = groupStatsLower.get(key);
                if (st == null) {
                    st = new long[]{0L, 0L};
                    groupStatsLower.put(key, st);
                }
                st[0] += 1;
                st[1] += chars;
            }
        }
    }

    private static long countChars(String t) {
        if (t == null) return 0L;
        try {
            return t.codePointCount(0, t.length());
        } catch (Throwable ignored) {
            try { return t.length(); } catch (Throwable ignored2) { return 0L; }
        }
    }

    void setListener(Listener l) { this.listener = l; }
    void setItemTouchHelper(ItemTouchHelper h) { this.helper = h; }
    void setSelectedGroup(String g) { this.selectedGroup = g != null ? g.trim() : ""; }

    @Override public int getItemCount() { return (groups != null ? groups.size() : 0) + 1; } // + "All"

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_clipboard_group_manage, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        if (h == null) return;

        boolean isAll = position == 0;
        String name = isAll ? (ctx != null ? ctx.getString(R.string.ui_group_all) : "All")
                : (groups != null && position - 1 < groups.size() ? String.valueOf(groups.get(position - 1)) : "");

        String trimmed = name != null ? name.trim() : "";
        if (h.tvName != null) h.tvName.setText(trimmed);

        // Per-group stats (count + chars), shown under the group name.
        if (h.tvStats != null) {
            long[] st = isAll ? allStats
                    : ((trimmed != null && !trimmed.isEmpty())
                    ? groupStatsLower.get(trimmed.toLowerCase(Locale.ROOT))
                    : null);

            long cnt = st != null ? st[0] : 0L;
            long chars = st != null ? st[1] : 0L;

            try {
                if (ctx != null) {
                    h.tvStats.setText(ctx.getString(R.string.ui_group_item_stats_fmt, cnt, chars));
                } else {
                    h.tvStats.setText(cnt + " · " + chars);
                }
            } catch (Throwable ignored) {
                h.tvStats.setText(cnt + " · " + chars);
            }
            h.tvStats.setVisibility(View.VISIBLE);
        }

        // Selected state (highlight row, no checkmark)
        boolean selected = false;
        if (isAll) {
            selected = (selectedGroup == null || selectedGroup.trim().isEmpty());
        } else {
            selected = (selectedGroup != null && selectedGroup.trim().equalsIgnoreCase(trimmed));
        }

        try {
            // Keep ripple, tint background when selected.
            int primary = ContextCompat.getColor(ctx, R.color.primary);
            if (h.itemView != null) {
                h.itemView.setBackgroundResource(R.drawable.bg_list_item_ripple);
                int bg = selected ? ((0x22 << 24) | (primary & 0x00FFFFFF)) : 0x00000000;
                try { h.itemView.setBackgroundTintList(ColorStateList.valueOf(bg)); } catch (Throwable ignored) {}
            }
            if (h.tvName != null) {
                h.tvName.setTextColor(selected ? primary : h.defaultTextColor);
            }
        } catch (Throwable ignored) {}

        // Drag handle + edit + delete only for user groups
        if (h.dragContainer != null) h.dragContainer.setVisibility(isAll ? View.GONE : View.VISIBLE);
        if (h.editContainer != null) h.editContainer.setVisibility(isAll ? View.GONE : View.VISIBLE);
        if (h.deleteContainer != null) h.deleteContainer.setVisibility(isAll ? View.GONE : View.VISIBLE);

        if (h.itemView != null) {
            h.itemView.setOnClickListener(v -> {
                if (listener == null) return;
                int posNow = h.getBindingAdapterPosition();
                if (posNow == RecyclerView.NO_POSITION) return;
                if (posNow == 0) {
                    listener.onSelect("");
                    return;
                }
                int idxNow = posNow - 1;
                if (idxNow >= 0 && idxNow < (groups != null ? groups.size() : 0)) {
                    String gNow = groups.get(idxNow);
                    gNow = gNow != null ? gNow.trim() : "";
                    listener.onSelect(gNow);
                }
            });
            // No long-press rename anymore.
            h.itemView.setOnLongClickListener(null);
        }

        // Edit (rename)
        if (!isAll && h.editContainer != null) {
            h.editContainer.setOnClickListener(v -> {
                if (listener == null) return;
                int posNow = h.getBindingAdapterPosition();
                if (posNow == RecyclerView.NO_POSITION) return;
                int idx = posNow - 1;
                if (idx >= 0 && idx < (groups != null ? groups.size() : 0)) {
                    listener.onRenameRequested(idx);
                }
            });
        } else if (h.editContainer != null) {
            h.editContainer.setOnClickListener(null);
        }

        // Delete
        if (!isAll && h.deleteContainer != null) {
            h.deleteContainer.setOnClickListener(v -> {
                if (listener == null) return;
                int posNow = h.getBindingAdapterPosition();
                if (posNow == RecyclerView.NO_POSITION) return;
                int idx = posNow - 1;
                if (idx >= 0 && idx < (groups != null ? groups.size() : 0)) {
                    listener.onDeleteRequested(idx);
                }
            });
        } else if (h.deleteContainer != null) {
            h.deleteContainer.setOnClickListener(null);
        }

        // Start drag only from handle so it doesn't conflict with long-press rename
        if (!isAll && h.dragHandle != null) {
            h.dragHandle.setOnTouchListener((v, ev) -> {
                if (listener == null || helper == null) return false;
                if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
                    listener.onStartDrag(h);
                    return true;
                }
                return false;
            });
        } else if (h.dragHandle != null) {
            h.dragHandle.setOnTouchListener(null);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvStats;
        final int defaultTextColor;
        final View dragContainer;
        final View dragHandle;
        final View editContainer;
        final View deleteContainer;
        VH(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_group_name);
            tvStats = itemView.findViewById(R.id.tv_group_stats);
            int c = 0;
            try { if (tvName != null) c = tvName.getCurrentTextColor(); } catch (Throwable ignored) {}
            defaultTextColor = c;
            dragContainer = itemView.findViewById(R.id.drag_handle_container);
            dragHandle = itemView.findViewById(R.id.iv_drag_handle);
            editContainer = itemView.findViewById(R.id.btn_edit_container);
            deleteContainer = itemView.findViewById(R.id.btn_delete_container);
        }
    }
}

private void showCreateClipboardGroupDialog(Context ctx, SPManager sp, Runnable afterCreate) {
    if (ctx == null || sp == null) return;
    final int MAX = 10;

    List<String> groups = null;
    try { groups = sp.getAiClipboardGroups(); } catch (Throwable ignored) {}
    if (groups == null) groups = new ArrayList<>();

    if (groups.size() >= MAX) {
        Toast.makeText(ctx, R.string.msg_group_limit, Toast.LENGTH_SHORT).show();
        return;
    }

    final EditText et = new EditText(ctx);
    et.setHint(R.string.ui_group_name);
    et.setSingleLine(true);
    et.setImeOptions(EditorInfo.IME_ACTION_DONE);
    et.setInputType(InputType.TYPE_CLASS_TEXT);
    int p = dp(ctx, 12);
    et.setPadding(p, p, p, p);

    new AlertDialog.Builder(ctx)
            .setTitle(R.string.ui_add_group)
            .setView(et)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (d, w) -> {
                String name = et.getText() != null ? et.getText().toString() : "";
                if (name == null) name = "";
                name = name.trim();

                if (name.isEmpty()) {
                    Toast.makeText(ctx, R.string.msg_group_name_empty, Toast.LENGTH_SHORT).show();
                    return;
                }

                List<String> cur = null;
                try { cur = sp.getAiClipboardGroups(); } catch (Throwable ignored) {}
                if (cur == null) cur = new ArrayList<>();

                if (cur.size() >= MAX) {
                    Toast.makeText(ctx, R.string.msg_group_limit, Toast.LENGTH_SHORT).show();
                    return;
                }

                for (String g : cur) {
                    if (g == null) continue;
                    if (g.trim().equalsIgnoreCase(name)) {
                        Toast.makeText(ctx, R.string.msg_group_name_exists, Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                cur.add(name);
                sp.setAiClipboardGroups(cur);

                // Mode B: stay in current group selection (do NOT auto-switch).
                Toast.makeText(ctx, ctx.getString(R.string.msg_group_added, name), Toast.LENGTH_SHORT).show();
                if (afterCreate != null) afterCreate.run();
            })
            .show();
}

	// Bulk move for selection mode
	private void showClipboardMoveSelectedToGroupMenu(Context ctx, View anchor, SPManager sp, List<Integer> storeIndices, Runnable afterMove) {
	    if (ctx == null || anchor == null || sp == null || storeIndices == null || storeIndices.isEmpty()) return;

	    List<String> groups = null;
	    try { groups = sp.getAiClipboardGroups(); } catch (Throwable ignored) {}
	    if (groups == null) groups = new ArrayList<>();

	    if (groups.isEmpty()) {
	        Toast.makeText(ctx, R.string.msg_no_groups, Toast.LENGTH_SHORT).show();
	        return;
	    }

	    // Show "Cancel group" only when at least one selected entry has a group.
	    boolean hasAnyGroup = false;
	    try {
	        for (Integer idx : storeIndices) {
	            if (idx == null) continue;
	            String g = AIClipboardStore.getGroup(ctx, idx);
	            if (g != null && !g.trim().isEmpty()) { hasAnyGroup = true; break; }
	        }
	    } catch (Throwable ignored) {}

	    PopupMenu popup = new PopupMenu(ctx, anchor);
	    Menu menu = popup.getMenu();
	    final int ID_BASE = 6100;
	    final int ID_NONE = 7999;

	    for (int i = 0; i < groups.size(); i++) {
	        String g = groups.get(i);
	        if (g == null) continue;
	        g = g.trim();
	        if (g.isEmpty()) continue;
	        menu.add(0, ID_BASE + i, i, g);
	    }
	    if (hasAnyGroup) {
	        menu.add(0, ID_NONE, 999, ctx.getString(R.string.ui_group_none));
	    }

	    popup.setOnMenuItemClickListener(item -> {
	        if (item == null) return false;
	        int id = item.getItemId();
	        int n = 0;
	        if (id == ID_NONE) {
	            try {
	                for (Integer idx : storeIndices) {
	                    if (idx == null) continue;
	                    AIClipboardStore.setGroup(ctx, idx, "");
	                    n++;
	                }
	            } catch (Throwable ignored) {}
	            try {
	                Toast.makeText(ctx, ctx.getString(R.string.msg_clipboard_moved_n_to_group_none, n), Toast.LENGTH_SHORT).show();
	            } catch (Throwable ignored) {
	                Toast.makeText(ctx, R.string.msg_moved_to_group_none, Toast.LENGTH_SHORT).show();
	            }
	        } else {
	            CharSequence t = item.getTitle();
	            String g = t != null ? t.toString().trim() : "";
	            try {
	                for (Integer idx : storeIndices) {
	                    if (idx == null) continue;
	                    AIClipboardStore.setGroup(ctx, idx, g);
	                    n++;
	                }
	            } catch (Throwable ignored) {}
	            try {
	                Toast.makeText(ctx, ctx.getString(R.string.msg_clipboard_moved_n_to_group, n, g), Toast.LENGTH_SHORT).show();
	            } catch (Throwable ignored) {
	                Toast.makeText(ctx, ctx.getString(R.string.msg_moved_to_group, g), Toast.LENGTH_SHORT).show();
	            }
	        }
	        if (afterMove != null) afterMove.run();
	        return true;
	    });

	    try { popup.show(); } catch (Throwable ignored) {}
	}

private void showClipboardMoveToGroupMenu(Context ctx, View anchor, SPManager sp, int storeIndex, Runnable afterMove) {
    if (ctx == null || anchor == null || sp == null) return;

    List<String> groups = null;
    try { groups = sp.getAiClipboardGroups(); } catch (Throwable ignored) {}
    if (groups == null) groups = new ArrayList<>();

    if (groups.isEmpty()) {
        Toast.makeText(ctx, R.string.msg_no_groups, Toast.LENGTH_SHORT).show();
        return;
    }

    PopupMenu popup = new PopupMenu(ctx, anchor);
    Menu menu = popup.getMenu();
    final int ID_BASE = 6000;
    final int ID_NONE = 6999;

    final String currentGroup = AIClipboardStore.getGroup(ctx, storeIndex);
    final boolean hasGroup = currentGroup != null && !currentGroup.trim().isEmpty();

    for (int i = 0; i < groups.size(); i++) {
        String g = groups.get(i);
        if (g == null) continue;
        g = g.trim();
        if (g.isEmpty()) continue;
        menu.add(0, ID_BASE + i, i, g);
    }
    // Only show "Cancel group" when this entry currently belongs to a group.
    if (hasGroup) {
        menu.add(0, ID_NONE, 999, ctx.getString(R.string.ui_group_none));
    }

    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
        @Override public boolean onMenuItemClick(MenuItem item) {
            if (item == null) return false;
            int id = item.getItemId();
            if (id == ID_NONE) {
                AIClipboardStore.setGroup(ctx, storeIndex, "");
                Toast.makeText(ctx, R.string.msg_moved_to_group_none, Toast.LENGTH_SHORT).show();
            } else {
                CharSequence t = item.getTitle();
                String g = t != null ? t.toString().trim() : "";
                AIClipboardStore.setGroup(ctx, storeIndex, g);
                Toast.makeText(ctx, ctx.getString(R.string.msg_moved_to_group, g), Toast.LENGTH_SHORT).show();
            }
            if (afterMove != null) afterMove.run();
            return true;
        }
    });

    try { popup.show(); } catch (Throwable ignored) {}
}


private void tryAppendCurrentClipboard(Context ctx) {
    try {
        String text = readSystemClipboardText(ctx);
        maybeAppendClipboardText(ctx, text);
    } catch (Throwable ignored) {}
}

/** Read the current system clipboard as text (best-effort). */
private String readSystemClipboardText(Context ctx) {
    try {
        if (ctx == null) return null;
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return null;
        if (!cm.hasPrimaryClip()) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() <= 0) return null;
        CharSequence cs = clip.getItemAt(0).coerceToText(ctx);
        if (cs == null) return null;
        String t = cs.toString();
        return t != null ? t : null;
    } catch (Throwable ignored) {
        return null;
    }
}

/** Append clipboard text into AIClipboardStore with guards. */
private void maybeAppendClipboardText(Context ctx, String text) {
    try {
        if (ctx == null) return;
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;

        // If user just deleted/cleared, don't immediately re-add the same clipboard content.
        if (clipboardIgnoreText != null) {
            if (clipboardIgnoreText.trim().equals(t)) {
                return;
            } else {
                // Clipboard changed to a different value; release the ignore guard.
                clipboardIgnoreText = null;
            }
        }

        AIClipboardStore.append(ctx, t);

        // Update UI list if we're currently showing the clipboard screen.
        if (clipboardUiRenderList != null) {
            try {
                clipboardUiRenderList.run();
            } catch (Throwable ignored) {}
        }
    } catch (Throwable ignored) {
    }
}

private void enableClipboardUiListener(Context ctx) {
    try {
        if (ctx == null) return;
        if (clipboardUiListener != null && clipboardUiManager != null) return;
        clipboardUiManager = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardUiManager == null) return;

        clipboardUiListener = new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                try {
                    String t = readSystemClipboardText(ctx);
                    maybeAppendClipboardText(ctx, t);
                } catch (Throwable ignored) {}
            }
        };
        clipboardUiManager.addPrimaryClipChangedListener(clipboardUiListener);
    } catch (Throwable ignored) {
    }
}

private void disableClipboardUiListener() {
    try {
        if (clipboardUiManager != null && clipboardUiListener != null) {
            clipboardUiManager.removePrimaryClipChangedListener(clipboardUiListener);
        }
    } catch (Throwable ignored) {
    } finally {
        clipboardUiListener = null;
        clipboardUiManager = null;
    }
}

private String oneLinePreview(String text) {
    if (text == null) return "";
    String t = text.replace('\n', ' ').replace('\r', ' ').trim();
    if (t.length() > 60) t = t.substring(0, 60) + "...";
    return t;
}

private String twoLinePreview(String text) {
    if (text == null) return "";
    String t = text.trim();
    if (t.isEmpty()) return "";
    // Keep a generous amount; TextView will handle maxLines + ellipsize.
    if (t.length() > 2000) t = t.substring(0, 2000);
    return t;
}

private int dp(Context ctx, int dp) {
    try {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    } catch (Exception e) {
        return dp;
    }
}

	/**
	 * Try to insert text directly into the currently focused input field.
	 * @return true if inserted into the input field, false if we had to fallback to clipboard.
	 */

    /**
     * AI Clipboard row click behavior:
     * 1) Try direct commit into the currently focused editor (works when we have the IME InputConnection).
     * 2) If not possible, we copy to system clipboard (OEM may show "已复制").
     * 3) Then, on rooted devices, close this sheet so focus returns to the target app and trigger paste/typing
     *    via shell injection.
     */
    private void handleAiClipboardItemClick(Context ctx, String text) {
        final String v = text != null ? text : "";

        boolean inserted = false;
        try {
            inserted = insertIntoInputOrClipboard(ctx, v);
        } catch (Throwable ignored) {
        }

        // Always dismiss the floating sheet so focus returns to the target editor.
        try {
            if (getDialog() != null) {
                getDialog().dismiss();
            } else if (getParent() != null) {
                getParent().finish();
            }
        } catch (Throwable ignored) {
        }

        if (inserted) return;

        // Root fallback: after focus returns, inject paste (KEYCODE_PASTE) or type the text.
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                new Thread(() -> {
                    try {
                        if (RootShell.hasRootAccess()) {
                            RootShell.pasteOrType(v);
                        }
                    } catch (Throwable ignored2) {
                    }
                }, "KGPT-AICB-Paste").start();
            }, 380);
        } catch (Throwable ignored) {
        }
    }

	private boolean insertIntoInputOrClipboard(Context ctx, String text) {
	    String v = text != null ? text : "";
	    // 1) Prefer direct IME commit when KGPT is the current keyboard and InputConnection is available.
	    try {
	        IMSController ims = IMSController.getInstance();
	        if (ims != null) {
	            boolean ok = ims.commitToInputNow(v);
	            if (ok) return true;
	        }
	    } catch (Throwable ignored) {
	    }
	    // 2) Fallback: copy to system clipboard so the user can paste manually.
	    copyToSystemClipboard(ctx, v);
	    return false;
	}

private String formatTime(Context ctx, long timeMs) {
    try {
        if (timeMs <= 0) return "";
        // 24-hour in zh locale, 12-hour in others (system default)
        return DateFormat.format("yyyy-MM-dd HH:mm", timeMs).toString();
    } catch (Exception e) {
        return "";
    }
}

private void showClipboardItemDialog(Context ctx, int storeIndex, String text, Runnable render) {
    if (ctx == null) return;
    String v = text != null ? text : "";

    try {
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.ui_ai_clipboard)
                .setMessage(v)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.delete, (d, w) -> {
                    AIClipboardStore.deleteAt(ctx, storeIndex);
                    if (render != null) render.run();
                })
                .setPositiveButton(R.string.ui_copy_to_clipboard, (d, w) -> {
                    copyToSystemClipboard(ctx, v);
                    Toast.makeText(ctx, R.string.msg_copied, Toast.LENGTH_SHORT).show();
                })
                .show();
    } catch (Exception e) {
        // Fallback: just copy
        copyToSystemClipboard(ctx, v);
        Toast.makeText(ctx, R.string.msg_copied, Toast.LENGTH_SHORT).show();
    }
}

/**
 * Long-press editor for a clipboard entry.
 * Allows the user to edit the entry text in place.
 */
private void showEditClipboardEntryDialog(Context ctx, int storeIndex, String text, Runnable render) {
    if (ctx == null) return;

    String v = text != null ? text : "";
    try {
        final EditText et = new EditText(ctx);
        et.setText(v);
        et.setSelection(et.getText() != null ? et.getText().length() : 0);
        et.setMinLines(3);
        et.setMaxLines(10);
        et.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setGravity(Gravity.START | Gravity.TOP);
        int p = dp(ctx, 12);
        et.setPadding(p, p, p, p);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.ui_edit)
                .setView(et)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String newText = et.getText() != null ? et.getText().toString() : "";
                    // Guard: prevent immediate re-add loop if system clipboard equals the old/new text.
                    clipboardIgnoreText = readSystemClipboardText(ctx);
                    AIClipboardStore.updateText(ctx, storeIndex, newText);
                    Toast.makeText(ctx, R.string.config_saved, Toast.LENGTH_SHORT).show();
                    if (render != null) render.run();
                })
                .show();
    } catch (Exception e) {
        // Fallback: copy to clipboard so user doesn't lose the content
        copyToSystemClipboard(ctx, v);
        Toast.makeText(ctx, R.string.msg_copied, Toast.LENGTH_SHORT).show();
    }
}

private void copyToSystemClipboard(Context ctx, String text) {
    try {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("KGPT", text != null ? text : ""));
        }
    } catch (Exception ignored) {}
}

    
    // -----------------------------
    // Inline editor dialogs (AI Commands / AI Triggers) - stays inside floating window
    // -----------------------------

    private void showAddOrEditAiCommandDialog(Context ctx, SPManager sp, int editIndex,
                                              GenerativeAICommand editing, Runnable render) {
        if (ctx == null || sp == null) return;

        View view = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_command, null, false);
        TextView tvTitle = view.findViewById(R.id.tv_dialog_title);
        TextInputEditText etPrefix = view.findViewById(R.id.et_command_name);
        TextInputEditText etMsg = view.findViewById(R.id.et_system_message);

        View btnDelete = view.findViewById(R.id.btn_delete);
        View btnCancel = view.findViewById(R.id.btn_cancel);
        View btnSave = view.findViewById(R.id.btn_save);

        final boolean isEdit = (editIndex >= 0 && editing != null);

        if (tvTitle != null) {
            tvTitle.setText(isEdit ? R.string.ui_edit_command : R.string.ui_add_new_command);
        }

        if (isEdit) {
            String oldPrefix = "";
            String oldMsg = "";
            try { oldPrefix = editing.getCommandPrefix(); } catch (Exception ignored) {}
            try { oldMsg = editing.getTweakMessage(); } catch (Exception ignored) {}
            if (oldPrefix == null) oldPrefix = "";
            if (oldMsg == null) oldMsg = "";

            oldPrefix = oldPrefix.trim();
            if (oldPrefix.startsWith("/")) oldPrefix = oldPrefix.substring(1);

            if (etPrefix != null) etPrefix.setText(oldPrefix);
            if (etMsg != null) etMsg.setText(oldMsg);

            if (btnDelete != null) btnDelete.setVisibility(View.VISIBLE);
        } else {
            if (btnDelete != null) btnDelete.setVisibility(View.GONE);
        }

        AlertDialog dialog = new AlertDialog.Builder(ctx).setView(view).create();
        dialog.setCancelable(true);

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        if (btnSave != null) btnSave.setOnClickListener(v -> {
            String prefix = etPrefix != null && etPrefix.getText() != null ? etPrefix.getText().toString().trim() : "";
            String msg = etMsg != null && etMsg.getText() != null ? etMsg.getText().toString() : "";

            if (prefix.startsWith("/")) prefix = prefix.substring(1);
            prefix = prefix.trim();

            if (prefix.isEmpty()) {
                Toast.makeText(ctx, R.string.msg_command_name_required, Toast.LENGTH_SHORT).show();
                return;
            }

            List<GenerativeAICommand> cur = null;
            try { cur = sp.getGenerativeAICommands(); } catch (Exception ignored) {}
            List<GenerativeAICommand> list = new ArrayList<>();
            if (cur != null) list.addAll(cur);

            SimpleGenerativeAICommand newCmd = new SimpleGenerativeAICommand(prefix, msg);

            if (isEdit && editIndex < list.size()) {
                list.set(editIndex, newCmd);
            } else {
                list.add(newCmd);
            }

            sp.setGenerativeAICommands(list);
            broadcastConfigOnly(ctx, sp);

            if (render != null) render.run();
            dialog.dismiss();
        });

        if (btnDelete != null) btnDelete.setOnClickListener(v -> {
            if (!isEdit) return;

            List<GenerativeAICommand> cur = null;
            try { cur = sp.getGenerativeAICommands(); } catch (Exception ignored) {}
            List<GenerativeAICommand> list = new ArrayList<>();
            if (cur != null) list.addAll(cur);

            if (editIndex >= 0 && editIndex < list.size()) {
                list.remove(editIndex);
                sp.setGenerativeAICommands(list);
                broadcastConfigOnly(ctx, sp);
                if (render != null) render.run();
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private String buildTriggerPreview(PatternType type, String symbol) {
        if (type == null) return "";
        if (symbol == null) symbol = "";
        String s = symbol.trim();
        if (s.isEmpty()) return "";

        if (type == PatternType.CommandCustom) {
            // text%fix% (or text%something%)
            return "你好" + s + "fix" + s;
        }
        if (type.groupCount == 0) {
            return s;
        }
        return "你好" + s;
    }

    private void showEditAiTriggerDialog(Context ctx, SPManager sp, int editIndex,
                                         ParsePattern editing, Runnable render) {
        if (ctx == null || sp == null || editing == null) return;

        PatternType type = editing.getType();
        if (type != null && !type.editable) {
            Toast.makeText(ctx, R.string.msg_pattern_create_symbol_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        View view = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_pattern_symbol, null, false);
        TextView tvType = view.findViewById(R.id.tv_pattern_type);
        TextView tvDesc = view.findViewById(R.id.tv_description);
        MaterialSwitch swEnabled = view.findViewById(R.id.switch_enabled);
        TextInputEditText etSymbol = view.findViewById(R.id.et_symbol);
        TextView tvExample = view.findViewById(R.id.tv_example);

        View btnReset = view.findViewById(R.id.btn_reset);
        View btnCancel = view.findViewById(R.id.btn_cancel);
        View btnSave = view.findViewById(R.id.btn_save);

        String oldRegex = "";
        try { oldRegex = editing.getPattern().pattern(); } catch (Exception ignored) {}
        if (oldRegex == null) oldRegex = "";

        String curSymbol = "";
        try { curSymbol = PatternType.regexToSymbol(oldRegex); } catch (Exception ignored) {}
        if (curSymbol == null) curSymbol = "";

        if (tvType != null) tvType.setText(type != null ? type.title : "Pattern");
        if (tvDesc != null) tvDesc.setText(type != null ? type.description : "");
        if (swEnabled != null) swEnabled.setChecked(editing.isEnabled());
        if (etSymbol != null) etSymbol.setText(curSymbol);

        Runnable updatePreview = () -> {
            if (tvExample == null) return;
            String s = etSymbol != null && etSymbol.getText() != null ? etSymbol.getText().toString() : "";
            tvExample.setText(buildTriggerPreview(type, s));
        };
        updatePreview.run();

        if (etSymbol != null) {
            etSymbol.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) { updatePreview.run(); }
            });
        }

        AlertDialog dialog = new AlertDialog.Builder(ctx).setView(view).create();
        dialog.setCancelable(true);

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        if (btnReset != null) btnReset.setOnClickListener(v -> {
            if (type != null) {
                if (etSymbol != null) etSymbol.setText(type.defaultSymbol);
                if (swEnabled != null) swEnabled.setChecked(true);
                updatePreview.run();
            }
        });

        if (btnSave != null) btnSave.setOnClickListener(v -> {
            String symbol = etSymbol != null && etSymbol.getText() != null ? etSymbol.getText().toString().trim() : "";
            if (symbol.isEmpty() || type == null) {
                Toast.makeText(ctx, R.string.msg_symbol_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            String newRegex = PatternType.symbolToRegex(symbol, type.groupCount);
            if (newRegex == null || newRegex.trim().isEmpty()) {
                Toast.makeText(ctx, R.string.msg_pattern_create_symbol_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            boolean enabled = swEnabled != null && swEnabled.isChecked();

            List<ParsePattern> list = null;
            try { list = sp.getParsePatterns(); } catch (Exception ignored) {}
            if (list == null) list = new ArrayList<>();

            // Prevent duplicate symbols (conflicts with other triggers)
            for (int i = 0; i < list.size(); i++) {
                if (i == editIndex) continue;
                ParsePattern other = list.get(i);
                if (other == null) continue;
                try {
                    String os = PatternType.regexToSymbol(other.getPattern().pattern());
                    if (os != null && os.trim().equals(symbol)) {
                        Toast.makeText(ctx, R.string.msg_symbol_already_used, Toast.LENGTH_SHORT).show();
                        return;
                    }
                } catch (Exception ignored) {}
            }

            if (editIndex >= 0 && editIndex < list.size()) {
                ParsePattern cur = list.get(editIndex);
                java.util.Map<String, String> extras = null;
                try {
                    if (cur != null && cur.getExtras() != null) extras = new java.util.HashMap<>(cur.getExtras());
                } catch (Exception ignored) {}
                ParsePattern np = new ParsePattern(type, newRegex, extras);
                np.setEnabled(enabled);
                list.set(editIndex, np);
            } else {
                ParsePattern np = new ParsePattern(type, newRegex);
                np.setEnabled(enabled);
                list.add(np);
            }

            sp.setParsePatterns(list);
            broadcastConfigOnly(ctx, sp);

            if (render != null) render.run();
            dialog.dismiss();
        });

        dialog.show();
    }


// -----------------------------
    // Screen: ROLES
    // -----------------------------
    private void renderRoles(View root, LinearLayout container, TextView tvTitle, TextView tvSubtitle, ImageView ivTitleIcon,
                             SPManager sp, String[] pendingRoleId,
                             Runnable render) {
        // Title: Role management (title) + details (subtitle)
        String rolesJson = sp.getRolesJson();
        List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);
        int total = roles != null ? roles.size() : 0;
        String activeId = pendingRoleId != null ? pendingRoleId[0] : sp.getActiveRoleId();
        int idx = 0;
        String name = "-";
        if (roles != null && !roles.isEmpty()) {
            for (int i = 0; i < roles.size(); i++) {
                RoleManager.Role r = roles.get(i);
                if (r == null) continue;
                if (activeId != null && activeId.equals(r.id)) {
                    idx = i + 1;
                    name = (r.name != null && !r.name.trim().isEmpty()) ? r.name : "-";
                    break;
                }
            }
            if (idx == 0) {
                idx = 1;
                RoleManager.Role r0 = roles.get(0);
                name = (r0 != null && r0.name != null && !r0.name.trim().isEmpty()) ? r0.name : "-";
            }
        }
        String base = root.getContext().getString(R.string.ui_manage_roles);
        if (tvTitle != null) tvTitle.setText(base);
        setSubtitle(tvSubtitle, String.format(Locale.getDefault(), "%d/%d · %s", idx, total, name));

        if (ivTitleIcon != null) ivTitleIcon.setImageResource(R.drawable.ic_command_filled);


        LayoutInflater inflater = LayoutInflater.from(root.getContext());

        if (roles == null || roles.isEmpty()) {
            TextView tv = new TextView(root.getContext());
            tv.setText(R.string.no_roles_found);
            tv.setTextSize(16f);
            tv.setPadding(0, 8, 0, 8);
            container.addView(tv);
            return;
        }

        // If there are duplicate role names, display them with a numeric suffix (001/002/...).
        // This does NOT change the stored name; it only improves the UI readability.
        HashMap<String, Integer> nameTotal = new HashMap<>();
        for (RoleManager.Role rr : roles) {
            if (rr == null) continue;
            String n = rr.name != null ? rr.name.trim() : "";
            nameTotal.put(n, nameTotal.getOrDefault(n, 0) + 1);
        }
        HashMap<String, Integer> nameSeen = new HashMap<>();

        for (RoleManager.Role r : roles) {
            if (r == null) continue;

            View item = inflater.inflate(R.layout.item_model_option, container, false);
            TextView tv = item.findViewById(R.id.tv_model_name);
            TextView tvDesc = item.findViewById(R.id.tv_model_desc);
            ImageView iv = item.findViewById(R.id.iv_icon);
            ImageView ivEdit = item.findViewById(R.id.iv_edit);
            CheckBox cb = item.findViewById(R.id.cb_selected);

            String rawName = r.name != null ? r.name : "";
            String baseName = rawName.trim();
            int totalSame = nameTotal.getOrDefault(baseName, 0);
            int occ = nameSeen.getOrDefault(baseName, 0) + 1;
            nameSeen.put(baseName, occ);
            String displayName = (totalSame > 1)
                    ? baseName + String.format(Locale.getDefault(), "%03d", occ)
                    : baseName;

if (tv != null) tv.setText(displayName);
            // Show trigger keyword (inherits global AI trigger if blank)
            if (tvDesc != null) {
                String globalTrig = "$";
                try {
                    globalTrig = sp != null ? sp.getAiTriggerSymbol() : "$";
                } catch (Throwable ignored) {}
                if (globalTrig == null || globalTrig.trim().isEmpty()) globalTrig = "$";

                String rawTrig = r.trigger != null ? r.trigger.trim() : "";
                String shownTrig = !rawTrig.isEmpty() ? rawTrig : globalTrig;
                boolean isDefaultTrig = rawTrig.isEmpty();
                tvDesc.setVisibility(View.VISIBLE);
                tvDesc.setText(root.getContext().getString(
                        isDefaultTrig ? R.string.role_trigger_display_default : R.string.role_trigger_display,
                        shownTrig
                ));
            }
            if (iv != null) iv.setImageResource(R.drawable.ic_command_filled);

            String id = r.id != null ? r.id : RoleManager.DEFAULT_ROLE_ID;
            if (cb != null) {
                cb.setChecked(id.equals(pendingRoleId[0]));
                // Important: prevent the checkbox from consuming click events.
                // Users often tap the checkbox itself; this must trigger the same selection logic as tapping the row.
                cb.setClickable(false);
                cb.setFocusable(false);
                cb.setFocusableInTouchMode(false);
            }

            item.setOnClickListener(v -> {
                // Realtime persist: selecting a role should take effect immediately
                pendingRoleId[0] = id;
                try {
                    sp.setActiveRoleId(id);
                    broadcastConfigOnly(root.getContext(), sp);
                } catch (Throwable ignored) {}
                Toast.makeText(root.getContext(),
                        root.getContext().getString(R.string.role_selected_toast, displayName),
                        Toast.LENGTH_SHORT).show();
                if (render != null) render.run(); // refresh checks + subtitle
            });

            // Pencil icon (preferred): edit custom roles without long press
            if (ivEdit != null) {
                if (r.id != null && !RoleManager.DEFAULT_ROLE_ID.equals(r.id)) {
                    ivEdit.setVisibility(View.VISIBLE);
                    ivEdit.setOnClickListener(v -> showAddOrEditRoleDialog(root.getContext(), sp, pendingRoleId, r, render));
                } else {
                    ivEdit.setVisibility(View.GONE);
                }
            }

            container.addView(item);
        }
    }

    // -----------------------------
    // Screen: MODELS
    // -----------------------------
    private void renderModels(View root, LinearLayout fixedContainer, LinearLayout container, TextView tvTitle, TextView tvSubtitle, ImageView ivTitleIcon,
                              SPManager sp, LanguageModel[] pendingProvider, String[] pendingSubModel,
                              Runnable render) {
        LanguageModel provider = (pendingProvider != null && pendingProvider[0] != null) ? pendingProvider[0] : sp.getLanguageModel();

        // Title: Model management (title) + details (subtitle)
        List<String> allModels = sp.getCachedModels(provider);
        int total = allModels != null ? allModels.size() : 0;
        String active = (pendingSubModel != null && pendingSubModel[0] != null) ? pendingSubModel[0] : sp.getSubModel(provider);
        int idx = 0;
        String name = "-";
        if (allModels != null && !allModels.isEmpty()) {
            for (int i = 0; i < allModels.size(); i++) {
                String m = allModels.get(i);
                if (m == null) continue;
                String mm = m.trim();
                if (mm.isEmpty()) continue;
                if (active != null && active.equals(mm)) {
                    idx = i + 1;
                    name = mm;
                    break;
                }
            }
            if (idx == 0) {
                idx = 1;
                name = allModels.get(0) != null ? allModels.get(0).trim() : "-";
            }
        }
        String base = root.getContext().getString(R.string.ui_manage_models);
        String providerLabel = provider != null ? provider.label : "-";
        if (tvTitle != null) tvTitle.setText(base);
        setSubtitle(tvSubtitle, String.format(Locale.getDefault(), "%d/%d · %s (%s)", idx, total, name, providerLabel));

        if (ivTitleIcon != null) ivTitleIcon.setImageResource(R.drawable.ic_ai_invocation_filled);

        final LayoutInflater inflater = LayoutInflater.from(root.getContext());

        // Render list only (used for live search) without rebuilding the pinned header.
        final Runnable[] renderListRef = new Runnable[1];
        renderListRef[0] = () -> {
            if (container == null) return;
            container.removeAllViews();

            List<String> models = sp.getCachedModels(provider);
            if (models == null || models.isEmpty()) {
                TextView tv = new TextView(root.getContext());
                tv.setText(R.string.no_cached_models);
                tv.setTextSize(16f);
                tv.setPadding(0, 8, 0, 8);
                container.addView(tv);
                return;
            }

            String activeNow = (pendingSubModel != null && pendingSubModel[0] != null)
                    ? pendingSubModel[0]
                    : sp.getSubModel(provider);

            String q = modelSearchQuery != null ? modelSearchQuery.trim() : "";
            ArrayList<String> visible = new ArrayList<>();
            if (!q.isEmpty()) {
                String qLower = q.toLowerCase(Locale.ROOT);
                for (String m : models) {
                    if (m == null) continue;
                    String modelName = m.trim();
                    if (modelName.isEmpty()) continue;
                    if (modelName.toLowerCase(Locale.ROOT).contains(qLower)) {
                        visible.add(modelName);
                    }
                }
            } else {
                for (String m : models) {
                    if (m == null) continue;
                    String modelName = m.trim();
                    if (modelName.isEmpty()) continue;
                    visible.add(modelName);
                }
            }

            if (visible.isEmpty()) {
                TextView tvEmpty = new TextView(root.getContext());
                tvEmpty.setText(R.string.ui_no_matching_models);
                tvEmpty.setTextSize(16f);
                tvEmpty.setPadding(0, 8, 0, 8);
                container.addView(tvEmpty);
                return;
            }

            for (String modelName : visible) {
                View item = inflater.inflate(R.layout.item_model_option, container, false);
                TextView tv = item.findViewById(R.id.tv_model_name);
                TextView tvDesc = item.findViewById(R.id.tv_model_desc);
                ImageView iv = item.findViewById(R.id.iv_icon);
                ImageView ivEdit = item.findViewById(R.id.iv_edit);
                CheckBox cb = item.findViewById(R.id.cb_selected);

                if (tv != null) tv.setText(modelName);
                if (tvDesc != null) tvDesc.setVisibility(View.GONE);
                if (iv != null) iv.setImageResource(R.drawable.ic_ai_invocation_filled);
                if (ivEdit != null) ivEdit.setVisibility(View.GONE);
                if (cb != null) cb.setChecked(activeNow != null && activeNow.equals(modelName));

                item.setOnClickListener(v -> {
                    pendingSubModel[0] = modelName;

                    try {
                        // Save immediately so the selection survives process kill / recent-app swipe.
                        sp.setLanguageModel(provider);
                        sp.setSubModel(provider, modelName);
                        broadcastConfigOnly(root.getContext(), sp);
                    } catch (Throwable ignored) {}

                    Toast.makeText(root.getContext(),
                            root.getContext().getString(R.string.msg_config_saved),
                            Toast.LENGTH_SHORT).show();

                    if (render != null) render.run(); // refresh checks + subtitle
                });

                container.addView(item);
            }
        };

        // Pinned header: Search (3rd line) + BaseURL/Fetch models
        if (fixedContainer != null) {
            fixedContainer.setVisibility(View.VISIBLE);
            fixedContainer.removeAllViews();

            // Current in-use card + one-tap "Why" panel
            try {
                String cur = (pendingSubModel != null && pendingSubModel[0] != null) ? pendingSubModel[0] : sp.getSubModel(provider);
                if (cur == null) cur = "";
                cur = cur.trim();
                String providerLabel2 = provider != null ? provider.label : "-";
                View curCard = inflater.inflate(R.layout.view_current_model_card, fixedContainer, false);
                TextView tvCur = curCard.findViewById(R.id.tv_current_value);
                if (tvCur != null) {
                    String line = cur.isEmpty() ? "-" : cur;
                    tvCur.setText(line + " (" + providerLabel2 + ")");
                }
                ImageView ivWhy = curCard.findViewById(R.id.iv_current_why);
                if (ivWhy != null) {
                    final String mm = cur;
                    ivWhy.setOnClickListener(v -> {
                        try {
                            MaxTokensWhySheet.show(root.getContext(), provider, mm, "submodel_dialog_current_card");
                        } catch (Throwable ignored) {}
                    });
                }
                fixedContainer.addView(curCard);
            } catch (Throwable ignored) {}

            addInlineModelSearchRow(root.getContext(), fixedContainer, renderListRef[0]);
            addInlineFetchModelsRow(root.getContext(), fixedContainer, sp, provider, pendingSubModel, render);
        } else {
            // Fallback (should not happen): render everything inside scroll area.
            addInlineModelSearchRow(root.getContext(), container, () -> { if (render != null) render.run(); });
            addInlineFetchModelsRow(root.getContext(), container, sp, provider, pendingSubModel, render);
        }

        // Initial list render
        if (renderListRef[0] != null) renderListRef[0].run();
    }


    // -----------------------------
    // Screen: KEYS (providers with saved keys)
    // -----------------------------
    private void renderKeys(View root, LinearLayout container, TextView tvTitle, TextView tvSubtitle, ImageView ivTitleIcon,
                            SPManager sp, LanguageModel[] pendingProvider, String[] pendingSubModel,
                            Screen[] screen, Runnable render) {
        LanguageModel current = (pendingProvider != null && pendingProvider[0] != null) ? pendingProvider[0] : sp.getLanguageModel();
        List<LanguageModel> providers = new ArrayList<>();
        for (LanguageModel m : LanguageModel.values()) {
            if (m == null) continue;
            String key = sp.getApiKey(m);
            boolean hasKey = key != null && !key.trim().isEmpty();
            if (hasKey || m == current) providers.add(m);
        }
        int total = providers.size();
        int idx = 0;
        if (current != null) {
            for (int i = 0; i < providers.size(); i++) {
                if (providers.get(i) == current) {
                    idx = i + 1;
                    break;
                }
            }
        }
        if (idx == 0 && total > 0) idx = 1;
        String base = root.getContext().getString(R.string.ui_manage_keys);
        String name = (current != null) ? current.label : "-";
        if (tvTitle != null) tvTitle.setText(base);
        setSubtitle(tvSubtitle, String.format(Locale.getDefault(), "%d/%d · %s", idx, total, name));

        if (ivTitleIcon != null) ivTitleIcon.setImageResource(R.drawable.ic_key_filled);


        LayoutInflater inflater = LayoutInflater.from(root.getContext());

        if (providers.isEmpty()) {
            TextView tv = new TextView(root.getContext());
            tv.setText(R.string.no_saved_keys);
            tv.setTextSize(16f);
            tv.setPadding(0, 8, 0, 8);
            container.addView(tv);
            return;
        }

        for (LanguageModel m : providers) {
            String key = sp.getApiKey(m);
            boolean hasKey = key != null && !key.trim().isEmpty();

            View item = inflater.inflate(R.layout.item_model_option, container, false);
            TextView tv = item.findViewById(R.id.tv_model_name);
            TextView tvDesc = item.findViewById(R.id.tv_model_desc);
            ImageView iv = item.findViewById(R.id.iv_icon);
            ImageView ivEdit = item.findViewById(R.id.iv_edit);
            CheckBox cb = item.findViewById(R.id.cb_selected);

            if (tv != null) tv.setText(m.label);
            if (tvDesc != null) tvDesc.setVisibility(View.GONE);
            if (iv != null) iv.setImageResource(R.drawable.ic_key_filled);
            if (cb != null) cb.setChecked(m == current);

            // visually indicate missing key
            item.setAlpha(hasKey ? 1.0f : 0.5f);

            item.setOnClickListener(v -> {
                if (!hasKey) {
                    Toast.makeText(root.getContext(),
                            root.getContext().getString(R.string.key_not_set_toast, m.label),
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                // Select provider immediately (persist now)
                pendingProvider[0] = m;
                pendingSubModel[0] = sp.getSubModel(m);

                try {
                    sp.setLanguageModel(m);
                    if (pendingSubModel[0] != null && !pendingSubModel[0].trim().isEmpty()) {
                        sp.setSubModel(m, pendingSubModel[0].trim());
                    }
                    broadcastConfigOnly(root.getContext(), sp);
                } catch (Throwable ignored) {}

                Toast.makeText(root.getContext(),
                        root.getContext().getString(R.string.msg_config_saved),
                        Toast.LENGTH_SHORT).show();

                // If provider has no cached models, fetch first; then go to model list.
                ensureModelsCachedThen(root.getContext(), sp, m, pendingSubModel, () -> {
                    screen[0] = Screen.MODELS;
                    render.run();
                });
            });

            // Pencil icon: edit key for this provider (preferred, no long press)
            if (ivEdit != null) {
                ivEdit.setVisibility(View.VISIBLE);
                ivEdit.setOnClickListener(v -> showAddOrEditKeyFlow(root.getContext(), sp, m, pendingProvider, pendingSubModel, render));
            }

            container.addView(item);
        }
    }

    
// -----------------------------
// Helpers: Add/Edit Role (staged)
// -----------------------------
private void showAddOrEditRoleDialog(Context ctx, SPManager sp, String[] pendingRoleId,
                                     RoleManager.Role editingRole, Runnable render) {
    if (ctx == null || sp == null) return;

    boolean isEdit = (editingRole != null
            && editingRole.id != null && !editingRole.id.trim().isEmpty()
            && !RoleManager.DEFAULT_ROLE_ID.equals(editingRole.id));

    View dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_add_role, null);

    TextView tvEmoji = dialogView.findViewById(R.id.tv_role_emoji);
    com.google.android.material.textfield.TextInputEditText etName = dialogView.findViewById(R.id.et_role_name);
    com.google.android.material.textfield.TextInputEditText etTrigger = dialogView.findViewById(R.id.et_role_trigger);
    com.google.android.material.textfield.TextInputEditText etPrompt = dialogView.findViewById(R.id.et_role_prompt);
    TextView tvToken = dialogView.findViewById(R.id.tv_token_count);
    LinearLayout llChips = dialogView.findViewById(R.id.ll_role_var_chips);

    androidx.appcompat.widget.SwitchCompat swOverride = dialogView.findViewById(R.id.sw_role_override);
    View panel = dialogView.findViewById(R.id.ll_role_override_panel);
    View rowOverrideModel = dialogView.findViewById(R.id.row_role_override_model);
    TextView tvOverrideModelValue = dialogView.findViewById(R.id.tv_role_override_model_value);
    com.google.android.material.slider.Slider sliderOverrideTemp = dialogView.findViewById(R.id.slider_role_override_temp);
    TextView tvOverrideTempValue = dialogView.findViewById(R.id.tv_role_override_temp_value);

    // Prefill
    if (isEdit) {
        if (tvEmoji != null) tvEmoji.setText(!TextUtils.isEmpty(editingRole.emoji) ? editingRole.emoji : "🤖");
        if (etName != null) etName.setText(editingRole.name != null ? editingRole.name : "");
        if (etTrigger != null) etTrigger.setText(editingRole.trigger != null ? editingRole.trigger : "");
        if (etPrompt != null) etPrompt.setText(editingRole.prompt != null ? editingRole.prompt : "");

        if (swOverride != null) swOverride.setChecked(editingRole.overrideEnabled);
        if (panel != null) panel.setVisibility(editingRole.overrideEnabled ? View.VISIBLE : View.GONE);

        if (tvOverrideModelValue != null) {
            String v = editingRole.overrideSubModel != null ? editingRole.overrideSubModel.trim() : "";
            tvOverrideModelValue.setText(TextUtils.isEmpty(v) ? "跟随全局" : v);
        }
        if (sliderOverrideTemp != null && tvOverrideTempValue != null) {
            float t = editingRole.overrideTemperature;
            if (t < 0f || t > 2.0f) t = 0.7f;
            sliderOverrideTemp.setValue(t);
            tvOverrideTempValue.setText(String.format(Locale.US, "%.1f", t));
        }
    } else {
        if (tvEmoji != null) tvEmoji.setText("🤖");
        if (tvOverrideModelValue != null) tvOverrideModelValue.setText("跟随全局");
        if (sliderOverrideTemp != null) sliderOverrideTemp.setValue(0.7f);
        if (tvOverrideTempValue != null) tvOverrideTempValue.setText("0.7");
        if (panel != null) panel.setVisibility(View.GONE);
        if (swOverride != null) swOverride.setChecked(false);
    }

    // Emoji picker
    if (tvEmoji != null) {
        tvEmoji.setOnClickListener(v -> {
            final String[] emojis = new String[]{
                    "🤖", "🧠", "💻", "📝", "🎨", "📊", "🧪", "🎯", "⚡", "🌌", "😎", "🕵️", "🧙"
            };
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("选择头像")
                    .setItems(emojis, (d, which) -> {
                        try { tvEmoji.setText(emojis[which]); } catch (Throwable ignored) {}
                    })
                    .show();
        });
    }

    // Variable chips toolbar (insert at cursor)
    if (llChips != null && etPrompt != null) {
        llChips.removeAllViews();
        final float density = ctx.getResources().getDisplayMetrics().density;

        java.util.function.BiConsumer<String, String> addChip = (label, token) -> {
            TextView tv = new TextView(ctx);
            tv.setText(label);
            tv.setTextSize(12);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);

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
                    android.text.Editable e = etPrompt.getText();
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
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateTokenLabel(s);
            }
            @Override public void afterTextChanged(android.text.Editable s) {
                updateTokenLabel(s);
            }

            private void updateTokenLabel(CharSequence s) {
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
        };
        etPrompt.addTextChangedListener(tw);
        try {
            CharSequence cur = etPrompt.getText();
            if (cur != null) tvToken.setText(cur.length() + " 字 / 约 0 Tokens");
        } catch (Throwable ignored) {}
    }

    // Override panel toggling
    if (swOverride != null && panel != null) {
        swOverride.setOnCheckedChangeListener((btn, checked) -> panel.setVisibility(checked ? View.VISIBLE : View.GONE));
    }

    // Override temperature value label
    if (sliderOverrideTemp != null && tvOverrideTempValue != null) {
        sliderOverrideTemp.addOnChangeListener((s, value, fromUser) -> {
            try { tvOverrideTempValue.setText(String.format(Locale.US, "%.1f", value)); } catch (Throwable ignored) {}
        });
    }

    // Override sub-model input
    if (rowOverrideModel != null && tvOverrideModelValue != null) {
        rowOverrideModel.setOnClickListener(v -> {
            final com.google.android.material.textfield.TextInputEditText input =
                    new com.google.android.material.textfield.TextInputEditText(ctx);
            input.setBackgroundResource(R.drawable.bg_input_field);
            input.setHint("留空表示跟随全局");
            int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density + 0.5f);
            int pv = (int) (12 * ctx.getResources().getDisplayMetrics().density + 0.5f);
            input.setPadding(pad, pv, pad, pv);

            String cur = String.valueOf(tvOverrideModelValue.getText()).trim();
            if (!TextUtils.isEmpty(cur) && !"跟随全局".equals(cur)) input.setText(cur);

            new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("专属大模型")
                    .setView(input)
                    .setNeutralButton("跟随全局", (d, w) -> {
                        try { tvOverrideModelValue.setText("跟随全局"); } catch (Throwable ignored) {}
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String v2 = input.getText() != null ? input.getText().toString().trim() : "";
                        if (TextUtils.isEmpty(v2)) {
                            tvOverrideModelValue.setText("跟随全局");
                        } else {
                            tvOverrideModelValue.setText(v2);
                        }
                    })
                    .show();
        });
    }

    int titleRes = isEdit ? R.string.ui_edit_role : R.string.ui_add_role;

    new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(titleRes)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
                String trigger = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "";
                String prompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";

                if (name.isEmpty() || prompt.isEmpty()) {
                    Toast.makeText(ctx, ctx.getString(R.string.ui_fields_required), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (trigger.length() > 10) trigger = trigger.substring(0, 10);

                String emoji = tvEmoji != null ? String.valueOf(tvEmoji.getText()).trim() : "🤖";
                if (TextUtils.isEmpty(emoji)) emoji = "🤖";

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

                String rolesJson = sp.getRolesJson();
                List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);
                if (roles == null) roles = new ArrayList<>();

                // Ensure the role name is unique (if duplicates exist, append 001/002/...).
                name = makeUniqueRoleName(name, roles, isEdit ? editingRole.id : null);

                // Build custom roles list (exclude default)
                List<RoleManager.Role> custom = new ArrayList<>();
                for (RoleManager.Role r : roles) {
                    if (r == null) continue;
                    if (RoleManager.DEFAULT_ROLE_ID.equals(r.id)) continue;
                    custom.add(r);
                }

                String id;
                if (isEdit) {
                    id = editingRole.id.trim();
                    boolean replaced = false;
                    for (int i = 0; i < custom.size(); i++) {
                        RoleManager.Role r = custom.get(i);
                        if (r != null && id.equals(r.id)) {
                            custom.set(i, new RoleManager.Role(id, name, prompt, trigger, emoji, overrideEnabled, overrideSubModel, overrideTemp));
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced) {
                        custom.add(new RoleManager.Role(id, name, prompt, trigger, emoji, overrideEnabled, overrideSubModel, overrideTemp));
                    }
                } else {
                    id = "custom_" + System.currentTimeMillis();
                    custom.add(new RoleManager.Role(id, name, prompt, trigger, emoji, overrideEnabled, overrideSubModel, overrideTemp));
                }

                sp.setRolesJson(RoleManager.serializeCustomRoles(custom));

                if (pendingRoleId != null) pendingRoleId[0] = id;

                try { broadcastConfigOnly(ctx, sp); } catch (Throwable ignored) {}
                Toast.makeText(ctx, ctx.getString(R.string.role_saved), Toast.LENGTH_SHORT).show();
                if (render != null) render.run();
            })
            .show();
}

private String makeUniqueRoleName(String desired, List<RoleManager.Role> roles, String excludeId) {
    if (desired == null) desired = "";
    String base = desired.trim();
    if (base.isEmpty()) return desired;

    HashSet<String> existing = new HashSet<>();
    if (roles != null) {
        for (RoleManager.Role r : roles) {
            if (r == null) continue;
            if (excludeId != null && excludeId.equals(r.id)) continue;
            if (r.name == null) continue;
            existing.add(r.name.trim());
        }
    }

    if (!existing.contains(base)) return base;

    int i = 1;
    while (i < 1000) {
        String candidate = base + String.format(Locale.getDefault(), "%03d", i);
        if (!existing.contains(candidate)) return candidate;
        i++;
    }
    // fallback (shouldn't happen)
    return base + System.currentTimeMillis();
}

// -----------------------------
// Helpers: Add/Edit Key (staged)
// -----------------------------
private void showAddOrEditKeyFlow(Context ctx, SPManager sp, LanguageModel fixedProvider,
                                 LanguageModel[] pendingProvider, String[] pendingSubModel,
                                 Runnable render) {
    if (ctx == null || sp == null) return;

    if (fixedProvider != null) {
        showKeyInputDialog(ctx, sp, fixedProvider, pendingProvider, pendingSubModel, render);
        return;
    }

    // Choose provider first
    // When adding a new key, do NOT show providers that already have a saved key.
    final ArrayList<LanguageModel> candidates = new ArrayList<>();
    for (LanguageModel m : LanguageModel.values()) {
        if (m == null) continue;
        String k = sp.getApiKey(m);
        boolean hasKey = k != null && !k.trim().isEmpty();
        if (!hasKey) candidates.add(m);
    }

    if (candidates.isEmpty()) {
        Toast.makeText(ctx, ctx.getString(R.string.ui_all_keys_added), Toast.LENGTH_SHORT).show();
        return;
    }

    final String[] labels = new String[candidates.size()];
    for (int i = 0; i < candidates.size(); i++) {
        labels[i] = candidates.get(i).label;
    }

    new AlertDialog.Builder(ctx)
            .setTitle(R.string.ui_choose_provider)
            .setItems(labels, (d, which) -> {
                if (which < 0 || which >= candidates.size()) return;
                showKeyInputDialog(ctx, sp, candidates.get(which), pendingProvider, pendingSubModel, render);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
}

private void showKeyInputDialog(Context ctx, SPManager sp, LanguageModel provider,
                                LanguageModel[] pendingProvider, String[] pendingSubModel,
                                Runnable render) {
    if (ctx == null || sp == null || provider == null) return;

    final TextInputLayout til = new TextInputLayout(ctx);
    til.setHint(provider.label + " API Key");
    til.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);

    final TextInputEditText et = new TextInputEditText(ctx);
    et.setSingleLine(true);
    et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    String old = sp.getApiKey(provider);
    if (old != null) et.setText(old);

    til.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    new AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.ui_add_key_for, provider.label))
            .setView(til)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String key = et.getText() != null ? et.getText().toString().trim() : "";
                if (key.isEmpty()) {
                    Toast.makeText(ctx, ctx.getString(R.string.ui_fields_required), Toast.LENGTH_SHORT).show();
                    return;
                }
                sp.setApiKey(provider, key);
                // Sync immediately so AI Settings grid and IME side can see the new key
                broadcastConfigOnly(ctx, sp);
                Toast.makeText(ctx, ctx.getString(R.string.ui_key_saved, provider.label), Toast.LENGTH_SHORT).show();

                // Optional: stage provider switch if user is on keys screen and wants immediate usage
                // (We do NOT auto-switch here; keep staged selections unchanged unless user taps the item.)
                if (render != null) render.run();
            })
            .show();
}

// -----------------------------
// Helpers: Inline Fetch Models Row (Model screen)
// -----------------------------
private void addInlineFetchModelsRow(Context ctx, LinearLayout container, SPManager sp, LanguageModel provider,
                                     String[] pendingSubModel, Runnable render) {
    if (ctx == null || container == null || sp == null || provider == null) return;

    // Claude has no public models endpoint - show disabled row
    boolean supported = provider != LanguageModel.Claude;

    LinearLayout row = new LinearLayout(ctx);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, 4, 0, 12);

    final EditText et = new EditText(ctx);
    et.setSingleLine(true);
    et.setHint(R.string.ui_proxy_baseurl_hint);

    String prefill = sp.getCachedModelsBaseUrl(provider);
    if (prefill == null || prefill.trim().isEmpty()) prefill = sp.getBaseUrl(provider);
    if (prefill == null) prefill = "";
    et.setText(prefill);

    // Save base URL immediately when the user finishes editing ("选择即保存").
    // Previously the value was only persisted after pressing "获取模型", which caused
    // the actual request to keep using the default endpoint.
    final Runnable saveBaseUrlNow = () -> {
        try {
            String input = et.getText() != null ? et.getText().toString().trim() : "";
            String baseUrl = normalizeBaseUrl(provider, input);
            String cur = sp.getBaseUrl(provider);
            if (cur == null) cur = "";
            if (!cur.equals(baseUrl)) {
                sp.setBaseUrl(provider, baseUrl);
                broadcastConfigOnly(ctx, sp);
            }
        } catch (Throwable ignored) {}
    };

    et.setOnFocusChangeListener((v, hasFocus) -> {
        if (!hasFocus) saveBaseUrlNow.run();
    });
    et.setImeOptions(EditorInfo.IME_ACTION_DONE);
    et.setOnEditorActionListener((v, actionId, event) -> {
        if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_SEND) {
            saveBaseUrlNow.run();
        }
        return false;
    });

    LinearLayout.LayoutParams lpEt = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    et.setLayoutParams(lpEt);

    MaterialButton btn = new MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    btn.setText(R.string.fetch_models);
    btn.setMinHeight((int) (ctx.getResources().getDisplayMetrics().density * 36));
    btn.setEnabled(supported);

    btn.setOnClickListener(v -> {
        if (!supported) {
            Toast.makeText(ctx, ctx.getString(R.string.fetch_models_claude_not_supported), Toast.LENGTH_SHORT).show();
            return;
        }

        String input = et.getText() != null ? et.getText().toString().trim() : "";
        String baseUrl = normalizeBaseUrl(provider, input);
        String apiKey = sp.getApiKey(provider);

        // Persist base URL even if the request fails.
        try { saveBaseUrlNow.run(); } catch (Throwable ignored) {}

        Toast.makeText(ctx, ctx.getString(R.string.ui_fetching_models), Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                List<String> models = fetchModelsFromEndpoint(provider, baseUrl, apiKey);
                Activity act = getParent();
                if (act != null) {
                    act.runOnUiThread(() -> {
                        if (models == null || models.isEmpty()) {
                            Toast.makeText(ctx, ctx.getString(R.string.fetch_models_empty), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        sp.setBaseUrl(provider, baseUrl);
                        sp.setCachedModels(provider, baseUrl, models);
                        broadcastConfigOnly(ctx, sp);

                        String cur = (pendingSubModel != null) ? pendingSubModel[0] : null;
                        if (pendingSubModel != null) {
                            if (cur == null || cur.trim().isEmpty() || !models.contains(cur)) {
                                pendingSubModel[0] = models.get(0);
                            }
                        }

                        // Save the selected submodel immediately.
                        try {
                            if (pendingSubModel != null && pendingSubModel[0] != null && !pendingSubModel[0].trim().isEmpty()) {
                                sp.setLanguageModel(provider);
                                sp.setSubModel(provider, pendingSubModel[0].trim());
                            }
                            broadcastConfigOnly(ctx, sp);
                        } catch (Throwable ignored) {}

                        Toast.makeText(ctx, ctx.getString(R.string.ui_models_cached_toast, models.size()), Toast.LENGTH_SHORT).show();
                        if (render != null) render.run();
                    });
                }
            } catch (Exception e) {
                Activity act = getParent();
                if (act != null) {
                    act.runOnUiThread(() ->
                            Toast.makeText(ctx, ctx.getString(R.string.fetch_models_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show()
                    );
                }
            }
        }).start();
    });

    row.addView(et);
    row.addView(btn);
    container.addView(row);
}

// -----------------------------
// Helpers: Inline Search Models Row (Model screen)
// -----------------------------
private void addInlineModelSearchRow(Context ctx, LinearLayout container, Runnable renderList) {
    if (ctx == null || container == null) return;

    LinearLayout row = new LinearLayout(ctx);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, 0, 0, 8);

    final EditText et = new EditText(ctx);
    et.setSingleLine(true);
    et.setHint(R.string.search_models);
    et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    et.setInputType(InputType.TYPE_CLASS_TEXT);

    String pre = modelSearchQuery != null ? modelSearchQuery : "";
    et.setText(pre);
    try {
        if (et.getText() != null) et.setSelection(et.getText().length());
    } catch (Exception ignored) {}

    LinearLayout.LayoutParams lpEt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    et.setLayoutParams(lpEt);

    et.addTextChangedListener(new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) {
            String q = s != null ? s.toString() : "";
            if (q == null) q = "";
            if (!q.equals(modelSearchQuery)) {
                modelSearchQuery = q;
                if (renderList != null) renderList.run();
            }
        }
    });

    row.addView(et);
    container.addView(row);
}


// -----------------------------
    // Helpers: Locate current selection
    // -----------------------------
    private void locateToChecked(LinearLayout container, ScrollView scrollView) {
        if (container == null || scrollView == null) return;
        View target = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == null) continue;
            CheckBox cb = child.findViewById(R.id.cb_selected);
            if (cb != null && cb.getVisibility() == View.VISIBLE && cb.isChecked()) {
                target = child;
                break;
            }
        }
        if (target == null) return;
        final View finalTarget = target;
        scrollView.post(() -> {
            try {
                int y = Math.max(0, finalTarget.getTop() - 24);
                scrollView.smoothScrollTo(0, y);
            } catch (Exception ignored) {
            }
        });
    }

    // -----------------------------
    // Helpers: ensure model cache
    // -----------------------------
    /**
     * If the provider has cached models, run {@code onReady} immediately.
     * Otherwise, prompt the user for proxy/base URL and fetch models, cache them,
     * then run {@code onReady}.
     *
     * This is used when the user switches provider/key (Key Management) or chooses a provider
     * from AI Settings. The user requested: only show the proxy input when there is NO cached
     * models.
     */
    private void ensureModelsCachedThen(Context ctx, SPManager sp, LanguageModel provider,
                                        String[] pendingSubModel,
                                        Runnable onReady) {
        if (ctx == null || sp == null || provider == null) {
            if (onReady != null) onReady.run();
            return;
        }

        List<String> cached = sp.getCachedModels(provider);
        if (cached != null && !cached.isEmpty()) {
            if (onReady != null) onReady.run();
            return;
        }

        // Claude has no public models-list endpoint
        if (provider == LanguageModel.Claude) {
            Toast.makeText(ctx, ctx.getString(R.string.fetch_models_claude_not_supported), Toast.LENGTH_SHORT).show();
            if (onReady != null) onReady.run();
            return;
        }

        showBaseUrlAndFetchDialogMissingCache(ctx, sp, provider, pendingSubModel, onReady);
    }

    /**
     * Show the proxy/base URL input and fetch models. Calls {@code onSuccess} only when models
     * are successfully fetched and cached.
     */
    private void showBaseUrlAndFetchDialogMissingCache(Context ctx, SPManager sp, LanguageModel provider,
                                                       String[] pendingSubModel,
                                                       Runnable onSuccess) {
        final EditText et = new EditText(ctx);
        et.setSingleLine(true);

        String prefill = sp.getCachedModelsBaseUrl(provider);
        if (prefill == null || prefill.trim().isEmpty()) prefill = sp.getBaseUrl(provider);
        if (prefill == null) prefill = "";
        et.setText(prefill);
        et.setHint(provider.getDefault(tn.eluea.kgpt.llm.LanguageModelField.BaseUrl));

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.ui_enter_proxy_title)
                .setMessage(R.string.ui_enter_proxy_msg)
                .setView(et)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    // Do nothing (stay on current screen)
                })
                .setPositiveButton(R.string.fetch_models, (d, w) -> {
                    String input = et.getText() != null ? et.getText().toString().trim() : "";
                    String baseUrl = normalizeBaseUrl(provider, input);
                    String apiKey = sp.getApiKey(provider);

                    Toast.makeText(ctx, ctx.getString(R.string.ui_fetching_models), Toast.LENGTH_SHORT).show();

                    new Thread(() -> {
                        try {
                            List<String> models = fetchModelsFromEndpoint(provider, baseUrl, apiKey);
                            Activity act = getParent();
                            if (act != null) {
                                act.runOnUiThread(() -> {
                                    if (models == null || models.isEmpty()) {
                                        Toast.makeText(ctx, ctx.getString(R.string.fetch_models_empty), Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    sp.setBaseUrl(provider, baseUrl);
                        sp.setCachedModels(provider, baseUrl, models);
                        broadcastConfigOnly(ctx, sp);

                                    // If the currently staged submodel is not in the new list, fall back to first
                                    String cur = (pendingSubModel != null) ? pendingSubModel[0] : null;
                                    if (cur == null || cur.trim().isEmpty() || !models.contains(cur)) {
                                        if (pendingSubModel != null) pendingSubModel[0] = models.get(0);
                                    }

                                    // Save the selected submodel immediately.
                                    try {
                                        if (pendingSubModel != null && pendingSubModel[0] != null && !pendingSubModel[0].trim().isEmpty()) {
                                            sp.setLanguageModel(provider);
                                            sp.setSubModel(provider, pendingSubModel[0].trim());
                                        }
                                        broadcastConfigOnly(ctx, sp);
                                    } catch (Throwable ignored) {}

                                    Toast.makeText(ctx, ctx.getString(R.string.ui_models_cached_toast, models.size()), Toast.LENGTH_SHORT).show();
                                    if (onSuccess != null) onSuccess.run();
                                });
                            }
                        } catch (Exception e) {
                            Activity act = getParent();
                            if (act != null) {
                                act.runOnUiThread(() -> Toast.makeText(ctx, ctx.getString(R.string.fetch_models_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show());
                            }
                        }
                    }).start();
                })
                .show();
    }

    // -----------------------------
    // Helpers: Key switching -> optional model refresh
    // -----------------------------
    private void showUpdateModelsPrompt(Context ctx, SPManager sp, LanguageModel provider,
                                        String[] pendingSubModel,
                                        Runnable onDone) {
        if (ctx == null || sp == null || provider == null) {
            if (onDone != null) onDone.run();
            return;
        }

        // Claude has no public models-list endpoint
        if (provider == LanguageModel.Claude) {
            Toast.makeText(ctx, ctx.getString(R.string.fetch_models_claude_not_supported), Toast.LENGTH_SHORT).show();
            if (onDone != null) onDone.run();
            return;
        }

        String title = ctx.getString(R.string.ui_update_models_title);
        String msg = ctx.getString(R.string.ui_update_models_msg, provider.label);

        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton(R.string.ui_update_models_no, (d, w) -> {
                    if (onDone != null) onDone.run();
                })
                .setPositiveButton(R.string.ui_update_models_yes, (d, w) -> {
                    showBaseUrlAndFetchDialog(ctx, sp, provider, pendingSubModel, onDone);
                })
                .show();
    }

    private void showBaseUrlAndFetchDialog(Context ctx, SPManager sp, LanguageModel provider,
                                           String[] pendingSubModel,
                                           Runnable onDone) {
        if (ctx == null || sp == null || provider == null) {
            if (onDone != null) onDone.run();
            return;
        }

        final EditText et = new EditText(ctx);
        et.setSingleLine(true);

        String prefill = sp.getCachedModelsBaseUrl(provider);
        if (prefill == null || prefill.trim().isEmpty()) prefill = sp.getBaseUrl(provider);
        if (prefill == null) prefill = "";
        et.setText(prefill);
        et.setHint(provider.getDefault(tn.eluea.kgpt.llm.LanguageModelField.BaseUrl));

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.ui_enter_proxy_title)
                .setMessage(R.string.ui_enter_proxy_msg)
                .setView(et)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (onDone != null) onDone.run();
                })
                .setPositiveButton(R.string.fetch_models, (d, w) -> {
                    String input = et.getText() != null ? et.getText().toString().trim() : "";
                    String baseUrl = normalizeBaseUrl(provider, input);
                    String apiKey = sp.getApiKey(provider);

                    Toast.makeText(ctx, ctx.getString(R.string.ui_fetching_models), Toast.LENGTH_SHORT).show();

                    new Thread(() -> {
                        try {
                            List<String> models = fetchModelsFromEndpoint(provider, baseUrl, apiKey);
                            Activity act = getParent();
                            if (act != null) {
                                act.runOnUiThread(() -> {
                                    if (models == null || models.isEmpty()) {
                                        Toast.makeText(ctx, ctx.getString(R.string.fetch_models_empty), Toast.LENGTH_SHORT).show();
                                        if (onDone != null) onDone.run();
                                        return;
                                    }
                                    sp.setBaseUrl(provider, baseUrl);
                        sp.setCachedModels(provider, baseUrl, models);
                        broadcastConfigOnly(ctx, sp);

                                    // If the currently staged submodel is not in the new list, fall back to first
                                    String cur = (pendingSubModel != null) ? pendingSubModel[0] : null;
                                    if (cur == null || cur.trim().isEmpty() || !models.contains(cur)) {
                                        pendingSubModel[0] = models.get(0);
                                    }

                                    // Save the selected submodel immediately.
                                    try {
                                        if (pendingSubModel != null && pendingSubModel[0] != null && !pendingSubModel[0].trim().isEmpty()) {
                                            sp.setLanguageModel(provider);
                                            sp.setSubModel(provider, pendingSubModel[0].trim());
                                        }
                                        broadcastConfigOnly(ctx, sp);
                                    } catch (Throwable ignored) {}

                                    Toast.makeText(ctx, ctx.getString(R.string.ui_models_cached_toast, models.size()), Toast.LENGTH_SHORT).show();
                                    if (onDone != null) onDone.run();
                                });
                            }
                        } catch (Exception e) {
                            Activity act = getParent();
                            if (act != null) {
                                act.runOnUiThread(() -> {
                                    Toast.makeText(ctx, ctx.getString(R.string.fetch_models_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                                    if (onDone != null) onDone.run();
                                });
                            }
                        }
                    }).start();
                })
                .show();
    }

    /**
     * Normalize proxy/relay base URL. If the user only enters a host, append the provider's default path.
     */
    private String normalizeBaseUrl(LanguageModel model, String input) {
        String defaultUrl = model.getDefault(tn.eluea.kgpt.llm.LanguageModelField.BaseUrl);
        if (input == null) return defaultUrl;

        String s = input.trim();
        if (s.isEmpty()) return defaultUrl;

        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }

        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);

        try {
            Uri uri = Uri.parse(s);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                String host = uri.getHost();
                String appendPath;

                if (model == LanguageModel.OpenRouter) {
                    // OpenRouter official endpoint uses /api/v1, most OpenAI-compatible relay servers use /v1.
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

        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private List<String> fetchModelsFromEndpoint(LanguageModel model, String baseUrl, String apiKey) throws Exception {
        List<String> out = new ArrayList<>();
        if (baseUrl == null || baseUrl.trim().isEmpty()) return out;

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
        return parseModels(body);
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private List<String> parseModels(String body) {
        List<String> list = new ArrayList<>();
        if (body == null) return list;
        try {
            JSONObject root = new JSONObject(body);

            // OpenAI style: { data: [ { id: ... }, ... ] }
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
                        // Gemini sometimes returns "models/xxx". Strip the prefix defensively.
                        if (name != null && name.startsWith("models/")) {
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
                            // Strip Gemini "models/" prefix if present.
                            if (name.startsWith("models/")) name = name.substring("models/".length());
                            list.add(name);
                        }
                    } else if (it instanceof String) {
                        list.add((String) it);
                    }
                }
            }

        } catch (Exception ignored) {
        }

        // De-dup and trim
        HashSet<String> seen = new HashSet<>();
        ArrayList<String> out = new ArrayList<>();
        for (String s : list) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (seen.add(v)) out.add(v);
        }
        return out;
    }
    // -----------------------------
    // Open AI Invocation editor inside the app (Commands/Triggers)
    // This avoids jumping to the old Settings dialog-tree (the unexpected KGPT menu).
    // -----------------------------
    private static final String EXTRA_OPEN_AI_INVOCATION = "kgpt_open_ai_invocation";
    private static final String EXTRA_AI_INVOCATION_TAB = "kgpt_ai_invocation_tab";

    private void openAiInvocationInApp(Context ctx, int tabIndex) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ctx, tn.eluea.kgpt.ui.main.MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra(EXTRA_OPEN_AI_INVOCATION, true);
            i.putExtra(EXTRA_AI_INVOCATION_TAB, tabIndex);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Toast.makeText(ctx, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        }
    }

    // -----------------------------
    // Quick Jump editor (deep-link templates)
    // -----------------------------

}