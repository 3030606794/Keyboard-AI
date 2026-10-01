package tn.eluea.kgpt.core.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.util.Random;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.Toast;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;
import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.listener.GenerativeAIListener;
import tn.eluea.kgpt.llm.GenerativeAIController;
import tn.eluea.kgpt.ui.IMSController;
import tn.eluea.kgpt.ui.UiInteractor;
import tn.eluea.kgpt.core.ui.TopStatusBanner;
import tn.eluea.kgpt.receiver.GeneratingStatusBridgeReceiver;
import tn.eluea.kgpt.util.AiDiagnostics;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.clipboard.AIClipboardStore;
import tn.eluea.kgpt.util.Logger;

public class AiResponseManager implements GenerativeAIListener {

    // Strip leading role labels that some models echo (e.g., "Assistant:").
    private static final Pattern LEADING_ASSISTANT_LABEL =
            Pattern.compile("(?is)^\\s*(assistant|\\u52a9\\u624b)\\s*[:：]\\s*");
    private boolean checkedLeadingAssistantLabelThisRequest = true;
    private final StringBuilder leadingAssistantLabelBuffer = new StringBuilder();

    private final GenerativeAIController mAIController;
    private final Runnable onAiPrepareCallback;
    private boolean justPrepared = true;

    // Per-request snapshots (so settings don't change mid-response)
    private boolean generatingContentEnabledSnapshot = true;
    private String generatingContentSnapshot = null; // actual placeholder inserted (after resolving defaults)
    private String suffixAfterCursorSnapshot = null; // inserted after cursor (cursor stays before it)
    private boolean vibrateOnReplySnapshot = false;
    private boolean replyStartedThisRequest = false;
    private boolean suffixInsertedThisRequest = false;

    private boolean toastEnabledSnapshot = true;
    // v4+: input box role marker mode (plain text only for input markers)
    private boolean inputRoleMarkerEnabledSnapshot = false;
    // v6: per-position switches (after-cursor / before-cursor)
    private boolean inputRoleMarkerApplyToSuffixSnapshot = true;
    // v5: optionally also apply role name into prefix (thinking placeholder)
    private boolean inputRoleMarkerApplyToPrefixSnapshot = false;
    private volatile String pendingUiRoleId = null;
    private volatile String pendingUiRoleName = null;
    private String currentRequestRoleNameSnapshot = null;
    private boolean dynamicPrefixEnabledSnapshot = true;
    private boolean tokenBurnerEnabledSnapshot = true;
    private boolean standaloneFloatEnabledSnapshot = false; // KGPT app process standalone overlay strip
    private int completeSoundSnapshot = SPManager.GEN_SOUND_NONE;

    private int vibrateIntensityPercentSnapshot = 65;
    private int vibrateFrequencyPercentSnapshot = 70;
    private long minVibrateIntervalMsSnapshot = MIN_VIBRATE_INTERVAL_MS;
    private int vibAmplitudeSnapshot = VibrationEffect.DEFAULT_AMPLITUDE;

    private int markerStyleSnapshot = SPManager.GEN_MARKER_STYLE_PLAIN;
    private int markerColorSnapshot = SPManager.GEN_MARKER_COLOR_BLUE;
    private int markerAnimLengthSnapshot = 6;
    private int markerAnimSpeedPercentSnapshot = 70;

    private String rawSuffixKeywordSnapshot = "";
    private String currentReplyMarkerAfterCursor = "";
    private boolean replyToastShownThisRequest = false;

    // =============================
    // v7: User interrupt (soft pause/buffer + hard cancel)
    // =============================
    // When true, we immediately stop writing any further text into the input box.
    // If interruptBufferEnabledThisRequest is also true, we keep buffering chunks into
    // interruptBuffer and store it into AIClipboardStore at completion/cancel.
    private volatile boolean interruptUiSuppressedThisRequest = false;
    private volatile boolean interruptBufferEnabledThisRequest = false;
    private volatile long interruptRequestedAtMs = 0L;
    private volatile int interruptModeSnapshot = -1;
    private final StringBuilder interruptBuffer = new StringBuilder();

    // ===== Persistent "replying" toast (best-effort; Android toasts cannot be truly indefinite) =====
    private final Handler toastHandler = new Handler(Looper.getMainLooper());
    private boolean replyToastLoopRunning = false;
    private Toast replyToastInstance = null;
    private final Runnable replyToastLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!replyToastLoopRunning) return;
            if (!generatingContentEnabledSnapshot || !toastEnabledSnapshot) return;
            showOrUpdateReplyToast();
            // Re-show slightly faster than LENGTH_LONG so it *looks* persistent.
            toastHandler.postDelayed(this, 2200L);
        }
    };
    private boolean completionSoundPlayedThisRequest = false;

    // Rainbow animation state (per request)
    private String[] rainbowBaseBlocks = null;
    private int rainbowAnimStep = 0;
    private int rainbowAnimTickCounter = 0;

    // Haptics: vibrate in sync with the streaming renderer until output finishes.
    private long lastVibrateAtMs = 0;
    private static final long MIN_VIBRATE_INTERVAL_MS = 25;

    private static final String[] RAINBOW_PALETTE = new String[]{"🟥","🟧","🟨","🟩","🟦","🟪"};

    /** Map marker animation speed percent (0..100) to toast shader sweep duration (ms). */
    private static long mapToastSweepDurationMs(int percent) {
        int p = clampInt(percent, 0, 100);
        double x = p / 100.0;
        // Quadratic curve: slow has more resolution.
        double y = x * x;
        final double min = 450.0;   // fastest sweep
        final double max = 3500.0;  // slowest sweep
        return Math.round(max - (max - min) * y);
    }

    // State for text actions (replace mode)
    private boolean isTextActionMode = false;
    private String pendingSelectedText = null;

    // When streaming output is disabled, we buffer chunks and commit once at completion.
    private final StringBuilder bufferedResponse = new StringBuilder();

    // Streaming output renderer: throttle commits so the user can actually see a stream/typing effect.
    // This also provides a "simulated" streaming effect when the backend returns the full text at once.
    private final Handler streamHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder streamPending = new StringBuilder();
    // Network/prefetch buffer. When enabled, we decouple network chunk timing from UI rendering.
    // We first prefetch enough text, then render from the buffer using a stable pacing clock.
    private final StringBuilder streamPrefetch = new StringBuilder();
    private boolean streamScheduled = false;
    private boolean streamCompleted = false;
    private boolean streamingEnabledSnapshot = false;

    // Prefetch mode (per request). Enabled for NON-LINEAR pacing to make rhythm stable across
    // network chunk bursts/pauses.
    private boolean prefetchEnabledSnapshot = false;
    private boolean prefetchRenderStarted = false;

    // Prefetch tuning (chars) snapshot per request.
    private int prefetchStartCharsSnapshot = 120;
    private int prefetchLowWatermarkSnapshot = 80;
    private int prefetchTopUpTargetSnapshot = 260;

    // Prefetch tuning defaults (chars). These are intentionally conservative.
    private static final int DEFAULT_PREFETCH_START_CHARS = 120;
    private static final int DEFAULT_PREFETCH_LOW_WATERMARK = 80;
    private static final int DEFAULT_PREFETCH_TOPUP_TARGET = 260;

    // Snapshot per-request for consistent output pacing.
    private int streamingSpeedPercentSnapshot = 60; // 0..100, 100 = fastest
    private boolean streamingSpeedAutoSnapshot = true;
    private int streamingGranularitySnapshot = SPManager.STREAM_GRANULARITY_CHARS;

    // Speed algorithm snapshot (Linear vs Non-linear)
    private int streamingSpeedAlgorithmSnapshot = SPManager.STREAM_SPEED_ALGO_LINEAR;

    // Non-linear snapshots
    private int streamingNonLinearModelSnapshot = SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK;
    private int nonLinearSigmaMsSnapshot = 0;
    private double nonLinearPauseMultiplierSnapshot = 2.0;

    // Per-model parameter snapshots (defaults are defined in SPManager getters)
    private int nlLcTBaseMs = 90;

    private int nlExpTMaxMs = 220;
    private int nlExpTMinMs = 28;
    private double nlExpLambda = 0.045;

    private int nlSineTBaseMs = 85;
    private int nlSineAMs = 35;
    private double nlSineOmega = 0.9;
    private double nlSinePhi = 0.0;

    private int nlDampTBaseMs = 90;
    private int nlDampAMs = 85;
    private double nlDampOmega = 1.1;
    private double nlDampZeta = 0.05;
    private double nlDampPhi = 0.0;

    private int nlSquareTBaseMs = 95;
    private int nlSquareAMs = 70;
    private double nlSquareOmega = 0.7;

    private int nlMarkovMuMs = 80;
    private double nlMarkovRho = 0.9;
    private int nlMarkovSigmaMs = 25;

    private int nlMarkovTMinMs = 30;
    private int nlMarkovTMaxMs = 450;
    private double nlMarkovPThinkProb = 0.02;

    // v16+ geek models (Perlin / PID / Logistic / Retro)
    private double nlPerlinBaseSpeed = 1.25;
    private int nlPerlinOctaves = 3;
    private double nlPerlinFrequency = 1.10;

    private double nlPidTargetSpeed = 1.35;
    private double nlPidP = 0.22;
    private double nlPidD = 0.12;

    private int nlLogiInitialBurstMs = 24;
    private double nlLogiDecayHalfLife = 40.0;
    private double nlLogiRecovery = 0.35;

    private int nlRetroKeySpeedMs = 38;
    private int nlRetroCrDelayMs = 240;
    private int nlRetroPuncDragMs = 80;

    // Runtime state for non-linear algorithms (per request)
    private int nonLinearTickIndex = 0;
    private double markovPrevMs = Double.NaN;
    private boolean punctuationPauseNextTick = false;

    // The last committed character (end of previous tick), used by some models (Retro/Logistic).
    private char nonLinearLastChar = '\0';

    // PID runtime state
    private boolean pidInit = false;
    private double pidPrevErr = 0.0;
    private double pidCurMs = 0.0;

    // Logistic runtime state
    private double logisticFatigue = 0.0;

    // Perlin-ish runtime state
    private double perlinPhase = 0.0;
    private double[] perlinPhaseOffsets = null;

    private final Random nonLinearRng = new Random();

    private final Random markerRng = new Random();

    // For text rainbow marker style
    private int[] rainbowTextPaletteColors = null;
    private int rainbowTextPaletteLenSnapshot = -1;
    private int rainbowTextPaletteColorModeSnapshot = -1;

    // =============================
    // v9+ Dynamic status prefix & token burner (streaming banner)
    // =============================
    private boolean burnerActiveThisRequest = false;
    private int burnerMaxTokensSnapshot = 4096;
    private long lastBannerUiUpdateAtMs = 0L;

    // Timing stats for fallback Toast UX (IME-safe)
    private long requestPreparedAtMs = 0L;
    private long firstVisibleOutputAtMs = 0L;
    private boolean thinkingElapsedToastShownThisRequest = false;
    private int finalCompletionTokenEstimateSnapshot = 0;
    private int finalCompletionCharCountSnapshot = 0;

    // Live placeholder / marker diagnostics UX (plain text only)
    private String currentGeneratingPrefixBeforeCursor = "";
    private long lastThinkingPlaceholderUpdateAtMs = 0L;
    private int lastReplyMarkerLiveTokenEstimate = -1;
    private boolean generatingTypingSoundEnabledSnapshot = false;
    private int generatingTypingSoundStyleSnapshot = SPManager.GEN_TYPING_SOUND_STYLE_CLICK;
    private boolean thinkingElapsedEnabledSnapshot = true;
    private boolean replyTokenCounterEnabledSnapshot = false;
    private boolean replyTokenCounterAutoRemoveSnapshot = true;
    // Delay (ms) before auto-removing the final token counter marker after completion.
    private long replyTokenCounterAutoRemoveDelayMsSnapshot = 0L;
    private long lastTypingSoundAtMs = 0L;

