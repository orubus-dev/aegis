package com.aegis.os.vault;

import android.content.Context;

import com.aegis.os.AegisPrefs;
import com.aegis.os.Diagnostics;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Aegis M4: guest media auto-vault.
 *
 * Guest apps write photos/videos into Aegis' virtual storage.  This scanner
 * moves any NEW media files into the encrypted vault (AES-256-GCM, hardware
 * keystore key) and securely shreds the unencrypted original, so media that
 * guests produce is never left in plaintext inside the container.
 *
 * The scan runs when the vault (or Aegis OS settings) is opened while the
 * toggle is on -- no engine hooks, no background services, nothing that
 * could break guests.
 */
public final class VaultAutoImport {

    public static final String PREF_ENABLED = "vault_auto_import_enabled";

    private static final String TAG = "VaultAutoImport";
    private static final String MARKER_DIR = ".guest-imports";
    private static final long MAX_SOURCE_BYTES = 512L * 1024 * 1024;

    private static final String[] MEDIA_EXTENSIONS = {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic",
            "mp4", "mov", "webm", "m4v", "3gp", "mkv"
    };

    private VaultAutoImport() {
    }

    public static boolean isEnabled(Context ctx) {
        return AegisPrefs.getBoolean(ctx, PREF_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        AegisPrefs.putBoolean(ctx, PREF_ENABLED, enabled);
    }

    /**
     * Scans the guest virtual storage for new media and imports it into the
     * encrypted vault.  Returns the number of files imported.
     */
    public static int importNewGuestMedia(Context ctx) {
        if (!isEnabled(ctx)) {
            return 0;
        }
        List<File> roots = new ArrayList<>();
        File vsBase = VEnvironment.getVirtualStorageBaseDir();
        if (vsBase != null && vsBase.exists()) {
            roots.add(vsBase);
        }
        // guest private data dirs (apps that write into their own storage)
        File userData = VEnvironment.getUserSystemDirectory(0);
        if (userData != null && userData.exists()) {
            File[] pkgs = userData.listFiles();
            if (pkgs != null) {
                roots.addAll(Arrays.asList(pkgs));
            }
        }

        File markerDir = new File(VaultStore.vaultDir(ctx), MARKER_DIR);
        if (!markerDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            markerDir.mkdirs();
        }

        int imported = 0;
        for (File root : roots) {
            try {
                imported += scanTree(ctx, root, markerDir, 0);
            } catch (Throwable t) {
                VLog.w(TAG, "scan failed under %s: %s", root, t);
            }
        }
        if (imported > 0) {
            Diagnostics.log(ctx, "vault auto-import: moved " + imported
                    + " guest media file(s) into the encrypted vault");
        }
        return imported;
    }

    private static int scanTree(Context ctx, File dir, File markerDir, int depth) {
        if (dir == null || depth > 6) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File f : children) {
            if (f.isDirectory()) {
                if (".guest-imports".equals(f.getName())) {
                    continue;
                }
                count += scanTree(ctx, f, markerDir, depth + 1);
            } else if (isMediaFile(f.getName())) {
                if (importOne(ctx, f, markerDir)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean importOne(Context ctx, File source, File markerDir) {
        try {
            String markerName = markerNameFor(source);
            File marker = new File(markerDir, markerName);
            if (marker.exists()) {
                // already imported this exact file
                return false;
            }
            if (source.length() == 0 || source.length() > MAX_SOURCE_BYTES) {
                return false;
            }

            // Copy to a temp file first so the vault encrypts from a stable
            // snapshot even if the guest is mid-write.
            File tmp = new File(VaultStore.vaultDir(ctx),
                    "autoimport_" + VaultCrypto.newId());
            copy(source, tmp);

            // Aegis: scrub identifying metadata (GPS, device model, ...) from
            // imported JPEGs before they are sealed into the vault.
            if (AegisScrubber.isJpeg(tmp)) {
                AegisScrubber.scrubImage(tmp);
            }

            File target = new File(VaultStore.vaultDir(ctx),
                    "guest_" + VaultCrypto.newId());
            String name = source.getName();
            String mime = guessMime(name);
            VaultCrypto.encryptFile(ctx, tmp, target, name, mime,
                    source.lastModified());
            VaultCrypto.shred(tmp);

            // shred the unencrypted guest original, then record the marker
            VaultCrypto.shred(source);
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
            return true;
        } catch (Throwable t) {
            VLog.w(TAG, "auto import failed for %s: %s", source, t);
            return false;
        }
    }

    private static boolean isMediaFile(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        return Arrays.asList(MEDIA_EXTENSIONS).contains(ext);
    }

    /**
     * Marker key: stable hash of the absolute path + size.  A rewritten file
     * (new size) gets imported again; an unchanged file never does.
     */
    private static String markerNameFor(File f) {
        String key = f.getAbsolutePath() + ":" + f.length();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 32);
        } catch (Throwable t) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".m4v")) return "video/x-m4v";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        return "application/octet-stream";
    }
}
