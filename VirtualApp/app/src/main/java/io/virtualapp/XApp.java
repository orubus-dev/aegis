package io.virtualapp;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.stub.VASettings;

import io.virtualapp.delegate.MyVirtualInitializer;

/**
 * @author Lody
 */
public class XApp extends Application {

    private static final String TAG = "XApp";

    public static final String XPOSED_INSTALLER_PACKAGE = "de.robv.android.xposed.installer";

    private static XApp gApp;

    public static XApp getApp() {
        return gApp;
    }

    @Override
    protected void attachBaseContext(Context base) {
        // Aegis: the black box goes in FIRST -- engine startup crashes
        // (attachBaseContext) must be recorded too, not only UI crashes.
        installCrashBlackBox();
        gApp = this;
        super.attachBaseContext(base);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NativeEngine.disableJit(Build.VERSION.SDK_INT);
        }
        VASettings.ENABLE_IO_REDIRECT = true;
        VASettings.ENABLE_INNER_SHORTCUT = false;
        try {
            VirtualCore.get().startup(base);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // (crash black box already installed in attachBaseContext)
        VirtualCore virtualCore = VirtualCore.get();
        virtualCore.initialize(new MyVirtualInitializer(this, virtualCore));
    }

    /**
     * Aegis crash black box.  Field testers have no adb/logcat: the only way
     * a "keeps stopping" report becomes actionable is if the stack trace is
     * written to the persistent diagnostics file at the moment of death.
     * Installed in Application.onCreate so every Aegis process is covered
     * (main UI, engine ":x" and guest ":p" processes all use XApp).
     */
    private void installCrashBlackBox() {
        try {
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    try {
                        com.aegis.os.Diagnostics.logCrash(XApp.this, thread, throwable);
                    } catch (Throwable ignored) {
                        // last resort must never itself crash
                    }
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

}