private ToneGenerator toneGenerator;
private final Runnable thinkingPlaceholderTickerRunnable = new Runnable() {
    @Override
    public void run() {
        try {
            updateThinkingPlaceholderElapsedUiOnce();
        } catch (Throwable ignored) {
        }
        if (justPrepared && generatingContentEnabledSnapshot && !replyStartedThisRequest) {
            try {
                toastHandler.postDelayed(this, 250L);
            } catch (Throwable ignored) {
            }
        }
    }
};
    private static final long TYPING_SOUND_MIN_INTERVAL_MS = 30L;

    // State sniffing
    private boolean modelSuggestsReasoning = false;
    private boolean inThinkBlock = false;
    private int codeFenceTripletCount = 0; // number of "```" occurrences seen so far
    private int backtickStreak = 0;        // helps detect "```" across chunk boundaries
    private boolean geekCodeMode = false;  // true when code fences are unclosed

    // Stats for auto pacing.
    private int streamReceivedTotalChars = 0;
    private int streamCommittedTotalChars = 0;

    // Default pacing (used if preferences are unavailable)
    private static final long DEFAULT_TICK_MS = 110;
    private static final int DEFAULT_CHARS_PER_TICK = 12;

    // Keep the last scheduled tick delay so non-linear models advance exactly once per tick.
    // (Non-linear delays are stateful; computing delay multiple times per tick will distort rhythm.)
    private long lastTickDelayMs = DEFAULT_TICK_MS;

    private static int clampInt(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /**
     * Map speed percent (0..100) to a base tick interval in ms.
     * Non-linear mapping: lower speeds have more resolution.
     */
    private static long mapPercentToTickMs(int percent) {
        int p = clampInt(percent, 0, 100);
        double x = p / 100.0;
        // Non-linear curve (quadratic). 0->0, 1->1.
        double y = x * x;
        final double min = 18.0;   // fastest
        final double max = 420.0;  // slowest (allow much slower typing)
        return Math.round(max - (max - min) * y);
    }

    /** Map speed percent (0..100) to a base chars-per-tick. */
    private static int mapPercentToCharsPerTick(int percent) {
        int p = clampInt(percent, 0, 100);
        double x = p / 100.0;
        // Slightly more aggressive curve for characters.
        double y = Math.pow(x, 1.7);
        final double min = 2.0;
        final double max = 38.0;
        return (int) Math.round(min + (max - min) * y);
    }

    /**
     * Map vibration frequency percent (0..100) to a minimum interval (ms) between pulses.
     * Higher percent => more frequent vibrations.
     */
    private static long mapVibrateFrequencyPercentToIntervalMs(int percent) {
        int p = clampInt(percent, 0, 100);
        double x = p / 100.0;
        // Quadratic curve so low values have more resolution.
        double y = x * x;
        final double max = 240.0; // least frequent
        final double min = 18.0;  // most frequent
        return Math.round(max - (max - min) * y);
    }

    /**
     * Map vibration strength percent (0..100) to amplitude (1..255).
     */
    private static int mapVibrateIntensityPercentToAmplitude(int percent) {
        int p = clampInt(percent, 0, 100);
        if (p <= 0) return 0;
        // Slightly non-linear so low values are still noticeable.
        double x = p / 100.0;
        double y = Math.pow(x, 1.4);
        int amp = (int) Math.round(1.0 + (255.0 - 1.0) * y);
        if (amp < 1) amp = 1;
        if (amp > 255) amp = 255;
        return amp;
    }

    private static int safeParseInt(String s, int defVal) {
        try {
            if (s == null) return defVal;
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return defVal;
        }
    }

    /**
     * Compute effective pacing based on speed percent + auto mode.
     * Auto mode makes long answers/backlogs render faster and short answers slower.
     */
    private long computeLinearTickDelayMs() {
        long base = mapPercentToTickMs(streamingSpeedPercentSnapshot);
        if (!streamingSpeedAutoSnapshot) return base;

        // Estimate total length (as we may still be streaming).
        int estTotal = Math.max(streamReceivedTotalChars, streamCommittedTotalChars + streamPending.length());

        // Length factor: longer => faster (approaches ~1.9x)
        double lenFactor = 1.0 + 0.9 * (1.0 - Math.exp(-estTotal / 700.0));
        // Backlog factor: if backend bursts chunks, catch up faster (up to ~1.7x)
        double backlogFactor = 1.0 + Math.min(0.7, streamPending.length() / 700.0);

        double speedUp = lenFactor * backlogFactor;
        long ms = (long) Math.round(base / speedUp);
        return clampInt((int) ms, 12, (int) base);
    }

    /**
     * Compute a tick delay using the selected non-linear model.
     * This is a pure "delay" model (ms) - characters-per-tick is still controlled elsewhere.
     */
    private long computeNonLinearTickDelayMs() {
        int n = nonLinearTickIndex++;

        double ms;
        switch (streamingNonLinearModelSnapshot) {
            case SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT: {
                ms = nlLcTBaseMs;
                break;
            }
            case SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY: {
                // T(n) = Tmin + (Tmax - Tmin) * exp(-lambda * n)
                double tmax = Math.max(nlExpTMaxMs, nlExpTMinMs);
                double tmin = Math.min(nlExpTMaxMs, nlExpTMinMs);
                ms = tmin + (tmax - tmin) * Math.exp(-nlExpLambda * n);
                break;
            }
            case SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER: {
                // T(n) = Tbase + A * sin(omega * n + phi)
                ms = nlSineTBaseMs + nlSineAMs * Math.sin(nlSineOmega * n + nlSinePhi);
                break;
            }
            case SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR: {
                // T(n) = Tbase + A * exp(-zeta*n) * cos(omega*n + phi)
                ms = nlDampTBaseMs + nlDampAMs * Math.exp(-nlDampZeta * n) * Math.cos(nlDampOmega * n + nlDampPhi);
                break;
            }
            case SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST: {
                // T(n) = Tbase + A * sgn(sin(omega*n))
                double s = Math.sin(nlSquareOmega * n);
                double sign = (s >= 0.0) ? 1.0 : -1.0;
                ms = nlSquareTBaseMs + nlSquareAMs * sign;
                break;
            }
            case SPManager.STREAM_NL_MODEL_PERLIN_NOISE: {
                // "Perlin-ish" smooth noise: multi-sine octaves with stable random phase offsets.
                // BaseDelay is mapped from speed. Higher speed => lower delay.
                double baseDelay = 95.0 / Math.max(0.10, nlPerlinBaseSpeed);
                double ampDelay = baseDelay * 0.35;

                int oct = nlPerlinOctaves;
                if (oct < 1) oct = 1;
                if (oct > 8) oct = 8;

                if (perlinPhaseOffsets == null || perlinPhaseOffsets.length < 8) {
                    perlinPhaseOffsets = new double[8];
                    for (int i = 0; i < perlinPhaseOffsets.length; i++) {
                        perlinPhaseOffsets[i] = nonLinearRng.nextDouble() * (2.0 * Math.PI);
                    }
                }

                // UI semantics: bigger frequency => slower evolution.
                double step = 0.20 / Math.max(0.05, nlPerlinFrequency);
                perlinPhase += step;

                double noise = 0.0;
                double a = 1.0;
                double sum = 0.0;
                for (int o = 0; o < oct; o++) {
                    double w = (1 << o);
                    noise += a * Math.sin(perlinPhase * w + perlinPhaseOffsets[o]);
                    sum += a;
                    a *= 0.5;
                }
                if (sum > 1e-6) noise /= sum;
                ms = baseDelay + ampDelay * noise;
                break;
            }
            case SPManager.STREAM_NL_MODEL_PID_CONTROLLER: {
                // PD controller that converges towards a target delay.
                double targetDelay = 95.0 / Math.max(0.10, nlPidTargetSpeed);

                if (!pidInit) {
                    pidCurMs = Math.max(12.0, targetDelay * 1.6);
                    pidPrevErr = 0.0;
                    pidInit = true;
                }

                double err = targetDelay - pidCurMs;
                double out = nlPidP * err + nlPidD * (err - pidPrevErr);
                pidCurMs = pidCurMs + out;
                pidPrevErr = err;

                ms = pidCurMs;
                break;
            }
            case SPManager.STREAM_NL_MODEL_LOGISTIC_FATIGUE: {
                // Logistic "fatigue" curve that slows down over time, with recovery on pauses.
                double halfLife = nlLogiDecayHalfLife;
                if (halfLife < 1.0) halfLife = 1.0;
                double decay = Math.log(2.0) / halfLife;

                // Fatigue increases towards 1.0.
                logisticFatigue = logisticFatigue + (1.0 - logisticFatigue) * decay;

                double t0 = nlLogiInitialBurstMs;
                double t1 = Math.min(1800.0, t0 * 6.0 + 90.0);
                double sCurve = 1.0 / (1.0 + Math.exp(-10.0 * (logisticFatigue - 0.35)));
                ms = t0 + (t1 - t0) * sCurve;

                // Recovery: apply after computing this tick's delay so it affects next ticks.
                if (nlLogiRecovery > 0.0 && isPunctuationBoundary(nonLinearLastChar)) {
                    logisticFatigue = Math.max(0.0, logisticFatigue - nlLogiRecovery * 0.45);
                }
                break;
            }
            case SPManager.STREAM_NL_MODEL_RETRO_TYPEWRITER: {
                // Mechanical typewriter: base key speed + CR delay on newline + drag on punctuation.
                ms = nlRetroKeySpeedMs;

                if (nonLinearLastChar == '\n' || nonLinearLastChar == '\r') {
                    ms += nlRetroCrDelayMs;
                } else if (isPunctuationBoundary(nonLinearLastChar)) {
                    ms += nlRetroPuncDragMs;
                }
                break;
            }
            case SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK:
            default: {
                // AR(1): T(n) = mu + rho*(T(n-1)-mu) + sigma*epsilon
                double mu = nlMarkovMuMs;
                double rho = nlMarkovRho;
                double sigma = nlMarkovSigmaMs;

                double tmin = nlMarkovTMinMs;
                double tmax = nlMarkovTMaxMs;
                if (tmax < tmin) {
                    double tmp = tmax;
                    tmax = tmin;
                    tmin = tmp;
                }

                if (Double.isNaN(markovPrevMs)) markovPrevMs = mu;
                double eps = nonLinearRng.nextGaussian();
                double next = mu + rho * (markovPrevMs - mu) + sigma * eps;

                // Clamp physical bounds.
                if (next < tmin) next = tmin;
                if (next > tmax) next = tmax;

                markovPrevMs = next;
                ms = next;

                // Occasional "thinking" stall.
                if (nlMarkovPThinkProb > 0.0 && nonLinearRng.nextDouble() < nlMarkovPThinkProb) {
                    ms = tmax;
                }
                break;
            }
        }

        // Clamp to sane range.
        if (ms < 8.0) ms = 8.0;
        if (ms > 1800.0) ms = 1800.0;

        return Math.round(ms);
    }

    /**
     * Unified tick delay used by the renderer.
     * - Linear: existing speed percent + auto pacing
     * - Non-linear: physics/random model + optional punctuation pause + Gaussian noise
     */
    private long computeTickDelayMs() {
        long ms;

        if (streamingSpeedAlgorithmSnapshot == SPManager.STREAM_SPEED_ALGO_NONLINEAR) {
            ms = computeNonLinearTickDelayMs();

            // Pause after punctuation boundaries (applied to NEXT tick).
            if (punctuationPauseNextTick && nonLinearPauseMultiplierSnapshot > 1.0) {
                ms = (long) Math.round(ms * nonLinearPauseMultiplierSnapshot);
            }

            // Gaussian noise (ms)
            if (nonLinearSigmaMsSnapshot > 0) {
                double noise = nonLinearRng.nextGaussian() * nonLinearSigmaMsSnapshot;
                ms = (long) Math.round(ms + noise);
            }

        } else {
            ms = computeLinearTickDelayMs();
        }

        // Consume punctuation flag.
        punctuationPauseNextTick = false;

        // Final clamp.
        if (ms < 12) ms = 12;
        if (ms > 2000) ms = 2000;
        return ms;
    }


    private int computeCharsThisTick(long tickMs) {
        // In non-linear mode, keep slices smaller so the rhythm (pause/jitter) is actually visible.
        // If we scale chars-per-tick with tickMs here, the perceived speed becomes almost constant
        // and the non-linear delay curve feels like it "does nothing".
        if (streamingSpeedAlgorithmSnapshot == SPManager.STREAM_SPEED_ALGO_NONLINEAR) {
            int base = mapPercentToCharsPerTick(streamingSpeedPercentSnapshot); // 2..38
            int n = (int) Math.round(base / 4.0); // ~1..10
            return clampInt(n, 1, 12);
        }

        int base = mapPercentToCharsPerTick(streamingSpeedPercentSnapshot);
        if (!streamingSpeedAutoSnapshot) {
            return clampInt(base, 1, 80);
        }
        int estTotal = Math.max(streamReceivedTotalChars, streamCommittedTotalChars + streamPending.length());

        // Longer => increase chars per tick (up to ~2x)
        double lenFactor = 0.85 + 1.15 * (1.0 - Math.exp(-estTotal / 500.0));
        // If we have a big pending backlog, increase per-tick slice so it doesn't take forever.
        double backlogFactor = 1.0 + Math.min(1.0, streamPending.length() / 500.0);

        // Keep overall feel stable by scaling with tick interval.
        // If tickMs is bigger (slow), commit a bit more per tick; if smaller, commit less.
        double tickNorm = tickMs / (double) DEFAULT_TICK_MS;

        int n = (int) Math.round(base * lenFactor * backlogFactor * Math.max(0.65, Math.min(1.6, tickNorm)));
        return clampInt(n, 1, 120);
    }

    private static boolean isPunctuationBoundary(char ch) {
        // Common English + Chinese punctuation, plus line breaks.
        switch (ch) {
            case '\n':
            case '\r':
            case '.':
            case '!':
            case '?':
            case ',':
            case ';':
            case ':':
            case '。':
            case '！':
            case '？':
            case '，':
            case '；':
            case '：':
            case '、':
            case ')':
            case '）':
            case ']':
            case '】':
            case '"':
            case '”':
            case '’':
                return true;
            default:
                return false;
        }
    }

    /**
     * Snap the slice length to a boundary so the stream feels more "natural".
     * - WORDS: prefer whitespace boundary
     * - PUNCT: prefer punctuation boundary; fallback to whitespace
     */
    private static int snapToBoundary(CharSequence pending, int max, int granularity) {
        int len = pending != null ? pending.length() : 0;
        int limit = Math.min(Math.max(1, max), len);
        if (len <= 1 || limit <= 1) return limit;

        int minAccept = Math.max(1, limit / 3); // avoid tiny output pieces

        if (granularity == SPManager.STREAM_GRANULARITY_WORDS) {
            for (int i = limit - 1; i >= 0; i--) {
                char ch = pending.charAt(i);
                if (Character.isWhitespace(ch)) {
                    int snapped = i + 1;
                    return snapped >= minAccept ? snapped : limit;
                }
            }
            return limit;
        }

        if (granularity == SPManager.STREAM_GRANULARITY_PUNCT) {
            for (int i = limit - 1; i >= 0; i--) {
                if (isPunctuationBoundary(pending.charAt(i))) {
                    int snapped = i + 1;
                    return snapped >= minAccept ? snapped : limit;
                }
            }
            // Fallback to whitespace if no punctuation boundary.
            for (int i = limit - 1; i >= 0; i--) {
                if (Character.isWhitespace(pending.charAt(i))) {
                    int snapped = i + 1;
                    return snapped >= minAccept ? snapped : limit;
                }
            }
            return limit;
        }

        return limit;
    }

    private final Runnable streamTick = new Runnable() {
        @Override
        public void run() {
            streamScheduled = false;
            if (!streamingEnabledSnapshot) {
                streamPending.setLength(0);
                streamCompleted = false;
                return;
            }

            // Prefetch mode: keep the render buffer topped-up so output pacing is less sensitive
            // to backend chunk timing.
            if (prefetchEnabledSnapshot && prefetchRenderStarted) {
                if (streamPending.length() < prefetchLowWatermarkSnapshot) {
                    topUpFromPrefetchIfNeeded(prefetchTopUpTargetSnapshot);
                }
            }

            if (streamPending.length() == 0) {
                // Nothing to flush.
                if (streamCompleted) {
                    finishStreamingIfNeeded();
                    return;
                }

                // In prefetch mode, keep the pacing clock alive so we don't "burst" immediately
                // when the next network chunk arrives. We do NOT advance the non-linear model
                // state here (no delay recomputation) to keep rhythm consistent with commits.
                if (prefetchEnabledSnapshot && prefetchRenderStarted) {
                    IMSController.getInstance().startInputLock();
                    scheduleStreamTickDelayed(lastTickDelayMs);
                }
                return;
            }

            // Clear placeholder on first real output.
            IMSController.getInstance().endInputLock();
            clearGeneratingContent();
            // Switch to "replying" state only when we are about to show real output.
            if (!replyStartedThisRequest) {
                replyStartedThisRequest = true;
                markFirstVisibleOutputIfNeeded();
                ensureReplySuffixInsertedIfNeeded();
                showReplyingToastIfNeeded();
            }
            IMSController.getInstance().flush();

            // Take a small slice to commit (avoid splitting surrogate pairs).
            // NOTE: Use the last scheduled delay for chars-per-tick sizing.
            // The delay for the *next* tick will be computed after we commit this piece.
            long tickMsForSizing = lastTickDelayMs;
            int charsThisTick = computeCharsThisTick(tickMsForSizing);
            int n = Math.min(charsThisTick, streamPending.length());

            // Apply granularity (chars/words/punctuation)
            try {
                if (streamingGranularitySnapshot != SPManager.STREAM_GRANULARITY_CHARS) {
                    n = snapToBoundary(streamPending, n, streamingGranularitySnapshot);
                }
            } catch (Throwable ignored) {}

            if (n > 0 && n < streamPending.length()) {
                char c = streamPending.charAt(n - 1);
                if (Character.isHighSurrogate(c)) {
                    n = Math.max(0, n - 1);
                }
            }
            if (n <= 0) {
                n = Math.min(1, streamPending.length());
            }
            String piece = streamPending.substring(0, n);
            streamPending.delete(0, n);

            IMSController.getInstance().commit(piece);
            streamCommittedTotalChars += piece.length();
            maybePlayTypingSoundOnOutputTick(piece.length());
            refreshReplyMarkerLiveStatsIfNeeded();

            // If we are inside an unclosed code block, lock the cursor marker to geek green.
            setGeekCodeMode((codeFenceTripletCount & 1) == 1);

            // Keep the banner burner responsive to committed output (throttled).
            maybeUpdateBannerUi(/*force*/false);

            // Haptics: vibrate in sync with the renderer while outputting.
            if (replyStartedThisRequest) {
                vibrateForStreamTick(tickMsForSizing, piece.length());
            }


            // Marker animation (after-cursor rainbow) in sync with the renderer ticks.
            if (replyStartedThisRequest) {
                maybeAdvanceRainbowMarkerOnTick();
            }

            // Non-linear pause after punctuation: if the piece ends with punctuation, delay the NEXT tick.
            try {
                if (piece != null && !piece.isEmpty()) {
                    char last = piece.charAt(piece.length() - 1);
                    punctuationPauseNextTick = isPunctuationBoundary(last);
                    nonLinearLastChar = last;
                }
            } catch (Throwable ignored) {}

            // If more to output, keep locking to prevent self-trigger recursion.
            if (streamPending.length() > 0 || !streamCompleted) {
                IMSController.getInstance().startInputLock();
                // Compute delay ONCE for the next tick (stateful for non-linear models).
                long nextDelay;
                try {
                    nextDelay = computeTickDelayMs();
                } catch (Throwable ignored) {
                    nextDelay = DEFAULT_TICK_MS;
                }
                lastTickDelayMs = nextDelay;
                scheduleStreamTickDelayed(nextDelay);
            } else {
                // All done.
                finishStreamingIfNeeded();
            }
        }
    };

    /** Schedule an immediate tick (first output should appear as soon as we have data). */
    private void scheduleStreamTickNow() {
        if (streamScheduled) return;
        streamScheduled = true;
        streamHandler.post(streamTick);
    }

    /** Schedule a paced tick to make the output feel like streaming/typing. */
    private void scheduleStreamTickDelayed(long delayMs) {
        if (streamScheduled) return;
        streamScheduled = true;
        long ms = delayMs;
        if (ms < 0) ms = 0;
        streamHandler.postDelayed(streamTick, ms);
    }

    private void cancelStreamTicks() {
        streamHandler.removeCallbacks(streamTick);
        streamScheduled = false;
    }

    /**
     * Move up to {@code targetPending} chars from the prefetch buffer into the render buffer.
     * This helps keep output pacing stable even if the backend delivers chunks unevenly.
     */
    private void topUpFromPrefetchIfNeeded(int targetPending) {
        if (!prefetchEnabledSnapshot) return;
        if (streamPrefetch.length() == 0) return;
        int need = targetPending - streamPending.length();
        if (need <= 0) return;
        int take = Math.min(need, streamPrefetch.length());
        if (take <= 0) return;
        try {
            streamPending.append(streamPrefetch, 0, take);
            streamPrefetch.delete(0, take);
        } catch (Throwable ignored) {
            // Fallback: substring copy
            try {
                String s = streamPrefetch.substring(0, take);
                streamPending.append(s);
                streamPrefetch.delete(0, take);
            } catch (Throwable ignored2) {}
        }
    }

    private void finishStreamingIfNeeded() {
        finalCompletionCharCountSnapshot = Math.max(0, streamCommittedTotalChars);
        finalCompletionTokenEstimateSnapshot = Math.max(0, estimateCompletionTokens());
        cancelStreamTicks();
        burnerActiveThisRequest = false;
        geekCodeMode = false;
        streamCompleted = false;
        streamPending.setLength(0);
        streamPrefetch.setLength(0);
        prefetchRenderStarted = false;
        prefetchEnabledSnapshot = false;

        // Remove trailing "AI replying" keyword after output finishes.
        removeReplySuffixIfPresent();
        // Stop "replying" toast loop and show completion status.
        stopReplyingToastLoop(/*showDoneToast*/true);
        showCompletionTokenToastIfNeeded(finalCompletionCharCountSnapshot);
        // Sound: play once after output completes.
        playCompletionSoundIfNeeded();
        replyStartedThisRequest = false;
        lastVibrateAtMs = 0;
        requestPreparedAtMs = 0L;
        firstVisibleOutputAtMs = 0L;
        thinkingElapsedToastShownThisRequest = false;
        finalCompletionTokenEstimateSnapshot = 0;
        finalCompletionCharCountSnapshot = 0;

        IMSController.getInstance().endInputLock();
        IMSController.getInstance().startNotifyInput();
        // Reset text action mode
        currentGeneratingPrefixBeforeCursor = "";
        requestPreparedAtMs = 0L;
        firstVisibleOutputAtMs = 0L;
        setTextActionMode(false, null);
    }

    // Use method to get string to support locale changes and resources
    private String getDefaultGeneratingContentString() {
        Context ctx = UiInteractor.getInstance().getContext();
        if (ctx != null) {
            try {
                return ctx.getString(R.string.generating_content);
            } catch (Exception e) {
                // Fallback if resource not found (e.g. running in Xposed context with wrong
                // Resources)
                return "<Generating Content...>";
            }
        }
        return "<Generating Content...>";
    }

    private void vibratePulseMs(long pulseMs) {
        if (!vibrateOnReplySnapshot) return;
        if (vibrateIntensityPercentSnapshot <= 0) return;
        long now = System.currentTimeMillis();
        long minGap = Math.max(12L, minVibrateIntervalMsSnapshot);
        if (now - lastVibrateAtMs < minGap) return;
        lastVibrateAtMs = now;
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx == null) return;
            Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null) return;

            long d = pulseMs;
            if (d < 6) d = 6;
            if (d > 70) d = 70;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int amp = vibAmplitudeSnapshot;
                if (amp <= 0) amp = VibrationEffect.DEFAULT_AMPLITUDE;
                if (amp > 255) amp = 255;
                VibrationEffect effect = VibrationEffect.createOneShot(d, amp);
                vib.vibrate(effect);
            } else {
                //noinspection deprecation
                vib.vibrate(d);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Emit a short vibration pulse that roughly matches the current streaming animation rhythm.
     * Called on every renderer tick that commits visible text.
     */
    private void vibrateForStreamTick(long tickMs, int committedChars) {
        if (!vibrateOnReplySnapshot) return;
        // Heuristic: slightly longer pulses on slower animation and/or larger chunks.
        double base = Math.max(8.0, Math.min(45.0, tickMs * 0.16));
        double byLen = Math.max(6.0, Math.min(45.0, committedChars * 1.6));
        long pulse = Math.round((base + byLen) / 2.0);
        vibratePulseMs(pulse);
    }

    private int dpToPx(Context ctx, int dp) {
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return (int) (dp * dm.density + 0.5f);
        } catch (Throwable ignored) {
            return dp * 2;
        }
    }

    private void toastTopSafe(final String message, final boolean isLong) {
        if (message == null) return;
        final String msg = message.trim();
        if (msg.isEmpty()) return;
        try {
            UiInteractor.getInstance().runOnUiThread(() -> {
                try {
                    Context ctx = UiInteractor.getInstance().getContext();
                    if (ctx == null) return;
                    Toast t = Toast.makeText(ctx.getApplicationContext(), msg,
                            isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
                    // Place near top like a status banner.
                    t.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dpToPx(ctx, 72));
                    t.show();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {
        }
    }

    private void toastShortSafe(final String message) {
        toastTopSafe(message, /*isLong*/false);
    }

    private boolean canUseTopBannerOverlayInCurrentContext() {
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx == null) return false;
            String pkg = "";
            try { pkg = String.valueOf(ctx.getPackageName()).toLowerCase(Locale.ROOT); } catch (Throwable ignored) {}
            // Many IME / overlay-host contexts are hostile to TYPE_APPLICATION_OVERLAY updates.
            if (pkg.contains("input") || pkg.contains("ime") || pkg.contains("baidu")) return false;
            return TopStatusBanner.getInstance().canUseOverlay(ctx);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String formatClockTime(long whenMs) {
        try {
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(whenMs));
        } catch (Throwable ignored) {
            return "--:--:--";
        }
    }

    private String formatDurationShort(long ms) {
        if (ms < 0) ms = 0;
        if (ms < 1000L) return ms + "ms";
        long sec = ms / 1000L;
        long rem = ms % 1000L;
        if (sec < 60L) {
            long d = rem / 100L;
            return sec + "." + d + "s";
        }
        long min = sec / 60L;
        long s = sec % 60L;
        return min + "m" + s + "s";
    }

    private void markFirstVisibleOutputIfNeeded() {
        if (firstVisibleOutputAtMs > 0L) return;
        stopThinkingPlaceholderTicker();
        firstVisibleOutputAtMs = System.currentTimeMillis();
        if (thinkingElapsedToastShownThisRequest) return;
        thinkingElapsedToastShownThisRequest = true;
        if (!generatingContentEnabledSnapshot || !toastEnabledSnapshot) return;
        long base = requestPreparedAtMs > 0L ? requestPreparedAtMs : firstVisibleOutputAtMs;
        long dt = Math.max(0L, firstVisibleOutputAtMs - base);
        toastShortSafe("🧠 正在输出（思考 " + formatDurationShort(dt) + "）");
    }

    private void showCompletionTokenToastIfNeeded(int finalCharsHint) {
        if (!generatingContentEnabledSnapshot || !toastEnabledSnapshot) return;
        int chars = finalCharsHint;
        if (chars < 0) chars = finalCompletionCharCountSnapshot;
        if (chars < 0) chars = 0;
        int tokens = finalCompletionTokenEstimateSnapshot;
        if (tokens <= 0) {
            tokens = chars > 0 ? (int) Math.round(chars / 1.5) : estimateCompletionTokens();
        }
        finalCompletionCharCountSnapshot = chars;
        finalCompletionTokenEstimateSnapshot = Math.max(0, tokens);

        StringBuilder sb = new StringBuilder();
        sb.append("✅ 输出完成");
        if (chars > 0) sb.append(" · ").append(chars).append("字");
        sb.append(" · ≈").append(Math.max(0, tokens)).append(" Token");
        if (requestPreparedAtMs > 0L && firstVisibleOutputAtMs > 0L) {
            sb.append(" · 思考").append(formatDurationShort(firstVisibleOutputAtMs - requestPreparedAtMs));
        }
        toastTopSafe(sb.toString(), false);
    }

    private String resolveThinkingToastText() {
        String s = generatingContentSnapshot;
        if (s != null && !s.trim().isEmpty()) return s.trim();
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null) return ctx.getString(R.string.ui_toast_ai_thinking);
        } catch (Throwable ignored) {}
        return "AI is thinking";
    }

    private String resolveReplyingToastText() {
        String s = rawSuffixKeywordSnapshot;
        if (s != null && !s.trim().isEmpty()) return s.trim();
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null) return ctx.getString(R.string.ui_toast_ai_replying);
        } catch (Throwable ignored) {}
        return "AI is replying";
    }

    private void showThinkingToastIfEnabled() {
        if (!generatingContentEnabledSnapshot) return;
        if (!toastEnabledSnapshot) return;

        String thinkingMsg = "🧠 正在思考 " + formatClockTime(requestPreparedAtMs > 0L ? requestPreparedAtMs : System.currentTimeMillis());
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null && canUseTopBannerOverlayInCurrentContext()) {
                // Preferred: stable persistent top banner (not limited by ROM Toast restrictions).
                TopStatusBanner.getInstance().show(ctx, resolveThinkingToastText());
                // Also show a one-shot timestamp toast as a reliable fallback cue in IME/ROM environments.
                toastShortSafe(thinkingMsg);
            } else {
                toastShortSafe(thinkingMsg);
            }
        } catch (Throwable ignored) {
            toastShortSafe(thinkingMsg);
        }
    }

    private void showReplyingToastIfNeeded() {
        if (!generatingContentEnabledSnapshot) return;
        if (!toastEnabledSnapshot) return;

        // Preferred: stable persistent top banner (not limited by Toast restrictions).
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null && canUseTopBannerOverlayInCurrentContext()) {
                TopStatusBanner.getInstance().show(ctx, resolveReplyingToastText());
                replyToastShownThisRequest = true;

                // Stop any fallback loop/toast instance if they were running.
                replyToastLoopRunning = false;
                toastHandler.removeCallbacks(replyToastLoopRunnable);
                if (replyToastInstance != null) {
                    try { replyToastInstance.cancel(); } catch (Throwable ignored) {}
                    replyToastInstance = null;
                }
                return;
            }
        } catch (Throwable ignored) {}

        // Fallback: show a top Toast periodically to keep it visible.
        if (replyToastShownThisRequest) return;
        replyToastShownThisRequest = true;

        replyToastLoopRunning = true;
        toastHandler.removeCallbacks(replyToastLoopRunnable);
        toastHandler.post(() -> {
            showOrUpdateReplyToast();
            toastHandler.postDelayed(replyToastLoopRunnable, 2200L);
        });
    }

    private void showOrUpdateReplyToast() {
        // Toast custom views are restricted on Android 11+ and many ROMs.
        // Keep this fallback simple and reliable: text-only top Toast.
        toastTopSafe(resolveReplyingToastText(), false);
    }

    private String resolveDoneToastText() {
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null) return ctx.getString(R.string.ui_toast_ai_done);
        } catch (Throwable ignored) {}
        return "已全部完成";
    }

    private void stopReplyingToastLoop(boolean showDoneToast) {
        // UI Cleanup: always hide any lingering banner visuals first.
        try { TopStatusBanner.getInstance().hide(); } catch (Throwable ignored) {}
        try { broadcastStandaloneGenHide(); } catch (Throwable ignored) {}

        replyToastLoopRunning = false;
        toastHandler.removeCallbacks(replyToastLoopRunnable);
        toastHandler.post(() -> {
            try {
                if (replyToastInstance != null) {
                    try { replyToastInstance.cancel(); } catch (Throwable ignored) {}
                    replyToastInstance = null;
                }
            } catch (Throwable ignored) {}

            // Prefer banner completion message when overlay is available.
            try {
                Context ctx = UiInteractor.getInstance().getContext();
                if (ctx != null && canUseTopBannerOverlayInCurrentContext()) {
                    if (showDoneToast && generatingContentEnabledSnapshot && toastEnabledSnapshot) {
                        TopStatusBanner.getInstance().showDone(ctx, resolveDoneToastText());
                        if (standaloneFloatEnabledSnapshot) broadcastStandaloneGenDone(resolveDoneToastText());
                    } else {
                        TopStatusBanner.getInstance().hide();
                        if (standaloneFloatEnabledSnapshot) broadcastStandaloneGenHide();
                    }
                    return;
                }
            } catch (Throwable ignored) {}

            if (showDoneToast && generatingContentEnabledSnapshot && toastEnabledSnapshot) {
                toastTopSafe(resolveDoneToastText(), /*isLong*/false);
                if (standaloneFloatEnabledSnapshot) broadcastStandaloneGenDone(resolveDoneToastText());
            } else {
                if (standaloneFloatEnabledSnapshot) broadcastStandaloneGenHide();
            }
        });
    }


