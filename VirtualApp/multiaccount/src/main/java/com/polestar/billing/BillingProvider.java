package com.polestar.billing;

/**
 * Created by guojia on 2017/6/25.
 */

import android.os.Handler;
import android.os.Looper;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.polestar.superclone.MApp;
import com.polestar.superclone.utils.MLogs;
import com.polestar.superclone.utils.PreferencesUtils;

import java.util.List;

/**
 * An interface that provides an access to BillingLibrary methods
 */
public class BillingProvider {
    private static BillingProvider sInstance;
    private BillingManager manager;
    private final String TAG = "BillingProvider";

    private OnStatusUpdatedListener statusUpdatedListener;

    private boolean isVIP;

    private BillingProvider() {
        manager = new BillingManager(MApp.getApp(), new BillingManager.BillingUpdatesListener() {
            @Override
            public void onBillingClientSetupFinished() {
                MLogs.d(TAG, "onBillingClientSetupFinished");
            }

            @Override
            public void onConsumeFinished(String token, @BillingClient.BillingResponse int result) {

            }

            @Override
            public void onPurchasesUpdated(List<Purchase> purchases) {
                isVIP = false;
                for (Purchase purchase : purchases) {
                    MLogs.d(TAG, "SKU:  " + purchase);
                    if (purchase.isAutoRenewing()) {
                        MLogs.d(TAG, "Got a AD free version!!! ");
                        isVIP = true;
                        break;
                    }
                    switch (purchase.getSku()) {
                        case BillingConstants.VIP_1_MONTH:
                            if ((System.currentTimeMillis() - purchase.getPurchaseTime()) < 30*24*3600) {
                                isVIP = true;
                            }
                            break;
                        case BillingConstants.VIP_3_MONTH:
                            if ((System.currentTimeMillis() - purchase.getPurchaseTime()) < 3*30*24*3600) {
                                isVIP = true;
                            }
                            break;
                        case BillingConstants.VIP_1_YEAR:
                            if ((System.currentTimeMillis() - purchase.getPurchaseTime()) < 12*30*24*3600) {
                                isVIP = true;
                            }
                            break;
                        default:
                            break;
                    }
                }
                PreferencesUtils.setVIP(isVIP);
                if (statusUpdatedListener != null) {
                    new Handler(Looper.myLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (statusUpdatedListener != null) {
                                statusUpdatedListener.onStatusUpdated();
                            }
                        }
                    });
                }
            }
        });
    }

    synchronized public static BillingProvider get() {
        if (sInstance == null ) {
            sInstance = new BillingProvider();
        }
        return  sInstance;
    }

    public BillingManager getBillingManager() {
        return manager;
    }

    public interface OnStatusUpdatedListener {
        void onStatusUpdated();
    }

    public void updateStatus(OnStatusUpdatedListener listener) {
        statusUpdatedListener = listener;
        if (manager != null) {
            manager.queryPurchases();
        }
    }

    public void querySkuDetails( final String itemType, final SkuDetailsResponseListener listener){
        if (manager != null) {
            manager.querySkuDetailsAsync(itemType, BillingConstants.getSkuList(itemType), listener);
        }
    }
}