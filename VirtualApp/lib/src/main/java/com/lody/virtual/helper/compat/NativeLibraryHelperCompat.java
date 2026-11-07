package com.lody.virtual.helper.compat;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.SystemClock;

import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.VLog;

import java.io.File;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import mirror.com.android.internal.content.NativeLibraryHelper;
import mirror.dalvik.system.VMRuntime;

public class NativeLibraryHelperCompat {

        private static String TAG = NativeLibraryHelperCompat.class.getSimpleName();

        public static int copyNativeBinaries(File apkFile, File sharedLibraryDir) {
                int result;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        result = copyNativeBinariesAfterL(apkFile, sharedLibraryDir);
                } else {
                        result = copyNativeBinariesBeforeL(apkFile, sharedLibraryDir);
                }
                if (result != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        // Aegis: the hidden com.android.internal.content.NativeLibraryHelper
                        // may be blocked or removed on newer Android.  Extract the libs
                        // ourselves with plain zip I/O so installs keep working.
                        result = copyNativeBinariesZipFallback(apkFile, sharedLibraryDir);
                }
                return result;
        }

        /**
         * Aegis: framework-free native lib extraction.  Chooses the ABI exactly
         * like the platform does: first entry of Build.SUPPORTED_ABIS (device
         * preference order) that exists inside the APK, then flattens
         * lib/<abi>/*.so into the target dir.
         */
        private static int copyNativeBinariesZipFallback(File apkFile, File sharedLibraryDir) {
                Set<String> abiSet = getABIsFromApk(apkFile.getAbsolutePath());
                if (abiSet == null || abiSet.isEmpty()) {
                        return 0; // pure-Java app, nothing to extract
                }
                String chosen = null;
                for (String abi : Build.SUPPORTED_ABIS) {
                        if (abiSet.contains(abi)) {
                                chosen = abi;
                                break;
                        }
                }
                if (chosen == null) {
                        VLog.w(TAG, "zip fallback: no supported ABI in %s", apkFile.getName());
                        return -1;
                }
                int copied = 0;
                try (ZipFile zip = new ZipFile(apkFile)) {
                        Enumeration<? extends ZipEntry> entries = zip.entries();
                        String prefix = "lib/" + chosen + "/";
                        while (entries.hasMoreElements()) {
                                ZipEntry entry = entries.nextElement();
                                String name = entry.getName();
                                if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".so")) {
                                        continue;
                                }
                                String soName = name.substring(prefix.length());
                                if (soName.isEmpty() || soName.contains("/") || soName.contains("..")) {
                                        continue; // never write outside libDir
                                }
                                File out = new File(sharedLibraryDir, soName);
                                try (java.io.InputStream in = zip.getInputStream(entry);
                                     java.io.FileOutputStream fout = new java.io.FileOutputStream(out)) {
                                        byte[] buf = new byte[65536];
                                        int n;
                                        while ((n = in.read(buf)) > 0) {
                                                fout.write(buf, 0, n);
                                        }
                                }
                                copied++;
                        }
                } catch (Throwable t) {
                        VLog.w(TAG, "zip fallback failed for %s: %s", apkFile.getName(), t.getMessage());
                        return -1;
                }
                VLog.i(TAG, "zip fallback extracted %d libs (%s) from %s", copied, chosen, apkFile.getName());
                return copied > 0 ? 0 : -1;
        }

        private static int copyNativeBinariesBeforeL(File apkFile, File sharedLibraryDir) {
                try {
                        return Reflect.on(NativeLibraryHelper.TYPE).call("copyNativeBinariesIfNeededLI", apkFile, sharedLibraryDir)
                                        .get();
                } catch (Throwable e) {
                        e.printStackTrace();
                }
                return -1;
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        private static int copyNativeBinariesAfterL(File apkFile, File sharedLibraryDir) {
                try {
                        Object handle = NativeLibraryHelper.Handle.create.call(apkFile);
                        if (handle == null) {
                                return -1;
                        }

                        String abi = null;
                        Set<String> abiSet = getABIsFromApk(apkFile.getAbsolutePath());
                        if (abiSet == null || abiSet.isEmpty()) {
                                return 0;
                        }
                        boolean is64Bit = VMRuntime.is64Bit.call(VMRuntime.getRuntime.call());
                        if (is64Bit && isVM64(abiSet)) {
                                if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
                                        int abiIndex = NativeLibraryHelper.findSupportedAbi.call(handle, Build.SUPPORTED_64_BIT_ABIS);
                                        if (abiIndex >= 0) {
                                                abi = Build.SUPPORTED_64_BIT_ABIS[abiIndex];
                                        }
                                }
                        } else {
                                if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                                        int abiIndex = NativeLibraryHelper.findSupportedAbi.call(handle, Build.SUPPORTED_32_BIT_ABIS);
                                        if (abiIndex >= 0) {
                                                abi = Build.SUPPORTED_32_BIT_ABIS[abiIndex];
                                        }
                                }
                        }

                        if (abi == null) {
                                VLog.e(TAG, "Not match any abi [%s].", apkFile.getPath());
                                return -1;
                        }
                        return NativeLibraryHelper.copyNativeBinaries.call(handle, sharedLibraryDir, abi);
                } catch (Throwable e) {
                        VLog.d(TAG, "copyNativeBinaries with error : %s", e.getLocalizedMessage());
                        e.printStackTrace();
                }

                return -1;
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        private static boolean isVM64(Set<String> supportedABIs) {
                if (Build.SUPPORTED_64_BIT_ABIS.length == 0) {
                        return false;
                }

                if (supportedABIs == null || supportedABIs.isEmpty()) {
                        return true;
                }

                for (String supportedAbi : supportedABIs) {
                        if ("arm64-v8a".endsWith(supportedAbi) || "x86_64".equals(supportedAbi) || "mips64".equals(supportedAbi)) {
                                return true;
                        }
                }

                return false;
        }

        private static Set<String> getABIsFromApk(String apk) {
                try (ZipFile apkFile = new ZipFile(apk)) {
                        Enumeration<? extends ZipEntry> entries = apkFile.entries();
                        Set<String> supportedABIs = new HashSet<String>();
                        while (entries.hasMoreElements()) {
                                ZipEntry entry = entries.nextElement();
                                String name = entry.getName();
                                if (name.contains("../")) {
                                        continue;
                                }
                                if (name.startsWith("lib/") && !entry.isDirectory() && name.endsWith(".so")) {
                                        String supportedAbi = name.substring(name.indexOf("/") + 1, name.lastIndexOf("/"));
                                        supportedABIs.add(supportedAbi);
                                }
                        }
                        return supportedABIs;
                } catch (Exception e) {
                        e.printStackTrace();
                }

                return null;
        }

        public static boolean isApk64(String apk) {
                long start = SystemClock.elapsedRealtime();
                Set<String> abIsFromApk = getABIsFromApk(apk);
                System.out.println("pkg: " + apk + " abis: " + abIsFromApk + " cost: " + (SystemClock.elapsedRealtime() - start));
                return isVM64(abIsFromApk);
        }
}
