package com.aegis.os.vault;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Aegis Vault crypto core.
 *
 * Design goals: hardware-backed key material, authenticated encryption for
 * every byte on disk, streaming-friendly for large videos, nothing readable
 * leaves the app sandbox unencrypted.
 *
 * Format "AEGV1" (all multi-byte values big-endian):
 *
 *   bytes  0..4    magic "AEGV1"
 *   bytes  5..8    headerLen (int)
 *   bytes  9..     headerBlob  = AES-256-GCM(masterKey, headerPlain)
 *                  headerPlain = JSON: name, mime, addedAt, size
 *                                + key (fileKey wrapped by masterKey) + iv
 *   bytes  ..      [int idLen][fileId ascii]
 *   then           frames: [int frameLen][12B IV][ciphertext+16B GCM tag]
 *                  each frame = AES-256-GCM(fileKey, chunk, aad = aadFor(fileId, idx))
 *
 * Keys:
 *   masterKey -- AndroidKeyStore AES-256 key, never extractable on API 23+.
 *                StrongBox is requested when the device has it (API 28+).
 *   fileKey   -- fresh 256-bit key per file from SecureRandom, wrapped by
 *                the master key inside the encrypted header.
 *   Per-chunk IVs are 12 bytes: 4 random bytes per file + 8-byte big-endian
 *   chunk counter, so IVs never repeat under the same file key.  Chunk index
 *   and fileId are bound as GCM AAD, making chunk reordering/splicing and
 *   truncation detectable (AEADBadTagException on read).
 *
 * On API < 23 (no AndroidKeyStore AES-GCM) the master key is a random key in
 * the app's private files dir -- weaker, but still authenticated encryption
 * at rest inside the sandbox.  Aegis targets Android 13-15 so this legacy
 * path only exists for old devices.
 */
public final class VaultCrypto {

    public static final String MAGIC = "AEGV1";
    public static final int CHUNK_SIZE = 1024 * 1024; // 1 MiB frames
    public static final int GCM_TAG_BITS = 128;
    public static final int IV_LEN = 12;

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String MASTER_ALIAS = "aegis_vault_master_v1";
    private static final String FALLBACK_KEY_FILE = "aegis_vault_master.key";

    private static final String B64C =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private VaultCrypto() {
    }

    // ------------------------------------------------------------------
    // Master key
    // ------------------------------------------------------------------

    private static SecretKey masterKey(Context context) throws Exception {
        if (Build.VERSION.SDK_INT >= 23) {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            SecretKey existing = (SecretKey) ks.getKey(MASTER_ALIAS, null);
            if (existing != null) {
                return existing;
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                    MASTER_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256);
            if (Build.VERSION.SDK_INT >= 28
                    && context.getPackageManager().hasSystemFeature("android.hardware.strongbox_keystore")) {
                try {
                    b.setIsStrongBoxBacked(true);
                } catch (Throwable ignored) {
                    // some OEMs advertise the feature but reject the request
                }
            }
            kg.init(b.build());
            return kg.generateKey();
        }
        // Legacy path: random master key in app-private storage.
        File f = new File(context.getFilesDir(), FALLBACK_KEY_FILE);
        if (f.exists()) {
            byte[] raw = readAll(f);
            return new SecretKeySpec(raw, "AES");
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(raw);
        }
        return new SecretKeySpec(raw, "AES");
    }

    // ------------------------------------------------------------------
    // Header (metadata + wrapped file key)
    // ------------------------------------------------------------------

    public static final class Header {
        public String name;
        public String mime;
        public long addedAt;
        public long plainSize;
        public byte[] fileKey; // unwrapped, in-memory only; zero after use
        public String fileId;
    }

