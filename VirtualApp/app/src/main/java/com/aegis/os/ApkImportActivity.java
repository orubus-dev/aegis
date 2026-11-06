package com.aegis.os;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lody.virtual.client.core.InstallStrategy;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

import io.virtualapp.R;

/**
 * Imports an app into Aegis from an APK file picked with the system file
 * picker (SAF).  The APK never gets installed on the real device -- it is
 * copied into Aegis' private storage and installed into the virtual OS.
 */
public class ApkImportActivity extends Activity {

    private static final int REQ_PICK_APK = 5001;
    private static final long MAX_APK_BYTES = 4L * 1024 * 1024 * 1024;

    private ProgressBar mProgress;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apk_import);
        TextView hint = findViewById(R.id.apk_import_hint);
        hint.setText(R.string.apk_import_hint);
        mProgress = findViewById(R.id.apk_import_progress);
        mStatus = findViewById(R.id.apk_import_status);
        findViewById(R.id.apk_import_pick).setOnClickListener(v -> pickApk());
    }

    private void pickApk() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, REQ_PICK_APK);
        } catch (Throwable e) {
            Toast.makeText(this, R.string.vault_picker_missing, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        importApk(data.getData());
    }

    private void importApk(Uri uri) {
        mProgress.setVisibility(View.VISIBLE);
        mStatus.setText(R.string.apk_import_copying);
        new Thread(() -> {
            File tmp = null;
            try {
                tmp = new File(getCacheDir(), "import_" + System.currentTimeMillis() + ".apk");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_APK_BYTES) {
                            throw new IllegalStateException("APK too large");
                        }
                        out.write(buf, 0, n);
                    }
                }
                runOnUiThread(() -> mStatus.setText(R.string.apk_import_installing));
                final File apk = tmp;
                com.lody.virtual.remote.InstallResult res =
                        VirtualCore.get().installPackage(apk.getAbsolutePath(),
                                InstallStrategy.COMPARE_VERSION | InstallStrategy.SKIP_DEX_OPT);
                final boolean ok = res != null && res.isSuccess;
                final String errMsg = res == null ? "null result" : res.error;
                runOnUiThread(() -> {
                    mProgress.setVisibility(View.GONE);
                    if (ok) {
                        mStatus.setText(R.string.apk_import_done);
                        Toast.makeText(this, R.string.apk_import_done, Toast.LENGTH_LONG).show();
                    } else {
                        mStatus.setText(getString(R.string.apk_import_failed, errMsg == null ? "?" : errMsg));
                        Toast.makeText(this, getString(R.string.apk_import_failed, errMsg == null ? "?" : errMsg), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable e) {
                VLog.w("ApkImport", "import failed: " + e);
                runOnUiThread(() -> {
                    mProgress.setVisibility(View.GONE);
                    mStatus.setText(R.string.apk_import_failed_generic);
                });
            } finally {
                if (tmp != null && tmp.exists()) {
                    // Do not keep a copy of the APK around after install.
                    tmp.delete();
                }
            }
        }).start();
    }
}
