package com.aegis.os.vault;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import io.virtualapp.R;

/**
 * Secure Camera: captures a photo or video straight into the encrypted
 * vault.  Uses the system camera app (no CAMERA permission needed since we
 * don't declare it), receives the raw media in a private temp file, then
 * encrypts it into the vault and shreds the temp copy.
 */
public class VaultCameraActivity extends Activity {

    private static final int REQ_PHOTO = 4001;
    private static final int REQ_VIDEO = 4002;
    private static final long MAX_VIDEO_BYTES = 512L * 1024 * 1024;

    private File mTempCapture;
    private boolean mIsVideo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vault_capture);
        Button photo = findViewById(R.id.vault_btn_photo);
        Button video = findViewById(R.id.vault_btn_video);
        TextView hint = findViewById(R.id.vault_capture_hint);
        hint.setText(R.string.vault_capture_hint);
        photo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatchCapture(false);
            }
        });
        video.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dispatchCapture(true);
            }
        });
    }

    private void dispatchCapture(boolean video) {
        mIsVideo = video;
        File dir = VaultStore.viewCacheDir(this);
        mTempCapture = new File(dir, "capture_" + VaultCrypto.newId() + (video ? ".mp4" : ".jpg"));
        try {
            Intent it = new Intent(video ? MediaStore.ACTION_VIDEO_CAPTURE : MediaStore.ACTION_IMAGE_CAPTURE);
            if (!video) {
                it.putExtra(MediaStore.EXTRA_OUTPUT, android.support.v4.content.FileProvider
                        .getUriForFile(this, authority(), mTempCapture));
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else {
                it.putExtra(MediaStore.EXTRA_OUTPUT, android.support.v4.content.FileProvider
                        .getUriForFile(this, authority(), mTempCapture));
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                it.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
            }
            startActivityForResult(it, video ? REQ_VIDEO : REQ_PHOTO);
        } catch (Throwable e) {
            Toast.makeText(this, R.string.vault_camera_missing, Toast.LENGTH_SHORT).show();
        }
    }

    public static String authority() {
        return "com.aegis.os.vault.files";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQ_PHOTO && requestCode != REQ_VIDEO) || resultCode != RESULT_OK) {
            cleanupTemp();
            return;
        }
        if (requestCode == REQ_VIDEO && data != null && data.getData() != null) {
            // Some camera apps write to the EXTRA_OUTPUT uri, others return a
            // MediaStore uri; handle both by copying into our temp file.
            File moved = new File(VaultStore.viewCacheDir(this), "capture_" + VaultCrypto.newId() + ".mp4");
            try {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                java.io.FileOutputStream out = new java.io.FileOutputStream(moved);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                mTempCapture = moved;
            } catch (Throwable e) {
                Toast.makeText(this, R.string.vault_import_failed, Toast.LENGTH_SHORT).show();
                cleanupTemp();
                return;
            }
        }
        if (mTempCapture == null || !mTempCapture.exists() || mTempCapture.length() == 0) {
            Toast.makeText(this, R.string.vault_capture_empty, Toast.LENGTH_SHORT).show();
            cleanupTemp();
            return;
        }
        if (mTempCapture.length() > MAX_VIDEO_BYTES) {
            Toast.makeText(this, R.string.vault_too_large, Toast.LENGTH_LONG).show();
            cleanupTemp();
            return;
        }
        // Encrypt into the vault on a worker thread, then shred the temp.
        final File src = mTempCapture;
        final boolean video = mIsVideo;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String name = "IMG_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg");
                    String mime = video ? "video/mp4" : "image/jpeg";
                    VaultCrypto.encryptFile(VaultCameraActivity.this, src,
                            VaultStore.newVaultFile(VaultCameraActivity.this), name, mime,
                            System.currentTimeMillis());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(VaultCameraActivity.this, R.string.vault_saved, Toast.LENGTH_SHORT).show();
                            setResult(RESULT_OK);
                            finish();
                        }
                    });
                } catch (Throwable e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(VaultCameraActivity.this, R.string.vault_import_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    VaultCrypto.shred(src);
                }
            }
        }).start();
    }

    private void cleanupTemp() {
        if (mTempCapture != null) {
            VaultCrypto.shred(mTempCapture);
            mTempCapture = null;
        }
    }

    @Override
    protected void onDestroy() {
        cleanupTemp();
        super.onDestroy();
    }
}