private String formatThinkingElapsedForPlaceholder(long ms) {
    if (ms < 0L) ms = 0L;
    long sec = ms / 1000L;
    long d = (ms % 1000L) / 100L;
    if (sec < 60L) return sec + "." + d + "s";
    long min = sec / 60L;
    long s = sec % 60L;
    return min + "m" + s + "s";
}

private String buildThinkingPlaceholderWithElapsed(long nowMs) {
    String base = generatingContentSnapshot;
    if (base == null || base.isEmpty()) base = getDefaultGeneratingContentString();
    if (!thinkingElapsedEnabledSnapshot) return base;
    long start = requestPreparedAtMs > 0L ? requestPreparedAtMs : nowMs;
    long dt = Math.max(0L, nowMs - start);
    return base + " " + formatThinkingElapsedForPlaceholder(dt);
}

private void updateThinkingPlaceholderElapsedUiOnce() {
    if (!justPrepared) return;
    if (!generatingContentEnabledSnapshot) return;
    if (!thinkingElapsedEnabledSnapshot) return;
    if (replyStartedThisRequest) return;
    long now = System.currentTimeMillis();
    if (lastThinkingPlaceholderUpdateAtMs > 0L && (now - lastThinkingPlaceholderUpdateAtMs) < 200L) return;
    String oldText = currentGeneratingPrefixBeforeCursor;
    if (oldText == null || oldText.isEmpty()) return;
    String nextText = buildThinkingPlaceholderWithElapsed(now);
    if (nextText.equals(oldText)) return;
    boolean ok = false;
    try {
        IMSController ims = IMSController.getInstance();
        ims.flush();
        if (ims.deleteBeforeCursorIfMatches(oldText)) {
            ims.commit(nextText);
            ok = true;
        }
    } catch (Throwable ignored) {
    }
    if (ok) {
        currentGeneratingPrefixBeforeCursor = nextText;
        lastThinkingPlaceholderUpdateAtMs = now;
    }
}

