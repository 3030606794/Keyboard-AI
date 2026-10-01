package tn.eluea.kgpt.ui.chat;

import java.util.ArrayList;
import java.util.List;

/** Builds context only from completed turns in the caller's selected session. */
public final class ChatHistory {
    private ChatHistory() { }
    public static String build(List<ChatMessage> messages, String prompt, int memoryTurns) {
        if (memoryTurns <= 0) return prompt;
        List<String> turns = new ArrayList<>();
        StringBuilder user = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.USER) {
                if (user.length() > 0) user.append('\n');
                user.append(message.getText());
            } else {
                if (message.isComplete() && user.length() > 0 && !message.getText().trim().isEmpty())
                    turns.add("User: " + user + "\nAssistant: " + message.getText() + "\n");
                user.setLength(0);
            }
        }
        if (turns.isEmpty()) return prompt;
        StringBuilder result = new StringBuilder("Previous conversation (context):\n");
        for (int i = Math.max(0, turns.size() - Math.min(memoryTurns, 20)); i < turns.size(); i++)
            result.append(turns.get(i));
        return result.append("\nCurrent user request:\n").append(prompt).toString();
    }
}
