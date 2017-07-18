package com.polestar.domultiple;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.delegate.PhoneInfoDelegate;
import com.lody.virtual.client.stub.StubManifest;
import com.lody.virtual.helper.utils.VLog;
import com.polestar.ad.AdConstants;
import com.polestar.domultiple.billing.BillingProvider;
import com.polestar.domultiple.clone.CloneApiDelegate;
import com.polestar.domultiple.clone.CloneComponentDelegate;
import com.polestar.domultiple.utils.CommonUtils;
import com.polestar.domultiple.utils.EventReporter;
import com.polestar.domultiple.utils.MLogs;
import com.polestar.domultiple.utils.PreferencesUtils;
import com.polestar.domultiple.utils.RemoteConfig;
import com.tencent.bugly.crashreport.CrashReport;

import java.io.File;

/**
 * Created by guojia on 2017/7/15.
 */

public class PolestarApp extends Application {

    private static PolestarApp gDefault;

    public static PolestarApp getApp() {
        return gDefault;
    }

    public static boolean isOpenLog(){
        File file = new File(Environment.getExternalStorageDirectory() + "/polelog");
        boolean ret =  file.exists();
        if(ret) {
            Log.d(MLogs.DEFAULT_TAG, "log opened by file");
        }
        return  ret;
    }

