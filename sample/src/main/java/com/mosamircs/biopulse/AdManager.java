package com.mosamircs.biopulse;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

/**
 * Policy-compliant Google AdMob manager for BioPulse.
 * - Anchored adaptive banner in dedicated footer container.
 * - Frequency-capped interstitial shown only after every 3rd completed sensor test.
 */
public class AdManager {
    private static final String TAG = "BioPulseAds";
    private static final int INTERSTITIAL_SCAN_INTERVAL = 3;

    private final Activity activity;
    private AdView bannerAdView;
    private InterstitialAd interstitialAd;
    private boolean isLoadingInterstitial = false;
    private int completedScansSinceAd = 0;

    public AdManager(@NonNull Activity activity) {
        this.activity = activity;
    }

    public void initializeAndLoadBanner(@NonNull FrameLayout bannerContainer) {
        MobileAds.initialize(activity, initializationStatus -> {
            activity.runOnUiThread(() -> {
                loadAdaptiveBanner(bannerContainer);
                preloadInterstitial();
            });
        });
    }

    private void loadAdaptiveBanner(@NonNull FrameLayout container) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        container.removeAllViews();

        AdView adView = new AdView(activity);
        adView.setAdUnitId(BuildConfig.ADMOB_BANNER_ID);
        adView.setAdSize(getAdaptiveBannerSize(activity));
        container.addView(adView);

        AdRequest request = new AdRequest.Builder().build();
        adView.loadAd(request);
        bannerAdView = adView;
    }

    private AdSize getAdaptiveBannerSize(@NonNull Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int adWidth = (int) (metrics.widthPixels / metrics.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, Math.max(adWidth, 320));
    }

    public void preloadInterstitial() {
        if (isLoadingInterstitial || interstitialAd != null) return;
        isLoadingInterstitial = true;

        AdRequest request = new AdRequest.Builder().build();
        InterstitialAd.load(activity, BuildConfig.ADMOB_INTERSTITIAL_ID, request,
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        interstitialAd = ad;
                        isLoadingInterstitial = false;
                        Log.d(TAG, "Interstitial ad ready");
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        interstitialAd = null;
                        isLoadingInterstitial = false;
                        Log.d(TAG, "Interstitial failed to load: " + loadAdError.getMessage());
                    }
                });
    }

    public void onScanCompleted() {
        completedScansSinceAd++;
        if (completedScansSinceAd < INTERSTITIAL_SCAN_INTERVAL) {
            return;
        }
        if (interstitialAd != null && !activity.isFinishing() && !activity.isDestroyed()) {
            completedScansSinceAd = 0;
            interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    interstitialAd = null;
                    preloadInterstitial();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                    interstitialAd = null;
                    preloadInterstitial();
                }
            });
            interstitialAd.show(activity);
        } else {
            preloadInterstitial();
        }
    }

    public void pause() {
        if (bannerAdView != null) {
            bannerAdView.pause();
        }
    }

    public void resume() {
        if (bannerAdView != null) {
            bannerAdView.resume();
        }
    }

    public void destroy() {
        if (bannerAdView != null) {
            bannerAdView.destroy();
            bannerAdView = null;
        }
        interstitialAd = null;
    }
}
