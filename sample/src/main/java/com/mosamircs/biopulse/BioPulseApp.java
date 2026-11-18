package com.mosamircs.biopulse;

import android.app.Application;
import android.util.Log;

import com.github.ajalt.reprint.core.Reprint;

public class BioPulseApp extends Application {
    private static final String TAG = "BioPulse";

    @Override
    public void onCreate() {
        super.onCreate();
        Reprint.initialize(this, new Reprint.Logger() {
            @Override
            public void log(String message) {
                Log.d(TAG, message);
            }

            @Override
            public void logException(Throwable throwable, String message) {
                Log.e(TAG, message, throwable);
            }
        });
    }
}