    @Override
    protected void attachBaseContext(Context base) {
        Log.d(MLogs.DEFAULT_TAG, "APP version: " + BuildConfig.VERSION_NAME + " Type: " + BuildConfig.BUILD_TYPE);
        Log.d(MLogs.DEFAULT_TAG, "LIB version: " + com.lody.virtual.BuildConfig.VERSION_NAME + " Type: " + com.lody.virtual.BuildConfig.BUILD_TYPE );

        super.attachBaseContext(base);
        gDefault = this;
        try {
            StubManifest.ENABLE_IO_REDIRECT = true;
            StubManifest.ENABLE_INNER_SHORTCUT = false;
            StubManifest.ENABLE_GMS = PreferencesUtils.isGMSEnable();
            Log.d(MLogs.DEFAULT_TAG, "GMS state: " + StubManifest.ENABLE_GMS);
            VirtualCore.get().startup(base);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        VirtualCore virtualCore = VirtualCore.get();
        virtualCore.initialize(new VirtualCore.VirtualInitializer() {

            @Override
            public void onMainProcess() {
                MLogs.d("Main process create");
//                AdUtils.initMVSDK("33047", "e4a6e0bf98078d3fa81ca6d315c28123", gDefault);
//                AdUtils.preloadAppWall(AppConstants.WALL_UNIT_ID);

                FirebaseApp.initializeApp(gDefault);
                RemoteConfig.init();
                //ImageLoaderUtil.init(gDefault);
                initRawData();
                //registerActivityLifecycleCallbacks(new LocalActivityLifecycleCallBacks(MApp.this, true));
                EventReporter.init(gDefault);
                BillingProvider.get();
                //
            }

            @Override
            public void onVirtualProcess() {
                MLogs.d("Virtual process create");
                CloneComponentDelegate delegate = new CloneComponentDelegate();
                delegate.init();
                virtualCore.setComponentDelegate(delegate);
                virtualCore.setPhoneInfoDelegate(new MyPhoneInfoDelegate());

                virtualCore.setAppApiDelegate(new CloneApiDelegate());
            }

            @Override
            public void onServerProcess() {
                MLogs.d("Server process create");
                VirtualCore.get().setAppRequestListener(new VirtualCore.AppRequestListener() {
                    @Override
                    public void onRequestInstall(String path) {
                        //We can start AppInstallActivity TODO
                    }

                    @Override
                    public void onRequestUninstall(String pkg) {

                    }
                });
                FirebaseApp.initializeApp(gDefault);
                RemoteConfig.init();
                MLogs.d("Server process app onCreate 0");
                CloneComponentDelegate delegate = new CloneComponentDelegate();
                delegate.init();
                MLogs.d("Server process app onCreate 1");
                VirtualCore.get().setComponentDelegate(delegate);
                MLogs.d("Server process app onCreate 2");
                MLogs.d("Server process app onCreate done");
            }
        });

        try {
            // init exception handler and bugly before attatchBaseContext and appOnCreate
            setDefaultUncaughtExceptionHandler(this);
            initBugly(gDefault);
        }catch (Exception e){
            e.printStackTrace();
        }

        if (isOpenLog() || !AppConstants.IS_RELEASE_VERSION ) {
            VLog.openLog();
            VLog.d(MLogs.DEFAULT_TAG, "VLOG is opened");
            MLogs.DEBUG = true;
            AdConstants.DEBUG = true;
        }
        VLog.setKeyLogger(new VLog.IKeyLogger() {
            @Override
            public void keyLog(Context context, String tag, String log) {
                MLogs.logBug(tag,log);
                EventReporter.keyLog(gDefault, tag, log);
            }

            @Override
            public void logBug(String tag, String log) {
                MLogs.logBug(tag, log);
            }
        });
    }

    private class MAppCrashHandler implements Thread.UncaughtExceptionHandler {

        private Context context;
        private Thread.UncaughtExceptionHandler orig;
        MAppCrashHandler(Context c, Thread.UncaughtExceptionHandler orig) {
            context = c;
            this.orig = orig;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            MLogs.logBug("uncaughtException");

            String pkg;
            int tag;
            //CrashReport.startCrashReport();
            //1. innerContext = null, internal error in Pb
            if (VirtualCore.get() != null
                    && (VirtualCore.get().isMainProcess() )) {
                MLogs.logBug("Super Clone main app exception, exit.");
                pkg = "main";
                tag = AppConstants.CrashTag.MAPP_CRASH;
                //CrashReport.setUserSceneTag(context, AppConstants.CrashTag.MAPP_CRASH);
            } else if(VirtualCore.get()!= null && VirtualCore.get().isServerProcess()){
                MLogs.logBug("Server process crash!");
                pkg = "server";
                tag = AppConstants.CrashTag.SERVER_CRASH;
                //CrashReport.setUserSceneTag(context, AppConstants.CrashTag.SERVER_CRASH);
            } else {
                MLogs.logBug("Client process crash!");
                pkg = VClientImpl.get() == null? null: VClientImpl.get().getCurrentPackage();
                tag = AppConstants.CrashTag.CLONE_CRASH;
                //CrashReport.setUserSceneTag(context, AppConstants.CrashTag.CLONE_CRASH);
            }
            MLogs.logBug(MLogs.getStackTraceString(ex));

            ActivityManager.RunningAppProcessInfo info = CommonUtils.getForegroundProcess(context);
            boolean forground = false;
            if (info != null && android.os.Process.myPid() == info.pid) {
                MLogs.logBug("forground crash");
                forground = true;
                //CrashReport.setUserSceneTag(context, AppConstants.CrashTag.FG_CRASH);
            }
            Intent crash = new Intent("appclone.intent.action.SHOW_CRASH_DIALOG");
            crash.putExtra("package", pkg);
            crash.putExtra("forground", forground);
            crash.putExtra("exception", ex);
            crash.putExtra("tag", tag);
            sendBroadcast(crash);
            //CrashReport.postCatchedException(ex);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }
    }
    private void setDefaultUncaughtExceptionHandler(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new MAppCrashHandler(context, Thread.getDefaultUncaughtExceptionHandler()));
    }

    private void initRawData() {
    }

    private void initBugly(Context context) {
        //Bugly
        String channel = CommonUtils.getMetaDataInApplicationTag(context, "CHANNEL_NAME");
        AppConstants.IS_RELEASE_VERSION = !channel.equals(AppConstants.DEVELOP_CHANNEL);
        MLogs.e("IS_RELEASE_VERSION: " + AppConstants.IS_RELEASE_VERSION);

        MLogs.e("versioncode: " + CommonUtils.getCurrentVersionCode(context) + ", versionName:" + CommonUtils.getCurrentVersionName(context));
        CrashReport.UserStrategy strategy = new CrashReport.UserStrategy(context);
        String referChannel = PreferencesUtils.getInstallChannel();
        strategy.setAppChannel(referChannel == null? channel : referChannel);
        CrashReport.initCrashReport(context, "12a06457f1", !AppConstants.IS_RELEASE_VERSION, strategy);
        // close auto report, manual control
        MLogs.e("bugly channel: " + channel + " referrer: "+ referChannel);
        CrashReport.closeCrashReport();
    }

    class MyPhoneInfoDelegate implements PhoneInfoDelegate {

        @Override
        public String getDeviceId(String oldDeviceId, int userId) {
            return oldDeviceId;
        }

        @Override
        public String getBluetoothAddress(String oldAddress, int userId) {
            return oldAddress;
        }

        @Override
        public String getMacAddress(String oldMacAddress, int userId) {
            if (oldMacAddress == null || oldMacAddress.startsWith("00-00-00-00-00-00") ){
                return "00:00:08:76:54:32";
            }
            return oldMacAddress;
        }

    }
}