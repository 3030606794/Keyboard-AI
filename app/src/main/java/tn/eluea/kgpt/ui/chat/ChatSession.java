package tn.eluea.kgpt.ui.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A lightweight chat session container for the in-app "AI Chat" page.
 * Stored locally as JSON in SharedPreferences.
 */
public class ChatSession {

    public String id;
    public long createdAt;
    public long updatedAt;
    public String title;
    public final List<ChatMessage> messages = new ArrayList<>();

    public ChatSession(String id, long createdAt, String title) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.title = title;
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    public String getLastSummary() {
        if (messages.isEmpty()) return "";
        ChatMessage m = messages.get(messages.size() - 1);
        if (m == null) return "";
        String text = m.getText() == null ? "" : m.getText().trim();
        if (m.getKind() == ChatMessage.Kind.IMAGE) {
            String name = m.getName() == null ? "" : m.getName().trim();
            String base = "[图片]" + (name.isEmpty() ? "" : (" " + name));
            return text.isEmpty() ? base : (base + " · " + text);
        }
        if (m.getKind() == ChatMessage.Kind.FILE) {
            String name = m.getName() == null ? "" : m.getName().trim();
            String base = "[文件]" + (name.isEmpty() ? "" : (" " + name));
            return text.isEmpty() ? base : (base + " · " + text);
        }
        return text;
    }

    public JSONObject toJson(int maxMessages) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            o.put("title", title);
            JSONArray arr = new JSONArray();
            int start = Math.max(0, messages.size() - Math.max(1, maxMessages));
            for (int i = start; i < messages.size(); i++) {
                ChatMessage m = messages.get(i);
                JSONObject jm = new JSONObject();
                jm.put("role", m.getRole().name());
                jm.put("kind", m.getKind().name());
                jm.put("text", m.getText());
                jm.put("complete", m.isComplete());
                if (m.getKind() != ChatMessage.Kind.TEXT) {
                    jm.put("uri", m.getUri());
                    jm.put("mime", m.getMime());
                    jm.put("name", m.getName());
                    jm.put("preview", m.getPreview());
                }
                arr.put(jm);
            }
            o.put("messages", arr);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static ChatSession fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        long createdAt = o.optLong("createdAt", System.currentTimeMillis());
        long updatedAt = o.optLong("updatedAt", createdAt);
        String title = o.optString("title", "");
        ChatSession s = new ChatSession(id, createdAt, title);
        s.updatedAt = updatedAt;
        JSONArray arr = o.optJSONArray("messages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject jm = arr.optJSONObject(i);
                if (jm == null) continue;
                String role = jm.optString("role", "");
                String kind = jm.optString("kind", "TEXT");
                String text = jm.optString("text", "");
                ChatMessage.Role r;
                try {
                    r = ChatMessage.Role.valueOf(role);
                } catch (Throwable ignored) {
                    r = ChatMessage.Role.ASSISTANT;
                }

                ChatMessage.Kind k;
                try {
                    k = ChatMessage.Kind.valueOf(kind);
                } catch (Throwable ignored) {
                    k = ChatMessage.Kind.TEXT;
                }

                if (k == ChatMessage.Kind.IMAGE) {
                    s.messages.add(ChatMessage.image(r, text,
                            jm.optString("uri", null),
                            jm.optString("mime", null),
                            jm.optString("name", null)));
                } else if (k == ChatMessage.Kind.FILE) {
                    s.messages.add(ChatMessage.file(r, text,
                            jm.optString("uri", null),
                            jm.optString("mime", null),
                            jm.optString("name", null),
                            jm.optString("preview", null)));
                } else {
                    s.messages.add(new ChatMessage(r, text));
                }
                s.messages.get(s.messages.size() - 1).setComplete(jm.optBoolean("complete", true));
            }
        }
        return s;
    }
}
