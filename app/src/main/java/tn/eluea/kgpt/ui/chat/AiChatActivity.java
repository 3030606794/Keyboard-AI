package tn.eluea.kgpt.ui.chat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.listener.GenerativeAIListener;
import tn.eluea.kgpt.llm.ConversationMemoryStore;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.llm.SimpleAIController;
import tn.eluea.kgpt.ui.main.MainActivity;
import tn.eluea.kgpt.util.LocaleHelper;

/**
 * In-app AI chat page (Scheme 1).
 * Acts as a quick console to test the current provider/model configuration.
 */
public class AiChatActivity extends AppCompatActivity implements GenerativeAIListener {

    /** Used by the floating screenshot bubble to preload an image into pending attachments. */
    public static final String EXTRA_PRELOAD_IMAGE_URI = "kgpt_preload_image_uri";

    private static final String PREFS_NAME = "kgpt_ai_chat";
    private static final String KEY_SESSIONS_JSON = "sessions_json";
    private static final String KEY_ACTIVE_SESSION_ID = "active_session_id";
    // Legacy single-session key (v4-v6). We migrate it into the first session if present.
    private static final String KEY_HISTORY_JSON_LEGACY = "history_json";

    private static final int MAX_SAVED_SESSIONS = 20;
    private static final int MAX_SAVED_MESSAGES_PER_SESSION = 200;
    // Attachment limits (app-side). These are intentionally generous.
    // Note: The upstream provider may still reject oversized requests.
    // Per image (single). If you attach multiple images, each image is validated against this limit.
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024; // 20MB per image
    // Per file (text import limit). We only try to read text-like files.
    private static final int MAX_FILE_BYTES = 50 * 1024 * 1024;  // 50MB
    private static final int MAX_FILE_TEXT_CHARS = 2_000_000;
    // For UI preview only (small slice)
    private static final int MAX_FILE_PREVIEW_BYTES = 256 * 1024; // 256KB

    private static final Pattern LEADING_ASSISTANT_LABEL =
            Pattern.compile("(?is)^\\s*(assistant|\\u52a9\\u624b)\\s*[:：]\\s*");

    private MaterialToolbar toolbar;
    private TextView tvCurrent;
    private TextView tvMemory;
    private TextView tvWarning;

    private RecyclerView rv;
    private RecyclerView rvPending;
    private EditText etInput;
    private ImageButton btnPlus;
    private MaterialButton btnSend;

    private ChatMessagesAdapter adapter;
    private List<ChatMessage> messages = new ArrayList<>();

    private final List<PendingAttachment> pendingAttachments = new ArrayList<>();
    private PendingAttachmentsAdapter pendingAdapter;
    private ItemTouchHelper pendingTouchHelper;

    private final List<ChatSession> sessions = new ArrayList<>();
    private ChatSession activeSession;

    private SimpleAIController controller;

    private boolean isGenerating = false;
    private int generationVersion;
    private String generatingSessionId;

    private void stopGeneration() {
        generationVersion++;
        if (controller != null) controller.cancelRequest();
        isGenerating = false;
        generatingSessionId = null;
        streamingAssistantIndex = -1;
        updateConfigWarning();
        saveSessions();
    }

    private boolean ownsGeneration() {
        return isGenerating && activeSession != null && activeSession.id.equals(generatingSessionId);
    }

    private int streamingAssistantIndex = -1;

    // Attachment pickers
    private ActivityResultLauncher<Intent> pickImageLauncher;
    private ActivityResultLauncher<Intent> pickFileLauncher;
    private ActivityResultLauncher<Intent> sessionsLauncher;
    // (reserved)

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge + make keyboard resize behavior reliable
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        setContentView(R.layout.activity_ai_chat);

        // Ensure SP is ready (MainActivity usually does this, but keep it safe)
        try { SPManager.init(this); } catch (Throwable ignored) {}

        bindViews();
        setupToolbar();
        applyWindowInsets();
        setupPickers();

        restoreSessions();
        setupRecycler();

        // Preload image attachment (if launched by floating screenshot bubble)
        handlePreloadedAttachment(getIntent());

        controller = new SimpleAIController();
        controller.addListener(this);

        updateHeader();
        updateConfigWarning();

        if (tvWarning != null) {
            tvWarning.setOnClickListener(v -> openAiInvocationSettings());
        }

