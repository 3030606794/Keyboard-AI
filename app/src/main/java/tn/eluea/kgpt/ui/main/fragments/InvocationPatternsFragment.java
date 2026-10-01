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

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.ui.UiInteractor;
import android.content.Intent;
import tn.eluea.kgpt.instruction.command.InlineAskCommand;
import tn.eluea.kgpt.text.parse.ParsePattern;
import tn.eluea.kgpt.text.parse.PatternType;
import tn.eluea.kgpt.ui.main.BottomSheetHelper;
import tn.eluea.kgpt.ui.main.FloatingBottomSheet;
import tn.eluea.kgpt.ui.main.adapters.PatternsAdapter;

public class InvocationPatternsFragment extends Fragment {

    private RecyclerView rvPatterns;
    private TextView tvNoPatterns;
    private MaterialSwitch switchTriggersMaster;
    private PatternsAdapter adapter;
    private List<ParsePattern> patterns;

    public InvocationPatternsFragment() {
        super(R.layout.fragment_invocation_patterns);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvPatterns = view.findViewById(R.id.rv_patterns);
        tvNoPatterns = view.findViewById(R.id.tv_no_patterns);

        // Master switch: enable/disable all triggers
        switchTriggersMaster = view.findViewById(R.id.switch_triggers_master);
        if (switchTriggersMaster != null) {
            boolean enabled = SPManager.getInstance().getInvocationTriggersEnabled();
            switchTriggersMaster.setChecked(enabled);
            applyMasterUi(enabled);
            switchTriggersMaster.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    if (isChecked) {
                        SPManager.getInstance().restoreInvocationTriggersFromBackup();
                    } else {
                        SPManager.getInstance().disableAllInvocationTriggersWithBackup();
                    }
                } catch (Throwable ignored) {
                }
                // Refresh list + notify parser immediately
                loadPatterns();
                syncConfig();
                Toast.makeText(getContext(),
                        getString(isChecked ? R.string.ui_triggers_enabled_toast : R.string.ui_triggers_disabled_toast),
                        Toast.LENGTH_SHORT).show();
            });
        }

        setupRecyclerView();
        loadPatterns();
    }

    private void applyMasterUi(boolean enabled) {
        if (rvPatterns != null) {
            rvPatterns.setAlpha(1.0f);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            if (switchTriggersMaster != null) {
                boolean enabled = SPManager.getInstance().getInvocationTriggersEnabled();
                switchTriggersMaster.setChecked(enabled);
                applyMasterUi(enabled);
            }
        } catch (Throwable ignored) {
        }

        // Patterns (trigger symbols) may be updated from other screens (e.g. default role trigger shortcut).
        // Reload so UI stays in sync.
        try { loadPatterns(); } catch (Throwable ignored) {}
    }

    private void setupRecyclerView() {
        rvPatterns.setLayoutManager(new LinearLayoutManager(requireContext()));
        patterns = SPManager.getInstance().getParsePatterns();

        adapter = new PatternsAdapter(patterns, new PatternsAdapter.OnPatternClickListener() {
            @Override
            public void onPatternClick(ParsePattern pattern, int position) {
                showEditPatternDialog(pattern, position);
            }

            @Override
            public void onPatternDelete(ParsePattern pattern, int position) {
                showResetPatternConfirmation(pattern, position);
            }
        });
        rvPatterns.setAdapter(adapter);
    }

    private void loadPatterns() {
        patterns = SPManager.getInstance().getParsePatterns();
        if (patterns == null || patterns.isEmpty()) {
            rvPatterns.setVisibility(View.GONE);
            tvNoPatterns.setVisibility(View.VISIBLE);
        } else {
            rvPatterns.setVisibility(View.VISIBLE);
            tvNoPatterns.setVisibility(View.GONE);
            adapter.updatePatterns(patterns);
        }
    }

    public void showHowToUse() {
        FloatingBottomSheet bottomSheet = BottomSheetHelper.showFloating(requireContext(), R.layout.bottom_sheet_ai_usage);
        View sheetView = bottomSheet.findViewById(R.id.bottom_sheet_container);
        if (sheetView == null) {
            bottomSheet.show();
            return;
        }

        TextView tvAiExample = bottomSheet.findViewById(R.id.tv_ai_trigger_example);
        TextView tvAskExample = bottomSheet.findViewById(R.id.tv_ask_example);
        TextView tvCommandExample = bottomSheet.findViewById(R.id.tv_command_example);
        TextView tvFormatExample = bottomSheet.findViewById(R.id.tv_format_example);
        MaterialButton btnClose = bottomSheet.findViewById(R.id.btn_close);

        // Read current symbols
        String aiTriggerSymbol = "$";
        String italicSymbol = "|";
        String boldSymbol = "@";
        String crossoutSymbol = "~";
        String underlineSymbol = "_";

        List<ParsePattern> ps = SPManager.getInstance().getParsePatterns();
        if (ps != null) {
            for (ParsePattern p : ps) {
                String sym = PatternType.regexToSymbol(p.getPattern().pattern());
                if (sym == null || sym.isEmpty()) continue;
                switch (p.getType()) {
                    case CommandAI:
                        aiTriggerSymbol = sym;
                        break;
                    case FormatItalic:
                        italicSymbol = sym;
                        break;
                    case FormatBold:
                        boldSymbol = sym;
                        break;
                    case FormatCrossout:
                        crossoutSymbol = sym;
                        break;
                    case FormatUnderline:
                        underlineSymbol = sym;
                        break;
                }
            }
        }

        String askPrefix = InlineAskCommand.getPrefix();
        String cmdName = "translate";
        // Try to pick first custom command name for the example
        try {
            List<tn.eluea.kgpt.instruction.command.GenerativeAICommand> cmds = SPManager.getInstance().getGenerativeAICommands();
            if (cmds != null && !cmds.isEmpty() && cmds.get(0) != null && cmds.get(0).getCommandPrefix() != null) {
                cmdName = cmds.get(0).getCommandPrefix();
            }
        } catch (Exception ignored) {
        }

        boolean isZh = java.util.Locale.getDefault().getLanguage().equals("zh");
        if (tvAiExample != null) {
            tvAiExample.setText(isZh ? ("天气怎么样？" + aiTriggerSymbol) : ("What is the weather?" + aiTriggerSymbol));
        }
        if (tvAskExample != null) {
            tvAskExample.setText(isZh
                    ? ("备注 /" + askPrefix + " 时间？" + aiTriggerSymbol + " → 保留“备注”。")
                    : ("Note. /" + askPrefix + " time?" + aiTriggerSymbol + " → keeps Note."));
        }
        if (tvCommandExample != null) {
            tvCommandExample.setText(isZh
                    ? ("你好 /" + cmdName + aiTriggerSymbol)
                    : ("Hello /" + cmdName + aiTriggerSymbol));
        }
        if (tvFormatExample != null) {
            tvFormatExample.setText(isZh
                    ? ("文本" + italicSymbol + " 文本" + boldSymbol + " 文本" + crossoutSymbol + " 文本" + underlineSymbol)
                    : ("text" + italicSymbol + " text" + boldSymbol + " text" + crossoutSymbol + " text" + underlineSymbol));
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> bottomSheet.dismiss());
        }

        BottomSheetHelper.applyTheme(requireContext(), sheetView);
        bottomSheet.show();
    }

    private void showEditPatternDialog(ParsePattern pattern, int position) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_pattern_symbol, null);
        TextView tvPatternType = dialogView.findViewById(R.id.tv_pattern_type);
        TextView tvDescription = dialogView.findViewById(R.id.tv_description);
        MaterialSwitch switchEnabled = dialogView.findViewById(R.id.switch_enabled);
        TextInputLayout inputLayout = dialogView.findViewById(R.id.input_layout_symbol);
        TextInputEditText etSymbol = dialogView.findViewById(R.id.et_symbol);
        TextView tvExample = dialogView.findViewById(R.id.tv_example);

        tvPatternType.setText(pattern.getType().title);
        tvDescription.setText(pattern.getType().description);
        switchEnabled.setChecked(pattern.isEnabled());
        switchEnabled.setEnabled(true);

        String currentSymbol = PatternType.regexToSymbol(pattern.getPattern().pattern());
        if (currentSymbol == null || currentSymbol.isEmpty()) {
            currentSymbol = pattern.getType().defaultSymbol;
        }
        etSymbol.setText(currentSymbol);
        // Guard against maxLength trimming (selection must be within current text length)
        int sel = 0;
        try {
            sel = Math.min(currentSymbol.length(), etSymbol.getText() != null ? etSymbol.getText().length() : 0);
        } catch (Throwable ignored) {
        }
        etSymbol.setSelection(sel);

        updateExample(tvExample, pattern.getType(), currentSymbol);

        // The master toggle is a bulk action (disable/restore). Individual triggers must always be editable.
        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ParsePattern updated = pattern.withEnabled(isChecked);
            patterns.set(position, updated);
            adapter.updatePatterns(patterns);
            syncConfig();
            Toast.makeText(requireContext(),
                    getString(isChecked ? R.string.msg_trigger_enabled : R.string.msg_trigger_disabled,
                            updated.getType().title),
                    Toast.LENGTH_SHORT).show();
        });

        etSymbol.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String sym = s == null ? "" : s.toString();
                updateExample(tvExample, pattern.getType(), sym);
                if (sym.isEmpty()) {
                    inputLayout.setError(getString(R.string.msg_symbol_empty));
                } else if (sym.length() > 32) {
                    inputLayout.setError(getString(R.string.msg_symbol_too_long));
                } else {
                    inputLayout.setError(null);
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        MaterialButton btnReset = dialogView.findViewById(R.id.btn_reset);
        MaterialButton btnCancel = dialogView.findViewById(R.id.btn_cancel);
        MaterialButton btnSave = dialogView.findViewById(R.id.btn_save);

        btnReset.setOnClickListener(v -> {
            String defaultSymbol = pattern.getType().defaultSymbol;
            etSymbol.setText(defaultSymbol);
            int s = 0;
            try {
                s = Math.min(defaultSymbol.length(), etSymbol.getText() != null ? etSymbol.getText().length() : 0);
            } catch (Throwable ignored) {
            }
            etSymbol.setSelection(s);
        });
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String newSymbol = etSymbol.getText() == null ? "" : etSymbol.getText().toString();
            if (newSymbol.isEmpty()) {
                inputLayout.setError(getString(R.string.msg_symbol_empty));
                return;
            }
            if (newSymbol.length() > 32) {
                inputLayout.setError(getString(R.string.msg_symbol_too_long));
                return;
            }

            // Update regex pattern
            String newRegex = PatternType.symbolToRegex(newSymbol, pattern.getType().groupCount);
            ParsePattern updated = new ParsePattern(pattern.getType(), newRegex, pattern.getExtras());
            // IMPORTANT: the enabled switch can be toggled before pressing "Save".
            // Don't rely on the old `pattern` object (it may still have the previous enabled state),
            // otherwise pressing Save would silently revert the switch.
            updated.setEnabled(switchEnabled.isChecked());

            patterns.set(position, updated);
            adapter.updatePatterns(patterns);
            syncConfig();

            Toast.makeText(requireContext(),
                    getString(R.string.msg_trigger_symbol_updated, updated.getType().title, newSymbol),
                    Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void updateExample(TextView tvExample, PatternType type, String symbol) {
        if (tvExample == null) return;

        if (symbol == null) symbol = "";
        boolean isZh = java.util.Locale.getDefault().getLanguage().equals("zh");

        String example;
        if (isZh) {
            switch (type) {
                case CommandAI:
                    example = "写一首关于自然的诗" + symbol;
                    break;
                case CommandCustom:
                    example = "你好世界 /translate" + symbol;
                    break;
                case FormatItalic:
                    example = "重要文本" + symbol;
                    break;
                case FormatBold:
                    example = "高亮这段" + symbol;
                    break;
                case FormatCrossout:
                    example = "删除的文字" + symbol;
                    break;
                case FormatUnderline:
                    example = "带下划线的文字" + symbol;
                    break;
                case WebSearch:
                    example = "关于 AI 的最新新闻" + symbol;
                    break;
                case Settings:
                    example = symbol;
                    break;
                default:
                    example = "在这里输入文本" + symbol;
                    break;
            }
            tvExample.setText("示例：" + example);
            return;
        }

        switch (type) {
            case CommandAI:
                example = "Write a poem about nature" + symbol;
                break;
            case CommandCustom:
                example = "Hello world /translate" + symbol;
                break;
            case FormatItalic:
                example = "important text" + symbol;
                break;
            case FormatBold:
                example = "highlight this" + symbol;
                break;
            case FormatCrossout:
                example = "deleted text" + symbol;
                break;
            case FormatUnderline:
                example = "underlined text" + symbol;
                break;
            case WebSearch:
                example = "latest news about AI" + symbol;
                break;
            case Settings:
                example = symbol;
                break;
            default:
                example = "your text here" + symbol;
                break;
        }

        tvExample.setText("Example: " + example);
    }

    private void showResetPatternConfirmation(ParsePattern pattern, int position) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.btn_reset_pattern))
                .setMessage(getString(R.string.msg_reset_pattern_confirm, pattern.getType().title, pattern.getType().defaultSymbol))
                .setPositiveButton(R.string.ui_reset, (dialog, which) -> {
                    ParsePattern reset = new ParsePattern(pattern.getType(), pattern.getType().defaultPattern);
                    reset.setEnabled(true);
                    patterns.set(position, reset);
                    adapter.updatePatterns(patterns);
                    syncConfig();
                    Toast.makeText(requireContext(),
                            getString(R.string.msg_trigger_reset, reset.getType().title),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.ui_cancel, null)
                .show();
    }

    public void syncConfig() {
        SPManager.getInstance().setParsePatterns(patterns);
        // Notify IME/TextParser process immediately (no force-stop/restart needed)
        try {
            Intent i = new Intent(UiInteractor.ACTION_DIALOG_RESULT);
            String patternsRaw = SPManager.getInstance().getParsePatternsRaw();
            if (patternsRaw != null) {
                i.putExtra(UiInteractor.EXTRA_PATTERN_LIST, patternsRaw);
            }
            tn.eluea.kgpt.provider.BridgeAuth.send(requireContext(), i);
        } catch (Throwable ignored) {
        }
    }
}
