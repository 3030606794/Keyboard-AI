package tn.eluea.kgpt.ui.chat;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ChatHistoryTest {
    private ChatMessage user(String text) { return new ChatMessage(ChatMessage.Role.USER, text); }
    private ChatMessage assistant(String text) { return new ChatMessage(ChatMessage.Role.ASSISTANT, text); }
    @Test public void sessionsNeverReadEachOthersHistory() {
        String first = ChatHistory.build(Arrays.asList(user("session A"), assistant("answer A")), "next", 5);
        String second = ChatHistory.build(Arrays.asList(user("session B"), assistant("answer B")), "next", 5);
        assertTrue(first.contains("session A")); assertFalse(first.contains("session B"));
        assertTrue(second.contains("session B")); assertFalse(second.contains("session A"));
    }
    @Test public void cancelledAndFailedResponsesAreExcluded() {
        ChatMessage cancelled = assistant("partial response"); cancelled.setComplete(false);
        assertEquals("next", ChatHistory.build(Arrays.asList(user("old"), cancelled), "next", 5));
    }
    @Test public void memoryWindowAndDisabledMemoryAreRespected() {
        java.util.List<ChatMessage> history = Arrays.asList(user("old question"), assistant("old answer"), user("new question"), assistant("new answer"));
        String prompt = ChatHistory.build(history, "next", 1);
        assertFalse(prompt.contains("old question")); assertTrue(prompt.contains("new question"));
        assertEquals("next", ChatHistory.build(history, "next", 0));
    }
}