private void startThinkingPlaceholderTicker() {
    stopThinkingPlaceholderTicker();
    if (!justPrepared) return;
    if (!generatingContentEnabledSnapshot) return;
    if (!thinkingElapsedEnabledSnapshot) return;
    if (replyStartedThisRequest) return;
    try {
        toastHandler.postDelayed(thinkingPlaceholderTickerRunnable, 250L);
    } catch (Throwable ignored) {
    }
}

private void stopThinkingPlaceholderTicker() {
    try {
        toastHandler.removeCallbacks(thinkingPlaceholderTickerRunnable);
    } catch (Throwable ignored) {
    }
}

private int estimateTokensFromChars(int charCount) {
    if (charCount <= 0) return 0;
    // Lightweight UI estimate only (not server-side billing tokens).
    // Chinese text tends to be denser in chars/token than English; use a blended heuristic.
    double approx = Math.ceil(charCount / 2.2d);
    if (approx < 1d) approx = 1d;
    if (approx > Integer.MAX_VALUE) return Integer.MAX_VALUE;
    return (int) approx;
}

private int estimateWordsFromChars(int charCount) {
    if (charCount <= 0) return 0;
    // UI-only rough estimate; mixed CJK/Latin safe fallback.
    double approx = Math.ceil(charCount / 3.2d);
    if (approx < 1d) approx = 1d;
    if (approx > Integer.MAX_VALUE) return Integer.MAX_VALUE;
    return (int) approx;
}

