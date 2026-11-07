package com.aegis.os;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import io.virtualapp.R;

/**
 * Shows the on-device diagnostics log (installs, engine failures) so a field
 * tester can copy the exact error without adb.
 */
public class DiagnosticsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aegis_diagnostics);
        TextView text = findViewById(R.id.aegis_diag_text);
        text.setText(Diagnostics.readLast(this, 220));
        findViewById(R.id.aegis_diag_copy).setOnClickListener(v -> {
            try {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Aegis diagnostics",
                        Diagnostics.readLast(DiagnosticsActivity.this, 220)));
                Toast.makeText(this, R.string.aegis_diag_copied, Toast.LENGTH_SHORT).show();
            } catch (Throwable e) {
                Toast.makeText(this, R.string.aegis_diag_copy_failed, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.aegis_diag_clear).setOnClickListener(v -> {
            Diagnostics.clear(DiagnosticsActivity.this);
            text.setText(Diagnostics.readLast(DiagnosticsActivity.this, 220));
        });
    }
}
