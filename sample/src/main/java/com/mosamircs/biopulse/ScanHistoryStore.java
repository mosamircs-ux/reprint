package com.mosamircs.biopulse;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight local persistence for biometric benchmark stats and recent scan history.
 */
public class ScanHistoryStore {
    private static final String PREFS_NAME = "biopulse_metrics";
    private static final String KEY_TOTAL = "total_scans";
    private static final String KEY_SUCCESS = "success_scans";
    private static final String KEY_LATENCY_SUM = "latency_sum_ms";
    private static final String KEY_LATENCY_COUNT = "latency_count";
    private static final String KEY_RECORDS = "recent_records_json";
    private static final int MAX_RECORDS = 12;

    public static class Record {
        public final String timestamp;
        public final String engine;
        public final boolean success;
        public final long latencyMs;
        public final String message;

        public Record(String timestamp, String engine, boolean success, long latencyMs, String message) {
            this.timestamp = timestamp;
            this.engine = engine;
            this.success = success;
            this.latencyMs = latencyMs;
            this.message = message;
        }
    }

    private final SharedPreferences prefs;

    public ScanHistoryStore(@NonNull Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void addRecord(@NonNull String engine, boolean success, long latencyMs, @NonNull String message) {
        int total = prefs.getInt(KEY_TOTAL, 0) + 1;
        int ok = prefs.getInt(KEY_SUCCESS, 0) + (success ? 1 : 0);
        long latSum = prefs.getLong(KEY_LATENCY_SUM, 0L);
        int latCount = prefs.getInt(KEY_LATENCY_COUNT, 0);
        if (success && latencyMs > 0) {
            latSum += latencyMs;
            latCount += 1;
        }

        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        List<Record> list = getRecords();
        list.add(0, new Record(time, engine, success, latencyMs, message));
        if (list.size() > MAX_RECORDS) {
            list = list.subList(0, MAX_RECORDS);
        }

        JSONArray arr = new JSONArray();
        for (Record r : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("t", r.timestamp);
                o.put("e", r.engine);
                o.put("s", r.success);
                o.put("l", r.latencyMs);
                o.put("m", r.message);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }

        prefs.edit()
                .putInt(KEY_TOTAL, total)
                .putInt(KEY_SUCCESS, ok)
                .putLong(KEY_LATENCY_SUM, latSum)
                .putInt(KEY_LATENCY_COUNT, latCount)
                .putString(KEY_RECORDS, arr.toString())
                .apply();
    }

    public int getTotalScans() {
        return prefs.getInt(KEY_TOTAL, 0);
    }

    public int getSuccessRatePercent() {
        int total = getTotalScans();
        if (total == 0) return 0;
        int ok = prefs.getInt(KEY_SUCCESS, 0);
        return Math.round((ok * 100f) / total);
    }

    public long getAverageLatencyMs() {
        int count = prefs.getInt(KEY_LATENCY_COUNT, 0);
        if (count == 0) return 0L;
        return prefs.getLong(KEY_LATENCY_SUM, 0L) / count;
    }

    @NonNull
    public List<Record> getRecords() {
        List<Record> result = new ArrayList<>();
        String raw = prefs.getString(KEY_RECORDS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                result.add(new Record(
                        o.optString("t", ""),
                        o.optString("e", ""),
                        o.optBoolean("s", false),
                        o.optLong("l", 0L),
                        o.optString("m", "")
                ));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
