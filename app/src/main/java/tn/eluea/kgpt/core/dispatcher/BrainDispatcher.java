package tn.eluea.kgpt.core.dispatcher;

import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.core.ai.AiResponseManager;
import tn.eluea.kgpt.instruction.command.AbstractCommand;
import tn.eluea.kgpt.instruction.command.CommandManager;
import tn.eluea.kgpt.instruction.command.GenerativeAICommand;
import tn.eluea.kgpt.instruction.command.WebSearchCommand;
import tn.eluea.kgpt.text.parse.result.AIParseResult;
import tn.eluea.kgpt.text.parse.result.AppTriggerParseResult;
import tn.eluea.kgpt.text.parse.result.CommandParseResult;
import tn.eluea.kgpt.text.parse.result.FormatParseResult;
import tn.eluea.kgpt.text.parse.result.InlineAskParseResult;
import tn.eluea.kgpt.text.parse.result.InlineCommandParseResult;
import tn.eluea.kgpt.text.parse.result.ParseResult;
import tn.eluea.kgpt.text.parse.result.SettingsParseResult;
import tn.eluea.kgpt.text.parse.result.TextActionParseResult;
import tn.eluea.kgpt.text.parse.result.WebSearchParseResult;
import tn.eluea.kgpt.text.parse.result.QuickJumpParseResult;
import tn.eluea.kgpt.text.parse.result.ModelSwitchParseResult;
import tn.eluea.kgpt.text.parse.result.AIClipboardParseResult;
import tn.eluea.kgpt.text.parse.result.QuickJumpMenuParseResult;
import tn.eluea.kgpt.text.transform.format.TextUnicodeConverter;
import tn.eluea.kgpt.features.textactions.TextActionPrompts;
import tn.eluea.kgpt.ui.IMSController;
import tn.eluea.kgpt.ui.UiInteractor;

public class BrainDispatcher {

    private final AiResponseManager aiManager;
    private final CommandManager commandManager;

    public BrainDispatcher(AiResponseManager aiManager, CommandManager commandManager) {
        this.aiManager = aiManager;
        this.commandManager = commandManager;
    }

    public boolean requiresAi(ParseResult result) {
        if (result instanceof AIParseResult || result instanceof InlineAskParseResult || result instanceof TextActionParseResult) return true;
        String name = result instanceof CommandParseResult ? ((CommandParseResult) result).command
                : result instanceof InlineCommandParseResult ? ((InlineCommandParseResult) result).command : null;
        return name != null && commandManager.get(name) instanceof GenerativeAICommand;
    }

    public void dispatch(ParseResult parseResult) {
        IMSController imsController = UiInteractor.getInstance().getIMSController();

        if (parseResult instanceof FormatParseResult) {
            handleFormat((FormatParseResult) parseResult, imsController);
        } else if (parseResult instanceof AIParseResult) {
            AIParseResult res = (AIParseResult) parseResult;
            // If this AI invocation was triggered by a role-specific keyword, apply that role.
            String roleId = res.roleIdOverride;
            if (roleId != null && !roleId.trim().isEmpty()) {
                // IMPORTANT:
                // Role-specific triggers are meant to be *per-invocation* overrides.
                // Persisting them as the "active role" makes the next normal AI call (e.g. the
                // global trigger like "gpt") incorrectly keep the previous role, which feels
                // like "can't switch back to default".
                //
                // The active role should only be changed from the UI (Role Manager) or a dedicated
                // command—not as a side-effect of using a role trigger.
                aiManager.generateResponse(res.prompt, null, roleId);
            } else {
                aiManager.generateResponse(res.prompt, null);
            }
        } else if (parseResult instanceof InlineAskParseResult) {
            InlineAskParseResult res = (InlineAskParseResult) parseResult;
            aiManager.generateResponse(res.prompt, null);
        } else if (parseResult instanceof InlineCommandParseResult) {
            handleInlineCommand((InlineCommandParseResult) parseResult);
        } else if (parseResult instanceof CommandParseResult) {
            handleCommand((CommandParseResult) parseResult);
        } else if (parseResult instanceof SettingsParseResult) {
            UiInteractor.getInstance().showSettingsDialog();
        } else if (parseResult instanceof ModelSwitchParseResult) {
            UiInteractor.getInstance().showChooseSubModelDialog();
        } else if (parseResult instanceof AIClipboardParseResult) {
            UiInteractor.getInstance().showAiClipboardDialog();
        } else if (parseResult instanceof QuickJumpMenuParseResult) {
            UiInteractor.getInstance().showQuickJumpDialog();
        } else if (parseResult instanceof QuickJumpParseResult) {
            handleQuickJump((QuickJumpParseResult) parseResult);
        } else if (parseResult instanceof WebSearchParseResult) {
            handleWebSearch((WebSearchParseResult) parseResult);
        } else if (parseResult instanceof AppTriggerParseResult) {
            handleAppTrigger((AppTriggerParseResult) parseResult);
        } else if (parseResult instanceof TextActionParseResult) {
            handleTextAction((TextActionParseResult) parseResult, imsController);
        }
    }