private String buildReplyTokenCounterText() {
    if (!replyTokenCounterEnabledSnapshot) return "";
    int chars = finalCompletionCharCountSnapshot > 0 ? finalCompletionCharCountSnapshot : Math.max(0, streamCommittedTotalChars);
    int tokens = finalCompletionTokenEstimateSnapshot > 0 ? finalCompletionTokenEstimateSnapshot : Math.max(0, estimateTokensFromChars(chars));
    int words = estimateWordsFromChars(chars);
    return "【" + tokens + "Token " + chars + "字 " + words + "词】";
}

private String buildLiveReplyMarkerKeyword() {
    String keyword = rawSuffixKeywordSnapshot;
    if (keyword == null || keyword.trim().isEmpty()) keyword = suffixAfterCursorSnapshot;
    if (keyword == null) keyword = "";
    return keyword.trim();
}

private String buildLiveReplyMarkerBody() {
    String keyword = buildLiveReplyMarkerKeyword();
    String tokenPart = buildReplyTokenCounterText();
    boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
    boolean hasToken = tokenPart != null && !tokenPart.isEmpty();
    if (hasKeyword && hasToken) return keyword + " " + tokenPart;
    if (hasKeyword) return keyword;
    if (hasToken) return tokenPart;
    return "";
}

private String buildReplyMarkerString() {
    return buildReplyMarkerString(rainbowAnimStep);
}

private CharSequence buildReplyMarkerCharSequence() {
    return buildReplyMarkerCharSequence(rainbowAnimStep);
}

private void ensureTypingToneReady() {
    if (toneGenerator != null) return;
    try {
        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35);
    } catch (Throwable ignored) {
        toneGenerator = null;
    }
}

private void releaseTypingTone() {
    try {
        if (toneGenerator != null) toneGenerator.release();
    } catch (Throwable ignored) {
    } finally {
        toneGenerator = null;
    }
}

    private void maybePlayTypingSoundOnOutputTick(int appendedChars) {
        if (!generatingTypingSoundEnabledSnapshot) return;
        if (appendedChars <= 0) return;
        long now = System.currentTimeMillis();
        if (lastTypingSoundAtMs > 0L && now - lastTypingSoundAtMs < TYPING_SOUND_MIN_INTERVAL_MS) return;
        lastTypingSoundAtMs = now;
        try {
            ensureTypingToneReady();
            if (toneGenerator != null) {
                int tone = ToneGenerator.TONE_PROP_ACK;
                int dur = 16;
                switch (generatingTypingSoundStyleSnapshot) {
                    case SPManager.GEN_TYPING_SOUND_STYLE_SOFT_TICK:
                        tone = ToneGenerator.TONE_PROP_BEEP; dur = 12; break;
                    case SPManager.GEN_TYPING_SOUND_STYLE_MECH_KEY:
                        tone = ToneGenerator.TONE_DTMF_5; dur = 18; break;
                    case SPManager.GEN_TYPING_SOUND_STYLE_PULSE:
                        tone = ToneGenerator.TONE_SUP_PIP; dur = 14; break;
                    case SPManager.GEN_TYPING_SOUND_STYLE_BEEP:
                        tone = ToneGenerator.TONE_DTMF_1; dur = 14; break;
                    case SPManager.GEN_TYPING_SOUND_STYLE_CLICK:
                    default:
                        tone = ToneGenerator.TONE_PROP_ACK; dur = 16; break;
                }
                toneGenerator.startTone(tone, dur);
            }
        } catch (Throwable ignored) {
        }
    }

    private void playCompletionSoundIfNeeded() {
        if (!generatingContentEnabledSnapshot) return;
        if (completionSoundPlayedThisRequest) return;
        completionSoundPlayedThisRequest = true;
        int type = completeSoundSnapshot;
        if (type == SPManager.GEN_SOUND_NONE) return;
        final Context ctx = UiInteractor.getInstance().getContext();
        if (ctx == null) return;

        UiInteractor.getInstance().runOnUiThread(() -> {
            try {
                if (type == SPManager.GEN_SOUND_SYSTEM_NOTIFICATION) {
                    Uri uri = null;
                    try { uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION); } catch (Throwable ignored) {}
                    if (uri == null) {
                        try { uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM); } catch (Throwable ignored) {}
                    }
                    if (uri == null) return;
                    Ringtone rt = RingtoneManager.getRingtone(ctx.getApplicationContext(), uri);
                    if (rt != null) rt.play();
                    return;
                }

                int tone = ToneGenerator.TONE_PROP_BEEP;
                int dur = 140;
                if (type == SPManager.GEN_SOUND_CLICK) {
                    tone = ToneGenerator.TONE_PROP_ACK;
                    dur = 90;
                }

                final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
                tg.startTone(tone, dur);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try { tg.release(); } catch (Throwable ignored) {}
                }, dur + 80L);
            } catch (Throwable ignored) {
            }
        });
    }

    private String pickColorBlockEmoji(int color) {
        switch (color) {
            case SPManager.GEN_MARKER_COLOR_RED: return "🟥";
            case SPManager.GEN_MARKER_COLOR_GREEN: return "🟩";
            case SPManager.GEN_MARKER_COLOR_YELLOW: return "🟨";
            case SPManager.GEN_MARKER_COLOR_PURPLE: return "🟪";
            case SPManager.GEN_MARKER_COLOR_BLUE: return "🟦";
            case SPManager.GEN_MARKER_COLOR_RANDOM: {
                try {
                    return RAINBOW_PALETTE[Math.abs(markerRng.nextInt()) % RAINBOW_PALETTE.length];
                } catch (Throwable ignored) {
                    return "🟦";
                }
            }
            default: return "🟦";
        }
    }

    private void initRainbowBaseBlocksIfNeeded() {
        if (rainbowBaseBlocks != null && rainbowBaseBlocks.length > 0) return;
        int len = markerAnimLengthSnapshot;
        if (len <= 0) len = 6;
        if (len > 30) len = 30;

        List<String> palette = new ArrayList<>(Arrays.asList(RAINBOW_PALETTE));
        if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_RANDOM) {
            Collections.shuffle(palette, markerRng);
        } else {
            String start = pickColorBlockEmoji(markerColorSnapshot);
            int idx = palette.indexOf(start);
            if (idx >= 0) {
                // rotate so the chosen color leads
                Collections.rotate(palette, -idx);
            }
        }

        String[] out = new String[len];
        int pos = 0;
        while (pos < len) {
            if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_RANDOM) {
                Collections.shuffle(palette, markerRng);
            }
            for (String c : palette) {
                if (pos >= len) break;
                out[pos++] = c;
            }
        }
        rainbowBaseBlocks = out;
        rainbowAnimStep = 0;
        rainbowAnimTickCounter = 0;
    }


private void initRainbowTextPaletteIfNeeded() {
    int paletteLen = markerAnimLengthSnapshot;
    if (paletteLen <= 0) paletteLen = 6;
    if (paletteLen < 3) paletteLen = 3;
    if (paletteLen > 10) paletteLen = 10;

    if (rainbowTextPaletteColors != null
            && rainbowTextPaletteLenSnapshot == paletteLen
            && rainbowTextPaletteColorModeSnapshot == markerColorSnapshot) {
        return;
    }

    // Base rainbow palette (10 colors)
    int[] base = new int[]{
            Color.parseColor("#FF3B30"), // red
            Color.parseColor("#FF9500"), // orange
            Color.parseColor("#FFCC00"), // yellow
            Color.parseColor("#34C759"), // green
            Color.parseColor("#00C7BE"), // teal
            Color.parseColor("#007AFF"), // blue
            Color.parseColor("#5856D6"), // indigo
            Color.parseColor("#AF52DE"), // purple
            Color.parseColor("#FF2D55"), // pink
            Color.parseColor("#8E8E93"), // gray (fallback)
    };

    int[] palette = new int[paletteLen];
    for (int i = 0; i < paletteLen; i++) {
        palette[i] = base[i % base.length];
    }

    // Apply color mode: fixed color tints, or random shuffle
    if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_RANDOM) {
        for (int i = paletteLen - 1; i > 0; i--) {
            int j = markerRng.nextInt(i + 1);
            int tmp = palette[i];
            palette[i] = palette[j];
            palette[j] = tmp;
        }
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_GREEN) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#34C759");
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_BLUE) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#007AFF");
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_YELLOW) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#FFCC00");
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_PURPLE) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#AF52DE");
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_RED) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#FF3B30");
    }

    rainbowTextPaletteColors = palette;
    rainbowTextPaletteLenSnapshot = paletteLen;
    rainbowTextPaletteColorModeSnapshot = markerColorSnapshot;
}

private CharSequence buildTextRainbowMarker(CharSequence plainText, int step) {
    if (plainText == null) return "";
    String s = plainText.toString();
    if (s.isEmpty()) return s;

    initRainbowTextPaletteIfNeeded();

    SpannableString ss = new SpannableString(s);
    int n = s.length();
    int paletteLen = (rainbowTextPaletteColors == null || rainbowTextPaletteColors.length == 0) ? 1 : rainbowTextPaletteColors.length;

    for (int i = 0; i < n; i++) {
        int color = rainbowTextPaletteColors[(i + step) % paletteLen];
        ss.setSpan(new ForegroundColorSpan(color), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return ss;
}

    private String buildRainbowBlocksString(int step) {
        initRainbowBaseBlocksIfNeeded();
        if (rainbowBaseBlocks == null || rainbowBaseBlocks.length == 0) return "";
        int len = rainbowBaseBlocks.length;
        int shift = step % len;
        if (shift < 0) shift += len;

        // Right shift (left-to-right feeling)
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int src = i - shift;
            if (src < 0) src += len;
            sb.append(rainbowBaseBlocks[src]);
        }
        return sb.toString();
    }

    private String buildReplyMarkerString(int step) {
        String body = buildLiveReplyMarkerBody();
        if (body == null) body = "";
        if (body.trim().isEmpty()) return "";

        if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_COLOR_TAG) {
            return pickColorBlockEmoji(markerColorSnapshot) + body; // B format: no space
        }
        if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM) {
            return buildRainbowBlocksString(step) + body; // B format: no space
        }
        // Plain
        return body;
    }