        btnSend.setOnClickListener(v -> { if (isGenerating) stopGeneration(); else sendCurrent(); });
        btnPlus.setOnClickListener(v -> showPlusMenu(v));

        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrent();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePreloadedAttachment(intent);
    }

    /**
     * If launched with EXTRA_PRELOAD_IMAGE_URI, add it to pending attachments.
     * This is used by the floating "Screenshot → Ask AI" bubble.
     */
    private void handlePreloadedAttachment(Intent intent) {
        if (intent == null) return;
        if (isGenerating) return;

        String uriStr = intent.getStringExtra(EXTRA_PRELOAD_IMAGE_URI);
        if (uriStr == null || uriStr.trim().isEmpty()) return;

        // Prevent repeated preload when activity is recreated or receives same intent.
        intent.removeExtra(EXTRA_PRELOAD_IMAGE_URI);

        try {
            Uri uri = Uri.parse(uriStr);
            String name = getDisplayName(uri);
            if (name == null || name.trim().isEmpty()) name = "screenshot";
            String mime = null;
            try { mime = getContentResolver().getType(uri); } catch (Throwable ignored) {}
            long size = getSizeBytes(uri);

            pendingAttachments.add(new PendingAttachment(
                    PendingAttachment.Kind.IMAGE,
                    uri.toString(),
                    mime,
                    name,
                    size,
                    null,
                    ""
            ));

            if (pendingAdapter != null) pendingAdapter.notifyDataSetChanged();
            updatePendingVisibility();

            if (etInput != null) {
                etInput.requestFocus();
                showKeyboard(etInput);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHeader();
        updateConfigWarning();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.cancelRequest();
        generationVersion++;
        super.onDestroy();
        try {
            if (controller != null) controller.removeListener(this);
        } catch (Throwable ignored) {}
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar_ai_chat);
        tvCurrent = findViewById(R.id.tv_ai_chat_current);
        tvMemory = findViewById(R.id.tv_ai_chat_memory);
        tvWarning = findViewById(R.id.tv_ai_chat_warning);

        rv = findViewById(R.id.rv_ai_chat);
        rvPending = findViewById(R.id.rv_ai_chat_pending);
        etInput = findViewById(R.id.et_ai_chat_input);
        btnPlus = findViewById(R.id.btn_ai_chat_plus);
        btnSend = findViewById(R.id.btn_ai_chat_send);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    /**
     * Fix toolbar/status-bar overlap and make the input bar follow the IME (keyboard).
     */
    private void applyWindowInsets() {
        // 1) Toolbar: add status bar height as top padding
        if (toolbar != null) {
            final int pl = toolbar.getPaddingLeft();
            final int pt = toolbar.getPaddingTop();
            final int pr = toolbar.getPaddingRight();
            final int pb = toolbar.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(pl, pt + top, pr, pb);
                return insets;
            });
            ViewCompat.requestApplyInsets(toolbar);
        }

        // 2) Content: add bottom padding for nav bars / IME so the input box stays visible
        View content = findViewById(R.id.content_ai_chat);
        if (content != null) {
            final int pl = content.getPaddingLeft();
            final int pt = content.getPaddingTop();
            final int pr = content.getPaddingRight();
            final int pb = content.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                int navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                int bottom = Math.max(navBottom, imeBottom);
                v.setPadding(pl, pt, pr, pb + bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(content);
        }
    }

    private void setupRecycler() {
        adapter = new ChatMessagesAdapter(messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        rv.setAdapter(adapter);
        scrollToBottom();

        // Pending attachments (v12: large preview + details + drag reorder + editable captions)
        pendingAdapter = new PendingAttachmentsAdapter(pendingAttachments, new PendingAttachmentsAdapter.Listener() {
            @Override
            public void onRemove(int position) {
                if (position < 0 || position >= pendingAttachments.size()) return;
                pendingAttachments.remove(position);
                pendingAdapter.notifyItemRemoved(position);
                updatePendingVisibility();
            }

            @Override
            public void onOpen(int position) {
                if (position < 0 || position >= pendingAttachments.size()) return;
                openAttachmentUriSafe(Uri.parse(pendingAttachments.get(position).uri), pendingAttachments.get(position).mime);
            }

            @Override
            public void onStartDrag(RecyclerView.ViewHolder holder) {
                try {
                    if (pendingTouchHelper != null) pendingTouchHelper.startDrag(holder);
                } catch (Throwable ignored) {
                }
            }
        });
        LinearLayoutManager vm = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        rvPending.setLayoutManager(vm);
        rvPending.setAdapter(pendingAdapter);

        // Drag-to-reorder
        ItemTouchHelper.SimpleCallback cb = new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return pendingAdapter.onItemMove(viewHolder.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // no-op
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false; // use handle
            }
        };
        pendingTouchHelper = new ItemTouchHelper(cb);
        pendingTouchHelper.attachToRecyclerView(rvPending);
        updatePendingVisibility();
    }

    private void setupPickers() {
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result == null || result.getData() == null) return;
                    if (result.getResultCode() != RESULT_OK) return;
                    List<Uri> uris = collectUrisFromResult(result.getData());
                    if (uris.isEmpty()) return;
                    for (Uri u : uris) tryTakePersistableRead(u);
                    handlePickedImages(uris);
                }
        );

        pickFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result == null || result.getData() == null) return;
                    if (result.getResultCode() != RESULT_OK) return;
                    List<Uri> uris = collectUrisFromResult(result.getData());
                    if (uris.isEmpty()) return;
                    for (Uri u : uris) tryTakePersistableRead(u);
                    handlePickedFiles(uris);
                }
        );

        sessionsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result == null || result.getData() == null) return;
                    if (result.getResultCode() != RESULT_OK) return;
                    String id = result.getData().getStringExtra(ChatSessionsActivity.EXTRA_SELECTED_SESSION_ID);
                    if (id == null || id.trim().isEmpty()) return;
                    restoreSessions();
                    for (ChatSession s : sessions) {
                        if (id.equals(s.id)) {
                            // Context is rebuilt from this session; no global memory to clear.
                            setActiveSession(s, true);
                            break;
                        }
                    }
                }
        );
    }

    private void tryTakePersistableRead(Uri uri) {
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Throwable ignored) {
        }
    }

    private void updateHeader() {
        String modelText = "";
        try {
            if (SPManager.isReady()) {
                SPManager sp = SPManager.getInstance();
                LanguageModel model = sp.hasLanguageModel() ? sp.getLanguageModel() : LanguageModel.Gemini;
                String sub = sp.getLanguageModelField(model, LanguageModelField.SubModel);
                if (sub == null) sub = "";
                sub = sub.trim();
                modelText = model.label + (sub.isEmpty() ? "" : (" / " + sub));

                int mem = 0;
                try { mem = sp.getConversationMemoryLevel(); } catch (Throwable ignored) {}
                if (mem > 0) {
                    tvMemory.setText(getString(R.string.ui_ai_chat_memory_on_fmt, mem));
                } else {
                    tvMemory.setText(R.string.ui_ai_chat_memory_off);
                }
            }
        } catch (Throwable ignored) {}

        if (TextUtils.isEmpty(modelText)) {
            tvCurrent.setText(getString(R.string.ui_ai_chat_current_fmt, "-"));
        } else {
            tvCurrent.setText(getString(R.string.ui_ai_chat_current_fmt, modelText));
        }
    }

    private void updateConfigWarning() {
        boolean notReady;
        try {
            notReady = (controller == null) || controller.needModelClient() || controller.needApiKey();
        } catch (Throwable ignored) {
            notReady = true;
        }

        tvWarning.setVisibility(notReady ? View.VISIBLE : View.GONE);
        btnSend.setEnabled(isGenerating || !notReady);
        btnSend.setText(isGenerating ? R.string.phase1_stop : R.string.phase1_send);
        btnSend.setContentDescription(getString(isGenerating ? R.string.phase1_stop : R.string.phase1_send));
        // Keep the input enabled even while generating so the keyboard doesn't collapse.
        etInput.setEnabled(!notReady);
        btnPlus.setEnabled(!notReady && !isGenerating);
    }

    private void updatePendingVisibility() {
        if (rvPending == null) return;
        rvPending.setVisibility(pendingAttachments.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openAttachmentUriSafe(Uri uri, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (mime != null && !mime.trim().isEmpty()) {
                i.setDataAndType(uri, mime);
            } else {
                i.setData(uri);
            }
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private List<Uri> collectUrisFromResult(Intent data) {
        List<Uri> uris = new ArrayList<>();
        try {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            }
        } catch (Throwable ignored) {}
        try {
            Uri u = data.getData();
            if (u != null) uris.add(u);
        } catch (Throwable ignored) {}
        return uris;
    }

    private void touchActiveSession() {
        try {
            if (activeSession != null) activeSession.touch();
        } catch (Throwable ignored) {}
    }

    private void openAiInvocationSettings() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_AI_INVOCATION, true);
        intent.putExtra(MainActivity.EXTRA_AI_INVOCATION_TAB, 0);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void sendCurrent() {
        if (isGenerating) return;
        String prompt = etInput.getText() == null ? "" : etInput.getText().toString();
        if (prompt == null) prompt = "";
        prompt = prompt.trim();

        if (prompt.isEmpty() && pendingAttachments.isEmpty()) return;

        // If not configured, jump to settings
        if (controller == null || controller.needModelClient() || controller.needApiKey()) {
            Toast.makeText(this, R.string.ui_ai_chat_not_configured, Toast.LENGTH_LONG).show();
            openAiInvocationSettings();
            return;
        }

        // Determine the effective prompt.
        final boolean hasImages = hasPendingImages();
        final boolean hasFiles = hasPendingFiles();

        String effectivePrompt = prompt;
        // v13: per-attachment note fields were removed (focus stealing). If user didn't type anything,
        // use a sensible default for attachments.
        if (TextUtils.isEmpty(effectivePrompt) && !pendingAttachments.isEmpty()) {
            if (hasImages && !hasFiles) {
                effectivePrompt = getString(R.string.ui_ai_chat_default_image_prompt);
            } else if (!hasImages && hasFiles) {
                effectivePrompt = getString(R.string.ui_ai_chat_default_file_prompt);
            } else {
                effectivePrompt = getString(R.string.ui_ai_chat_default_attachment_prompt);
            }
        }

        // Freeze values that will be captured by background lambdas.
        final String effectivePromptFinal = effectivePrompt;
        int memoryTurns = SPManager.getInstance().getConversationMemoryLevel();
        final String sessionPrompt = ChatHistory.build(messages, effectivePrompt, memoryTurns);
        final int requestVersion = ++generationVersion;
        generatingSessionId = activeSession.id;

        // Clear input AFTER we capture it, but keep keyboard.
        etInput.setText("");
        etInput.requestFocus();
        showKeyboard(etInput);

        // Snapshot pending attachments (so we can clear UI but still build request)
        final List<PendingAttachment> pendingSnapshot = new ArrayList<>(pendingAttachments);

        // 1) user message(s)
        if (pendingSnapshot.isEmpty()) {
            messages.add(new ChatMessage(ChatMessage.Role.USER, effectivePrompt));
            adapter.notifyItemInserted(messages.size() - 1);
        } else {
            boolean first = true;
            for (PendingAttachment a : pendingSnapshot) {
                String cap = first ? effectivePrompt : "";
                first = false;

                if (a.kind == PendingAttachment.Kind.IMAGE) {
                    messages.add(ChatMessage.image(ChatMessage.Role.USER, cap, a.uri, a.mime, a.safeName()));
                } else {
                    // short preview already stored for UI
                    String pv = a.preview;
                    if (pv == null || pv.trim().isEmpty()) pv = getString(R.string.ui_ai_chat_file_no_preview);
                    messages.add(ChatMessage.file(ChatMessage.Role.USER, cap, a.uri, a.mime, a.safeName(), pv));
                }
                adapter.notifyItemInserted(messages.size() - 1);
            }
        }

        // 2) assistant placeholder (streaming)
        ChatMessage placeholder = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
        placeholder.setComplete(false);
        messages.add(placeholder);
        streamingAssistantIndex = messages.size() - 1;
        adapter.notifyItemInserted(streamingAssistantIndex);
        scrollToBottom();

        isGenerating = true;
        updateConfigWarning();

        touchActiveSession();

        boolean useMemory = false;
        try {
            if (SPManager.isReady()) {
                useMemory = SPManager.getInstance().getConversationMemoryLevel() > 0;
            }
        } catch (Throwable ignored) {}

        final boolean useMemoryFinal = useMemory;

        // Begin diagnostics capture for in-app chat requests (helps exported logs).
        try {
            String meta = "CHAT provider=" + safe(controller.getProviderNameSafe())
                    + " subModel=" + safe(controller.getSubModelNameSafe())
                    + " images=" + countPendingKind(pendingSnapshot, PendingAttachment.Kind.IMAGE)
                    + " files=" + countPendingKind(pendingSnapshot, PendingAttachment.Kind.FILE);
            tn.eluea.kgpt.util.AiDiagnostics.beginNewRequest(meta);
            tn.eluea.kgpt.util.AiDiagnostics.append("CHAT_SEND", meta);
        } catch (Throwable ignored) {}

        // Build request + encode attachments off the UI thread (base64 for screenshots can be heavy).
        new Thread(() -> {
            try {
                String requestPrompt = buildPromptWithAttachments(sessionPrompt, pendingSnapshot);
                List<String> dataUris = hasImages ? buildImageDataUris(pendingSnapshot) : java.util.Collections.emptyList();
                runOnUiThread(() -> {
                    if (requestVersion != generationVersion || !ownsGeneration() || isFinishing()) return;
                    if (hasImages && (dataUris == null || dataUris.isEmpty())) {
                        onAIError(new IllegalStateException(getString(R.string.ui_ai_chat_image_read_failed)));
                        return;
                    }
                    if (hasImages) controller.generateResponseWithImageDataUris(requestPrompt, dataUris, false);
                    else controller.generateResponse(requestPrompt, null, null, false);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (requestVersion == generationVersion && ownsGeneration()) onAIError(error);
                });
            }
        }, "KGPT-ChatAttachments").start();

        // clear pending UI once we have started the request
        if (!pendingAttachments.isEmpty()) {
            pendingAttachments.clear();
            pendingAdapter.notifyDataSetChanged();
            updatePendingVisibility();
        }

        updateActiveSessionTitleIfNeeded(effectivePromptFinal);
    }

    private boolean hasPendingImages() {
        for (PendingAttachment a : pendingAttachments) {
            if (a.kind == PendingAttachment.Kind.IMAGE) return true;
        }
        return false;
    }

    private boolean hasPendingFiles() {
        for (PendingAttachment a : pendingAttachments) {
            if (a.kind == PendingAttachment.Kind.FILE) return true;
        }
        return false;
    }

    private String buildPromptWithAttachments(String basePrompt, List<PendingAttachment> snapshot) {
        StringBuilder sb = new StringBuilder(basePrompt == null ? "" : basePrompt);

        int imgIdx = 1;
        int fileIdx = 1;
        for (PendingAttachment a : snapshot) {
            if (a == null) continue;

            if (a.kind == PendingAttachment.Kind.IMAGE) {
                sb.append("\n\n[Image ").append(imgIdx).append(": ").append(a.safeName()).append("]");
                imgIdx++;
                continue;
            }

            if (a.kind == PendingAttachment.Kind.FILE) {
                Uri uri = Uri.parse(a.uri);
                String name = a.safeName();
                sb.append("\n\n[File ").append(fileIdx).append(": ").append(name).append("]");

                String fileText = readFileAsText(uri);
                if (fileText != null) {
                    sb.append("\n").append(fileText);
                }
                fileIdx++;
            }
        }
        return sb.toString();
    }

    private List<String> buildImageDataUris(List<PendingAttachment> snapshot) {
        List<String> list = new ArrayList<>();
        for (PendingAttachment a : snapshot) {
            if (a.kind != PendingAttachment.Kind.IMAGE) continue;
            String dataUri = readImageAsDataUri(Uri.parse(a.uri));
            if (dataUri != null) list.add(dataUri);
        }
        return list;
    }

    private int countPendingKind(List<PendingAttachment> snapshot, PendingAttachment.Kind kind) {
        if (snapshot == null) return 0;
        int c = 0;
        for (PendingAttachment a : snapshot) {
            if (a != null && a.kind == kind) c++;
        }
        return c;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private void showKeyboard(View target) {
        try {
            target.post(() -> {
                try {
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void scrollToBottom() {
        try {
            rv.post(() -> {
                if (adapter != null && adapter.getItemCount() > 0) {
                    rv.scrollToPosition(adapter.getItemCount() - 1);
                }
            });
        } catch (Throwable ignored) {}
    }

    private String stripLeadingAssistantLabel(String s) {
        if (s == null || s.isEmpty()) return s;
        try {
            return LEADING_ASSISTANT_LABEL.matcher(s).replaceFirst("");
        } catch (Throwable ignored) {
            return s;
        }
    }

    // ---- Sessions ----

    private void restoreSessions() {
        sessions.clear();

        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String sessionsJson = sp.getString(KEY_SESSIONS_JSON, null);

        try {
            if (sessionsJson != null && !sessionsJson.trim().isEmpty()) {
                JSONArray arr = new JSONArray(sessionsJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    ChatSession s = ChatSession.fromJson(o);
                    if (s == null || s.id == null || s.id.trim().isEmpty()) continue;
                    // Sanitize assistant labels on load
                    for (ChatMessage m : s.messages) {
                        if (m.getRole() == ChatMessage.Role.ASSISTANT) {
                            m.setText(stripLeadingAssistantLabel(m.getText()));
                        }
                    }
                    if (s.title == null) s.title = "";
                    if (s.title.trim().isEmpty()) s.title = getString(R.string.ui_ai_chat_new_session_title);
                    sessions.add(s);
                }
            }
        } catch (Throwable ignored) {
        }

        // Legacy migration (single history -> first session)
        if (sessions.isEmpty()) {
            try {
                String legacy = sp.getString(KEY_HISTORY_JSON_LEGACY, null);
                if (legacy != null && !legacy.trim().isEmpty()) {
                    ChatSession s = new ChatSession(UUID.randomUUID().toString(), System.currentTimeMillis(), getString(R.string.ui_ai_chat_new_session_title));
                    JSONArray arr = new JSONArray(legacy);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        String role = o.optString("role", "");
                        String text = o.optString("text", "");
                        ChatMessage.Role r;
                        try { r = ChatMessage.Role.valueOf(role); } catch (Throwable ignored2) { r = ChatMessage.Role.ASSISTANT; }
                        if (r == ChatMessage.Role.ASSISTANT) text = stripLeadingAssistantLabel(text);
                        s.messages.add(new ChatMessage(r, text));
                    }
                    sessions.add(s);
                    sp.edit().remove(KEY_HISTORY_JSON_LEGACY).apply();
                }
            } catch (Throwable ignored) {
            }
        }

        if (sessions.isEmpty()) {
            sessions.add(new ChatSession(UUID.randomUUID().toString(), System.currentTimeMillis(), getString(R.string.ui_ai_chat_new_session_title)));
        }

        // Sort by updated time (newest first)
        try {
            Collections.sort(sessions, new Comparator<ChatSession>() {
                @Override
                public int compare(ChatSession a, ChatSession b) {
                    long ta = a.updatedAt > 0 ? a.updatedAt : a.createdAt;
                    long tb = b.updatedAt > 0 ? b.updatedAt : b.createdAt;
                    return Long.compare(tb, ta);
                }
            });
        } catch (Throwable ignored) {
        }

        String activeId = sp.getString(KEY_ACTIVE_SESSION_ID, null);
        ChatSession found = null;
        if (activeId != null) {
            for (ChatSession s : sessions) {
                if (activeId.equals(s.id)) {
                    found = s;
                    break;
                }
            }
        }
        if (found == null) found = sessions.get(0);
        setActiveSession(found, false);
    }

    private void saveSessions() {
        try {
            // Keep newest first
            try {
                Collections.sort(sessions, new Comparator<ChatSession>() {
                    @Override
                    public int compare(ChatSession a, ChatSession b) {
                        long ta = a.updatedAt > 0 ? a.updatedAt : a.createdAt;
                        long tb = b.updatedAt > 0 ? b.updatedAt : b.createdAt;
                        return Long.compare(tb, ta);
                    }
                });
            } catch (Throwable ignored) {
            }

            // Cap sessions
            while (sessions.size() > MAX_SAVED_SESSIONS) {
                sessions.remove(sessions.size() - 1);
            }

            JSONArray arr = new JSONArray();
            for (ChatSession s : sessions) {
                if (s == null) continue;
                if (s.title == null || s.title.trim().isEmpty()) {
                    s.title = getString(R.string.ui_ai_chat_new_session_title);
                }
                arr.put(s.toJson(MAX_SAVED_MESSAGES_PER_SESSION));
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SESSIONS_JSON, arr.toString())
                    .putString(KEY_ACTIVE_SESSION_ID, activeSession == null ? "" : activeSession.id)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void setActiveSession(ChatSession s, boolean saveNow) {
        if (s == null) return;
        if (isGenerating) stopGeneration();
        generationVersion++;
        activeSession = s;
        messages = s.messages;
        if (adapter != null) {
            adapter.setItems(messages);
        }
        streamingAssistantIndex = -1;
        isGenerating = false;
        updateConfigWarning();
        scrollToBottom();
        if (saveNow) saveSessions();
    }

    private void updateActiveSessionTitleIfNeeded(String firstUserPrompt) {
        try {
            if (activeSession == null) return;
            String title = activeSession.title == null ? "" : activeSession.title.trim();
            String def = getString(R.string.ui_ai_chat_new_session_title);
            if (!title.isEmpty() && !title.equals(def)) return;
            if (firstUserPrompt == null) return;
            String t = firstUserPrompt.trim();
            if (t.isEmpty()) return;
            if (t.length() > 14) t = t.substring(0, 14) + "…";
            activeSession.title = t;
        } catch (Throwable ignored) {
        }
    }

    private void newChat() {
        if (isGenerating) return;
        ChatSession s = new ChatSession(UUID.randomUUID().toString(), System.currentTimeMillis(), getString(R.string.ui_ai_chat_new_session_title));
        s.touch();
        sessions.add(0, s);

        setActiveSession(s, true);
    }

    private void openSessionsList() {
        try {
            Intent intent = new Intent(this, ChatSessionsActivity.class);
            sessionsLauncher.launch(intent);
        } catch (Throwable ignored) {
        }
    }

    // ---- Plus menu ----

    private void showPlusMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.inflate(R.menu.menu_ai_chat_plus);
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_ai_chat_send_image) {
                openImagePicker();
                return true;
            } else if (id == R.id.action_ai_chat_send_file) {
                openFilePicker();
                return true;
            } else if (id == R.id.action_ai_chat_new_chat) {
                newChat();
                return true;
            } else if (id == R.id.action_ai_chat_switch_chat) {
                openSessionsList();
                return true;
            }
            return false;
        });
        pm.show();
    }

    private void openImagePicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            pickImageLauncher.launch(i);
        } catch (Throwable ignored) {
        }
    }

    private void openFilePicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            pickFileLauncher.launch(i);
        } catch (Throwable ignored) {
        }
    }

    private void persistReadPermission(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Copy a picked image Uri into app cache and return a FileProvider Uri.
     *
     * Why: Some ROMs/providers return Document Uris whose temporary read grants may expire
     * after process death or app restart. Using ImageView#setImageURI on such stale Uris can
     * crash the app (SecurityException during layout).
     */
    private Uri copyImageToCache(Uri src, String nameHint, String mimeHint, long[] outSize) {
        if (src == null) return null;
        try {
            File dir = new File(getCacheDir(), "picked_images");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }

            String ext = guessImageExt(nameHint, mimeHint);
            String base = "img_" + System.currentTimeMillis();
            File out = new File(dir, base + ext);

            try (InputStream is = getContentResolver().openInputStream(src);
                 OutputStream os = new FileOutputStream(out)) {
                if (is == null) return null;
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }

            if (outSize != null && outSize.length > 0) {
                outSize[0] = out.length();
            }

            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
        } catch (Throwable t) {
            try { tn.eluea.kgpt.util.Logger.error("copyImageToCache failed: " + t); } catch (Throwable ignored) {}
            return null;
        }
    }

    private static String guessImageExt(String nameHint, String mimeHint) {
        try {
            if (nameHint != null) {
                String n = nameHint.toLowerCase();
                if (n.endsWith(".png")) return ".png";
                if (n.endsWith(".webp")) return ".webp";
                if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return ".jpg";
            }
            if (mimeHint != null) {
                String m = mimeHint.toLowerCase();
                if (m.contains("png")) return ".png";
                if (m.contains("webp")) return ".webp";
            }
        } catch (Throwable ignored) {}
        return ".jpg";
    }

    private void handlePickedImages(List<Uri> uris) {
        if (isGenerating) return;
        if (uris == null || uris.isEmpty()) return;

        for (Uri uri : uris) {
            persistReadPermission(uri);
            String name = getDisplayName(uri);
            if (name == null || name.trim().isEmpty()) name = "image";
            String mime = null;
            try { mime = getContentResolver().getType(uri); } catch (Throwable ignored) {}

            // Copy to app cache so the Uri stays readable across restarts.
            long[] copiedSize = new long[]{-1};
            Uri local = copyImageToCache(uri, name, mime, copiedSize);
            Uri effective = local != null ? local : uri;
            long size = copiedSize[0] > 0 ? copiedSize[0] : getSizeBytes(effective);

            pendingAttachments.add(new PendingAttachment(PendingAttachment.Kind.IMAGE, effective.toString(), mime, name, size, null, ""));
        }
        pendingAdapter.notifyDataSetChanged();
        updatePendingVisibility();
        etInput.requestFocus();
        showKeyboard(etInput);
    }

    private void handlePickedFiles(List<Uri> uris) {
        if (isGenerating) return;
        if (uris == null || uris.isEmpty()) return;

        for (Uri uri : uris) {
            persistReadPermission(uri);
            String name = getDisplayName(uri);
            if (name == null || name.trim().isEmpty()) name = "file";
            String mime = null;
            try { mime = getContentResolver().getType(uri); } catch (Throwable ignored) {}
            long size = getSizeBytes(uri);

            String preview = readFilePreviewAsText(uri);
            if (preview == null || preview.trim().isEmpty()) preview = getString(R.string.ui_ai_chat_file_no_preview);
            preview = preview.trim();
            if (preview.length() > 200) preview = preview.substring(0, 200) + "…";

            pendingAttachments.add(new PendingAttachment(PendingAttachment.Kind.FILE, uri.toString(), mime, name, size, preview, ""));
        }
        pendingAdapter.notifyDataSetChanged();
        updatePendingVisibility();
        etInput.requestFocus();
        showKeyboard(etInput);
    }

    private String getDisplayName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) {
                        return c.getString(idx);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private long getSizeBytes(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (idx >= 0 && c.moveToFirst()) {
                        return c.getLong(idx);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private String readImageAsDataUri(Uri uri) {
        try {
            // Best-effort MIME detection (FileProvider may return null on some ROMs)
            String mime = null;
            try { mime = getContentResolver().getType(uri); } catch (Throwable ignored) {}
            if (mime == null || mime.trim().isEmpty()) {
                String name = null;
                try { name = getDisplayName(uri); } catch (Throwable ignored) {}
                if (name != null) {
                    int dot = name.lastIndexOf('.');
                    if (dot >= 0 && dot < name.length() - 1) {
                        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
                        try {
                            String guessed = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                            if (guessed != null && !guessed.trim().isEmpty()) mime = guessed;
                        } catch (Throwable ignored) {}
                    }
                }
            }
            if (mime == null || mime.trim().isEmpty()) {
                // Default to PNG for screenshots (safer than labeling PNG bytes as JPEG).
                mime = "image/png";
            }

            // Downscale + recompress large images to keep requests fast and within provider limits.
            // This runs on a background thread (sendCurrent offloads), so it won't block UI.
            byte[] encoded = null;
            String outMime = mime;

            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is != null) android.graphics.BitmapFactory.decodeStream(is, null, o);
                }

                int w = o.outWidth;
                int h = o.outHeight;
                int maxDim = Math.max(w, h);
                int targetMax = 1600;

                boolean shouldRecompress = false;
                if (maxDim > targetMax) shouldRecompress = true;

                // Also recompress PNG screenshots (often huge) into JPEG unless the image has alpha.
                if ("image/png".equalsIgnoreCase(mime)) shouldRecompress = true;

                if (shouldRecompress && w > 0 && h > 0) {
                    int inSample = 1;
                    while ((maxDim / inSample) > targetMax) inSample *= 2;

                    android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                    o2.inSampleSize = Math.max(1, inSample);
                    o2.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;

                    android.graphics.Bitmap bmp;
                    try (java.io.InputStream is2 = getContentResolver().openInputStream(uri)) {
                        bmp = (is2 == null) ? null : android.graphics.BitmapFactory.decodeStream(is2, null, o2);
                    }

                    if (bmp != null) {
                        // If it has alpha, keep PNG; otherwise JPEG is smaller.
                        boolean hasAlpha = false;
                        try { hasAlpha = bmp.hasAlpha(); } catch (Throwable ignored) {}
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        if (hasAlpha) {
                            outMime = "image/png";
                            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos);
                        } else {
                            outMime = "image/jpeg";
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos);
                        }
                        encoded = baos.toByteArray();
                        try { bmp.recycle(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            if (encoded == null) {
                boolean[] tooLarge = new boolean[]{false};
                byte[] bytes = readBytesWithLimit(uri, MAX_IMAGE_BYTES, tooLarge);
                if (bytes == null) {
                    if (tooLarge[0]) {
                        Toast.makeText(this, R.string.ui_ai_chat_image_too_large, Toast.LENGTH_SHORT).show();
                        try { tn.eluea.kgpt.util.AiDiagnostics.append("CHAT_IMG", "too_large uri=" + uri); } catch (Throwable ignored) {}
                    } else {
                        Toast.makeText(this, R.string.ui_ai_chat_image_read_failed, Toast.LENGTH_SHORT).show();
                        try { tn.eluea.kgpt.util.AiDiagnostics.append("CHAT_IMG", "read_failed uri=" + uri); } catch (Throwable ignored) {}
                    }
                    return null;
                }
                encoded = bytes;
                outMime = mime;
            }

            String b64 = android.util.Base64.encodeToString(encoded, android.util.Base64.NO_WRAP);
            return "data:" + outMime + ";base64," + b64;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readFileAsText(Uri uri) {
        try {
            String mime = null;
            try { mime = getContentResolver().getType(uri); } catch (Throwable ignored) {}
            boolean likelyText = mime == null
                    || mime.startsWith("text/")
                    || mime.contains("json")
                    || mime.contains("xml")
                    || mime.contains("yaml")
                    || mime.contains("csv")
                    || mime.contains("markdown")
                    || mime.contains("javascript")
                    || mime.contains("java")
                    || mime.contains("kotlin")
                    || mime.contains("python");

            if (!likelyText) {
                // We don't try to parse binary formats.
                return null;
            }

            boolean[] truncated = new boolean[]{false};
            byte[] bytes = readBytesWithSoftLimit(uri, MAX_FILE_BYTES, truncated);
            if (bytes == null) return null;

            String text = new String(bytes);
            if (truncated[0]) {
                text = text + "\n\n" + getString(R.string.ui_ai_chat_truncated_fmt, (MAX_FILE_BYTES / (1024 * 1024)));
            }
            if (text.length() > MAX_FILE_TEXT_CHARS) {
                text = text.substring(0, MAX_FILE_TEXT_CHARS) + "\n...";
            }
            return text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readFilePreviewAsText(Uri uri) {
        try {
            String mime = null;
            try { mime = getContentResolver().getType(uri); } catch (Throwable ignored) {}
            boolean likelyText = mime == null
                    || mime.startsWith("text/")
                    || mime.contains("json")
                    || mime.contains("xml")
                    || mime.contains("yaml")
                    || mime.contains("csv")
                    || mime.contains("markdown")
                    || mime.contains("javascript")
                    || mime.contains("java")
                    || mime.contains("kotlin")
                    || mime.contains("python");

            if (!likelyText) return null;

            boolean[] truncated = new boolean[]{false};
            byte[] bytes = readBytesWithSoftLimit(uri, MAX_FILE_PREVIEW_BYTES, truncated);
            if (bytes == null) return null;

            String text = new String(bytes);
            if (text.length() > 20_000) {
                text = text.substring(0, 20_000);
            }
            return text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private byte[] readBytesWithSoftLimit(Uri uri, int maxBytes, boolean[] truncatedOut) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                if (total + n > maxBytes) {
                    int remain = maxBytes - total;
                    if (remain > 0) bos.write(buf, 0, remain);
                    if (truncatedOut != null && truncatedOut.length > 0) truncatedOut[0] = true;
                    break;
                }
                bos.write(buf, 0, n);
                total += n;
            }
            return bos.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private byte[] readBytesWithLimit(Uri uri, int maxBytes) {
        return readBytesWithLimit(uri, maxBytes, null);
    }

    /** Hard limit read; returns null on error or too-large. If tooLargeOut[0]=true then it exceeded maxBytes. */
    private byte[] readBytesWithLimit(Uri uri, int maxBytes, boolean[] tooLargeOut) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                if (total + n > maxBytes) {
                    if (tooLargeOut != null && tooLargeOut.length > 0) tooLargeOut[0] = true;
                    return null;
                }
                bos.write(buf, 0, n);
                total += n;
            }
            return bos.toByteArray();
        } catch (SecurityException se) {
            try { tn.eluea.kgpt.util.AiDiagnostics.append("CHAT_IO", "security_exception uri=" + uri + " msg=" + se.getMessage()); } catch (Throwable ignored) {}
            return null;
        } catch (Throwable t) {
            try { tn.eluea.kgpt.util.AiDiagnostics.append("CHAT_IO", "read_error uri=" + uri + " msg=" + t.getMessage()); } catch (Throwable ignored) {}
            return null;
        }
    }

    private void clearChat() {
        if (isGenerating) return;
        messages.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        streamingAssistantIndex = -1;
        isGenerating = false;

        saveSessions();
        Toast.makeText(this, R.string.ui_ai_chat_cleared, Toast.LENGTH_SHORT).show();
        updateConfigWarning();
    }

    // ---- Options menu ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_ai_chat, menu);
        // Fix invisible white icon on light toolbar by forcing a theme-aware tint.
        try {
            MenuItem mi = menu.findItem(R.id.action_clear_chat);
            if (mi != null && mi.getIcon() != null) {
                int tint = MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface);
                mi.getIcon().setTint(tint);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_clear_chat) {
            clearChat();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSessions();
    }

    // ---- GenerativeAIListener ----

    @Override
    public void onAIPrepare() {
        // No-op (placeholder already added)
    }

    @Override
    public void onAINext(String chunk) {
        if (!ownsGeneration()) return;
        if (chunk == null || chunk.isEmpty()) return;
        if (streamingAssistantIndex < 0 || streamingAssistantIndex >= messages.size()) return;

        ChatMessage m = messages.get(streamingAssistantIndex);
        if (m.getText() == null || m.getText().isEmpty()) {
            chunk = stripLeadingAssistantLabel(chunk);
            if (chunk == null || chunk.isEmpty()) return;
        }

        m.append(chunk);
        adapter.notifyItemChanged(streamingAssistantIndex);
        scrollToBottom();
    }

    @Override
    public void onAIError(Throwable t) {
        if (!ownsGeneration()) return;
        isGenerating = false;
        updateConfigWarning();

        if (streamingAssistantIndex >= 0 && streamingAssistantIndex < messages.size()) {
            String msg = (t == null || t.getMessage() == null) ? "" : t.getMessage();
            if (msg == null) msg = "";
            if (messages.get(streamingAssistantIndex).getText().trim().isEmpty()) {
                messages.get(streamingAssistantIndex).setText(msg.isEmpty() ? "(Error)" : msg);
                adapter.notifyItemChanged(streamingAssistantIndex);
            }
        }

        touchActiveSession();
        saveSessions();
        Toast.makeText(this, (t != null && t.getMessage() != null) ? t.getMessage() : "Error", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onAIComplete() {
        if (!ownsGeneration()) return;
        if (streamingAssistantIndex >= 0 && streamingAssistantIndex < messages.size())
            messages.get(streamingAssistantIndex).setComplete(true);
        isGenerating = false;
        updateConfigWarning();
        scrollToBottom();
        touchActiveSession();
        saveSessions();
    }
}