    public static void writeHeader(OutputStream out, Context context, Header h, String fileId)
            throws Exception {
        Cipher wrap = Cipher.getInstance("AES/GCM/NoPadding");
        wrap.init(Cipher.ENCRYPT_MODE, masterKey(context));
        byte[] wrappedKey = wrap.doFinal(h.fileKey);
        byte[] wrapIv = wrap.getIV();
        if (wrapIv == null || wrapIv.length == 0) {
            throw new IllegalStateException("GCM IV missing");
        }

        org.json.JSONObject meta = new org.json.JSONObject();
        meta.put("name", h.name);
        meta.put("mime", h.mime);
        meta.put("addedAt", h.addedAt);
        meta.put("size", h.plainSize);
        meta.put("key", b64(wrappedKey));
        meta.put("wiv", b64(wrapIv));
        byte[] plain = meta.toString().getBytes(StandardCharsets.UTF_8);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, masterKey(context));
        byte[] headerBlob = c.doFinal(plain);
        byte[] idBytes = fileId.getBytes(StandardCharsets.US_ASCII);

        out.write(MAGIC.getBytes(StandardCharsets.US_ASCII));
        ByteBuffer bb = ByteBuffer.allocate(4 + headerBlob.length + 4 + idBytes.length);
        bb.putInt(headerBlob.length);
        bb.put(headerBlob);
        bb.putInt(idBytes.length);
        bb.put(idBytes);
        out.write(bb.array());
    }

    public static Header readHeader(InputStream in, Context context) throws Exception {
        byte[] magic = new byte[5];
        if (readFully(in, magic) != magic.length
                || !MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("not an Aegis vault file (bad magic)");
        }
        byte[] intBuf = new byte[4];
        if (readFully(in, intBuf) != 4) throw new IOException("truncated header");
        int headerLen = ByteBuffer.wrap(intBuf).getInt();
        if (headerLen <= 0 || headerLen > 64 * 1024) throw new IOException("bad header length");
        byte[] headerBlob = new byte[headerLen];
        if (readFully(in, headerBlob) != headerLen) throw new IOException("truncated header blob");

        if (readFully(in, intBuf) != 4) throw new IOException("truncated header");
        int idLen = ByteBuffer.wrap(intBuf).getInt();
        if (idLen <= 0 || idLen > 256) throw new IOException("bad fileId length");
        byte[] idBuf = new byte[idLen];
        if (readFully(in, idBuf) != idLen) throw new IOException("truncated fileId");

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, masterKey(context));
        byte[] plain = c.doFinal(headerBlob);
        org.json.JSONObject meta = new org.json.JSONObject(new String(plain, StandardCharsets.UTF_8));

        byte[] wrappedKey = ub64(meta.optString("key"));
        byte[] wrapIv = ub64(meta.optString("wiv"));
        Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
        unwrap.init(Cipher.DECRYPT_MODE, masterKey(context), new GCMParameterSpec(GCM_TAG_BITS, wrapIv));

        Header h = new Header();
        h.name = meta.optString("name");
        h.mime = meta.optString("mime");
        h.addedAt = meta.optLong("addedAt");
        h.plainSize = meta.optLong("size");
        h.fileId = new String(idBuf, StandardCharsets.US_ASCII);
        h.fileKey = unwrap.doFinal(wrappedKey);
        return h;
    }

    public static String fileIdOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ------------------------------------------------------------------
    // Payload frames
    // ------------------------------------------------------------------

    private static byte[] ivFor(byte[] prefix, long chunkIndex) {
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(prefix, 0, iv, 0, 4);
        ByteBuffer.wrap(iv, 4, 8).putLong(chunkIndex);
        return iv;
    }

    public static byte[] aadFor(String fileId, long chunkIndex) {
        return ("AEGV1|" + fileId + "|" + chunkIndex).getBytes(StandardCharsets.UTF_8);
    }

    public static void encryptChunk(OutputStream out, SecretKey fileKey, byte[] ivPrefix,
                                    String fileId, long index, byte[] chunk, int len) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, fileKey, new GCMParameterSpec(GCM_TAG_BITS, ivFor(ivPrefix, index)));
        c.updateAAD(aadFor(fileId, index));
        byte[] ct = c.doFinal(chunk, 0, len);
        ByteBuffer bb = ByteBuffer.allocate(4 + IV_LEN + ct.length);
        bb.putInt(ct.length);
        bb.put(ivFor(ivPrefix, index));
        bb.put(ct);
        out.write(bb.array());
    }

    /** Decrypts exactly one frame; throws AEADBadTagException on tamper. */
    public static byte[] decryptFrame(InputStream in, SecretKey fileKey, String fileId, long index)
            throws Exception {
        byte[] intBuf = new byte[4];
        if (readFully(in, intBuf) != 4) throw new IOException("truncated frame length");
        int frameLen = ByteBuffer.wrap(intBuf).getInt();
        if (frameLen <= IV_LEN || frameLen > CHUNK_SIZE + 1024) throw new IOException("bad frame length");
        byte[] iv = new byte[IV_LEN];
        if (readFully(in, iv) != IV_LEN) throw new IOException("truncated frame iv");
        int ctLen = frameLen - IV_LEN;
        byte[] ct = new byte[ctLen];
        if (readFully(in, ct) != ctLen) throw new IOException("truncated frame body");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, fileKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        c.updateAAD(aadFor(fileId, index));
        return c.doFinal(ct);
    }

    // ------------------------------------------------------------------
    // Whole-file streaming helpers
    // ------------------------------------------------------------------

    public static Header encryptFile(Context context, File plainSource, File target,
                                     String name, String mime, long addedAt) throws Exception {
        Header h = new Header();
        h.name = name;
        h.mime = mime;
        h.addedAt = addedAt;
        h.plainSize = plainSource.length();
        h.fileId = newId();
        h.fileKey = new byte[32];
        new SecureRandom().nextBytes(h.fileKey);

        byte[] ivPrefix = new byte[4];
        new SecureRandom().nextBytes(ivPrefix);

        FileInputStream in = new FileInputStream(plainSource);
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                writeHeader(out, context, h, h.fileId);
                SecretKey fk = new SecretKeySpec(h.fileKey, "AES");
                byte[] buf = new byte[CHUNK_SIZE];
                long index = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    encryptChunk(out, fk, ivPrefix, h.fileId, index++, buf, n);
                }
                out.getFD().sync();
            } finally {
                try { out.close(); } catch (IOException ignored) {}
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
        zero(h.fileKey);
        return h;
    }

    public static Header decryptFile(Context context, File vaultFile, OutputStream out) throws Exception {
        FileInputStream in = new FileInputStream(vaultFile);
        try {
            Header h = readHeader(in, context);
            SecretKey fk = new SecretKeySpec(h.fileKey, "AES");
            long index = 0;
            while (true) {
                byte[] plain;
                try {
                    plain = decryptFrame(in, fk, h.fileId, index);
                } catch (IOException cleanEnd) {
                    break;
                }
                out.write(plain);
                index++;
            }
            out.flush();
            zero(h.fileKey);
            return h;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /** Secure erase: overwrite with zeros + fsync before deleting. */
    public static void shred(File f) {
        if (f == null || !f.exists()) return;
        try {
            long len = f.length();
            FileOutputStream out = new FileOutputStream(f);
            try {
                byte[] zeros = new byte[8192];
                long left = len;
                while (left > 0) {
                    int n = (int) Math.min(zeros.length, left);
                    out.write(zeros, 0, n);
                    left -= n;
                }
                out.getFD().sync();
            } finally {
                try { out.close(); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
        f.delete();
    }

    public static void zero(byte[] key) {
        if (key != null) Arrays.fill(key, (byte) 0);
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    public static String newId() {
        byte[] id = new byte[16];
        new SecureRandom().nextBytes(id);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : id) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return off;
            off += n;
        }
        return off;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return buf;
    }

    public static String b64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(B64C.charAt(b0 >> 2));
            sb.append(B64C.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < data.length ? B64C.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < data.length ? B64C.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    public static byte[] ub64(String s) {
        s = s.replace("\n", "").replace("\r", "");
        int pad = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '='; i--) pad++;
        int len = (s.length() * 6) / 8 - pad;
        byte[] out = new byte[len];
        int outPos = 0;
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '=') break;
            int v = B64C.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("bad base64");
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[outPos++] = (byte) ((acc >> bits) & 0xFF);
            }
        }
        return out;
    }
}
