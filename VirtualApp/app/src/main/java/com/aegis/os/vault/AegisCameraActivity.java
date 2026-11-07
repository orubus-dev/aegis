package com.aegis.os.vault;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import android.widget.Toast;

import com.aegis.os.Diagnostics;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import io.virtualapp.R;

/**
 * Aegis secure camera: a real in-app camera.  The preview never leaves the
 * app, the captured JPEG is scrubbed of all identifying EXIF (GPS, device
 * model, timestamps -- see AegisScrubber) and then encrypted straight into
 * the vault; the plaintext temp file is shredded.  No picture taken in
 * Aegis ever lands in the phone's gallery.
 *
 * If the hardware preview is unavailable the user can fall back to the
 * system camera app (that path keeps the scrub + vault encryption too).
 */
public class AegisCameraActivity extends Activity
        implements SurfaceHolder.Callback, Camera.PictureCallback {

    private static final int REQ_CAMERA_PERM = 5101;
    private static final int REQ_SYSTEM_PHOTO = 5102;

    private SurfaceView mSurface;
    private Camera mCamera;
    private int mCameraId = -1;
    private boolean mPreviewing;
    private boolean mCapturing;
    private File mPendingShot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aegis_camera);

        mSurface = findViewById(R.id.aegis_camera_surface);
        mSurface.getHolder().addCallback(this);
        mSurface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        findViewById(R.id.aegis_camera_shutter).setOnClickListener(v -> capture());
        findViewById(R.id.aegis_camera_close).setOnClickListener(v -> finish());
        findViewById(R.id.aegis_camera_system).setOnClickListener(v -> {
            startActivityForResult(new Intent(this, VaultCameraActivity.class),
                    REQ_SYSTEM_PHOTO);
        });

        requestCameraPermission();
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERM) {
            if (grantResults == null || grantResults.length == 0
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showCameraError(getString(R.string.aegis_camera_perm_needed));
            }
        }
    }

    // ---------- camera lifecycle ----------

    private void openCameraIfPermitted() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return; // wait for the grant; surface callback retries via onResume
        }
        if (mPreviewing) {
            return;
        }
        try {
            mCamera = findBackCamera();
            if (mCamera == null) {
                showCameraError(getString(R.string.aegis_camera_unavailable));
                return;
            }
            Camera.Parameters p = mCamera.getParameters();
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            if (sizes != null && !sizes.isEmpty()) {
                Camera.Size best = sizes.get(0);
                for (Camera.Size s : sizes) {
                    if (s.width * s.height <= 1280 * 720
                            && s.width * s.height > best.width * best.height) {
                        best = s;
                    }
                }
                p.setPreviewSize(best.width, best.height);
            }
            List<String> focuses = p.getSupportedFocusModes();
            if (focuses != null && focuses.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            mCamera.setParameters(p);
            mCamera.setPreviewDisplay(mSurface.getHolder());
            mCamera.startPreview();
            mPreviewing = true;
            findViewById(R.id.aegis_camera_error).setVisibility(android.view.View.GONE);
        } catch (Throwable t) {
            releaseCamera();
            showCameraError(getString(R.string.aegis_camera_unavailable));
        }
    }

    private Camera findBackCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                try {
                    Camera c = Camera.open(i);
                    mCameraId = i;
                    return c;
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        try {
            return Camera.open();
        } catch (Throwable t) {
            return null;
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            try {
                if (mPreviewing) {
                    mCamera.stopPreview();
                }
            } catch (Throwable ignored) {
            }
            try {
                mCamera.release();
            } catch (Throwable ignored) {
            }
            mCamera = null;
        }
        mPreviewing = false;
        mCapturing = false;
    }

    private void showCameraError(String message) {
        TextView error = findViewById(R.id.aegis_camera_error);
        error.setVisibility(android.view.View.VISIBLE);
        error.setText(message);
    }

    // ---------- capture ----------

    private void capture() {
        if (mCapturing) {
            return;
        }
        if (!mPreviewing || mCamera == null) {
            openCameraIfPermitted();
            if (!mPreviewing) {
                Toast.makeText(this, R.string.aegis_camera_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mCapturing = true;
        try {
            mCamera.takePicture(null, null, this);
        } catch (Throwable t) {
            mCapturing = false;
            Toast.makeText(this, R.string.aegis_camera_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {
        releaseCamera();
        try {
            // 1. plaintext exists ONLY in a private temp file
            mPendingShot = new File(VaultStore.viewCacheDir(this),
                    "shot_" + VaultCrypto.newId() + ".jpg");
            FileOutputStream out = new FileOutputStream(mPendingShot);
            out.write(data);
            out.close();
            // 2. strip identifying metadata before anything else sees it
            int scrubbed = AegisScrubber.scrubImage(mPendingShot);
            final int fields = Math.max(scrubbed, 0);
            // 3. encrypt into the vault
            String name = "IMG_" + System.currentTimeMillis() + ".jpg";
            VaultCrypto.encryptFile(this, mPendingShot, VaultStore.newVaultFile(this),
                    name, "image/jpeg", System.currentTimeMillis());
            VaultCrypto.shred(mPendingShot);
            mPendingShot = null;
            Diagnostics.log(this, "camera: captured " + name
                    + " (scrubbed " + fields + " metadata fields)");
            Toast.makeText(this, R.string.aegis_camera_saved, Toast.LENGTH_SHORT).show();
            setResult(android.app.Activity.RESULT_OK);
            finish();
        } catch (Throwable t) {
            Diagnostics.log(this, "camera: save failed", t);
            Toast.makeText(this, R.string.vault_import_failed, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ---------- SurfaceHolder.Callback ----------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCameraIfPermitted();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mCamera != null && !mPreviewing) {
            openCameraIfPermitted();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        openCameraIfPermitted();
    }

    @Override
    protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SYSTEM_PHOTO && resultCode == RESULT_OK) {
            // VaultCameraActivity already stored the media; just close.
            setResult(RESULT_OK);
            finish();
        }
    }
}
