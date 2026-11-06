package com.aegis.os;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.compat.HiddenApiCompat;

import io.virtualapp.R;
import io.virtualapp.home.ListAppActivity;
import io.virtualapp.settings.AboutActivity;
import io.virtualapp.settings.AppManageActivity;
import io.virtualapp.settings.SettingsActivity;

/**
 * Aegis OS settings -- the in-sandbox operating-system hub.
 *
 * Everything reachable from here lives INSIDE Aegis: guest apps, the
 * encrypted vault, and engine diagnostics.  The device's own settings are
 * never opened from here (except the optional battery-optimization grant).
 */
public class AegisSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aegis_settings);

        TextView version = findViewById(R.id.aegis_version);
        version.setText(getString(R.string.aegis_version_line, "1.0.0-m1", "Android " + Build.VERSION.RELEASE));

        TextView status = findViewById(R.id.aegis_engine_status);
        status.setText(describeEngine());

        row(R.id.aegis_row_apps, R.string.aegis_row_apps, v ->
                startActivity(new Intent(this, AppManageActivity.class)));
        row(R.id.aegis_row_add_app, R.string.aegis_row_add_app, v ->
                ListAppActivity.gotoListApp(this));
        row(R.id.aegis_row_add_apk, R.string.aegis_row_add_apk, v ->
                startActivity(new Intent(this, ApkImportActivity.class)));
        row(R.id.aegis_row_vault, R.string.aegis_row_vault, v ->
                startActivity(new Intent(this, com.aegis.os.vault.VaultListActivity.class)));
        row(R.id.aegis_row_camera, R.string.aegis_row_camera, v ->
                startActivity(new Intent(this, com.aegis.os.vault.VaultCameraActivity.class)));
        row(R.id.aegis_row_engine, R.string.aegis_row_engine, v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        row(R.id.aegis_row_about, R.string.aegis_row_about, v ->
                startActivity(new Intent(this, AboutActivity.class)));
    }

    private interface RowClick {
        void go(View v);
    }

    private void row(int id, int label, RowClick click) {
        View v = findViewById(id);
        if (v != null) {
            v.setOnClickListener(click::go);
        }
    }

    private String describeEngine() {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.aegis_engine_guests,
                VirtualCore.get().getInstalledApps(0).size()));
        boolean enforcementDown = HiddenApiCompat.isEnforcementActive();
        sb.append('\n').append(getString(enforcementDown
                ? R.string.aegis_engine_hiddenapi_bad
                : R.string.aegis_engine_hiddenapi_ok));
        return sb.toString();
    }
}
