package com.mosamircs.biopulse;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.github.ajalt.reprint.core.AuthenticationFailureReason;
import com.github.ajalt.reprint.core.AuthenticationListener;
import com.github.ajalt.reprint.core.Reprint;
import com.github.ajalt.reprint.rxjava2.RxReprint;
import com.mosamircs.biopulse.databinding.ActivityMainBinding;

import java.util.List;
import java.util.concurrent.Executor;

import io.reactivex.disposables.Disposable;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ScanHistoryStore historyStore;
    private AdManager adManager;
    private BiometricAudit.AuditSummary lastAudit;

    private boolean isListening = false;
    private long scanStartElapsedMs = 0L;
    private Disposable rxDisposable;
    private AnimatorSet pulseAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        historyStore = new ScanHistoryStore(this);
        adManager = new AdManager(this);

        setupActions();
        refreshAuditAndMetrics();
        adManager.initializeAndLoadBanner(binding.adBannerContainer);
    }

    @Override
    protected void onResume() {
        super.onResume();
        adManager.resume();
        refreshAuditAndMetrics();
    }

    @Override
    protected void onPause() {
        super.onPause();
        adManager.pause();
        stopActiveScan(false);
    }

    @Override
    protected void onDestroy() {
        stopPulseAnimation();
        adManager.destroy();
        super.onDestroy();
    }

    private void setupActions() {
        binding.scannerPadContainer.setOnClickListener(v -> {
            if (isListening) {
                stopActiveScan(true);
            } else {
                startSensorTest();
            }
        });

        binding.btnOpenSettings.setOnClickListener(v -> openBiometricSettings());
        binding.btnEnrollBiometrics.setOnClickListener(v -> openBiometricSettings());

        binding.btnShareReport.setOnClickListener(v -> {
            if (lastAudit == null) return;
            String report = BiometricAudit.buildShareableReport(
                    this,
                    lastAudit,
                    historyStore.getTotalScans(),
                    historyStore.getSuccessRatePercent(),
                    historyStore.getAverageLatencyMs()
            );
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "BioPulse Biometric Diagnostics Report");
            shareIntent.putExtra(Intent.EXTRA_TEXT, report);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share)));
        });

        binding.btnClearHistory.setOnClickListener(v -> {
            historyStore.clear();
            refreshMetricsAndHistory();
            binding.tvLastLatency.setVisibility(View.GONE);
            Toast.makeText(this, "Scan history cleared", Toast.LENGTH_SHORT).show();
        });
    }

    private void startSensorTest() {
        isListening = true;
        scanStartElapsedMs = SystemClock.elapsedRealtime();
        setBadge(getString(R.string.scanner_status_listening), R.color.bp_secondary);
        binding.tvScanResultMessage.setText(R.string.scanner_hint_listening);
        binding.tvLastLatency.setVisibility(View.GONE);
        startPulseAnimation();

        int checkedId = binding.chipGroupEngine.getCheckedChipId();
        if (checkedId == R.id.chipDirect) {
            runDirectReprintSensor();
        } else if (checkedId == R.id.chipRx) {
            runRxReprintSensor();
        } else {
            runModernBiometricPrompt();
        }
    }

    private void runModernBiometricPrompt() {
        final String engine = getString(R.string.mode_biometric);
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        handleScanSuccess(engine, "Biometric verification succeeded");
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                            handleScanCancelled();
                        } else {
                            handleScanFailure(engine, true, errString.toString());
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        handleScanFailure(engine, false, "Biometric not recognized — try again");
                    }
                });

        BiometricPrompt.PromptInfo.Builder promptBuilder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.prompt_title))
                .setSubtitle(getString(R.string.prompt_subtitle))
                .setDescription(getString(R.string.prompt_description))
                .setNegativeButtonText(getString(R.string.prompt_negative));

        BiometricManager bm = BiometricManager.from(this);
        int strong = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        int weak = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        if (strong == BiometricManager.BIOMETRIC_SUCCESS) {
            promptBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        } else if (weak == BiometricManager.BIOMETRIC_SUCCESS) {
            promptBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        } else {
            // Fall back to allowing prompt to report exact system error cleanly
            promptBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        }

        try {
            prompt.authenticate(promptBuilder.build());
        } catch (Exception e) {
            handleScanFailure(engine, true, e.getMessage() != null ? e.getMessage() : "Biometric prompt unavailable");
        }
    }

    private void runDirectReprintSensor() {
        final String engine = getString(R.string.mode_direct);
        Reprint.authenticate(new AuthenticationListener() {
            @Override
            public void onSuccess(int moduleTag) {
                runOnUiThread(() -> handleScanSuccess(engine, "Fingerprint matched cleanly"));
            }

            @Override
            public void onFailure(AuthenticationFailureReason failureReason, boolean fatal,
                                  CharSequence errorMessage, int moduleTag, int errorCode) {
                String msg = errorMessage != null ? errorMessage.toString() : failureReason.name();
                runOnUiThread(() -> handleScanFailure(engine, fatal, msg));
            }
        });
    }

    private void runRxReprintSensor() {
        final String engine = getString(R.string.mode_rx);
        disposeRx();
        rxDisposable = RxReprint.authenticate().subscribe(res -> {
            switch (res.status) {
                case SUCCESS:
                    runOnUiThread(() -> handleScanSuccess(engine, "RxStream fingerprint verified"));
                    break;
                case NONFATAL_FAILURE:
                    runOnUiThread(() -> handleScanFailure(engine, false,
                            res.errorMessage != null ? res.errorMessage.toString() : "Try again"));
                    break;
                case FATAL_FAILURE:
                    runOnUiThread(() -> handleScanFailure(engine, true,
                            res.errorMessage != null ? res.errorMessage.toString() : "Sensor error"));
                    break;
            }
        }, err -> runOnUiThread(() -> handleScanFailure(engine, true,
                err.getMessage() != null ? err.getMessage() : "Stream error")));
    }

    private void handleScanSuccess(String engine, String detail) {
        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - scanStartElapsedMs);
        isListening = false;
        stopPulseAnimation();
        disposeRx();

        setBadge(getString(R.string.scanner_status_verified), R.color.bp_success);
        binding.tvScanResultMessage.setText(detail);
        binding.tvLastLatency.setText("Response time: " + elapsed + " ms");
        binding.tvLastLatency.setVisibility(View.VISIBLE);

        historyStore.addRecord(engine, true, elapsed, detail);
        refreshMetricsAndHistory();
        adManager.onScanCompleted();
    }

    private void handleScanFailure(String engine, boolean fatal, String message) {
        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - scanStartElapsedMs);
        binding.tvScanResultMessage.setText(message);

        if (fatal) {
            isListening = false;
            stopPulseAnimation();
            disposeRx();
            setBadge(getString(R.string.scanner_status_failed), R.color.bp_error);
            historyStore.addRecord(engine, false, elapsed, message);
            refreshMetricsAndHistory();
            adManager.onScanCompleted();
        } else {
            setBadge("RETRY", R.color.bp_warning);
        }
    }

    private void handleScanCancelled() {
        isListening = false;
        stopPulseAnimation();
        disposeRx();
        setBadge(getString(R.string.scanner_status_cancelled), R.color.bp_warning);
        binding.tvScanResultMessage.setText(R.string.scanner_hint_idle);
    }

    private void stopActiveScan(boolean userRequested) {
        if (!isListening) return;
        Reprint.cancelAuthentication();
        disposeRx();
        isListening = false;
        stopPulseAnimation();
        if (userRequested) {
            setBadge(getString(R.string.scanner_status_cancelled), R.color.bp_warning);
            binding.tvScanResultMessage.setText(R.string.scanner_hint_idle);
        }
    }

    private void disposeRx() {
        if (rxDisposable != null && !rxDisposable.isDisposed()) {
            rxDisposable.dispose();
        }
        rxDisposable = null;
    }

    private void refreshAuditAndMetrics() {
        lastAudit = BiometricAudit.inspect(this);

        binding.tvReadinessPill.setText(lastAudit.readinessLabel);
        int pillColor = colorForLevel(lastAudit.readinessLevel);
        binding.tvReadinessPill.setTextColor(ContextCompat.getColor(this, pillColor));
        binding.btnEnrollBiometrics.setVisibility(lastAudit.needsEnrollment ? View.VISIBLE : View.GONE);

        binding.auditRowsContainer.removeAllViews();
        for (BiometricAudit.AuditItem item : lastAudit.items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView labelTv = new TextView(this);
            labelTv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            labelTv.setPadding(0, 0, dp(12), 0);
            labelTv.setText(item.label);
            labelTv.setTextColor(ContextCompat.getColor(this, R.color.bp_text_secondary));
            labelTv.setTextSize(13f);

            TextView valTv = new TextView(this);
            valTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            valTv.setText(item.value);
            valTv.setTextColor(ContextCompat.getColor(this, colorForLevel(item.level)));
            valTv.setTextSize(13f);
            valTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);

            row.addView(labelTv);
            row.addView(valTv);
            binding.auditRowsContainer.addView(row);
        }

        refreshMetricsAndHistory();
    }

    private void refreshMetricsAndHistory() {
        int total = historyStore.getTotalScans();
        binding.tvKpiTotalScans.setText(String.valueOf(total));
        binding.tvKpiSuccessRate.setText(total > 0 ? historyStore.getSuccessRatePercent() + "%" : "—");
        long avg = historyStore.getAverageLatencyMs();
        binding.tvKpiAvgLatency.setText(avg > 0 ? avg + " ms" : "—");

        List<ScanHistoryStore.Record> records = historyStore.getRecords();
        binding.historyContainer.removeAllViews();
        binding.tvHistoryEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (ScanHistoryStore.Record r : records) {
            View row = inflater.inflate(R.layout.item_scan_history, binding.historyContainer, false);
            TextView headline = row.findViewById(R.id.tvHistoryHeadline);
            TextView detail = row.findViewById(R.id.tvHistoryDetail);
            TextView latency = row.findViewById(R.id.tvHistoryLatency);

            String outcome = r.success ? "Verified" : "Failed";
            headline.setText(outcome + " • " + r.engine);
            headline.setTextColor(ContextCompat.getColor(this, r.success ? R.color.bp_success : R.color.bp_error));
            detail.setText(r.timestamp + " • " + r.message);
            latency.setText(r.latencyMs + " ms");

            binding.historyContainer.addView(row);
        }
    }

    private void openBiometricSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent enrollIntent = new Intent(Settings.ACTION_BIOMETRIC_ENROLL);
                enrollIntent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | BiometricManager.Authenticators.BIOMETRIC_WEAK);
                startActivity(enrollIntent);
            } else {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    private void setBadge(String text, int colorRes) {
        binding.tvStatusBadge.setText(text);
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    private int colorForLevel(BiometricAudit.StatusLevel level) {
        switch (level) {
            case OK:
                return R.color.bp_success;
            case WARNING:
                return R.color.bp_warning;
            case ERROR:
                return R.color.bp_error;
            default:
                return R.color.bp_text_primary;
        }
    }

    private void startPulseAnimation() {
        stopPulseAnimation();
        ObjectAnimator outerX = ObjectAnimator.ofFloat(binding.pulseRingOuter, View.SCALE_X, 0.94f, 1.08f);
        ObjectAnimator outerY = ObjectAnimator.ofFloat(binding.pulseRingOuter, View.SCALE_Y, 0.94f, 1.08f);
        ObjectAnimator outerA = ObjectAnimator.ofFloat(binding.pulseRingOuter, View.ALPHA, 0.9f, 0.25f);
        ObjectAnimator innerX = ObjectAnimator.ofFloat(binding.pulseRingInner, View.SCALE_X, 0.96f, 1.05f);
        ObjectAnimator innerY = ObjectAnimator.ofFloat(binding.pulseRingInner, View.SCALE_Y, 0.96f, 1.05f);

        for (ObjectAnimator anim : new ObjectAnimator[]{outerX, outerY, outerA, innerX, innerY}) {
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setRepeatMode(ValueAnimator.REVERSE);
            anim.setDuration(750L);
        }
        pulseAnimator = new AnimatorSet();
        pulseAnimator.playTogether(outerX, outerY, outerA, innerX, innerY);
        pulseAnimator.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (binding != null) {
            binding.pulseRingOuter.setScaleX(1f);
            binding.pulseRingOuter.setScaleY(1f);
            binding.pulseRingOuter.setAlpha(1f);
            binding.pulseRingInner.setScaleX(1f);
            binding.pulseRingInner.setScaleY(1f);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