private CharSequence buildReplyMarkerCharSequence(int step) {
    String body = buildLiveReplyMarkerBody();
    if (body == null) body = "";
    if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) {
        return buildTextRainbowMarker(body, step);
    }
    return buildReplyMarkerString(step);
}

    private int computeRainbowTickSkip() {
        int p = clampInt(markerAnimSpeedPercentSnapshot, 0, 100);
        double x = p / 100.0;
        double y = Math.pow(x, 1.2);
        int skip = (int) Math.round(6.0 - 5.0 * y); // 0->6 ticks, 100->1 tick
        if (skip < 1) skip = 1;
        if (skip > 12) skip = 12;
        return skip;
    }

    /**
     * Update the rainbow marker after the cursor in sync with the streaming renderer ticks.
     */
    private void refreshReplyMarkerLiveStatsIfNeeded() {
        if (currentReplyMarkerAfterCursor == null || currentReplyMarkerAfterCursor.length() == 0) return;
        if (!replyStartedThisRequest) return;
        if (!replyTokenCounterEnabledSnapshot) return;
        int approxTokens = Math.max(0, estimateTokensFromChars(streamCommittedTotalChars));
        if (approxTokens == lastReplyMarkerLiveTokenEstimate) return;
        lastReplyMarkerLiveTokenEstimate = approxTokens;
        try {
            CharSequence next;
            if (geekCodeMode) {
                next = buildGeekGreenMarker(buildLiveReplyMarkerKeyword());
                if (next == null) next = buildReplyMarkerString();
            } else {
                next = buildReplyMarkerCharSequence();
            }
            if (next == null || next.length() == 0) return;
            if (!next.toString().contentEquals(currentReplyMarkerAfterCursor)) {
                boolean ok = IMSController.getInstance().replaceAfterCursorIfMatches(currentReplyMarkerAfterCursor, next);
                if (ok) currentReplyMarkerAfterCursor = next.toString();
            }
        } catch (Throwable ignored) {}
    }

    private void maybeAdvanceRainbowMarkerOnTick() {
        if (!suffixInsertedThisRequest) return;
        if (geekCodeMode) return; // locked to single-color marker in code mode
        if (markerStyleSnapshot != SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM
                && markerStyleSnapshot != SPManager.GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) return;
        if (buildLiveReplyMarkerBody().trim().isEmpty()) return;
        if (currentReplyMarkerAfterCursor == null || currentReplyMarkerAfterCursor.isEmpty()) return;

        int skip = computeRainbowTickSkip();
        rainbowAnimTickCounter++;
        if ((rainbowAnimTickCounter % skip) != 0) return;


rainbowAnimStep++;

try {
    if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM) {
        String next = buildReplyMarkerString(rainbowAnimStep);
        if (next == null || next.isEmpty()) return;
        if (next.equals(currentReplyMarkerAfterCursor)) return;

        boolean ok = IMSController.getInstance().replaceAfterCursorIfMatches(currentReplyMarkerAfterCursor, next);
        if (ok) {
            currentReplyMarkerAfterCursor = next;
        }
    } else if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) {
        CharSequence styled = buildReplyMarkerCharSequence(rainbowAnimStep);
        boolean ok = IMSController.getInstance().replaceAfterCursorIfMatches(currentReplyMarkerAfterCursor, styled);
        if (ok && styled != null) {
            currentReplyMarkerAfterCursor = styled.toString();
        }
    }
} catch (Throwable ignored) {
}
    }

    private void ensureReplySuffixInsertedIfNeeded() {
        if (suffixInsertedThisRequest) return;
        if (!generatingContentEnabledSnapshot) return;
        String markerBody = buildLiveReplyMarkerBody();
        if (markerBody == null || markerBody.trim().isEmpty()) return;


if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM) {
    initRainbowBaseBlocksIfNeeded();
} else if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) {
    initRainbowTextPaletteIfNeeded();
}

CharSequence markerCs = buildReplyMarkerCharSequence(rainbowAnimStep);
String markerPlain = buildReplyMarkerString(rainbowAnimStep);
if (markerPlain == null || markerPlain.isEmpty()) return;

