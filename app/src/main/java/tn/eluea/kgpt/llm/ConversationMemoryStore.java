package tn.eluea.kgpt.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory conversation context store.
 *
 * This is intentionally lightweight and process-local. It keeps a rolling window of the
 * latest turns (user+assistant). The "memory level" preference decides how many turns
 * are included in the next request as context.
 *
 * NOTE: This store is NOT persisted across reboot. It only provides multi-turn context
 * within the current process lifetime.
 */
public class ConversationMemoryStore {

    private static final int MAX_TURNS = 20; // hard cap to avoid unbounded growth

    private static volatile ConversationMemoryStore sInstance;

    private static final class Turn {
        final String user;
        final String assistant;

        Turn(String user, String assistant) {
            this.user = user == null ? "" : user;
            this.assistant = assistant == null ? "" : assistant;
        }
    }

    private final Object lock = new Object();
    private final ArrayList<Turn> turns = new ArrayList<>();
    private String scopeKey = "";

    public static ConversationMemoryStore getInstance() {
        if (sInstance == null) {
            synchronized (ConversationMemoryStore.class) {
                if (sInstance == null) sInstance = new ConversationMemoryStore();
            }
        }
        return sInstance;
    }

    /** Clears memory when switching to a different scope (model/role). */
    public void ensureScope(String newScopeKey) {
        if (newScopeKey == null) newScopeKey = "";
        synchronized (lock) {
            if (!newScopeKey.equals(scopeKey)) {
                scopeKey = newScopeKey;
                turns.clear();
            }
        }
    }

    public void clear() {
        synchronized (lock) {
            turns.clear();
        }
    }

    public void addTurn(String user, String assistant) {
        if (user == null || user.trim().isEmpty()) return;
        synchronized (lock) {
            turns.add(new Turn(user, assistant));
            while (turns.size() > MAX_TURNS) {
                turns.remove(0);
            }
        }
    }

    /** Returns the most recent N turns, oldest->newest. */
    private List<Turn> getRecentTurns(int n) {
        ArrayList<Turn> out = new ArrayList<>();
        synchronized (lock) {
            int size = turns.size();
            if (size == 0 || n <= 0) return out;
            int start = Math.max(0, size - n);
            for (int i = start; i < size; i++) out.add(turns.get(i));
        }
        return out;
    }

    /**
     * Builds a single prompt string that includes conversation history.
     * This works across providers even if they don't support multi-message chat APIs.
     */
    public String buildPromptWithHistory(String userPrompt, int memoryTurns) {
        return buildPromptWithHistory(userPrompt, memoryTurns, false);
    }

    /**
     * Build a prompt with conversation history attached.
     *
     * @param autoSummarize If true and memoryTurns is high, older turns will be compacted into a short
     *                      bullet summary to save tokens and improve stability.
     */
    public String buildPromptWithHistory(String userPrompt, int memoryTurns, boolean autoSummarize) {
        if (userPrompt == null) userPrompt = "";
        String prompt = userPrompt.trim();
        if (prompt.isEmpty()) return userPrompt;

        int n = memoryTurns;
        if (n < 0) n = 0;
        // True support up to 20 turns (UI allows 0..20, store cap is also 20).
        if (n > 20) n = 20;

        List<Turn> recent = getRecentTurns(n);
        if (recent.isEmpty()) return userPrompt;

        final int keepFull = 3;
        boolean doSummarize = autoSummarize && recent.size() > keepFull;

        StringBuilder sb = new StringBuilder();
        if (doSummarize) {
            int cut = Math.max(0, recent.size() - keepFull);
            List<Turn> older = recent.subList(0, cut);
            List<Turn> last = recent.subList(cut, recent.size());

            sb.append("Conversation summary (older turns):\n");
            for (Turn t : older) {
                String u = compact(t.user, 80);
                String a = compact(t.assistant, 100);
                if (!u.isEmpty() || !a.isEmpty()) {
                    sb.append("• U: ").append(u);
                    if (!a.isEmpty()) sb.append(" | A: ").append(a);
                    sb.append("\n");
                }
            }

            sb.append("Recent turns:\n");
            for (Turn t : last) {
                if (t.user != null && !t.user.isEmpty()) {
                    sb.append("User: ").append(t.user).append("\n");
                }
                if (t.assistant != null && !t.assistant.isEmpty()) {
                    sb.append("Assistant: ").append(t.assistant).append("\n");
                }
            }
        } else {
            sb.append("Conversation so far (for context):\n");
            for (Turn t : recent) {
                if (t.user != null && !t.user.isEmpty()) {
                    sb.append("User: ").append(t.user).append("\n");
                }
                if (t.assistant != null && !t.assistant.isEmpty()) {
                    sb.append("Assistant: ").append(t.assistant).append("\n");
                }
            }
        }

        sb.append("User: ").append(prompt);
        return sb.toString();
    }

    private static String compact(String s, int maxChars) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.length() <= maxChars) return t;
        return t.substring(0, Math.max(0, maxChars)).trim() + "…";
    }
}
