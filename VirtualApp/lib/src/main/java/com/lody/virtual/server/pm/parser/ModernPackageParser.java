package com.lody.virtual.server.pm.parser;

import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.InstrumentationInfo;
import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.Bundle;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Aegis: framework-independent package parser for Android 13+ (API 33+).
 *
 * AOSP removed android.content.pm.PackageParser from the boot classpath in
 * Android 14 (and it is deprecated/unreliable on 13).  The legacy install
 * path reflected into that hidden class, so every install on 13-15 died with
 * "Unable to parse the package."  This parser rebuilds the same VPackage the
 * engine expects, using only:
 *
 *   - PackageManager.getPackageArchiveInfo()  -- public; the platform's own
 *     modern parser (ParsingPackageUtils) does the heavy manifest lifting
 *     (components, meta-data, signatures, permissions).
 *   - AssetManager + openXmlResourceParser()  -- to walk the binary manifest
 *     and collect per-component intent filters and activity-aliases, which
 *     PackageInfo does not expose.  addAssetPath / the empty ctor are hidden
 *     but ancient and reachable via the HiddenApiBypass exemption installed
 *     in every engine process.
 *
 * It never touches android.content.pm.PackageParser, so it works identically
 * on Android 13, 14, 15 and future releases where the class stays deleted.
 */
public final class ModernPackageParser {

    private static final String TAG = "ModernPackageParser";

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String MANIFEST_FILE = "AndroidManifest.xml";

    private static final String TAG_MANIFEST = "manifest";
    private static final String TAG_APPLICATION = "application";
    private static final String TAG_ACTIVITY = "activity";
    private static final String TAG_ACTIVITY_ALIAS = "activity-alias";
    private static final String TAG_SERVICE = "service";
    private static final String TAG_RECEIVER = "receiver";
    private static final String TAG_PROVIDER = "provider";
    private static final String TAG_INTENT_FILTER = "intent-filter";
    private static final String TAG_ACTION = "action";
    private static final String TAG_CATEGORY = "category";
    private static final String TAG_DATA = "data";

    private ModernPackageParser() {
    }

    /**
     * Parse an APK file (or a directory containing base.apk + split APKs)
     * into a VPackage.  Callers must run {@link PackageParserEx#addOwner}
     * on the result before handing it to the package cache.
     */
    public static VPackage parse(File packageFile) throws Throwable {
        File baseApk = packageFile;
        File splitDir = null;
        if (packageFile.isDirectory()) {
            splitDir = packageFile;
            baseApk = findBaseApk(packageFile);
            if (baseApk == null) {
                throw new IOException("No base APK found in " + packageFile);
            }
        }
        if (!baseApk.isFile()) {
            throw new IOException("Package file is not a file: " + baseApk);
        }

        PackageManager pm = VirtualCore.get().getPM();
        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_INSTRUMENTATION
                | PackageManager.GET_PERMISSIONS
                | PackageManager.GET_META_DATA
                | PackageManager.GET_SIGNATURES
                | PackageManager.GET_CONFIGURATIONS;
        PackageInfo pi = pm.getPackageArchiveInfo(baseApk.getAbsolutePath(), flags);
        if (pi == null || pi.applicationInfo == null || pi.packageName == null) {
            throw new IOException("Platform parser returned nothing for " + baseApk);
        }

        // intent filters + aliases come from a manual manifest walk
        Map<String, List<IntentFilter>> filters = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        collectIntentFilters(baseApk, filters, aliases);

        VPackage cache = new VPackage();
        cache.packageName = pi.packageName;
        cache.mVersionCode = pi.versionCode;
        cache.mVersionName = pi.versionName;
        cache.mSharedUserId = pi.sharedUserId;
        cache.mSharedUserLabel = pi.sharedUserLabel;
        cache.applicationInfo = pi.applicationInfo;
        cache.mAppMetaData = pi.applicationInfo.metaData;
        cache.mSignatures = pi.signatures;
        cache.codePath = packageFile.getPath();
        cache.baseCodePath = baseApk.getAbsolutePath();

        cache.requestedPermissions = new ArrayList<>();
        if (pi.requestedPermissions != null) {
            for (String perm : pi.requestedPermissions) {
                if (perm != null) {
                    cache.requestedPermissions.add(perm);
                }
            }
        }
        cache.protectedBroadcasts = new ArrayList<>();

        if (pi.configPreferences != null) {
            cache.configPreferences = new ArrayList<>();
            for (android.content.pm.ConfigurationInfo ci : pi.configPreferences) {
                cache.configPreferences.add(ci);
            }
        }
        if (pi.reqFeatures != null) {
            cache.reqFeatures = new ArrayList<>();
            for (android.content.pm.FeatureInfo fi : pi.reqFeatures) {
                cache.reqFeatures.add(fi);
            }
        }

        cache.activities = new ArrayList<>();
        if (pi.activities != null) {
            for (ActivityInfo ai : pi.activities) {
                if (ai == null || ai.name == null) {
                    continue;
                }
                cache.activities.add(buildActivity(ai, ai.name, ai.metaData,
                        filters.get(normalizeClassName(pi.packageName, ai.name))));
            }
        }
        // activity-aliases: clone the target's info under the alias name.
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            String aliasName = normalizeClassName(pi.packageName, alias.getKey());
            String targetName = normalizeClassName(pi.packageName, alias.getValue());
            ActivityInfo target = findActivityInfo(pi, targetName);
            if (target == null || aliasName == null) {
                continue;
            }
            ActivityInfo clone = new ActivityInfo(target);
            clone.name = aliasName;
            clone.metaData = null;
            cache.activities.add(buildActivity(clone, aliasName, null,
                    filters.get(alias.getKey())));
        }

