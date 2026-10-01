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
package tn.eluea.kgpt.core.ui.dialog.box;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;

import java.util.Locale;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.core.data.ConfigContainer;
import tn.eluea.kgpt.core.ui.dialog.DialogBoxManager;
import tn.eluea.kgpt.core.ui.dialog.DialogType;
import tn.eluea.kgpt.instruction.command.GenerativeAICommand;
import tn.eluea.kgpt.instruction.command.SimpleGenerativeAICommand;

import android.view.ContextThemeWrapper;
import tn.eluea.kgpt.util.MaterialYouManager;

public class CommandEditDialogBox extends DialogBox {
    public CommandEditDialogBox(DialogBoxManager dialogManager, Activity parent,
            Bundle inputBundle, ConfigContainer configContainer) {
        super(dialogManager, parent, inputBundle, configContainer);
    }

    @Override
    protected Dialog build() {
        safeguardCommands();

        tn.eluea.kgpt.ui.main.FloatingBottomSheet sheet = new tn.eluea.kgpt.ui.main.FloatingBottomSheet(getContext());

        // Use Activity Context directly to ensure Theme and Dynamic Colors are
        // preserved and TextInputLayout works
        Context themedContext = getContext();

        View layout = android.view.LayoutInflater.from(themedContext).inflate(R.layout.dialog_command_edit, null);

        TextInputEditText prefixEditText = layout.findViewById(R.id.edit_prefix);
        TextInputEditText messageEditText = layout.findViewById(R.id.edit_message);
        TextView tvTitle = layout.findViewById(R.id.tv_title);
        MaterialButton btnCancel = layout.findViewById(R.id.btn_cancel);
        MaterialButton btnSave = layout.findViewById(R.id.btn_save);
        MaterialButton btnDelete = layout.findViewById(R.id.btn_delete);
        ImageView headerIcon = layout.findViewById(R.id.iv_header_icon);
        View headerIconContainer = layout.findViewById(R.id.icon_container);

        if (headerIcon != null) {
            tn.eluea.kgpt.core.ui.dialog.utils.DialogUiUtils.applyMaterialYouTints(themedContext, headerIcon,
                    headerIconContainer);
        }

        // Built-in command prompts: display localized text in UI, but keep stored prompt in English unless user edits it.
        final String[] originalBuiltinPrompt = new String[]{null};
        final String[] builtinPromptDisplay = new String[]{null};
        final boolean[] isBuiltinPromptDisplay = new boolean[]{false};
        final boolean[] builtinPromptEdited = new boolean[]{false};

        final int commandIndex = getConfig().focusCommandIndex;
        if (commandIndex >= 0) {
            if (commandIndex >= getConfig().commands.size()) {
                // Should not happen, but safe guard
                try {
                    sheet.dismiss();
                } catch (Exception e) {
                }
                return sheet;
            }
            GenerativeAICommand command = getConfig().commands.get(commandIndex);
            String rawPrefix = command.getCommandPrefix();
            prefixEditText.setText(rawPrefix);

            // If this is a built-in command with an unmodified default prompt, show a Chinese display text (UI only).
            String display = getBuiltinPromptDisplayIfDefault(rawPrefix, command.getTweakMessage());
            if (display != null) {
                originalBuiltinPrompt[0] = command.getTweakMessage();
                builtinPromptDisplay[0] = display;
                isBuiltinPromptDisplay[0] = true;

                messageEditText.setText(display);
                messageEditText.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (builtinPromptDisplay[0] == null) return;
                        builtinPromptEdited[0] = (s != null) && (!s.toString().equals(builtinPromptDisplay[0]));
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                    }
                });
            } else {
                messageEditText.setText(command.getTweakMessage());
            }
            tvTitle.setText("Edit Command");
            btnDelete.setVisibility(View.VISIBLE);
        } else {
            tvTitle.setText("New Command");
            btnDelete.setVisibility(View.GONE);
        }

        btnCancel.setOnClickListener(v -> {
            sheet.dismiss();
            switchToDialog(DialogType.EditCommandsList);
        });

        btnSave.setOnClickListener(v -> {
            int commandPos = commandIndex;

            String prefix = prefixEditText.getText().toString().trim();
            String message = messageEditText.getText().toString();

            // If we are displaying a localized built-in prompt and the user didn't edit it, keep the stored English prompt.
            if (isBuiltinPromptDisplay[0]) {
                String current = message == null ? "" : message;
                if (!builtinPromptEdited[0] || (builtinPromptDisplay[0] != null && current.equals(builtinPromptDisplay[0]))) {
                    if (originalBuiltinPrompt[0] != null) {
                        message = originalBuiltinPrompt[0];
                    }
                }
            }

            long similarCount = getConfig().commands.stream().filter((c) -> prefix.equals(c.getCommandPrefix()))
                    .count();
            if ((commandPos == -1 && similarCount >= 1)
                    || (commandPos >= 0 && similarCount >= 2)) {
                Toast.makeText(getContext(), "There is another command with same name", Toast.LENGTH_LONG).show();
                return;
            }

            if (commandPos >= 0) {
                getConfig().commands.remove(commandPos);
            } else {
                commandPos = getConfig().commands.size();
            }

            getConfig().commands.add(commandPos, new SimpleGenerativeAICommand(prefix, message));

            // Save immediately to ContentProvider and notify listeners
            getConfig().saveToProvider();

            // Send broadcast to notify CommandManager of the change
            android.content.Intent broadcastIntent = new android.content.Intent(
                    tn.eluea.kgpt.ui.UiInteractor.ACTION_DIALOG_RESULT);
            broadcastIntent.putExtra(tn.eluea.kgpt.ui.UiInteractor.EXTRA_COMMAND_LIST,
                    tn.eluea.kgpt.instruction.command.Commands.encodeCommands(getConfig().commands));
            tn.eluea.kgpt.provider.BridgeAuth.send(getContext(), broadcastIntent);

            // Go back to command list instead of closing
            sheet.dismiss();
            switchToDialog(DialogType.EditCommandsList);
        });

        btnDelete.setOnClickListener(v -> {
            getConfig().commands.remove(commandIndex);

            // Save immediately to ContentProvider and notify listeners
            getConfig().saveToProvider();

            // Send broadcast to notify CommandManager of the change
            android.content.Intent broadcastIntent = new android.content.Intent(
                    tn.eluea.kgpt.ui.UiInteractor.ACTION_DIALOG_RESULT);
            broadcastIntent.putExtra(tn.eluea.kgpt.ui.UiInteractor.EXTRA_COMMAND_LIST,
                    tn.eluea.kgpt.instruction.command.Commands.encodeCommands(getConfig().commands));
            tn.eluea.kgpt.provider.BridgeAuth.send(getContext(), broadcastIntent);

            sheet.dismiss();
            switchToDialog(DialogType.EditCommandsList);
        });

        // Back Button
        View btnBackHeader = layout.findViewById(R.id.btn_back_header);
        if (btnBackHeader != null) {
            btnBackHeader.setOnClickListener(v -> {
                sheet.dismiss();
                switchToDialog(DialogType.EditCommandsList);
            });
        }

        // Tints
        tn.eluea.kgpt.core.ui.dialog.utils.DialogUiUtils.applyButtonTheme(themedContext, btnSave);

        sheet.setContentView(layout);
        return sheet;
    }



    private static String normalizePrefix(String raw) {
        if (raw == null) return null;
        String p = raw.trim();
        if (p.startsWith("/")) p = p.substring(1);
        return p.trim();
    }

    /**
     * UI-only: if this command matches the app's built-in default prompt (English), return a Chinese display prompt.
     * This allows the user to see Chinese in the editor while keeping the stored prompt English unless they edit it.
     */
    private static String getBuiltinPromptDisplayIfDefault(String rawPrefix, String message) {
        if (rawPrefix == null || message == null) return null;
        if (!Locale.getDefault().getLanguage().equals("zh")) return null;

        String p = normalizePrefix(rawPrefix);
        if (p == null || p.isEmpty()) return null;

        String defaultEn = null;
        for (GenerativeAICommand c : tn.eluea.kgpt.instruction.command.Commands.getDefaultCommands()) {
            if (p.equals(c.getCommandPrefix())) {
                defaultEn = c.getTweakMessage();
                break;
            }
        }
        if (defaultEn == null) return null;
        if (!message.trim().equals(defaultEn.trim())) return null;

        switch (p) {
            case "tr":
                return "翻译下面的文本。如果是阿拉伯语，翻译成英语；如果是英语，翻译成阿拉伯语。只输出翻译结果。";
            case "fix":
                return "修正下面文本的拼写和语法错误。只输出修正后的文本。";
            case "short":
                return "用一两句话总结下面的文本。";
            case "formal":
                return "用正式、专业的风格改写下面的文本。只输出改写后的文本。";
            case "casual":
                return "用友好、随意的风格改写下面的文本。只输出改写后的文本。";
            case "reply":
                return "为下面的消息写一条简短且合适的回复。只输出回复内容。";
            case "email":
                return "根据下面的主题撰写一封专业邮件。";
            case "explain":
                return "用通俗易懂的方式解释下面的主题。";
            case "code":
                return "按要求编写代码。不要添加额外解释，只输出代码。";
            case "emoji":
                return "给下面的文本添加合适的表情符号（emoji）。只输出添加了 emoji 的文本。";
            default:
                return null;
        }
    }

}
