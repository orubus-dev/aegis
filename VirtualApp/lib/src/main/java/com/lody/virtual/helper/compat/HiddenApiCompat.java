package com.lody.virtual.helper.compat;

import android.content.Context;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import com.lody.virtual.helper.utils.VLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Aegis: hidden-API (non-SDK interface) enforcement bypass.
 *
 * The engine's mirror layer (mirror.**, com.lody.virtual.helper.**) reflects
 * on dozens of internal framework classes.  Since Android 9 that reflection
 * is blocked by the non-SDK interface list; the old workaround
 * (me.weishu.reflection.Reflection.unseal, a.k.a. free_reflection) relies on
 * the double-meta-reflection trick that Google hardened into oblivion — it
 * stopped working reliably on Android 12 and is fully dead on 13+.
 *
 * LSPosed's HiddenApiBypass takes a different route: it uses Unsafe to flip
 * the "hidden" access flag on ArtMethod/ArtField structures directly, which
 * still works on Android 9 through 15 (and is expected to keep working; the
 * bypass only needs `dalvik.system.VMRuntime#setHiddenApiExemptions` reachable
 * via its own ART internals, not via the restricted APIs it exempts).
 *
 * We exempt EVERYTHING by passing the single signature prefix "L" — every
 * Java class descriptor starts with "L", so this whitelists the whole
 * platform.  Exemptions are per-process: this must be called in EVERY
 * process that touches internal APIs (main, server, engine and all guest
 * processes).  VirtualCore.startup() is exactly that choke point — it runs
 * in every process before any engine reflection happens.
 */
public final class HiddenApiCompat {

    private static final String TAG = "HiddenApiCompat";
    private static volatile boolean sApplied;

    private HiddenApiCompat() {
    }

    /**
     * @return true if at least one bypass path reported success on this
     * process.  Guests must treat false as fatal on P+ (engine reflection
     * will otherwise be selectively blocked by the runtime).
     */
    public static synchronized boolean bypass(Context context) {
        if (sApplied) {
            return true;
        }
        boolean ok = false;
        // Path 1 (preferred, P through 15+): LSPosed ArtMethod access-flag patch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ok = HiddenApiBypass.addHiddenApiExemptions("L");
                if (ok) {
                    VLog.i(TAG, "HiddenApiBypass exemption installed (SDK %d).", Build.VERSION.SDK_INT);
                } else {
                    VLog.w(TAG, "HiddenApiBypass.addHiddenApiExemptions returned false (SDK %d).", Build.VERSION.SDK_INT);
                }
            } catch (Throwable t) {
                VLog.w(TAG, "HiddenApiBypass failed on SDK %d: %s", Build.VERSION.SDK_INT, t.getMessage());
            }
        }
        // Path 2 (legacy fallback, P..R only): free_reflection meta-reflection.
        // Harmless to attempt everywhere; on 12+ it usually fails silently.
        try {
            Class<?> reflection = Class.forName("me.weishu.reflection.Reflection");
            Method unseal = reflection.getDeclaredMethod("unseal", Context.class);
            unseal.setAccessible(true);
            unseal.invoke(null, context);
            ok = true;
            VLog.i(TAG, "free_reflection unseal succeeded (SDK %d).", Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            VLog.i(TAG, "free_reflection unseal unavailable (expected on 12+): %s", t.getMessage());
        }
        // Belt and braces: also poke VMRuntime.setHiddenApiExemptions directly
        // through the bypass in case addHiddenApiExemptions took a different
        // internal route than expected on future Android versions.
        if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ok = setHiddenApiExemptionsViaVmRuntime("");
            } catch (Throwable ignored) {
            }
        }
        sApplied = ok;
        return ok;
    }

    /**
     * Direct VMRuntime.setHiddenApiExemptions("") attempt, driven entirely
     * through HiddenApiBypass.invoke so the call itself is exempt from the
     * restriction it is lifting.
     */
    private static boolean setHiddenApiExemptionsViaVmRuntime(String prefix) throws Throwable {
        Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
        Object vmRuntime = HiddenApiBypass.invoke(vmRuntimeClass, null, "getRuntime");
        Object result = HiddenApiBypass.invoke(
                vmRuntimeClass, vmRuntime, "setHiddenApiExemptions", (Object) new String[]{prefix});
        return result == null || !(result instanceof Boolean) || (Boolean) result;
    }

    /** Engine self-check used at bind time in guest processes. */
    public static boolean isEnforcementActive() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        try {
            Class<?> at = mirror.android.app.ActivityThread.TYPE;
            if (at == null) {
                return true;
            }
            Field f = at.getDeclaredField("mPackages");
            f.setAccessible(true);
            return false; // field reachable -> enforcement is not blocking us
        } catch (Throwable t) {
            return true;
        }
    }
}
