package com.lody.virtual.client.core;

import com.lody.virtual.client.env.DeadServerException;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.server.IAccountManager;
import com.lody.virtual.server.IActivityManager;
import com.lody.virtual.server.IAppManager;
import com.lody.virtual.server.IBinderDelegateService;
import com.lody.virtual.server.IDeviceInfoManager;
import com.lody.virtual.server.INotificationManager;
import com.lody.virtual.server.IPackageInstaller;
import com.lody.virtual.server.IPackageManager;
import com.lody.virtual.server.IUserManager;
import com.lody.virtual.server.IVirtualLocationManager;
import com.lody.virtual.server.IVirtualStorageService;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Aegis: kill the silent-null AIDL default implementations.
 *
 * Modern AGP AIDL codegen gives every Stub a {@code Default} implementation
 * whose methods return {@code null}/{@code false}.  The generated proxy uses
 * that default whenever {@code transact()} returns false -- which is exactly
 * what happens when a binder transaction fails, e.g. the engine process
 * (":x") is momentarily frozen by the Android 12+ cached-app freezer or a
 * transaction races engine startup.  Before this guard, a frozen-engine
 * install surfaced as a silent {@code null} InstallResult ("Install failed:
 * null result" / an isSuccess NPE) instead of a real error.
 *
 * Installing a THROWING default turns every such transaction failure into a
 * loud, descriptive exception that installers report and log.
 */
public final class AidlGuard {

    private static final String TAG = "AidlGuard";

    private AidlGuard() {
    }

    public static void install() {
        install(IPackageManager.class, "package");
        install(IActivityManager.class, "activity");
        install(IAppManager.class, "app");
        install(INotificationManager.class, "notification");
        install(IAccountManager.class, "account");
        install(IUserManager.class, "user");
        install(IPackageInstaller.class, "installer");
        install(IDeviceInfoManager.class, "device");
        install(IBinderDelegateService.class, "binder-delegate");
        install(IVirtualLocationManager.class, "virtual-loc");
        install(IVirtualStorageService.class, "vs");
    }

    private static void install(Class<?> iface, String label) {
        try {
            Class<?> stub = findStubClass(iface);
            if (stub == null) {
                return;
            }
            Method setter = stub.getMethod("setDefaultImpl", iface);
            Object throwing = Proxy.newProxyInstance(iface.getClassLoader(),
                    new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(proxy, args);
                        }
                        throw new DeadServerException(
                                "Aegis engine transaction failed for "
                                        + method.getName()
                                        + " (" + label + "); engine process busy or frozen");
                    });
            setter.invoke(null, throwing);
        } catch (Throwable t) {
            // Never let the guard break the engine -- worst case we keep the
            // old silent-null behavior.
            VLog.w(TAG, "guard not installed for %s: %s", label, t);
        }
    }

    private static Class<?> findStubClass(Class<?> iface) {
        for (Class<?> inner : iface.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("Stub")) {
                return inner;
            }
        }
        return null;
    }
}