boolean ok = IMSController.getInstance().commitAfterCursor(markerCs);
if (ok) {
    currentReplyMarkerAfterCursor = markerPlain;
    suffixInsertedThisRequest = true;
}
    }

    private void removeReplySuffixIfPresent() {
        if (!suffixInsertedThisRequest) return;

        String expected = currentReplyMarkerAfterCursor;
        if (expected == null || expected.isEmpty()) {
            expected = buildReplyMarkerString(rainbowAnimStep);
        }

        // Decide whether to keep a final token counter marker (and whether to auto-remove it after a delay).
        String finalTokenOnly = "";
        boolean scheduleAutoRemoveToken = false;
        long autoRemoveDelayMs = replyTokenCounterAutoRemoveDelayMsSnapshot;

        if (replyTokenCounterEnabledSnapshot) {
            if (!replyTokenCounterAutoRemoveSnapshot) {
                // Keep token counter permanently.
                finalTokenOnly = buildReplyTokenCounterText();
            } else {
                // Auto-remove is enabled.
                // If delay is 0 -> remove immediately (legacy behavior).
                // If delay > 0 -> keep the final token marker for a short time, then remove.
                if (autoRemoveDelayMs > 0) {
                    finalTokenOnly = buildReplyTokenCounterText();
                    scheduleAutoRemoveToken = (finalTokenOnly != null && !finalTokenOnly.isEmpty());
                }
            }
        }

        try {
            boolean ok = false;
            if (expected != null && !expected.isEmpty()) {
                if (finalTokenOnly != null && !finalTokenOnly.isEmpty()) {
                    ok = IMSController.getInstance().replaceAfterCursorIfMatches(expected, finalTokenOnly);
                } else {
                    ok = IMSController.getInstance().tryDeleteAfterCursorIfMatches(expected);
                }
            }
            if (!ok) {
                String keyword = rawSuffixKeywordSnapshot;
                if (keyword != null && !keyword.trim().isEmpty()) {
                    if (finalTokenOnly != null && !finalTokenOnly.isEmpty()) {
                        ok = IMSController.getInstance().replaceAfterCursorIfMatches(keyword, finalTokenOnly);
                    } else {
                        ok = IMSController.getInstance().tryDeleteAfterCursorIfMatches(keyword);
                    }
                }
            }
            if (!ok && expected != null && !expected.isEmpty()) {
                if (finalTokenOnly != null && !finalTokenOnly.isEmpty()) {
                    IMSController.getInstance().replaceAfterCursorIfMatches(expected, finalTokenOnly);
                } else {
                    IMSController.getInstance().deleteAfterCursorIfMatches(expected);
                }
            }
            if (ok && finalTokenOnly != null && !finalTokenOnly.isEmpty()) {
                suffixInsertedThisRequest = false;
                currentReplyMarkerAfterCursor = finalTokenOnly;
                rainbowBaseBlocks = null;
                rainbowAnimStep = 0;
                rainbowAnimTickCounter = 0;

                if (scheduleAutoRemoveToken) {
                    scheduleAutoRemoveTokenCounterMarker(finalTokenOnly, autoRemoveDelayMs);
                }
                return;
            }
        } catch (Throwable ignored) {
        }

        suffixInsertedThisRequest = false;
        currentReplyMarkerAfterCursor = "";
        rainbowBaseBlocks = null;
        rainbowAnimStep = 0;
        rainbowAnimTickCounter = 0;
    }

    private void scheduleAutoRemoveTokenCounterMarker(@NonNull String tokenOnly, long delayMs) {
        if (delayMs <= 0L) return;
        if (tokenOnly == null || tokenOnly.isEmpty()) return;

        // v8: Add a plain-text countdown after the token marker, e.g. "【...】 10s".
        // This is purely UI and does not affect the actual response content.
        final long startMs = System.currentTimeMillis();
        final long endMs = startMs + delayMs;
        final String base = tokenOnly;
        final String[] current = new String[]{base};

        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long remainMs = endMs - now;
                if (remainMs <= 0L) {
                    try {
                        boolean ok = IMSController.getInstance().tryDeleteAfterCursorIfMatches(current[0]);
                        if (!ok && !base.equals(current[0])) {
                            IMSController.getInstance().tryDeleteAfterCursorIfMatches(base);
                        }
                    } catch (Throwable ignored) {
                    }
                    return;
                }

                int remainSec = (int) Math.ceil(remainMs / 1000.0d);
                if (remainSec < 1) remainSec = 1;

                String next = base + " " + remainSec + "s";
                try {
                    if (!next.equals(current[0])) {
                        boolean ok = IMSController.getInstance().replaceAfterCursorIfMatches(current[0], next);
                        if (!ok && base.equals(current[0])) {
                            ok = IMSController.getInstance().replaceAfterCursorIfMatches(base, next);
                        }
                        if (ok) {
                            current[0] = next;
                        }
                    }
                } catch (Throwable ignored) {
                }

                try {
                    toastHandler.postDelayed(this, 1000L);
                } catch (Throwable ignored) {
                }
            }
        };

        try {
            // Update immediately (show the initial countdown value).
            toastHandler.post(tick);
        } catch (Throwable ignored) {
        }
    }

    // Thread pool for AI requests - reuse threads instead of creating new ones
    private static final ExecutorService aiExecutor = Executors.newFixedThreadPool(2);

    // Shutdown hook to clean up executor
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            aiExecutor.shutdown();
            try {
                if (!aiExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    aiExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                aiExecutor.shutdownNow();
            }
        }));
    }

    private void aiDiag(String category, String msg) {
        try { AiDiagnostics.append(category, msg); } catch (Throwable ignored) {}
    }

    // =============================
    // v7: Interrupt helpers
    // =============================

    private void resetInterruptState() {
        interruptUiSuppressedThisRequest = false;
        interruptBufferEnabledThisRequest = false;
        interruptRequestedAtMs = 0L;
        interruptModeSnapshot = -1;
        interruptBuffer.setLength(0);
    }

    private void appendToInterruptBuffer(String s) {
        if (!interruptBufferEnabledThisRequest) return;
        if (s == null || s.isEmpty()) return;
        try {
            interruptBuffer.append(s);
        } catch (Throwable ignored) {
        }
    }

    private void harvestAnyPendingOutputIntoInterruptBuffer() {
        // Harvest any UI/network buffers that haven't been committed yet.
        try {
            if (!streamingEnabledSnapshot) {
                if (bufferedResponse.length() > 0) {
                    appendToInterruptBuffer(bufferedResponse.toString());
                    bufferedResponse.setLength(0);
                }
                return;
            }
            if (streamPending.length() > 0) {
                appendToInterruptBuffer(streamPending.toString());
                streamPending.setLength(0);
            }
            if (streamPrefetch.length() > 0) {
                appendToInterruptBuffer(streamPrefetch.toString());
                streamPrefetch.setLength(0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopUiOutputForInterrupt() {
        try {
            burnerActiveThisRequest = false;
            geekCodeMode = false;
        } catch (Throwable ignored) {}

        try { cancelStreamTicks(); } catch (Throwable ignored) {}
        try {
            streamScheduled = false;
            streamCompleted = false;
        } catch (Throwable ignored) {}

        // Stop any IME-visible UI states (placeholders, reply marker, toast loop).
        try { IMSController.getInstance().endInputLock(); } catch (Throwable ignored) {}
        try { clearGeneratingContent(); } catch (Throwable ignored) {}
        try { removeReplySuffixIfPresent(); } catch (Throwable ignored) {}
        try { stopReplyingToastLoop(/*showDoneToast*/false); } catch (Throwable ignored) {}
        try { stopThinkingPlaceholderTicker(); } catch (Throwable ignored) {}
        try { releaseTypingTone(); } catch (Throwable ignored) {}
        try {
            replyStartedThisRequest = false;
            suffixInsertedThisRequest = false;
            lastVibrateAtMs = 0;
        } catch (Throwable ignored) {}

        // Make sure triggers are usable immediately after interrupt.
        try {
            IMSController.getInstance().startNotifyInput();
        } catch (Throwable ignored) {}

        // Reset text action mode
        try { setTextActionMode(false, null); } catch (Throwable ignored) {}
    }

    private void flushInterruptBufferToClipboardIfNeeded(String reason) {
        if (!interruptBufferEnabledThisRequest) return;
        String s;
        try { s = interruptBuffer.toString(); } catch (Throwable t) { s = null; }
        if (s == null) s = "";
        s = s.trim();
        if (s.isEmpty()) {
            aiDiag("INTERRUPT_BUFFER_EMPTY", String.valueOf(reason));
            return;
        }
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null) {
                AIClipboardStore.append(ctx, s);
                aiDiag("INTERRUPT_BUFFER_SAVED", "len=" + s.length() + " reason=" + reason);
                Logger.log("[INTERRUPT] buffered saved to AIClipboardStore len=" + s.length() + " reason=" + reason);
            }
        } catch (Throwable t) {
            aiDiag("INTERRUPT_BUFFER_SAVE_FAIL", String.valueOf(t == null ? null : t.getMessage()));
            Logger.log(t);
        }
    }

    /**
     * Called from InputConnection hook (locked state). Must be lightweight and IME-safe.
     *
     * @param mode SPManager.INTERRUPT_MODE_*
     * @param deleteBeforeCursor best-effort delete count to remove trigger from input box
     * @param triggerText trigger string ("!!" or "/stop"), null for backspace gesture
     */
    public void requestUserInterrupt(final int mode, final int deleteBeforeCursor, final String triggerText) {
        if (safeRequestRunning) {
            cancelSafeRequest();
            UiInteractor.getInstance().toastShort("已停止生成，原文已保留");
            return;
        }
        // Ensure we run on main looper (AiResponseManager is UI-thread oriented).
        try {
            // Prioritize interrupt over queued streaming ticks.
            streamHandler.postAtFrontOfQueue(() -> handleUserInterrupt(mode, deleteBeforeCursor, triggerText));
        } catch (Throwable t) {
            // Fallback (should be rare)
            handleUserInterrupt(mode, deleteBeforeCursor, triggerText);
        }
    }

    private void handleUserInterrupt(int mode, int deleteBeforeCursor, String triggerText) {
        // Debounce repeated hits.
        long now = System.currentTimeMillis();
        if (interruptRequestedAtMs > 0L && (now - interruptRequestedAtMs) < 350L) {
            return;
        }

        interruptRequestedAtMs = now;
        interruptModeSnapshot = mode;
        boolean wantSoft = (mode == SPManager.INTERRUPT_MODE_SOFT_BUFFER || mode == SPManager.INTERRUPT_MODE_BOTH);
        boolean wantHard = (mode == SPManager.INTERRUPT_MODE_HARD_CANCEL || mode == SPManager.INTERRUPT_MODE_BOTH);

        // Always suppress UI output immediately (even for hard-only) so user can regain control.
        interruptUiSuppressedThisRequest = true;
        interruptBufferEnabledThisRequest = wantSoft;

        aiDiag("INTERRUPT_REQ", "mode=" + mode + " soft=" + wantSoft + " hard=" + wantHard + " trig=" + triggerText);
        Logger.log("[INTERRUPT] request mode=" + mode + " trig=" + triggerText);

        // Best-effort remove the trigger sequence from the editor to avoid polluting the input box.
        try {
            if (triggerText != null && !triggerText.isEmpty()) {
                boolean ok = IMSController.getInstance().deleteBeforeCursorIfMatches(triggerText);
                if (!ok && deleteBeforeCursor > 0) {
                    IMSController.getInstance().delete(deleteBeforeCursor);
                }
            } else if (deleteBeforeCursor > 0) {
                IMSController.getInstance().delete(deleteBeforeCursor);
            }
        } catch (Throwable ignored) {
        }

        // Soft interrupt: pause UI writing and start buffering.
        if (wantSoft) {
            harvestAnyPendingOutputIntoInterruptBuffer();
        } else {
            // Hard-only: drop any uncommitted buffers to stop immediately.
            try {
                bufferedResponse.setLength(0);
                streamPending.setLength(0);
                streamPrefetch.setLength(0);
            } catch (Throwable ignored) {
            }
        }

        stopUiOutputForInterrupt();

        // Hard interrupt: attempt to cancel the in-flight request.
        if (wantHard) {
            try {
                aiDiag("INTERRUPT_HARD_CANCEL", "invoke cancelActiveRequestByUser");
                Logger.log("[INTERRUPT] hard cancel invoked");
                mAIController.cancelActiveRequestByUser();
            } catch (Throwable t) {
                aiDiag("INTERRUPT_HARD_CANCEL_FAIL", String.valueOf(t == null ? null : t.getMessage()));
                Logger.log(t);
            }
        }
    }

    private String buildPlainFailureHint(String errorMsg) {
        String msg = errorMsg == null ? "" : errorMsg;
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        String lc = msg.toLowerCase(Locale.ROOT);
        if (lc.contains("timeout") || msg.contains("超时")) return "AI请求失败: 请求超时，已恢复触发器";
        if (msg.contains("空响应") || msg.contains("未返回任何内容") || lc.contains("empty")) return "AI请求失败: 空响应（无内容）";
        if ((msg.contains("流") && (msg.contains("解析") || msg.contains("协议"))) || lc.contains("sse") || lc.contains("jsonl") || lc.contains("stream parse")) {
            return "AI请求失败: 流式解析失败，已尝试回退非流式";
        }
        if (lc.contains("model not found") || lc.contains("does not exist") || lc.contains("unsupported model") || msg.contains("模型不存在")) {
            return "AI请求失败: 模型不可用（名称不存在/不支持）";
        }
        if (lc.contains("quota") || lc.contains("insufficient_quota") || msg.contains("额度") || msg.contains("配额")) {
            return "AI请求失败: 配额不足或已超限";
        }
        // Auth / permission
        if (lc.contains("missing authentication header") || lc.contains("missing authentication") || lc.contains("missing api key") || lc.contains("missing apikey")) {
            return "AI请求失败: 未携带鉴权信息（可能未配置API Key，或Key包含空格/换行导致请求头无效）";
        }
        if (lc.contains("401") || lc.contains("403") || lc.contains("unauthorized") || lc.contains("forbidden") || msg.contains("无权限")) {
            return "AI请求失败: 无权限访问（请检查API Key/模型权限/是否被风控）";
        }
        if (msg.isEmpty()) msg = "未知错误";
        if (msg.length() > 96) msg = msg.substring(0, 96) + "…";
        return "AI请求失败: " + msg;
    }

    public AiResponseManager(GenerativeAIController aiController, Runnable onAiPrepareCallback) {
        this.mAIController = aiController;
        this.onAiPrepareCallback = onAiPrepareCallback;
        this.mAIController.addListener(this);
    }

        private void resetLeadingAssistantLabelStripState() {
        checkedLeadingAssistantLabelThisRequest = false;
        leadingAssistantLabelBuffer.setLength(0);
    }

    private String maybeStripLeadingAssistantLabel(String chunk) {
        if (chunk == null) return null;
        if (checkedLeadingAssistantLabelThisRequest) return chunk;

        try {
            leadingAssistantLabelBuffer.append(chunk);
            String buf = leadingAssistantLabelBuffer.toString();

            // Fast path: if it doesn't even look like a role label prefix, stop waiting immediately.
            String t = buf.trim();
            boolean mightBeLabel = false;
            if (!t.isEmpty()) {
                String tl = t.toLowerCase(Locale.US);
                mightBeLabel = tl.startsWith("assistant") || t.startsWith("助手");
            }
            if (!mightBeLabel) {
                checkedLeadingAssistantLabelThisRequest = true;
                leadingAssistantLabelBuffer.setLength(0);
                return buf;
            }

            // Wait for ':' / '：' or enough characters to decide.
            boolean hasColon = buf.contains(":") || buf.contains("：");
            if (!hasColon && leadingAssistantLabelBuffer.length() < 16) {
                return null;
            }

            checkedLeadingAssistantLabelThisRequest = true;
            leadingAssistantLabelBuffer.setLength(0);
            return LEADING_ASSISTANT_LABEL.matcher(buf).replaceFirst("");
        } catch (Throwable ignored) {
            checkedLeadingAssistantLabelThisRequest = true;
            leadingAssistantLabelBuffer.setLength(0);
            return chunk;
        }
    }

    private tn.eluea.kgpt.core.input.SafeInputEdit pendingSafeEdit;
    private tn.eluea.kgpt.core.input.SafeInputEdit activeSafeEdit;
    private final StringBuilder safeResponse = new StringBuilder();
    private volatile boolean safeRequestRunning;

    public boolean hasSafeEdit() { return pendingSafeEdit != null; }

    public boolean prepareSafeEdit(String text, int start, int end) {
        if (safeRequestRunning || mAIController.isRequestRunning()) {
            UiInteractor.getInstance().toastShort("正在生成，请先停止当前请求");
            return false;
        }
        pendingSafeEdit = IMSController.getInstance().captureSafeEdit(text, start, end);
        if (pendingSafeEdit == null) {
            UiInteractor.getInstance().toastShort("无法安全读取此输入框，原文已保留，请使用选中文字或应用内聊天");
            return false;
        }
        return true;
    }

    public void cancelSafeRequest() {
        if (safeRequestRunning) {
            finishSafeRequest(false);
            mAIController.cancelActiveRequestByUser();
        }
        if (pendingSafeEdit != null) pendingSafeEdit.cancel();
        pendingSafeEdit = null;
    }

    private void finishSafeRequest(boolean success) {
        String output = LEADING_ASSISTANT_LABEL.matcher(safeResponse.toString()).replaceFirst("");
        boolean inserted = success && activeSafeEdit != null && activeSafeEdit.complete(output);
        if (!inserted && !output.trim().isEmpty()) {
            AIClipboardStore.append(UiInteractor.getInstance().getContext(), output);
            UiInteractor.getInstance().toastLong("结果已保存到 AI 剪贴板，原文已保留");
        }
        if (activeSafeEdit != null) activeSafeEdit.cancel();
        activeSafeEdit = null;
        safeResponse.setLength(0);
        safeRequestRunning = false;
        setTextActionMode(false, null);
        TopStatusBanner.getInstance().hide();
        broadcastStandaloneGenHide();
    }

    public void generateResponse(String prompt, String systemMessage) {
        generateResponse(prompt, systemMessage, null);
    }

    /**
     * Generate AI response with an optional role (persona) override.
     * If roleIdOverride is null/blank, the currently active role is used.
     */
    public void generateResponse(String prompt, String systemMessage, String roleIdOverride) {
        // If prompt is empty, don't trigger anything - treat as normal text
        if (prompt == null || prompt.trim().isEmpty()) {
            return;
        }

        if (mAIController.needModelClient()) {
            if (UiInteractor.getInstance().showChoseModelDialog()) {
                Context ctx = UiInteractor.getInstance().getContext();
                String msg = ctx != null ? ctx.getString(R.string.choose_model_message)
                        : "Chose and configure your language model";
                UiInteractor.getInstance().toastLong(msg);
            }
            return;
        }

        if (mAIController.needApiKey()) {
            if (UiInteractor.getInstance().showChoseModelDialog()) {
                Context ctx = UiInteractor.getInstance().getContext();
                String msg = ctx != null
                        ? ctx.getString(R.string.missing_api_key_message, mAIController.getLanguageModel().label)
                        : mAIController.getLanguageModel().label + " is Missing API Key";
                UiInteractor.getInstance().toastLong(msg);
            }
            return;
        }

        if (safeRequestRunning || mAIController.isRequestRunning()) {
            pendingSafeEdit = null;
            UiInteractor.getInstance().toastShort("正在生成，请先停止当前请求");
            return;
        }
        activeSafeEdit = pendingSafeEdit != null ? pendingSafeEdit
                : IMSController.getInstance().captureSafeEdit(null, -1, -1);
        pendingSafeEdit = null;
        if (activeSafeEdit == null) {
            UiInteractor.getInstance().toastShort("无法安全替换此输入框，原文已保留");
            return;
        }
        safeResponse.setLength(0);
        safeRequestRunning = true;

        // Snapshot the role for UI placeholders (thinking/reply markers).
        // This is best-effort: the controller may run async, so we cache the last requested role.
        try {
            SPManager sp = tn.eluea.kgpt.SPManager.getInstance();
            String rid = roleIdOverride;
            if (rid == null || rid.trim().isEmpty()) rid = sp.getActiveRoleId();
            pendingUiRoleId = rid;
            pendingUiRoleName = sanitizePlainRoleName(resolveRoleNameById(rid, sp.getRolesJson()));
        } catch (Throwable ignored) {
            pendingUiRoleId = roleIdOverride;
            pendingUiRoleName = "";
        }

        // Reset per-request label stripping state.
        resetLeadingAssistantLabelStripState();

        // Use thread pool instead of creating new threads
        // The controller owns background work; acceptance happens before any editor mutation.
        try {
            mAIController.generateResponse(prompt, systemMessage, roleIdOverride, !isTextActionMode);
        } catch (Throwable error) {
            onAIError(error);
        }
    }

    public void setTextActionMode(boolean enabled, String selectedText) {
        this.isTextActionMode = enabled;
        this.pendingSelectedText = selectedText;
    }

    public GenerativeAIController getController() {
        return mAIController;
    }

    // --- GenerativeAIListener Implementation ---

    @Override
    public void onAIPrepare() {
        if (!safeRequestRunning) return;
        Context context = UiInteractor.getInstance().getContext();
        if (context != null && canUseTopBannerOverlayInCurrentContext())
            TopStatusBanner.getInstance().show(context, "正在生成，原文保留中");
        if (onAiPrepareCallback != null) onAiPrepareCallback.run();
        UiInteractor.getInstance().toastShort("正在生成，原文将保留至完成");
    }

    private void clearGeneratingContent() {
        if (justPrepared) {
            stopThinkingPlaceholderTicker();
            justPrepared = false;
            IMSController.getInstance().flush();
            String current = currentGeneratingPrefixBeforeCursor;
            boolean removed = false;
            if (current != null && !current.isEmpty()) {
                try {
                    removed = IMSController.getInstance().deleteBeforeCursorIfMatches(current);
                } catch (Throwable ignored) {
                    removed = false;
                }
            }
            if (!removed) {
                String generatingContent = generatingContentSnapshot;
                if (generatingContent == null || generatingContent.isEmpty()) {
                    generatingContent = getDefaultGeneratingContentString();
                }
                IMSController.getInstance().delete(generatingContent.length());
            }
            currentGeneratingPrefixBeforeCursor = "";
        }
    }

    // ------------------------------------------------------------
    // Dynamic banner prefix + token burner (TopStatusBanner)
    // ------------------------------------------------------------
    private static int countTriplets(String haystack) {
        if (haystack == null || haystack.isEmpty()) return 0;
        final String needle = "```";
        int count = 0;
        int idx = 0;
        while (true) {
            int at = haystack.indexOf(needle, idx);
            if (at < 0) break;
            count++;
            idx = at + needle.length();
        }
        return count;
    }

    private boolean computeModelSuggestsReasoning() {
        try {
            String sub = null;
            if (mAIController != null && mAIController.getModelClient() != null) {
                sub = mAIController.getModelClient().getSubModel();
            }
            String s = (sub == null ? "" : sub).toLowerCase();
            // simple keyword sniffing as requested
            return s.contains("o1")
                    || s.contains("o3")
                    || s.contains("r1")
                    || s.contains("think")
                    || s.contains("reason");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int computeBurnerMaxTokens() {
        int max = 4096;
        try {
            int v = SPManager.getInstance().getMaxTokensLimit();
            if (v > 0) max = v;
        } catch (Throwable ignored) {
        }
        // Safety clamp for absurdly high values (keep the visualization meaningful)
        if (max <= 0 || max > 150000) max = 4096;
        return max;
    }

    private int estimateCompletionTokens() {
        // If streaming usage is unavailable, estimate from characters.
        int chars = 0;
        try {
            chars = streamCommittedTotalChars;
            // include buffered chars as a small look-ahead to keep it responsive
            chars += streamPending.length();
            if (prefetchEnabledSnapshot) chars += streamPrefetch.length();
        } catch (Throwable ignored) {
        }
        if (chars < 0) chars = 0;
        return (int) Math.round(chars / 1.5);
    }

    private String computeDynamicBannerText() {
        if (!dynamicPrefixEnabledSnapshot) {
            // Keep user's custom prefix (or default) without any sniffing.
            String s = generatingContentSnapshot;
            if (s != null && !s.trim().isEmpty()) return s.trim();
            return getDefaultGeneratingContentString();
        }

        // Code mode has priority
        boolean code = (codeFenceTripletCount & 1) == 1;
        if (code) {
            return "💻 正在编译代码...";
        }

        boolean reasoning = modelSuggestsReasoning || inThinkBlock;
        if (reasoning) {
            // Early stage / explicit <think> tag
            if (inThinkBlock || streamCommittedTotalChars < 160) {
                return "🧠 正在构建思维链...";
            }
            return "💡 深度发散思考中...";
        }

        // Normal: keep user's custom prefix
        String s = generatingContentSnapshot;
        if (s != null && !s.trim().isEmpty()) return s.trim();
        return getDefaultGeneratingContentString();
    }

    private void broadcastStandaloneGenStatus(String text, int progressPercent, int color) {
        if (!generatingContentEnabledSnapshot || !toastEnabledSnapshot || !standaloneFloatEnabledSnapshot) return;
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx == null) return;
            Intent i = new Intent(GeneratingStatusBridgeReceiver.ACTION);
            i.setPackage(tn.eluea.kgpt.BuildConfig.APPLICATION_ID);
            i.putExtra(GeneratingStatusBridgeReceiver.EXTRA_CMD, GeneratingStatusBridgeReceiver.CMD_UPDATE);
            i.putExtra(GeneratingStatusBridgeReceiver.EXTRA_TEXT, text);
            i.putExtra(GeneratingStatusBridgeReceiver.EXTRA_PROGRESS, progressPercent);
            i.putExtra(GeneratingStatusBridgeReceiver.EXTRA_COLOR, color);
            tn.eluea.kgpt.provider.BridgeAuth.send(ctx, i);
        } catch (Throwable ignored) {}
    }

    private void broadcastStandaloneGenDone(String text) {
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx == null) return;
            Intent i = new Intent(GeneratingStatusBridgeReceiver.ACTION);
            i.setPackage(tn.eluea.kgpt.BuildConfig.APPLICATION_ID);
            i.putExtra(GeneratingStatusBridgeReceiver.EXTRA_CMD, GeneratingStatusBridgeReceiver.CMD_DONE);
            i.putExtra(GeneratingStatusBridgeReceiver.EXTRA_TEXT, text);
            tn.eluea.kgpt.provider.BridgeAuth.send(ctx, i);
        } catch (Throwable ignored) {}
    }

    private void broadcastStandaloneGenHide() {
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx == null) return;
            Intent i = new Intent(GeneratingStatusBridgeReceiver.ACTION);
            i.setPackage(tn.eluea.kgpt.BuildConfig.APPLICATION_ID);
            i.putExtra(GeneratingStatusBridgeReceiver.EXTRA_CMD, GeneratingStatusBridgeReceiver.CMD_HIDE);
            tn.eluea.kgpt.provider.BridgeAuth.send(ctx, i);
        } catch (Throwable ignored) {}
    }

    private void maybeUpdateBannerUi(boolean force) {
        if (!generatingContentEnabledSnapshot || !toastEnabledSnapshot) return;
        long now = System.currentTimeMillis();
        if (!force && (now - lastBannerUiUpdateAtMs) < 120L) return;
        lastBannerUiUpdateAtMs = now;

        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx == null) return;

            final boolean allowTopBanner = canUseTopBannerOverlayInCurrentContext();
            final boolean allowStandalone = standaloneFloatEnabledSnapshot;
            if (!allowTopBanner && !allowStandalone) return;

            String msg = computeDynamicBannerText();
            final boolean showBurner = tokenBurnerEnabledSnapshot;
            int pct = -1;
            int color = Color.parseColor("#4CAF50");
            if (showBurner) {
                int cur = estimateCompletionTokens();
                int max = burnerMaxTokensSnapshot > 0 ? burnerMaxTokensSnapshot : 4096;
                float ratio = max <= 0 ? 0f : (cur / (float) max);
                if (ratio < 0f) ratio = 0f;

                pct = (int) Math.round(Math.min(1.0f, ratio) * 100f);
                if (ratio < 0.5f) color = Color.parseColor("#4CAF50");
                else if (ratio < 0.8f) color = Color.parseColor("#FF9800");
                else color = Color.parseColor("#F44336");
            }

            if (allowStandalone) {
                broadcastStandaloneGenStatus(msg, showBurner ? pct : -1, color);
            }

            if (!allowTopBanner) return;

            // Explicit UI thread request (even though TopStatusBanner is main-safe)
            final int p = Math.max(0, pct);
            final int c = color;
            UiInteractor.getInstance().runOnUiThread(() ->
                    TopStatusBanner.getInstance().updateState(msg, /*showBurner*/showBurner, p, c)
            );
        } catch (Throwable ignored) {
        }
    }

    private CharSequence buildGeekGreenMarker(CharSequence plain) {
        if (plain == null) plain = "";
        SpannableString ss = new SpannableString(plain);
        try {
            ss.setSpan(new ForegroundColorSpan(Color.parseColor("#00FF00")), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Throwable ignored) {}
        return ss;
    }

    private void setGeekCodeMode(boolean enabled) {
        // InputConnection is plain-text only in many IME-host apps.
        // In v3 role marker mode, we must not insert any styled spans.
        if (inputRoleMarkerEnabledSnapshot) {
            geekCodeMode = false;
            return;
        }
        if (geekCodeMode == enabled) return;
        geekCodeMode = enabled;
        if (!suffixInsertedThisRequest) return;
        try {
            // Remove current marker, then insert the new one.
            String old = currentReplyMarkerAfterCursor;
            if (old != null && !old.isEmpty()) {
                IMSController.getInstance().tryDeleteAfterCursorIfMatches(old);
            }

            if (enabled) {
                // Stable single-color cursor marker (geek green)
                String plainBody = buildLiveReplyMarkerBody();
                CharSequence cs = buildGeekGreenMarker(plainBody);
                boolean ok = IMSController.getInstance().commitAfterCursor(cs);
                if (ok) {
                    currentReplyMarkerAfterCursor = plainBody;
                }
            } else {
                // Restore user's marker style (restart animation from step 0)
                rainbowBaseBlocks = null;
                rainbowAnimStep = 0;
                rainbowAnimTickCounter = 0;

                CharSequence cs = buildReplyMarkerCharSequence(rainbowAnimStep);
                String plain = buildReplyMarkerString(rainbowAnimStep);
                if (plain != null && !plain.isEmpty()) {
                    boolean ok = IMSController.getInstance().commitAfterCursor(cs);
                    if (ok) {
                        currentReplyMarkerAfterCursor = plain;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAINext(String chunk) {
        if (safeRequestRunning && chunk != null
                && !tn.eluea.kgpt.llm.client.LanguageModelClient.INTERNAL_KEEPALIVE_MARKER.equals(chunk))
            safeResponse.append(chunk);
    }

    @Override
    public void onAIError(Throwable t) {
        if (!safeRequestRunning) return;
        finishSafeRequest(false);
        String message = t == null ? "未知错误" : t.getMessage();
        if (message == null || !message.contains(GenerativeAIController.USER_CANCELLED_MARKER))
            UiInteractor.getInstance().toastLong("生成失败，原文已保留：" + message);
    }

    @Override
    public void onAIComplete() {
        if (safeRequestRunning) finishSafeRequest(true);
    }


    // =============================
    // v4: Plain-text role marker helpers
    // =============================

    private static boolean containsRoleToken(String s) {
        if (s == null) return false;
        return s.contains("{role}") || s.contains("${role}");
    }

    private static String applyRoleToPlaceholder(String raw, String roleName, String fallback) {
        String r = raw == null ? "" : raw;
        String rn = roleName == null ? "" : roleName.trim();
        if (r.trim().isEmpty()) return fallback;

        // Template substitution
        String expanded = r.replace("{role}", rn).replace("${role}", rn);
        if (containsRoleToken(r)) {
            return expanded;
        }

        // Auto-prefix role name when template token is absent.
        if (rn.isEmpty()) return expanded;
        String trimmed = expanded.trim();
        if (trimmed.startsWith(rn)) return expanded;
        return rn + expanded;
    }

    private static String sanitizePlainInputMarkerText(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        int len = s.length();
        for (int i = 0; i < len; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            // Drop non-BMP (emoji, many symbols)
            if (cp > 0xFFFF) continue;
            char c = (char) cp;
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
                continue;
            }
            if (Character.isISOControl(c)) continue;
            out.append(c);
        }
        String r = out.toString();
        // Avoid extremely long markers
        if (r.length() > 96) r = r.substring(0, 96);
        return r;
    }

    private static String sanitizePlainRoleName(String s) {
        if (s == null) return "";
        // InputConnection commitText is plain text; many IME-host apps drop spans/emoji.
        // Keep BMP characters and remove control chars / newlines.
        StringBuilder out = new StringBuilder();
        int len = s.length();
        for (int i = 0; i < len; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            // Drop non-BMP (emoji, many symbols)
            if (cp > 0xFFFF) continue;
            char c = (char) cp;
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
                continue;
            }
            if (Character.isISOControl(c)) continue;
            out.append(c);
        }
        String r = out.toString().trim();
        // Avoid extremely long markers
        if (r.length() > 24) r = r.substring(0, 24);
        return r;
    }

    private static String resolveRoleNameById(String roleId, String rolesJson) {
        String rid = roleId == null ? "" : roleId.trim();
        if (rid.isEmpty()) rid = RoleManager.DEFAULT_ROLE_ID;
        try {
            List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);
            for (RoleManager.Role r : roles) {
                if (r == null) continue;
                if (rid.equals(r.id)) return r.name;
            }
        } catch (Throwable ignored) {}
        // Fallback
        if (RoleManager.DEFAULT_ROLE_ID.equals(rid)) return RoleManager.DEFAULT_ROLE_NAME;
        return RoleManager.DEFAULT_ROLE_NAME;
    }

}