        cache.receivers = new ArrayList<>();
        if (pi.receivers != null) {
            for (ActivityInfo ai : pi.receivers) {
                if (ai == null || ai.name == null) {
                    continue;
                }
                cache.receivers.add(buildActivity(ai, ai.name, ai.metaData,
                        filters.get(normalizeClassName(pi.packageName, ai.name))));
            }
        }

        cache.services = new ArrayList<>();
        if (pi.services != null) {
            for (ServiceInfo si : pi.services) {
                if (si == null || si.name == null) {
                    continue;
                }
                VPackage.ServiceComponent sc = new VPackage.ServiceComponent();
                sc.info = si;
                sc.className = si.name;
                sc.metaData = si.metaData;
                sc.intents = buildServiceIntentInfos(filters.get(normalizeClassName(pi.packageName, si.name)));
                cache.services.add(sc);
            }
        }

        cache.providers = new ArrayList<>();
        if (pi.providers != null) {
            for (ProviderInfo pri : pi.providers) {
                if (pri == null || pri.name == null) {
                    continue;
                }
                VPackage.ProviderComponent pc = new VPackage.ProviderComponent();
                pc.info = pri;
                pc.className = pri.name;
                pc.metaData = pri.metaData;
                pc.intents = buildProviderIntentInfos(filters.get(normalizeClassName(pi.packageName, pri.name)));
                cache.providers.add(pc);
            }
        }

        cache.instrumentation = new ArrayList<>();
        if (pi.instrumentation != null) {
            for (InstrumentationInfo ii : pi.instrumentation) {
                if (ii == null || ii.name == null) {
                    continue;
                }
                VPackage.InstrumentationComponent ic = new VPackage.InstrumentationComponent();
                ic.info = ii;
                ic.className = ii.name;
                ic.metaData = ii.metaData;
                ic.intents = new ArrayList<>();
                cache.instrumentation.add(ic);
            }
        }

        cache.permissions = new ArrayList<>();
        if (pi.permissions != null) {
            for (PermissionInfo pri : pi.permissions) {
                if (pri == null || pri.name == null) {
                    continue;
                }
                VPackage.PermissionComponent pc = new VPackage.PermissionComponent();
                pc.info = pri;
                pc.className = pri.name;
                pc.metaData = pri.metaData;
                pc.intents = new ArrayList<>();
                cache.permissions.add(pc);
            }
        }

        cache.permissionGroups = new ArrayList<>();
        // permission groups: PackageInfo does not expose them on modern APIs;
        // guest apps essentially never declare custom permission groups.

