package com.lody.virtual.server.pm;

import android.content.pm.PackageManager;

import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Aegis simulated-permission store.
 *
 * The sandbox simulates Android's permission system for every guest app:
 * each guest has its own grant state, independent of the host device.
 * The authoritative state lives in one JSON file inside the engine's
 * private data area, so the settings UI and the engine process (which may
 * run in a separate ":x" process) always agree.
 *
 * Semantics of {@link #checkPermission} when simulation is ON:
 *  - permission never requested by the guest        -> DENIED  (like real Android)
 *  - requested + no explicit override               -> GRANTED (safe default,
 *                                                      matches how the engine
 *                                                      behaved before)
 *  - requested + explicit override                  -> the override
 *
 * Overrides are managed from the Aegis OS "Permissions" tab.
 */
public final class AegisPermissionStore {

    private static final String TAG = "AegisPermissionStore";

    private static final Object LOCK = new Object();
    private static JSONObject sState;
    private static boolean sLoaded;

    private AegisPermissionStore() {
    }

    private static File storeFile() {
        return new File(VEnvironment.getUserSystemDirectory(), "aegis_permissions.json");
    }

    private static JSONObject state() {
        synchronized (LOCK) {
            if (sLoaded) {
                return sState;
            }
            sLoaded = true;
            JSONObject obj = new JSONObject();
            File f = storeFile();
            if (f.exists()) {
                FileInputStream in = null;
                try {
                    // Read fully: a single read() can return early and truncate
                    // the JSON, which then poisons every later parse.
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    in = new FileInputStream(f);
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        bos.write(chunk, 0, n);
                    }
                    obj = new JSONObject(bos.toString("UTF-8"));
                } catch (Throwable t) {
                    VLog.w(TAG, "unreadable permission store, starting clean: " + t);
                    obj = new JSONObject();
                } finally {
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!obj.has("sim")) {
                try {
                    obj.put("sim", true);
                } catch (Throwable ignored) {
                }
            }
            if (!obj.has("overrides")) {
                try {
                    obj.put("overrides", new JSONObject());
                } catch (Throwable ignored) {
                }
            }
            sState = obj;
            return obj;
        }
    }

    private static void persistLocked() {
        FileOutputStream out = null;
        try {
            File f = storeFile();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            out = new FileOutputStream(tmp);
            out.write(sState.toString().getBytes("UTF-8"));
            out.close();
            if (f.exists() && !f.delete()) {
                VLog.w(TAG, "unable to replace permission store");
            }
            if (!tmp.renameTo(f)) {
                VLog.w(TAG, "unable to commit permission store");
            }
        } catch (Throwable t) {
            VLog.w(TAG, "persist failed: " + t);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean isSimulationEnabled() {
        return state().optBoolean("sim", true);
    }

    public static void setSimulationEnabled(boolean enabled) {
        synchronized (LOCK) {
            try {
                state().put("sim", enabled);
                persistLocked();
            } catch (Throwable ignored) {
            }
        }
    }

    private static JSONObject overridesRoot() {
        synchronized (LOCK) {
            JSONObject root = sState == null ? null : sState.optJSONObject("overrides");
            if (root == null) {
                // A corrupt or missing "overrides" node must never NPE the
                // engine process -- self-heal it in place instead.
                root = new JSONObject();
                try {
                    state().put("overrides", root);
                } catch (Throwable ignored) {
                }
            }
            return root;
        }
    }

    private static String key(String pkg, int userId) {
        return pkg + "#" + userId;
    }

    /**
     * @return {@link PackageManager#PERMISSION_GRANTED},
     *         {@link PackageManager#PERMISSION_DENIED}, or
     *         {@link #VERDICT_UNSET} when the guest never requested it.
     */
    public static final int VERDICT_UNSET = 0;

    public static int checkPermission(String permName, String pkgName, int userId) {
        try {
            return checkPermissionInner(permName, pkgName, userId);
        } catch (Throwable t) {
            // This runs on the engine's binder thread for EVERY guest
            // permission check; a throw here would take the whole sandbox
            // down ("keeps stopping").  Fall back to the legacy verdict.
            VLog.w(TAG, "checkPermission failed, falling back to granted: " + t);
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    private static int checkPermissionInner(String permName, String pkgName, int userId) {
        if (permName == null || pkgName == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        JSONObject overridesRoot = overridesRoot();
        synchronized (LOCK) {
            JSONObject perApp = overridesRoot.optJSONObject(key(pkgName, userId));
            if (perApp != null && perApp.has(permName)) {
                return perApp.optBoolean(permName, true)
                        ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED;
            }
        }
        // No explicit override: grant what the guest actually requested,
        // deny what it never asked for (real Android behavior).
        com.lody.virtual.server.pm.parser.VPackage pkg = PackageCacheManager.get(pkgName);
        if (pkg == null || pkg.requestedPermissions == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        for (String p : pkg.requestedPermissions) {
            if (permName.equals(p)) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }
        return VERDICT_UNSET;
    }

    /** Map of permission -> granted state for one app (may be empty). */
    public static Map<String, Boolean> overridesFor(String pkgName, int userId) {
        Map<String, Boolean> out = new HashMap<>();
        try {
            synchronized (LOCK) {
                JSONObject perApp = overridesRoot().optJSONObject(key(pkgName, userId));
                if (perApp != null) {
                    Iterator<String> it = perApp.keys();
                    while (it.hasNext()) {
                        String p = it.next();
                        out.put(p, perApp.optBoolean(p, true));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void setOverride(String pkgName, int userId, String permName, boolean granted) {
        synchronized (LOCK) {
            try {
                String k = key(pkgName, userId);
                JSONObject root = overridesRoot();
                JSONObject perApp = root.optJSONObject(k);
                if (perApp == null) {
                    perApp = new JSONObject();
                    root.put(k, perApp);
                }
                perApp.put(permName, granted);
                persistLocked();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void clearOverride(String pkgName, int userId, String permName) {
        synchronized (LOCK) {
            try {
                JSONObject perApp = overridesRoot().optJSONObject(key(pkgName, userId));
                if (perApp != null && perApp.has(permName)) {
                    perApp.remove(permName);
                    persistLocked();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
