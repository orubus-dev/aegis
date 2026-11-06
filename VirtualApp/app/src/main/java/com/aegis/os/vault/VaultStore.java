package com.aegis.os.vault;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Aegis Vault storage: files live in the host app's private dir
 * (files/aegis_vault/), each named by a random 128-bit hex id.  No
 * permissions are needed -- nothing outside Aegis can even see the files,
 * and inside them every byte is AES-256-GCM (see VaultCrypto).
 */
public final class VaultStore {

    private VaultStore() {
    }

    public static File vaultDir(Context context) {
        File dir = new File(context.getFilesDir(), "aegis_vault");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File newVaultFile(Context context) {
        return new File(vaultDir(context), VaultCrypto.newId() + ".avx");
    }

    public static List<File> list(Context context) {
        File[] files = vaultDir(context).listFiles();
        List<File> out = new ArrayList<>();
        if (files != null) {
            out.addAll(Arrays.asList(files));
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    public static void delete(Context context, File f) {
        VaultCrypto.shred(f);
    }

    /** Temp dir for decrypt-on-view; contents shredded on exit. */
    public static File viewCacheDir(Context context) {
        File dir = new File(context.getCacheDir(), "aegis_vault_view");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static void shredViewCache(Context context) {
        File dir = viewCacheDir(context);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                VaultCrypto.shred(f);
            }
        }
        dir.delete();
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }
}
