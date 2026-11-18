package com.mosamircs.biopulse;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;

import com.github.ajalt.reprint.core.Reprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Inspects device biometric capabilities across both modern AndroidX BiometricManager
 * and legacy Reprint FingerprintManager APIs.
 */
public class BiometricAudit {

    public enum StatusLevel {
        OK, WARNING, ERROR, INFO
    }

    public static class AuditItem {
        public final String label;
        public final String value;
        public final StatusLevel level;

        public AuditItem(String label, String value, StatusLevel level) {
            this.label = label;
            this.value = value;
            this.level = level;
        }
    }

    public static class AuditSummary {
        public final String readinessLabel;
        public final StatusLevel readinessLevel;
        public final boolean needsEnrollment;
        public final List<AuditItem> items;

        public AuditSummary(String readinessLabel, StatusLevel readinessLevel,
                            boolean needsEnrollment, List<AuditItem> items) {
            this.readinessLabel = readinessLabel;
            this.readinessLevel = readinessLevel;
            this.needsEnrollment = needsEnrollment;
            this.items = items;
        }
    }

    @NonNull
    public static AuditSummary inspect(@NonNull Context context) {
        BiometricManager bm = BiometricManager.from(context);
        int strongCode = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        int weakCode = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        int credCode = bm.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL);

        boolean legacyHw = Reprint.isHardwarePresent();
        boolean legacyEnrolled = Reprint.hasFingerprintRegistered();

        List<AuditItem> items = new ArrayList<>();
        items.add(new AuditItem(
                context.getString(R.string.audit_strong),
                describeBiometricCode(strongCode),
                levelForCode(strongCode)
        ));
        items.add(new AuditItem(
                context.getString(R.string.audit_weak),
                describeBiometricCode(weakCode),
                levelForCode(weakCode)
        ));
        items.add(new AuditItem(
                context.getString(R.string.audit_credential),
                describeBiometricCode(credCode),
                levelForCode(credCode)
        ));
        items.add(new AuditItem(
                context.getString(R.string.audit_legacy_hw),
                legacyHw ? "Detected" : "Not Detected",
                legacyHw ? StatusLevel.OK : StatusLevel.WARNING
        ));
        items.add(new AuditItem(
                context.getString(R.string.audit_enrolled),
                legacyEnrolled ? "Enrolled" : "None Enrolled",
                legacyEnrolled ? StatusLevel.OK : StatusLevel.WARNING
        ));

        String manufacturer = capitalize(Build.MANUFACTURER);
        String deviceInfo = manufacturer + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + " • API " + Build.VERSION.SDK_INT + ")";
        items.add(new AuditItem(context.getString(R.string.audit_device), deviceInfo, StatusLevel.INFO));

        String patch = Build.VERSION.SECURITY_PATCH != null ? Build.VERSION.SECURITY_PATCH : "Unknown";
        items.add(new AuditItem(context.getString(R.string.audit_patch), patch, StatusLevel.INFO));

        boolean needsEnrollment = strongCode == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                || weakCode == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                || (legacyHw && !legacyEnrolled);

        String readinessLabel;
        StatusLevel readinessLevel;
        if (strongCode == BiometricManager.BIOMETRIC_SUCCESS || weakCode == BiometricManager.BIOMETRIC_SUCCESS || legacyEnrolled) {
            readinessLabel = "READY";
            readinessLevel = StatusLevel.OK;
        } else if (needsEnrollment) {
            readinessLabel = "NEEDS ENROLLMENT";
            readinessLevel = StatusLevel.WARNING;
        } else {
            readinessLabel = "NO HARDWARE";
            readinessLevel = StatusLevel.ERROR;
        }

        return new AuditSummary(readinessLabel, readinessLevel, needsEnrollment, items);
    }

    @NonNull
    public static String buildShareableReport(@NonNull Context context,
                                              @NonNull AuditSummary summary,
                                              int totalScans,
                                              int successRatePct,
                                              long avgLatencyMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("BioPulse Biometric Diagnostics Report\n");
        sb.append("=====================================\n");
        sb.append("Overall Status: ").append(summary.readinessLabel).append("\n\n");
        for (AuditItem item : summary.items) {
            sb.append("• ").append(item.label).append(": ").append(item.value).append("\n");
        }
        sb.append("\nBenchmark Metrics:\n");
        sb.append("• Total Scans: ").append(totalScans).append("\n");
        sb.append("• Success Rate: ").append(totalScans > 0 ? successRatePct + "%" : "N/A").append("\n");
        sb.append("• Avg Response Time: ").append(avgLatencyMs > 0 ? avgLatencyMs + " ms" : "N/A").append("\n");
        return sb.toString();
    }

    private static String describeBiometricCode(int code) {
        switch (code) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return "Available & Enrolled";
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return "Hardware Ready (None Enrolled)";
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                return "No Biometric Hardware";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "Hardware Busy / Unavailable";
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return "Security Update Required";
            case BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED:
                return "Unsupported on OS";
            default:
                return "Status Unknown (" + code + ")";
        }
    }

    private static StatusLevel levelForCode(int code) {
        switch (code) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return StatusLevel.OK;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return StatusLevel.WARNING;
            default:
                return StatusLevel.ERROR;
        }
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