        // split APKs (only meaningful when installing from a directory)
        if (splitDir != null) {
            List<String> splitNames = new ArrayList<>();
            List<String> splitPaths = new ArrayList<>();
            File[] candidates = splitDir.listFiles();
            if (candidates != null) {
                for (File f : candidates) {
                    if (f.equals(baseApk) || !f.getName().toLowerCase().endsWith(".apk")) {
                        continue;
                    }
                    try {
                        String split = readSplitName(f);
                        if (split != null) {
                            splitNames.add(split);
                            splitPaths.add(f.getAbsolutePath());
                        }
                    } catch (Throwable t) {
                        VLog.w(TAG, "split parse failed: %s (%s)", f.getName(), t.getMessage());
                    }
                }
            }
            if (!splitNames.isEmpty()) {
                cache.splitNames = splitNames.toArray(new String[0]);
                cache.splitCodePaths = splitPaths.toArray(new String[0]);
            }
        }

        VLog.i(TAG, "modern parse ok: %s v%s (%d activities, %d services, %d receivers, %d providers)",
                cache.packageName, cache.mVersionName,
                cache.activities.size(), cache.services.size(),
                cache.receivers.size(), cache.providers.size());
        return cache;
    }

    /**
     * Aegis: heal a VPackage loaded from the parcel cache.  Caches written by
     * builds before intent-filter normalization existed stored activities
     * with EMPTY intent lists (every filter lookup missed), which made the
     * package invisible in the drawer (nothing launchable) while still
     * blocking reinstall with "version downrange".  This re-walks the
     * manifest of the installed base APK and re-attaches the filters.
     *
     * @return {@link #REPAIR_OK_LAUNCHABLE}, {@link #REPAIR_NO_LAUNCHER}
     * (manifest genuinely declares no launcher activity -- fine for
     * plugins/services), or {@link #REPAIR_FAILED} (manifest declares a
     * launcher we could not attach, or the APK is unreadable -> corrupt).
     */
    public static final int REPAIR_OK_LAUNCHABLE = 1;
    public static final int REPAIR_NO_LAUNCHER = 0;
    public static final int REPAIR_FAILED = -1;

    public static int repairIntentFilters(VPackage pkg, File baseApk) {
        if (pkg == null || pkg.packageName == null) {
            return REPAIR_FAILED;
        }
        if (baseApk == null || !baseApk.isFile()) {
            VLog.w(TAG, "repair: base apk missing for " + pkg.packageName);
            return REPAIR_FAILED;
        }
        boolean attachedLauncher = false;
        int attached = 0;
        try {
            Map<String, List<IntentFilter>> filters = new HashMap<>();
            Map<String, String> aliases = new HashMap<>();
            collectIntentFilters(baseApk, filters, aliases);

            if (pkg.activities != null) {
                for (VPackage.ActivityComponent ac : pkg.activities) {
                    if (ac == null || ac.info == null || ac.info.name == null) {
                        continue;
                    }
                    List<IntentFilter> list = filters.get(normalizeClassName(pkg.packageName, ac.info.name));
                    if (list != null && !list.isEmpty()) {
                        ac.intents = buildIntentInfos(list);
                        attached += ac.intents.size();
                        for (IntentFilter f : list) {
                            if (f.hasAction(android.content.Intent.ACTION_MAIN)
                                    && f.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)) {
                                attachedLauncher = true;
                            }
                        }
                    }
                }
            }
            if (pkg.receivers != null) {
                for (VPackage.ActivityComponent rc : pkg.receivers) {
                    if (rc == null || rc.info == null || rc.info.name == null) {
                        continue;
                    }
                    List<IntentFilter> list = filters.get(normalizeClassName(pkg.packageName, rc.info.name));
                    if (list != null && !list.isEmpty()) {
                        rc.intents = buildIntentInfos(list);
                        attached += rc.intents.size();
                    }
                }
            }
            if (pkg.services != null) {
                for (VPackage.ServiceComponent sc : pkg.services) {
                    if (sc == null || sc.info == null || sc.info.name == null) {
                        continue;
                    }
                    List<IntentFilter> list = filters.get(normalizeClassName(pkg.packageName, sc.info.name));
                    if (list != null && !list.isEmpty()) {
                        sc.intents = buildServiceIntentInfos(list);
                        attached += sc.intents.size();
                    }
                }
            }
            if (pkg.providers != null) {
                for (VPackage.ProviderComponent pc : pkg.providers) {
                    if (pc == null || pc.info == null || pc.info.name == null) {
                        continue;
                    }
                    List<IntentFilter> list = filters.get(normalizeClassName(pkg.packageName, pc.info.name));
                    if (list != null && !list.isEmpty()) {
                        pc.intents = buildProviderIntentInfos(list);
                        attached += pc.intents.size();
                    }
                }
            }
        } catch (Throwable t) {
            VLog.w(TAG, "repair walk failed for " + pkg.packageName + ": " + t);
            return REPAIR_FAILED;
        }
        VLog.i(TAG, "repair: " + pkg.packageName + " attached " + attached
                + " filters (launcher=" + attachedLauncher + ")");
        if (attachedLauncher) {
            return REPAIR_OK_LAUNCHABLE;
        }
        // No launcher filter attached. Healthy only when the manifest itself
        // declares no MAIN/LAUNCHER activity; otherwise the cache is corrupt.
        return manifestDeclaresLauncher(baseApk) ? REPAIR_FAILED : REPAIR_NO_LAUNCHER;
    }

    /**
     * true only when the manifest actually declares an activity with a
     * MAIN action + LAUNCHER category.  Unreadable manifests return false
     * (we cannot prove corruption from a read failure alone).
     */
    private static boolean manifestDeclaresLauncher(File baseApk) {
        AssetManager assets = null;
        XmlResourceParser parser = null;
        try {
            assets = newAssetManager(baseApk.getAbsolutePath());
            if (assets == null) {
                return false;
            }
            parser = assets.openXmlResourceParser(MANIFEST_FILE);
            int event;
            boolean inActivity = false;
            boolean sawMain = false;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if (TAG_ACTIVITY.equals(name)) {
                        inActivity = true;
                        sawMain = false;
                    } else if (TAG_ACTION.equals(name) && inActivity) {
                        if (android.content.Intent.ACTION_MAIN.equals(attr(parser, "name"))) {
                            sawMain = true;
                        }
                    } else if (TAG_CATEGORY.equals(name) && inActivity) {
                        if (sawMain && android.content.Intent.CATEGORY_LAUNCHER.equals(attr(parser, "name"))) {
                            return true;
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && TAG_ACTIVITY.equals(parser.getName())) {
                    inActivity = false;
                    sawMain = false;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (parser != null) {
                try {
                    parser.close();
                } catch (Throwable ignored) {
                }
            }
            if (assets != null) {
                try {
                    assets.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static VPackage.ActivityComponent buildActivity(ActivityInfo info, String className,
                                                            Bundle metaData, List<IntentFilter> filterList) {
        VPackage.ActivityComponent ac = new VPackage.ActivityComponent();
        ac.info = info;
        ac.className = className;
        ac.metaData = metaData;
        ac.intents = buildIntentInfos(filterList);
        return ac;
    }

    private static ArrayList<VPackage.ActivityIntentInfo> buildIntentInfos(List<IntentFilter> filterList) {
        ArrayList<VPackage.ActivityIntentInfo> intents = new ArrayList<>();
        if (filterList != null) {
            for (IntentFilter filter : filterList) {
                VPackage.ActivityIntentInfo info = new VPackage.ActivityIntentInfo();
                info.filter = filter;
                info.hasDefault = filter.hasCategory(android.content.Intent.CATEGORY_DEFAULT);
                intents.add(info);
            }
        }
        return intents;
    }

    private static ArrayList<VPackage.ServiceIntentInfo> buildServiceIntentInfos(List<IntentFilter> filterList) {
        ArrayList<VPackage.ServiceIntentInfo> intents = new ArrayList<>();
        if (filterList != null) {
            for (IntentFilter filter : filterList) {
                VPackage.ServiceIntentInfo info = new VPackage.ServiceIntentInfo();
                info.filter = filter;
                info.hasDefault = filter.hasCategory(android.content.Intent.CATEGORY_DEFAULT);
                intents.add(info);
            }
        }
        return intents;
    }

    private static ArrayList<VPackage.ProviderIntentInfo> buildProviderIntentInfos(List<IntentFilter> filterList) {
        ArrayList<VPackage.ProviderIntentInfo> intents = new ArrayList<>();
        if (filterList != null) {
            for (IntentFilter filter : filterList) {
                VPackage.ProviderIntentInfo info = new VPackage.ProviderIntentInfo();
                info.filter = filter;
                info.hasDefault = filter.hasCategory(android.content.Intent.CATEGORY_DEFAULT);
                intents.add(info);
            }
        }
        return intents;
    }

    private static ActivityInfo findActivityInfo(PackageInfo pi, String name) {
        if (pi.activities == null || name == null) {
            return null;
        }
        for (ActivityInfo ai : pi.activities) {
            if (ai != null && name.equals(ai.name)) {
                return ai;
            }
        }
        return null;
    }

    /**
     * Manifests allow relative class names (".Main" or "Main" when the
     * manifest has no leading dot).  The platform parser expands them inside
     * the *Info objects it returns, but our own manifest walk sees the raw
     * attribute values -- normalize here so both worlds agree.
     */
    private static String normalizeClassName(String packageName, String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.startsWith(".")) {
            return packageName + name;
        }
        if (!name.contains(".")) {
            return packageName + "." + name;
        }
        return name;
    }

    private static File findBaseApk(File dir) {
        File base = new File(dir, "base.apk");
        if (base.isFile()) {
            return base;
        }
        File[] apks = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".apk"));
        if (apks == null) {
            return null;
        }
        for (File f : apks) {
            try {
                if (readSplitName(f) == null) {
                    return f;
                }
            } catch (Throwable ignored) {
            }
        }
        return apks.length > 0 ? apks[0] : null;
    }

    /**
     * Reads android:split (or android:isFeatureSplit) from the manifest root,
     * the version-proof way to tell a split APK from a base APK.
     */
    private static String readSplitName(File apk) throws Throwable {
        AssetManager assets = newAssetManager(apk.getAbsolutePath());
        if (assets == null) {
            return null;
        }
        XmlResourceParser parser = null;
        try {
            parser = assets.openXmlResourceParser(MANIFEST_FILE);
            while (parser.next() != XmlPullParser.START_TAG) {
                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    return null;
                }
            }
            String split = attr(parser, "split");
            if (split == null) {
                String feature = attr(parser, "isFeatureSplit");
                if (feature != null && (feature.equals("true") || feature.equals("1"))) {
                    split = attr(parser, "isFeatureSplit");
                }
                // feature splits carry their name in android:isFeatureSplit? no:
                // they declare android:isFeatureSplit plus android:split as well;
                // treat any non-null isFeatureSplit marker as unnamed split.
                if (split == null && feature != null) {
                    split = "feature";
                }
            }
            return split;
        } finally {
            if (parser != null) {
                try {
                    parser.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                assets.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // manifest walk: intent filters + activity aliases
    // ------------------------------------------------------------------

    private static void collectIntentFilters(File apk,
                                             Map<String, List<IntentFilter>> filters,
                                             Map<String, String> aliases) {
        AssetManager assets = null;
        XmlResourceParser parser = null;
        try {
            assets = newAssetManager(apk.getAbsolutePath());
            if (assets == null) {
                VLog.w(TAG, "unable to open assets of %s; intent filters empty", apk);
                return;
            }
            parser = assets.openXmlResourceParser(MANIFEST_FILE);
            walkManifest(parser, filters, aliases);
        } catch (Throwable t) {
            VLog.w(TAG, "manifest walk failed for %s: %s", apk, t.getMessage());
        } finally {
            if (parser != null) {
                try {
                    parser.close();
                } catch (Throwable ignored) {
                }
            }
            if (assets != null) {
                try {
                    assets.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Creates an AssetManager holding exactly one APK.  Uses the hidden
     * empty constructor + addAssetPath (both present on 13-15 and reachable
     * through the engine's hidden-API exemption); falls back to the system
     * AssetManager if the constructor is unavailable.
     */
    private static AssetManager newAssetManager(String apkPath) throws Throwable {
        try {
            AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
            int cookie = (Integer) AssetManager.class
                    .getMethod("addAssetPath", String.class)
                    .invoke(am, apkPath);
            return cookie == 0 ? null : am;
        } catch (Throwable primary) {
            VLog.w(TAG, "hidden AssetManager ctor failed (%s), trying system instance",
                    primary.getMessage());
            AssetManager sys = android.content.res.Resources.getSystem().getAssets();
            int cookie = (Integer) AssetManager.class
                    .getMethod("addAssetPath", String.class)
                    .invoke(sys, apkPath);
            return cookie == 0 ? null : sys;
        }
    }

    private static void walkManifest(XmlPullParser p,
                                     Map<String, List<IntentFilter>> filters,
                                     Map<String, String> aliases) throws Exception {
        boolean inApplication = false;
        String manifestPackage = null; // <manifest android:package=...> value
        String component = null;      // current component class or alias name
        String aliasTarget = null;    // target activity when component is an alias
        List<IntentFilter> pending = null;
        IntentFilter filter = null;

        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            String tag = p.getName();
            if (event == XmlPullParser.START_TAG) {
                if (TAG_MANIFEST.equals(tag)) {
                    // capture the real package name so component names can be
                    // normalized at INSERT time -- the lookup side uses the
                    // platform-expanded names from PackageInfo, so storing raw
                    // ".MainActivity" keys made every lookup miss and left
                    // the whole launcher empty (installed apps never showed).
                    manifestPackage = attr(p, "package");
                } else if (TAG_APPLICATION.equals(tag)) {
                    inApplication = true;
                } else if (inApplication && (TAG_ACTIVITY.equals(tag)
                        || TAG_SERVICE.equals(tag) || TAG_RECEIVER.equals(tag)
                        || TAG_PROVIDER.equals(tag))) {
                    component = normalizeClassName(manifestPackage, attr(p, "name"));
                    aliasTarget = null;
                    pending = new ArrayList<>();
                } else if (inApplication && TAG_ACTIVITY_ALIAS.equals(tag)) {
                    component = normalizeClassName(manifestPackage, attr(p, "name"));
                    aliasTarget = normalizeClassName(manifestPackage, attr(p, "targetActivity"));
                    pending = new ArrayList<>();
                } else if (component != null && TAG_INTENT_FILTER.equals(tag)) {
                    filter = new IntentFilter();
                    int priority = intAttr(p, "priority", 0);
                    if (priority != 0) {
                        filter.setPriority(priority);
                    }
                } else if (filter != null && TAG_ACTION.equals(tag)) {
                    String action = attr(p, "name");
                    if (action != null) {
                        filter.addAction(action);
                    }
                } else if (filter != null && TAG_CATEGORY.equals(tag)) {
                    String category = attr(p, "name");
                    if (category != null) {
                        filter.addCategory(category);
                    }
                } else if (filter != null && TAG_DATA.equals(tag)) {
                    applyData(filter, p);
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (TAG_INTENT_FILTER.equals(tag) && filter != null) {
                    if (pending != null) {
                        pending.add(filter);
                    }
                    filter = null;
                } else if (component != null && (TAG_ACTIVITY.equals(tag)
                        || TAG_ACTIVITY_ALIAS.equals(tag) || TAG_SERVICE.equals(tag)
                        || TAG_RECEIVER.equals(tag) || TAG_PROVIDER.equals(tag))) {
                    if (pending != null && !pending.isEmpty()) {
                        filters.put(component, pending);
                    }
                    if (TAG_ACTIVITY_ALIAS.equals(tag) && component != null && aliasTarget != null) {
                        aliases.put(component, aliasTarget);
                    }
                    component = null;
                    aliasTarget = null;
                    pending = null;
                } else if (TAG_APPLICATION.equals(tag)) {
                    inApplication = false;
                }
            }
        }
    }

    private static void applyData(IntentFilter filter, XmlPullParser p) {
        String mimeType = attr(p, "mimeType");
        if (mimeType != null) {
            try {
                filter.addDataType(mimeType);
            } catch (IntentFilter.MalformedMimeTypeException ignored) {
            }
        }
        String scheme = attr(p, "scheme");
        if (scheme != null) {
            filter.addDataScheme(scheme);
        }
        String host = attr(p, "host");
        if (host != null) {
            String port = attr(p, "port");
            filter.addDataAuthority(host, port);
        }
        addPathData(filter, p, "path", android.os.PatternMatcher.PATTERN_LITERAL);
        addPathData(filter, p, "pathPrefix", android.os.PatternMatcher.PATTERN_PREFIX);
        addPathData(filter, p, "pathPattern", android.os.PatternMatcher.PATTERN_SIMPLE_GLOB);
    }

    private static void addPathData(IntentFilter filter, XmlPullParser p, String name, int type) {
        String value = attr(p, name);
        if (value != null) {
            try {
                filter.addDataPath(value, type);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String ns = p.getAttributeNamespace(i);
            if ((ns != null && ns.equals(ANDROID_NS)) || ns == null || ns.length() == 0) {
                if (name.equals(p.getAttributeName(i))) {
                    return p.getAttributeValue(i);
                }
            }
        }
        return null;
    }

    private static int intAttr(XmlPullParser p, String name, int def) {
        String v = attr(p, name);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
