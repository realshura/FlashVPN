package com.polestar.ad.adapters;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import com.ironsource.mediationsdk.IronSource;
import com.ironsource.mediationsdk.logger.IronSourceError;
import com.ironsource.mediationsdk.model.Placement;
import com.ironsource.mediationsdk.sdk.RewardedVideoListener;
import com.polestar.ad.AdConstants;
import com.polestar.ad.AdLog;

public class IronSourceRewardVideoAdapter extends AdAdapter {

    public IronSourceRewardVideoAdapter(Context context, String key) {
        mKey = key; //placement_id
    }
    @Override
    public void registerPrivacyIconView(View view) {

    }

    @Override
    public void loadAd(Context context, int num, IAdLoadListener listener) {
        adListener = listener;
        if (listener == null) {
            AdLog.e("Not set listener!");
            return;
        }
        IronSource.setRewardedVideoListener(new RewardedVideoListener() {
            @Override
            public void onRewardedVideoAdOpened() {
                AdLog.d("onRewardedVideoAdOpened");
            }

            @Override
            public void onRewardedVideoAdClosed() {
                if (adListener != null) {
                    adListener.onAdClosed(IronSourceRewardVideoAdapter.this);
                }
            }

            @Override
            public void onRewardedVideoAvailabilityChanged(boolean b) {
                if (b) {
                    AdLog.d("onRewardedVideoAdLoaded");
                    stopMonitor();
                    mLoadedTime = System.currentTimeMillis();
                    if(adListener != null) {
                        adListener.onAdLoaded(IronSourceRewardVideoAdapter.this);
                    }
                } else {
                    if (adListener != null) {
                        adListener.onError("Not Available");
                    }
                    stopMonitor();
                }
            }

            @Override
            public void onRewardedVideoAdStarted() {
                AdLog.d("onRewardedVideoAdStarted");
            }

            @Override
            public void onRewardedVideoAdEnded() {
                AdLog.d("onRewardedVideoAdEnded");
            }

            @Override
            public void onRewardedVideoAdRewarded(Placement placement) {
                AdLog.d("onRewardedVideoAdRewarded");
                if (adListener != null) {
                    adListener.onRewarded(IronSourceRewardVideoAdapter.this);
                }
            }

            @Override
            public void onRewardedVideoAdShowFailed(IronSourceError ironSourceError) {
                AdLog.d("onRewardedVideoAdShowFailed");
            }

            @Override
            public void onRewardedVideoAdClicked(Placement placement) {
                AdLog.d("onRewardedVideoAdClicked");
            }
        });
        if (IronSource.isRewardedVideoAvailable()) {
            mLoadedTime = System.currentTimeMillis();
            if(adListener != null) {
                adListener.onAdLoaded(IronSourceRewardVideoAdapter.this);
            }
        } else {
            startMonitor();
        }
    }

    @Override
    public boolean isInterstitialAd() {
        return true;
    }


    @Override
    public Object getAdObject() {
        return this;
    }

    @Override
    public String getAdType() {
        return AdConstants.AdType.AD_SOURCE_IRONSOURCE_REWARD;
    }

    @Override
    protected void onTimeOut() {
        if (adListener != null) {
            adListener.onError("TIME_OUT");
        }
    }

    @Override
    public void show() {
        if (IronSource.isRewardedVideoAvailable()) {
            IronSource.showRewardedVideo(mKey);
            registerViewForInteraction(null);
        }
    }


    @Override
    public void onActivityResume(Activity activity) {
        IronSource.onResume(activity);
    }

    @Override
    public void onActivityPause(Activity activity) {
        IronSource.onPause(activity);
    }
}