package tn.eluea.kgpt.ui.chat;

/**
 * A single message in the in-app chat.
 *
 * Supports TEXT/IMAGE/FILE kinds so the UI can render previews.
 */
public class ChatMessage {

    public enum Kind {
        TEXT,
        IMAGE,
        FILE
    }

    public enum Role {
        USER,
        ASSISTANT
    }

    private final Role role;
    private final Kind kind;

    /**
     * For TEXT: the content.
     * For IMAGE/FILE: optional caption / prompt.
     */
    private String text;
    private boolean complete = true;
    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }

    // Attachment fields (only for IMAGE/FILE)
    private String uri;      // content://...
    private String mime;     // image/*, text/plain, etc.
    private String name;     // display name
    private String preview;  // optional preview snippet (file)

    public ChatMessage(Role role, String text) {
        this(role, Kind.TEXT, text);
    }

    public ChatMessage(Role role, Kind kind, String text) {
        this.role = role == null ? Role.ASSISTANT : role;
        this.kind = kind == null ? Kind.TEXT : kind;
        this.text = text == null ? "" : text;
    }

    public static ChatMessage image(Role role, String caption, String uri, String mime, String name) {
        ChatMessage m = new ChatMessage(role, Kind.IMAGE, caption);
        m.uri = uri;
        m.mime = mime;
        m.name = name;
        return m;
    }

    public static ChatMessage file(Role role, String caption, String uri, String mime, String name, String preview) {
        ChatMessage m = new ChatMessage(role, Kind.FILE, caption);
        m.uri = uri;
        m.mime = mime;
        m.name = name;
        m.preview = preview;
        return m;
    }

    public Role getRole() {
        return role;
    }

    public Kind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text == null ? "" : text;
    }

    public void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        this.text = (this.text == null ? "" : this.text) + chunk;
    }

    public String getUri() {
        return uri;
    }

    public String getMime() {
        return mime;
    }

    public String getName() {
        return name;
    }

    public String getPreview() {
        return preview;
    }
}