    private void handleFormat(FormatParseResult result, IMSController imsController) {
        String newText = TextUnicodeConverter.convert(result.target, result.conversionMethod);

        imsController.stopNotifyInput();
        imsController.commit(newText);
        imsController.startNotifyInput();
    }

    private void handleCommand(CommandParseResult result) {
        if (result.command.isEmpty()) {
            UiInteractor.getInstance().showEditCommandsDialog();
        } else {
            AbstractCommand command = commandManager.get(result.command);
            if (command instanceof GenerativeAICommand) {
                GenerativeAICommand genAICommand = (GenerativeAICommand) command;
                aiManager.generateResponse(result.prompt, genAICommand.getTweakMessage());
            } else if (command instanceof WebSearchCommand) {
                Context ctx = UiInteractor.getInstance().getContext();
                String url = SPManager.getSearchUrlFromKGPT(ctx, result.prompt);
                UiInteractor.getInstance().showWebSearchDialog("Web Search", url);
            }
        }
    }

    private void handleInlineCommand(InlineCommandParseResult result) {
        // Handle inline command - similar to regular command but preserves text before it
        AbstractCommand command = commandManager.get(result.command);
        if (command instanceof GenerativeAICommand) {
            GenerativeAICommand genAICommand = (GenerativeAICommand) command;
            aiManager.generateResponse(result.prompt, genAICommand.getTweakMessage());
        } else if (command instanceof WebSearchCommand) {
            Context ctx = UiInteractor.getInstance().getContext();
            String url = SPManager.getSearchUrlFromKGPT(ctx, result.prompt);
            UiInteractor.getInstance().showWebSearchDialog("Web Search", url);
        }
    }

    private void handleQuickJump(QuickJumpParseResult result) {
        final Context ctx = UiInteractor.getInstance().getContext();
        final String url = result != null ? result.url : null;
        if (ctx == null || url == null || url.trim().isEmpty()) return;

        UiInteractor.getInstance().post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                ctx.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                UiInteractor.getInstance().toastShort("No app can open this link");
            } catch (Throwable t) {
                UiInteractor.getInstance().toastShort("Failed to open link");
            }
        });
    }

    private void handleWebSearch(WebSearchParseResult result) {
        Context ctx = UiInteractor.getInstance().getContext();
        String url = SPManager.getSearchUrlFromKGPT(ctx, result.query);
        UiInteractor.getInstance().showWebSearchDialog("Web Search", url);
    }

    private void handleAppTrigger(AppTriggerParseResult result) {
        tn.eluea.kgpt.util.Logger.log("AppTrigger detected: trigger='" + result.trigger +
                "', package='" + result.packageName +
                "', activity='" + result.activityName +
                "', app='" + result.appName + "'");

        // Launch on main thread to ensure proper context
        UiInteractor.getInstance().post(() -> {
            boolean launched = UiInteractor.getInstance().launchApp(
                    result.packageName,
                    result.activityName);
            if (!launched) {
                tn.eluea.kgpt.util.Logger.log("Failed to launch app: " + result.packageName);
                UiInteractor.getInstance().toastShort("Failed to launch " + result.appName);
            }
        });
    }

    private void handleTextAction(TextActionParseResult result, IMSController imsController) {
        // Handle text action commands like "$rephrase", "$fix", etc.
        tn.eluea.kgpt.util.Logger.log("TextAction detected: action=" + result.action.name() +
                ", text='" + result.text + "'");

        // Get the system message for this action
        String systemMessage = TextActionPrompts.getSystemMessage(result.action);
        String prompt = TextActionPrompts.buildPrompt(result.action, result.text);

        if (aiManager.hasSafeEdit()) {
            aiManager.generateResponse(prompt, systemMessage);
            return;
        }

        // First, commit the original text back (since we deleted the command)
        imsController.stopNotifyInput();
        imsController.commit(result.text);
        imsController.startNotifyInput();

        // Then delete it and generate the response
        imsController.stopNotifyInput();
        imsController.delete(result.text.length());
        imsController.startNotifyInput();

        // Generate the AI response
        aiManager.generateResponse(prompt, systemMessage);
    }
}
