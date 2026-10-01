package tn.eluea.kgpt.ui.lab;

import android.content.Intent;
import android.content.SharedPreferences;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import com.google.android.material.color.MaterialColors;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.RadioButton;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Toast;
import android.animation.ValueAnimator;
import android.animation.ObjectAnimator;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.ActivityResultLauncher;
import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;

import tn.eluea.kgpt.util.AiDiagnostics;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import tn.eluea.kgpt.R;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.ui.roles.RolesSettingsActivity;

import tn.eluea.kgpt.ui.main.MainActivity;

import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.llm.SubModelSuggestions;
import tn.eluea.kgpt.llm.ModelCapabilities;
import tn.eluea.kgpt.llm.client.LanguageModelClient;
import tn.eluea.kgpt.llm.client.ChatGPTClient;
import tn.eluea.kgpt.ui.UiInteractor;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.FrameLayout;
import android.text.TextWatcher;
import android.text.Editable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;
import android.content.res.ColorStateList;
import androidx.core.graphics.ColorUtils;

import android.widget.LinearLayout;
import android.view.animation.DecelerateInterpolator;

import tn.eluea.kgpt.util.ModelTagHelper;
import tn.eluea.kgpt.util.Logger;
import tn.eluea.kgpt.util.ProviderModelsFetcher;
import tn.eluea.kgpt.util.TokenTagHelper;
import tn.eluea.kgpt.ui.common.MaxTokensWhySheet;
import tn.eluea.kgpt.ui.common.HardCapManagerSheet;

public class LabFragment extends Fragment implements ProviderListDialogFragment.Callback, BackupModelListDialogFragment.Callback {

    @Nullable
    private java.util.function.Consumer<LanguageModel> onBackupModelPicked;

    // Export AI diagnostics ZIP (for large logs that can't be fully copied)
    private ActivityResultLauncher<Intent> exportAiDiagLauncher;
    private java.io.File pendingAiDiagZipFile;

    private View rowConversationSubModel;
    private TextView tvConversationSubModelValue;
    private TextView tvConversationSubModelProvider;
    private TextView tvConversationProviderValue;
    private TextView tvConversationProviderStatus;
    private ImageView ivConversationProviderIcon;
    private ImageView ivConversationProviderRefresh;
    private LinearLayout llMainSelectedModelTags;
    private LinearLayout llMainSelectedModelTagsRow1;
    private LinearLayout llMainSelectedModelTagsRow2;
    private View areaPickProvider;
    private View areaPickSubmodel;

    private View rowNormalModelThinking;
    private TextView tvNormalModelThinkingValue;
    private ProgressBar pbNormalModelThinkingMicro;
    private ProgressBar pbConversationMemoryMicro;
    private FrameLayout flNormalModelThinkingMicroContainer;
    private FrameLayout flConversationMemoryMicroContainer;
    private View vNormalModelThinkingMicroSweep;
    private View vConversationMemoryMicroSweep;
    private TextView tvNormalModelThinkingRiskHint;

    private ValueAnimator conversationMemoryRowMicroAnimator;
    private ValueAnimator normalThinkingRowMicroAnimator;

    private View rowReasoningModelThinking;
    private TextView tvReasoningModelThinkingValue;
    private ProgressBar pbReasoningModelThinkingMicro;
    private FrameLayout flReasoningModelThinkingMicroContainer;
    private View vReasoningModelThinkingMicroSweep;
    private TextView tvReasoningModelThinkingRiskHint;

    private ValueAnimator reasoningThinkingRowMicroAnimator;

    // Streaming settings row values that need refresh when returning from settings screens.
    private TextView tvStreamingNlModelValue;

    // AI trigger multiline send mode (syncs with AI Triggers switch)
    private TextView tvAiMultilineSendValue;

    // Roles row (needs refresh when returning from RolesSettingsActivity)
    private TextView tvManageRolesValue;

    // Output length row (needs live refresh when model capability cache changes / auto-downgrade fires)
    private TextView tvOutputLengthValue;
    private TextView tvOutputLengthCapsule;
    private TextView tvOutputLengthEffectiveHint;

    private static final int MEMORY_ANIM_MODE_SEQ_LOOP = 0;
    private static final int MEMORY_ANIM_MODE_TRI_PHASE = 1;
    private static final int MEMORY_ANIM_MODE_SWEEP = 2;

    private static final String PREFS_UI_ANIM = "lab_ui_anim_prefs";
    private static final String PREF_MEMORY_ANIM_MODE = "memory_anim_mode";
    private static final String PREF_MEMORY_ANIM_ENABLED = "memory_anim_enabled";
    private static final String PREF_MEMORY_ANIM_SPEED = "memory_anim_speed";
    private static final String PREF_NORMAL_THINK_ANIM_MODE = "normal_think_anim_mode";
    private static final String PREF_NORMAL_THINK_ANIM_ENABLED = "normal_think_anim_enabled";
    private static final String PREF_NORMAL_THINK_ANIM_SPEED = "normal_think_anim_speed";
    private static final String PREF_REASONING_THINK_ANIM_MODE = "reasoning_think_anim_mode";
    private static final String PREF_REASONING_THINK_ANIM_ENABLED = "reasoning_think_anim_enabled";
    private static final String PREF_REASONING_THINK_ANIM_SPEED = "reasoning_think_anim_speed";
    private static final int DEFAULT_UI_ANIM_SPEED_PERCENT = 70;

    private BroadcastReceiver configChangedReceiver;

    private ActivityResultLauncher<Intent> rolesSettingsLauncher;

    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        exportAiDiagLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    try {
                        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                            cleanupPendingAiDiagExport();
                            return;
                        }
                        Uri uri = result.getData().getData();
                        if (uri == null) {
                            cleanupPendingAiDiagExport();
                            return;
                        }
                        if (pendingAiDiagZipFile == null || !pendingAiDiagZipFile.exists()) {
                            cleanupPendingAiDiagExport();
                            return;
                        }
                        try (java.io.InputStream in = new java.io.FileInputStream(pendingAiDiagZipFile);
                             java.io.OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
                            if (out != null) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    out.write(buf, 0, n);
                                }
                                out.flush();
                            }
                        }
                        try { AiDiagnostics.append("AI_DIAG_EXPORT", "ok uri=" + String.valueOf(uri)); } catch (Throwable ignored) {}
                        try { Toast.makeText(requireContext(), getString(R.string.ui_exported), Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
                    } catch (Throwable t) {
                        try { AiDiagnostics.append("AI_DIAG_EXPORT", "fail " + String.valueOf(t)); } catch (Throwable ignored) {}
                        try { Toast.makeText(requireContext(), getString(R.string.ui_export_failed), Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
                    } finally {
                        cleanupPendingAiDiagExport();
                    }
                });

        rolesSettingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        // Roles might have changed (active role / role list). Refresh summary row.
                        refreshRolesRow();
                    }
                }
        );
    }

    private void cleanupPendingAiDiagExport() {
        try {
            if (pendingAiDiagZipFile != null && pendingAiDiagZipFile.exists()) {
                // best-effort cleanup temp file
                //noinspection ResultOfMethodCallIgnored
                pendingAiDiagZipFile.delete();
            }
        } catch (Throwable ignored) {}
        pendingAiDiagZipFile = null;
    }

    private void startExportAiDiagnosticsZip(String logsContent) {
        try {
            if (getContext() == null) return;
            // Build temp zip in cache
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
            String name = "KGPT_AI_Diagnostics_" + ts + ".zip";
            java.io.File tmp = new java.io.File(requireContext().getCacheDir(), name);
            buildAiDiagnosticsZip(tmp, logsContent);
            pendingAiDiagZipFile = tmp;

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, name);
            exportAiDiagLauncher.launch(intent);
        } catch (Throwable t) {
            try { AiDiagnostics.append("AI_DIAG_EXPORT", "prep_fail " + String.valueOf(t)); } catch (Throwable ignored) {}
            try { Toast.makeText(requireContext(), getString(R.string.ui_export_failed), Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
            cleanupPendingAiDiagExport();
        }
    }

    private void buildAiDiagnosticsZip(java.io.File outFile, String logsContent) throws java.io.IOException {
        if (logsContent == null) logsContent = "";
        if (logsContent.trim().isEmpty()) logsContent = "(empty)";
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {

            // ai_diagnostics.txt
            zos.putNextEntry(new java.util.zip.ZipEntry("ai_diagnostics.txt"));
            byte[] data = logsContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            zos.write(data);
            zos.closeEntry();

            // last_request_meta.txt (most recent request snapshot)
            String lastMeta = tn.eluea.kgpt.util.AiDiagnostics.readLastRequestMeta();
            if (lastMeta == null || lastMeta.trim().isEmpty()) lastMeta = "(empty)";
            zos.putNextEntry(new java.util.zip.ZipEntry("last_request_meta.txt"));
            zos.write(lastMeta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // last_response_raw.txt (raw SSE/JSONL lines or non-stream blob)
            String lastRaw = tn.eluea.kgpt.util.AiDiagnostics.readLastRaw();
            if (lastRaw == null || lastRaw.trim().isEmpty()) lastRaw = "(empty)";
            zos.putNextEntry(new java.util.zip.ZipEntry("last_response_raw.txt"));
            zos.write(lastRaw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // last_interrupt_info.txt (interrupt events for the most recent request)
            String lastInt = tn.eluea.kgpt.util.AiDiagnostics.readLastInterrupt();
            if (lastInt == null || lastInt.trim().isEmpty()) lastInt = "(empty)";
            zos.putNextEntry(new java.util.zip.ZipEntry("last_interrupt_info.txt"));
            zos.write(lastInt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();

            // minimal device/app info (helps debugging)
            StringBuilder info = new StringBuilder();
            info.append("appVersion=").append(tn.eluea.kgpt.BuildConfig.VERSION_NAME).append("\n");
            info.append("versionCode=").append(tn.eluea.kgpt.BuildConfig.VERSION_CODE).append("\n");
            info.append("device=").append(android.os.Build.DEVICE).append("\n");
            info.append("model=").append(android.os.Build.MODEL).append("\n");
            info.append("sdk=").append(android.os.Build.VERSION.SDK_INT).append("\n");
            zos.putNextEntry(new java.util.zip.ZipEntry("device_info.txt"));
            zos.write(info.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

@Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        applyAmoledIfNeeded();
        // Lab is now a main navigation tab, so we don't set dock action here
        // The navigation dock will be shown by default
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshConversationSubModelRow();

        // Refresh streaming non-linear model label (it can be changed in the settings screen).
        try {
            int m = SPManager.getInstance().getStreamingNonLinearModel();
            if (tvStreamingNlModelValue != null) {
                tvStreamingNlModelValue.setText(getStreamingNonLinearModelLabel(m));
            }
        } catch (Throwable ignored) {
        }

        refreshAiMultilineSendRow();

        restartSettingsRowPreviewAnimations();
    }


    @Override
    public void onStart() {
        super.onStart();
        // Listen for config broadcasts so provider/sub-model UI stays in sync
        if (configChangedReceiver == null) {
            configChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    refreshConversationSubModelRow();
                }
            };
        }
        try {
            IntentFilter f = new IntentFilter(UiInteractor.ACTION_DIALOG_RESULT);
            ContextCompat.registerReceiver(requireContext().getApplicationContext(), configChangedReceiver,
                    f, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        cancelSettingsRowPreviewAnimations();
        try {
            if (configChangedReceiver != null) {
                requireContext().getApplicationContext().unregisterReceiver(configChangedReceiver);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelSettingsRowPreviewAnimations();
        pbConversationMemoryMicro = null;
        pbNormalModelThinkingMicro = null;
        flConversationMemoryMicroContainer = null;
        flNormalModelThinkingMicroContainer = null;
        vConversationMemoryMicroSweep = null;
        vNormalModelThinkingMicroSweep = null;
        tvAiMultilineSendValue = null;
        tvNormalModelThinkingRiskHint = null;
        pbReasoningModelThinkingMicro = null;
        flReasoningModelThinkingMicroContainer = null;
        vReasoningModelThinkingMicroSweep = null;
        tvReasoningModelThinkingRiskHint = null;
        tvOutputLengthValue = null;
        tvOutputLengthCapsule = null;
        tvOutputLengthEffectiveHint = null;
        // Lab is a main navigation tab, nothing to clean up
    }

    private void initViews(View view) {
        // Hide back button since Lab is now a main navigation tab
        View btnBack = view.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setVisibility(View.GONE);
        }

        // App Triggers feature
        view.findViewById(R.id.card_app_triggers).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToAppTrigger();
            }
        });

        // Text Actions feature
        view.findViewById(R.id.card_text_actions).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToTextActions();
            }
        });

        // Screenshot Ask AI (floating button)
        View cardScreenshotAsk = view.findViewById(R.id.card_screenshot_ask_ai);
        if (cardScreenshotAsk != null) {
            cardScreenshotAsk.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(requireContext(),
                            tn.eluea.kgpt.ui.lab.screenshotask.ScreenshotAskActivity.class));
                } catch (Throwable ignored) {}
            });
        }

        // Streaming output controls
        SwitchMaterial switchStreaming = view.findViewById(R.id.switch_streaming_output);
        View headerRow = view.findViewById(R.id.row_streaming_header);
        View streamingContent = view.findViewById(R.id.layout_streaming_output_content);
        ImageView ivStreamingExpand = view.findViewById(R.id.iv_streaming_output_expand);
        ViewGroup streamingCard = view.findViewById(R.id.card_streaming_output);
        View speedRow = view.findViewById(R.id.row_streaming_speed);
        View algoLinearRow = view.findViewById(R.id.row_streaming_algo_linear);
        View algoNonLinearRow = view.findViewById(R.id.row_streaming_algo_nonlinear);
        RadioButton rbAlgoLinear = view.findViewById(R.id.rb_stream_algo_linear);
        RadioButton rbAlgoNonLinear = view.findViewById(R.id.rb_stream_algo_nonlinear);
        View nlModelRow = view.findViewById(R.id.row_streaming_nonlinear_model);
        TextView tvNlModelValue = view.findViewById(R.id.tv_stream_nonlinear_model_value);
        tvStreamingNlModelValue = tvNlModelValue;
        Slider sliderSpeed = view.findViewById(R.id.slider_stream_speed);
        TextView tvSpeedValue = view.findViewById(R.id.tv_stream_speed_value);
        SwitchMaterial switchAuto = view.findViewById(R.id.switch_stream_speed_auto);

        View modeRow = view.findViewById(R.id.row_streaming_mode);
        View selfCheckRow = view.findViewById(R.id.row_streaming_selfcheck);
        TextView tvSelfCheckValue = view.findViewById(R.id.tv_stream_selfcheck_value);
        TextView tvModeValue = view.findViewById(R.id.tv_stream_mode_value);
        View granularityRow = view.findViewById(R.id.row_streaming_granularity);
        TextView tvGranularityValue = view.findViewById(R.id.tv_stream_granularity_value);
        View fallbackRow = view.findViewById(R.id.row_streaming_fallback);
        SwitchMaterial switchFallback = view.findViewById(R.id.switch_stream_fallback_non_stream);

        boolean enabled = false;
        int speedPercent = 60;
        boolean autoSpeed = true;
        int speedAlgo = SPManager.STREAM_SPEED_ALGO_LINEAR;
        int mode = SPManager.STREAM_MODE_AUTO;
        int granularity = SPManager.STREAM_GRANULARITY_CHARS;
        boolean fallbackNonStream = true;
        try {
            enabled = SPManager.getInstance().getStreamingOutputEnabled();
        } catch (Throwable ignored) {
        }
        try {
            speedPercent = SPManager.getInstance().getStreamingOutputSpeedPercent();
        } catch (Throwable ignored) {
        }
        try {
            autoSpeed = SPManager.getInstance().getStreamingOutputSpeedAutoEnabled();
        } catch (Throwable ignored) {
        }
        try {
            mode = SPManager.getInstance().getStreamingOutputMode();
        } catch (Throwable ignored) {
        }
        try {
            granularity = SPManager.getInstance().getStreamingOutputGranularity();
        } catch (Throwable ignored) {
        }
        try {
            fallbackNonStream = SPManager.getInstance().getStreamingOutputFallbackNonStreamEnabled();
        } catch (Throwable ignored) {
        }
        try {
            speedAlgo = SPManager.getInstance().getStreamingOutputSpeedAlgorithm();
        } catch (Throwable ignored) {
        }

        int nlModel = SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT;
        try {
            nlModel = SPManager.getInstance().getStreamingNonLinearModel();
        } catch (Throwable ignored) {
        }

        if (switchStreaming != null) {
            switchStreaming.setChecked(enabled);
            switchStreaming.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    SPManager.getInstance().setStreamingOutputEnabled(isChecked);
                } catch (Throwable ignored) {
                }
                // Speed slider + auto-speed are linear-only. When user selects non-linear speed,
                // keep these controls visible but disabled to avoid confusion.
                setStreamingSpeedControlsEnabled(speedRow, sliderSpeed, tvSpeedValue, switchAuto,
                        isChecked, getSafeStreamingSpeedAlgorithm());
                setStreamingAlgorithmOptionRowsEnabled(algoLinearRow, algoNonLinearRow, rbAlgoLinear, rbAlgoNonLinear, isChecked);
                setStreamingNonLinearModelRowEnabled(nlModelRow, tvNlModelValue, isChecked, getSafeStreamingSpeedAlgorithm());
                setStreamingAdvancedControlsEnabled(modeRow, tvModeValue, granularityRow, tvGranularityValue, fallbackRow, switchFallback,
                        isChecked, getSafeStreamingMode());
                setStreamingSelfCheckRowEnabled(selfCheckRow, tvSelfCheckValue, isChecked);
            });
        }

        // Streaming output accordion (default: collapsed). Tap header to expand/collapse.
        if (headerRow != null && streamingContent != null && ivStreamingExpand != null && streamingCard != null) {
            final boolean[] expanded = new boolean[]{false};
            streamingContent.setVisibility(View.GONE);
            ivStreamingExpand.setRotation(0f);
            headerRow.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                tn.eluea.kgpt.util.TransitionHelper.beginTransition(streamingCard, tn.eluea.kgpt.util.TransitionHelper.DURATION_FAST);
                streamingContent.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                ivStreamingExpand.animate()
                        .rotation(expanded[0] ? 180f : 0f)
                        .setDuration(tn.eluea.kgpt.util.TransitionHelper.DURATION_FAST)
                        .start();
            });
        }

        // Speed algorithm options (Linear / Non-linear)
        final IntConsumer applySpeedAlgo = selected -> {
            try {
                SPManager.getInstance().setStreamingOutputSpeedAlgorithm(selected);
            } catch (Throwable ignored) {
            }
            // Update radios
            if (rbAlgoLinear != null) rbAlgoLinear.setChecked(selected == SPManager.STREAM_SPEED_ALGO_LINEAR);
            if (rbAlgoNonLinear != null) rbAlgoNonLinear.setChecked(selected == SPManager.STREAM_SPEED_ALGO_NONLINEAR);
            // Linear-only controls should reflect the selected algorithm immediately.
            setStreamingSpeedControlsEnabled(speedRow, sliderSpeed, tvSpeedValue, switchAuto,
                    getSafeStreamingEnabled(), selected);
            setStreamingNonLinearModelRowEnabled(nlModelRow, tvNlModelValue, getSafeStreamingEnabled(), selected);
        };

        // Initial radio state
        if (rbAlgoLinear != null) rbAlgoLinear.setChecked(speedAlgo == SPManager.STREAM_SPEED_ALGO_LINEAR);
        if (rbAlgoNonLinear != null) rbAlgoNonLinear.setChecked(speedAlgo == SPManager.STREAM_SPEED_ALGO_NONLINEAR);

        if (algoLinearRow != null) {
            algoLinearRow.setOnClickListener(v -> applySpeedAlgo.accept(SPManager.STREAM_SPEED_ALGO_LINEAR));
        }
        if (algoNonLinearRow != null) {
            algoNonLinearRow.setOnClickListener(v -> applySpeedAlgo.accept(SPManager.STREAM_SPEED_ALGO_NONLINEAR));
        }

        // Non-linear model settings screen
        if (tvNlModelValue != null) tvNlModelValue.setText(getStreamingNonLinearModelLabel(nlModel));
        if (nlModelRow != null) {
            nlModelRow.setOnClickListener(v -> {
                if (getContext() == null) return;
                try {
                    startActivity(new Intent(requireContext(), StreamingNonLinearModelSettingsActivity.class));
                } catch (Throwable ignored) {
                    // Fallback to legacy sheet
                    StreamingNonLinearModelSettingsBottomSheet.show(requireContext(), null);
                }
            });
        }

        if (sliderSpeed != null) {
            if (speedPercent < 0) speedPercent = 0;
            if (speedPercent > 100) speedPercent = 100;
            sliderSpeed.setValue(speedPercent);
            if (tvSpeedValue != null)
                tvSpeedValue.setText(speedPercent + "%");

            // Update label live, persist on release.
            sliderSpeed.addOnChangeListener((slider, value, fromUser) -> {
                int v = Math.max(0, Math.min(100, Math.round(value)));
                if (tvSpeedValue != null)
                    tvSpeedValue.setText(v + "%");
            });
            sliderSpeed.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) {
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    int v = Math.max(0, Math.min(100, Math.round(slider.getValue())));
                    try {
                        SPManager.getInstance().setStreamingOutputSpeedPercent(v);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        if (switchAuto != null) {
            switchAuto.setChecked(autoSpeed);
            switchAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    SPManager.getInstance().setStreamingOutputSpeedAutoEnabled(isChecked);
                } catch (Throwable ignored) {
                }

                // Auto-speed and manual speed slider are mutually exclusive.
                // When auto-speed is ON, disable the slider (greyed out).
                setStreamingSpeedControlsEnabled(speedRow, sliderSpeed, tvSpeedValue, switchAuto,
                        getSafeStreamingEnabled(), getSafeStreamingSpeedAlgorithm());
            });
        }

        // Mode selection
        if (tvModeValue != null) tvModeValue.setText(getStreamingModeValueText(mode));
        if (modeRow != null) {
            modeRow.setOnClickListener(v -> showStreamingModeDialog(getSafeStreamingMode(), selected -> {
                try {
                    SPManager.getInstance().setStreamingOutputMode(selected);
                } catch (Throwable ignored) {
                }
                if (tvModeValue != null) tvModeValue.setText(getStreamingModeValueText(selected));
                setStreamingAdvancedControlsEnabled(modeRow, tvModeValue, granularityRow, tvGranularityValue, fallbackRow, switchFallback,
                        getSafeStreamingEnabled(), selected);
            }));
        }



        // Self-check (optional)
        if (tvSelfCheckValue != null) {
            tvSelfCheckValue.setText(getString(R.string.ui_streaming_selfcheck_hint));
        }
        if (selfCheckRow != null) {
            selfCheckRow.setOnClickListener(v -> showStreamingSelfCheckDialog());
        }
        // Granularity selection
        if (tvGranularityValue != null) tvGranularityValue.setText(getStreamingGranularityLabel(granularity));
        if (granularityRow != null) {
            granularityRow.setOnClickListener(v -> showStreamingGranularityDialog(getSafeStreamingGranularity(), selected -> {
                try {
                    SPManager.getInstance().setStreamingOutputGranularity(selected);
                } catch (Throwable ignored) {
                }
                if (tvGranularityValue != null) tvGranularityValue.setText(getStreamingGranularityLabel(selected));
            }));
        }

        // Fallback toggle
        if (switchFallback != null) {
            switchFallback.setChecked(fallbackNonStream);
            switchFallback.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    SPManager.getInstance().setStreamingOutputFallbackNonStreamEnabled(isChecked);
                } catch (Throwable ignored) {
                }
            });
        }

        setStreamingSpeedControlsEnabled(speedRow, sliderSpeed, tvSpeedValue, switchAuto, enabled, speedAlgo);
        setStreamingAlgorithmOptionRowsEnabled(algoLinearRow, algoNonLinearRow, rbAlgoLinear, rbAlgoNonLinear, enabled);
        setStreamingNonLinearModelRowEnabled(nlModelRow, tvNlModelValue, enabled, speedAlgo);
        setStreamingAdvancedControlsEnabled(modeRow, tvModeValue, granularityRow, tvGranularityValue, fallbackRow, switchFallback,
                enabled, mode);
        setStreamingSelfCheckRowEnabled(selfCheckRow, tvSelfCheckValue, enabled);

        // Conversation settings controls (memory & thinking depth)
        rowConversationSubModel = view.findViewById(R.id.row_conversation_sub_model);
        areaPickProvider = view.findViewById(R.id.area_pick_provider);
        areaPickSubmodel = view.findViewById(R.id.area_pick_submodel);
        tvConversationProviderValue = view.findViewById(R.id.tv_conversation_ai_provider_value);
        tvConversationProviderStatus = view.findViewById(R.id.tv_conversation_ai_provider_status);
        ivConversationProviderIcon = view.findViewById(R.id.iv_conversation_ai_provider_icon);
        ivConversationProviderRefresh = view.findViewById(R.id.iv_conversation_provider_refresh);
        tvConversationSubModelValue = view.findViewById(R.id.tv_conversation_sub_model_value);
        tvConversationSubModelProvider = view.findViewById(R.id.tv_conversation_sub_model_provider);
        llMainSelectedModelTags = view.findViewById(R.id.ll_main_selected_model_tags);
        llMainSelectedModelTagsRow1 = view.findViewById(R.id.ll_main_selected_model_tags_row1);
        llMainSelectedModelTagsRow2 = view.findViewById(R.id.ll_main_selected_model_tags_row2);

        // Marquee needs the TextView to be "selected"; AAPT doesn't support android:selected in XML here.
        if (tvConversationSubModelValue != null) {
            tvConversationSubModelValue.setSelected(true);
        }
        View memoryRow = view.findViewById(R.id.row_conversation_memory);

        // AI trigger multiline (newline policy)
        View rowAiMultiline = view.findViewById(R.id.row_ai_multiline_send);
        tvAiMultilineSendValue = view.findViewById(R.id.tv_ai_multiline_send_value);
        refreshAiMultilineSendRow();
        if (rowAiMultiline != null) {
            rowAiMultiline.setOnClickListener(v -> showAiMultilineSendSettingsDialog());
        }

        // Conversation settings accordion (default: expanded)
        View conversationHeader = view.findViewById(R.id.conversation_settings_header);
        View conversationContent = view.findViewById(R.id.layout_conversation_settings_content);
        ImageView ivConversationExpand = view.findViewById(R.id.iv_conversation_settings_expand);
        ViewGroup conversationCard = view.findViewById(R.id.card_conversation_settings);
        if (conversationHeader != null && conversationContent != null && ivConversationExpand != null && conversationCard != null) {
            final boolean[] expanded = new boolean[]{false};
            // expand_more icon points down; rotate 180 to show ▲ when expanded.
            ivConversationExpand.setRotation(0f);
            conversationContent.setVisibility(View.GONE);
            conversationHeader.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                tn.eluea.kgpt.util.TransitionHelper.beginTransition(conversationCard, tn.eluea.kgpt.util.TransitionHelper.DURATION_FAST);
                conversationContent.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                ivConversationExpand.animate()
                        .rotation(expanded[0] ? 180f : 0f)
                        .setDuration(tn.eluea.kgpt.util.TransitionHelper.DURATION_FAST)
                        .start();
            });
        }

        TextView tvMemoryValue = view.findViewById(R.id.tv_conversation_memory_value);
        pbConversationMemoryMicro = view.findViewById(R.id.pb_conversation_memory_micro);
        flConversationMemoryMicroContainer = view.findViewById(R.id.fl_conversation_memory_micro_container);
        vConversationMemoryMicroSweep = view.findViewById(R.id.v_conversation_memory_micro_sweep);
        rowNormalModelThinking = view.findViewById(R.id.row_thinking_depth);
        tvNormalModelThinkingValue = view.findViewById(R.id.tv_thinking_depth_value);
        pbNormalModelThinkingMicro = view.findViewById(R.id.pb_thinking_depth_micro);
        flNormalModelThinkingMicroContainer = view.findViewById(R.id.fl_thinking_depth_micro_container);
        vNormalModelThinkingMicroSweep = view.findViewById(R.id.v_thinking_depth_micro_sweep);
        tvNormalModelThinkingRiskHint = view.findViewById(R.id.tv_thinking_depth_risk_hint);
        try { if (tvNormalModelThinkingRiskHint != null) tvNormalModelThinkingRiskHint.setEllipsize(TextUtils.TruncateAt.END); } catch (Throwable ignored) {}

        rowReasoningModelThinking = view.findViewById(R.id.row_reasoning_model_thinking);
        tvReasoningModelThinkingValue = view.findViewById(R.id.tv_reasoning_model_thinking_value);
        pbReasoningModelThinkingMicro = view.findViewById(R.id.pb_reasoning_model_thinking_micro);
        flReasoningModelThinkingMicroContainer = view.findViewById(R.id.fl_reasoning_model_thinking_micro_container);
        vReasoningModelThinkingMicroSweep = view.findViewById(R.id.v_reasoning_model_thinking_micro_sweep);
        tvReasoningModelThinkingRiskHint = view.findViewById(R.id.tv_reasoning_model_thinking_risk_hint);
        try { if (tvReasoningModelThinkingRiskHint != null) tvReasoningModelThinkingRiskHint.setEllipsize(TextUtils.TruncateAt.END); } catch (Throwable ignored) {}

        int memLevel = SPManager.getInstance().getConversationMemoryLevel();
        float normalThinking = 0.7f;
        try { normalThinking = SPManager.getInstance().getNormalModelThinking(); } catch (Throwable ignored) {}
        int reasoningThinkingMode = SPManager.REASONING_MODEL_THINKING_AUTO;
        try { reasoningThinkingMode = SPManager.getInstance().getReasoningModelThinkingMode(); } catch (Throwable ignored) {}

        updateConversationMemoryRowSummary(tvMemoryValue, pbConversationMemoryMicro, memLevel);
        if (tvNormalModelThinkingValue != null) tvNormalModelThinkingValue.setText(getNormalModelThinkingSummary(normalThinking));
        updateNormalModelThinkingMicro(normalThinking);
        updateReasoningModelThinkingRowVisuals(reasoningThinkingMode);
        refreshAiMultilineSendRow();

        restartSettingsRowPreviewAnimations();

        refreshConversationSubModelRow();
        if (areaPickProvider != null) {
            areaPickProvider.setOnClickListener(v -> showConversationProviderPicker());
            areaPickProvider.setOnLongClickListener(v -> {
                try {
                    if (!isAdded()) return false;
                    if (!SPManager.isReady()) return false;
                    SPManager sp = SPManager.getInstance();
                    LanguageModel provider = sp.hasLanguageModel() ? sp.getLanguageModel() : null;
                    if (provider == null) return false;
                    // Long-press: directly open edit dialog for current provider.
                    QuickProviderConfigDialogFragment.newInstance(provider.name())
                            .show(getParentFragmentManager(), QuickProviderConfigDialogFragment.TAG);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            });
        }
        if (ivConversationProviderRefresh != null) {
            ivConversationProviderRefresh.setOnClickListener(v -> refreshCurrentProviderModelCache());
        }
        if (areaPickSubmodel != null) {
            areaPickSubmodel.setOnClickListener(v -> showConversationSubModelPicker());
            areaPickSubmodel.setOnLongClickListener(v -> {
                try {
                    if (!SPManager.isReady()) return false;
                    SPManager sp = SPManager.getInstance();
                    LanguageModel provider = sp.hasLanguageModel() ? sp.getLanguageModel() : null;
                    if (provider == null) return false;
                    String sub = sp.getSubModel(provider);
                    if (TextUtils.isEmpty(sub) && provider != null) sub = provider.getDefault(LanguageModelField.SubModel);
                    if (TextUtils.isEmpty(sub)) return false;
                    showModelCapabilityTestDialog(provider, sub, null);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            });
        }

        memoryRow.setOnClickListener(v -> showConversationMemoryConsoleDialog(
                SPManager.getInstance().getConversationMemoryLevel(),
                1,
                selected -> {
                    SPManager.getInstance().setConversationMemoryLevel(selected);
                    updateConversationMemoryRowSummary(tvMemoryValue, pbConversationMemoryMicro, selected);
                    refreshAiMultilineSendRow();

        restartSettingsRowPreviewAnimations();
                }
        ));
        // Enable/disable based on current provider + sub-model capability
        refreshNormalModelThinkingRowState();
        refreshReasoningModelThinkingRowState();

        if (rowNormalModelThinking != null) {
            rowNormalModelThinking.setOnClickListener(v -> {
                if (!isNormalModelThinkingAdjustable()) return;
                float cur = 0.7f;
                try { cur = SPManager.getInstance().getNormalModelThinking(); } catch (Throwable ignored) {}
                showNormalModelThinkingDialog(cur, selected -> {
                    try { SPManager.getInstance().setNormalModelThinking(selected); } catch (Throwable ignored) {}
                    if (tvNormalModelThinkingValue != null) tvNormalModelThinkingValue.setText(getNormalModelThinkingSummary(selected));
                    updateNormalModelThinkingMicro(selected);
                    refreshAiMultilineSendRow();

        restartSettingsRowPreviewAnimations();
                });
            });
        }

        if (rowReasoningModelThinking != null) {
            rowReasoningModelThinking.setOnClickListener(v -> {
                if (!isReasoningModelThinkingAdjustable()) return;
                int cur = SPManager.REASONING_MODEL_THINKING_AUTO;
                try { cur = SPManager.getInstance().getReasoningModelThinkingMode(); } catch (Throwable ignored) {}
                showReasoningModelThinkingDialog(cur, selected -> {
                    try { SPManager.getInstance().setReasoningModelThinkingMode(selected); } catch (Throwable ignored) {}
                    updateReasoningModelThinkingRowVisuals(selected);
                    refreshAiMultilineSendRow();

        restartSettingsRowPreviewAnimations();
                });
            });
        }
        // Output length (Max tokens preset)
        View rowMaxTokens = view.findViewById(R.id.row_max_tokens_preset);
        View ivTokenWhy = view.findViewById(R.id.iv_token_why);
        TextView tvTokenValue = view.findViewById(R.id.tv_token_value);
        TextView tvTokenCapsule = view.findViewById(R.id.tv_token_capsule);
        TextView tvTokenEffectiveHint = view.findViewById(R.id.tv_token_effective_hint);
        tvOutputLengthValue = tvTokenValue;
        tvOutputLengthCapsule = tvTokenCapsule;
        tvOutputLengthEffectiveHint = tvTokenEffectiveHint;

        // Hard cap manager row
        View rowHardCapMgr = view.findViewById(R.id.row_hard_cap_manager);
        TextView tvHardCapMgrValue = view.findViewById(R.id.tv_hard_cap_manager_value);
        try {
            if (tvHardCapMgrValue != null && SPManager.isReady()) {
                int n = 0;
                try { n = SPManager.getInstance().getAllCachedHardMaxTokensEntries().size(); } catch (Throwable ignored) {}
                if (n > 0) tvHardCapMgrValue.setText(getString(R.string.ui_hard_cap_manager_count, n));
                else tvHardCapMgrValue.setText("-");
            }
        } catch (Throwable ignored) {}

        if (rowHardCapMgr != null) {
            rowHardCapMgr.setOnClickListener(v -> {
                try {
                    HardCapManagerSheet.show(requireContext());
                } catch (Throwable ignored) {}
            });
        }

        // Auto summarize older context
        View rowAutoSumm = view.findViewById(R.id.row_auto_summarize_old_context);
        SwitchMaterial switchAutoSumm = view.findViewById(R.id.switch_auto_summarize_old_context);

        // Auto fallback strategy (stream / baseurl / model)
        View rowDowngrade = view.findViewById(R.id.row_auto_downgrade);
        TextView tvDowngrade = view.findViewById(R.id.tv_auto_downgrade_value);

        // Request cancel / concurrency policy
        View rowPolicy = view.findViewById(R.id.row_request_policy);
        TextView tvPolicy = view.findViewById(R.id.tv_request_policy_value);

        // Watchdog timeouts (first chunk / stall)
        View rowWatchdog = view.findViewById(R.id.row_watchdog_timeout);
        TextView tvWatchdog = view.findViewById(R.id.tv_watchdog_timeout_value);

        try {
            refreshOutputLengthRowUi();
        } catch (Throwable ignored) {}

        // One-tap "Why" panel for output length decision
        if (ivTokenWhy != null) {
            ivTokenWhy.setOnClickListener(v -> {
                try {
                    SPManager spInner = SPManager.getInstance();
                    LanguageModel p = spInner.getLanguageModel();
                    String sub = p != null ? spInner.getSubModel(p) : null;
                    MaxTokensWhySheet.show(requireContext(), p, sub, "lab_output_length_row");
                } catch (Throwable ignored) {}
            });
        }
if (rowMaxTokens != null) {
            rowMaxTokens.setOnClickListener(v -> showOutputLengthDialog(
                    safeGetMaxTokensLimit(),
                    (selectedTokens, isCustomSelection) -> {
                        try {
                            SPManager spInner = SPManager.getInstance();
                            spInner.setMaxTokensLimit(selectedTokens);
                            spInner.setMaxTokensIsCustom(isCustomSelection);
                            try { spInner.setMaxTokensSelectionSource(SPManager.MAX_TOKENS_SELECTION_SOURCE_USER_FIXED); } catch (Throwable ignored2) {}
                        } catch (Throwable ignored) {}
                        refreshOutputLengthRowUi();
                        // 自定义模式只提醒，不自动改；固定档位超过已知上限时给出提示（真正自动回落发生在切换模型/探测完成后）
                        maybeShowOutputLengthManualSelectionHint(selectedTokens, isCustomSelection);
                    }
            ));
            rowMaxTokens.setOnLongClickListener(v -> {
				try {
					v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
				} catch (Throwable ignored) {}
                showOutputLengthCacheManagementMenu();
                return true;
            });
        }

        try {
            boolean autoSumm = SPManager.getInstance().getAutoSummarizeOldContextEnabled();
            if (switchAutoSumm != null) switchAutoSumm.setChecked(autoSumm);
        } catch (Throwable ignored) {}

        if (switchAutoSumm != null) {
            switchAutoSumm.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    SPManager.getInstance().setAutoSummarizeOldContextEnabled(isChecked);
                } catch (Throwable ignored) {}
            });
        }
        if (rowAutoSumm != null && switchAutoSumm != null) {
            rowAutoSumm.setOnClickListener(v -> switchAutoSumm.setChecked(!switchAutoSumm.isChecked()));
        }

        if (tvDowngrade != null) tvDowngrade.setText(getAutoDowngradeSummary());
        if (rowDowngrade != null) {
            rowDowngrade.setOnClickListener(v -> showAutoDowngradeDialog(() -> {
                if (tvDowngrade != null) tvDowngrade.setText(getAutoDowngradeSummary());
            }));
        }

        try {
            int policy = SPManager.getInstance().getRequestConcurrencyPolicy();
            if (tvPolicy != null) tvPolicy.setText(getRequestPolicyLabel(policy));
        } catch (Throwable ignored) {}

        if (rowPolicy != null) {
            rowPolicy.setOnClickListener(v -> showRequestPolicyDialog(
                    safeGetRequestPolicy(),
                    selected -> {
                        try {
                            SPManager.getInstance().setRequestConcurrencyPolicy(selected);
                        } catch (Throwable ignored) {}
                        if (tvPolicy != null) tvPolicy.setText(getRequestPolicyLabel(selected));
                    }
            ));
        }
        // Watchdog timeout customization
        Runnable refreshWatchdog = () -> {
            try {
                SPManager spInner = SPManager.getInstance();
                long firstMs = spInner.getWatchdogFirstChunkTimeoutMs();
                long stallMs = spInner.getWatchdogStallTimeoutMs();
                int firstSec = (int) Math.max(1, firstMs / 1000L);
                int stallSec = (int) Math.max(1, stallMs / 1000L);
                if (tvWatchdog != null) {
                    tvWatchdog.setText(getString(R.string.ui_watchdog_timeout_summary_format, firstSec, stallSec));
                }
            } catch (Throwable ignored) {
                if (tvWatchdog != null) tvWatchdog.setText("-");
            }
        };
        refreshWatchdog.run();

        if (rowWatchdog != null) {
            rowWatchdog.setOnClickListener(v -> showWatchdogTimeoutDialog(refreshWatchdog));
        }





        // Interrupt AI settings (Stop/Pause gesture during streaming output)
        View rowInterruptAi = view.findViewById(R.id.row_interrupt_ai);
        TextView tvInterruptAi = view.findViewById(R.id.tv_interrupt_ai_value);

        Runnable refreshInterruptAi = () -> {
            boolean en = false;
            try { en = SPManager.getInstance().getInterruptAiEnabled(); } catch (Throwable ignored) {}
            if (tvInterruptAi != null) {
                tvInterruptAi.setText(en ? getString(R.string.ui_enabled) : getString(R.string.ui_disabled));
            }
        };
        refreshInterruptAi.run();

        if (rowInterruptAi != null) {
            rowInterruptAi.setOnClickListener(v -> showInterruptAiDialog(refreshInterruptAi));
        }

        // AI diagnostics log (rolling buffer) - helps users report issues.
        View rowAiDiag = view.findViewById(R.id.row_ai_diagnostics);
        TextView tvAiDiag = view.findViewById(R.id.tv_ai_diagnostics_value);

        Runnable refreshAiDiag = () -> {
            try {
                String s = tn.eluea.kgpt.util.AiDiagnostics.readAll();
                int lines = 0;
                if (s != null && !s.isEmpty()) {
                    lines = 1;
                    for (int i = 0; i < s.length(); i++) {
                        if (s.charAt(i) == '\n') lines++;
                    }
                }
                if (tvAiDiag != null) {
                    tvAiDiag.setText(lines > 0 ? (lines + " " + getString(R.string.ui_lines)) : getString(R.string.ui_empty));
                }
            } catch (Throwable t) {
                if (tvAiDiag != null) tvAiDiag.setText(getString(R.string.ui_empty));
            }
        };
        refreshAiDiag.run();
        if (rowAiDiag != null) {
            rowAiDiag.setOnClickListener(v -> showAiDiagnosticsDialog(refreshAiDiag));
        }


        // Generating Content settings (placeholder + trailing keyword + haptic)
        View rowGenContent = view.findViewById(R.id.row_generating_content);
        TextView tvGenContent = view.findViewById(R.id.tv_generating_content_value);

        Runnable refreshGenContent = () -> {
            boolean en = true;
            try {
                en = SPManager.getInstance().getGeneratingContentEnabled();
            } catch (Throwable ignored) {}
            if (tvGenContent != null) {
                tvGenContent.setText(en ? getString(R.string.ui_enabled) : getString(R.string.ui_disabled));
            }
        };
        refreshGenContent.run();

        if (rowGenContent != null) {
            rowGenContent.setOnClickListener(v -> showGeneratingContentDialog(refreshGenContent));
        }


        // Roles (system prompt/persona)
        View rowRoles = view.findViewById(R.id.row_manage_roles);
        tvManageRolesValue = view.findViewById(R.id.tv_manage_roles_value);

        refreshRolesRow();

        if (rowRoles != null) {
            rowRoles.setOnClickListener(v -> {
                if (getContext() == null) return;
                Intent it = new Intent(requireContext(), RolesSettingsActivity.class);
                if (rolesSettingsLauncher != null) {
                    rolesSettingsLauncher.launch(it);
                } else {
                    startActivity(it);
                }
            });
        }



    }

    /** Refresh roles summary row (current role name) after returning from RolesSettingsActivity. */
    private void refreshRolesRow() {
        try {
            if (tvManageRolesValue == null) return;
            SPManager sp = SPManager.getInstance();
            String activeId = sp.getActiveRoleId();
            String rolesJson = sp.getRolesJson();
            java.util.List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);
            String name = RoleManager.DEFAULT_ROLE_NAME;
            if (activeId != null) {
                for (RoleManager.Role r : roles) {
                    if (r != null && activeId.equals(r.id)) {
                        name = r.name;
                        break;
                    }
                }
            }
            tvManageRolesValue.setText(name);

            // Persona override may have updated "Normal Model Thinking"; refresh micro visualization instantly.
            try {
                float v = sp.getNormalModelThinking();
                if (tvNormalModelThinkingValue != null) tvNormalModelThinkingValue.setText(getNormalModelThinkingSummary(v));
                updateNormalModelThinkingMicro(v);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try {
                if (tvManageRolesValue != null) tvManageRolesValue.setText(RoleManager.DEFAULT_ROLE_NAME);
            } catch (Throwable ignored) {}
        }
    }

    private void showInterruptAiDialog(Runnable onSaved) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View dialogView = inflater.inflate(R.layout.dialog_interrupt_ai, null);

        SwitchMaterial switchEnabled = dialogView.findViewById(R.id.switch_interrupt_ai_enabled);
        com.google.android.material.radiobutton.MaterialRadioButton rbGestureBang = dialogView.findViewById(R.id.rb_interrupt_gesture_bangbang);
        com.google.android.material.radiobutton.MaterialRadioButton rbGestureStop = dialogView.findViewById(R.id.rb_interrupt_gesture_stop);
        com.google.android.material.radiobutton.MaterialRadioButton rbGestureSpace = dialogView.findViewById(R.id.rb_interrupt_gesture_double_space);
        com.google.android.material.radiobutton.MaterialRadioButton rbGestureBack = dialogView.findViewById(R.id.rb_interrupt_gesture_double_backspace);
        com.google.android.material.radiobutton.MaterialRadioButton rbGestureCustom = dialogView.findViewById(R.id.rb_interrupt_gesture_custom);

        com.google.android.material.textfield.TextInputLayout tilCustom = dialogView.findViewById(R.id.til_interrupt_custom);
        com.google.android.material.textfield.TextInputEditText etCustom = dialogView.findViewById(R.id.et_interrupt_custom);
        android.widget.RadioGroup rgGesture = dialogView.findViewById(R.id.rg_interrupt_gesture);

        com.google.android.material.radiobutton.MaterialRadioButton rbModeSoft = dialogView.findViewById(R.id.rb_interrupt_mode_soft);
        com.google.android.material.radiobutton.MaterialRadioButton rbModeHard = dialogView.findViewById(R.id.rb_interrupt_mode_hard);
        com.google.android.material.radiobutton.MaterialRadioButton rbModeBoth = dialogView.findViewById(R.id.rb_interrupt_mode_both);

        SPManager sp = SPManager.getInstance();

        boolean enabled = false;
        int gesture = SPManager.INTERRUPT_GESTURE_BANGBANG;
        int mode = SPManager.INTERRUPT_MODE_SOFT_BUFFER;
        String customTrig = "!!";
        try { enabled = sp.getInterruptAiEnabled(); } catch (Throwable ignored) {}
        try { gesture = sp.getInterruptAiGesture(); } catch (Throwable ignored) {}
        try { mode = sp.getInterruptAiMode(); } catch (Throwable ignored) {}
        try { customTrig = sp.getInterruptAiCustomTrigger(); } catch (Throwable ignored) {}

        if (switchEnabled != null) switchEnabled.setChecked(enabled);

        // Gesture
        if (rbGestureBang != null) rbGestureBang.setChecked(gesture == SPManager.INTERRUPT_GESTURE_BANGBANG);
        if (rbGestureStop != null) rbGestureStop.setChecked(gesture == SPManager.INTERRUPT_GESTURE_STOP);
        if (rbGestureSpace != null) rbGestureSpace.setChecked(gesture == SPManager.INTERRUPT_GESTURE_DOUBLE_SPACE);
        if (rbGestureBack != null) rbGestureBack.setChecked(gesture == SPManager.INTERRUPT_GESTURE_DOUBLE_BACKSPACE);
        if (rbGestureCustom != null) rbGestureCustom.setChecked(gesture == SPManager.INTERRUPT_GESTURE_CUSTOM);

        if (etCustom != null) {
            try { etCustom.setText(customTrig); } catch (Throwable ignored) {}
        }

        // Mode
        if (rbModeSoft != null) rbModeSoft.setChecked(mode == SPManager.INTERRUPT_MODE_SOFT_BUFFER);
        if (rbModeHard != null) rbModeHard.setChecked(mode == SPManager.INTERRUPT_MODE_HARD_CANCEL);
        if (rbModeBoth != null) rbModeBoth.setChecked(mode == SPManager.INTERRUPT_MODE_BOTH);

        // Enable/disable radio groups based on master switch
        Runnable applyEnableState = () -> {
            boolean en = switchEnabled != null && switchEnabled.isChecked();
            if (rbGestureBang != null) rbGestureBang.setEnabled(en);
            if (rbGestureStop != null) rbGestureStop.setEnabled(en);
            if (rbGestureSpace != null) rbGestureSpace.setEnabled(en);
            if (rbGestureBack != null) rbGestureBack.setEnabled(en);
            if (rbGestureCustom != null) rbGestureCustom.setEnabled(en);
            if (rbModeSoft != null) rbModeSoft.setEnabled(en);
            if (rbModeHard != null) rbModeHard.setEnabled(en);
            if (rbModeBoth != null) rbModeBoth.setEnabled(en);

            boolean showCustom = en && rbGestureCustom != null && rbGestureCustom.isChecked();
            if (tilCustom != null) {
                tilCustom.setEnabled(en);
                tilCustom.setVisibility(showCustom ? View.VISIBLE : View.GONE);
            }
        };
        applyEnableState.run();
        if (switchEnabled != null) {
            switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> applyEnableState.run());
        }

        if (rgGesture != null) {
            rgGesture.setOnCheckedChangeListener((group, checkedId) -> applyEnableState.run());
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ui_interrupt_ai))
                .setView(dialogView)
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.confirm), (dialog, which) -> {
                    try {
                        boolean en = switchEnabled != null && switchEnabled.isChecked();
                        sp.setInterruptAiEnabled(en);

                        int g = SPManager.INTERRUPT_GESTURE_BANGBANG;
                        if (rbGestureStop != null && rbGestureStop.isChecked()) g = SPManager.INTERRUPT_GESTURE_STOP;
                        else if (rbGestureSpace != null && rbGestureSpace.isChecked()) g = SPManager.INTERRUPT_GESTURE_DOUBLE_SPACE;
                        else if (rbGestureBack != null && rbGestureBack.isChecked()) g = SPManager.INTERRUPT_GESTURE_DOUBLE_BACKSPACE;
                        else if (rbGestureCustom != null && rbGestureCustom.isChecked()) g = SPManager.INTERRUPT_GESTURE_CUSTOM;
                        sp.setInterruptAiGesture(g);

                        if (g == SPManager.INTERRUPT_GESTURE_CUSTOM) {
                            String t = "";
                            try { t = etCustom != null ? String.valueOf(etCustom.getText()) : ""; } catch (Throwable ignored) {}
                            sp.setInterruptAiCustomTrigger(t);
                        }

                        int m = SPManager.INTERRUPT_MODE_SOFT_BUFFER;
                        if (rbModeHard != null && rbModeHard.isChecked()) m = SPManager.INTERRUPT_MODE_HARD_CANCEL;
                        else if (rbModeBoth != null && rbModeBoth.isChecked()) m = SPManager.INTERRUPT_MODE_BOTH;
                        sp.setInterruptAiMode(m);
                    } catch (Throwable ignored) {}

                    if (onSaved != null) onSaved.run();
                })
                .show();
    }

    
    private void showAiDiagnosticsDialog(Runnable onClosed) {
        if (getContext() == null) return;

        String logs = "";
        try { logs = tn.eluea.kgpt.util.AiDiagnostics.readAll(); } catch (Throwable ignored) {}
        if (logs == null) logs = "";
        final String logsSnapshot = logs;

        LayoutInflater inflater = LayoutInflater.from(getContext());
        View root = inflater.inflate(R.layout.dialog_ai_diagnostics, null);

        android.widget.TextView tv = root.findViewById(R.id.tv_ai_diag);
        if (tv != null) {
            tv.setText(logsSnapshot.isEmpty() ? getString(R.string.ui_empty) : logsSnapshot);
        }

        final androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ui_ai_diagnostics))
                .setView(root)
                .create();

        com.google.android.material.button.MaterialButton btnClear = root.findViewById(R.id.btn_ai_diag_clear);
        com.google.android.material.button.MaterialButton btnExport = root.findViewById(R.id.btn_ai_diag_export);
        com.google.android.material.button.MaterialButton btnCopy = root.findViewById(R.id.btn_ai_diag_copy);
        com.google.android.material.button.MaterialButton btnClose = root.findViewById(R.id.btn_ai_diag_close);

        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                try { tn.eluea.kgpt.util.AiDiagnostics.clear(); } catch (Throwable ignored) {}
                try { android.widget.Toast.makeText(requireContext(), getString(R.string.ui_cleared), android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
                try { dialog.dismiss(); } catch (Throwable ignored) {}
                if (onClosed != null) onClosed.run();
            });
        }

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Diagnostics", logsSnapshot));
                        android.widget.Toast.makeText(requireContext(), getString(R.string.ui_copied), android.widget.Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {}
                try { dialog.dismiss(); } catch (Throwable ignored) {}
                if (onClosed != null) onClosed.run();
            });
        }

        if (btnExport != null) {
            btnExport.setOnClickListener(v -> {
                // Export as a zip file via Storage Access Framework (large logs may not fit clipboard)
                startExportAiDiagnosticsZip(logsSnapshot);
                try { dialog.dismiss(); } catch (Throwable ignored) {}
                if (onClosed != null) onClosed.run();
            });
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                try { dialog.dismiss(); } catch (Throwable ignored) {}
                if (onClosed != null) onClosed.run();
            });
        }

        dialog.show();
    }


    private void showGeneratingContentDialog(Runnable onSaved) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View dialogView = inflater.inflate(R.layout.dialog_generating_content, null);

        SwitchMaterial switchEnabled = dialogView.findViewById(R.id.switch_gen_content_enabled);
        SwitchMaterial switchGenToast = dialogView.findViewById(R.id.switch_gen_content_toast);
        SwitchMaterial switchGenDynamicPrefix = dialogView.findViewById(R.id.switch_gen_content_dynamic_prefix);
        SwitchMaterial switchGenTokenBurner = dialogView.findViewById(R.id.switch_gen_content_token_burner);
        SwitchMaterial switchGenStandaloneFloat = dialogView.findViewById(R.id.switch_gen_content_standalone_float);
        SwitchMaterial switchInputRoleMarkerMaster = dialogView.findViewById(R.id.switch_gen_input_role_marker_master);
        SwitchMaterial switchInputRoleMarkerSuffix = dialogView.findViewById(R.id.switch_gen_input_role_marker_suffix);
        SwitchMaterial switchInputRoleMarkerPrefix = dialogView.findViewById(R.id.switch_gen_input_role_marker_prefix);
        TextInputEditText etPrefix = dialogView.findViewById(R.id.et_gen_content_prefix);
        TextInputEditText etSuffix = dialogView.findViewById(R.id.et_gen_content_suffix);
        SwitchMaterial switchThinkingElapsed = dialogView.findViewById(R.id.switch_gen_thinking_elapsed);
        SwitchMaterial switchReplyTokenCounter = dialogView.findViewById(R.id.switch_gen_reply_token_counter);
        SwitchMaterial switchReplyTokenCounterAutoRemove = dialogView.findViewById(R.id.switch_gen_reply_token_counter_auto_remove);

        // Auto-remove delay (0-10s) for the final token counter marker
        View layoutTokenAutoRemoveDelay = dialogView.findViewById(R.id.layout_gen_reply_token_counter_auto_remove_delay);
        Slider sliderTokenAutoRemoveDelay = dialogView.findViewById(R.id.slider_gen_reply_token_counter_auto_remove_delay);
        TextView tvTokenAutoRemoveDelay = dialogView.findViewById(R.id.tv_gen_reply_token_counter_auto_remove_delay_value);

        MaterialAutoCompleteTextView actMarkerStyle = dialogView.findViewById(R.id.act_gen_marker_style);
        MaterialAutoCompleteTextView actMarkerColor = dialogView.findViewById(R.id.act_gen_marker_color);
        MaterialAutoCompleteTextView actMarkerLen = dialogView.findViewById(R.id.act_gen_marker_len);
        com.google.android.material.slider.Slider sliderMarkerSpeed = dialogView.findViewById(R.id.slider_gen_marker_speed);
        TextView tvMarkerSpeed = dialogView.findViewById(R.id.tv_gen_marker_speed_value);
        MaterialAutoCompleteTextView actSound = dialogView.findViewById(R.id.act_gen_complete_sound);

        SwitchMaterial switchTypingSound = dialogView.findViewById(R.id.switch_gen_typing_sound);
        MaterialAutoCompleteTextView actTypingSoundStyle = dialogView.findViewById(R.id.act_gen_typing_sound_style);
        SwitchMaterial switchVibrate = dialogView.findViewById(R.id.switch_ai_reply_vibrate);
        Slider sliderIntensity = dialogView.findViewById(R.id.slider_vibrate_intensity);
        Slider sliderFrequency = dialogView.findViewById(R.id.slider_vibrate_frequency);
        TextView tvIntensityLabel = dialogView.findViewById(R.id.tv_vibrate_intensity_label);
        TextView tvFrequencyLabel = dialogView.findViewById(R.id.tv_vibrate_frequency_label);

        SPManager sp = SPManager.getInstance();

        // Make dropdowns always open on tap (some OEM ROMs + inputType=none may not open reliably)
        forceDropdownOnTap(actMarkerStyle);
        forceDropdownOnTap(actMarkerColor);
        forceDropdownOnTap(actMarkerLen);
        forceDropdownOnTap(actSound);
        forceDropdownOnTap(actTypingSoundStyle);

        // Sliders inside ScrollView: prevent parent from intercepting horizontal drags
        disallowParentInterceptOnTouch(sliderMarkerSpeed);
        disallowParentInterceptOnTouch(sliderIntensity);
        disallowParentInterceptOnTouch(sliderFrequency);
        disallowParentInterceptOnTouch(sliderTokenAutoRemoveDelay);

        switchEnabled.setChecked(sp.getGeneratingContentEnabled());
        if (switchInputRoleMarkerMaster != null) switchInputRoleMarkerMaster.setChecked(sp.getGeneratingContentInputRoleMarkerEnabled());
        if (switchInputRoleMarkerSuffix != null) switchInputRoleMarkerSuffix.setChecked(sp.getGeneratingContentInputRoleMarkerApplyToSuffixEnabled());
        if (switchInputRoleMarkerPrefix != null) switchInputRoleMarkerPrefix.setChecked(sp.getGeneratingContentInputRoleMarkerApplyToPrefixEnabled());
        if (switchGenToast != null) switchGenToast.setChecked(sp.getGeneratingContentToastEnabled());
        if (switchGenDynamicPrefix != null) switchGenDynamicPrefix.setChecked(sp.getGeneratingContentDynamicPrefixEnabled());
        if (switchGenTokenBurner != null) switchGenTokenBurner.setChecked(sp.getGeneratingContentTokenBurnerEnabled());
        if (switchGenStandaloneFloat != null) switchGenStandaloneFloat.setChecked(sp.getGeneratingContentStandaloneFloatEnabled());
        etPrefix.setText(sp.getGeneratingContentPrefix());
        etSuffix.setText(sp.getGeneratingContentSuffixAfterCursor());
        if (switchThinkingElapsed != null) switchThinkingElapsed.setChecked(sp.getGeneratingContentThinkingElapsedEnabled());
        if (switchReplyTokenCounter != null) switchReplyTokenCounter.setChecked(sp.getGeneratingContentReplyTokenCounterEnabled());
        if (switchReplyTokenCounterAutoRemove != null) switchReplyTokenCounterAutoRemove.setChecked(sp.getGeneratingContentReplyTokenCounterAutoRemove());

        // Auto-remove delay slider init
        try {
            long dms = sp.getGeneratingContentReplyTokenCounterAutoRemoveDelayMs();
            int sec = (int) Math.max(0L, Math.min(10L, dms / 1000L));
            if (sliderTokenAutoRemoveDelay != null) sliderTokenAutoRemoveDelay.setValue(sec);
            if (tvTokenAutoRemoveDelay != null) tvTokenAutoRemoveDelay.setText(sec + "s");
        } catch (Throwable ignored) {}

        if (switchTypingSound != null) switchTypingSound.setChecked(sp.getGeneratingContentTypingSoundEnabled());
        switchVibrate.setChecked(sp.getAiReplyVibrateEnabled());
        sliderIntensity.setValue(sp.getAiReplyVibrateIntensityPercent());
        sliderFrequency.setValue(sp.getAiReplyVibrateFrequencyPercent());

        // ===== Dropdown: Marker Style =====
        final int[] styleValues = new int[] {
                SPManager.GEN_MARKER_STYLE_PLAIN,
                SPManager.GEN_MARKER_STYLE_COLOR_TAG,
                SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM
        };
        final String[] styleLabels = new String[] {
                getString(R.string.ui_generating_content_marker_style_plain),
                getString(R.string.ui_generating_content_marker_style_color),
                getString(R.string.ui_generating_content_marker_style_rainbow)
        };
        ArrayAdapter<String> styleAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, styleLabels);
        actMarkerStyle.setAdapter(styleAdapter);

        // ===== Dropdown: Marker Color =====
        final int[] colorValues = new int[] {
                SPManager.GEN_MARKER_COLOR_BLUE,
                SPManager.GEN_MARKER_COLOR_RED,
                SPManager.GEN_MARKER_COLOR_GREEN,
                SPManager.GEN_MARKER_COLOR_YELLOW,
                SPManager.GEN_MARKER_COLOR_PURPLE,
                SPManager.GEN_MARKER_COLOR_RANDOM
        };
        final String[] colorLabels = new String[] {
                "🟦 " + getString(R.string.ui_generating_content_marker_color_blue),
                "🟥 " + getString(R.string.ui_generating_content_marker_color_red),
                "🟩 " + getString(R.string.ui_generating_content_marker_color_green),
                "🟨 " + getString(R.string.ui_generating_content_marker_color_yellow),
                "🟪 " + getString(R.string.ui_generating_content_marker_color_purple),
                "🎲 " + getString(R.string.ui_generating_content_marker_color_random)
        };
        ArrayAdapter<String> colorAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, colorLabels);
        actMarkerColor.setAdapter(colorAdapter);

        // ===== Dropdown: Completion Sound =====
        final int[] soundValues = new int[] {
                SPManager.GEN_SOUND_NONE,
                SPManager.GEN_SOUND_SYSTEM_NOTIFICATION,
                SPManager.GEN_SOUND_BEEP,
                SPManager.GEN_SOUND_CLICK
        };
        final String[] soundLabels = new String[] {
                getString(R.string.ui_generating_content_sound_none),
                getString(R.string.ui_generating_content_sound_system),
                getString(R.string.ui_generating_content_sound_beep),
                getString(R.string.ui_generating_content_sound_click)
        };
        ArrayAdapter<String> soundAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, soundLabels);
        actSound.setAdapter(soundAdapter);

        // ===== Dropdown: Typing Sound Style =====
        final int[] typingSoundStyleValues = new int[] {
                SPManager.GEN_TYPING_SOUND_STYLE_CLICK,
                SPManager.GEN_TYPING_SOUND_STYLE_SOFT_TICK,
                SPManager.GEN_TYPING_SOUND_STYLE_MECH_KEY,
                SPManager.GEN_TYPING_SOUND_STYLE_PULSE,
                SPManager.GEN_TYPING_SOUND_STYLE_BEEP
        };
        final String[] typingSoundStyleLabels = new String[] {
                "点击声（轻）",
                "滴答（柔和）",
                "机械键（清脆）",
                "神经脉冲（短促）",
                "蜂鸣（极简）"
        };
        ArrayAdapter<String> typingSoundStyleAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, typingSoundStyleLabels);
        if (actTypingSoundStyle != null) actTypingSoundStyle.setAdapter(typingSoundStyleAdapter);

        // ===== Dropdown: Rainbow Length =====
        final int[] lenValues = new int[] { 3, 6, 10 };
        final String[] lenLabels = new String[] { "3", "6", "10" };
        ArrayAdapter<String> lenAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, lenLabels);
        actMarkerLen.setAdapter(lenAdapter);

        // ===== Slider: Rainbow Speed (percent) =====
        Runnable updateMarkerSpeedLabel = () -> {
            if (tvMarkerSpeed != null) {
                tvMarkerSpeed.setText(Math.round(sliderMarkerSpeed.getValue()) + "%");
            }
        };
        updateMarkerSpeedLabel.run();
        sliderMarkerSpeed.addOnChangeListener((slider, value, fromUser) -> updateMarkerSpeedLabel.run());


        // ===== Preselect dropdowns =====
        int currentStyle = sp.getGeneratingContentMarkerStyle();
        for (int i = 0; i < styleValues.length; i++) {
            if (styleValues[i] == currentStyle) {
                actMarkerStyle.setText(styleLabels[i], false);
                break;
            }
        }

        int currentColor = sp.getGeneratingContentMarkerColor();
        for (int i = 0; i < colorValues.length; i++) {
            if (colorValues[i] == currentColor) {
                actMarkerColor.setText(colorLabels[i], false);
                break;
            }
        }

        int currentSound = sp.getGeneratingContentCompleteSound();
        for (int i = 0; i < soundValues.length; i++) {
            if (soundValues[i] == currentSound) {
                actSound.setText(soundLabels[i], false);
                break;
            }
        }

        if (actTypingSoundStyle != null) {
            int currentTypingStyle = sp.getGeneratingContentTypingSoundStyle();
            for (int i = 0; i < typingSoundStyleValues.length; i++) {
                if (typingSoundStyleValues[i] == currentTypingStyle) {
                    actTypingSoundStyle.setText(typingSoundStyleLabels[i], false);
                    break;
                }
            }
            if (actTypingSoundStyle.getText() == null || actTypingSoundStyle.getText().length() == 0) {
                actTypingSoundStyle.setText(typingSoundStyleLabels[0], false);
            }
        }

        int currentLen = sp.getGeneratingContentMarkerAnimLength();
        for (int i = 0; i < lenValues.length; i++) {
            if (lenValues[i] == currentLen) {
                actMarkerLen.setText(lenLabels[i], false);
                break;
            }
        }

        sliderMarkerSpeed.setValue(sp.getGeneratingContentMarkerAnimSpeedPercent());
        updateMarkerSpeedLabel.run();

	// ===== Live enable/disable =====
        Runnable updateTokenCounterControlsEnabled = () -> {
            boolean on = switchReplyTokenCounter != null && switchReplyTokenCounter.isChecked();
            boolean autoOn = switchReplyTokenCounterAutoRemove != null && switchReplyTokenCounterAutoRemove.isChecked();
            if (switchReplyTokenCounterAutoRemove != null) {
                switchReplyTokenCounterAutoRemove.setEnabled(on);
                switchReplyTokenCounterAutoRemove.setAlpha(on ? 1f : 0.4f);
            }

            // Delay slider is only meaningful when token counter is enabled + auto remove is enabled.
            if (layoutTokenAutoRemoveDelay != null) {
                layoutTokenAutoRemoveDelay.setVisibility((on && autoOn) ? View.VISIBLE : View.GONE);
            }
        };
        updateTokenCounterControlsEnabled.run();
        if (switchReplyTokenCounter != null) {
            switchReplyTokenCounter.setOnCheckedChangeListener((buttonView, isChecked) -> updateTokenCounterControlsEnabled.run());
        }

        if (switchReplyTokenCounterAutoRemove != null) {
            switchReplyTokenCounterAutoRemove.setOnCheckedChangeListener((buttonView, isChecked) -> updateTokenCounterControlsEnabled.run());
        }

        // Delay label live update
        if (sliderTokenAutoRemoveDelay != null) {
            sliderTokenAutoRemoveDelay.addOnChangeListener((slider, value, fromUser) -> {
                if (tvTokenAutoRemoveDelay != null) {
                    tvTokenAutoRemoveDelay.setText(Math.round(value) + "s");
                }
            });
        }

        Runnable updateTypingSoundStyleEnabled = () -> {
            boolean on = switchTypingSound != null && switchTypingSound.isChecked();
            if (actTypingSoundStyle != null) {
                actTypingSoundStyle.setEnabled(on);
                actTypingSoundStyle.setAlpha(on ? 1f : 0.4f);
            }
        };
        updateTypingSoundStyleEnabled.run();
        if (switchTypingSound != null) {
            switchTypingSound.setOnCheckedChangeListener((buttonView, isChecked) -> updateTypingSoundStyleEnabled.run());
        }

        Runnable updateVibrateEnabled = () -> {
            boolean on = switchVibrate.isChecked();
            sliderIntensity.setEnabled(on);
            sliderFrequency.setEnabled(on);
            tvIntensityLabel.setAlpha(on ? 1f : 0.4f);
            tvFrequencyLabel.setAlpha(on ? 1f : 0.4f);
        };
        updateVibrateEnabled.run();
        switchVibrate.setOnCheckedChangeListener((buttonView, isChecked) -> updateVibrateEnabled.run());

        Runnable updateVibrateLabels = () -> {
            tvIntensityLabel.setText(getString(R.string.ui_ai_reply_vibrate_intensity) + "（" + Math.round(sliderIntensity.getValue()) + "%）");
            tvFrequencyLabel.setText(getString(R.string.ui_ai_reply_vibrate_frequency) + "（" + Math.round(sliderFrequency.getValue()) + "%）");
        };
        updateVibrateLabels.run();
        sliderIntensity.addOnChangeListener((slider, value, fromUser) -> updateVibrateLabels.run());
        sliderFrequency.addOnChangeListener((slider, value, fromUser) -> updateVibrateLabels.run());

        // Marker controls
        Runnable updateMarkerControlsEnabled = () -> {
            String styleText = actMarkerStyle.getText() == null ? "" : actMarkerStyle.getText().toString();
            int selected = styleValues[0];
            for (int i = 0; i < styleLabels.length; i++) {
                if (styleLabels[i].equals(styleText)) {
                    selected = styleValues[i];
                    break;
                }
            }

            boolean isPlain = selected == SPManager.GEN_MARKER_STYLE_PLAIN;
            boolean isRainbow = selected == SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM;

            // Color is meaningful for non-plain modes
            actMarkerColor.setEnabled(!isPlain);
            actMarkerColor.setAlpha(isPlain ? 0.4f : 1f);

            // Rainbow-only controls
            actMarkerLen.setEnabled(isRainbow);
            actMarkerLen.setAlpha(isRainbow ? 1f : 0.4f);
            sliderMarkerSpeed.setEnabled(isRainbow);
            sliderMarkerSpeed.setAlpha(isRainbow ? 1f : 0.4f);
            if (tvMarkerSpeed != null) tvMarkerSpeed.setAlpha(isRainbow ? 1f : 0.4f);
        };
        updateMarkerControlsEnabled.run();
        actMarkerStyle.setOnItemClickListener((parent, view, position, id) -> updateMarkerControlsEnabled.run());

        // ===== Input-box dynamic role marker (plain text) =====
        // Master switch + per-position switches (after-cursor / before-cursor). Users can freely enable either side.
        Runnable updateRoleMarkerUiEnabled = () -> {
            boolean masterOn = switchInputRoleMarkerMaster != null && switchInputRoleMarkerMaster.isChecked();
            if (switchInputRoleMarkerSuffix != null) {
                switchInputRoleMarkerSuffix.setEnabled(masterOn);
                switchInputRoleMarkerSuffix.setAlpha(masterOn ? 1f : 0.4f);
            }
            if (switchInputRoleMarkerPrefix != null) {
                switchInputRoleMarkerPrefix.setEnabled(masterOn);
                switchInputRoleMarkerPrefix.setAlpha(masterOn ? 1f : 0.4f);
            }
        };
        updateRoleMarkerUiEnabled.run();

        if (switchInputRoleMarkerMaster != null) {
            switchInputRoleMarkerMaster.setOnCheckedChangeListener((buttonView, isChecked) -> updateRoleMarkerUiEnabled.run());
        }

androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
                .setTitle(getString(R.string.ui_generating_content_dialog_title))
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (di, which) -> {
                    sp.setGeneratingContentEnabled(switchEnabled.isChecked());
                    boolean roleMarkerMasterOn = switchInputRoleMarkerMaster != null && switchInputRoleMarkerMaster.isChecked();
                    sp.setGeneratingContentInputRoleMarkerEnabled(roleMarkerMasterOn);
                    if (switchInputRoleMarkerSuffix != null) sp.setGeneratingContentInputRoleMarkerApplyToSuffixEnabled(switchInputRoleMarkerSuffix.isChecked());
                    if (switchInputRoleMarkerPrefix != null) sp.setGeneratingContentInputRoleMarkerApplyToPrefixEnabled(switchInputRoleMarkerPrefix.isChecked());
                    if (switchGenToast != null) sp.setGeneratingContentToastEnabled(switchGenToast.isChecked());
                    if (switchGenDynamicPrefix != null) sp.setGeneratingContentDynamicPrefixEnabled(switchGenDynamicPrefix.isChecked());
                    if (switchGenTokenBurner != null) sp.setGeneratingContentTokenBurnerEnabled(switchGenTokenBurner.isChecked());
                    if (switchGenStandaloneFloat != null) sp.setGeneratingContentStandaloneFloatEnabled(switchGenStandaloneFloat.isChecked());
                    sp.setGeneratingContentPrefix(etPrefix.getText() == null ? "" : etPrefix.getText().toString());
                    sp.setGeneratingContentSuffixAfterCursor(etSuffix.getText() == null ? "" : etSuffix.getText().toString());
                    if (switchThinkingElapsed != null) sp.setGeneratingContentThinkingElapsedEnabled(switchThinkingElapsed.isChecked());
                    if (switchReplyTokenCounter != null) sp.setGeneratingContentReplyTokenCounterEnabled(switchReplyTokenCounter.isChecked());
                    if (switchReplyTokenCounterAutoRemove != null) sp.setGeneratingContentReplyTokenCounterAutoRemove(switchReplyTokenCounterAutoRemove.isChecked());

                    // Save auto-remove delay (0-10s)
                    if (sliderTokenAutoRemoveDelay != null) {
                        long dms = (long) (Math.round(sliderTokenAutoRemoveDelay.getValue()) * 1000L);
                        sp.setGeneratingContentReplyTokenCounterAutoRemoveDelayMs(dms);
                    }

                    sp.setAiReplyVibrateEnabled(switchVibrate.isChecked());
                    sp.setAiReplyVibrateIntensityPercent(Math.round(sliderIntensity.getValue()));
                    sp.setAiReplyVibrateFrequencyPercent(Math.round(sliderFrequency.getValue()));

                    int selectedStyle = styleValues[0];
                    String styleSel = actMarkerStyle.getText() == null ? "" : actMarkerStyle.getText().toString();
                    for (int i = 0; i < styleLabels.length; i++) {
                        if (styleLabels[i].equals(styleSel)) {
                            selectedStyle = styleValues[i];
                            break;
                        }
                    }
                    sp.setGeneratingContentMarkerStyle(selectedStyle);

                    int selectedLen = sp.getGeneratingContentMarkerAnimLength();
                    String lenSel = actMarkerLen.getText() == null ? "" : actMarkerLen.getText().toString();
                    for (int i = 0; i < lenLabels.length; i++) {
                        if (lenLabels[i].equals(lenSel)) {
                            selectedLen = lenValues[i];
                            break;
                        }
                    }
                    sp.setGeneratingContentMarkerAnimLength(selectedLen);
                    sp.setGeneratingContentMarkerAnimSpeedPercent(Math.round(sliderMarkerSpeed.getValue()));

                    int selectedColor = colorValues[0];
                    String colorSel = actMarkerColor.getText() == null ? "" : actMarkerColor.getText().toString();
                    for (int i = 0; i < colorLabels.length; i++) {
                        if (colorLabels[i].equals(colorSel)) {
                            selectedColor = colorValues[i];
                            break;
                        }
                    }
                    sp.setGeneratingContentMarkerColor(selectedColor);

                    int selectedSound = soundValues[0];
                    String soundSel = actSound.getText() == null ? "" : actSound.getText().toString();
                    for (int i = 0; i < soundLabels.length; i++) {
                        if (soundLabels[i].equals(soundSel)) {
                            selectedSound = soundValues[i];
                            break;
                        }
                    }
                    sp.setGeneratingContentCompleteSound(selectedSound);
                    if (switchTypingSound != null) sp.setGeneratingContentTypingSoundEnabled(switchTypingSound.isChecked());
                    if (actTypingSoundStyle != null) {
                        int selectedTypingStyle = typingSoundStyleValues[0];
                        String typingSel = actTypingSoundStyle.getText() == null ? "" : actTypingSoundStyle.getText().toString();
                        for (int i = 0; i < typingSoundStyleLabels.length; i++) {
                            if (typingSoundStyleLabels[i].equals(typingSel)) {
                                selectedTypingStyle = typingSoundStyleValues[i];
                                break;
                            }
                        }
                        sp.setGeneratingContentTypingSoundStyle(selectedTypingStyle);
                    }

                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.show();

    }

    /**
     * Some ROMs + TextInputLayout combinations may not reliably open the dropdown when
     * MaterialAutoCompleteTextView uses inputType="none". Force opening on tap/focus.
     */
    private void forceDropdownOnTap(MaterialAutoCompleteTextView act) {
        if (act == null) return;
        act.setThreshold(0);
        act.setOnClickListener(v -> act.showDropDown());
        act.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                act.showDropDown();
            }
        });
    }

    /**
     * Ensure sliders inside ScrollView remain draggable (prevent parent from hijacking horizontal drag).
     */
    private void disallowParentInterceptOnTouch(View v) {
        if (v == null) return;
        v.setOnTouchListener((view, event) -> {
            if (view.getParent() != null) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
    }

    private boolean getSafeStreamingEnabled() {
        try {
            return SPManager.getInstance().getStreamingOutputEnabled();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private int getSafeStreamingMode() {
        try {
            return SPManager.getInstance().getStreamingOutputMode();
        } catch (Throwable ignored) {
        }
        return SPManager.STREAM_MODE_AUTO;
    }

    private int getSafeStreamingGranularity() {
        try {
            return SPManager.getInstance().getStreamingOutputGranularity();
        } catch (Throwable ignored) {
        }
        return SPManager.STREAM_GRANULARITY_CHARS;
    }

    private int getSafeStreamingSpeedAlgorithm() {
        try {
            return SPManager.getInstance().getStreamingOutputSpeedAlgorithm();
        } catch (Throwable ignored) {
        }
        return SPManager.STREAM_SPEED_ALGO_LINEAR;
    }

    private String getLevelLabel(int level, int defaultValue) {
        int v = level;
        if (v < 0) v = 0;
        if (v > 20) v = 20;

        String label = String.valueOf(v);
        if (v == defaultValue) {
            label = label + " " + getString(R.string.ui_default_value);
        }
        return label;
    }

    private SharedPreferences getUiAnimPrefs() {
        Context ctx = getContext();
        if (ctx == null) return null;
        try {
            return ctx.getSharedPreferences(PREFS_UI_ANIM, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int clampPercent(int percent) {
        int p = percent;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        return p;
    }

    private int getUiAnimInt(String key, int defValue) {
        try {
            SharedPreferences sp = getUiAnimPrefs();
            if (sp == null) return defValue;
            return sp.getInt(key, defValue);
        } catch (Throwable ignored) {
            return defValue;
        }
    }

    private boolean getUiAnimBool(String key, boolean defValue) {
        try {
            SharedPreferences sp = getUiAnimPrefs();
            if (sp == null) return defValue;
            return sp.getBoolean(key, defValue);
        } catch (Throwable ignored) {
            return defValue;
        }
    }

    private void putUiAnimInt(String key, int value) {
        try {
            SharedPreferences sp = getUiAnimPrefs();
            if (sp != null) sp.edit().putInt(key, value).apply();
        } catch (Throwable ignored) {}
    }

    private void putUiAnimBool(String key, boolean value) {
        try {
            SharedPreferences sp = getUiAnimPrefs();
            if (sp != null) sp.edit().putBoolean(key, value).apply();
        } catch (Throwable ignored) {}
    }

    private int getSavedMemoryAnimMode() {
        int mode = getUiAnimInt(PREF_MEMORY_ANIM_MODE, MEMORY_ANIM_MODE_SEQ_LOOP);
        if (mode < 0 || mode > 2) mode = MEMORY_ANIM_MODE_SEQ_LOOP;
        return mode;
    }

    private boolean getSavedMemoryAnimEnabled() {
        return getUiAnimBool(PREF_MEMORY_ANIM_ENABLED, true);
    }

    private int getSavedMemoryAnimSpeedPercent() {
        return clampPercent(getUiAnimInt(PREF_MEMORY_ANIM_SPEED, DEFAULT_UI_ANIM_SPEED_PERCENT));
    }

    private int getSavedNormalThinkingAnimMode() {
        int mode = getUiAnimInt(PREF_NORMAL_THINK_ANIM_MODE, NormalModelThinkingOptionAdapter.ANIM_MODE_SEQ_LOOP);
        if (mode < 0 || mode > 2) mode = NormalModelThinkingOptionAdapter.ANIM_MODE_SEQ_LOOP;
        return mode;
    }

    private boolean getSavedNormalThinkingAnimEnabled() {
        return getUiAnimBool(PREF_NORMAL_THINK_ANIM_ENABLED, true);
    }

    private int getSavedNormalThinkingAnimSpeedPercent() {
        return clampPercent(getUiAnimInt(PREF_NORMAL_THINK_ANIM_SPEED, DEFAULT_UI_ANIM_SPEED_PERCENT));
    }


    private int getSavedReasoningThinkingAnimMode() {
        int mode = getUiAnimInt(PREF_REASONING_THINK_ANIM_MODE, ReasoningModelThinkingOptionAdapter.ANIM_MODE_SEQ_LOOP);
        if (mode < ReasoningModelThinkingOptionAdapter.ANIM_MODE_SEQ_LOOP || mode > ReasoningModelThinkingOptionAdapter.ANIM_MODE_NEURAL_PULSE) {
            mode = ReasoningModelThinkingOptionAdapter.ANIM_MODE_SEQ_LOOP;
        }
        return mode;
    }

    private boolean getSavedReasoningThinkingAnimEnabled() {
        return getUiAnimBool(PREF_REASONING_THINK_ANIM_ENABLED, true);
    }

    private int getSavedReasoningThinkingAnimSpeedPercent() {
        return clampPercent(getUiAnimInt(PREF_REASONING_THINK_ANIM_SPEED, DEFAULT_UI_ANIM_SPEED_PERCENT));
    }

    private String getAnimSpeedPercentLabel(int percent) {
        return clampPercent(percent) + "%（周期倍率）";
    }

    private long scaleAnimDurationByPercent(long baseMs, int speedPercent, long minMs, long maxMs) {
        long base = Math.max(1L, baseMs);
        int p = clampPercent(speedPercent);
        // 100% = base speed, 0% = slower (~2x duration)
        float scale = 2.0f - (p / 100f);
        long out = (long) (base * scale);
        if (minMs > 0 && out < minMs) out = minMs;
        if (maxMs > 0 && out > maxMs) out = maxMs;
        return out;
    }

    private String getMemoryTierShortLabel(int rounds) {
        int n = rounds;
        if (n < 0) n = 0;
        if (n > 20) n = 20;
        if (n == 0) return "无上下文";
        if (n <= 5) return "冷静";
        if (n <= 10) return "常规";
        if (n <= 15) return "进阶";
        return "高温";
    }

    private String getMemoryTierOuterLabel(int rounds) {
        int n = rounds;
        if (n < 0) n = 0;
        if (n > 20) n = 20;
        if (n == 0) return "记忆抹除(无上下文)";
        if (n <= 3) return "极简快答(轻量低耗)";
        if (n <= 6) return "短期记忆(自然流畅)";
        if (n <= 9) return "常规沟通(标准模式)";
        if (n <= 12) return "日常活跃(进阶推演)";
        if (n <= 15) return "深度连贯(逻辑串联)";
        if (n <= 17) return "算力燃烧(长篇解析)";
        if (n <= 19) return "极限临界(幻觉风险)";
        return "记忆宫殿(宇宙边界)";
    }

    private void updateConversationMemoryRowSummary(@Nullable TextView tvValue,
                                                    @Nullable ProgressBar pb,
                                                    int rounds) {
        int n = rounds;
        if (n < 0) n = 0;
        if (n > 20) n = 20;
        if (tvValue != null) {
            tvValue.setText(n + "轮·" + getMemoryTierOuterLabel(n));
            try {
                tvValue.setMaxLines(2);
                tvValue.setEllipsize(TextUtils.TruncateAt.END);
            } catch (Throwable ignored) {}
        }
        if (pb != null) {
            try {
                pb.setMax(20);
                pb.setProgress(n);
            } catch (Throwable ignored) {}
            try {
                int color = getMemorySpectrumColor(n);
                pb.setProgressTintList(ColorStateList.valueOf(color));
                pb.setIndeterminateTintList(ColorStateList.valueOf(color));
                int track = 0xFFCAD4DB;
                pb.setProgressBackgroundTintList(ColorStateList.valueOf(track));
            } catch (Throwable ignored) {}
        }
    }



    // ===== AI Trigger multiline send (syncs with AI Triggers switch) =====
    private void refreshAiMultilineSendRow() {
        try {
            if (tvAiMultilineSendValue == null) return;
            if (!SPManager.isReady()) return;
            int mode = SPManager.getInstance().getAiTriggerMultilineMode();
            tvAiMultilineSendValue.setText(getAiMultilineModeValueLabel(mode));
        } catch (Throwable ignored) {
        }
    }

    private String getAiMultilineModeValueLabel(int mode) {
        try {
            if (mode == SPManager.AI_TRIGGER_MULTILINE_OFF) return getString(R.string.ui_ai_multiline_value_off);
            if (mode == SPManager.AI_TRIGGER_MULTILINE_KEEP_NEWLINES) return getString(R.string.ui_ai_multiline_value_keep);
            if (mode == SPManager.AI_TRIGGER_MULTILINE_NEWLINES_TO_SPACES) return getString(R.string.ui_ai_multiline_value_space);
            if (mode == SPManager.AI_TRIGGER_MULTILINE_REMOVE_NEWLINES) return getString(R.string.ui_ai_multiline_value_remove);
        } catch (Throwable ignored) {}
        return getString(R.string.ui_ai_multiline_value_keep);
    }

    private void showAiMultilineSendSettingsDialog() {
        if (!isAdded() || getContext() == null) return;
        if (!SPManager.isReady()) return;
        SPManager sp = SPManager.getInstance();
        int cur = sp.getAiTriggerMultilineMode();

        final String[] items = new String[]{
                getString(R.string.ui_ai_multiline_mode_off),
                getString(R.string.ui_ai_multiline_mode_keep),
                getString(R.string.ui_ai_multiline_mode_space),
                getString(R.string.ui_ai_multiline_mode_remove)
        };

        int checked = cur;
        if (checked < 0 || checked >= items.length) checked = SPManager.AI_TRIGGER_MULTILINE_KEEP_NEWLINES;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_ai_multiline_dialog_title)
                .setMessage(R.string.ui_ai_multiline_dialog_message)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    try {
                        sp.setAiTriggerMultilineMode(which);
                    } catch (Throwable ignored) {}
                    refreshAiMultilineSendRow();
                    try {
                        Toast.makeText(requireContext(), items[which], Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showConversationMemoryConsoleDialog(int currentValue,
                                                   int defaultValue,
                                                   IntConsumer onSelected) {
        if (getContext() == null) return;

        int cur = currentValue;
        if (cur < 0) cur = 0;
        if (cur > 20) cur = 20;
        final int maxRounds = 20;
        final int warningThreshold = 16;

        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_conversation_memory_console, null, false);
        TextView tvStatus = content.findViewById(R.id.tv_memory_status);
        LinearLayout llBlocks = content.findViewById(R.id.ll_memory_blocks);
        android.widget.FrameLayout flBlocksContainer = content.findViewById(R.id.fl_memory_blocks_container);
        View vSweepLight = content.findViewById(R.id.v_sweep_light);
        android.widget.SeekBar seekBar = content.findViewById(R.id.seek_memory_rounds);
        TextView tvRight = content.findViewById(R.id.tv_memory_right_label);
        TextView tvEst = content.findViewById(R.id.tv_token_estimation);
        TextView tvDefaultHint = content.findViewById(R.id.tv_memory_default_hint);
        TextView tvCurrentValue = content.findViewById(R.id.tv_current_value);
        TextView tvWarn = content.findViewById(R.id.tv_attention_warning);
        android.widget.Button btnAnimMode = content.findViewById(R.id.btn_memory_animation_mode);
        android.widget.Button btnAnimToggle = content.findViewById(R.id.btn_memory_animation_toggle);
        TextView tvAnimSpeedValue = content.findViewById(R.id.tv_memory_anim_speed_value);
        android.widget.SeekBar seekAnimSpeed = content.findViewById(R.id.seek_memory_anim_speed);

        if (seekBar != null) seekBar.setMax(maxRounds);
        if (tvRight != null) tvRight.setText(maxRounds + " 轮");
        if (tvDefaultHint != null) tvDefaultHint.setText("默认值：" + defaultValue + " 轮");

        final int[] selected = new int[]{cur};
        final int[] animMode = new int[]{getSavedMemoryAnimMode()};
        final boolean[] animEnabled = new boolean[]{getSavedMemoryAnimEnabled()};
        final int[] animSpeedPercent = new int[]{getSavedMemoryAnimSpeedPercent()};
        final ValueAnimator[] fillAnimatorRef = new ValueAnimator[1];
        final ValueAnimator[] sweepAnimatorRef = new ValueAnimator[1];

        Runnable refreshAnimControlTexts = () -> {
            if (btnAnimMode != null) {
                btnAnimMode.setText("动画选择：" + getMemoryAnimationModeLabel(animMode[0]));
            }
            if (btnAnimToggle != null) {
                btnAnimToggle.setText(animEnabled[0] ? "关闭动画" : "开启动画");
            }
            if (tvAnimSpeedValue != null) {
                tvAnimSpeedValue.setText(getAnimSpeedPercentLabel(animSpeedPercent[0]));
            }
            if (seekAnimSpeed != null && seekAnimSpeed.getProgress() != clampPercent(animSpeedPercent[0])) {
                seekAnimSpeed.setProgress(clampPercent(animSpeedPercent[0]));
            }
        };

        Runnable renderStatic = () -> {
            updateMemoryConsoleLabels(tvStatus, tvEst, tvWarn, tvCurrentValue, selected[0], warningThreshold);
            cancelMemoryFillAnimator(fillAnimatorRef);
            cancelMemorySweepAnimator(sweepAnimatorRef);
            renderMemoryBlockMatrix(llBlocks, selected[0], getMemorySpectrumColor(selected[0]), maxRounds);
            setSweepLightHidden(vSweepLight);
        };

        final Runnable[] restartVisualAnimatorRef = new Runnable[1];
        restartVisualAnimatorRef[0] = () -> {
            cancelMemoryFillAnimator(fillAnimatorRef);
            cancelMemorySweepAnimator(sweepAnimatorRef);
            if (!animEnabled[0]) {
                renderMemoryBlockMatrix(llBlocks, selected[0], getMemorySpectrumColor(selected[0]), maxRounds);
                setSweepLightHidden(vSweepLight);
                return;
            }
            startMemoryVisualAnimatorByMode(fillAnimatorRef,
                    sweepAnimatorRef,
                    llBlocks,
                    flBlocksContainer,
                    vSweepLight,
                    selected[0],
                    maxRounds,
                    animMode[0],
                    animSpeedPercent[0]);
        };

        updateMemoryConsoleLabels(tvStatus, tvEst, tvWarn, tvCurrentValue, selected[0], warningThreshold);
        renderMemoryBlockMatrix(llBlocks, selected[0], getMemorySpectrumColor(selected[0]), maxRounds);
        setSweepLightHidden(vSweepLight);
        refreshAnimControlTexts.run();

        if (btnAnimMode != null) {
            btnAnimMode.setOnClickListener(v -> {
                final String[] items = new String[]{
                        "逐格充能循环",
                        "三段式机械节",
                        "流光扫掠"
                };
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("动画选择")
                        .setSingleChoiceItems(items, animMode[0], (dialog, which) -> {
                            if (which < 0 || which > 2) return;
                            animMode[0] = which;
                            putUiAnimInt(PREF_MEMORY_ANIM_MODE, animMode[0]);
                            refreshAnimControlTexts.run();
                            if (animEnabled[0]) {
                                restartVisualAnimatorRef[0].run();
                            } else {
                                renderStatic.run();
                            }
                            dialog.dismiss();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }

        if (btnAnimToggle != null) {
            btnAnimToggle.setOnClickListener(v -> {
                animEnabled[0] = !animEnabled[0];
                putUiAnimBool(PREF_MEMORY_ANIM_ENABLED, animEnabled[0]);
                refreshAnimControlTexts.run();
                if (animEnabled[0]) {
                    restartVisualAnimatorRef[0].run();
                } else {
                    renderStatic.run();
                }
            });
        }

        if (seekAnimSpeed != null) {
            seekAnimSpeed.setMax(100);
            seekAnimSpeed.setProgress(clampPercent(animSpeedPercent[0]));
            disallowParentInterceptOnTouch(seekAnimSpeed);
            seekAnimSpeed.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    animSpeedPercent[0] = clampPercent(progress);
                    if (tvAnimSpeedValue != null) tvAnimSpeedValue.setText(getAnimSpeedPercentLabel(animSpeedPercent[0]));
                    putUiAnimInt(PREF_MEMORY_ANIM_SPEED, animSpeedPercent[0]);
                }

                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) { }

                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    if (animEnabled[0]) {
                        restartVisualAnimatorRef[0].run();
                    }
                }
            });
        }

        if (seekBar != null) {
            seekBar.setProgress(cur);
            seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    if (progress < 0) progress = 0;
                    if (progress > maxRounds) progress = maxRounds;
                    selected[0] = progress;
                    updateMemoryConsoleLabels(tvStatus, tvEst, tvWarn, tvCurrentValue, selected[0], warningThreshold);
                    if (fromUser) {
                        cancelMemoryFillAnimator(fillAnimatorRef);
                        cancelMemorySweepAnimator(sweepAnimatorRef);
                        renderMemoryBlockMatrix(llBlocks, selected[0], getMemorySpectrumColor(selected[0]), maxRounds);
                        setSweepLightHidden(vSweepLight);
                    }
                }

                @Override
                public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                    cancelMemoryFillAnimator(fillAnimatorRef);
                    cancelMemorySweepAnimator(sweepAnimatorRef);
                }

                @Override
                public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                    if (animEnabled[0]) {
                        restartVisualAnimatorRef[0].run();
                    } else {
                        renderStatic.run();
                    }
                }
            });
        }

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_dialogue_memory)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> {
                    if (onSelected != null) onSelected.accept(selected[0]);
                })
                .create();
        dialog.setOnDismissListener(d -> {
            cancelMemoryFillAnimator(fillAnimatorRef);
            cancelMemorySweepAnimator(sweepAnimatorRef);
            refreshAiMultilineSendRow();

        restartSettingsRowPreviewAnimations();
        });
        dialog.show();

        if (animEnabled[0]) {
            restartVisualAnimatorRef[0].run();
        }
    }

    private void cancelMemoryFillAnimator(@Nullable ValueAnimator[] animatorRef) {
        if (animatorRef == null || animatorRef.length == 0) return;
        try {
            ValueAnimator animator = animatorRef[0];
            if (animator != null) {
                animator.cancel();
            }
        } catch (Throwable ignored) {
        }
        animatorRef[0] = null;
    }

    private void cancelMemorySweepAnimator(@Nullable ValueAnimator[] animatorRef) {
        if (animatorRef == null || animatorRef.length == 0) return;
        try {
            ValueAnimator animator = animatorRef[0];
            if (animator != null) {
                animator.cancel();
            }
        } catch (Throwable ignored) {
        }
        animatorRef[0] = null;
    }

    private void setSweepLightHidden(@Nullable View vSweepLight) {
        if (vSweepLight == null) return;
        try {
            vSweepLight.animate().cancel();
        } catch (Throwable ignored) {
        }
        try {
            vSweepLight.setTranslationX(0f);
            vSweepLight.setAlpha(0f);
            vSweepLight.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

    private String getMemoryAnimationModeLabel(int mode) {
        if (mode == MEMORY_ANIM_MODE_TRI_PHASE) return "三段机械节";
        if (mode == MEMORY_ANIM_MODE_SWEEP) return "流光扫掠";
        return "逐格充能";
    }

    private void startMemoryVisualAnimatorByMode(@Nullable ValueAnimator[] fillAnimatorRef,
                                                 @Nullable ValueAnimator[] sweepAnimatorRef,
                                                 @Nullable LinearLayout llBlocks,
                                                 @Nullable android.widget.FrameLayout flBlocksContainer,
                                                 @Nullable View vSweepLight,
                                                 int targetValue,
                                                 int maxRounds,
                                                 int mode,
                                                 int speedPercent) {
        if (mode == MEMORY_ANIM_MODE_SWEEP) {
            playMemorySweepLightAnimation(sweepAnimatorRef, llBlocks, flBlocksContainer, vSweepLight, targetValue, maxRounds, speedPercent);
            return;
        }
        setSweepLightHidden(vSweepLight);
        if (mode == MEMORY_ANIM_MODE_TRI_PHASE) {
            playTriPhaseMemoryBreathAnimation(fillAnimatorRef, llBlocks, targetValue, maxRounds, speedPercent);
        } else {
            playSequentialMemoryFillAnimation(fillAnimatorRef, llBlocks, targetValue, maxRounds, speedPercent);
        }
    }

    private void playSequentialMemoryFillAnimation(@Nullable ValueAnimator[] animatorRef,
                                                   @Nullable LinearLayout llBlocks,
                                                   int targetValue,
                                                   int maxRounds,
                                                   int speedPercent) {
        int target = targetValue;
        if (target < 0) target = 0;
        if (target > maxRounds) target = maxRounds;

        cancelMemoryFillAnimator(animatorRef);

        if (llBlocks == null || !isAdded()) {
            renderMemoryBlockMatrix(llBlocks, target, getMemorySpectrumColor(target), maxRounds);
            return;
        }

        if (target <= 0) {
            renderMemoryBlockMatrix(llBlocks, 0, getMemorySpectrumColor(0), maxRounds);
            return;
        }

        long duration = Math.max(800L, Math.min(2000L, target * 100L));
        ValueAnimator animator = ValueAnimator.ofInt(0, target);
        animator.setDuration(scaleAnimDurationByPercent(duration, speedPercent, 800L, 4000L));
        animator.setInterpolator(new DecelerateInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        final int finalTarget = target;
        final int finalActiveColor = getMemorySpectrumColor(finalTarget);
        animator.addUpdateListener(animation -> {
            if (!isAdded()) return;
            Object value = animation.getAnimatedValue();
            int animated = 0;
            if (value instanceof Integer) {
                animated = (Integer) value;
            }
            if (animated < 0) animated = 0;
            if (animated > finalTarget) animated = finalTarget;
            renderMemoryBlockMatrix(llBlocks, animated, finalActiveColor, maxRounds);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                if (isAdded()) {
                    renderMemoryBlockMatrix(llBlocks, 0, finalActiveColor, maxRounds);
                }
            }

            @Override
            public void onAnimationRepeat(android.animation.Animator animation) {
                if (isAdded()) {
                    renderMemoryBlockMatrix(llBlocks, 0, finalActiveColor, maxRounds);
                }
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                    animatorRef[0] = null;
                }
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                    animatorRef[0] = null;
                }
            }
        });
        if (animatorRef != null && animatorRef.length > 0) {
            animatorRef[0] = animator;
        }
        animator.start();
    }

    private void playTriPhaseMemoryBreathAnimation(@Nullable ValueAnimator[] animatorRef,
                                                   @Nullable LinearLayout llBlocks,
                                                   int targetValue,
                                                   int maxRounds,
                                                   int speedPercent) {
        int target = targetValue;
        if (target < 0) target = 0;
        if (target > maxRounds) target = maxRounds;

        cancelMemoryFillAnimator(animatorRef);

        if (llBlocks == null || !isAdded()) {
            renderMemoryBlockMatrix(llBlocks, target, getMemorySpectrumColor(target), maxRounds);
            return;
        }
        if (target <= 0) {
            renderMemoryBlockMatrix(llBlocks, 0, getMemorySpectrumColor(0), maxRounds);
            return;
        }

        final int finalTarget = target;
        final int finalActiveColor = getMemorySpectrumColor(finalTarget);
        final long duration = 1800L;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(scaleAnimDurationByPercent(duration, speedPercent, 1000L, 4500L));
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(animation -> {
            if (!isAdded()) return;
            float phase = 0f;
            Object value = animation.getAnimatedValue();
            if (value instanceof Float) {
                phase = (Float) value;
            }
            if (phase < 0f) phase = 0f;
            if (phase > 1f) phase = 1f;

            float ratio;
            if (phase <= 0.58f) {
                float t = phase / 0.58f;
                float eased = 1f - (1f - t) * (1f - t); // decelerate fill
                ratio = eased;
            } else if (phase <= 0.78f) {
                ratio = 1f; // hold
            } else {
                float t = (phase - 0.78f) / 0.22f; // 0..1
                if (t <= 0.45f) {
                    ratio = 1f - (0.18f * (t / 0.45f)); // fallback to ~82%
                } else {
                    float b = (t - 0.45f) / 0.55f;
                    ratio = 0.82f + (0.08f * (float) Math.sin(b * Math.PI)); // micro-breath
                }
            }

            int lit = Math.round(finalTarget * ratio);
            if (lit < 1) lit = 1;
            if (lit > finalTarget) lit = finalTarget;
            renderMemoryBlockMatrix(llBlocks, lit, finalActiveColor, maxRounds);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                if (isAdded()) {
                    renderMemoryBlockMatrix(llBlocks, 0, finalActiveColor, maxRounds);
                }
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                    animatorRef[0] = null;
                }
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                    animatorRef[0] = null;
                }
            }
        });
        if (animatorRef != null && animatorRef.length > 0) {
            animatorRef[0] = animator;
        }
        animator.start();
    }

    private void playMemorySweepLightAnimation(@Nullable ValueAnimator[] animatorRef,
                                               @Nullable LinearLayout llBlocks,
                                               @Nullable android.widget.FrameLayout flBlocksContainer,
                                               @Nullable View vSweepLight,
                                               int targetValue,
                                               int maxRounds,
                                               int speedPercent) {
        int target = targetValue;
        if (target < 0) target = 0;
        if (target > maxRounds) target = maxRounds;

        cancelMemorySweepAnimator(animatorRef);

        if (llBlocks == null || vSweepLight == null || !isAdded()) {
            renderMemoryBlockMatrix(llBlocks, target, getMemorySpectrumColor(target), maxRounds);
            setSweepLightHidden(vSweepLight);
            return;
        }

        renderMemoryBlockMatrix(llBlocks, target, getMemorySpectrumColor(target), maxRounds);

        if (target <= 0) {
            setSweepLightHidden(vSweepLight);
            return;
        }

        final int finalTarget = target;
        final int finalMaxRounds = Math.max(1, maxRounds);
        final View finalSweepLight = vSweepLight;
        llBlocks.post(() -> {
            if (!isAdded()) return;
            int blocksWidth = llBlocks.getWidth();
            if (blocksWidth <= 0 && flBlocksContainer != null) {
                blocksWidth = flBlocksContainer.getWidth();
            }
            if (blocksWidth <= 0) {
                setSweepLightHidden(finalSweepLight);
                return;
            }
            float activeWidth = (finalTarget / (float) finalMaxRounds) * blocksWidth;
            if (activeWidth <= 0f) {
                setSweepLightHidden(finalSweepLight);
                return;
            }
            finalSweepLight.setVisibility(View.VISIBLE);
            finalSweepLight.setAlpha(0.92f);
            int sweepWidthPx = finalSweepLight.getWidth();
            if (sweepWidthPx <= 0) {
                sweepWidthPx = dpToPx(20);
                try {
                    android.view.ViewGroup.LayoutParams lp = finalSweepLight.getLayoutParams();
                    if (lp != null) {
                        lp.width = sweepWidthPx;
                        finalSweepLight.setLayoutParams(lp);
                    }
                } catch (Throwable ignored) {
                }
            }
            float startX = -sweepWidthPx;
            float endX = Math.max(0f, activeWidth);
            ValueAnimator animator = ValueAnimator.ofFloat(startX, endX);
            animator.setDuration(scaleAnimDurationByPercent(1400L, speedPercent, 700L, 4000L));
            animator.setInterpolator(new android.view.animation.LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.addUpdateListener(animation -> {
                if (!isAdded()) return;
                Object value = animation.getAnimatedValue();
                float x = startX;
                if (value instanceof Float) {
                    x = (Float) value;
                }
                finalSweepLight.setTranslationX(x);
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    setSweepLightHidden(finalSweepLight);
                    if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                        animatorRef[0] = null;
                    }
                }

                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (animatorRef != null && animatorRef.length > 0 && animatorRef[0] == animation) {
                        animatorRef[0] = null;
                    }
                }
            });
            if (animatorRef != null && animatorRef.length > 0) {
                animatorRef[0] = animator;
            }
            animator.start();
        });
    }

    private void updateMemoryConsoleLabels(@Nullable TextView tvStatus,
                                           @Nullable TextView tvEst,
                                           @Nullable TextView tvWarn,
                                           @Nullable TextView tvCurrentValue,
                                           int targetValue,
                                           int warningThreshold) {
        int n = targetValue;
        if (n < 0) n = 0;
        if (n > 20) n = 20;

        String tierLabel = getMemoryTierLabel(n);

        if (tvStatus != null) {
            tvStatus.setText("当前记忆轮数：" + n + " 轮  ·  " + tierLabel);
        }
        if (tvCurrentValue != null) {
            tvCurrentValue.setText("当前设定：" + n + " 轮");
        }

        if (tvEst != null) {
            long estimated = (long) n * 800L;
            tvEst.setText("[📊 算力估值] 携带上下文将消耗 ~" + estimated + " Tokens/次。");
            try {
                tvEst.setTypeface(android.graphics.Typeface.MONOSPACE);
            } catch (Throwable ignored) {}
        }

        if (tvWarn != null) {
            if (n >= warningThreshold) {
                tvWarn.setVisibility(View.VISIBLE);
                if (n >= 18) {
                    tvWarn.setText("⚠️ 警告：已进入极限上下文区间（18-20轮），幻觉风险与延迟显著上升。");
                } else {
                    tvWarn.setText("⚠️ 警告：上下文过长可能导致模型注意力稀释（幻觉率上升）。");
                }
            } else {
                tvWarn.setVisibility(View.GONE);
            }
        }
    }

    private void renderMemoryBlockMatrix(@Nullable LinearLayout llBlocks,
                                         int litBlocks,
                                         int activeColor,
                                         int maxRounds) {
        if (llBlocks == null) return;

        int totalBlocks = 20; // 严格与滑块总档位一致
        int n = litBlocks;
        if (n < 0) n = 0;
        if (n > totalBlocks) n = totalBlocks;

        final int inactiveTrackColor = 0xFF2F2F2F;
        Context ctx = llBlocks.getContext();
        llBlocks.setOrientation(LinearLayout.HORIZONTAL);
        try { llBlocks.setBaselineAligned(false); } catch (Throwable ignored) {}

        int blockHeight = dpToPx(16);
        int margin = dpToPx(1);

        if (llBlocks.getChildCount() != totalBlocks) {
            llBlocks.removeAllViews();
            for (int i = 0; i < totalBlocks; i++) {
                View block = new View(ctx);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                bg.setCornerRadius(dpToPx(3));
                bg.setColor(inactiveTrackColor);
                block.setBackground(bg);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, blockHeight, 1f);
                if (i > 0) lp.leftMargin = margin;
                if (i < totalBlocks - 1) lp.rightMargin = margin;
                block.setLayoutParams(lp);
                llBlocks.addView(block);
            }
        }

        for (int i = 0; i < totalBlocks; i++) {
            View block = llBlocks.getChildAt(i);
            if (block == null) continue;
            android.graphics.drawable.Drawable drawable = block.getBackground();
            int blockColor = (i < n) ? activeColor : inactiveTrackColor;
            if (drawable instanceof android.graphics.drawable.GradientDrawable) {
                try {
                    ((android.graphics.drawable.GradientDrawable) drawable).setColor(blockColor);
                } catch (Throwable ignored) {
                    block.setBackgroundColor(blockColor);
                }
            } else {
                block.setBackgroundColor(blockColor);
            }
        }
    }


    private int getMemorySpectrumColor(int rounds) {
        int n = rounds;
        if (n < 0) n = 0;
        if (n > 20) n = 20;

        // Unified 4-tier spectrum with 普通模型思考: 蓝 / 绿 / 橙 / 红
        if (n <= 5) {
            return 0xFF2196F3; // 蓝
        } else if (n <= 10) {
            return 0xFF4CAF50; // 绿
        } else if (n <= 15) {
            return 0xFFFF9800; // 橙
        } else {
            return 0xFFF44336; // 红
        }
    }

    private String getMemoryTierLabel(int rounds) {
        int n = rounds;
        if (n < 0) n = 0;
        if (n > 20) n = 20;

        if (n == 0) {
            return "[🐟 记忆抹除 (无上下文)]";
        } else if (n <= 3) {
            return "[🧊 极简快答 (轻量低耗)]";
        } else if (n <= 6) {
            return "[🍃 短期记忆 (自然流畅)]";
        } else if (n <= 9) {
            return "[🌿 常规沟通 (标准模式)]";
        } else if (n <= 12) {
            return "[🌟 日常活跃 (进阶推演)]";
        } else if (n <= 15) {
            return "[🔥 深度连贯 (逻辑串联)]";
        } else if (n <= 17) {
            return "[🚨 算力燃烧 (长篇解析)]";
        } else if (n <= 19) {
            return "[🌋 极限临界 (幻觉风险)]";
        } else {
            return "[🌌 记忆宫殿 (宇宙边界)]";
        }
    }


    private int dpToPx(int dp) {
        if (getResources() == null) return dp;
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showLevelDialog(@androidx.annotation.StringRes int titleRes,
                                 int currentValue,
                                 int defaultValue,
                                 IntConsumer onSelected) {
        int cur = currentValue;
        if (cur < 0) cur = 0;
        if (cur > 20) cur = 20;

        String defaultTag = " " + getString(R.string.ui_default_value);
        String[] items = new String[11];
        for (int i = 0; i <= 10; i++) {
            items[i] = String.valueOf(i) + (i == defaultValue ? defaultTag : "");
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setSingleChoiceItems(items, cur, (dialog, which) -> {
                    if (onSelected != null) {
                        onSelected.accept(which);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

private interface IntConsumer {
        void accept(int value);
    }


    private boolean isNormalModelThinkingAdjustable() {
        Boolean cached = getCurrentModelCachedTempSupport();
        return cached == null || cached.booleanValue();
    }

    private boolean isReasoningModelThinkingAdjustable() {
        Boolean cached = getCurrentModelCachedReasoningSupport();
        return cached == null || cached.booleanValue();
    }

    @Nullable
    private Boolean getCurrentModelCachedTempSupport() {
        try {
            if (!SPManager.isReady()) return null;
            SPManager sp = SPManager.getInstance();
            LanguageModel provider = sp.getLanguageModel();
            String subModel = sp.getSubModel(provider);
            if (provider == null || TextUtils.isEmpty(subModel)) return null;
            return sp.getCachedSupportsTemperature(provider, subModel);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private Boolean getCurrentModelCachedReasoningSupport() {
        try {
            if (!SPManager.isReady()) return null;
            SPManager sp = SPManager.getInstance();
            LanguageModel provider = sp.getLanguageModel();
            String subModel = sp.getSubModel(provider);
            if (provider == null || TextUtils.isEmpty(subModel)) return null;
            return sp.getCachedSupportsReasoningThinking(provider, subModel);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void refreshNormalModelThinkingRowState() {
        if (rowNormalModelThinking == null || tvNormalModelThinkingValue == null) return;
        boolean adjustable = isNormalModelThinkingAdjustable();
        float alpha = adjustable ? 1.0f : 0.45f;
        try {
            rowNormalModelThinking.setEnabled(adjustable);
            rowNormalModelThinking.setAlpha(alpha);
            tvNormalModelThinkingValue.setEnabled(adjustable);
        } catch (Throwable ignored) {}

        try {
            float v = SPManager.getInstance().getNormalModelThinking();
            tvNormalModelThinkingValue.setText(getNormalModelThinkingSummary(v));
            updateNormalModelThinkingMicro(v);
        } catch (Throwable ignored) {}
    }

    private void refreshReasoningModelThinkingRowState() {
        if (rowReasoningModelThinking == null || tvReasoningModelThinkingValue == null) return;
        boolean adjustable = isReasoningModelThinkingAdjustable();
        float alpha = adjustable ? 1.0f : 0.45f;
        try {
            rowReasoningModelThinking.setEnabled(adjustable);
            rowReasoningModelThinking.setAlpha(alpha);
            tvReasoningModelThinkingValue.setEnabled(adjustable);
        } catch (Throwable ignored) {}

        try {
            int v = SPManager.getInstance().getReasoningModelThinkingMode();
            updateReasoningModelThinkingRowVisuals(v);
        } catch (Throwable ignored) {}
    }

    private String getReasoningModelThinkingSummary(int id) {
        try {
            ReasoningModelThinkingOption opt = ReasoningModelThinkingOptions.findById(id);
            if (opt != null) return opt.title;
        } catch (Throwable ignored) {}
        return String.valueOf(id);
    }

    /**
     * Main-screen summary with a tiny "compute" emoji badge.
     * Low=🔋, Medium=⚖️, High=⚡, Auto=🤖. Divergent/Convergent are mapped to ⚡/🔋.
     */
    private String getReasoningModelThinkingSummaryWithEmoji(int id) {
        String base = getReasoningModelThinkingSummary(id);
        String emoji;
        switch (id) {
            case ReasoningModelThinkingOptions.LOW:
                emoji = "🔋";
                break;
            case ReasoningModelThinkingOptions.MEDIUM:
                emoji = "⚖️";
                break;
            case ReasoningModelThinkingOptions.HIGH:
                emoji = "⚡";
                break;
            case ReasoningModelThinkingOptions.DIVERGENT:
                emoji = "⚡";
                break;
            case ReasoningModelThinkingOptions.CONVERGENT:
                emoji = "🔋";
                break;
            case ReasoningModelThinkingOptions.AUTO:
            default:
                emoji = "🤖";
                break;
        }
        return base + " " + emoji;
    }

    private int getReasoningThinkingPreviewProgress(int id) {
        switch (id) {
            case ReasoningModelThinkingOptions.CONVERGENT: return 24;
            case ReasoningModelThinkingOptions.LOW: return 36;
            case ReasoningModelThinkingOptions.AUTO: return 52;
            case ReasoningModelThinkingOptions.MEDIUM: return 60;
            case ReasoningModelThinkingOptions.DIVERGENT: return 78;
            case ReasoningModelThinkingOptions.HIGH: return 90;
            default: return 50;
        }
    }

    private int getReasoningThinkingSpectrumColor(int id) {
        switch (id) {
            case ReasoningModelThinkingOptions.CONVERGENT:
                return 0xFF2196F3;
            case ReasoningModelThinkingOptions.LOW:
                return 0xFF4CAF50;
            case ReasoningModelThinkingOptions.AUTO:
            case ReasoningModelThinkingOptions.MEDIUM:
                return 0xFFFF9800;
            case ReasoningModelThinkingOptions.DIVERGENT:
            case ReasoningModelThinkingOptions.HIGH:
            default:
                return 0xFFF44336;
        }
    }

    @Nullable
    private String getReasoningThinkingRiskHintText(int id) {
        switch (id) {
            case ReasoningModelThinkingOptions.DIVERGENT:
                return "小风险提示：灵感跳跃·跑题概率↑";
            case ReasoningModelThinkingOptions.CONVERGENT:
                return "小风险提示：过度收敛可能压制创意";
            case ReasoningModelThinkingOptions.HIGH:
                return "高温风险：耗时高 / Token 消耗显著增加";
            default:
                return null;
        }
    }

    private String getReasoningModelThinkingSummaryCompact(int id) {
        try {
            ReasoningModelThinkingOption opt = ReasoningModelThinkingOptions.findById(id);
            if (opt != null) {
                String t = opt.title == null ? "" : opt.title;
                int idx = t.indexOf('（');
                if (idx > 0) t = t.substring(0, idx);
                return t;
            }
        } catch (Throwable ignored) {}
        return getReasoningModelThinkingSummary(id);
    }

    private void updateReasoningModelThinkingRowVisuals(int id) {
        if (tvReasoningModelThinkingValue != null) {
            String compact = getReasoningModelThinkingSummaryCompact(id);
            tvReasoningModelThinkingValue.setText(compact);
            try {
                tvReasoningModelThinkingValue.setMaxLines(2);
                tvReasoningModelThinkingValue.setEllipsize(TextUtils.TruncateAt.END);
            } catch (Throwable ignored) {}
        }
        updateReasoningModelThinkingMicroStatic(id);
    }

    private void updateReasoningModelThinkingMicroStatic(int id) {
        final ProgressBar pb = pbReasoningModelThinkingMicro;
        if (pb == null) return;
        int progress = getReasoningThinkingPreviewProgress(id);
        int color = getReasoningThinkingSpectrumColor(id);
        try {
            pb.setMax(100);
            pb.setProgress(progress);
            ColorStateList tint = ColorStateList.valueOf(color);
            pb.setProgressTintList(tint);
            pb.setIndeterminateTintList(tint);
            pb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFCAD4DB));
            pb.setAlpha(1f);
        } catch (Throwable ignored) {}
        if (tvReasoningModelThinkingRiskHint != null) {
            String hint = getReasoningThinkingRiskHintText(id);
            if (TextUtils.isEmpty(hint)) {
                tvReasoningModelThinkingRiskHint.setVisibility(View.GONE);
            } else {
                tvReasoningModelThinkingRiskHint.setVisibility(View.VISIBLE);
                tvReasoningModelThinkingRiskHint.setText(hint);
                try {
                    tvReasoningModelThinkingRiskHint.setMaxLines(1);
                    tvReasoningModelThinkingRiskHint.setEllipsize(TextUtils.TruncateAt.END);
                    tvReasoningModelThinkingRiskHint.setTextColor(id == ReasoningModelThinkingOptions.HIGH ? 0xFFD84315 : 0xFFE65100);
                } catch (Throwable ignored) {}
            }
        }
    }


    /**
     * Micro visualization for normal model thinking (temperature-like).
     * Full scale is 2.0, and the bar color changes with value.
     */
    private void updateNormalModelThinkingMicro(float value) {
        updateNormalModelThinkingMicroStatic(value);
    }

    private void updateNormalModelThinkingMicroStatic(float value) {
        if (pbNormalModelThinkingMicro == null) return;
        float v = value;
        if (v < 0f) v = 0f;
        if (v > 2.0f) v = 2.0f;
        int progress = Math.round(v * 100f);
        try {
            pbNormalModelThinkingMicro.setMax(200);
            pbNormalModelThinkingMicro.setProgress(progress);
        } catch (Throwable ignored) {}

        int color = getNormalThinkingSpectrumColor(v);
        try {
            ColorStateList tint = ColorStateList.valueOf(color);
            pbNormalModelThinkingMicro.setProgressTintList(tint);
            pbNormalModelThinkingMicro.setIndeterminateTintList(tint);
            int track = 0xFFCDD6DD;
            pbNormalModelThinkingMicro.setProgressBackgroundTintList(ColorStateList.valueOf(track));
            pbNormalModelThinkingMicro.setAlpha(1f);
        } catch (Throwable ignored) {}
        updateNormalModelThinkingRiskHint(v);
    }

    private int getNormalThinkingSpectrumColor(float v) {
        if (v <= 0.4f) {
            return 0xFF2196F3;
        } else if (v <= 0.9f) {
            return 0xFF4CAF50;
        } else if (v <= 1.4f) {
            return 0xFFFF9800;
        } else {
            return 0xFFF44336;
        }
    }

    private void updateNormalModelThinkingRiskHint(float value) {
        if (tvNormalModelThinkingRiskHint == null) return;
        float v = value;
        if (v < 0f) v = 0f;
        if (v > 2.0f) v = 2.0f;
        if (v >= 1.4f) {
            tvNormalModelThinkingRiskHint.setVisibility(View.VISIBLE);
            try {
                tvNormalModelThinkingRiskHint.setMaxLines(1);
                tvNormalModelThinkingRiskHint.setEllipsize(TextUtils.TruncateAt.END);
            } catch (Throwable ignored) {}
            if (v >= 1.7f) {
                tvNormalModelThinkingRiskHint.setText("高温风险：可能失真/乱码");
            } else {
                tvNormalModelThinkingRiskHint.setText("小风险提示：高温创意·跑题概率↑");
            }
        } else {
            tvNormalModelThinkingRiskHint.setVisibility(View.GONE);
        }
    }

    private void cancelSettingsRowPreviewAnimations() {
        try {
            if (conversationMemoryRowMicroAnimator != null) conversationMemoryRowMicroAnimator.cancel();
        } catch (Throwable ignored) {}
        conversationMemoryRowMicroAnimator = null;
        try {
            if (normalThinkingRowMicroAnimator != null) normalThinkingRowMicroAnimator.cancel();
        } catch (Throwable ignored) {}
        normalThinkingRowMicroAnimator = null;
        try {
            if (reasoningThinkingRowMicroAnimator != null) reasoningThinkingRowMicroAnimator.cancel();
        } catch (Throwable ignored) {}
        reasoningThinkingRowMicroAnimator = null;
        try {
            if (pbConversationMemoryMicro != null) pbConversationMemoryMicro.setAlpha(1f);
        } catch (Throwable ignored) {}
        try {
            if (pbNormalModelThinkingMicro != null) pbNormalModelThinkingMicro.setAlpha(1f);
        } catch (Throwable ignored) {}
        try {
            if (pbReasoningModelThinkingMicro != null) pbReasoningModelThinkingMicro.setAlpha(1f);
        } catch (Throwable ignored) {}
        hideMiniSweepLight(vConversationMemoryMicroSweep);
        hideMiniSweepLight(vNormalModelThinkingMicroSweep);
        hideMiniSweepLight(vReasoningModelThinkingMicroSweep);
    }

    private void restartSettingsRowPreviewAnimations() {
        cancelSettingsRowPreviewAnimations();
        if (!isAdded()) return;
        startConversationMemoryRowPreviewAnimation();
        startNormalThinkingRowPreviewAnimation();
        startReasoningThinkingRowPreviewAnimation();
    }

    private void hideMiniSweepLight(@Nullable View sweepView) {
        if (sweepView == null) return;
        try { sweepView.animate().cancel(); } catch (Throwable ignored) {}
        try {
            sweepView.setTranslationX(0f);
            sweepView.setAlpha(0f);
            sweepView.setVisibility(View.GONE);
        } catch (Throwable ignored) {}
    }

    private void applyMiniSweepLight(@Nullable FrameLayout container,
                                     @Nullable ProgressBar pb,
                                     @Nullable View sweepView,
                                     int targetProgress,
                                     int maxProgress,
                                     float phase) {
        if (container == null || pb == null || sweepView == null) return;
        if (targetProgress <= 0 || maxProgress <= 0) {
            hideMiniSweepLight(sweepView);
            return;
        }
        int cw = container.getWidth();
        if (cw <= 0) cw = pb.getWidth();
        if (cw <= 0) {
            hideMiniSweepLight(sweepView);
            return;
        }
        int sw = sweepView.getWidth();
        if (sw <= 0) sw = Math.max(10, Math.round(container.getResources().getDisplayMetrics().density * 14f));
        float activeWidth = (targetProgress / (float) maxProgress) * (float) cw;
        if (activeWidth <= 1f) {
            hideMiniSweepLight(sweepView);
            return;
        }
        if (phase < 0f) phase = 0f;
        if (phase > 1f) phase = 1f;
        float startX = -sw;
        float endX = Math.max(0f, activeWidth);
        float x = startX + ((endX - startX) * phase);
        try {
            sweepView.setVisibility(View.VISIBLE);
            sweepView.setAlpha(0.92f);
            sweepView.setTranslationX(x);
            sweepView.bringToFront();
        } catch (Throwable ignored) {}
    }

    private void startConversationMemoryRowPreviewAnimation() {
        final ProgressBar pb = pbConversationMemoryMicro;
        if (pb == null) return;
        int rounds = 0;
        try { rounds = SPManager.getInstance().getConversationMemoryLevel(); } catch (Throwable ignored) {}
        if (rounds < 0) rounds = 0;
        if (rounds > 20) rounds = 20;
        final int target = rounds;
        final int color = getMemorySpectrumColor(target);
        try {
            pb.setMax(20);
            pb.setProgress(target);
            pb.setProgressTintList(ColorStateList.valueOf(color));
            pb.setIndeterminateTintList(ColorStateList.valueOf(color));
            pb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFCAD4DB));
            pb.setAlpha(1f);
        } catch (Throwable ignored) {}
        hideMiniSweepLight(vConversationMemoryMicroSweep);
        boolean enabled = getSavedMemoryAnimEnabled();
        final int mode = getSavedMemoryAnimMode();
        final int speed = getSavedMemoryAnimSpeedPercent();
        if (!enabled || target <= 0) return;
        if (mode == MEMORY_ANIM_MODE_SWEEP) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleAnimDurationByPercent(1400L, speed, 700L, 5000L));
            a.setInterpolator(new android.view.animation.LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            a.addUpdateListener(anim -> {
                if (!isAdded() || pbConversationMemoryMicro != pb) return;
                float t = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) t = (Float) v;
                pb.setProgress(target);
                pb.setAlpha(0.88f + 0.12f * (float) Math.sin(t * Math.PI));
                applyMiniSweepLight(flConversationMemoryMicroContainer, pb, vConversationMemoryMicroSweep, target, 20, t);
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setAlpha(1f); hideMiniSweepLight(vConversationMemoryMicroSweep); if (conversationMemoryRowMicroAnimator == animation) conversationMemoryRowMicroAnimator = null; }
                @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setAlpha(1f); hideMiniSweepLight(vConversationMemoryMicroSweep); if (conversationMemoryRowMicroAnimator == animation) conversationMemoryRowMicroAnimator = null; }
            });
            conversationMemoryRowMicroAnimator = a;
            a.start();
            return;
        }
        if (mode == MEMORY_ANIM_MODE_TRI_PHASE) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleAnimDurationByPercent(1800L, speed, 1000L, 6000L));
            a.setInterpolator(new android.view.animation.LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            final int finalTarget = target;
            a.addUpdateListener(anim -> {
                if (!isAdded() || pbConversationMemoryMicro != pb) return;
                float phase = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) phase = (Float) v;
                float ratio;
                if (phase <= 0.58f) {
                    float t = phase / 0.58f;
                    ratio = 1f - (1f - t) * (1f - t);
                } else if (phase <= 0.78f) {
                    ratio = 1f;
                } else {
                    float t = (phase - 0.78f) / 0.22f;
                    if (t <= 0.45f) ratio = 1f - (0.18f * (t / 0.45f));
                    else {
                        float b = (t - 0.45f) / 0.55f;
                        ratio = 0.82f + (0.08f * (float) Math.sin(b * Math.PI));
                    }
                }
                int lit = Math.round(finalTarget * ratio);
                if (lit < 1) lit = 1;
                if (lit > finalTarget) lit = finalTarget;
                pb.setProgress(lit);
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (conversationMemoryRowMicroAnimator == animation) conversationMemoryRowMicroAnimator = null; }
                @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (conversationMemoryRowMicroAnimator == animation) conversationMemoryRowMicroAnimator = null; }
            });
            conversationMemoryRowMicroAnimator = a;
            a.start();
            return;
        }
        hideMiniSweepLight(vConversationMemoryMicroSweep);
        ValueAnimator a = ValueAnimator.ofInt(0, target);
        long duration = Math.max(800L, Math.min(2000L, target * 100L));
        a.setDuration(scaleAnimDurationByPercent(duration, speed, 800L, 5000L));
        a.setInterpolator(new DecelerateInterpolator());
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.RESTART);
        a.addUpdateListener(anim -> {
            if (!isAdded() || pbConversationMemoryMicro != pb) return;
            Object v = anim.getAnimatedValue();
            int lit = 0;
            if (v instanceof Integer) lit = (Integer) v;
            if (lit < 0) lit = 0;
            if (lit > target) lit = target;
            pb.setProgress(lit);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationRepeat(android.animation.Animator animation) { if (pb != null) pb.setProgress(0); }
            @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (conversationMemoryRowMicroAnimator == animation) conversationMemoryRowMicroAnimator = null; }
            @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (conversationMemoryRowMicroAnimator == animation) conversationMemoryRowMicroAnimator = null; }
        });
        conversationMemoryRowMicroAnimator = a;
        a.start();
    }

    private void startNormalThinkingRowPreviewAnimation() {
        final ProgressBar pb = pbNormalModelThinkingMicro;
        if (pb == null) return;
        float val = 0.7f;
        try { val = SPManager.getInstance().getNormalModelThinking(); } catch (Throwable ignored) {}
        if (val < 0f) val = 0f;
        if (val > 2.0f) val = 2.0f;
        final float value = val;
        final int target = Math.round(value * 100f);
        final int color = getNormalThinkingSpectrumColor(value);
        try {
            pb.setMax(200);
            pb.setProgress(target);
            pb.setProgressTintList(ColorStateList.valueOf(color));
            pb.setIndeterminateTintList(ColorStateList.valueOf(color));
            pb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFCAD4DB));
            pb.setAlpha(1f);
        } catch (Throwable ignored) {}
        hideMiniSweepLight(vNormalModelThinkingMicroSweep);
        boolean enabled = getSavedNormalThinkingAnimEnabled();
        final int mode = getSavedNormalThinkingAnimMode();
        final int speed = getSavedNormalThinkingAnimSpeedPercent();
        if (!enabled || target <= 0) return;
        if (mode == NormalModelThinkingOptionAdapter.ANIM_MODE_SWEEP) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleAnimDurationByPercent(1400L, speed, 700L, 5000L));
            a.setInterpolator(new android.view.animation.LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            a.addUpdateListener(anim -> {
                if (!isAdded() || pbNormalModelThinkingMicro != pb) return;
                float t = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) t = (Float) v;
                pb.setProgress(target);
                pb.setAlpha(0.88f + 0.12f * (float) Math.sin(t * Math.PI));
                applyMiniSweepLight(flNormalModelThinkingMicroContainer, pb, vNormalModelThinkingMicroSweep, target, 200, t);
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setAlpha(1f); hideMiniSweepLight(vNormalModelThinkingMicroSweep); if (normalThinkingRowMicroAnimator == animation) normalThinkingRowMicroAnimator = null; }
                @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setAlpha(1f); hideMiniSweepLight(vNormalModelThinkingMicroSweep); if (normalThinkingRowMicroAnimator == animation) normalThinkingRowMicroAnimator = null; }
            });
            normalThinkingRowMicroAnimator = a;
            a.start();
            return;
        }
        if (mode == NormalModelThinkingOptionAdapter.ANIM_MODE_TRI_PHASE) {
            hideMiniSweepLight(vNormalModelThinkingMicroSweep);
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleAnimDurationByPercent(1800L, speed, 1000L, 6000L));
            a.setInterpolator(new android.view.animation.LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            final int finalTarget = target;
            a.addUpdateListener(anim -> {
                if (!isAdded() || pbNormalModelThinkingMicro != pb) return;
                float phase = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) phase = (Float) v;
                float ratio;
                if (phase <= 0.58f) {
                    float t = phase / 0.58f;
                    ratio = 1f - (1f - t) * (1f - t);
                } else if (phase <= 0.78f) {
                    ratio = 1f;
                } else {
                    float t = (phase - 0.78f) / 0.22f;
                    if (t <= 0.45f) ratio = 1f - (0.18f * (t / 0.45f));
                    else {
                        float b = (t - 0.45f) / 0.55f;
                        ratio = 0.82f + (0.08f * (float) Math.sin(b * Math.PI));
                    }
                }
                int lit = Math.round(finalTarget * ratio);
                if (lit < 1) lit = 1;
                if (lit > finalTarget) lit = finalTarget;
                pb.setProgress(lit);
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (normalThinkingRowMicroAnimator == animation) normalThinkingRowMicroAnimator = null; }
                @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (normalThinkingRowMicroAnimator == animation) normalThinkingRowMicroAnimator = null; }
            });
            normalThinkingRowMicroAnimator = a;
            a.start();
            return;
        }
        hideMiniSweepLight(vNormalModelThinkingMicroSweep);
        ValueAnimator a = ValueAnimator.ofInt(0, target);
        long duration = Math.max(800L, Math.min(2000L, ((target + 9) / 10) * 100L));
        a.setDuration(scaleAnimDurationByPercent(duration, speed, 800L, 5000L));
        a.setInterpolator(new DecelerateInterpolator());
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.RESTART);
        a.addUpdateListener(anim -> {
            if (!isAdded() || pbNormalModelThinkingMicro != pb) return;
            Object v = anim.getAnimatedValue();
            int lit = 0;
            if (v instanceof Integer) lit = (Integer) v;
            if (lit < 0) lit = 0;
            if (lit > target) lit = target;
            pb.setProgress(lit);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationRepeat(android.animation.Animator animation) { if (pb != null) pb.setProgress(0); }
            @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (normalThinkingRowMicroAnimator == animation) normalThinkingRowMicroAnimator = null; }
            @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (normalThinkingRowMicroAnimator == animation) normalThinkingRowMicroAnimator = null; }
        });
        normalThinkingRowMicroAnimator = a;
        a.start();
    }


    private void startReasoningThinkingRowPreviewAnimation() {
        final ProgressBar pb = pbReasoningModelThinkingMicro;
        if (pb == null) return;
        int id = SPManager.REASONING_MODEL_THINKING_AUTO;
        try { id = SPManager.getInstance().getReasoningModelThinkingMode(); } catch (Throwable ignored) {}
        final int target = getReasoningThinkingPreviewProgress(id);
        final int color = getReasoningThinkingSpectrumColor(id);
        try {
            pb.setMax(100);
            pb.setProgress(target);
            ColorStateList tint = ColorStateList.valueOf(color);
            pb.setProgressTintList(tint);
            pb.setIndeterminateTintList(tint);
            pb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFCAD4DB));
            pb.setAlpha(1f);
        } catch (Throwable ignored) {}
        hideMiniSweepLight(vReasoningModelThinkingMicroSweep);
        boolean enabled = getSavedReasoningThinkingAnimEnabled();
        final int mode = getSavedReasoningThinkingAnimMode();
        final int speed = getSavedReasoningThinkingAnimSpeedPercent();
        if (!enabled || target <= 0) return;
        if (mode == ReasoningModelThinkingOptionAdapter.ANIM_MODE_SWEEP) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleAnimDurationByPercent(1400L, speed, 700L, 5000L));
            a.setInterpolator(new android.view.animation.LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            a.addUpdateListener(anim -> {
                if (!isAdded() || pbReasoningModelThinkingMicro != pb) return;
                float t = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) t = (Float) v;
                pb.setProgress(target);
                pb.setAlpha(0.88f + 0.12f * (float) Math.sin(t * Math.PI));
                applyMiniSweepLight(flReasoningModelThinkingMicroContainer, pb, vReasoningModelThinkingMicroSweep, target, 100, t);
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setAlpha(1f); hideMiniSweepLight(vReasoningModelThinkingMicroSweep); if (reasoningThinkingRowMicroAnimator == animation) reasoningThinkingRowMicroAnimator = null; }
                @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setAlpha(1f); hideMiniSweepLight(vReasoningModelThinkingMicroSweep); if (reasoningThinkingRowMicroAnimator == animation) reasoningThinkingRowMicroAnimator = null; }
            });
            reasoningThinkingRowMicroAnimator = a;
            a.start();
            return;
        }
        if (mode == ReasoningModelThinkingOptionAdapter.ANIM_MODE_TRI_PHASE) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleAnimDurationByPercent(1800L, speed, 1000L, 6000L));
            a.setInterpolator(new android.view.animation.LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            final int finalTarget = target;
            a.addUpdateListener(anim -> {
                if (!isAdded() || pbReasoningModelThinkingMicro != pb) return;
                float phase = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) phase = (Float) v;
                float ratio;
                if (phase <= 0.58f) {
                    float t = phase / 0.58f;
                    ratio = 1f - (1f - t) * (1f - t);
                } else if (phase <= 0.78f) {
                    ratio = 1f;
                } else {
                    float t = (phase - 0.78f) / 0.22f;
                    if (t <= 0.45f) ratio = 1f - (0.18f * (t / 0.45f));
                    else {
                        float b = (t - 0.45f) / 0.55f;
                        ratio = 0.82f + (0.08f * (float) Math.sin(b * Math.PI));
                    }
                }
                int lit = Math.round(finalTarget * ratio);
                if (lit < 1) lit = 1;
                if (lit > finalTarget) lit = finalTarget;
                pb.setProgress(lit);
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (reasoningThinkingRowMicroAnimator == animation) reasoningThinkingRowMicroAnimator = null; }
                @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (reasoningThinkingRowMicroAnimator == animation) reasoningThinkingRowMicroAnimator = null; }
            });
            reasoningThinkingRowMicroAnimator = a;
            a.start();
            return;
        }
        if (mode == ReasoningModelThinkingOptionAdapter.ANIM_MODE_NEURAL_PULSE) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleAnimDurationByPercent(1600L, speed, 800L, 6000L));
            a.setInterpolator(new android.view.animation.LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            a.addUpdateListener(anim -> {
                if (!isAdded() || pbReasoningModelThinkingMicro != pb) return;
                float p = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) p = (Float) v;
                pb.setProgress(target);
                pb.setAlpha(0.86f + 0.14f * (float) Math.sin(p * Math.PI * 2f));
                applyMiniSweepLight(flReasoningModelThinkingMicroContainer, pb, vReasoningModelThinkingMicroSweep, target, 100, (p * 2f) % 1f);
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setAlpha(1f); hideMiniSweepLight(vReasoningModelThinkingMicroSweep); if (reasoningThinkingRowMicroAnimator == animation) reasoningThinkingRowMicroAnimator = null; }
                @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setAlpha(1f); hideMiniSweepLight(vReasoningModelThinkingMicroSweep); if (reasoningThinkingRowMicroAnimator == animation) reasoningThinkingRowMicroAnimator = null; }
            });
            reasoningThinkingRowMicroAnimator = a;
            a.start();
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(0, target);
        long duration = Math.max(900L, Math.min(2300L, target * 20L));
        a.setDuration(scaleAnimDurationByPercent(duration, speed, 800L, 6000L));
        a.setInterpolator(new DecelerateInterpolator());
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.RESTART);
        a.addUpdateListener(anim -> {
            if (!isAdded() || pbReasoningModelThinkingMicro != pb) return;
            Object v = anim.getAnimatedValue();
            int lit = 0;
            if (v instanceof Integer) lit = (Integer) v;
            if (lit < 0) lit = 0;
            if (lit > target) lit = target;
            pb.setProgress(lit);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationRepeat(android.animation.Animator animation) { if (pb != null) pb.setProgress(0); }
            @Override public void onAnimationCancel(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (reasoningThinkingRowMicroAnimator == animation) reasoningThinkingRowMicroAnimator = null; }
            @Override public void onAnimationEnd(android.animation.Animator animation) { if (pb != null) pb.setProgress(target); if (reasoningThinkingRowMicroAnimator == animation) reasoningThinkingRowMicroAnimator = null; }
        });
        reasoningThinkingRowMicroAnimator = a;
        a.start();
    }

    private void showReasoningModelThinkingDialog(int currentId, java.util.function.Consumer<Integer> onSelected) {
        if (getContext() == null) return;

        java.util.List<ReasoningModelThinkingOption> options = ReasoningModelThinkingOptions.buildOptions();
        int initial = ReasoningModelThinkingOptions.indexOf(currentId);
        if (initial < 0) initial = ReasoningModelThinkingOptions.indexOf(ReasoningModelThinkingOptions.AUTO);

        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_reasoning_model_thinking, null);
        RecyclerView rv = content.findViewById(R.id.rv_reasoning_model_thinking_options);
        android.widget.Button btnAnimMode = content.findViewById(R.id.btn_reasoning_animation_mode);
        android.widget.Button btnAnimToggle = content.findViewById(R.id.btn_reasoning_animation_toggle);
        android.widget.SeekBar seekAnimSpeed = content.findViewById(R.id.seek_reasoning_anim_speed);
        TextView tvAnimSpeedValue = content.findViewById(R.id.tv_reasoning_anim_speed_value);
        View llSpeedControls = content.findViewById(R.id.ll_reasoning_anim_speed_controls);

        final int[] pendingIndex = new int[]{initial};
        final int[] animMode = new int[]{getSavedReasoningThinkingAnimMode()};
        final boolean[] animEnabled = new boolean[]{getSavedReasoningThinkingAnimEnabled()};
        final int[] animSpeed = new int[]{getSavedReasoningThinkingAnimSpeedPercent()};

        final ReasoningModelThinkingOptionAdapter adapter = new ReasoningModelThinkingOptionAdapter(options, initial, pos -> {
            if (pos < 0 || pos >= options.size()) return;
            pendingIndex[0] = pos;
        });
        try {
            adapter.setAnimationMode(animMode[0]);
            adapter.setAnimationEnabled(animEnabled[0]);
            adapter.setAnimationSpeedPercent(animSpeed[0]);
        } catch (Throwable ignored) {}

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);
        rv.setNestedScrollingEnabled(false);

        rv.post(() -> {
            try {
                android.util.DisplayMetrics dm = content.getResources().getDisplayMetrics();
                int maxH = (int) (dm.heightPixels * 0.50f);
                ViewGroup.LayoutParams lp = rv.getLayoutParams();
                if (lp != null) {
                    int h = rv.computeVerticalScrollRange();
                    if (h <= 0) h = rv.getMeasuredHeight();
                    lp.height = Math.min(maxH, Math.max(h, (int) (180 * dm.density)));
                    rv.setLayoutParams(lp);
                }
            } catch (Throwable ignored) {}
            try { adapter.restartSelectedIndicatorAnimation(); } catch (Throwable ignored) {}
        });

        final Runnable refreshAnimButtons = new Runnable() {
            @Override public void run() {
                if (btnAnimMode != null) btnAnimMode.setText("动画选择：" + getReasoningAnimModeLabel(animMode[0]));
                if (btnAnimToggle != null) btnAnimToggle.setText(animEnabled[0] ? "关闭动画" : "开启动画");
                if (seekAnimSpeed != null) seekAnimSpeed.setEnabled(animEnabled[0]);
                if (llSpeedControls != null) llSpeedControls.setAlpha(animEnabled[0] ? 1f : 0.5f);
                if (tvAnimSpeedValue != null) tvAnimSpeedValue.setText(getAnimSpeedPercentLabel(animSpeed[0]));
                try {
                    adapter.setAnimationMode(animMode[0]);
                    adapter.setAnimationEnabled(animEnabled[0]);
                    adapter.setAnimationSpeedPercent(animSpeed[0]);
                } catch (Throwable ignored) {}
            }
        };

        if (seekAnimSpeed != null) {
            seekAnimSpeed.setMax(100);
            seekAnimSpeed.setProgress(animSpeed[0]);
            seekAnimSpeed.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    animSpeed[0] = progress;
                    if (tvAnimSpeedValue != null) tvAnimSpeedValue.setText(getAnimSpeedPercentLabel(progress));
                    try { adapter.setAnimationSpeedPercent(progress); } catch (Throwable ignored) {}
                    putUiAnimInt(PREF_REASONING_THINK_ANIM_SPEED, progress);
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
            });
        }

        if (btnAnimMode != null) {
            btnAnimMode.setOnClickListener(v -> {
                final String[] labels = new String[]{"逐格充能循环", "三段式机械节", "流光扫掠", "神经脉冲"};
                final int[] values = new int[]{
                        ReasoningModelThinkingOptionAdapter.ANIM_MODE_SEQ_LOOP,
                        ReasoningModelThinkingOptionAdapter.ANIM_MODE_TRI_PHASE,
                        ReasoningModelThinkingOptionAdapter.ANIM_MODE_SWEEP,
                        ReasoningModelThinkingOptionAdapter.ANIM_MODE_NEURAL_PULSE
                };
                int checked = 0;
                for (int i = 0; i < values.length; i++) if (values[i] == animMode[0]) { checked = i; break; }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("选择动画")
                        .setSingleChoiceItems(labels, checked, (d, which) -> {
                            if (which >= 0 && which < values.length) {
                                animMode[0] = values[which];
                                putUiAnimInt(PREF_REASONING_THINK_ANIM_MODE, animMode[0]);
                                refreshAnimButtons.run();
                            }
                            d.dismiss();
                        })
                        .show();
            });
        }

        if (btnAnimToggle != null) {
            btnAnimToggle.setOnClickListener(v -> {
                animEnabled[0] = !animEnabled[0];
                putUiAnimBool(PREF_REASONING_THINK_ANIM_ENABLED, animEnabled[0]);
                refreshAnimButtons.run();
            });
        }
        refreshAnimButtons.run();

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("推理模型思考")
                .setView(content)
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .setPositiveButton("确定", (d, w) -> {
                    int idx2 = pendingIndex[0];
                    if (idx2 < 0) idx2 = 0;
                    if (idx2 >= options.size()) idx2 = options.size() - 1;
                    if (onSelected != null) onSelected.accept(options.get(idx2).id);
                })
                .create();

        dialog.setOnShowListener(dlg -> {
            try { adapter.restartSelectedIndicatorAnimation(); } catch (Throwable ignored) {}
        });
        dialog.setOnDismissListener(dlg -> {
            try { adapter.cancelSelectedIndicatorAnimation(); } catch (Throwable ignored) {}
            refreshAiMultilineSendRow();

        restartSettingsRowPreviewAnimations();
        });
        dialog.show();
        applyThinkingDialogWidth(dialog);
    }

    private String getReasoningAnimModeLabel(int mode) {
        switch (mode) {
            case ReasoningModelThinkingOptionAdapter.ANIM_MODE_TRI_PHASE:
                return "三段式机械节";
            case ReasoningModelThinkingOptionAdapter.ANIM_MODE_SWEEP:
                return "流光扫掠";
            case ReasoningModelThinkingOptionAdapter.ANIM_MODE_NEURAL_PULSE:
                return "神经脉冲";
            case ReasoningModelThinkingOptionAdapter.ANIM_MODE_SEQ_LOOP:
            default:
                return "逐格充能";
        }
    }

    private void showOutputLengthDialog(int currentTokens, OutputLengthConfirmListener onConfirmed) {
        if (getContext() == null) return;

        final int customIndex;
        final java.util.List<OutputLengthOption> options = OutputLengthOptions.buildOptions(
                getString(R.string.ui_output_length_custom),
                getString(R.string.ui_output_length_custom_subtitle)
        );
        customIndex = options.size() - 1;

        boolean currentIsCustomSelection = false;
        try { currentIsCustomSelection = SPManager.getInstance().getMaxTokensIsCustom(); } catch (Throwable ignored) {}

        Integer cachedCap = resolveCurrentModelCachedSafeMaxTokens();
        String cachedCapSource = resolveCurrentModelCachedSafeMaxTokensSource();
        boolean cachedCapLowerOnly = !TextUtils.isEmpty(cachedCapSource) && cachedCapSource.toLowerCase(java.util.Locale.US).contains("lower");
        String currentSubModelName = resolveCurrentSubModelNameSafe();
        Integer floorPreset = (cachedCap != null && cachedCap > 0)
                ? Integer.valueOf(OutputLengthOptions.findFloorFixedTokens(cachedCap))
                : null;

        // Apply UI availability + floor preset 4th-line note.
        if (options != null) {
            for (OutputLengthOption o : options) {
                if (o == null) continue;
                if (!o.isCustom) {
                    o.enabled = OutputLengthOptions.isFixedSelectable(o.tokens, cachedCap);
                    if (cachedCap != null && cachedCap > 0 && floorPreset != null && floorPreset > 0 && o.tokens == floorPreset) {
                        o.modelCapNote = "当前子模型「" + safeEllipsize(currentSubModelName, 28) + "」" + (cachedCapLowerOnly ? "已学习可用下限≥ " : "已学习上限约 ") + cachedCap + " Token（自动匹配档位）";
                    } else {
                        o.modelCapNote = null;
                    }
                } else {
                    o.enabled = true;
                    if (cachedCap != null && cachedCap > 0) {
                        o.modelCapNote = "自定义值不可超过" + (cachedCapLowerOnly ? "当前已学习可用下限保护值 " : "已学习上限 ") + cachedCap + " Token";
                    } else {
                        o.modelCapNote = null;
                    }
                }
            }
        }

        int initialIndex = -1;
        for (int i = 0; i < options.size(); i++) {
            OutputLengthOption o = options.get(i);
            if (!o.isCustom && o.tokens == currentTokens) {
                initialIndex = i;
                break;
            }
        }
        if (currentIsCustomSelection) initialIndex = customIndex;
        else if (initialIndex < 0) initialIndex = customIndex;

        // If current persisted selection is invalid under learned cap, preselect the auto bucket (UI only).
        if (cachedCap != null && cachedCap > 0) {
            boolean currentInvalid = currentTokens <= 0 || currentTokens > cachedCap;
            if (currentInvalid) {
                int autoIdx = findOutputLengthOptionIndexForCapBucket(options, customIndex, cachedCap);
                if (autoIdx >= 0) initialIndex = autoIdx;
            } else if (!currentIsCustomSelection && initialIndex >= 0 && initialIndex < options.size()) {
                OutputLengthOption selectedOpt = options.get(initialIndex);
                if (selectedOpt != null && !selectedOpt.isCustom && !selectedOpt.enabled) {
                    int autoIdx = findOutputLengthOptionIndexForCapBucket(options, customIndex, cachedCap);
                    if (autoIdx >= 0) initialIndex = autoIdx;
                }
            }
        }

        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_output_length, null);
        RecyclerView rv = content.findViewById(R.id.rv_output_length_options);
        TextInputLayout tilCustom = content.findViewById(R.id.til_custom_tokens);
        TextInputEditText etCustom = content.findViewById(R.id.et_custom_tokens);
        TextView tvHint = content.findViewById(R.id.tv_custom_tokens_hint);
        TextView tvDialogNotice = content.findViewById(R.id.tv_output_length_notice);
        TextView tvCustomCapNotice = content.findViewById(R.id.tv_custom_tokens_cap_notice);

        bindOutputLengthDialogNotice(tvDialogNotice, cachedCap, currentSubModelName);
        bindCustomCapNotice(tvCustomCapNotice, cachedCap, currentSubModelName);

        final int[] selected = new int[]{initialIndex};

        // For the "Custom" row capsule, show current token only when user is actually on custom row.
        final int initialCustomTokensValue = (initialIndex == customIndex) ? currentTokens : 0;

        final OutputLengthOptionAdapter[] adapterRef = new OutputLengthOptionAdapter[1];
        adapterRef[0] = new OutputLengthOptionAdapter(options, initialIndex, initialCustomTokensValue, pos -> {
            if (pos < 0 || pos >= options.size()) return;
            OutputLengthOption clicked = options.get(pos);
            if (clicked != null && !clicked.enabled) {
                if (cachedCap != null && cachedCap > 0) {
                    try {
                        android.widget.Toast.makeText(requireContext(), "该档位超过当前子模型已学习上限（" + cachedCap + " Token），不可选择", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignoredToast) {}
                }
                return;
            }

            selected[0] = pos;
            if (adapterRef[0] != null) adapterRef[0].setSelectedIndex(pos);
            boolean isCustom = (pos == customIndex);
            if (tilCustom != null) tilCustom.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            if (tvHint != null) tvHint.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            if (tvCustomCapNotice != null) tvCustomCapNotice.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            if (isCustom && etCustom != null) {
                etCustom.requestFocus();
            }
        });

        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(requireContext()));
            rv.setAdapter(adapterRef[0]);
        }

        // Init custom area
        boolean startCustom = (initialIndex == customIndex);
        if (tilCustom != null) tilCustom.setVisibility(startCustom ? View.VISIBLE : View.GONE);
        if (tvHint != null) tvHint.setVisibility(startCustom ? View.VISIBLE : View.GONE);
        if (tvCustomCapNotice != null) tvCustomCapNotice.setVisibility(startCustom ? View.VISIBLE : View.GONE);

        if (etCustom != null) {
            if (startCustom) {
                etCustom.setText(String.valueOf(currentTokens));
                etCustom.setSelection(etCustom.getText() == null ? 0 : etCustom.getText().length());
            }
            updateCustomApproxText(tvHint, etCustom.getText() == null ? "" : etCustom.getText().toString(), cachedCap);
            etCustom.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String raw = (s == null ? "" : s.toString());
                    updateCustomApproxText(tvHint, raw, cachedCap);

                    if (tilCustom != null) {
                        try { tilCustom.setError(null); } catch (Throwable ignored) {}
                    }

                    int parsed = 0;
                    try {
                        if (raw != null && !raw.trim().isEmpty()) parsed = Integer.parseInt(raw.trim());
                    } catch (Throwable ignored) {
                        parsed = 0;
                    }
                    if (adapterRef[0] != null) adapterRef[0].setCustomTokensValue(parsed);
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        try {
            Logger.log("OUTLEN_UI", "open dialog subModel=" + String.valueOf(currentSubModelName)
                    + ", currentTokens=" + currentTokens
                    + ", currentIsCustom=" + currentIsCustomSelection
                    + ", cachedCap=" + String.valueOf(cachedCap)
                    + ", floorPreset=" + String.valueOf(floorPreset)
                    + ", initialIndex=" + initialIndex);
        } catch (Throwable ignored) {}

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_output_length)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        // Hook positive button click to validate and apply without causing layout jitter.
        dialog.setOnShowListener(dlg -> {
            android.widget.Button btnOk = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            if (btnOk != null) {
                btnOk.setOnClickListener(v -> {
                    int pos = selected[0];
                    if (pos < 0 || pos >= options.size()) pos = customIndex;

                    OutputLengthOption opt = options.get(pos);
                    boolean isCustom = (opt != null && opt.isCustom) || (pos == customIndex);

                    int finalTokens = currentTokens;
                    if (isCustom) {
                        String raw = "";
                        try {
                            raw = (etCustom != null && etCustom.getText() != null) ? etCustom.getText().toString().trim() : "";
                        } catch (Throwable ignored) {}

                        int parsed = 0;
                        try { parsed = Integer.parseInt(raw); } catch (Throwable ignored) { parsed = 0; }

                        if (parsed <= 0) {
                            try { if (tilCustom != null) tilCustom.setError("请输入有效 Token 数"); } catch (Throwable ignored) {}
                            return; // keep dialog open
                        }

                        if (cachedCap != null && cachedCap > 0 && parsed > cachedCap) {
                            try { if (tilCustom != null) tilCustom.setError("不可超过 " + cachedCap + " Token"); } catch (Throwable ignored) {}
                            return; // keep dialog open
                        }

                        finalTokens = parsed;
                    } else if (opt != null && opt.tokens > 0) {
                        finalTokens = opt.tokens;
                    }

                    try {
                        if (onConfirmed != null) onConfirmed.onConfirmed(finalTokens, isCustom);
                    } catch (Throwable ignored) {}

                    dialog.dismiss();
                });
            }
        });

        dialog.show();
        applyThinkingDialogWidth(dialog);
    }

    private void bindOutputLengthDialogNotice(@Nullable TextView tv, @Nullable Integer cachedCap, @Nullable String subModelName) {
        if (tv == null) return;
        try {
            if (cachedCap != null && cachedCap > 0) {
                int floor = OutputLengthOptions.findFloorFixedTokens(cachedCap);
                StringBuilder sb = new StringBuilder();
                sb.append("当前子模型：").append(TextUtils.isEmpty(subModelName) ? "未设置" : safeEllipsize(subModelName, 36));
                sb.append("\n已学习最大输出上限约 ").append(cachedCap).append(" Token");
                if (floor > 0) {
                    sb.append("，固定档位已自动映射到 ≤ ").append(floor).append(" Token");
                } else {
                    sb.append("（低于最小固定档位，将使用自定义值）");
                }
                tv.setText(sb.toString());
                tv.setVisibility(View.VISIBLE);
            } else {
                tv.setText("当前子模型尚未学习输出上限。首次请求时会自动学习，并在成功识别后自动切换到对应固定档位。");
                tv.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {
            tv.setVisibility(View.GONE);
        }
    }

    private void bindCustomCapNotice(@Nullable TextView tv, @Nullable Integer cachedCap, @Nullable String subModelName) {
        if (tv == null) return;
        if (cachedCap != null && cachedCap > 0) {
            tv.setText("自定义模式限制：当前子模型「" + safeEllipsize(subModelName, 28) + "」最大可填约 " + cachedCap + " Token（超过将被拦截）");
            tv.setVisibility(View.VISIBLE);
        } else {
            tv.setText("自定义模式当前未学习上限；首次请求后会自动学习并回填限制。若后续学习到较小上限，会自动切换到对应固定档位。");
            tv.setVisibility(View.VISIBLE);
        }
    }

    private int findOutputLengthOptionIndexForCapBucket(@Nullable java.util.List<OutputLengthOption> options, int customIndex, int cap) {
        if (options == null || options.isEmpty()) return customIndex;
        int floor = OutputLengthOptions.findFloorFixedTokens(cap);
        if (floor > 0) {
            for (int i = 0; i < options.size(); i++) {
                OutputLengthOption o = options.get(i);
                if (o != null && !o.isCustom && o.tokens == floor) return i;
            }
        }
        return customIndex;
    }

    @Nullable
    private String resolveCurrentSubModelNameSafe() {
        try {
            if (!SPManager.isReady()) return null;
            LanguageModel p = SPManager.getInstance().getLanguageModel();
            String sub = SPManager.getInstance().getSubModel(p);
            if (TextUtils.isEmpty(sub) && p != null) sub = p.getDefault(LanguageModelField.SubModel);
            return sub;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String safeEllipsize(@Nullable String s, int maxLen) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() <= Math.max(1, maxLen)) return v;
        int keep = Math.max(1, maxLen - 1);
        return v.substring(0, keep) + "…";
    }

    private int safeGetRequestPolicy() {
        try {
            return SPManager.getInstance().getRequestConcurrencyPolicy();
        } catch (Throwable ignored) {
            return SPManager.REQUEST_POLICY_CANCEL_PREVIOUS;
        }
    }

    private String getRequestPolicyLabel(int policy) {
        int p = policy;
        if (p < SPManager.REQUEST_POLICY_CANCEL_PREVIOUS || p > SPManager.REQUEST_POLICY_QUEUE_LATEST) {
            p = SPManager.REQUEST_POLICY_CANCEL_PREVIOUS;
        }
        switch (p) {
            case SPManager.REQUEST_POLICY_IGNORE_NEW:
                return getString(R.string.ui_request_policy_ignore_new);
            case SPManager.REQUEST_POLICY_QUEUE_LATEST:
                return getString(R.string.ui_request_policy_queue_latest);
            case SPManager.REQUEST_POLICY_CANCEL_PREVIOUS:
            default:
                return getString(R.string.ui_request_policy_cancel_previous);
        }
    }

    private void showRequestPolicyDialog(int current, IntConsumer onSelected) {
        if (getContext() == null) return;

        final int[] values = new int[] {
                SPManager.REQUEST_POLICY_CANCEL_PREVIOUS,
                SPManager.REQUEST_POLICY_IGNORE_NEW,
                SPManager.REQUEST_POLICY_QUEUE_LATEST,
        };

        final String[] items = new String[] {
                getString(R.string.ui_request_policy_cancel_previous),
                getString(R.string.ui_request_policy_ignore_new),
                getString(R.string.ui_request_policy_queue_latest),
        };

        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { checked = i; break; }
        }


    
    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ui_request_policy)
            .setSingleChoiceItems(items, checked, (dialog, which) -> {
                if (which >= 0 && which < values.length) {
                    if (onSelected != null) onSelected.accept(values[which]);
                }
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
}

private void showWatchdogTimeoutDialog(@Nullable Runnable onSaved) {
        if (getContext() == null) return;

        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_watchdog_timeout, null, false);

        TextView tvFirstVal = content.findViewById(R.id.tv_watchdog_first_value);
        TextView tvStallVal = content.findViewById(R.id.tv_watchdog_stall_value);
        TextView tvFirstRange = content.findViewById(R.id.tv_watchdog_first_range);
        TextView tvStallRange = content.findViewById(R.id.tv_watchdog_stall_range);
        android.widget.SeekBar seekFirst = content.findViewById(R.id.seek_watchdog_first);
        android.widget.SeekBar seekStall = content.findViewById(R.id.seek_watchdog_stall);
        com.google.android.material.button.MaterialButton btnRestore = content.findViewById(R.id.btn_watchdog_restore_default);

        // Ranges are in seconds (UI friendly), stored in ms in SPManager.
        final int FIRST_MIN = 5, FIRST_MAX = 120, FIRST_STEP = 5;
        final int STALL_MIN = 30, STALL_MAX = 600, STALL_STEP = 10;

        int curFirstSec = 30;
        int curStallSec = 180;
        try {
            SPManager sp = SPManager.getInstance();
            curFirstSec = (int) Math.max(1, sp.getWatchdogFirstChunkTimeoutMs() / 1000L);
            curStallSec = (int) Math.max(1, sp.getWatchdogStallTimeoutMs() / 1000L);
        } catch (Throwable ignored) {}

        // Align to step.
        int firstProg = Math.round((curFirstSec - FIRST_MIN) / (float) FIRST_STEP);
        int stallProg = Math.round((curStallSec - STALL_MIN) / (float) STALL_STEP);

        int firstMaxProg = (FIRST_MAX - FIRST_MIN) / FIRST_STEP;
        int stallMaxProg = (STALL_MAX - STALL_MIN) / STALL_STEP;

        if (firstProg < 0) firstProg = 0;
        if (firstProg > firstMaxProg) firstProg = firstMaxProg;
        if (stallProg < 0) stallProg = 0;
        if (stallProg > stallMaxProg) stallProg = stallMaxProg;

        final int[] selectedFirstSec = new int[]{FIRST_MIN + firstProg * FIRST_STEP};
        final int[] selectedStallSec = new int[]{STALL_MIN + stallProg * STALL_STEP};

        Runnable refreshLabels = () -> {
            if (tvFirstVal != null) tvFirstVal.setText(selectedFirstSec[0] + "s");
            if (tvStallVal != null) tvStallVal.setText(selectedStallSec[0] + "s");
            if (tvFirstRange != null) tvFirstRange.setText(FIRST_MIN + "s - " + FIRST_MAX + "s");
            if (tvStallRange != null) tvStallRange.setText(STALL_MIN + "s - " + STALL_MAX + "s");
        };

        if (seekFirst != null) {
            seekFirst.setMax(firstMaxProg);
            seekFirst.setProgress(firstProg);
            disallowParentInterceptOnTouch(seekFirst);
            seekFirst.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    int p = progress;
                    if (p < 0) p = 0;
                    if (p > firstMaxProg) p = firstMaxProg;
                    selectedFirstSec[0] = FIRST_MIN + p * FIRST_STEP;
                    refreshLabels.run();
                }

                @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
            });
        }

        if (seekStall != null) {
            seekStall.setMax(stallMaxProg);
            seekStall.setProgress(stallProg);
            disallowParentInterceptOnTouch(seekStall);
            seekStall.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    int p = progress;
                    if (p < 0) p = 0;
                    if (p > stallMaxProg) p = stallMaxProg;
                    selectedStallSec[0] = STALL_MIN + p * STALL_STEP;
                    refreshLabels.run();
                }

                @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
            });
        }

        if (btnRestore != null) {
            btnRestore.setOnClickListener(v -> {
                // Restore defaults (same as v3).
                int defFirst = 30;
                int defStall = 180;

                int pFirst = Math.round((defFirst - FIRST_MIN) / (float) FIRST_STEP);
                int pStall = Math.round((defStall - STALL_MIN) / (float) STALL_STEP);
                if (pFirst < 0) pFirst = 0;
                if (pFirst > firstMaxProg) pFirst = firstMaxProg;
                if (pStall < 0) pStall = 0;
                if (pStall > stallMaxProg) pStall = stallMaxProg;

                selectedFirstSec[0] = FIRST_MIN + pFirst * FIRST_STEP;
                selectedStallSec[0] = STALL_MIN + pStall * STALL_STEP;

                try { if (seekFirst != null) seekFirst.setProgress(pFirst); } catch (Throwable ignored) {}
                try { if (seekStall != null) seekStall.setProgress(pStall); } catch (Throwable ignored) {}
                refreshLabels.run();
            });
        }

        refreshLabels.run();

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_watchdog_timeout)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> {
                    try {
                        SPManager sp = SPManager.getInstance();
                        sp.setWatchdogFirstChunkTimeoutMs(selectedFirstSec[0] * 1000L);
                        sp.setWatchdogStallTimeoutMs(selectedStallSec[0] * 1000L);
                    } catch (Throwable ignored) {}
                    if (onSaved != null) onSaved.run();
                })
                .show();
    }



    private String getAutoDowngradeSummary() {
        int flags = 0;
        try { flags = SPManager.getInstance().getAutoDowngradeFlags(); } catch (Throwable ignored) {}

        ArrayList<String> parts = new ArrayList<>();
        if ((flags & SPManager.DOWNGRADE_FLAG_STREAM) != 0) parts.add(getString(R.string.ui_auto_downgrade_stream));
        if ((flags & SPManager.DOWNGRADE_FLAG_BASEURL) != 0) parts.add(getString(R.string.ui_auto_downgrade_baseurl));
        if ((flags & SPManager.DOWNGRADE_FLAG_MODEL) != 0) parts.add(getString(R.string.ui_auto_downgrade_model));

        if (parts.isEmpty()) return getString(R.string.ui_off);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void showAutoDowngradeDialog(@Nullable Runnable onSaved) {
        if (getContext() == null) return;

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_auto_downgrade, null, false);

        SwitchMaterial swStream = dialogView.findViewById(R.id.switch_downgrade_stream);
        SwitchMaterial swBaseUrl = dialogView.findViewById(R.id.switch_downgrade_baseurl);
        SwitchMaterial swModel = dialogView.findViewById(R.id.switch_downgrade_model);
        TextInputEditText etBaseUrl = dialogView.findViewById(R.id.et_backup_baseurl);
        View rowModel = dialogView.findViewById(R.id.row_backup_model);
        TextView tvModelValue = dialogView.findViewById(R.id.tv_backup_model_value);

        int flags = 0;
        String baseUrl = "";
        LanguageModel backupModel = null;
        try { flags = SPManager.getInstance().getAutoDowngradeFlags(); } catch (Throwable ignored) {}
        try { baseUrl = SPManager.getInstance().getAutoDowngradeBackupBaseUrl(); } catch (Throwable ignored) {}
        try { backupModel = SPManager.getInstance().getAutoDowngradeBackupModel(); } catch (Throwable ignored) {}

        final LanguageModel[] selectedModel = new LanguageModel[] { backupModel };

        if (swStream != null) swStream.setChecked((flags & SPManager.DOWNGRADE_FLAG_STREAM) != 0);
        if (swBaseUrl != null) swBaseUrl.setChecked((flags & SPManager.DOWNGRADE_FLAG_BASEURL) != 0);
        if (swModel != null) swModel.setChecked((flags & SPManager.DOWNGRADE_FLAG_MODEL) != 0);

        if (etBaseUrl != null && baseUrl != null) etBaseUrl.setText(baseUrl);

        if (tvModelValue != null) {
            tvModelValue.setText(selectedModel[0] == null ? getString(R.string.ui_not_set) : selectedModel[0].label);
        }

        final Runnable refreshEnabledState = () -> {
            boolean baseEnabled = swBaseUrl != null && swBaseUrl.isChecked();
            boolean modelEnabled = swModel != null && swModel.isChecked();

            if (etBaseUrl != null) {
                etBaseUrl.setEnabled(baseEnabled);
                etBaseUrl.setAlpha(baseEnabled ? 1.0f : 0.5f);
            }
            if (rowModel != null) {
                rowModel.setEnabled(modelEnabled);
                rowModel.setAlpha(modelEnabled ? 1.0f : 0.5f);
            }
        };

        if (swBaseUrl != null) swBaseUrl.setOnCheckedChangeListener((b, c) -> refreshEnabledState.run());
        if (swModel != null) swModel.setOnCheckedChangeListener((b, c) -> refreshEnabledState.run());
        refreshEnabledState.run();

        if (rowModel != null) {
            rowModel.setOnClickListener(v -> {
                // Use the same interaction as the Provider picker: highlight selection + Cancel/OK.
                onBackupModelPicked = picked -> {
                    selectedModel[0] = picked;
                    if (tvModelValue != null) {
                        tvModelValue.setText(picked == null ? getString(R.string.ui_not_set) : picked.label);
                    }
                };
                BackupModelListDialogFragment.newInstance(selectedModel[0])
                        .show(getChildFragmentManager(), BackupModelListDialogFragment.TAG);
            });
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_auto_downgrade_strategy)
                .setView(dialogView)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    int newFlags = 0;
                    if (swStream != null && swStream.isChecked()) newFlags |= SPManager.DOWNGRADE_FLAG_STREAM;
                    if (swBaseUrl != null && swBaseUrl.isChecked()) newFlags |= SPManager.DOWNGRADE_FLAG_BASEURL;
                    if (swModel != null && swModel.isChecked()) newFlags |= SPManager.DOWNGRADE_FLAG_MODEL;

                    String newBase = "";
                    if (etBaseUrl != null && etBaseUrl.getText() != null) {
                        newBase = etBaseUrl.getText().toString();
                    }

                    try { SPManager.getInstance().setAutoDowngradeFlags(newFlags); } catch (Throwable ignored) {}
                    try { SPManager.getInstance().setAutoDowngradeBackupBaseUrl(newBase); } catch (Throwable ignored) {}
                    try { SPManager.getInstance().setAutoDowngradeBackupModel(selectedModel[0]); } catch (Throwable ignored) {}

                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    private void setStreamingAlgorithmOptionRowsEnabled(@Nullable View rowLinear,
            @Nullable View rowNonLinear,
            @Nullable RadioButton rbLinear,
            @Nullable RadioButton rbNonLinear,
            boolean enabled) {
        float alpha = enabled ? 1.0f : 0.45f;
        if (rowLinear != null) {
            rowLinear.setEnabled(enabled);
            rowLinear.setAlpha(alpha);
        }
        if (rowNonLinear != null) {
            rowNonLinear.setEnabled(enabled);
            rowNonLinear.setAlpha(alpha);
        }
        if (rbLinear != null) rbLinear.setEnabled(enabled);
        if (rbNonLinear != null) rbNonLinear.setEnabled(enabled);
    }




    private void setStreamingNonLinearModelRowEnabled(@Nullable View nlModelRow,
            @Nullable TextView tvNlModelValue,
            boolean streamingEnabled,
            int speedAlgo) {
        // Always show the non-linear model row in the Streaming section.
        // It becomes editable only when streaming is enabled AND the speed algorithm is NON-LINEAR.
        boolean enabled = streamingEnabled && speedAlgo == SPManager.STREAM_SPEED_ALGO_NONLINEAR;
        if (nlModelRow != null) {
            nlModelRow.setVisibility(View.VISIBLE);
            nlModelRow.setEnabled(enabled);
            nlModelRow.setAlpha(enabled ? 1.0f : 0.45f);
        }
        if (tvNlModelValue != null) {
            try {
                int m = SPManager.getInstance().getStreamingNonLinearModel();
                tvNlModelValue.setText(getStreamingNonLinearModelLabel(m));
            } catch (Throwable ignored) {}
            tvNlModelValue.setEnabled(enabled);
        }
    }

    private void setStreamingSpeedControlsEnabled(@Nullable View speedRow,
            @Nullable Slider sliderSpeed,
            @Nullable TextView tvSpeedValue,
            @Nullable SwitchMaterial switchAuto,
            boolean streamingEnabled,
            int speedAlgo) {
        // These controls are only meaningful for LINEAR speed.
        boolean enabled = streamingEnabled && speedAlgo == SPManager.STREAM_SPEED_ALGO_LINEAR;
        boolean auto = false;
        try {
            if (switchAuto != null) {
                auto = switchAuto.isChecked();
            } else if (SPManager.isReady()) {
                auto = SPManager.getInstance().getStreamingOutputSpeedAutoEnabled();
            }
        } catch (Throwable ignored) {
        }

        boolean sliderEnabled = enabled && !auto;
        if (sliderSpeed != null) {
            sliderSpeed.setEnabled(sliderEnabled);
            sliderSpeed.setAlpha(sliderEnabled ? 1.0f : (enabled ? 0.35f : 0.45f));
        }
        if (switchAuto != null) switchAuto.setEnabled(enabled);
        if (tvSpeedValue != null) {
            tvSpeedValue.setEnabled(sliderEnabled);
            tvSpeedValue.setAlpha(sliderEnabled ? 1.0f : (enabled ? 0.45f : 0.45f));
        }
        if (speedRow != null) {
            speedRow.setEnabled(enabled);
            speedRow.setAlpha(enabled ? 1.0f : 0.45f);
        }
    }

    private void setStreamingAdvancedControlsEnabled(@Nullable View modeRow,
            @Nullable TextView tvModeValue,
            @Nullable View granularityRow,
            @Nullable TextView tvGranularityValue,
            @Nullable View fallbackRow,
            @Nullable SwitchMaterial switchFallback,
            boolean enabled,
            int mode) {

        // Disable controls when streaming output is OFF.
        float alpha = enabled ? 1.0f : 0.45f;

        if (modeRow != null) {
            modeRow.setEnabled(enabled);
            modeRow.setAlpha(alpha);
        }
        if (tvModeValue != null) {
            tvModeValue.setEnabled(enabled);
        }

        if (granularityRow != null) {
            granularityRow.setEnabled(enabled);
            granularityRow.setAlpha(alpha);
        }
        if (tvGranularityValue != null) {
            tvGranularityValue.setEnabled(enabled);
        }

        // Fallback is only meaningful when we actually request streaming from backend.
        boolean fallbackRelevant = enabled && mode != SPManager.STREAM_MODE_TYPEWRITER;
        float fallbackAlpha = fallbackRelevant ? 1.0f : 0.45f;
        if (fallbackRow != null) {
            fallbackRow.setEnabled(fallbackRelevant);
            fallbackRow.setAlpha(fallbackAlpha);
        }
        if (switchFallback != null) {
            switchFallback.setEnabled(fallbackRelevant);
        }
    }
private void refreshConversationSubModelRow() {
    if (!SPManager.isReady()) return;
    try {
        SPManager spReady = SPManager.getInstance();
        LanguageModel provider = spReady.hasLanguageModel() ? spReady.getLanguageModel() : null;

        // Left icon: current provider logo (keep original colors; no tint)
        if (ivConversationProviderIcon != null) {
            ivConversationProviderIcon.setImageResource(getProviderIconRes(provider));
            try { ivConversationProviderIcon.setImageTintList(null); } catch (Throwable ignored) {}
        }

        // Left subtitle: current provider (supplier)
        if (tvConversationProviderValue != null) {
            tvConversationProviderValue.setText(provider != null ? provider.label : getString(R.string.ui_not_set));
            // Ensure marquee works when the provider label is long.
            try { tvConversationProviderValue.setSelected(true); } catch (Throwable ignored) {}
        }
        if (ivConversationProviderRefresh != null) {
            ivConversationProviderRefresh.setVisibility(provider != null ? View.VISIBLE : View.GONE);
        }

        // Left status: cache count + base host + last check + latency/reachability
        if (tvConversationProviderStatus != null) {
            String text = "";
            try {
                if (provider == null) {
                    text = "";
                } else {
                    SPManager sp = SPManager.getInstance();

                    int modelCount = 0;
                    try {
                        java.util.List<String> cached = sp.getCachedModels(provider);
                        modelCount = cached != null ? cached.size() : 0;
                    } catch (Throwable ignored) {
                        modelCount = 0;
                    }

                    String baseHost = "-";
                    try {
                        String base = sp.getBaseUrl(provider);
                        if (!TextUtils.isEmpty(base)) {
                            android.net.Uri u = android.net.Uri.parse(base);
                            String host = u != null ? u.getHost() : null;
                            if (!TextUtils.isEmpty(host)) baseHost = host;
                        }
                    } catch (Throwable ignored) {}

                    long last = 0L;
                    long latency = 0L;
                    boolean ok = false;
                    try {
                        last = sp.getProviderHealthLastCheckMs(provider);
                        latency = sp.getProviderHealthLatencyMs(provider);
                        ok = sp.getProviderHealthOk(provider);
                    } catch (Throwable ignored) {}

                    String line1 = "缓存: " + modelCount + " · Base: " + baseHost;
                    String checkLine = "校验: 未";
                    String latencyLine = "延迟: -";
                    if (last > 0L) {
                        checkLine = "校验: " + formatTimeAgo(last);
                        if (ok) latencyLine = "延迟: " + latency + "ms";
                        else latencyLine = "延迟: 不可达";
                    }
                    String line2 = checkLine + " · " + latencyLine;
                    text = line1 + "\n" + line2;
                }
            } catch (Throwable ignored) {
                text = "";
            }
            tvConversationProviderStatus.setText(text);
        }

        // Right small label: Sub-model
        if (tvConversationSubModelProvider != null) {
            tvConversationSubModelProvider.setText(getString(R.string.sub_model));
        }

        // Right value: selected sub-model for that provider
        if (tvConversationSubModelValue != null) {
            String sub = null;
            if (provider != null) {
                sub = SPManager.getInstance().getSubModel(provider);
                if (TextUtils.isEmpty(sub)) sub = provider.getDefault(LanguageModelField.SubModel);
            }
            tvConversationSubModelValue.setText(!TextUtils.isEmpty(sub) ? sub : getString(R.string.ui_not_set));
            // Ensure marquee works when the sub-model name is long.
            try { tvConversationSubModelValue.setSelected(true); } catch (Throwable ignored) {}

            // Under the selected sub-model show (centered):
            // 1st line: 温度 / 推理 (✔/✘/?)
            // 2nd line: 上限 / 下限 (cached)
            if (llMainSelectedModelTags != null && llMainSelectedModelTagsRow1 != null && llMainSelectedModelTagsRow2 != null) {
                llMainSelectedModelTagsRow1.removeAllViews();
                llMainSelectedModelTagsRow2.removeAllViews();
                llMainSelectedModelTags.setVisibility(View.GONE);

                Boolean t = null;
                Boolean r = null;
                try {
                    if (provider != null && !TextUtils.isEmpty(sub)) {
                        t = SPManager.getInstance().getCachedSupportsTemperature(provider, sub);
                    }
                } catch (Throwable ignored) {}
                try {
                    if (provider != null && !TextUtils.isEmpty(sub)) {
                        r = SPManager.getInstance().getCachedSupportsReasoningThinking(provider, sub);
                    }
                } catch (Throwable ignored) {}

                // 温度
                if (t == null) addMiniChip(llMainSelectedModelTagsRow1, "温度: ?", "#1A9E9E9E", "#757575");
                else if (Boolean.TRUE.equals(t)) addMiniChip(llMainSelectedModelTagsRow1, "温度: ✔", "#1A4CAF50", "#2E7D32");
                else addMiniChip(llMainSelectedModelTagsRow1, "温度: ✘", "#1AE57373", "#C62828");

                // 推理
                if (r == null) addMiniChip(llMainSelectedModelTagsRow1, "推理: ?", "#1A9E9E9E", "#757575");
                else if (Boolean.TRUE.equals(r)) addMiniChip(llMainSelectedModelTagsRow1, "推理: ✔", "#1A4CAF50", "#2E7D32");
                else addMiniChip(llMainSelectedModelTagsRow1, "推理: ✘", "#1AE57373", "#C62828");

                // 输出上限（缓存）
                Integer cap = null;
                Integer lb = null;
                try {
                    if (provider != null && !TextUtils.isEmpty(sub)) {
                        cap = SPManager.getInstance().getCachedSafeMaxTokens(provider, sub);
                        lb = SPManager.getInstance().getCachedSafeMaxTokensLowerBound(provider, sub);
                    }
                } catch (Throwable ignored) {}

                if (cap != null) {
                    addMiniChip(llMainSelectedModelTagsRow2, "上限: " + cap, "#1A1976D2", "#1565C0");
                } else if (lb != null) {
                    addMiniChip(llMainSelectedModelTagsRow2, "下限≥" + lb, "#1A1976D2", "#1565C0");
                } else {
                    addMiniChip(llMainSelectedModelTagsRow2, "上限: 未学", "#1A9E9E9E", "#757575");
                }

                boolean has = (llMainSelectedModelTagsRow1.getChildCount() + llMainSelectedModelTagsRow2.getChildCount()) > 0;
                llMainSelectedModelTags.setVisibility(has ? View.VISIBLE : View.GONE);
            }
        }
    } catch (Throwable ignored) {
    }

    // 输出长度：切换子模型后根据“已缓存上限”自动同步到对应固定档（含自定义→固定档）
    refreshOutputLengthAutoGuardForCurrentModel(true);

    // Update dependent rows
    refreshNormalModelThinkingRowState();
    refreshReasoningModelThinkingRowState();
}



    private static String formatTimeAgo(long epochMs) {
        long now = System.currentTimeMillis();
        long diff = Math.max(0, now - epochMs);
        long sec = diff / 1000L;
        if (sec < 30) return "刚刚";
        if (sec < 60) return sec + "秒前";
        long min = sec / 60L;
        if (min < 60) return min + "分钟前";
        long hr = min / 60L;
        if (hr < 24) return hr + "小时前";
        long day = hr / 24L;
        return day + "天前";
    }

    private static int getProviderIconRes(@Nullable LanguageModel p) {
        if (p == null) return R.drawable.ic_provider_default;
        switch (p) {
            case Gemini:
                return R.drawable.ic_provider_gemini;
            case ChatGPT:
                return R.drawable.ic_provider_chatgpt;
            case DeepSeek:
                return R.drawable.ic_provider_deepseek;
            case Doubao:
                return R.drawable.ic_provider_doubao;
            case Qwen:
                return R.drawable.ic_provider_qwen;
            case Groq:
                return R.drawable.ic_provider_groq;
            case Grok:
                return R.drawable.ic_provider_grok;
            case OpenRouter:
                return R.drawable.ic_provider_openrouter;
            case Claude:
                return R.drawable.ic_provider_claude;
            case Mistral:
                return R.drawable.ic_provider_mistral;
            case Chutes:
                return R.drawable.ic_provider_chutes;
            case Perplexity:
                return R.drawable.ic_provider_perplexity;
            case GLM:
                return R.drawable.ic_provider_zhipuai;
            default:
                return R.drawable.ic_provider_default;
        }
    }

    /**
     * Small refresh button (requested): refresh cached model list for current provider.
     * - Uses stored API Key + Base URL
     * - Updates cached model count
     * - Updates "recent validation" + latency/reachability shown in provider list
     */
    private void refreshCurrentProviderModelCache() {
        if (!isAdded()) return;
        if (!SPManager.isReady()) return;

        final Context ctx = requireContext();
        final SPManager sp = SPManager.getInstance();
        final LanguageModel provider = sp.hasLanguageModel() ? sp.getLanguageModel() : null;
        if (provider == null) return;

        final String apiKey = sp.getApiKey(provider);
        final String baseUrl = sp.getBaseUrl(provider);

        if (TextUtils.isEmpty(apiKey) || TextUtils.isEmpty(baseUrl)) {
            Toast.makeText(ctx, "请先配置当前供应商（API Key / Base URL）", Toast.LENGTH_SHORT).show();
            return;
        }

        // UI: disable + spin
        final ImageView btn = ivConversationProviderRefresh;
        final ObjectAnimator spin;
        if (btn != null) {
            btn.setEnabled(false);
            btn.setAlpha(0.6f);
            spin = ObjectAnimator.ofFloat(btn, View.ROTATION, 0f, 360f);
            spin.setDuration(900);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new android.view.animation.LinearInterpolator());
            spin.start();
        } else {
            spin = null;
        }

        Toast.makeText(ctx, "正在刷新模型缓存…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                ProviderModelsFetcher.Result r = ProviderModelsFetcher.fetchModels(provider, baseUrl, apiKey);
                final List<String> models = r.models != null ? r.models : new ArrayList<>();
                final long latency = r.latencyMs;
                sp.setCachedModels(provider, baseUrl, models);
                try { sp.setProviderHealth(provider, true, latency, null); } catch (Throwable ignored) {}

                // If current sub-model is empty, pick the first one.
                try {
                    String curSub = sp.getSubModel(provider);
                    if (TextUtils.isEmpty(curSub) && !models.isEmpty()) {
                        sp.setSubModel(provider, models.get(0));
                    }
                } catch (Throwable ignored) {
                }

                final int count = models.size();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    if (spin != null) { try { spin.cancel(); } catch (Throwable ignored) {} }
                    if (btn != null) {
                        btn.setRotation(0f);
                        btn.setEnabled(true);
                        btn.setAlpha(1f);
                    }
                    Toast.makeText(ctx, "已刷新：" + count + " 个模型（" + latency + "ms）", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                final long latency = Math.max(0, android.os.SystemClock.elapsedRealtime() - started);
                try { sp.setProviderHealth(provider, false, latency, e.getMessage()); } catch (Throwable ignored) {}
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    if (spin != null) { try { spin.cancel(); } catch (Throwable ignored) {} }
                    if (btn != null) {
                        btn.setRotation(0f);
                        btn.setEnabled(true);
                        btn.setAlpha(1f);
                    }
                    Toast.makeText(ctx, "刷新失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    

    private void refreshConversationUiAfterSubModelCommit() {
        refreshConversationSubModelRow();
        try { refreshOutputLengthRowUi(); } catch (Throwable ignored) {}
    }

    @Override
    public void onProviderConfirmed(@Nullable LanguageModel provider) {
        if (!SPManager.isReady()) return;
        try {
            SPManager sp = SPManager.getInstance();
            if (provider == null) {
                sp.clearLanguageModel();
            } else {
                sp.setLanguageModel(provider);
            }
            refreshConversationSubModelRow();
            sendConfigBroadcast();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onBackupModelConfirmed(@Nullable LanguageModel model) {
        try {
            if (onBackupModelPicked != null) {
                onBackupModelPicked.accept(model);
            }
        } catch (Throwable ignored) {
        } finally {
            onBackupModelPicked = null;
        }
    }

    private void showConversationProviderPicker() {
        if (!isAdded()) return;
        ProviderListDialogFragment.newInstance()
                .show(getChildFragmentManager(), ProviderListDialogFragment.TAG);
    }
	private void showConversationSubModelPicker() {
		if (!SPManager.isReady()) return;
		if (getContext() == null) return;

		final SPManager sp = SPManager.getInstance();

		final LanguageModel provider;
		try {
			provider = sp.hasLanguageModel() ? sp.getLanguageModel() : null;
		} catch (Throwable t) {
			return;
		}
		if (provider == null) {
			android.widget.Toast.makeText(requireContext(), "请先选择 AI 大模型", android.widget.Toast.LENGTH_SHORT).show();
			showConversationProviderPicker();
			return;
		}

		String current = null;
		try {
			current = sp.getSubModel(provider);
		} catch (Throwable ignored) {
		}
		if (TextUtils.isEmpty(current) && provider != null) current = provider.getDefault(LanguageModelField.SubModel);
		final String originalSelectedModel = current;

		// IMPORTANT (UX): the user expects the list to reflect the *cached* models only.
		// Do NOT mix in presets here, otherwise the list count can exceed "已缓存".
		List<String> suggestions;
		try {
			suggestions = sp.getCachedModels(provider);
		} catch (Throwable t) {
			suggestions = java.util.Collections.emptyList();
		}
		List<String> customSaved = java.util.Collections.emptyList();
		try {
			customSaved = sp.getCustomSubModels(provider);
		} catch (Throwable ignored) {
		}
		final java.util.ArrayList<String> workingCustom = new java.util.ArrayList<>();
		if (customSaved != null) {
			for (String s : customSaved) {
				if (s == null) continue;
				String v = s.trim();
				if (v.isEmpty()) continue;
				// keep insertion order, dedupe
				boolean exists = false;
				for (String t : workingCustom) {
					if (v.equals(t)) {
						exists = true;
						break;
					}
				}
				if (!exists) workingCustom.add(v);
			}
		}
		final int[] customCount = new int[]{workingCustom.size()};

		// Cached count should match the shown official list (deduped).
		final java.util.LinkedHashSet<String> cachedDedup = new java.util.LinkedHashSet<>();
		if (suggestions != null) {
			for (String s : suggestions) {
				if (s == null) continue;
				String v = s.trim();
				if (!v.isEmpty()) cachedDedup.add(v);
			}
		}
		final java.util.ArrayList<String> cachedList = new java.util.ArrayList<>(cachedDedup);
		java.util.Collections.sort(cachedList);
		final int cachedCount = cachedList.size();

		// Build data source: official models first, then user custom models at the end.
		final java.util.HashSet<String> customSet = new java.util.HashSet<>();
		for (String s : workingCustom) customSet.add(s);
		final java.util.ArrayList<SubModelItem> fullItems = new java.util.ArrayList<>();
		final java.util.HashSet<String> seen = new java.util.HashSet<>();
		final java.util.HashSet<String> officialSet = new java.util.HashSet<>();
		if (cachedList != null) {
			for (String s : cachedList) {
				if (s == null) continue;
				String v = s.trim();
				if (v.isEmpty()) continue;
				if (customSet.contains(v)) continue; // custom always shown at the end
				if (seen.add(v)) {
					officialSet.add(v);
					fullItems.add(new SubModelItem(v, false));
				}
			}
		}
		for (String s : workingCustom) {
			if (s == null) continue;
			String v = s.trim();
			if (v.isEmpty()) continue;
			if (seen.add(v)) fullItems.add(new SubModelItem(v, true));
		}
		// Ensure the currently selected model is locatable even if it is not in suggestions/custom yet.
		if (!TextUtils.isEmpty(originalSelectedModel)) {
			String v = originalSelectedModel.trim();
			if (!v.isEmpty() && seen.add(v)) {
				// Treat as custom so it stays at the end (and can be edited/deleted later)
				workingCustom.add(v);
				customSet.add(v);
				customCount[0] = workingCustom.size();
				fullItems.add(new SubModelItem(v, true));
			}
		}

		final View root = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_conversation_submodel, null);

		final TextView tvTitleLabel = root.findViewById(R.id.tv_title_label);
		final TextView tvTitleName = root.findViewById(R.id.tv_title_name);
		final TextView tvTitleHint = root.findViewById(R.id.tv_title_hint);
		final LinearLayout llTitleTags = root.findViewById(R.id.row_title_tags);
		final TextView tvBottomStatus = root.findViewById(R.id.tv_bottom_status);
		final TextView tvBottomPending = root.findViewById(R.id.tv_bottom_pending);
		final RecyclerView rv = root.findViewById(R.id.rv_models);
		final EditText etSmart = root.findViewById(R.id.et_smart_input);
		final TextView tvInputCancel = root.findViewById(R.id.tv_input_cancel);
		final TextView tvInputAdd = root.findViewById(R.id.tv_input_add);
		final View btnBottomLocate = root.findViewById(R.id.btn_bottom_locate);
		final com.google.android.material.button.MaterialButton btnBottomFavorite = root.findViewById(R.id.btn_bottom_favorite); // "清筛"
		final View btnBottomCancel = root.findViewById(R.id.btn_bottom_cancel);
		final com.google.android.material.button.MaterialButton btnBottomOk = root.findViewById(R.id.btn_bottom_ok);
		final com.google.android.material.chip.ChipGroup chipFilters = root.findViewById(R.id.chip_group_submodel_filters);
		final com.google.android.material.button.MaterialButton btnSort = root.findViewById(R.id.btn_submodel_sort);

		final LinearLayoutManager lm = new LinearLayoutManager(requireContext());
		if (rv != null) {
			rv.setLayoutManager(lm);
		}

		// Starred models (persisted)
		final java.util.HashSet<String> starred = new java.util.HashSet<>();
		try {
			java.util.Set<String> s2 = sp.getStarredSubModels(provider);
			if (s2 != null) starred.addAll(s2);
		} catch (Throwable ignored) {
		}

		final String[] appliedModel = new String[]{originalSelectedModel};
		final String[] pendingModel = new String[]{originalSelectedModel};
		final boolean[] favoritesOnly = new boolean[]{false};
		final SubModelPickerAdapter adapter = new SubModelPickerAdapter(provider, fullItems, originalSelectedModel, originalSelectedModel, starred);
		if (rv != null) {
			rv.setAdapter(adapter);
			// Reduce visual "jitter" when the dialog first appears (RecyclerView can animate its first layout
			// and the subsequent scroll-to-applied can look like a shake).
			try { rv.setItemAnimator(null); } catch (Throwable ignored) {}
		}
		// Pre-position the list near the applied model BEFORE showing the dialog, so we don't "jump" after show.
		try {
			if (rv != null) {
				int initIdx = adapter.getAppliedIndexInShown();
				if (initIdx >= 0) {
					try { lm.scrollToPositionWithOffset(initIdx, 0); } catch (Throwable ignored) { rv.scrollToPosition(initIdx); }
				}
			}
		} catch (Throwable ignored) {}

		final java.util.LinkedHashMap<Integer, SubModelPickerAdapter.FilterMode> filterIdMap = new java.util.LinkedHashMap<>();
		final java.util.concurrent.atomic.AtomicBoolean syncingFilterChips = new java.util.concurrent.atomic.AtomicBoolean(false);
			final Runnable[] updateBottomStatusRef = new Runnable[]{() -> {}};
			final Runnable[] updateClearBtnRef = new Runnable[]{() -> {}};
		final Runnable refreshFilterChipUi = () -> {
			if (chipFilters == null) return;
			try { syncingFilterChips.set(true); } catch (Throwable ignored) {}
			try {
				SubModelPickerAdapter.FilterMode fm = adapter.getFilterMode();
				for (java.util.Map.Entry<Integer, SubModelPickerAdapter.FilterMode> e : filterIdMap.entrySet()) {
					com.google.android.material.chip.Chip c = chipFilters.findViewById(e.getKey());
					if (c != null) c.setChecked(fm == e.getValue());
				}
			} catch (Throwable ignored) {}
			try { syncingFilterChips.set(false); } catch (Throwable ignored) {}
		};
		if (chipFilters != null) {
			try { chipFilters.removeAllViews(); } catch (Throwable ignored) {}
			java.util.LinkedHashMap<String, SubModelPickerAdapter.FilterMode> defs = new java.util.LinkedHashMap<>();
			defs.put("全部", SubModelPickerAdapter.FilterMode.ALL);
			defs.put("已测上限", SubModelPickerAdapter.FilterMode.TESTED_CAP);
			defs.put("推理支持", SubModelPickerAdapter.FilterMode.REASONING_SUPPORTED);
			defs.put("未测试", SubModelPickerAdapter.FilterMode.UNTESTED);
			defs.put("收藏", SubModelPickerAdapter.FilterMode.STARRED);
			for (java.util.Map.Entry<String, SubModelPickerAdapter.FilterMode> e : defs.entrySet()) {
				com.google.android.material.chip.Chip c = buildSubModelFilterChip(chipFilters.getContext(), e.getKey());
				int cid = View.generateViewId();
				c.setId(cid);
				filterIdMap.put(cid, e.getValue());
				chipFilters.addView(c);
			}
			refreshFilterChipUi.run();
			chipFilters.setOnCheckedStateChangeListener((group, checkedIds) -> {
				if (syncingFilterChips.get()) return;
				SubModelPickerAdapter.FilterMode target = SubModelPickerAdapter.FilterMode.ALL;
				if (checkedIds != null && !checkedIds.isEmpty()) {
					SubModelPickerAdapter.FilterMode m = filterIdMap.get(checkedIds.get(0));
					if (m != null) target = m;
				}
				adapter.setFilterMode(target);
				boolean fav = (target == SubModelPickerAdapter.FilterMode.STARRED);
				favoritesOnly[0] = fav;
				adapter.setShowStarredOnly(fav);
				if (etSmart != null) etSmart.setText(etSmart.getText());
				updateBottomStatusRef[0].run();
				updateClearBtnRef[0].run();
			});
		}

		if (btnSort != null) {
			btnSort.setText(getSubModelSortModeLabel(adapter.getSortMode()));
			btnSort.setOnClickListener(v -> showSubModelSortDialog(adapter.getSortMode(), mode -> {
				adapter.setSortMode(mode);
				btnSort.setText(getSubModelSortModeLabel(mode));
				updateBottomStatusRef[0].run();
				updateClearBtnRef[0].run();
			}));
		}

		final Runnable updateHeader = () -> {
			String show = appliedModel[0]; // header ALWAYS reflects applied/current model only
			if (tvTitleName != null) {
				tvTitleName.setText(!TextUtils.isEmpty(show) ? show : getString(R.string.ui_not_set));				try {
					tvTitleName.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
					tvTitleName.setMarqueeRepeatLimit(-1);
					tvTitleName.setSelected(true);
				} catch (Throwable ignored) {}
			}
			try {
				bindSubModelHeaderCapabilitySummary(provider, show, llTitleTags, tvTitleHint);
			} catch (Throwable ignored) {}
		};

		updateBottomStatusRef[0] = () -> {
			if (tvBottomStatus != null) {
				int appliedIdx = adapter.getAppliedIndexInFull();
				int total = cachedCount + customCount[0];
				String status = "已缓存" + cachedCount + "个 · 自定义" + customCount[0] + "个 · 共" + total + "个";
				status += " · 筛选:" + getSubModelFilterModeLabel(adapter.getFilterMode());
				status += " · 排序:" + getSubModelSortModeStatusLabel(adapter.getSortMode());
				if (appliedIdx >= 0) status += " · 当前已应用第" + (appliedIdx + 1) + "项";
				tvBottomStatus.setText(status);
			}
			if (tvBottomPending != null) {
				String a = appliedModel[0];
				String p0 = pendingModel[0];
				if (!TextUtils.equals(a, p0) && !TextUtils.isEmpty(p0)) {
					int pIdx = adapter.getPendingIndexInFull();
					String txt = "临时选中：" + p0 + (pIdx >= 0 ? "（第" + (pIdx + 1) + "项，未应用）" : "（未应用）");
					tvBottomPending.setText(txt);
					tvBottomPending.setVisibility(View.VISIBLE);
				} else {
					tvBottomPending.setVisibility(View.GONE);
				}
			}
			if (btnBottomOk != null) {
				boolean changed = !TextUtils.equals(appliedModel[0], pendingModel[0]);
				btnBottomOk.setEnabled(changed);
				btnBottomOk.setText(changed ? "切换" : getString(R.string.btn_ok));
			}
		};
		updateHeader.run();
		if (btnBottomFavorite != null) { try { btnBottomFavorite.setText("清筛"); } catch (Throwable ignored) {} }
		updateBottomStatusRef[0].run();

		// "清筛" only shows when there is a non-default search/filter/sort state.
		updateClearBtnRef[0] = () -> {
			if (btnBottomFavorite == null) return;
			boolean dirty = false;
			try {
				if (etSmart != null && etSmart.getText() != null) {
					dirty = etSmart.getText().toString().trim().length() > 0;
				}
			} catch (Throwable ignored) {}
			try {
				if (!dirty && adapter.getFilterMode() != SubModelPickerAdapter.FilterMode.ALL) dirty = true;
			} catch (Throwable ignored) {}
			try {
				if (!dirty && adapter.getSortMode() != SubModelPickerAdapter.SortMode.DEFAULT) dirty = true;
			} catch (Throwable ignored) {}
			btnBottomFavorite.setVisibility(dirty ? View.VISIBLE : View.GONE);
		};
		updateClearBtnRef[0].run();

		// Title label is decorative only.
		if (tvTitleLabel != null) {
			tvTitleLabel.setClickable(false);
			tvTitleLabel.setFocusable(false);
		}
		// Model name is NOT a locate trigger anymore.
		if (tvTitleName != null) {
			tvTitleName.setClickable(false);
			// Keep focusable for marquee scrolling.
		}

		// Smart input box: unified search + add custom
		if (etSmart != null) {
			etSmart.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					String q = s != null ? s.toString() : "";
					adapter.setQuery(q);
					boolean has = q != null && q.trim().length() > 0;
					// Only show [取消/添加] when the query has NO matches in the cached list.
					boolean noMatch = has && adapter.getItemCount() == 0;
					if (tvInputCancel != null) tvInputCancel.setVisibility(noMatch ? View.VISIBLE : View.GONE);
					if (tvInputAdd != null) tvInputAdd.setVisibility(noMatch ? View.VISIBLE : View.GONE);
					updateClearBtnRef[0].run();
				}

				@Override
				public void afterTextChanged(Editable s) {
				}
			});
		}

		// Bottom console: [定位] click=定位到已应用；long-press=定位到临时选中（若存在）
		if (btnBottomLocate != null && rv != null) {
			final Runnable resetFilterAndSearch = () -> {
				favoritesOnly[0] = false;
				adapter.setLegacyStarredOnlyCompat(false);
				refreshFilterChipUi.run();
				if (etSmart != null) {
					etSmart.setText("");
					try { etSmart.clearFocus(); } catch (Throwable ignored) {}
				}
				updateClearBtnRef[0].run();
			};
			btnBottomLocate.setOnClickListener(v -> {
				resetFilterAndSearch.run();
				rv.post(() -> {
					int idx = adapter.getAppliedIndexInShown();
					if (idx < 0) return;
					try { rv.smoothScrollToPosition(idx); } catch (Throwable t) { rv.scrollToPosition(idx); }
				});
			});
			btnBottomLocate.setOnLongClickListener(v -> {
				if (TextUtils.equals(appliedModel[0], pendingModel[0]) || TextUtils.isEmpty(pendingModel[0])) {
					android.widget.Toast.makeText(requireContext(), "当前没有未应用的临时选中项", android.widget.Toast.LENGTH_SHORT).show();
					return true;
				}
				resetFilterAndSearch.run();
				rv.post(() -> {
					int idx = adapter.getPendingIndexInShown();
					if (idx < 0) return;
					try { rv.smoothScrollToPosition(idx); } catch (Throwable t) { rv.scrollToPosition(idx); }
				});
				android.widget.Toast.makeText(requireContext(), "已定位到临时选中项（未应用）", android.widget.Toast.LENGTH_SHORT).show();
				return true;
			});
		}

		// Bottom console: [清筛] reset search/filter/sort (轻量入口)
		if (btnBottomFavorite != null) {
			try { btnBottomFavorite.setText("清筛"); } catch (Throwable ignored) {}
			btnBottomFavorite.setOnClickListener(v -> {
				boolean changed = false;
				try {
					if (etSmart != null && etSmart.getText() != null && etSmart.getText().length() > 0) {
						etSmart.setText("");
						changed = true;
					}
				} catch (Throwable ignored) {}
				try {
					if (adapter.getFilterMode() != SubModelPickerAdapter.FilterMode.ALL) {
						adapter.setFilterMode(SubModelPickerAdapter.FilterMode.ALL);
						favoritesOnly[0] = false;
						adapter.setShowStarredOnly(false);
						adapter.setLegacyStarredOnlyCompat(false);
						refreshFilterChipUi.run();
						changed = true;
					}
				} catch (Throwable ignored) {}
				try {
					if (adapter.getSortMode() != SubModelPickerAdapter.SortMode.DEFAULT) {
						adapter.setSortMode(SubModelPickerAdapter.SortMode.DEFAULT);
						if (btnSort != null) btnSort.setText(getSubModelSortModeLabel(SubModelPickerAdapter.SortMode.DEFAULT));
						changed = true;
					}
				} catch (Throwable ignored) {}
				try { if (etSmart != null) etSmart.setText(etSmart.getText()); } catch (Throwable ignored) {}
				updateBottomStatusRef[0].run();
				updateClearBtnRef[0].run();
				if (changed) {
					android.widget.Toast.makeText(requireContext(), "已清除搜索/筛选/排序", android.widget.Toast.LENGTH_SHORT).show();
				}
			});
		}

		// Smart input actions
		if (tvInputCancel != null && etSmart != null) {
			tvInputCancel.setOnClickListener(v -> {
				etSmart.setText("");
				try {
					etSmart.clearFocus();
				} catch (Throwable ignored) {
				}
			});
		}

		final Runnable rebuildAll = () -> {
			java.util.HashSet<String> curCustom = new java.util.HashSet<>();
			for (String s : workingCustom) {
				if (s != null && !s.trim().isEmpty()) curCustom.add(s.trim());
			}
			java.util.ArrayList<SubModelItem> rebuilt = new java.util.ArrayList<>();
			java.util.HashSet<String> seen2 = new java.util.HashSet<>();
			if (cachedList != null) {
				for (String s : cachedList) {
					if (s == null) continue;
					String v = s.trim();
					if (v.isEmpty()) continue;
					if (curCustom.contains(v)) continue;
					if (seen2.add(v)) rebuilt.add(new SubModelItem(v, false));
				}
			}
			for (String s : workingCustom) {
				if (s == null) continue;
				String v = s.trim();
				if (v.isEmpty()) continue;
				if (seen2.add(v)) rebuilt.add(new SubModelItem(v, true));
			}
			adapter.setFullItems(rebuilt);
			customCount[0] = workingCustom.size();
			updateHeader.run();
			updateBottomStatusRef[0].run();
		};

		if (tvInputAdd != null && etSmart != null) {
			tvInputAdd.setOnClickListener(v -> {
				String typed = etSmart.getText() != null ? etSmart.getText().toString().trim() : "";
				if (TextUtils.isEmpty(typed)) return;
				// If it already exists as an official model, just select it.
				boolean inOfficial = officialSet.contains(typed);
				if (inOfficial) {
					pendingModel[0] = typed;
					adapter.setSelected(typed);
					updateBottomStatusRef[0].run();
				} else {
					for (int i = workingCustom.size() - 1; i >= 0; i--) {
						if (typed.equals(workingCustom.get(i))) workingCustom.remove(i);
					}
					workingCustom.add(typed);
					rebuildAll.run();
					pendingModel[0] = typed;
					adapter.setSelected(typed);
					updateBottomStatusRef[0].run();
					if (rv != null) {
						rv.post(() -> {
							int idx = adapter.getPendingIndexInShown();
							if (idx < 0) return;
							try {
								lm.scrollToPositionWithOffset(idx, rv.getHeight() / 3);
							} catch (Throwable t) {
								rv.scrollToPosition(idx);
							}
						});
					}
				}
				// Clear input & reset filter
				etSmart.setText("");
			});
		}


		// Item click -> stage selection ONLY (no persistence until dialog OK)
		adapter.setOnSelectionChangedListener(item -> {
			pendingModel[0] = item;
			updateBottomStatusRef[0].run();
		});

		adapter.setOnItemLongPressListener(itemName -> {
			if (!isAdded() || TextUtils.isEmpty(itemName)) return;
			showModelCapabilityTestDialog(provider, itemName, () -> {
				try { adapter.notifyDataSetChanged(); } catch (Throwable ignored) {}
				try { updateHeader.run(); } catch (Throwable ignored) {}
				try { updateBottomStatusRef[0].run(); } catch (Throwable ignored) {}
			});
		});

		adapter.setOnCustomActionListener(new SubModelPickerAdapter.CustomActionListener() {
			@Override
			public void onEdit(@NonNull String name) {
				if (!isAdded()) return;
				View field = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_rename_custom_submodel, null);
				final EditText et = field.findViewById(R.id.et_name);
				final View bCancel = field.findViewById(R.id.btn_cancel);
				final View bOk = field.findViewById(R.id.btn_ok);
				if (et != null) {
					et.setText(name);
					try {
						et.setSelection(name.length());
					} catch (Throwable ignored) {
					}
				}
				final androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(requireContext())
						.setView(field)
						.create();
				if (bCancel != null) bCancel.setOnClickListener(v -> dlg.dismiss());
				if (bOk != null) bOk.setOnClickListener(v -> {
					String typed = et != null && et.getText() != null ? et.getText().toString().trim() : "";
					if (TextUtils.isEmpty(typed)) {
						android.widget.Toast.makeText(requireContext(), getString(R.string.ui_invalid_value), android.widget.Toast.LENGTH_SHORT).show();
						return;
					}
					// rename in working custom list
					for (int i = workingCustom.size() - 1; i >= 0; i--) {
						if (name.equals(workingCustom.get(i))) workingCustom.remove(i);
					}
					for (int i = workingCustom.size() - 1; i >= 0; i--) {
						if (typed.equals(workingCustom.get(i))) workingCustom.remove(i);
					}
					workingCustom.add(typed);
					// keep star on rename
					try {
						if (starred.remove(name)) {
							starred.add(typed);
							sp.setStarredSubModels(provider, starred);
						}
					} catch (Throwable ignored) {}
					rebuildAll.run();
					if (name.equals(pendingModel[0])) {
						pendingModel[0] = typed;
						adapter.setSelected(typed);
						updateBottomStatusRef[0].run();
					}
					dlg.dismiss();
				});
				dlg.show();
			}

			@Override
			public void onDelete(@NonNull String name) {
				if (!isAdded()) return;
				new MaterialAlertDialogBuilder(requireContext())
						.setTitle(getString(R.string.ui_delete_custom_model))
						.setMessage(getString(R.string.ui_delete_custom_model_confirm, name))
						.setNegativeButton(R.string.btn_cancel, null)
						.setPositiveButton(R.string.btn_ok, (d, w) -> {
							for (int i = workingCustom.size() - 1; i >= 0; i--) {
								if (name.equals(workingCustom.get(i))) workingCustom.remove(i);
							}
						try {
							if (starred.remove(name)) sp.setStarredSubModels(provider, starred);
						} catch (Throwable ignored) {}
							rebuildAll.run();
							if (name.equals(pendingModel[0])) {
								String fallback = adapter.getFirstItemName();
								pendingModel[0] = fallback;
								adapter.setSelected(fallback);
								updateBottomStatusRef[0].run();
							}
						})
						.show();
			}
		});

		adapter.setOnStarToggleListener((name, isStarred) -> {
			try {
				if (isStarred) starred.add(name);
				else starred.remove(name);
				sp.setStarredSubModels(provider, starred);
			} catch (Throwable ignored) {
			}
			// If we are filtering favorites and user unstars, refresh immediately.
			if (favoritesOnly[0]) {
				adapter.setLegacyStarredOnlyCompat(true);
				refreshFilterChipUi.run();
			}
		});

		final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
				.setView(root)
				.create();
		// Configure window BEFORE showing to avoid visible "shake".
		// Keep the same "center pop" feel as the Provider list dialog.
		try {
			android.view.Window w = dialog.getWindow();
			if (w != null) {
				int screenH = getResources().getDisplayMetrics().heightPixels;
				int targetH = (int) (screenH * 0.78f);
				w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, targetH);
				View decor = w.getDecorView();
				if (decor != null) decor.setPadding(0, 0, 0, 0);
				w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
				// Do NOT set gravity/animations here; let the platform/dialog theme handle it to match ProviderListDialog.
			}
		} catch (Throwable ignored) {
		}

		final boolean[] confirmedApply = new boolean[]{false};
		final boolean[] suppressDismissToast = new boolean[]{false};

		dialog.setOnDismissListener(d -> {
			if (confirmedApply[0] || suppressDismissToast[0]) return;
			try {
				if (!TextUtils.equals(appliedModel[0], pendingModel[0])) {
					android.widget.Toast.makeText(requireContext(), "未确认切换，仍使用 " + (TextUtils.isEmpty(appliedModel[0]) ? getString(R.string.ui_not_set) : appliedModel[0]), android.widget.Toast.LENGTH_SHORT).show();
				}
			} catch (Throwable ignored) {}
		});

		// Dialog global actions
		if (btnBottomCancel != null) {
			btnBottomCancel.setOnClickListener(v -> {
				if (!TextUtils.equals(appliedModel[0], pendingModel[0])) {
					android.widget.Toast.makeText(requireContext(), "已取消临时选择，当前仍使用 " + (TextUtils.isEmpty(appliedModel[0]) ? getString(R.string.ui_not_set) : appliedModel[0]), android.widget.Toast.LENGTH_SHORT).show();
				}
					suppressDismissToast[0] = true;
				dialog.dismiss();
			});
		}
		if (btnBottomOk != null) {
			btnBottomOk.setOnClickListener(v -> {
				String chosen = pendingModel[0] != null ? pendingModel[0].trim() : "";
				if (TextUtils.isEmpty(chosen)) {
					android.widget.Toast.makeText(requireContext(), getString(R.string.ui_invalid_value), android.widget.Toast.LENGTH_SHORT).show();
					return;
				}
				try {
					try {
						AiDiagnostics.append("SUBMODEL_COMMIT", "provider=" + provider + " from=" + String.valueOf(appliedModel[0]) + " to=" + chosen);
					} catch (Throwable ignoredDiag) {}
					// Persist staged custom CRUD as a JSON array
					boolean inOfficial = officialSet.contains(chosen);
					if (!inOfficial) {
						for (int i = workingCustom.size() - 1; i >= 0; i--) {
							if (chosen.equals(workingCustom.get(i))) workingCustom.remove(i);
						}
						workingCustom.add(chosen);
					}
					sp.setCustomSubModels(provider, workingCustom);
					// Persist starred set too (it may already be saved on toggle)
					sp.setStarredSubModels(provider, starred);
					sp.setSubModel(provider, chosen);
					confirmedApply[0] = true;
					suppressDismissToast[0] = true;
					appliedModel[0] = chosen;
					refreshConversationUiAfterSubModelCommit();
					sendConfigBroadcast();
				} catch (Throwable ignored) {
				}
				dialog.dismiss();
			});
		}

		dialog.show();
	}

private void showCustomSubModelInputDialog(@NonNull LanguageModel provider, @Nullable String current) {
        View field = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_configure_model_field, null);
        TextInputEditText et = field.findViewById(R.id.field_edit);
        TextInputLayout til = field.findViewById(R.id.field_layout);
        if (til != null) {
            til.setHint(getString(R.string.sub_model));
        }
        if (et != null && current != null) {
            et.setText(current);
            et.setSelection(current.length());
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.sub_model) + " · " + provider.label)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String typed = et != null && et.getText() != null ? et.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(typed)) return;
                    try {
                        SPManager.getInstance().setSubModel(provider, typed);
                        refreshConversationUiAfterSubModelCommit();
                        sendConfigBroadcast();
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


    private enum CapabilityProbeType {
        NORMAL_TEMPERATURE,
        REASONING_NATIVE,
        OUTPUT_MAX_TOKENS
    }

    private enum OutputCapProbeMode {
        CONSERVATIVE,
        PRECISE
    }

    private static final int CAP_PROBE_MAX_TOKENS_NORMAL = 128;
    private static final int CAP_PROBE_MAX_TOKENS_REASONING = 4096;
    private static final int CAP_PROBE_OUTPUT_START_DEFAULT = 131072;
    private static final int CAP_PROBE_OUTPUT_START_CONSERVATIVE = 8192;
    private static final int CAP_PROBE_OUTPUT_MAX_BINARY_STEPS = 6;

    private boolean isChatCapabilityTestable(@NonNull LanguageModel provider, @Nullable String subModel) {
        try { return ModelCapabilities.isChatCapabilityTestable(provider, subModel); } catch (Throwable ignored) { return true; }
    }

    @NonNull
    private String buildCapabilityProbeParamSummary(@NonNull CapabilityProbeType type) {
        if (type == CapabilityProbeType.REASONING_NATIVE) {
            return "测试参数：reasoning_effort=low, max_tokens=" + CAP_PROBE_MAX_TOKENS_REASONING + "（测试专用）";
        }
        return "测试参数：temperature=0.5, top_p=0.9, max_tokens=" + CAP_PROBE_MAX_TOKENS_NORMAL + "（测试专用）";
    }

    @NonNull
    private String buildCapabilityProbeBudgetNote(@NonNull CapabilityProbeType type) {
        if (type == CapabilityProbeType.REASONING_NATIVE) {
            return "thinking budget：未显式传递（provider 默认）；不读取用户当前“输出长度”配置";
        }
        return "测试说明：不读取用户当前“输出长度”配置";
    }


    @NonNull
    private String buildCapabilityProbePurposeNote(@NonNull CapabilityProbeType type) {
        if (type == CapabilityProbeType.REASONING_NATIVE) {
            return "测试用途：仅检测原生推理参数是否支持（非性能测试；不代表推理质量/速度）";
        }
        return "测试用途：仅检测采样参数能力（temperature / top_p），非性能测试";
    }

    @NonNull
    private String buildCapabilityProbeCostNote(@NonNull CapabilityProbeType type, boolean chatProbeTestable) {
        if (!chatProbeTestable) {
            return "费用提示：当前模型疑似非聊天模型，本面板不会发起 chat 能力测试请求";
        }
        if (type == CapabilityProbeType.REASONING_NATIVE) {
            return "费用提示：点击测试会发送一次真实短请求，可能消耗少量额度（按供应商计费规则）";
        }
        return "费用提示：点击测试会发送一次真实短请求（很短），可能产生少量费用";
    }

    @Nullable
    private String buildCapabilityProbeStaticPrediction(@NonNull CapabilityProbeType type, @NonNull String modelName) {
        if (TextUtils.isEmpty(modelName)) return null;
        String m = modelName.toLowerCase(java.util.Locale.US);
        if (type == CapabilityProbeType.NORMAL_TEMPERATURE) {
            if (m.contains("gpt-5") || m.contains("o1") || m.contains("o3") || m.contains("reason")) {
                return "预判：部分新推理模型可能弱化/忽略采样参数（建议实测确认）";
            }
            return null;
        }
        if (m.startsWith("gpt-3.5") || m.contains("turbo-0125") || m.contains("0613") || m.contains("1106")) {
            return "预判：该模型大概率不支持原生推理参数 reasoning_effort（可手动测试确认）";
        }
        if (m.contains("whisper") || m.contains("embedding") || m.contains("tts") || m.contains("speech")) {
            return "预判：该模型属于非聊天能力模型，原生推理测试不适用";
        }
        return null;
    }

    private void logCapabilityProbe(@NonNull String stage, @NonNull LanguageModel provider, @NonNull String subModel, @NonNull CapabilityProbeType type, @Nullable String extra) {
        try {
            String msg = "stage=" + stage
                    + ", provider=" + provider.name()
                    + ", subModel=" + subModel
                    + ", type=" + (type == CapabilityProbeType.REASONING_NATIVE ? "reasoning" : "temperature")
                    + ", " + buildCapabilityProbeParamSummary(type)
                    + (extra == null || extra.trim().isEmpty() ? "" : (", " + extra));
            Logger.log("CAP_PROBE", msg);
        } catch (Throwable ignored) {}
    }

    private void showModelCapabilityTestDialog(@NonNull LanguageModel provider, @NonNull String subModel, @Nullable Runnable onChanged) {
        if (!isAdded()) return;
        final String modelName = subModel == null ? "" : subModel.trim();
        if (TextUtils.isEmpty(modelName)) return;

        final Context ctx = requireContext();
        final boolean chatProbeTestable = isChatCapabilityTestable(provider, modelName);
        final androidx.core.widget.NestedScrollView contentScroll = new androidx.core.widget.NestedScrollView(ctx);
        contentScroll.setFillViewport(true);
        try { contentScroll.setClipToPadding(false); } catch (Throwable ignored) {}
        final LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, (int) (10 * ctx.getResources().getDisplayMetrics().density));
        contentScroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView tip = new TextView(ctx);
        tip.setText("模型：" + modelName
                + "\n点击右侧【测试】发送短探针请求；测试结果会写入缓存并实时刷新 UI。长按子模型也可打开此面板。"
                + "\n说明：本面板用于能力判定（支持/不支持/未测），不是性能压测；测试可能产生少量费用。"
                + (chatProbeTestable ? "" : "\n当前模型疑似非聊天模型（embedding / tts / whisper 等），不建议进行 chat 能力测试。"));
        tip.setTextSize(13f);
        try { tip.setTextColor(com.google.android.material.color.MaterialColors.getColor(tip, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
        root.addView(tip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView normalStatusTv = new TextView(ctx);
        final TextView reasoningStatusTv = new TextView(ctx);
        final TextView outputCapStatusTv = new TextView(ctx);
        final TextView outputCapMetaTv = new TextView(ctx);
        final TextView outputCapErrTv = new TextView(ctx);
        final TextView outputCapErrRawTv = new TextView(ctx);
        final java.util.concurrent.atomic.AtomicBoolean outputCapErrExpanded = new java.util.concurrent.atomic.AtomicBoolean(false);
        final AtomicBoolean probeRunning = new AtomicBoolean(false);

        // Per-model manual-result protection (recommended): prevents auto-learning from overwriting manual cap results
        try {
            final SPManager sp = SPManager.getInstance();
            LinearLayout protectCard = new LinearLayout(ctx);
            protectCard.setOrientation(LinearLayout.VERTICAL);
            int pp = (int) (10 * ctx.getResources().getDisplayMetrics().density);
            protectCard.setPadding(pp, pp, pp, pp);
            try { protectCard.setBackgroundResource(R.drawable.bg_dialog_rounded_stroke); } catch (Throwable ignored) {}
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            LinearLayout left = new LinearLayout(ctx);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView ttl = new TextView(ctx);
            ttl.setText("🔒 保护手动结果（推荐）");
            ttl.setTextSize(14f);
            try { ttl.setTextColor(com.google.android.material.color.MaterialColors.getColor(ttl, com.google.android.material.R.attr.colorOnSurface)); } catch (Throwable ignored) {}
            left.addView(ttl);
            TextView sub = new TextView(ctx);
            sub.setTextSize(11f);
            sub.setText("开启后：自动学习仅记录观测，不覆盖你手动测得的输出上限");
            try { sub.setTextColor(com.google.android.material.color.MaterialColors.getColor(sub, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            left.addView(sub);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            androidx.appcompat.widget.SwitchCompat sw = new androidx.appcompat.widget.SwitchCompat(ctx);
            sw.setChecked(sp.isOutputCapManualProtectEnabled(provider, modelName));
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    sp.setOutputCapManualProtectOverride(provider, modelName, isChecked);
                    refreshOutputCapProbeStatusText(provider, modelName, outputCapStatusTv, outputCapMetaTv, outputCapErrTv, outputCapErrRawTv, outputCapErrExpanded);
                    if (onChanged != null) onChanged.run();
                    android.widget.Toast.makeText(ctx, isChecked ? "已开启手动结果保护" : "已关闭手动结果保护", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            });
            row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            protectCard.addView(row);

            TextView policyLink = new TextView(ctx);
            policyLink.setText("自动学习策略配置…");
            policyLink.setTextSize(12f);
            try { policyLink.setTextColor(com.google.android.material.color.MaterialColors.getColor(policyLink, androidx.appcompat.R.attr.colorPrimary)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = (int) (6 * ctx.getResources().getDisplayMetrics().density);
            policyLink.setOnClickListener(v -> showOutputCapAutoLearningStrategyDialog(provider, modelName, onChanged));
            protectCard.addView(policyLink, plp);

            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pLp.topMargin = (int) (10 * ctx.getResources().getDisplayMetrics().density);
            root.addView(protectCard, pLp);
        } catch (Throwable ignored) {}

        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        root.addView(list, listLp);

        java.util.function.Consumer<TextView> styleStatus = tv -> {
            tv.setTextSize(12f);
            try { tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(tv, androidx.appcompat.R.attr.colorPrimary)); } catch (Throwable ignored) {}
        };

        java.util.function.BiConsumer<String, View> addDivider = (s, v) -> {};

        java.util.function.BiConsumer<CapabilityProbeType, TextView> createRow = (probeType, statusTv) -> {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            int p = (int) (12 * ctx.getResources().getDisplayMetrics().density);
            card.setPadding(p, p, p, p);
            try { card.setBackgroundResource(R.drawable.bg_dialog_rounded_stroke); } catch (Throwable ignored) {}

            LinearLayout row1 = new LinearLayout(ctx);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView title = new TextView(ctx);
            title.setTextSize(16f);
            title.setText(probeType == CapabilityProbeType.NORMAL_TEMPERATURE ? "普通模型思考" : "推理模型思考");
            try { title.setTextColor(com.google.android.material.color.MaterialColors.getColor(title, com.google.android.material.R.attr.colorOnSurface)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row1.addView(title, tLp);

            android.widget.Button btn = new android.widget.Button(ctx);
            btn.setAllCaps(false);
            btn.setText("测试");
            if (!chatProbeTestable) {
                btn.setEnabled(false);
                try { btn.setAlpha(0.55f); } catch (Throwable ignored) {}
            }
            btn.setOnClickListener(v -> {
                if (!chatProbeTestable) {
                    android.widget.Toast.makeText(ctx, "非聊天模型不可进行 chat 能力测试", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (probeRunning.get()) {
                    android.widget.Toast.makeText(ctx, "已有测试进行中，请稍候", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                probeRunning.set(true);
                btn.setEnabled(false);
                btn.setText("测试中...");
                Logger.log("CAP_PROBE", "manual test start provider=" + provider + ", subModel=" + modelName + ", type=" + probeType + ", user_notice=real_request_may_cost");
                runCapabilityProbeAsync(provider, modelName, probeType, new CapabilityProbeCallback() {
                    @Override public void onDone(boolean supported, @NonNull String detail) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            probeRunning.set(false);
                            btn.setEnabled(true);
                            btn.setText("测试");
                            refreshCapabilityTestStatusTexts(provider, modelName, normalStatusTv, reasoningStatusTv);
                            refreshOutputCapProbeStatusText(provider, modelName, outputCapStatusTv, outputCapMetaTv, outputCapErrTv, outputCapErrRawTv, outputCapErrExpanded);
                            try {
                                if (onChanged != null) onChanged.run();
                            } catch (Throwable ignored) {}
                            String shortMsg = (probeType == CapabilityProbeType.NORMAL_TEMPERATURE ? "普通模型思考" : "推理模型思考")
                                    + (supported ? "：支持" : "：不支持") + "（已写入缓存）";
                            android.widget.Toast.makeText(ctx, shortMsg, android.widget.Toast.LENGTH_SHORT).show();
                            if (!supported) {
                                try { sendConfigBroadcast(); } catch (Throwable ignored) {}
                            }
                        });
                    }
                    @Override public void onError(@NonNull String detail) {
                        Logger.log("CAP_PROBE", "manual test error provider=" + provider + ", subModel=" + modelName + ", type=" + probeType + ", detail=" + detail);
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            probeRunning.set(false);
                            btn.setEnabled(true);
                            btn.setText("测试");
                            android.widget.Toast.makeText(ctx, "测试失败：" + detail, android.widget.Toast.LENGTH_LONG).show();
                            refreshCapabilityTestStatusTexts(provider, modelName, normalStatusTv, reasoningStatusTv);
                            refreshOutputCapProbeStatusText(provider, modelName, outputCapStatusTv, outputCapMetaTv, outputCapErrTv, outputCapErrRawTv, outputCapErrExpanded);
                        });
                    }
                });
            });
            row1.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(row1);

            TextView keywords = new TextView(ctx);
            keywords.setTextSize(13f);
            keywords.setText(probeType == CapabilityProbeType.NORMAL_TEMPERATURE
                    ? "测试关键词：temperature / top_p（发送短探针，检测采样参数能力）"
                    : "测试关键词：reasoning_effort=low（原生推理深度）；不支持时仅标记为不支持，可回退提示词方案");
            try { keywords.setTextColor(com.google.android.material.color.MaterialColors.getColor(keywords, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kLp.topMargin = (int) (6 * ctx.getResources().getDisplayMetrics().density);
            card.addView(keywords, kLp);

            TextView probeParamsTv = new TextView(ctx);
            probeParamsTv.setTextSize(12f);
            probeParamsTv.setText(buildCapabilityProbeParamSummary(probeType));
            try { probeParamsTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(probeParamsTv, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ppLp.topMargin = (int) (4 * ctx.getResources().getDisplayMetrics().density);
            card.addView(probeParamsTv, ppLp);

            TextView probeBudgetTv = new TextView(ctx);
            probeBudgetTv.setTextSize(11f);
            probeBudgetTv.setText(buildCapabilityProbeBudgetNote(probeType));
            try { probeBudgetTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(probeBudgetTv, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pbLp.topMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
            card.addView(probeBudgetTv, pbLp);

            TextView purposeTv = new TextView(ctx);
            purposeTv.setTextSize(11f);
            purposeTv.setText(buildCapabilityProbePurposeNote(probeType));
            try { purposeTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(purposeTv, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams purposeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            purposeLp.topMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
            card.addView(purposeTv, purposeLp);

            TextView costTv = new TextView(ctx);
            costTv.setTextSize(11f);
            costTv.setText(buildCapabilityProbeCostNote(probeType, chatProbeTestable));
            try { costTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(costTv, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams costLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            costLp.topMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
            card.addView(costTv, costLp);

            String staticPredict = buildCapabilityProbeStaticPrediction(probeType, modelName);
            if (!TextUtils.isEmpty(staticPredict)) {
                TextView predictTv = new TextView(ctx);
                predictTv.setTextSize(11f);
                predictTv.setText(staticPredict);
                try { predictTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(predictTv, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
                LinearLayout.LayoutParams predLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                predLp.topMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
                card.addView(predictTv, predLp);
            }

            styleStatus.accept(statusTv);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = (int) (6 * ctx.getResources().getDisplayMetrics().density);
            card.addView(statusTv, sLp);

            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.bottomMargin = (int) (10 * ctx.getResources().getDisplayMetrics().density);
            list.addView(card, cLp);
        };

        createRow.accept(CapabilityProbeType.NORMAL_TEMPERATURE, normalStatusTv);
        createRow.accept(CapabilityProbeType.REASONING_NATIVE, reasoningStatusTv);

        // V2: 手动学习“输出 TOKEN 上限”
        {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            int p2 = (int) (12 * ctx.getResources().getDisplayMetrics().density);
            card.setPadding(p2, p2, p2, p2);
            try { card.setBackgroundResource(R.drawable.bg_dialog_rounded_stroke); } catch (Throwable ignored) {}

            LinearLayout row1 = new LinearLayout(ctx);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView title = new TextView(ctx);
            title.setTextSize(16f);
            title.setText("输出 TOKEN 上限");
            try { title.setTextColor(com.google.android.material.color.MaterialColors.getColor(title, com.google.android.material.R.attr.colorOnSurface)); } catch (Throwable ignored) {}
            row1.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // One-tap "Why" panel
            try {
                final float d = ctx.getResources().getDisplayMetrics().density;
                final ImageView ivWhy = new ImageView(ctx);
                int sz = (int) (34 * d);
                int pad2 = (int) (7 * d);
                ivWhy.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
                ivWhy.setImageResource(R.drawable.ic_info_circle_filled);
                try {
                    int tint = com.google.android.material.color.MaterialColors.getColor(ivWhy, com.google.android.material.R.attr.colorOnSurfaceVariant);
                    ivWhy.setColorFilter(tint);
                } catch (Throwable ignored2) {}
                ivWhy.setPadding(pad2, pad2, pad2, pad2);
                ivWhy.setClickable(true);
                ivWhy.setFocusable(true);
                try {
                    android.util.TypedValue tv = new android.util.TypedValue();
                    if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
                        ivWhy.setBackgroundResource(tv.resourceId);
                    }
                } catch (Throwable ignored2) {}
                ivWhy.setOnClickListener(v -> {
                    try {
                        MaxTokensWhySheet.show(ctx, provider, modelName, "capability_test_output_cap");
                    } catch (Throwable ignored2) {}
                });
                LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(sz, sz);
                wlp.rightMargin = (int) (6 * d);
                row1.addView(ivWhy, wlp);
            } catch (Throwable ignored) {}

            final android.widget.Button btnOutCap = new android.widget.Button(ctx);
            btnOutCap.setAllCaps(false);
            btnOutCap.setText("测试");
            if (!chatProbeTestable) {
                btnOutCap.setEnabled(false);
                try { btnOutCap.setAlpha(0.55f); } catch (Throwable ignored) {}
            }
            row1.addView(btnOutCap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(row1);

            TextView desc1 = new TextView(ctx);
            desc1.setTextSize(13f);
            desc1.setText("作用：手动学习当前子模型可接受的最大输出 TOKEN 上限；学习后会写入缓存并自动同步输出长度档位");
            try { desc1.setTextColor(com.google.android.material.color.MaterialColors.getColor(desc1, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams d1Lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            d1Lp.topMargin = (int) (6 * ctx.getResources().getDisplayMetrics().density);
            card.addView(desc1, d1Lp);

            TextView desc2 = new TextView(ctx);
            desc2.setTextSize(12f);
            desc2.setText("策略：保守模式优先学习稳定可用档；精准模式会在触发上限后做少量二分探测（请求次数可能更多）");
            try { desc2.setTextColor(com.google.android.material.color.MaterialColors.getColor(desc2, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams d2Lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            d2Lp.topMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
            card.addView(desc2, d2Lp);

            final android.widget.RadioGroup rgMode = new android.widget.RadioGroup(ctx);
            rgMode.setOrientation(android.widget.RadioGroup.HORIZONTAL);
            final android.widget.RadioButton rbConservative = new android.widget.RadioButton(ctx);
            rbConservative.setText("保守探测");
            rbConservative.setId(View.generateViewId());
            final android.widget.RadioButton rbPrecise = new android.widget.RadioButton(ctx);
            rbPrecise.setText("精准探测");
            rbPrecise.setId(View.generateViewId());
            rgMode.addView(rbConservative);
            rgMode.addView(rbPrecise);
            rbConservative.setChecked(true);
            LinearLayout.LayoutParams rgLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rgLp.topMargin = (int) (6 * ctx.getResources().getDisplayMetrics().density);
            card.addView(rgMode, rgLp);

            final EditText etCustomProbe = new EditText(ctx);
            etCustomProbe.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etCustomProbe.setHint("自定义探针 max_tokens（可选，例如 3200）");
            try { etCustomProbe.setSingleLine(true); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            etLp.topMargin = (int) (4 * ctx.getResources().getDisplayMetrics().density);
            card.addView(etCustomProbe, etLp);

            TextView desc3 = new TextView(ctx);
            desc3.setTextSize(11f);
            desc3.setText("说明：若填自定义探针，会优先测试该值；若仍无法触发上限错误，则会把成功值作为可用下限缓存（用于自动切档/禁用高档位）");
            try { desc3.setTextColor(com.google.android.material.color.MaterialColors.getColor(desc3, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams d3Lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            d3Lp.topMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
            card.addView(desc3, d3Lp);

            styleStatus.accept(outputCapStatusTv);
            LinearLayout.LayoutParams s1Lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            s1Lp.topMargin = (int) (6 * ctx.getResources().getDisplayMetrics().density);
            card.addView(outputCapStatusTv, s1Lp);

            outputCapMetaTv.setTextSize(11f);
            try { outputCapMetaTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(outputCapMetaTv, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            smLp.topMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
            card.addView(outputCapMetaTv, smLp);

            outputCapErrTv.setTextSize(11f);
            try { outputCapErrTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(outputCapErrTv, com.google.android.material.R.attr.colorTertiary)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams seLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            seLp.topMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
            card.addView(outputCapErrTv, seLp);

            outputCapErrRawTv.setTextSize(10.5f);
            outputCapErrRawTv.setVisibility(View.GONE);
            try { outputCapErrRawTv.setTextIsSelectable(true); } catch (Throwable ignored) {}
            try { outputCapErrRawTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(outputCapErrRawTv, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams seRawLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            seRawLp.topMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
            card.addView(outputCapErrRawTv, seRawLp);

            btnOutCap.setOnClickListener(v -> {
                if (!chatProbeTestable) {
                    android.widget.Toast.makeText(ctx, "非聊天模型不可进行 chat 输出上限测试", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (probeRunning.get()) {
                    android.widget.Toast.makeText(ctx, "已有测试进行中，请稍候", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                int customProbe = 0;
                try {
                    CharSequence cs = etCustomProbe.getText();
                    if (cs != null) {
                        String raw = cs.toString().trim();
                        if (!raw.isEmpty()) customProbe = Integer.parseInt(raw);
                    }
                } catch (Throwable ignored) {}
                final OutputCapProbeMode mode = rbPrecise.isChecked() ? OutputCapProbeMode.PRECISE : OutputCapProbeMode.CONSERVATIVE;
                probeRunning.set(true);
                btnOutCap.setEnabled(false);
                btnOutCap.setText("测试中...");
                refreshOutputCapProbeStatusText(provider, modelName, outputCapStatusTv, outputCapMetaTv, outputCapErrTv, outputCapErrRawTv, outputCapErrExpanded);
                try {
                    outputCapStatusTv.setText("状态：测试中（正在发送短探针请求…）");
                } catch (Throwable ignored) {}
                runOutputCapProbeAsync(provider, modelName, mode, customProbe, new OutputCapProbeCallback() {
                    @Override public void onDone(@NonNull OutputCapProbeResult result) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            probeRunning.set(false);
                            btnOutCap.setEnabled(true);
                            btnOutCap.setText("测试");
                            refreshOutputCapProbeStatusText(provider, modelName, outputCapStatusTv, outputCapMetaTv, outputCapErrTv, outputCapErrRawTv, outputCapErrExpanded);
                            refreshCapabilityTestStatusTexts(provider, modelName, normalStatusTv, reasoningStatusTv);
                            try { refreshModelCapabilityRowsIfSelected(provider, modelName); } catch (Throwable ignored) {}
                            try { refreshOutputLengthAutoGuardForCurrentModel(false); } catch (Throwable ignored) {}
                            try { sendConfigBroadcast(); } catch (Throwable ignored) {}
                            try { if (onChanged != null) onChanged.run(); } catch (Throwable ignored) {}
                            if (result.learned) {
                                String msg = (result.exactCap ? ("已学习输出上限≈" + result.cap + " Token") : ("已学习可用下限≥" + result.cap + " Token（非精确）"));
                                msg += "（" + (result.precise ? "精准探测" : "保守探测") + "）";
                                if (result.syncedSelection > 0) msg += "，已同步档位=" + result.syncedSelection + (result.syncedSelectionIsCustom ? "（自定义）" : "");
                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show();
                            } else {
                                android.widget.Toast.makeText(ctx, "测试完成，但未写入上限缓存", android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    @Override public void onError(@NonNull String detail) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            probeRunning.set(false);
                            btnOutCap.setEnabled(true);
                            btnOutCap.setText("测试");
                            refreshOutputCapProbeStatusText(provider, modelName, outputCapStatusTv, outputCapMetaTv, outputCapErrTv, outputCapErrRawTv, outputCapErrExpanded);
                            android.widget.Toast.makeText(ctx, "输出上限测试失败：" + detail, android.widget.Toast.LENGTH_LONG).show();
                            try { if (onChanged != null) onChanged.run(); } catch (Throwable ignored) {}
                        });
                    }
                });
            });

            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.bottomMargin = (int) (10 * ctx.getResources().getDisplayMetrics().density);
            list.addView(card, cLp);
        }

        refreshCapabilityTestStatusTexts(provider, modelName, normalStatusTv, reasoningStatusTv);
        refreshOutputCapProbeStatusText(provider, modelName, outputCapStatusTv, outputCapMetaTv, outputCapErrTv, outputCapErrRawTv, outputCapErrExpanded);

        final androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(ctx)
                .setTitle("模型能力测试")
                .setView(contentScroll)
                .setNeutralButton("清除缓存", null)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_ok, null)
                .create();
        dlg.setOnShowListener(di -> {
            try {
                final android.view.Window w = dlg.getWindow();
                if (w != null) {
                    android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    final int maxDialogContentH = (int) (dm.heightPixels * 0.72f);
                    final int extraGap = (int) (12 * dm.density);
                    android.view.ViewGroup.LayoutParams lp = contentScroll.getLayoutParams();
                    if (lp != null) {
                        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                        contentScroll.setLayoutParams(lp);
                    }
                    contentScroll.post(() -> {
                        try {
                            int buttonBarH = 0;
                            android.widget.Button pBtn = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
                            if (pBtn != null && pBtn.getParent() instanceof android.view.View) {
                                android.view.View buttonPanel = (android.view.View) pBtn.getParent();
                                buttonBarH = buttonPanel.getHeight();
                                if (buttonBarH <= 0) {
                                    buttonPanel.measure(
                                            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
                                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                                    buttonBarH = buttonPanel.getMeasuredHeight();
                                }
                                if (buttonPanel.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                                    android.view.ViewGroup.MarginLayoutParams blp = (android.view.ViewGroup.MarginLayoutParams) buttonPanel.getLayoutParams();
                                    buttonBarH += Math.max(0, blp.topMargin) + Math.max(0, blp.bottomMargin);
                                }
                            }

                            int targetPb = Math.max(contentScroll.getPaddingBottom(), buttonBarH + extraGap);
                            if (contentScroll.getPaddingBottom() != targetPb) {
                                contentScroll.setClipToPadding(false);
                                contentScroll.setPadding(contentScroll.getPaddingLeft(), contentScroll.getPaddingTop(), contentScroll.getPaddingRight(), targetPb);
                            }

                            int measured = root.getMeasuredHeight() + root.getPaddingTop() + root.getPaddingBottom();
                            int targetH = Math.min(maxDialogContentH, Math.max(contentScroll.getMinimumHeight(), measured));
                            if (buttonBarH > 0) {
                                targetH = Math.max((int) (260 * dm.density), targetH - Math.min(buttonBarH, maxDialogContentH / 3));
                            }
                            android.view.ViewGroup.LayoutParams slp = contentScroll.getLayoutParams();
                            if (slp != null) {
                                slp.height = targetH;
                                contentScroll.setLayoutParams(slp);
                            }
                        } catch (Throwable ignored) {}
                    });
                }
            } catch (Throwable ignored) {}
            android.widget.Button neutral = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setOnClickListener(v -> {
                    try {
                        SPManager.getInstance().clearCachedModelCapabilityHints(provider, modelName);
                        Logger.log("CAP_PROBE", "cache cleared provider=" + provider + ", subModel=" + modelName + ", by=dialog");
                        refreshCapabilityTestStatusTexts(provider, modelName, normalStatusTv, reasoningStatusTv);
                        refreshOutputCapProbeStatusText(provider, modelName, outputCapStatusTv, outputCapMetaTv, outputCapErrTv, outputCapErrRawTv, outputCapErrExpanded);
                        refreshModelCapabilityRowsIfSelected(provider, modelName);
                        try { if (onChanged != null) onChanged.run(); } catch (Throwable ignored) {}
                        try { sendConfigBroadcast(); } catch (Throwable ignored) {}
                        android.widget.Toast.makeText(ctx, "已清除能力缓存：" + modelName, android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Logger.log(t);
                        android.widget.Toast.makeText(ctx, "清除缓存失败", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        dlg.show();
    }

    private void refreshCapabilityTestStatusTexts(@NonNull LanguageModel provider, @NonNull String subModel, @NonNull TextView normalTv, @NonNull TextView reasoningTv) {
        try {
            if (!isChatCapabilityTestable(provider, subModel)) {
                String txt = "状态：非聊天模型（不可测试）";
                normalTv.setText(txt);
                reasoningTv.setText(txt);
                try {
                    int c = com.google.android.material.color.MaterialColors.getColor(normalTv, com.google.android.material.R.attr.colorOnSurfaceVariant);
                    normalTv.setTextColor(c);
                    reasoningTv.setTextColor(c);
                } catch (Throwable ignored) {}
                return;
            }
            Boolean t = SPManager.getInstance().getCachedSupportsTemperature(provider, subModel);
            Boolean r = SPManager.getInstance().getCachedSupportsReasoningThinking(provider, subModel);
            setCapabilityStatusText(normalTv, "普通模型思考", t);
            setCapabilityStatusText(reasoningTv, "推理模型思考", r);
        } catch (Throwable e) {
            normalTv.setText("状态：读取失败");
            reasoningTv.setText("状态：读取失败");
        }
    }

    private void setCapabilityStatusText(@NonNull TextView tv, @NonNull String title, @Nullable Boolean state) {
        String txt;
        int attr;
        if (state == null) {
            txt = "状态：未测（Unknown）";
            attr = com.google.android.material.R.attr.colorTertiary;
        } else if (state) {
            txt = "状态：已测支持（Supported）";
            attr = androidx.appcompat.R.attr.colorPrimary;
        } else {
            txt = "状态：已测不支持（Unsupported）";
            attr = com.google.android.material.R.attr.colorTertiary;
        }
        tv.setText(txt);
        try { tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(tv, attr)); } catch (Throwable ignored) {}
    }

    private interface CapabilityProbeCallback {
        void onDone(boolean supported, @NonNull String detail);
        void onError(@NonNull String detail);
    }

    private void runCapabilityProbeAsync(@NonNull LanguageModel provider, @NonNull String subModel, @NonNull CapabilityProbeType type, @NonNull CapabilityProbeCallback cb) {
        new Thread(() -> {
            try {
                logCapabilityProbe("thread_start", provider, subModel, type, null);
                String detail = performCapabilityProbe(provider, subModel, type);
                boolean supported;
                if (type == CapabilityProbeType.NORMAL_TEMPERATURE) {
                    supported = Boolean.TRUE.equals(SPManager.getInstance().getCachedSupportsTemperature(provider, subModel));
                } else {
                    supported = Boolean.TRUE.equals(SPManager.getInstance().getCachedSupportsReasoningThinking(provider, subModel));
                }
                logCapabilityProbe("thread_done", provider, subModel, type, "supported=" + supported + ", detail=" + detail);
                cb.onDone(supported, detail);
            } catch (Throwable t) {
                Logger.log(t);
                String msg = t.getMessage();
                logCapabilityProbe("thread_error", provider, subModel, type, "error=" + (TextUtils.isEmpty(msg) ? t.getClass().getSimpleName() : msg));
                cb.onError(TextUtils.isEmpty(msg) ? t.getClass().getSimpleName() : msg);
            }
        }, "kgpt-cap-probe").start();
    }

    @NonNull
    private String performCapabilityProbe(@NonNull LanguageModel provider, @NonNull String subModel, @NonNull CapabilityProbeType type) throws Exception {
        if (!SPManager.isReady()) throw new IllegalStateException("SPManager not ready");
        if (!ModelCapabilities.isChatCapabilityTestable(provider, subModel)) {
            logCapabilityProbe("skip_non_chat", provider, subModel, type, "reason=non-chat-model");
            return "non-chat model skipped";
        }
        final SPManager sp = SPManager.getInstance();
        final LanguageModelClient client = LanguageModelClient.forModel(provider);
        for (LanguageModelField f : LanguageModelField.values()) {
            try {
                String v = sp.getLanguageModelField(provider, f);
                if (v != null) client.setField(f, v);
            } catch (Throwable ignored) {}
        }
        client.setField(LanguageModelField.SubModel, subModel);
        int probeMaxTokens = (type == CapabilityProbeType.REASONING_NATIVE) ? CAP_PROBE_MAX_TOKENS_REASONING : CAP_PROBE_MAX_TOKENS_NORMAL;
        client.setField(LanguageModelField.MaxTokens, String.valueOf(probeMaxTokens));
        // 保守探针：尽量降低流式/长度影响；测试专用参数不读取用户当前输出长度配置
        SPManager.setThreadStreamingModeOverride(SPManager.STREAM_MODE_TYPEWRITER);
        String probePrompt = "Reply ONLY with OK";
        String system = "Capability probe. Reply only OK.";
        logCapabilityProbe("prepare", provider, subModel, type, "client=" + client.getClass().getSimpleName());
        if (type == CapabilityProbeType.NORMAL_TEMPERATURE) {
            client.setField(LanguageModelField.Temperature, "0.5");
            try {
                client.setField(LanguageModelField.TopP, "0.9");
            } catch (Throwable ignored) {}
            try {
                runProbeRequest(client, probePrompt, system);
                sp.setCachedSupportsTemperature(provider, subModel, true);
                refreshModelCapabilityRowsIfSelected(provider, subModel);
                try { sendConfigBroadcast(); } catch (Throwable ignored) {}
                logCapabilityProbe("success", provider, subModel, type, "cache.temperature=true");
                return "temperature probe success";
            } catch (Throwable t) {
                String msg = safeErrMsg(t);
                if (msg.startsWith("__TEMP_TOPP_MUTEX__::")) {
                    sp.setCachedSupportsTemperature(provider, subModel, true);
                    refreshModelCapabilityRowsIfSelected(provider, subModel);
                    try { sendConfigBroadcast(); } catch (Throwable ignored) {}
                    logCapabilityProbe("success_mutex", provider, subModel, type, "cache.temperature=true, error=" + msg);
                    return "temperature supported but temp/top_p mutex";
                }
                if (ModelCapabilities.isUnsupportedParamError(t, "temperature") || ModelCapabilities.isUnsupportedParamError(t, "top_p")) {
                    sp.setCachedSupportsTemperature(provider, subModel, false);
                    refreshModelCapabilityRowsIfSelected(provider, subModel);
                    try { sendConfigBroadcast(); } catch (Throwable ignored) {}
                    logCapabilityProbe("unsupported", provider, subModel, type, "cache.temperature=false, error=" + msg);
                    return "temperature unsupported";
                }
                if (ModelCapabilities.isLikelyMaxTokensConstraintError(t)) {
                    logCapabilityProbe("inconclusive", provider, subModel, type, "token-limit-error=" + msg);
                    return "temperature probe token-limit conflict（未写缓存）";
                }
                if (ModelCapabilities.isNonChatEndpointError(t)) {
                    logCapabilityProbe("skip_non_chat_error", provider, subModel, type, "error=" + msg);
                    return "non-chat endpoint（未写缓存）";
                }
                logCapabilityProbe("error", provider, subModel, type, "error=" + msg);
                throw t;
            }
        }

        // reasoning native probe: only meaningful on ChatGPT-compatible clients
        if (!(client instanceof ChatGPTClient)) {
            sp.setCachedSupportsReasoningThinking(provider, subModel, false);
            refreshModelCapabilityRowsIfSelected(provider, subModel);
            try { sendConfigBroadcast(); } catch (Throwable ignored) {}
            logCapabilityProbe("unsupported_client", provider, subModel, type, "client=" + client.getClass().getSimpleName() + ", cache.reasoning=false");
            return "reasoning native unsupported for client type";
        }
        SPManager.setThreadReasoningEffortOverride("low");
        try {
            runProbeRequest(client, probePrompt, system);
            sp.setCachedSupportsReasoningThinking(provider, subModel, true);
            refreshModelCapabilityRowsIfSelected(provider, subModel);
            try { sendConfigBroadcast(); } catch (Throwable ignored) {}
            logCapabilityProbe("success", provider, subModel, type, "cache.reasoning=true");
            return "reasoning native probe success";
        } catch (Throwable t) {
            String msg = safeErrMsg(t);
            if (ModelCapabilities.isUnsupportedParamError(t, "reasoning")) {
                sp.setCachedSupportsReasoningThinking(provider, subModel, false);
                refreshModelCapabilityRowsIfSelected(provider, subModel);
                try { sendConfigBroadcast(); } catch (Throwable ignored) {}
                logCapabilityProbe("unsupported", provider, subModel, type, "cache.reasoning=false, error=" + msg);
                return "reasoning unsupported";
            }
            if (ModelCapabilities.isThinkingBudgetParamConflict(t) || ModelCapabilities.isTokenLimitError(t)) {
                logCapabilityProbe("inconclusive", provider, subModel, type, "param-conflict=" + msg);
                return "reasoning probe 参数冲突/Token 限制（未写缓存）";
            }
            if (ModelCapabilities.isNonChatEndpointError(t)) {
                logCapabilityProbe("skip_non_chat_error", provider, subModel, type, "error=" + msg);
                return "non-chat endpoint（未写缓存）";
            }
            logCapabilityProbe("error", provider, subModel, type, "error=" + msg);
            throw t;
        } finally {
            SPManager.clearThreadReasoningEffortOverride();
        }
    }

    private static final int[] CAP_PROBE_OUTPUT_FIXED_BUCKETS = new int[]{
            1024, 2048, 4096, 8192, 16384, 32768, 49152, 65536, 81920, 100000, 131072
    };

    private static final class OutputCapProbeResult {
        boolean learned;
        int cap;
        int syncedSelection;
        boolean syncedSelectionIsCustom;
        boolean precise; // probe mode (精准/保守)
        boolean exactCap; // whether cached value is an exact cap (vs lower-bound)
        String source;
        String detail;
    }

    private interface OutputCapProbeCallback {
        void onDone(@NonNull OutputCapProbeResult result);
        void onError(@NonNull String detail);
    }

    private void logOutputCapProbe(@NonNull String stage, @NonNull LanguageModel provider, @NonNull String subModel, @NonNull OutputCapProbeMode mode, int requestMax, @Nullable String extra) {
        try {
            String msg = "stage=" + stage
                    + ", provider=" + provider.name()
                    + ", subModel=" + subModel
                    + ", mode=" + mode.name().toLowerCase(java.util.Locale.US)
                    + ", requestMax=" + requestMax
                    + (extra == null || extra.trim().isEmpty() ? "" : (", " + extra));
            Logger.log("OUTCAP_PROBE", msg);
        } catch (Throwable ignored) {}
    }

    private int normalizeOutputCapProbeCustom(int customVal) {
        int v = customVal;
        if (v < 0) v = 0;
        if (v > 1000000) v = 1000000;
        return v;
    }

    private int defaultStartForOutputCapProbe(@NonNull OutputCapProbeMode mode) {
        return mode == OutputCapProbeMode.CONSERVATIVE ? CAP_PROBE_OUTPUT_START_CONSERVATIVE : CAP_PROBE_OUTPUT_START_DEFAULT;
    }

    @NonNull
    private String formatOutputCapSourceLabel(@Nullable String source) {
        if (TextUtils.isEmpty(source)) return "未知来源";
        String s = source.toLowerCase(java.util.Locale.US);
        boolean lower = s.contains("lower");
        if (s.contains("manual_precise")) return lower ? "手动精确（下限）" : "手动精确";
        if (s.contains("manual_conservative")) return lower ? "手动保守（下限）" : "手动保守";
        if (s.contains("manual_custom")) return lower ? "手动自定义（下限）" : "手动自定义";
        if (s.contains("auto_success")) return "自动成功学习（下限）";
        if (s.contains("auto_retry")) return lower ? "自动回退学习（下限）" : "自动回退学习";
        if (s.contains("manual_probe_unsupported")) return "手动测试：参数不支持";
        if (s.contains("manual_probe_error")) return "手动测试：错误";
        if (s.contains("manual_probe_inconclusive")) return "手动测试：未得结论";
        return source;
    }

    @NonNull
    private String getOutputCapSourceLabel(@Nullable String source) {
        return formatOutputCapSourceLabel(source);
    }

    @Nullable
    private String formatRelativeAgeShort(@Nullable Long updatedAtMs) {
        if (updatedAtMs == null || updatedAtMs <= 0L) return null;
        try {
            long diff = System.currentTimeMillis() - updatedAtMs;
            if (diff < 0L) diff = 0L;
            long sec = diff / 1000L;
            if (sec < 60L) return "刚刚";
            long min = sec / 60L;
            if (min < 60L) return min + "分钟前";
            long hr = min / 60L;
            if (hr < 24L) return hr + "小时前";
            long day = hr / 24L;
            if (day < 30L) return day + "天前";
            return formatLearnedTimeShort(updatedAtMs);
        } catch (Throwable ignored) {
            try { return formatLearnedTimeShort(updatedAtMs); } catch (Throwable ignored2) { return null; }
        }
    }


    @NonNull
    private String formatLearnedTimeShort(long ms) {
        if (ms <= 0L) return "";
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault());
            return f.format(new java.util.Date(ms));
        } catch (Throwable ignored) {
            return String.valueOf(ms);
        }
    }

    @NonNull
    private String summarizeThrowableForUi(@Nullable Throwable t) {
        String m = safeErrMsg(t);
        if (TextUtils.isEmpty(m)) return "unknown";
        m = m.replace('\n', ' ').replace('\r', ' ').trim();
        if (m.length() > 180) m = m.substring(0, 180) + "…";
        return m;
    }

    private void refreshOutputCapProbeStatusText(@NonNull LanguageModel provider, @NonNull String subModel,
                                                 @NonNull TextView statusTv,
                                                 @Nullable TextView metaTv,
                                                 @Nullable TextView errTv) {
        refreshOutputCapProbeStatusText(provider, subModel, statusTv, metaTv, errTv, null, null);
    }

    private void refreshOutputCapProbeStatusText(@NonNull LanguageModel provider, @NonNull String subModel,
                                                 @NonNull TextView statusTv,
                                                 @Nullable TextView metaTv,
                                                 @Nullable TextView errTv,
                                                 @Nullable TextView errRawTv,
                                                 @Nullable java.util.concurrent.atomic.AtomicBoolean errExpanded) {
        try {
            if (!isChatCapabilityTestable(provider, subModel)) {
                statusTv.setText("状态：非聊天模型（不可测试）");
                try {
                    int c = com.google.android.material.color.MaterialColors.getColor(statusTv, com.google.android.material.R.attr.colorOnSurfaceVariant);
                    statusTv.setTextColor(c);
                    if (metaTv != null) metaTv.setTextColor(c);
                    if (errTv != null) errTv.setTextColor(c);
                } catch (Throwable ignored) {}
                if (metaTv != null) {
                    metaTv.setVisibility(View.VISIBLE);
                    metaTv.setText("说明：输出 TOKEN 上限测试仅对聊天模型有效");
                }
                if (errTv != null) errTv.setVisibility(View.GONE);
                if (errRawTv != null) errRawTv.setVisibility(View.GONE);
                return;
            }
            SPManager sp = SPManager.getInstance();
            Integer cap = sp.getCachedSafeMaxTokens(provider, subModel);
            Integer lowerBound = null;
            try { lowerBound = sp.getCachedSafeMaxTokensLowerBound(provider, subModel); } catch (Throwable ignored) {}
            String source = sp.getCachedSafeMaxTokensSource(provider, subModel);
            long at = sp.getCachedSafeMaxTokensUpdatedAt(provider, subModel);
            String lastErr = sp.getCachedSafeMaxTokensLastError(provider, subModel);
            String lastErrRaw = null;
            try { lastErrRaw = sp.getCachedSafeMaxTokensLastErrorRaw(provider, subModel); } catch (Throwable ignored) {}

            Integer effectiveCap = (cap != null && cap > 0) ? cap : ((lowerBound != null && lowerBound > 0) ? lowerBound : null);
            boolean exactCapKnown = (cap != null && cap > 0);
            if (effectiveCap != null && effectiveCap > 0) {
                int floor = OutputLengthOptions.findFloorFixedTokens(effectiveCap);
                StringBuilder sb = new StringBuilder();
                if (exactCapKnown) {
                    sb.append("状态：已学习上限≈").append(cap).append(" Token");
                } else {
                    sb.append("状态：已学习可用下限≥").append(effectiveCap).append(" Token（非精确）");
                }
                if (floor > 0) sb.append("（固定档=").append(floor).append("）");
                statusTv.setText(sb.toString());
                try { statusTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(statusTv, androidx.appcompat.R.attr.colorPrimary)); } catch (Throwable ignored) {}
                if (metaTv != null) {
                    StringBuilder m = new StringBuilder();
                    m.append("来源：").append(formatOutputCapSourceLabel(source));
                    if (at > 0L) m.append(" · 时间：").append(formatLearnedTimeShort(at));
                    if (!exactCapKnown) m.append("\n提示：当前仅学习到“至少可用”值；继续手动测试可得到更精确上限");
                    metaTv.setText(m.toString());
                    metaTv.setVisibility(View.VISIBLE);
                }
            } else {
                statusTv.setText("状态：未学习（Unknown）");
                try { statusTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(statusTv, com.google.android.material.R.attr.colorTertiary)); } catch (Throwable ignored) {}
                if (metaTv != null) {
                    StringBuilder m = new StringBuilder();
                    m.append("说明：可用右侧【测试】手动学习输出上限；学习后会自动切换输出长度档位并刷新主界面提示");
                    if (!TextUtils.isEmpty(source) || at > 0L) {
                        m.append("\n最近来源：").append(formatOutputCapSourceLabel(source));
                        if (at > 0L) m.append(" · ").append(formatLearnedTimeShort(at));
                    }
                    metaTv.setText(m.toString());
                    metaTv.setVisibility(View.VISIBLE);
                }
            }

            if (errTv != null) {
                if (!TextUtils.isEmpty(lastErr)) {
                    boolean canExpand = !TextUtils.isEmpty(lastErrRaw);
                    boolean expanded = errExpanded != null && errExpanded.get();
                    errTv.setText("最近错误摘要：" + lastErr + (canExpand ? (expanded ? " （点按收起详情）" : " （点按展开详情）") : ""));
                    errTv.setVisibility(View.VISIBLE);
                    errTv.setClickable(canExpand);
                    if (canExpand && errRawTv != null && errExpanded != null) {
                        errTv.setOnClickListener(v -> {
                            try { errExpanded.set(!errExpanded.get()); } catch (Throwable ignored) {}
                            refreshOutputCapProbeStatusText(provider, subModel, statusTv, metaTv, errTv, errRawTv, errExpanded);
                        });
                    } else {
                        errTv.setOnClickListener(null);
                    }
                    try { errTv.setTextColor(com.google.android.material.color.MaterialColors.getColor(errTv, com.google.android.material.R.attr.colorTertiary)); } catch (Throwable ignored) {}
                } else {
                    errTv.setVisibility(View.GONE);
                    errTv.setText("");
                    errTv.setOnClickListener(null);
                }
            }
            if (errRawTv != null) {
                boolean showRaw = !TextUtils.isEmpty(lastErrRaw) && errExpanded != null && errExpanded.get();
                if (showRaw) {
                    errRawTv.setText("最近一次原始错误详情：\n" + lastErrRaw);
                    errRawTv.setVisibility(View.VISIBLE);
                } else {
                    errRawTv.setVisibility(View.GONE);
                    errRawTv.setText("");
                }
            }
        } catch (Throwable e) {
            statusTv.setText("状态：读取失败");
            if (metaTv != null) { metaTv.setVisibility(View.GONE); metaTv.setText(""); }
            if (errTv != null) { errTv.setVisibility(View.GONE); errTv.setText(""); }
            if (errRawTv != null) { errRawTv.setVisibility(View.GONE); errRawTv.setText(""); }
        }
    }

    private void runOutputCapProbeAsync(@NonNull LanguageModel provider,
                                        @NonNull String subModel,
                                        @NonNull OutputCapProbeMode mode,
                                        int customProbe,
                                        @NonNull OutputCapProbeCallback cb) {
        final int customVal = normalizeOutputCapProbeCustom(customProbe);
        new Thread(() -> {
            try {
                OutputCapProbeResult r = performOutputCapProbe(provider, subModel, mode, customVal);
                cb.onDone(r);
            } catch (Throwable t) {
                Logger.log(t);
                String summary = summarizeThrowableForUi(t);
                try {
                    if (SPManager.isReady()) {
                        SPManager.getInstance().setCachedSafeMaxTokensLastError(provider, subModel, summary);
                        try { SPManager.getInstance().setCachedSafeMaxTokensLastErrorRaw(provider, subModel, safeErrMsg(t)); } catch (Throwable ignored2) {}
                        SPManager.getInstance().setCachedSafeMaxTokensSource(provider, subModel, "manual_probe_error");
                        SPManager.getInstance().setCachedSafeMaxTokensUpdatedAt(provider, subModel, System.currentTimeMillis());
                    }
                } catch (Throwable ignored) {}
                cb.onError(summary);
            }
        }, "kgpt-outcap-probe").start();
    }

    @NonNull
    private OutputCapProbeResult performOutputCapProbe(@NonNull LanguageModel provider,
                                                       @NonNull String subModel,
                                                       @NonNull OutputCapProbeMode mode,
                                                       int customProbe) throws Exception {
        if (!SPManager.isReady()) throw new IllegalStateException("SPManager not ready");
        if (!ModelCapabilities.isChatCapabilityTestable(provider, subModel)) {
            throw new IllegalStateException("非聊天模型不可测试输出上限");
        }
        final SPManager sp = SPManager.getInstance();
        int bestSuccess = -1;
        int firstFailure = -1;
        Throwable lastTokenErr = null;

        java.util.LinkedHashSet<Integer> plan = new java.util.LinkedHashSet<>();
        if (customProbe > 0) plan.add(customProbe);
        int start = defaultStartForOutputCapProbe(mode);
        if (start > 0) plan.add(start);
        for (int b : CAP_PROBE_OUTPUT_FIXED_BUCKETS) plan.add(b);

        // Ascending sweep is easier to get a stable bracket for conservative/precise modes.
        java.util.ArrayList<Integer> ordered = new java.util.ArrayList<>(plan);
        java.util.Collections.sort(ordered);

        // If no custom and mode=PRECISE, bias by probing top first to quickly trigger token-limit error.
        if (customProbe <= 0 && mode == OutputCapProbeMode.PRECISE) {
            java.util.Collections.sort(ordered, java.util.Collections.reverseOrder());
        }

        java.util.HashSet<Integer> tried = new java.util.HashSet<>();
        for (Integer reqObj : ordered) {
            if (reqObj == null) continue;
            int req = reqObj;
            if (req <= 0 || !tried.add(req)) continue;
            try {
                runSingleOutputCapProbeRequest(provider, subModel, req, mode);
                bestSuccess = Math.max(bestSuccess, req);
                logOutputCapProbe("success", provider, subModel, mode, req, "bestSuccess=" + bestSuccess);
                if (mode == OutputCapProbeMode.CONSERVATIVE && firstFailure > 0 && bestSuccess > 0) break;
                if (mode == OutputCapProbeMode.PRECISE && firstFailure > bestSuccess && bestSuccess > 0) break;
            } catch (Throwable t) {
                String err = summarizeThrowableForUi(t);
                if (ModelCapabilities.isLikelyMaxTokensConstraintError(t)) {
                    lastTokenErr = t;
                    if (firstFailure <= 0 || req < firstFailure) firstFailure = req;
                    Integer suggested = ModelCapabilities.extractSuggestedMaxTokens(t);
                    logOutputCapProbe("token_limit", provider, subModel, mode, req, "suggested=" + String.valueOf(suggested) + ", err=" + err);
                    if (suggested != null && suggested > 0) {
                        int cap = suggested;
                        if (req > 0) cap = Math.min(cap, req);
                        return persistOutputCapProbeLearn(provider, subModel, mode, cap, "suggested_error");
                    }
                    if (mode == OutputCapProbeMode.CONSERVATIVE && bestSuccess > 0) {
                        break;
                    }
                    // continue scanning for bracket / additional evidence
                    continue;
                }
                if (ModelCapabilities.isUnsupportedParamError(t, "max_tokens")
                        || ModelCapabilities.isUnsupportedParamError(t, "max_completion_tokens")
                        || ModelCapabilities.isUnsupportedParamError(t, "maxoutputtokens")) {
                    sp.setCachedSafeMaxTokensLastError(provider, subModel, err);
                    try { sp.setCachedSafeMaxTokensLastErrorRaw(provider, subModel, safeErrMsg(t)); } catch (Throwable ignored) {}
                    sp.setCachedSafeMaxTokensSource(provider, subModel, "manual_probe_unsupported");
                    sp.setCachedSafeMaxTokensUpdatedAt(provider, subModel, System.currentTimeMillis());
                    throw new IllegalStateException("模型不支持 max_tokens 参数：" + err, t);
                }
                sp.setCachedSafeMaxTokensLastError(provider, subModel, err);
                try { sp.setCachedSafeMaxTokensLastErrorRaw(provider, subModel, safeErrMsg(t)); } catch (Throwable ignored2) {}
                sp.setCachedSafeMaxTokensSource(provider, subModel, "manual_probe_error");
                sp.setCachedSafeMaxTokensUpdatedAt(provider, subModel, System.currentTimeMillis());
                logOutputCapProbe("error", provider, subModel, mode, req, "err=" + err);
                throw t;
            }
        }

        if (bestSuccess > 0 && mode == OutputCapProbeMode.PRECISE && firstFailure > bestSuccess + 1) {
            int low = bestSuccess;
            int high = firstFailure - 1;
            for (int i = 0; i < CAP_PROBE_OUTPUT_MAX_BINARY_STEPS && low < high; i++) {
                int mid = low + (high - low + 1) / 2;
                try {
                    runSingleOutputCapProbeRequest(provider, subModel, mid, mode);
                    low = mid;
                    logOutputCapProbe("binary_success", provider, subModel, mode, mid, "low=" + low + ", high=" + high);
                } catch (Throwable t) {
                    if (ModelCapabilities.isLikelyMaxTokensConstraintError(t)) {
                        Integer suggested = ModelCapabilities.extractSuggestedMaxTokens(t);
                        if (suggested != null && suggested > 0) {
                            int cap = Math.min(suggested, mid);
                            return persistOutputCapProbeLearn(provider, subModel, mode, cap, "binary_suggested_error");
                        }
                        high = mid - 1;
                        lastTokenErr = t;
                        logOutputCapProbe("binary_token_limit", provider, subModel, mode, mid, "low=" + low + ", high=" + high);
                    } else {
                        String err = summarizeThrowableForUi(t);
                        sp.setCachedSafeMaxTokensLastError(provider, subModel, err);
                        try { sp.setCachedSafeMaxTokensLastErrorRaw(provider, subModel, safeErrMsg(t)); } catch (Throwable ignored3) {}
                        sp.setCachedSafeMaxTokensSource(provider, subModel, "manual_probe_error");
                        sp.setCachedSafeMaxTokensUpdatedAt(provider, subModel, System.currentTimeMillis());
                        throw t;
                    }
                }
            }
            if (low > 0) return persistOutputCapProbeLearn(provider, subModel, mode, low, "binary_refine");
        }

        if (bestSuccess > 0) {
            // custom success or conservative sweep without exact error suggestion -> cache as safe floor/lower-bound.
            String reason = (mode == OutputCapProbeMode.PRECISE) ? "sweep_lower_bound" : "sweep_floor";
            return persistOutputCapProbeLearn(provider, subModel, mode, bestSuccess, reason);
        }

        String errSummary = lastTokenErr != null ? summarizeThrowableForUi(lastTokenErr) : "未获得可用上限信息";
        sp.setCachedSafeMaxTokensLastError(provider, subModel, errSummary);
        try { sp.setCachedSafeMaxTokensLastErrorRaw(provider, subModel, lastTokenErr != null ? safeErrMsg(lastTokenErr) : null); } catch (Throwable ignored) {}
        sp.setCachedSafeMaxTokensSource(provider, subModel, "manual_probe_inconclusive");
        sp.setCachedSafeMaxTokensUpdatedAt(provider, subModel, System.currentTimeMillis());
        throw new IllegalStateException("输出上限测试未获得可缓存结果：" + errSummary);
    }

    @NonNull
    private OutputCapProbeResult persistOutputCapProbeLearn(@NonNull LanguageModel provider,
                                                            @NonNull String subModel,
                                                            @NonNull OutputCapProbeMode mode,
                                                            int cap,
                                                            @NonNull String detail) {
        OutputCapProbeResult r = new OutputCapProbeResult();
        int v = cap;
        if (v <= 0) v = 1;
        String src = (mode == OutputCapProbeMode.PRECISE ? "manual_precise" : "manual_conservative");
        try {
            SPManager sp = SPManager.getInstance();
            boolean lowerOnly = detail != null && (detail.contains("lower_bound") || detail.contains("floor"));
            if (lowerOnly) sp.recordCachedSafeMaxTokensLowerBoundLearned(provider, subModel, v, src + "_lower");
            else sp.recordCachedSafeMaxTokensLearned(provider, subModel, v, src);
            int synced = 0;
            boolean custom = false;
            boolean sameCurrent = false;
            try {
                LanguageModel curProvider = sp.getLanguageModel();
                String curSub = (curProvider != null) ? sp.getSubModel(curProvider) : null;
                sameCurrent = (curProvider == provider) && TextUtils.equals(curSub, subModel);
            } catch (Throwable ignored) {}
            if (sameCurrent) {
                synced = sp.syncOutputLengthToLearnedCap(provider, subModel, v, "LabFragment.manual_output_cap_probe");
                try { custom = sp.getMaxTokensIsCustom(); } catch (Throwable ignored) {}
            }
            r.learned = true;
            r.cap = v;
            r.syncedSelection = synced;
            r.syncedSelectionIsCustom = custom;
            r.precise = (mode == OutputCapProbeMode.PRECISE);
            r.exactCap = !lowerOnly;
            r.source = lowerOnly ? (src + "_lower") : src;
            r.detail = detail;
            logOutputCapProbe("persist", provider, subModel, mode, v, "synced=" + synced + ", custom=" + custom + ", detail=" + detail);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return r;
    }

    private void runSingleOutputCapProbeRequest(@NonNull LanguageModel provider,
                                                @NonNull String subModel,
                                                int reqMax,
                                                @NonNull OutputCapProbeMode mode) throws Exception {
        final SPManager sp = SPManager.getInstance();
        final LanguageModelClient client = LanguageModelClient.forModel(provider);
        for (LanguageModelField f : LanguageModelField.values()) {
            try {
                String v = sp.getLanguageModelField(provider, f);
                if (v != null) client.setField(f, v);
            } catch (Throwable ignored) {}
        }
        client.setField(LanguageModelField.SubModel, subModel);
        client.setField(LanguageModelField.MaxTokens, String.valueOf(reqMax));
        SPManager.setThreadStreamingModeOverride(SPManager.STREAM_MODE_TYPEWRITER);
        String prompt = "Output exactly the word OK once.";
        String system = "Output token cap probe. Reply exactly OK.";
        logOutputCapProbe("request", provider, subModel, mode, reqMax, "client=" + client.getClass().getSimpleName());
        runProbeRequest(client, prompt, system);
    }

    private void runProbeRequest(@NonNull LanguageModelClient client, @NonNull String prompt, @Nullable String system) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<>(null);
        final AtomicBoolean completed = new AtomicBoolean(false);
        try {
            client.submitPrompt(prompt, system).subscribe(new Subscriber<String>() {
                @Override public void onSubscribe(Subscription s) {
                    try { if (s != null) s.request(Long.MAX_VALUE); } catch (Throwable ignored) {}
                }
                @Override public void onNext(String s) { }
                @Override public void onError(Throwable t) { err.set(t); latch.countDown(); }
                @Override public void onComplete() { completed.set(true); latch.countDown(); }
            });
            boolean ok = latch.await(30, TimeUnit.SECONDS);
            if (!ok) throw new RuntimeException("probe timeout");
            Throwable t = err.get();
            if (t != null) {
                if (isTempTopPMutexError(t)) {
                    // 对普通思考探针：互斥报错通常表示参数存在但组合冲突
                    throw new IllegalStateException("__TEMP_TOPP_MUTEX__::" + safeErrMsg(t), t);
                }
                throw (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
            }
            if (!completed.get()) throw new RuntimeException("probe incomplete");
        } finally {
            try { SPManager.clearThreadStreamingModeOverride(); } catch (Throwable ignored) {}
        }
    }

    private boolean isTempTopPMutexError(@Nullable Throwable t) {
        if (t == null) return false;
        String m = safeErrMsg(t);
        if (TextUtils.isEmpty(m)) return false;
        String s = m.toLowerCase();
        return s.contains("temperature") && s.contains("top_p")
                && (s.contains("cannot both") || s.contains("mutually exclusive") || s.contains("choose one") || s.contains("only one of"));
    }

    @NonNull
    private String safeErrMsg(@Nullable Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return m != null ? m : t.toString();
    }

    private void refreshModelCapabilityRowsIfSelected(@NonNull LanguageModel provider, @NonNull String subModel) {
        try {
            if (!SPManager.isReady()) return;
            SPManager sp = SPManager.getInstance();
            LanguageModel curProvider = sp.getLanguageModel();
            String curSub = sp.getSubModel(curProvider);
            if (TextUtils.isEmpty(curSub) && curProvider != null) curSub = curProvider.getDefault(LanguageModelField.SubModel);
            if (curProvider == provider && TextUtils.equals(curSub, subModel)) {
                requireActivity().runOnUiThread(() -> {
                    try { refreshNormalModelThinkingRowState(); } catch (Throwable ignored) {}
                    try { refreshReasoningModelThinkingRowState(); } catch (Throwable ignored) {}
                    try { refreshConversationSubModelRow(); } catch (Throwable ignored) {}
                    try { refreshOutputLengthRowUi(); } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable ignored) {}
    }


    /**
     * Keep the Xposed module in-sync (same broadcast used across settings screens).
     */
    private void sendConfigBroadcast() {
        if (!SPManager.isReady()) return;
        try {
            SPManager sp = SPManager.getInstance();
            Intent i = new Intent(UiInteractor.ACTION_DIALOG_RESULT);
            i.putExtra(UiInteractor.EXTRA_CONFIG_SELECTED_MODEL, sp.getLanguageModel().name());
            i.putExtra(UiInteractor.EXTRA_CONFIG_LANGUAGE_MODEL, sp.getConfigBundle());
            tn.eluea.kgpt.provider.BridgeAuth.send(requireContext(), i);
        } catch (Throwable ignored) {
        }
    }


    private void bindSubModelHeaderCapabilitySummary(@NonNull LanguageModel provider,
                                                     @Nullable String subModelName,
                                                     @Nullable LinearLayout tagsRow,
                                                     @Nullable TextView summaryTv) {
        try {
            if (tagsRow != null) {
                tagsRow.removeAllViews();
                tagsRow.setVisibility(View.GONE);
            }
            if (summaryTv != null) {
                summaryTv.setText("");
                summaryTv.setVisibility(View.GONE);
            }
            if (TextUtils.isEmpty(subModelName) || !SPManager.isReady()) return;
            final SPManager sp = SPManager.getInstance();
            final String name = subModelName;

            if (tagsRow != null) {
                boolean isCustom = false;
                try {
                    java.util.List<String> customs = sp.getCustomSubModels(provider);
                    isCustom = customs != null && customs.contains(name);
                } catch (Throwable ignored) {}
                ModelTagHelper.bindTags(requireContext(), name, tagsRow, isCustom);
                try {
                    Boolean t = sp.getCachedSupportsTemperature(provider, name);
                    Boolean r = sp.getCachedSupportsReasoningThinking(provider, name);
                    Integer exact = sp.getCachedSafeMaxTokens(provider, name);
                    Integer lb = null;
                    try { lb = sp.getCachedSafeMaxTokensLowerBound(provider, name); } catch (Throwable ignored) {}
                    Integer v = (exact != null && exact > 0) ? exact : ((lb != null && lb > 0) ? lb : null);
                    if (r != null) {
                        addMiniChip(tagsRow, (Boolean.TRUE.equals(r) ? "🧠 推理:支持" : "🧠 推理:不支持"),
                                Boolean.TRUE.equals(r) ? "#1A4CAF50" : "#1AE57373",
                                Boolean.TRUE.equals(r) ? "#2E7D32" : "#C62828");
                    }
                    if (v != null && v > 0) {
                        boolean lower = (exact == null || exact <= 0);
                        addMiniChip(tagsRow, (lower ? "📏 下限≥" : "📏 上限≈") + v,
                                lower ? "#1A607D8B" : "#1A3F51B5",
                                lower ? "#607D8B" : "#3F51B5");
                    } else {
                        addMiniChip(tagsRow, "📏 未学", "#1A9E9E9E", "#757575");
                    }
                    if (t != null) {
                        addMiniChip(tagsRow, (Boolean.TRUE.equals(t) ? "🌡 普通:支持" : "🌡 普通:不支持"),
                                Boolean.TRUE.equals(t) ? "#1A4CAF50" : "#1AE57373",
                                Boolean.TRUE.equals(t) ? "#2E7D32" : "#C62828");
                    }
                } catch (Throwable ignored) {}
                tagsRow.setVisibility(tagsRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
                if (tagsRow.getChildCount() > 0) {
                    try { relayoutHeaderMiniChipsCentered(tagsRow, 2);
} catch (Throwable ignored) {}
                }
            }

            if (summaryTv != null) {
                String normal = "未测";
                String reason = "未测";
                String capText = "输出上限：未学";
                String freshness = "";
                try {
                    Boolean t = sp.getCachedSupportsTemperature(provider, name);
                    Boolean r = sp.getCachedSupportsReasoningThinking(provider, name);
                    normal = t == null ? "未测" : (Boolean.TRUE.equals(t) ? "支持" : "不支持");
                    reason = r == null ? "未测" : (Boolean.TRUE.equals(r) ? "支持" : "不支持");
                    Integer exact = sp.getCachedSafeMaxTokens(provider, name);
                    Integer lb = null;
                    try { lb = sp.getCachedSafeMaxTokensLowerBound(provider, name); } catch (Throwable ignored) {}
                    Integer v = (exact != null && exact > 0) ? exact : ((lb != null && lb > 0) ? lb : null);
                    if (v != null && v > 0) {
                        capText = (exact != null && exact > 0 ? "输出上限≈" : "输出下限≥") + v;
                    }
                    long updated = 0L;
                    try { updated = sp.getCachedSafeMaxTokensUpdatedAt(provider, name); } catch (Throwable ignored) {}
                    if (updated > 0L) {
                        freshness = " · " + formatRelativeTimeShort(updated);
                    }
                } catch (Throwable ignored) {}
                summaryTv.setText("普通思考:" + normal + " · 推理思考:" + reason + " · " + capText + freshness);
                summaryTv.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    private void relayoutHeaderMiniChipsCentered(@Nullable LinearLayout container, int maxRows) {
        if (container == null) return;
        try {
            if (container.getChildCount() <= 0) return;
            int cw = container.getWidth();
            if (cw <= 0) {
                try { cw = requireContext().getResources().getDisplayMetrics().widthPixels - (int) (88 * requireContext().getResources().getDisplayMetrics().density); } catch (Throwable ignored) {}
            }
            if (cw <= 0) return;
            final java.util.ArrayList<View> chips = new java.util.ArrayList<>();
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child == null) continue;
                child.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                chips.add(child);
            }
            container.removeAllViews();
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.CENTER_HORIZONTAL);

            java.util.ArrayList<LinearLayout> rows = new java.util.ArrayList<>();
            LinearLayout row = null;
            int used = 0;
            final int rowLimit = Math.max(1, maxRows);
            final int hGap = (int) (6 * container.getResources().getDisplayMetrics().density + 0.5f);
            for (int i = 0; i < chips.size(); i++) {
                View chip = chips.get(i);
                ViewGroup.LayoutParams rawLp = chip.getLayoutParams();
                LinearLayout.LayoutParams oldLp;
                if (rawLp instanceof LinearLayout.LayoutParams) oldLp = (LinearLayout.LayoutParams) rawLp;
                else oldLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                int childW = Math.max(chip.getMeasuredWidth(), chip.getWidth());
                int left = oldLp.leftMargin;
                int right = oldLp.rightMargin;
                int req = childW + left + right;
                boolean needNewRow = false;
                if (row == null) {
                    needNewRow = true;
                } else if ((used + req) > cw && rows.size() < rowLimit - 1) {
                    needNewRow = true;
                }
                if (needNewRow) {
                    row = new LinearLayout(container.getContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (!rows.isEmpty()) rowLp.topMargin = (int) (4 * container.getResources().getDisplayMetrics().density + 0.5f);
                    container.addView(row, rowLp);
                    rows.add(row);
                    used = 0;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = left;
                lp.rightMargin = (right > 0 ? right : hGap);
                lp.topMargin = oldLp.topMargin;
                lp.bottomMargin = oldLp.bottomMargin;
                try { chip.setLayoutParams(lp); } catch (Throwable ignored) {}
                row.addView(chip);
                used += req;
            }
            container.setVisibility(container.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {}
    }

    private static String formatRelativeTimeShort(long whenMs) {
        if (whenMs <= 0L) return "";
        long d = Math.max(0L, System.currentTimeMillis() - whenMs);
        long mins = d / 60000L;
        if (mins < 1) return "刚刚";
        if (mins < 60) return mins + "分钟前";
        long hrs = mins / 60L;
        if (hrs < 24) return hrs + "小时前";
        long days = hrs / 24L;
        return days + "天前";
    }

    private static void addMiniChip(@NonNull LinearLayout container,
                                    @NonNull String text,
                                    @NonNull String bgHex,
                                    @NonNull String fgHex) {
        try {
            android.widget.TextView tv = new android.widget.TextView(container.getContext());
            tv.setText(text);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
            try { tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD); } catch (Throwable ignored) {}
            tv.setTextColor(android.graphics.Color.parseColor(fgHex));
            int ph = (int) (6 * container.getResources().getDisplayMetrics().density + 0.5f);
            int pv = (int) (2 * container.getResources().getDisplayMetrics().density + 0.5f);
            tv.setPadding(ph, pv, ph, pv);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setCornerRadius(999f);
            d.setColor(android.graphics.Color.parseColor(bgHex));
            tv.setBackground(d);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (6 * container.getResources().getDisplayMetrics().density + 0.5f));
            tv.setLayoutParams(lp);
            container.addView(tv);
            // ModelTagHelper may hide the container when it finds no keyword tags.
            // These status chips ("已应用" / "临时选中") must always be visible.
            container.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
    }


    /**
     * Recycler adapter for Sub-model picker with lightweight in-memory filtering.
     */
	@NonNull
	private com.google.android.material.chip.Chip buildSubModelFilterChip(@NonNull android.content.Context ctx, @NonNull String text) {
		com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(ctx);
		chip.setText(text);
		chip.setCheckable(true);
		chip.setClickable(true);
		chip.setEnsureMinTouchTargetSize(false);
		try { chip.setChipMinHeight(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 36f, ctx.getResources().getDisplayMetrics())); } catch (Throwable ignored) {}
		try { chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12); } catch (Throwable ignored) {}
		try {
			float r = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f, ctx.getResources().getDisplayMetrics());
			chip.setChipCornerRadius(r);
		} catch (Throwable ignored) {}
		return chip;
	}

	@NonNull
	private String getSubModelFilterModeLabel(@Nullable SubModelPickerAdapter.FilterMode mode) {
		SubModelPickerAdapter.FilterMode m = mode == null ? SubModelPickerAdapter.FilterMode.ALL : mode;
		switch (m) {
			case TESTED_CAP: return "已测上限";
			case REASONING_SUPPORTED: return "推理支持";
			case UNTESTED: return "未测试";
			case STARRED: return "收藏";
			case ALL:
			default: return "全部";
		}
	}

	@NonNull
	private String getSubModelSortModeLabel(@Nullable SubModelPickerAdapter.SortMode mode) {
		SubModelPickerAdapter.SortMode m = mode == null ? SubModelPickerAdapter.SortMode.DEFAULT : mode;
		switch (m) {
			case RECENT_USED: return "最近使用";
			case OUTPUT_CAP_DESC: return "上限高低";
			case STAR_FIRST: return "收藏优先";
			case DEFAULT:
			default: return "排序";
		}
	}

	@NonNull
	private String getSubModelSortModeStatusLabel(@Nullable SubModelPickerAdapter.SortMode mode) {
		SubModelPickerAdapter.SortMode m = mode == null ? SubModelPickerAdapter.SortMode.DEFAULT : mode;
		switch (m) {
			case RECENT_USED: return "最近使用";
			case OUTPUT_CAP_DESC: return "上限高低";
			case STAR_FIRST: return "收藏优先";
			case DEFAULT:
			default: return "默认";
		}
	}

	private interface SubModelSortPickedCallback {
		void onPicked(@NonNull SubModelPickerAdapter.SortMode mode);
	}

	private void showSubModelSortDialog(@NonNull SubModelPickerAdapter.SortMode current, @NonNull SubModelSortPickedCallback cb) {
		if (!isAdded()) return;
		final CharSequence[] labels = new CharSequence[]{"默认顺序", "最近使用（新→旧）", "输出上限高→低", "收藏优先（再按最近使用）"};
		final SubModelPickerAdapter.SortMode[] values = new SubModelPickerAdapter.SortMode[]{
				SubModelPickerAdapter.SortMode.DEFAULT,
				SubModelPickerAdapter.SortMode.RECENT_USED,
				SubModelPickerAdapter.SortMode.OUTPUT_CAP_DESC,
				SubModelPickerAdapter.SortMode.STAR_FIRST
		};
		int checked = 0;
		for (int i = 0; i < values.length; i++) {
			if (values[i] == current) { checked = i; break; }
		}
		final int[] selected = new int[]{checked};
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("子模型排序")
				.setSingleChoiceItems(labels, checked, (d, which) -> selected[0] = which)
				.setNegativeButton(R.string.btn_cancel, null)
				.setPositiveButton(R.string.btn_ok, (d, w) -> {
					int idx = selected[0];
					if (idx < 0 || idx >= values.length) idx = 0;
					cb.onPicked(values[idx]);
				})
				.show();
	}

	private static final class SubModelItem {
		final String name;
		final boolean isCustom;
		int fullIndex; // 1-based index in full list

		SubModelItem(@NonNull String name, boolean isCustom) {
			this.name = name;
			this.isCustom = isCustom;
		}
	}

    private static final class SubModelPickerAdapter extends RecyclerView.Adapter<SubModelPickerAdapter.VH> {
		interface StarToggleListener {
			void onStarToggled(@NonNull String name, boolean isStarred);
		}

        interface OnSelectionChangedListener {
            void onSelectionChanged(String item);
        }

        interface OnItemLongPressListener {
            void onItemLongPressed(@NonNull String itemName);
        }

		interface CustomActionListener {
			void onEdit(@NonNull String name);
			void onDelete(@NonNull String name);
		}

		enum FilterMode { ALL, TESTED_CAP, REASONING_SUPPORTED, UNTESTED, STARRED }
		enum SortMode { DEFAULT, RECENT_USED, OUTPUT_CAP_DESC, STAR_FIRST }

		private final LanguageModel provider;
		private final ArrayList<SubModelItem> full = new ArrayList<>();      // full list (unfiltered)
		private final ArrayList<SubModelItem> shown = new ArrayList<>();
		private final java.util.Set<String> starred;
        private String pendingSelected;
        private String appliedSelected;
        private String query = "";
		private boolean showStarredOnly = false;
		private FilterMode filterMode = FilterMode.ALL;
		private SortMode sortMode = SortMode.DEFAULT;
        private OnSelectionChangedListener listener;
        private OnItemLongPressListener onItemLongPressListener;
		private CustomActionListener customActionListener;
		private StarToggleListener starToggleListener;

		SubModelPickerAdapter(@NonNull LanguageModel provider, @NonNull List<SubModelItem> fullItems, @Nullable String appliedSelected, @Nullable String pendingSelected, @NonNull java.util.Set<String> starred) {
			this.provider = provider;
			this.starred = starred;
			this.appliedSelected = appliedSelected;
			this.pendingSelected = pendingSelected;
			setFullItems(fullItems);
        }

        void setOnSelectionChangedListener(OnSelectionChangedListener l) {
            this.listener = l;
        }

        void setOnItemLongPressListener(@Nullable OnItemLongPressListener l) {
            this.onItemLongPressListener = l;
        }

		void setOnCustomActionListener(@Nullable CustomActionListener l) {
			this.customActionListener = l;
		}

		void setOnStarToggleListener(@Nullable StarToggleListener l) {
			this.starToggleListener = l;
		}

		void setShowStarredOnly(boolean only) {
			if (this.showStarredOnly == only) return;
			this.showStarredOnly = only;
			rebuildShown();
			notifyDataSetChanged();
		}

		void setFilterMode(@NonNull FilterMode mode) {
			FilterMode m = mode == null ? FilterMode.ALL : mode;
			if (this.filterMode == m) return;
			this.filterMode = m;
			rebuildShown();
			notifyDataSetChanged();
		}

		@NonNull
		FilterMode getFilterMode() { return filterMode; }

		void setSortMode(@NonNull SortMode mode) {
			SortMode m = mode == null ? SortMode.DEFAULT : mode;
			if (this.sortMode == m) return;
			this.sortMode = m;
			rebuildShown();
			notifyDataSetChanged();
		}

		@NonNull
		SortMode getSortMode() { return sortMode; }

		void setLegacyStarredOnlyCompat(boolean only) {
			setShowStarredOnly(only);
			if (only) setFilterMode(FilterMode.STARRED);
			else if (filterMode == FilterMode.STARRED) setFilterMode(FilterMode.ALL);
		}

		void setSelected(@Nullable String selected) { // pending selected in dialog
			String oldPending = this.pendingSelected;
			this.pendingSelected = selected;
			int oldPendingPos = findShownPositionByName(oldPending);
			int newPendingPos = findShownPositionByName(selected);
			int appliedPos = findShownPositionByName(appliedSelected);
			if (oldPendingPos >= 0) notifyItemChanged(oldPendingPos);
			if (newPendingPos >= 0 && newPendingPos != oldPendingPos) notifyItemChanged(newPendingPos);
			if (appliedPos >= 0 && appliedPos != oldPendingPos && appliedPos != newPendingPos) notifyItemChanged(appliedPos);
			if ((oldPending != null && oldPendingPos < 0) || (selected != null && newPendingPos < 0) || (appliedSelected != null && appliedPos < 0)) {
				notifyDataSetChanged();
			}
		}

		void setAppliedSelected(@Nullable String applied) {
			String oldApplied = this.appliedSelected;
			this.appliedSelected = applied;
			int oldPos = findShownPositionByName(oldApplied);
			int newPos = findShownPositionByName(applied);
			if (oldPos >= 0) notifyItemChanged(oldPos);
			if (newPos >= 0 && newPos != oldPos) notifyItemChanged(newPos);
			if ((oldApplied != null && oldPos < 0) || (applied != null && newPos < 0)) notifyDataSetChanged();
		}

        int getPendingIndexInFull() {
            if (pendingSelected == null) return -1;
			for (int i = 0; i < full.size(); i++) {
				if (pendingSelected.equals(full.get(i).name)) return i;
            }
            return -1;
        }

		int getAppliedIndexInFull() {
			if (appliedSelected == null) return -1;
			for (int i = 0; i < full.size(); i++) {
				if (appliedSelected.equals(full.get(i).name)) return i;
			}
			return -1;
		}

		int getPendingIndexInShown() {
			return findShownPositionByName(pendingSelected);
		}

		int getAppliedIndexInShown() {
			return findShownPositionByName(appliedSelected);
		}

		@Nullable
		String getFirstItemName() {
			return full.isEmpty() ? null : full.get(0).name;
		}

        void setQuery(@NonNull String q) {
            String nq = q != null ? q.trim() : "";
            if (nq.equals(query)) return;
            query = nq;
            rebuildShown();
            notifyDataSetChanged();
        }

		void setFullItems(@NonNull List<SubModelItem> items) {
			full.clear();
			if (items != null) full.addAll(items);
			for (int i = 0; i < full.size(); i++) {
				full.get(i).fullIndex = i + 1;
			}
			rebuildShown();
			notifyDataSetChanged();
		}

        private void rebuildShown() {
            shown.clear();
			String ql = query.isEmpty() ? "" : query.toLowerCase(java.util.Locale.US);
			for (SubModelItem it : full) {
				if (it == null || it.name == null) continue;
				String n = it.name;
				if (!ql.isEmpty() && !n.toLowerCase(java.util.Locale.US).contains(ql)) continue;
				boolean isStar = starred != null && starred.contains(n);
				if (showStarredOnly && !isStar) continue;
				if (!passesFilter(it, isStar)) continue;
				shown.add(it);
			}
			applySort();
        }

		private boolean passesFilter(@NonNull SubModelItem it, boolean isStarredItem) {
			FilterMode fm = filterMode == null ? FilterMode.ALL : filterMode;
			if (fm == FilterMode.ALL) return true;
			if (fm == FilterMode.STARRED) return isStarredItem;
			try {
				SPManager sp = SPManager.getInstance();
				Integer exact = sp.getCachedSafeMaxTokens(provider, it.name);
				Integer lb = null;
				try { lb = sp.getCachedSafeMaxTokensLowerBound(provider, it.name); } catch (Throwable ignored) {}
				Boolean reasoning = null;
				try { reasoning = sp.getCachedSupportsReasoningThinking(provider, it.name); } catch (Throwable ignored) {}
				Boolean temp = null;
				try { temp = sp.getCachedSupportsTemperature(provider, it.name); } catch (Throwable ignored) {}
				boolean hasCap = (exact != null && exact > 0) || (lb != null && lb > 0);
				boolean testedAny = hasCap || reasoning != null || temp != null;
				switch (fm) {
					case TESTED_CAP: return hasCap;
					case REASONING_SUPPORTED: return Boolean.TRUE.equals(reasoning);
					case UNTESTED: return !testedAny;
					default: return true;
				}
			} catch (Throwable ignored) {
				return fm == FilterMode.UNTESTED ? true : false;
			}
		}

		private int outputCapSortScore(@Nullable String name) {
			if (TextUtils.isEmpty(name) || !SPManager.isReady()) return -1;
			try {
				SPManager sp = SPManager.getInstance();
				Integer exact = sp.getCachedSafeMaxTokens(provider, name);
				if (exact != null && exact > 0) return exact * 10 + 1; // exact slightly ahead of lower-bound same value
				Integer lb = null;
				try { lb = sp.getCachedSafeMaxTokensLowerBound(provider, name); } catch (Throwable ignored) {}
				if (lb != null && lb > 0) return lb * 10;
			} catch (Throwable ignored) {}
			return -1;
		}

		private long lastUsedSortScore(@Nullable String name) {
			if (TextUtils.isEmpty(name) || !SPManager.isReady()) return 0L;
			try { return SPManager.getInstance().getSubModelLastUsedAt(provider, name); } catch (Throwable ignored) { return 0L; }
		}

		private void applySort() {
			SortMode sm = sortMode == null ? SortMode.DEFAULT : sortMode;
			if (sm == SortMode.DEFAULT || shown.size() <= 1) return;
			java.util.Collections.sort(shown, (a, b) -> {
				if (a == null || a.name == null) return 1;
				if (b == null || b.name == null) return -1;
				if (sm == SortMode.RECENT_USED) {
					long la = lastUsedSortScore(a.name);
					long lb = lastUsedSortScore(b.name);
					if (la != lb) return Long.compare(lb, la);
				}
				if (sm == SortMode.OUTPUT_CAP_DESC) {
					int ca = outputCapSortScore(a.name);
					int cb = outputCapSortScore(b.name);
					if (ca != cb) return Integer.compare(cb, ca);
				}
				if (sm == SortMode.STAR_FIRST) {
					boolean sa = starred != null && starred.contains(a.name);
					boolean sb = starred != null && starred.contains(b.name);
					if (sa != sb) return sa ? -1 : 1;
					long la = lastUsedSortScore(a.name);
					long lb = lastUsedSortScore(b.name);
					if (la != lb) return Long.compare(lb, la);
				}
				return Integer.compare(a.fullIndex, b.fullIndex);
			});
		}

		private void bindCapabilityStatus(@NonNull TextView tv, @Nullable String subModelName) {
			if (TextUtils.isEmpty(subModelName)) {
				tv.setVisibility(View.GONE);
				return;
			}
			try {
				if (!ModelCapabilities.isChatCapabilityTestable(provider, subModelName)) {
					tv.setText("能力测试：非聊天模型（不可测试）");
					tv.setVisibility(View.VISIBLE);
					try {
						tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurfaceVariant));
					} catch (Throwable ignored) {}
					return;
				}
			} catch (Throwable ignored) {}
			Boolean t = null;
			Boolean r = null;
			Integer cap = null;
			String capSource = null;
			long capUpdatedAt = 0L;
			try {
				SPManager sp = SPManager.getInstance();
				t = sp.getCachedSupportsTemperature(provider, subModelName);
				r = sp.getCachedSupportsReasoningThinking(provider, subModelName);
				cap = sp.getCachedSafeMaxTokens(provider, subModelName);
				if (cap == null || cap <= 0) { try { cap = sp.getCachedSafeMaxTokensLowerBound(provider, subModelName); } catch (Throwable ignored2) {} }
				capSource = sp.getCachedSafeMaxTokensSource(provider, subModelName);
				try { capUpdatedAt = sp.getCachedSafeMaxTokensUpdatedAt(provider, subModelName); } catch (Throwable ignored2) {}
			} catch (Throwable ignored) {}
			String normal = t == null ? "未测" : (Boolean.TRUE.equals(t) ? "支持" : "不支持");
			String reason = r == null ? "未测" : (Boolean.TRUE.equals(r) ? "支持" : "不支持");
			StringBuilder capLine = new StringBuilder();
			if (cap != null && cap > 0) {
				boolean lowerOnly = !TextUtils.isEmpty(capSource) && capSource.toLowerCase(java.util.Locale.US).contains("lower");
				capLine.append(lowerOnly ? "输出下限≥" : "输出上限≈").append(cap);
				if (!TextUtils.isEmpty(capSource)) {
					String s = capSource.toLowerCase(java.util.Locale.US);
					if (s.contains("manual_precise")) capLine.append("（手动精确）");
					else if (s.contains("manual_conservative")) capLine.append("（手动保守）");
					else if (s.contains("auto_retry")) capLine.append("（自动）");
				}
			} else {
				capLine.append("输出上限：未学");
			}
			if (capUpdatedAt > 0L) {
				long ageMs = Math.max(0L, System.currentTimeMillis() - capUpdatedAt);
				long ageDays = ageMs / (24L * 60L * 60L * 1000L);
				capLine.append(" · ").append(LabFragment.formatRelativeTimeShort(capUpdatedAt));
				if (ageDays >= 14L) capLine.append("（较旧，建议复测）");
			}
			tv.setText("普通思考：" + normal + "   推理思考：" + reason + "\n" + capLine);
			tv.setVisibility(View.VISIBLE);
			int attr = (Boolean.FALSE.equals(t) || Boolean.FALSE.equals(r))
					? com.google.android.material.R.attr.colorTertiary
					: ((Boolean.TRUE.equals(t) || Boolean.TRUE.equals(r) || (cap != null && cap > 0))
					? androidx.appcompat.R.attr.colorPrimary
					: com.google.android.material.R.attr.colorOnSurfaceVariant);
			try {
				tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(tv, attr));
			} catch (Throwable ignored) {}
		}

        private void appendOutputCapBadge(@NonNull LinearLayout tagContainer, @Nullable String subModelName) {
            try {
                if (TextUtils.isEmpty(subModelName) || !SPManager.isReady()) return;
                SPManager sp = SPManager.getInstance();
                Integer exact = sp.getCachedSafeMaxTokens(provider, subModelName);
                Integer lb = null;
                try { lb = sp.getCachedSafeMaxTokensLowerBound(provider, subModelName); } catch (Throwable ignored) {}
                Integer v = (exact != null && exact > 0) ? exact : ((lb != null && lb > 0) ? lb : null);
                if (v == null || v <= 0) return;
                boolean lower = (exact == null || exact <= 0);
                android.widget.TextView tv = new android.widget.TextView(tagContainer.getContext());
                tv.setText((lower ? "📏 下限≥" : "📏 上限≈") + v);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
                try { tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD); } catch (Throwable ignored) {}
                int ph = (int) (6 * tagContainer.getResources().getDisplayMetrics().density + 0.5f);
                int pv = (int) (2 * tagContainer.getResources().getDisplayMetrics().density + 0.5f);
                tv.setPadding(ph, pv, ph, pv);
                int bg = android.graphics.Color.parseColor(lower ? "#1A607D8B" : "#1A3F51B5");
                int fg = android.graphics.Color.parseColor(lower ? "#607D8B" : "#3F51B5");
                tv.setTextColor(fg);
                android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
                d.setColor(bg);
                d.setCornerRadius(999f);
                tv.setBackground(d);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd((int) (6 * tagContainer.getResources().getDisplayMetrics().density + 0.5f));
                tv.setLayoutParams(lp);
                tagContainer.addView(tv);
                tagContainer.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {}
        }

private int findShownPositionByName(@Nullable String name) {
			if (name == null) return -1;
			for (int i = 0; i < shown.size(); i++) {
				SubModelItem it = shown.get(i);
				if (it != null && name.equals(it.name)) return i;
			}
			return -1;
		}

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_submodel_option, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
			SubModelItem it = shown.get(position);
			final String name = it != null ? it.name : "";
			holder.index.setText(it != null ? String.valueOf(it.fullIndex) : "");
			holder.title.setText(name);
			// Optional: tap the index to copy (index + model) for quick sharing.
			holder.index.setOnClickListener(v -> {
				if (TextUtils.isEmpty(name)) return;
				try {
					String idx = holder.index.getText() == null ? "" : String.valueOf(holder.index.getText());
					String clip = TextUtils.isEmpty(idx) ? name : (idx + " " + name);
					android.content.ClipboardManager cm = (android.content.ClipboardManager) v.getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
					if (cm != null) {
						cm.setPrimaryClip(android.content.ClipData.newPlainText("model", clip));
					}
					android.widget.Toast.makeText(v.getContext(), R.string.msg_copied, android.widget.Toast.LENGTH_SHORT).show();
				} catch (Throwable ignored) {}
			});
			// CRITICAL for marquee: must be selected=true.
			holder.title.setSelected(true);
			boolean isPending = !TextUtils.isEmpty(name) && name.equals(pendingSelected);
			boolean isApplied = !TextUtils.isEmpty(name) && name.equals(appliedSelected);
			holder.root.setActivated(isPending);
			holder.root.setSelected(isApplied);
			holder.root.setAlpha((isPending || isApplied) ? 1f : 0.98f);
			boolean isCustom = it != null && it.isCustom;
			holder.actions.setVisibility(isCustom ? View.VISIBLE : View.GONE);
			ModelTagHelper.bindTags(holder.tags.getContext(), name, holder.tags, isCustom);
			if (isApplied && isPending) {
				LabFragment.addMiniChip(holder.tags, "✅ 已应用", "#1A4CAF50", "#2E7D32");
				LabFragment.addMiniChip(holder.tags, "🟣 临时选中", "#1A7E57C2", "#5E35B1");
			} else {
				if (isApplied) LabFragment.addMiniChip(holder.tags, "✅ 已应用", "#1A4CAF50", "#2E7D32");
				if (isPending) LabFragment.addMiniChip(holder.tags, "🟣 临时选中", "#1A7E57C2", "#5E35B1");
			}
			appendOutputCapBadge(holder.tags, name);
			bindCapabilityStatus(holder.capStatus, name);
			// Ensure the tags container is shown when we add status chips (pending/applied).
			// Some models may have no keyword-based tags, in which case ModelTagHelper hides the container.
			try {
				holder.tags.setVisibility(holder.tags.getChildCount() > 0 ? View.VISIBLE : View.GONE);
			} catch (Throwable ignored) {}
			// Star binding
			final boolean isStarred = starred != null && !TextUtils.isEmpty(name) && starred.contains(name);
			// Keep the star icon consistent; use a yellow background highlight to indicate "favorited".
			holder.ivStar.setActivated(isStarred);
			holder.ivStar.setImageResource(R.drawable.ic_star);
			int tint = com.google.android.material.color.MaterialColors.getColor(
					holder.ivStar,
					isStarred ? com.google.android.material.R.attr.colorOnSurface : com.google.android.material.R.attr.colorOnSurfaceVariant
			);
			holder.ivStar.setColorFilter(tint);
			holder.root.setOnClickListener(v -> {
				String old = pendingSelected;
				pendingSelected = name;
				int oldPos = findShownPositionByName(old);
				int newPos = holder.getBindingAdapterPosition();
				int appliedPos = findShownPositionByName(appliedSelected);
				if (oldPos >= 0) notifyItemChanged(oldPos);
				if (newPos >= 0) notifyItemChanged(newPos);
				if (appliedPos >= 0 && appliedPos != oldPos && appliedPos != newPos) notifyItemChanged(appliedPos);
				if (listener != null && !TextUtils.isEmpty(name)) listener.onSelectionChanged(name);
			});

			holder.root.setOnLongClickListener(v -> {
				if (onItemLongPressListener != null && !TextUtils.isEmpty(name)) {
					try {
						onItemLongPressListener.onItemLongPressed(name);
						return true;
					} catch (Throwable ignored) {
						return false;
					}
				}
				return false;
			});

			holder.ivStar.setOnClickListener(v -> {
				if (TextUtils.isEmpty(name) || starred == null) return;
				boolean nowStarred = starred.contains(name);
				if (nowStarred) starred.remove(name);
				else starred.add(name);
				if (starToggleListener != null) {
					starToggleListener.onStarToggled(name, !nowStarred);
				}
				if (showStarredOnly && nowStarred) {
					rebuildShown();
					notifyDataSetChanged();
				} else {
					notifyItemChanged(holder.getBindingAdapterPosition());
				}
			});

			holder.ivEdit.setOnClickListener(v -> {
				if (customActionListener != null && isCustom && !TextUtils.isEmpty(name)) {
					customActionListener.onEdit(name);
				}
			});
			holder.ivDelete.setOnClickListener(v -> {
				if (customActionListener != null && isCustom && !TextUtils.isEmpty(name)) {
					customActionListener.onDelete(name);
				}
			});
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final View root;
			final TextView index;
            final TextView title;
			final ImageView ivStar;
			final View actions;
			final android.widget.LinearLayout tags;
			final TextView capStatus;
			final ImageView ivEdit;
			final ImageView ivDelete;

            VH(@NonNull View itemView) {
                super(itemView);
                root = itemView.findViewById(R.id.item_root);
				index = itemView.findViewById(R.id.tv_index);
                title = itemView.findViewById(R.id.tv_item);
				ivStar = itemView.findViewById(R.id.iv_star);
				actions = itemView.findViewById(R.id.row_actions);
				tags = itemView.findViewById(R.id.row_tags);
				capStatus = itemView.findViewById(R.id.tv_capability_status);
				ivEdit = itemView.findViewById(R.id.iv_edit);
				ivDelete = itemView.findViewById(R.id.iv_delete);
            }
        }
    }


    private String getStreamingNonLinearModelLabel(int model) {
        int m = model;
        switch (m) {
            case SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT:
                return getString(R.string.ui_streaming_nl_model_linear_constant);
            case SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY:
                return getString(R.string.ui_streaming_nl_model_exponential_decay);
            case SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER:
                return getString(R.string.ui_streaming_nl_model_sine_wave_jitter);
            case SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR:
                return getString(R.string.ui_streaming_nl_model_damped_oscillator);
            case SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST:
                return getString(R.string.ui_streaming_nl_model_square_wave_burst);
            case SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK:
                return getString(R.string.ui_streaming_nl_model_markov_random_walk);
            case SPManager.STREAM_NL_MODEL_PERLIN_NOISE:
                return getString(R.string.ui_streaming_nl_model_perlin_noise);
            case SPManager.STREAM_NL_MODEL_PID_CONTROLLER:
                return getString(R.string.ui_streaming_nl_model_pid_controller);
            case SPManager.STREAM_NL_MODEL_LOGISTIC_FATIGUE:
                return getString(R.string.ui_streaming_nl_model_logistic_fatigue);
            case SPManager.STREAM_NL_MODEL_RETRO_TYPEWRITER:
                return getString(R.string.ui_streaming_nl_model_retro_typewriter);
            default:
                return "Model #" + String.valueOf(m);
        }
    }

    private String getStreamingModeLabel(int mode) {
        switch (mode) {
            case SPManager.STREAM_MODE_SSE:
                return getString(R.string.ui_streaming_mode_sse);
            case SPManager.STREAM_MODE_JSONL:
                return getString(R.string.ui_streaming_mode_jsonl);
            case SPManager.STREAM_MODE_TYPEWRITER:
                return getString(R.string.ui_streaming_mode_typewriter);
            case SPManager.STREAM_MODE_AUTO:
            default:
                return getString(R.string.ui_streaming_mode_auto);
        }
    }

    /**
     * Display text for the streaming mode row.
     * When mode is AUTO, we also show the last detected format (SSE/JSONL) as a small second line.
     */
    private CharSequence getStreamingModeValueText(int mode) {
        String title = getStreamingModeLabel(mode);
        if (mode != SPManager.STREAM_MODE_AUTO) return title;

        int detected = -1;
        try {
            if (SPManager.isReady()) detected = SPManager.getInstance().getStreamingOutputAutoDetectedMode();
        } catch (Throwable ignored) {
        }

        String detectedLabel;
        if (detected == SPManager.STREAM_MODE_SSE) {
            detectedLabel = "SSE";
        } else if (detected == SPManager.STREAM_MODE_JSONL) {
            detectedLabel = "JSONL";
        } else {
            detectedLabel = getString(R.string.ui_streaming_auto_detect_unknown);
        }

        String sub = getString(R.string.ui_streaming_auto_detect_result, detectedLabel);
        int subColor = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF777777);

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(title);
        sb.append("\n");
        int start = sb.length();
        sb.append(sub);
        sb.setSpan(new RelativeSizeSpan(0.85f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(subColor), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private CharSequence makeModeChoiceItem(String title, String subtitle) {
        int subColor = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF777777);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int t0 = sb.length();
        sb.append(title);
        sb.setSpan(new StyleSpan(Typeface.BOLD), t0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            sb.append("\n");
            int s0 = sb.length();
            sb.append(subtitle);
            sb.setSpan(new RelativeSizeSpan(0.85f), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(subColor), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private void setStreamingSelfCheckRowEnabled(@Nullable View selfCheckRow, @Nullable TextView tvSelfCheckValue, boolean enabled) {
        float alpha = enabled ? 1.0f : 0.45f;
        if (selfCheckRow != null) {
            selfCheckRow.setEnabled(enabled);
            selfCheckRow.setAlpha(alpha);
        }
        if (tvSelfCheckValue != null) {
            tvSelfCheckValue.setEnabled(enabled);
        }
    }
    private static String findLastLineContaining(String text, String needle) {
        if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) return null;
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String ln = lines[i];
            if (ln != null && ln.contains(needle)) return ln;
        }
        return null;
    }


    private void showStreamingSelfCheckDialog() {
        if (getContext() == null) return;

        String meta = "";
        try { meta = AiDiagnostics.readLastRequestMeta(); } catch (Throwable ignored) {}
        if (meta == null) meta = "";

        String raw = "";
        try { raw = AiDiagnostics.readLastRaw(); } catch (Throwable ignored) {}
        if (raw == null) raw = "";

        if (meta.trim().isEmpty() && raw.trim().isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.ui_streaming_selfcheck_title)
                    .setMessage(R.string.ui_streaming_selfcheck_no_data)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        String diag = "";
        try { diag = AiDiagnostics.readAll(); } catch (Throwable ignored) {}
        if (diag == null) diag = "";

        String detectLine = findLastLineContaining(diag, "[PARSER_DETECT]");
        String statsLine = findLastLineContaining(diag, "[PARSER_STATS]");

        String detected = null;
        if (detectLine != null) {
            int m = detectLine.indexOf("mode=");
            if (m >= 0) {
                String tail = detectLine.substring(m + 5);
                int sp = tail.indexOf(' ');
                detected = (sp > 0) ? tail.substring(0, sp) : tail;
            }
        }

        if (detected == null || detected.trim().isEmpty()) {
            String first = null;
            for (String ln : raw.split("\n")) {
                if (ln == null) continue;
                String t = ln.trim();
                if (t.isEmpty()) continue;
                first = t;
                break;
            }
            if (first != null) {
                if (first.startsWith("{") || first.startsWith("[")) detected = "JSONL";
                else if (first.startsWith("data:") || first.startsWith("event:") || first.startsWith(":")) detected = "SSE";
            }
        }
        if (detected == null) detected = getString(R.string.ui_streaming_auto_detect_unknown);

        // Heuristics (statsLine overrides these when present).
        boolean emittedAny = raw.contains("\"content\":\"");
        int nullFrames = raw.contains("\"content\":null") ? 1 : 0;
        int reasoningFrames = raw.contains("reasoning_content") ? 1 : 0;

        if (statsLine != null) {
            try {
                for (String part : statsLine.split(" ")) {
                    if (part.startsWith("nullContentFrames=")) {
                        nullFrames = Integer.parseInt(part.substring("nullContentFrames=".length()));
                    }
                    if (part.startsWith("reasoningFrames=")) {
                        reasoningFrames = Integer.parseInt(part.substring("reasoningFrames=".length()));
                    }
                    if (part.startsWith("emittedAny=")) {
                        emittedAny = part.substring("emittedAny=".length()).trim().equalsIgnoreCase("true");
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        boolean reasoningNull = (reasoningFrames > 0 && nullFrames > 0);

        String yes = getString(R.string.ui_yes);
        String no = getString(R.string.ui_no);

        StringBuilder msg = new StringBuilder();
        if (!meta.trim().isEmpty()) {
            msg.append(getString(R.string.ui_streaming_selfcheck_last_request))
               .append("\n")
               .append(meta.trim())
               .append("\n\n");
        }
        msg.append(getString(R.string.ui_streaming_selfcheck_detected))
           .append(" ")
           .append(detected)
           .append("\n");
        msg.append(getString(R.string.ui_streaming_selfcheck_has_content))
           .append(" ")
           .append(emittedAny ? yes : no)
           .append("\n");
        msg.append(getString(R.string.ui_streaming_selfcheck_reasoning_null))
           .append(" ")
           .append(reasoningNull ? yes : no);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_streaming_selfcheck_title)
                .setMessage(msg.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }


    private void showStreamingModeDialog(int current, IntConsumer onSelected) {
        if (getContext() == null) return;

        final int[] values = new int[] {
                SPManager.STREAM_MODE_AUTO,
                SPManager.STREAM_MODE_SSE,
                SPManager.STREAM_MODE_JSONL,
                SPManager.STREAM_MODE_TYPEWRITER
        };
        final CharSequence[] items = new CharSequence[] {
                makeModeChoiceItem(getString(R.string.ui_streaming_mode_auto), getString(R.string.ui_streaming_mode_auto_desc)),
                makeModeChoiceItem(getString(R.string.ui_streaming_mode_sse), getString(R.string.ui_streaming_mode_sse_desc)),
                makeModeChoiceItem(getString(R.string.ui_streaming_mode_jsonl), getString(R.string.ui_streaming_mode_jsonl_desc)),
                makeModeChoiceItem(getString(R.string.ui_streaming_mode_typewriter), getString(R.string.ui_streaming_mode_typewriter_desc))
        };

        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                checked = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_streaming_mode)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which >= 0 && which < values.length && onSelected != null) {
                        onSelected.accept(values[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String getStreamingGranularityLabel(int granularity) {
        switch (granularity) {
            case SPManager.STREAM_GRANULARITY_WORDS:
                return getString(R.string.ui_streaming_granularity_words);
            case SPManager.STREAM_GRANULARITY_PUNCT:
                return getString(R.string.ui_streaming_granularity_punct);
            case SPManager.STREAM_GRANULARITY_CHARS:
            default:
                return getString(R.string.ui_streaming_granularity_chars);
        }
    }

    private void showStreamingGranularityDialog(int current, IntConsumer onSelected) {
        if (getContext() == null) return;

        final int[] values = new int[] {
                SPManager.STREAM_GRANULARITY_CHARS,
                SPManager.STREAM_GRANULARITY_WORDS,
                SPManager.STREAM_GRANULARITY_PUNCT
        };
        final String[] items = new String[] {
                getString(R.string.ui_streaming_granularity_chars),
                getString(R.string.ui_streaming_granularity_words),
                getString(R.string.ui_streaming_granularity_punct)
        };

        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                checked = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_streaming_granularity)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which >= 0 && which < values.length && onSelected != null) {
                        onSelected.accept(values[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String getNormalModelThinkingSummary(float value) {
        try {
            NormalModelThinkingOption opt = NormalModelThinkingOptions.findByValue(value);
            if (opt != null && !TextUtils.isEmpty(opt.title)) return opt.title;
        } catch (Throwable ignored) {
        }
        try {
            return String.format(java.util.Locale.getDefault(), "%.1f", value);
        } catch (Throwable ignored) {
            return String.valueOf(value);
        }
    }

    private void showNormalModelThinkingDialog(float currentValue, java.util.function.Consumer<Float> onSelected) {
        if (getContext() == null) return;

        java.util.List<NormalModelThinkingOption> options = NormalModelThinkingOptions.buildOptions();
        int initial = NormalModelThinkingOptions.indexOf(currentValue);
        if (initial < 0) initial = NormalModelThinkingOptions.indexOf(0.7f);
        if (initial < 0) initial = 0;

        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_normal_model_thinking, null);
        RecyclerView rv = content.findViewById(R.id.rv_normal_model_thinking_options);
        android.widget.Button btnAnimMode = content.findViewById(R.id.btn_temp_animation_mode);
        android.widget.Button btnAnimToggle = content.findViewById(R.id.btn_temp_animation_toggle);
        android.widget.SeekBar seekAnimSpeed = content.findViewById(R.id.seek_temp_anim_speed);
        TextView tvAnimSpeedValue = content.findViewById(R.id.tv_temp_anim_speed_value);
        View llSpeedControls = content.findViewById(R.id.ll_temp_anim_speed_controls);

        final int[] pendingIndex = new int[]{initial};
        final int[] animMode = new int[]{getSavedNormalThinkingAnimMode()};
        final boolean[] animEnabled = new boolean[]{getSavedNormalThinkingAnimEnabled()};
        final int[] animSpeed = new int[]{getSavedNormalThinkingAnimSpeedPercent()};

        final NormalModelThinkingOptionAdapter adapter = new NormalModelThinkingOptionAdapter(options, initial, pos -> {
            if (pos < 0 || pos >= options.size()) return;
            pendingIndex[0] = pos;
        });

        try {
            adapter.setAnimationMode(animMode[0]);
            adapter.setAnimationEnabled(animEnabled[0]);
            adapter.setAnimationSpeedPercent(animSpeed[0]);
        } catch (Throwable ignored) {
        }

        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(requireContext()));
            rv.setAdapter(adapter);
            rv.setNestedScrollingEnabled(false);
            rv.post(() -> {
                try {
                    android.util.DisplayMetrics dm = content.getResources().getDisplayMetrics();
                    int maxH = (int) (dm.heightPixels * 0.50f);
                    ViewGroup.LayoutParams lp = rv.getLayoutParams();
                    if (lp != null) {
                        int h = rv.computeVerticalScrollRange();
                        if (h <= 0) h = rv.getMeasuredHeight();
                        lp.height = Math.min(maxH, Math.max(h, (int) (180 * dm.density)));
                        rv.setLayoutParams(lp);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    adapter.restartSelectedIndicatorAnimation();
                } catch (Throwable ignored) {
                }
            });
        }

        final Runnable refreshAnimButtons = new Runnable() {
            @Override public void run() {
                if (btnAnimMode != null) {
                    String label;
                    switch (animMode[0]) {
                        case NormalModelThinkingOptionAdapter.ANIM_MODE_TRI_PHASE:
                            label = "三段式机械节";
                            break;
                        case NormalModelThinkingOptionAdapter.ANIM_MODE_SWEEP:
                            label = "流光扫掠";
                            break;
                        case NormalModelThinkingOptionAdapter.ANIM_MODE_SEQ_LOOP:
                        default:
                            label = "逐格充能";
                            break;
                    }
                    btnAnimMode.setText("动画选择：" + label);
                }
                if (btnAnimToggle != null) btnAnimToggle.setText(animEnabled[0] ? "关闭动画" : "开启动画");
                if (seekAnimSpeed != null) seekAnimSpeed.setEnabled(animEnabled[0]);
                if (llSpeedControls != null) llSpeedControls.setAlpha(animEnabled[0] ? 1f : 0.5f);
                if (tvAnimSpeedValue != null) tvAnimSpeedValue.setText(getAnimSpeedPercentLabel(animSpeed[0]));
                try {
                    adapter.setAnimationMode(animMode[0]);
                    adapter.setAnimationEnabled(animEnabled[0]);
                    adapter.setAnimationSpeedPercent(animSpeed[0]);
                } catch (Throwable ignored) {
                }
            }
        };

        if (seekAnimSpeed != null) {
            seekAnimSpeed.setMax(100);
            seekAnimSpeed.setProgress(animSpeed[0]);
            seekAnimSpeed.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    animSpeed[0] = progress;
                    if (tvAnimSpeedValue != null) tvAnimSpeedValue.setText(getAnimSpeedPercentLabel(progress));
                    try { adapter.setAnimationSpeedPercent(progress); } catch (Throwable ignored) {}
                    putUiAnimInt(PREF_NORMAL_THINK_ANIM_SPEED, progress);
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
            });
        }

        if (btnAnimMode != null) {
            btnAnimMode.setOnClickListener(v -> {
                final String[] labels = new String[]{"逐格充能循环", "三段式机械节", "流光扫掠"};
                final int[] values = new int[]{
                        NormalModelThinkingOptionAdapter.ANIM_MODE_SEQ_LOOP,
                        NormalModelThinkingOptionAdapter.ANIM_MODE_TRI_PHASE,
                        NormalModelThinkingOptionAdapter.ANIM_MODE_SWEEP
                };
                int checked = 0;
                for (int i = 0; i < values.length; i++) if (values[i] == animMode[0]) { checked = i; break; }

                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("选择动画")
                        .setSingleChoiceItems(labels, checked, (d, which) -> {
                            if (which >= 0 && which < values.length) {
                                animMode[0] = values[which];
                                putUiAnimInt(PREF_NORMAL_THINK_ANIM_MODE, animMode[0]);
                                refreshAnimButtons.run();
                            }
                            d.dismiss();
                        })
                        .show();
            });
        }

        if (btnAnimToggle != null) {
            btnAnimToggle.setOnClickListener(v -> {
                animEnabled[0] = !animEnabled[0];
                putUiAnimBool(PREF_NORMAL_THINK_ANIM_ENABLED, animEnabled[0]);
                refreshAnimButtons.run();
            });
        }

        refreshAnimButtons.run();

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_thinking_depth)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int idx = pendingIndex[0];
                    if (idx < 0) idx = 0;
                    if (idx >= options.size()) idx = options.size() - 1;
                    if (onSelected != null) onSelected.accept(options.get(idx).value);
                })
                .create();

        dialog.setOnShowListener(dlg -> {
            try { adapter.restartSelectedIndicatorAnimation(); } catch (Throwable ignored) {}
        });
        dialog.setOnDismissListener(dlg -> {
            try { adapter.cancelSelectedIndicatorAnimation(); } catch (Throwable ignored) {}
            refreshAiMultilineSendRow();

        restartSettingsRowPreviewAnimations();
        });
        dialog.show();
        applyThinkingDialogWidth(dialog);
    }


    private void applyThinkingDialogWidth(@Nullable androidx.appcompat.app.AlertDialog dialog) {
        if (dialog == null) return;
        try {
            android.view.Window window = dialog.getWindow();
            if (window == null) return;
            android.util.DisplayMetrics dm = requireContext().getResources().getDisplayMetrics();
            int width = (int) (dm.widthPixels * 0.90f);
            int minPx = (int) (320f * dm.density);
            if (width < minPx) width = minPx;
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {}
    }

    private String getOutputLengthSummary(int tokens) {
        int v = tokens;
        if (v <= 0) v = 1024;

        try {
            OutputLengthOption fixed = OutputLengthOptions.findFixedByTokens(v);
            if (fixed != null && !TextUtils.isEmpty(fixed.title)) {
                return fixed.title;
            }
        } catch (Throwable ignored) {
        }

        String customLabel;
        try {
            customLabel = getString(R.string.ui_output_length_custom);
        } catch (Throwable ignored) {
            customLabel = "Custom";
        }
        return customLabel + " · " + v + " Token";
    }

    private int safeGetMaxTokensLimit() {
        try {
            int v = SPManager.getInstance().getMaxTokensLimit();
            if (v > 0) return v;
        } catch (Throwable ignored) {
        }
        return 1024;
    }

    private void updateCustomApproxText(@Nullable TextView tvHint, @Nullable String raw, @Nullable Integer cachedCap) {
        if (tvHint == null) return;

        int tokens = 0;
        try {
            if (raw != null && !raw.trim().isEmpty()) {
                tokens = Integer.parseInt(raw.trim());
            }
        } catch (Throwable ignored) {
            tokens = 0;
        }

        if (tokens <= 0) {
            tvHint.setText("");
            return;
        }

        int approxChars = (int) Math.round(tokens * 0.78d);
        int approxWords = (int) Math.round(tokens * 0.75d);
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(getString(R.string.ui_output_length_custom_approx, approxChars, approxWords));
        } catch (Throwable ignored) {
            sb.append("Approx. ").append(approxChars).append(" chars, ").append(approxWords).append(" words");
        }
        appendCapHintIfNeeded(sb, tokens, cachedCap);
        tvHint.setText(sb.toString());
    }


    private interface OutputLengthConfirmListener {
        void onConfirmed(int tokens, boolean isCustomSelection);
    }

    private void refreshOutputLengthRowUi() {
        try {
            int tokens = SPManager.getInstance().getMaxTokensLimit();
            Integer rowCap = resolveCurrentModelCachedSafeMaxTokens();
            int displayTokens = tokens;
            if (rowCap != null && rowCap > 0) {
                int floor = OutputLengthOptions.findFloorFixedTokens(rowCap);
                if (floor > 0 && tokens > floor) displayTokens = floor;
            }
            if (tvOutputLengthValue != null) tvOutputLengthValue.setText(getOutputLengthSummary(displayTokens));
            if (tvOutputLengthCapsule != null) TokenTagHelper.applyToCapsule(tvOutputLengthCapsule, displayTokens);

            if (tvOutputLengthEffectiveHint != null) {
				Integer cap = rowCap;
				String capSource = resolveCurrentModelCachedSafeMaxTokensSource();
				boolean lowerOnly = !TextUtils.isEmpty(capSource) && capSource.toLowerCase(java.util.Locale.US).contains("lower");
                int selectedTokens = 0;
                boolean isCustomSelection = false;
                int selectionSource = SPManager.MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED;
                try { selectedTokens = SPManager.getInstance().getMaxTokensLimit(); } catch (Throwable ignored) {}
                try { isCustomSelection = SPManager.getInstance().getMaxTokensIsCustom(); } catch (Throwable ignored) {}
                try { selectionSource = SPManager.getInstance().getMaxTokensSelectionSource(); } catch (Throwable ignored) {}
				// Keep the row hint short to avoid squeezing the left title; full details are available via long-press menu.
				String selectionModeLabel = (selectionSource == SPManager.MAX_TOKENS_SELECTION_SOURCE_USER_FIXED)
						? "用户固定"
						: "自动映射";

				if (cap != null && cap > 0) {
					int floor = OutputLengthOptions.findFloorFixedTokens(cap);
					StringBuilder sb = new StringBuilder();
					if (displayTokens != selectedTokens) {
						sb.append("显示档位").append(displayTokens).append("（受上限约束）").append(" · ");
					}
					sb.append(lowerOnly ? "保护≥" : "上限≈").append(cap);
					sb.append(" · 档位").append(selectedTokens);
					if (isCustomSelection) sb.append("(自定义)");
					sb.append(" · ").append(selectionModeLabel);
					if (!isCustomSelection && floor > 0 && floor != selectedTokens) {
						sb.append(" · 建议≤").append(floor);
					} else if (isCustomSelection && selectedTokens > cap) {
						sb.append(" · 可能失败");
					}
					tvOutputLengthEffectiveHint.setText(sb.toString());
					tvOutputLengthEffectiveHint.setVisibility(View.VISIBLE);
				} else {
					StringBuilder sb = new StringBuilder();
					sb.append("未学习上限");
					sb.append(" · 档位").append(selectedTokens);
					if (isCustomSelection) sb.append("(自定义)");
					sb.append(" · ").append(selectionModeLabel);
					tvOutputLengthEffectiveHint.setText(sb.toString());
					tvOutputLengthEffectiveHint.setVisibility(View.VISIBLE);
				}
            }
        } catch (Throwable ignored) {}
    }

    @Nullable
    private String resolveCurrentModelCachedSafeMaxTokensSource() {
        try {
            if (!SPManager.isReady()) return null;
            SPManager sp = SPManager.getInstance();
            LanguageModel p = sp.getLanguageModel();
            String sm = sp.getSubModel(p);
            return sp.getCachedSafeMaxTokensSource(p, sm);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private Integer resolveCurrentModelCachedSafeMaxTokens() {
        try {
            if (!SPManager.isReady()) return null;
            SPManager sp = SPManager.getInstance();
            LanguageModel p = sp.getLanguageModel();
            String sm = sp.getSubModel(p);
            Integer cap = sp.getCachedSafeMaxTokens(p, sm);
            if (cap != null && cap > 0) return cap;
            Integer lb = null;
            try { lb = sp.getCachedSafeMaxTokensLowerBound(p, sm); } catch (Throwable ignored) {}
            return (lb != null && lb > 0) ? lb : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void maybeShowOutputLengthDialogCapHint(int chosenTokens, boolean isCustomSelection) {
        try {
            Integer cap = resolveCurrentModelCachedSafeMaxTokens();
            if (cap == null || cap <= 0 || chosenTokens <= 0 || chosenTokens <= cap) return;
            String msg = isCustomSelection
                    ? ("当前子模型已学习到最大输出约 " + cap + " tokens；自定义值不会自动切换，请手动调整。")
                    : ("当前子模型已学习到最大输出约 " + cap + " tokens；你选择的档位可能会失败或在发送时自动回落。");
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    @NonNull
    private String getOutputCapAutoLearnModeLabel(int mode) {
        switch (mode) {
            case SPManager.OUTPUT_CAP_AUTO_LEARN_OFF:
                return "关闭";
            case SPManager.OUTPUT_CAP_AUTO_LEARN_SUCCESS_AND_FAIL:
                return "成功+失败都学习";
            case SPManager.OUTPUT_CAP_AUTO_LEARN_FAIL_ONLY:
            default:
                return "仅失败时学习（默认）";
        }
    }

    @NonNull
    private String getOutputCapManualProtectOverrideLabel(@Nullable Boolean override) {
        if (override == null) return "继承全局默认";
        return Boolean.TRUE.equals(override) ? "开启（仅当前模型）" : "关闭（仅当前模型）";
    }

    private void showOutputCapAutoLearningStrategyDialog(@Nullable LanguageModel provider,
                                                         @Nullable String subModel,
                                                         @Nullable Runnable onChanged) {
        if (!isAdded() || !SPManager.isReady()) return;
        final Context ctx = requireContext();
        final SPManager sp = SPManager.getInstance();

        final int currentMode = sp.getOutputCapAutoLearnMode();
        final boolean currentSyncPreset = sp.getOutputCapAutoSyncPresetEnabled();
        final boolean currentChatOnly = sp.getOutputCapAutoLearnChatOnlyEnabled();
        final boolean currentProtectDefault = sp.getOutputCapManualProtectDefaultEnabled();
        final boolean hasModel = provider != null && !TextUtils.isEmpty(subModel);
        final Boolean currentOverride = hasModel ? sp.getOutputCapManualProtectOverride(provider, subModel) : null;

        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = new TextView(ctx);
        intro.setText("自动学习策略会在真实对话请求中根据成功/失败结果记录输出上限能力；不会额外发起单独测试请求。\n建议默认：仅失败时学习 + 手动结果保护开启。");
        intro.setTextSize(13f);
        try { intro.setTextColor(com.google.android.material.color.MaterialColors.getColor(intro, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
        root.addView(intro);

        LinearLayout modeCard = new LinearLayout(ctx);
        modeCard.setOrientation(LinearLayout.VERTICAL);
        int cardPad = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        modeCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        try { modeCard.setBackgroundResource(R.drawable.bg_dialog_rounded_stroke); } catch (Throwable ignored) {}
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.topMargin = (int) (10 * ctx.getResources().getDisplayMetrics().density);
        root.addView(modeCard, modeLp);

        TextView modeTitle = new TextView(ctx);
        modeTitle.setText("自动学习模式");
        modeTitle.setTextSize(15f);
        try { modeTitle.setTypeface(modeTitle.getTypeface(), android.graphics.Typeface.BOLD); } catch (Throwable ignored) {}
        modeCard.addView(modeTitle);

        final android.widget.RadioGroup rgMode = new android.widget.RadioGroup(ctx);
        rgMode.setOrientation(android.widget.RadioGroup.VERTICAL);
        final int idModeOff = View.generateViewId();
        final int idModeFail = View.generateViewId();
        final int idModeBoth = View.generateViewId();
        RadioButton rbFail = new RadioButton(ctx);
        rbFail.setId(idModeFail);
        rbFail.setText("仅失败时学习（默认，最稳）");
        rgMode.addView(rbFail);
        RadioButton rbBoth = new RadioButton(ctx);
        rbBoth.setId(idModeBoth);
        rbBoth.setText("成功+失败都学习（成功时记录可用下限）");
        rgMode.addView(rbBoth);
        RadioButton rbOff = new RadioButton(ctx);
        rbOff.setId(idModeOff);
        rbOff.setText("关闭自动学习");
        rgMode.addView(rbOff);
        switch (currentMode) {
            case SPManager.OUTPUT_CAP_AUTO_LEARN_OFF: rgMode.check(idModeOff); break;
            case SPManager.OUTPUT_CAP_AUTO_LEARN_SUCCESS_AND_FAIL: rgMode.check(idModeBoth); break;
            case SPManager.OUTPUT_CAP_AUTO_LEARN_FAIL_ONLY:
            default: rgMode.check(idModeFail); break;
        }
        modeCard.addView(rgMode);

        TextView modeDesc = new TextView(ctx);
        modeDesc.setText("当前：" + getOutputCapAutoLearnModeLabel(currentMode));
        modeDesc.setTextSize(12f);
        try { modeDesc.setTextColor(com.google.android.material.color.MaterialColors.getColor(modeDesc, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
        modeCard.addView(modeDesc);

        LinearLayout toggleCard = new LinearLayout(ctx);
        toggleCard.setOrientation(LinearLayout.VERTICAL);
        toggleCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        try { toggleCard.setBackgroundResource(R.drawable.bg_dialog_rounded_stroke); } catch (Throwable ignored) {}
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = (int) (10 * ctx.getResources().getDisplayMetrics().density);
        root.addView(toggleCard, tLp);

        TextView tgTitle = new TextView(ctx);
        tgTitle.setText("策略开关");
        tgTitle.setTextSize(15f);
        try { tgTitle.setTypeface(tgTitle.getTypeface(), android.graphics.Typeface.BOLD); } catch (Throwable ignored) {}
        toggleCard.addView(tgTitle);

        java.util.function.BiFunction<String, String, LinearLayout> makeSwitchRow = (title, desc) -> {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout textBox = new LinearLayout(ctx);
            textBox.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(14f);
            TextView d = new TextView(ctx);
            d.setText(desc);
            d.setTextSize(11f);
            try { d.setTextColor(com.google.android.material.color.MaterialColors.getColor(d, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            textBox.addView(t);
            textBox.addView(d);
            row.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        };

        final SwitchMaterial swSyncPreset = new SwitchMaterial(ctx);
        swSyncPreset.setChecked(currentSyncPreset);
        LinearLayout rowSync = makeSwitchRow.apply("自动同步输出长度档位", "学习到更安全上限后，自动把主界面输出长度切到合适档位");
        rowSync.addView(swSyncPreset);
        toggleCard.addView(rowSync);

        final SwitchMaterial swChatOnly = new SwitchMaterial(ctx);
        swChatOnly.setChecked(currentChatOnly);
        LinearLayout rowChatOnly = makeSwitchRow.apply("仅对聊天模型启用", "embedding/tts/whisper 等非聊天模型不参与自动学习");
        rowChatOnly.addView(swChatOnly);
        LinearLayout.LayoutParams rowLp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp2.topMargin = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        toggleCard.addView(rowChatOnly, rowLp2);

        final SwitchMaterial swProtectDefault = new SwitchMaterial(ctx);
        swProtectDefault.setChecked(currentProtectDefault);
        LinearLayout rowProtect = makeSwitchRow.apply("手动结果保护（全局默认）", "开启后：手动测试结果优先；自动学习只记录观测，不覆盖手动结果");
        rowProtect.addView(swProtectDefault);
        LinearLayout.LayoutParams rowLp3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp3.topMargin = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        toggleCard.addView(rowProtect, rowLp3);

        final android.widget.RadioGroup rgOverride;
        final int[] overrideIds = new int[] {-1, -1, -1}; // inherit/on/off
        if (hasModel) {
            LinearLayout modelCard = new LinearLayout(ctx);
            modelCard.setOrientation(LinearLayout.VERTICAL);
            modelCard.setPadding(cardPad, cardPad, cardPad, cardPad);
            try { modelCard.setBackgroundResource(R.drawable.bg_dialog_rounded_stroke); } catch (Throwable ignored) {}
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mLp.topMargin = (int) (10 * ctx.getResources().getDisplayMetrics().density);
            root.addView(modelCard, mLp);

            TextView mt = new TextView(ctx);
            mt.setText("当前模型：" + String.valueOf(subModel));
            mt.setTextSize(14f);
            try { mt.setTypeface(mt.getTypeface(), android.graphics.Typeface.BOLD); } catch (Throwable ignored) {}
            modelCard.addView(mt);

            TextView ms = new TextView(ctx);
            ms.setText("手动结果保护覆盖：" + getOutputCapManualProtectOverrideLabel(currentOverride));
            ms.setTextSize(12f);
            try { ms.setTextColor(com.google.android.material.color.MaterialColors.getColor(ms, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
            modelCard.addView(ms);

            rgOverride = new android.widget.RadioGroup(ctx);
            rgOverride.setOrientation(android.widget.RadioGroup.VERTICAL);
            overrideIds[0] = View.generateViewId();
            overrideIds[1] = View.generateViewId();
            overrideIds[2] = View.generateViewId();
            RadioButton rbInherit = new RadioButton(ctx);
            rbInherit.setId(overrideIds[0]);
            rbInherit.setText("继承全局默认");
            rgOverride.addView(rbInherit);
            RadioButton rbOn = new RadioButton(ctx);
            rbOn.setId(overrideIds[1]);
            rbOn.setText("强制开启（当前模型）");
            rgOverride.addView(rbOn);
            RadioButton rbOff2 = new RadioButton(ctx);
            rbOff2.setId(overrideIds[2]);
            rbOff2.setText("强制关闭（当前模型）");
            rgOverride.addView(rbOff2);
            if (currentOverride == null) rgOverride.check(overrideIds[0]);
            else if (Boolean.TRUE.equals(currentOverride)) rgOverride.check(overrideIds[1]);
            else rgOverride.check(overrideIds[2]);
            modelCard.addView(rgOverride);
        } else {
            rgOverride = null;
        }

        new MaterialAlertDialogBuilder(ctx)
                .setTitle("输出上限自动学习策略")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        int newMode;
                        int checked = rgMode.getCheckedRadioButtonId();
                        if (checked == idModeOff) newMode = SPManager.OUTPUT_CAP_AUTO_LEARN_OFF;
                        else if (checked == idModeBoth) newMode = SPManager.OUTPUT_CAP_AUTO_LEARN_SUCCESS_AND_FAIL;
                        else newMode = SPManager.OUTPUT_CAP_AUTO_LEARN_FAIL_ONLY;

                        sp.setOutputCapAutoLearnMode(newMode);
                        sp.setOutputCapAutoSyncPresetEnabled(swSyncPreset.isChecked());
                        sp.setOutputCapAutoLearnChatOnlyEnabled(swChatOnly.isChecked());
                        sp.setOutputCapManualProtectDefaultEnabled(swProtectDefault.isChecked());

                        if (hasModel && rgOverride != null) {
                            int cid = rgOverride.getCheckedRadioButtonId();
                            Boolean ov = null;
                            if (cid == overrideIds[1]) ov = Boolean.TRUE;
                            else if (cid == overrideIds[2]) ov = Boolean.FALSE;
                            sp.setOutputCapManualProtectOverride(provider, subModel, ov);
                        }

                        try { refreshOutputLengthRowUi(); } catch (Throwable ignored) {}
                        try { sendConfigBroadcast(); } catch (Throwable ignored) {}
                        if (onChanged != null) {
                            try { onChanged.run(); } catch (Throwable ignored) {}
                        }

                        String msg = "已保存：" + getOutputCapAutoLearnModeLabel(newMode)
                                + " · 同步档位" + (swSyncPreset.isChecked() ? "开" : "关")
                                + " · 聊天模型限定" + (swChatOnly.isChecked() ? "开" : "关")
                                + " · 手动结果保护默认" + (swProtectDefault.isChecked() ? "开" : "关");
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {}
                })
                .show();
    }

    private void showOutputLengthCacheManagementMenu() {
        if (!isAdded() || !SPManager.isReady()) return;
        final SPManager sp = SPManager.getInstance();
        final LanguageModel provider;
        final String sub;
        try {
            provider = sp.getLanguageModel();
            sub = sp.getSubModel(provider);
        } catch (Throwable t) {
            return;
        }

        final Context ctx = requireContext();
        final java.util.ArrayList<android.util.Pair<String, String>> rows = new java.util.ArrayList<>();
        rows.add(new android.util.Pair<>("查看当前子模型输出上限缓存", "查看主缓存、自动观测、保护状态与更新时间"));
        rows.add(new android.util.Pair<>("清除当前子模型输出上限缓存", "仅清除当前模型缓存；默认不改输出长度档位，也不清保护设置"));
        rows.add(new android.util.Pair<>("清除全部输出上限缓存（危险）", "清除所有模型的输出上限与自动观测缓存；默认保留保护设置"));
        rows.add(new android.util.Pair<>("查看最近错误详情（打开能力测试）", "用于排查 max_tokens 失败原因，并可直接进入能力测试面板"));
        rows.add(new android.util.Pair<>("自动学习策略配置", "设置学习模式、自动同步档位、聊天模型限定与保护默认值"));
        rows.add(new android.util.Pair<>("重置输出长度档位（仅档位，不清缓存）", "将当前“输出长度”回到默认档位，不影响已学习缓存"));

        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        try { scroll.setFillViewport(true); } catch (Throwable ignored) {}
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * ctx.getResources().getDisplayMetrics().density);
        container.setPadding(pad, (int) (10 * ctx.getResources().getDisplayMetrics().density), pad, (int) (4 * ctx.getResources().getDisplayMetrics().density));
        scroll.addView(container, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < rows.size(); i++) {
            final int idx = i;
            android.util.Pair<String, String> row = rows.get(i);
            View item = buildOutputCapMenuItemRow(ctx, row.first, row.second, idx == 2);
            item.setOnClickListener(v -> {
                try {
                    androidx.appcompat.app.AlertDialog parent = (androidx.appcompat.app.AlertDialog) v.getTag();
                    if (parent != null && parent.isShowing()) parent.dismiss();
                } catch (Throwable ignored) {}
                Runnable action = () -> {
                    try {
                        switch (idx) {
                            case 0:
                                showCurrentModelOutputCapCacheInfo(provider, sub);
                                break;
                            case 1:
                                confirmClearCurrentModelOutputCapCache(provider, sub);
                                break;
                            case 2:
                                confirmClearAllOutputCapCache();
                                break;
                            case 3:
                                showModelCapabilityTestDialog(provider, sub, null);
                                break;
                            case 4:
                                showOutputCapAutoLearningStrategyDialog(provider, sub, null);
                                break;
                            case 5:
                                resetOutputLengthPresetOnly();
                                break;
                            default:
                                break;
                        }
                    } catch (Throwable ignored) {}
                };
                try {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(action);
                } catch (Throwable ignored) {
                    action.run();
                }
            });
            container.addView(item, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < rows.size() - 1) {
                View div = new View(ctx);
                try {
                    div.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(div, com.google.android.material.R.attr.colorOutlineVariant));
                    div.setAlpha(0.45f);
                } catch (Throwable ignored) {}
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (ctx.getResources().getDisplayMetrics().density)));
                dlp.leftMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
                dlp.rightMargin = (int) (2 * ctx.getResources().getDisplayMetrics().density);
                container.addView(div, dlp);
            }
        }

        final androidx.appcompat.app.AlertDialog dlg =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                        .setTitle("输出上限缓存管理")
                        .setView(scroll)
                        .setNegativeButton("取消", null)
                        .create();

        dlg.setOnShowListener(di -> {
            try {
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child != null && child.isClickable()) child.setTag(dlg);
                }
            } catch (Throwable ignored) {}
        });

        dlg.show();

        try {
            final android.view.Window w = dlg.getWindow();
            final android.view.View decor = (w != null) ? w.getDecorView() : null;
            if (decor != null) {
                decor.setAlpha(0f);
                decor.setScaleX(0.96f);
                decor.setScaleY(0.96f);
                decor.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(140)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }
        } catch (Throwable ignored) {}
    }

    private View buildOutputCapMenuItemRow(@NonNull Context ctx, @NonNull String title, @NonNull String desc, boolean danger) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
                try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                row.setBackgroundResource(tv.resourceId);
            }
        } catch (Throwable ignored) {}
        int ph = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        int pv = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        row.setPadding(ph, pv, ph, pv);

        TextView t1 = new TextView(ctx);
        t1.setText(title);
        t1.setTextSize(16f);
        try { t1.setTypeface(t1.getTypeface(), android.graphics.Typeface.BOLD); } catch (Throwable ignored) {}
        try {
            if (danger) {
                t1.setTextColor(0xFFB3261E); // fallback danger color (Material-like red)
            } else {
                android.util.TypedValue tv2 = new android.util.TypedValue();
                if (ctx.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv2, true)) {
                    if (tv2.resourceId != 0) {
                        t1.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, tv2.resourceId));
                    } else {
                        t1.setTextColor(tv2.data);
                    }
                }
            }
        } catch (Throwable ignored) {}
        row.addView(t1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t2 = new TextView(ctx);
        t2.setText(desc);
        t2.setTextSize(12f);
        t2.setMaxLines(2);
        t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        try {
            android.util.TypedValue tv3 = new android.util.TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv3, true)) {
                if (tv3.resourceId != 0) t2.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, tv3.resourceId));
                else t2.setTextColor(tv3.data);
            }
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = (int) (4 * ctx.getResources().getDisplayMetrics().density);
        row.addView(t2, lp2);
        return row;
    }

    private void showCurrentModelOutputCapCacheInfo(@Nullable LanguageModel provider, @Nullable String subModel) {
        try {
            if (!SPManager.isReady()) return;
            SPManager sp = SPManager.getInstance();
            Integer exact = sp.getCachedSafeMaxTokens(provider, subModel);
            Integer lb = sp.getCachedSafeMaxTokensLowerBound(provider, subModel);
            String src = sp.getCachedSafeMaxTokensSource(provider, subModel);
            Long at = sp.getCachedSafeMaxTokensUpdatedAt(provider, subModel);
            String err = sp.getCachedSafeMaxTokensLastError(provider, subModel);
            Integer hardCap = null;
            String hardSrc = null;
            long hardAt = 0L;
            try { hardCap = sp.getCachedHardMaxTokens(provider, subModel); } catch (Throwable ignored) {}
            try { hardSrc = sp.getCachedHardMaxTokensSource(provider, subModel); } catch (Throwable ignored) {}
            try { hardAt = sp.getCachedHardMaxTokensUpdatedAt(provider, subModel); } catch (Throwable ignored) {}
            Integer autoObs = null;
            Integer autoObsLb = null;
            String autoObsSrc = null;
            Long autoObsAt = null;
            boolean manualProtectEffective = false;
            try { autoObs = sp.getAutoObservedSafeMaxTokens(provider, subModel); } catch (Throwable ignored) {}
            try { autoObsLb = sp.getAutoObservedSafeMaxTokensLowerBound(provider, subModel); } catch (Throwable ignored) {}
            try { autoObsSrc = sp.getAutoObservedSafeMaxTokensSource(provider, subModel); } catch (Throwable ignored) {}
            try { autoObsAt = sp.getAutoObservedSafeMaxTokensUpdatedAt(provider, subModel); } catch (Throwable ignored) {}
            try { manualProtectEffective = sp.isOutputCapManualProtectEnabled(provider, subModel); } catch (Throwable ignored) {}
            StringBuilder sb = new StringBuilder();
            sb.append("模型：").append(TextUtils.isEmpty(subModel) ? "未设置" : subModel).append("\n");
            sb.append("精确上限：").append(exact == null || exact <= 0 ? "无" : String.valueOf(exact)).append("\n");
            sb.append("安全下限：").append(lb == null || lb <= 0 ? "无" : String.valueOf(lb)).append("\n");
            sb.append("来源：").append(TextUtils.isEmpty(src) ? "未知" : getOutputCapSourceLabel(src)).append("\n");
            sb.append("时间：").append(at == null || at <= 0 ? "未知" : formatRelativeAgeShort(at)).append("\n");
            if (hardCap != null && hardCap > 0) {
                sb.append("接口硬上限：≤").append(hardCap);
                if (!TextUtils.isEmpty(hardSrc)) sb.append(" · ").append(formatOutputCapSourceLabel(hardSrc));
                if (hardAt > 0) sb.append(" · ").append(formatLearnedTimeShort(hardAt));
                sb.append("\n");
            }
            if (!TextUtils.isEmpty(err)) sb.append("最近错误：").append(err).append("\n");
            sb.append("手动结果保护：").append(manualProtectEffective ? "开启" : "关闭").append("\n");
            if ((autoObs != null && autoObs > 0) || (autoObsLb != null && autoObsLb > 0)) {
                sb.append("自动观测（不覆写手动）：");
                if (autoObs != null && autoObs > 0) sb.append("精确≈").append(autoObs);
                if (autoObsLb != null && autoObsLb > 0) {
                    if (autoObs != null && autoObs > 0) sb.append(" / ");
                    sb.append("下限≥").append(autoObsLb);
                }
                if (!TextUtils.isEmpty(autoObsSrc)) sb.append(" · ").append(formatOutputCapSourceLabel(autoObsSrc));
                if (autoObsAt != null && autoObsAt > 0) sb.append(" · ").append(formatLearnedTimeShort(autoObsAt));
                sb.append("\n");
            }
            int current = sp.getMaxTokensLimit();
            boolean isCustom = false;
            int selSource = SPManager.MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED;
            try { isCustom = sp.getMaxTokensIsCustom(); } catch (Throwable ignored) {}
            try { selSource = sp.getMaxTokensSelectionSource(); } catch (Throwable ignored) {}
            sb.append("当前档位：").append(current).append(isCustom ? "（自定义）" : "（固定）");
            sb.append(" / ").append(selSource == SPManager.MAX_TOKENS_SELECTION_SOURCE_USER_FIXED ? "用户固定" : "自动映射");
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("当前子模型输出上限缓存")
                    .setMessage(sb.toString())
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    private void confirmClearCurrentModelOutputCapCache(@Nullable LanguageModel provider, @Nullable String subModel) {
        if (!isAdded() || !SPManager.isReady()) return;
        String modelName = TextUtils.isEmpty(subModel) ? "当前子模型" : subModel;
        final Context ctx = requireContext();
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * ctx.getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, (int) (8 * ctx.getResources().getDisplayMetrics().density), pad, 0);

        TextView msg = new TextView(ctx);
        msg.setText("确认清除「" + modelName + "」的输出上限缓存？\n不会修改当前输出长度档位；默认不清保护设置。");
        msg.setTextSize(14f);
        try { msg.setTextColor(com.google.android.material.color.MaterialColors.getColor(msg, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
        wrap.addView(msg);

        androidx.appcompat.widget.AppCompatCheckBox cb = new androidx.appcompat.widget.AppCompatCheckBox(ctx);
        cb.setText("同时清除该模型“保护手动结果”设置（可选）");
        cb.setChecked(false);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        wrap.addView(cb, cbLp);

        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("清除当前模型缓存")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (d, w) -> {
                    try {
                        SPManager sp = SPManager.getInstance();
                        sp.clearCachedSafeMaxTokens(provider, subModel);
                        try { sp.clearAutoObservedSafeMaxTokens(provider, subModel); } catch (Throwable ignored2) {}
                        boolean clearProtect = false;
                        try { clearProtect = cb.isChecked(); } catch (Throwable ignored3) {}
                        if (clearProtect) {
                            try { sp.setOutputCapManualProtectOverride(provider, subModel, null); } catch (Throwable ignored4) {}
                        }
                        String toast = "已清除当前子模型输出上限缓存" + (clearProtect ? "（含保护设置）" : "");
                        android.widget.Toast.makeText(ctx, toast, android.widget.Toast.LENGTH_SHORT).show();
                        refreshOutputLengthRowUi();
                        try { sendConfigBroadcast(); } catch (Throwable ignored5) {}
                    } catch (Throwable ignored) {}
                })
                .show();
    }

    private void confirmClearAllOutputCapCache() {
        if (!isAdded() || !SPManager.isReady()) return;
        final Context ctx = requireContext();
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * ctx.getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, (int) (8 * ctx.getResources().getDisplayMetrics().density), pad, 0);

        TextView msg = new TextView(ctx);
        msg.setText("此操作会清除所有子模型的输出上限缓存（含来源/时间/错误摘要）。\n不会修改当前输出长度档位；默认保留保护设置。\n建议仅在缓存明显混乱时使用。");
        msg.setTextSize(14f);
        try { msg.setTextColor(com.google.android.material.color.MaterialColors.getColor(msg, com.google.android.material.R.attr.colorOnSurfaceVariant)); } catch (Throwable ignored) {}
        wrap.addView(msg);

        androidx.appcompat.widget.AppCompatCheckBox cb = new androidx.appcompat.widget.AppCompatCheckBox(ctx);
        cb.setText("同时清除所有模型的“保护手动结果”设置（可选）");
        cb.setChecked(false);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        wrap.addView(cb, cbLp);

        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("清除全部输出上限缓存")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("全部清除", (d, w) -> {
                    try {
                        SPManager sp = SPManager.getInstance();
                        sp.clearAllCachedSafeMaxTokens();
                        boolean clearProtect = false;
                        try { clearProtect = cb.isChecked(); } catch (Throwable ignored1) {}
                        if (clearProtect) {
                            try { clearKnownModelsOutputCapProtectOverrides(); } catch (Throwable ignored2) {}
                        }
                        android.widget.Toast.makeText(ctx,
                                "已清空全部子模型输出上限缓存" + (clearProtect ? "（含已知模型保护设置）" : ""),
                                android.widget.Toast.LENGTH_SHORT).show();
                        refreshOutputLengthRowUi();
                        try { sendConfigBroadcast(); } catch (Throwable ignored3) {}
                    } catch (Throwable ignored) {}
                })
                .show();
    }

    private void clearKnownModelsOutputCapProtectOverrides() {
        if (!SPManager.isReady()) return;
        try {
            SPManager sp = SPManager.getInstance();
            for (LanguageModel lm : LanguageModel.values()) {
                if (lm == null) continue;
                java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
                try {
                    java.util.List<String> c1 = sp.getCachedModels(lm);
                    if (c1 != null) names.addAll(c1);
                } catch (Throwable ignored) {}
                try {
                    java.util.List<String> c2 = sp.getCustomSubModels(lm);
                    if (c2 != null) names.addAll(c2);
                } catch (Throwable ignored) {}
                try {
                    String cur = sp.getSubModel(lm);
                    if (!TextUtils.isEmpty(cur)) names.add(cur);
                } catch (Throwable ignored) {}
                for (String n : names) {
                    if (TextUtils.isEmpty(n)) continue;
                    try { sp.setOutputCapManualProtectOverride(lm, n, null); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private void resetOutputLengthPresetOnly() {
        if (!isAdded() || !SPManager.isReady()) return;
        try {
            SPManager sp = SPManager.getInstance();
            sp.setMaxTokensLimit(64);
            sp.setMaxTokensIsCustom(false);
            sp.setMaxTokensSelectionSource(SPManager.MAX_TOKENS_SELECTION_SOURCE_USER_FIXED);
            android.widget.Toast.makeText(requireContext(), "已重置输出长度档位为 64（不清缓存）", android.widget.Toast.LENGTH_SHORT).show();
            refreshOutputLengthRowUi();
        } catch (Throwable ignored) {}
    }

    private void maybeShowOutputLengthManualSelectionHint(int selectedTokens, boolean isCustomSelection) {
        try {
            Integer cap = resolveCurrentModelCachedSafeMaxTokens();
            if (cap == null || cap <= 0 || selectedTokens <= 0 || selectedTokens <= cap) return;
            String msg = isCustomSelection
                    ? ("已保存为自定义输出长度（" + selectedTokens + "）。当前模型缓存上限约 " + cap + "，发送失败时请手动调低。")
                    : ("当前模型缓存上限约 " + cap + "，该档位可能失败；不会自动升档，只会在需要时降档。" );
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    /**
     * 根据当前子模型的已缓存安全上限执行自动同步（切到对应固定档位；必要时灰掉更高档位）。
     * @param showToast 是否在发生切档时提示用户。
     */
    private void refreshOutputLengthAutoGuardForCurrentModel(boolean showToast) {
        try {
            if (!SPManager.isReady()) return;
            SPManager sp = SPManager.getInstance();
            int cur = sp.getMaxTokensLimit();
            boolean curCustom = false;
            try { curCustom = sp.getMaxTokensIsCustom(); } catch (Throwable ignored) {}

            LanguageModel provider = sp.getLanguageModel();
            String sub = sp.getSubModel(provider);
            Integer cap = sp.getCachedSafeMaxTokens(provider, sub);
            if (cap == null || cap <= 0) {
                try { cap = sp.getCachedSafeMaxTokensLowerBound(provider, sub); } catch (Throwable ignored) {}
            }
            if (cap == null || cap <= 0) {
                try {
                    Logger.log("OUTLEN_SYNC", "ui guard skip (no cache) provider=" + (provider == null ? "null" : provider.name())
                            + ", subModel=" + String.valueOf(sub));
                } catch (Throwable ignoredLog) {}
                refreshOutputLengthRowUi();
                return;
            }

            int after = sp.syncOutputLengthToLearnedCap(provider, sub, cap, "LabFragment.refreshOutputLengthAutoGuardForCurrentModel");
            boolean afterCustom = false;
            try { afterCustom = sp.getMaxTokensIsCustom(); } catch (Throwable ignored) {}

            if (showToast && isAdded() && (after != cur || afterCustom != curCustom)) {
                try {
                    String msg = "已按子模型已学习上限自动切换输出长度：" + cur + (curCustom ? "（自定义）" : "") + " → " + after;
                    if (afterCustom) msg += "（自定义）";
                    msg += "（上限≈" + cap + "）";
                    android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            }
            try {
                String conflictNotice = sp.consumeOutputCapConflictNotice();
                if (isAdded() && !TextUtils.isEmpty(conflictNotice)) {
                    android.widget.Toast.makeText(requireContext(), conflictNotice, android.widget.Toast.LENGTH_LONG).show();
                }
            } catch (Throwable ignored) {}

            refreshOutputLengthRowUi();
        } catch (Throwable ignored) {
            try { refreshOutputLengthRowUi(); } catch (Throwable ignored2) {}
        }
    }

    private void appendCapHintIfNeeded(@NonNull StringBuilder sb, int tokens, @Nullable Integer cachedCap) {
        try {
            if (cachedCap != null && cachedCap > 0 && tokens > cachedCap) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("⚠ 当前子模型缓存上限约 ").append(cachedCap).append(" tokens；自定义模式不会自动切换，请手动调整。") ;
            }
        } catch (Throwable ignored) {}
    }



    private void applyAmoledIfNeeded() {
        boolean isDarkMode = tn.eluea.kgpt.ui.main.BottomSheetHelper.isDarkMode(requireContext());
        boolean isAmoled = tn.eluea.kgpt.ui.main.BottomSheetHelper.isAmoledMode(requireContext());

        if (isDarkMode && isAmoled) {
            View root = getView() != null ? getView().findViewById(R.id.root_layout) : null;
            if (root != null) {
                root.setBackgroundColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.background_amoled));
            }
        }
    }

    // ===== Overlay permission helpers (for TopStatusBanner / Token Burner) =====

    @Nullable
    private static String getDefaultImePackage(@NonNull Context ctx) {
        try {
            String defaultIme = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (defaultIme == null || defaultIme.isEmpty()) return null;
            // Usually "com.package/.ImeService"
            int slash = defaultIme.indexOf('/');
            if (slash > 0) return defaultIme.substring(0, slash);
            return defaultIme;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static String getAppLabel(@NonNull Context ctx, @NonNull String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label == null ? null : label.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isOverlayAllowedForPackage(@NonNull Context ctx, @NonNull String pkg) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;

            // If checking self, use official API.
            if (pkg.equals(ctx.getPackageName())) {
                return Settings.canDrawOverlays(ctx.getApplicationContext());
            }

            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            int uid = ai.uid;

            AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;

            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg);
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable ignored) {
            return false;
        }
    }

}
