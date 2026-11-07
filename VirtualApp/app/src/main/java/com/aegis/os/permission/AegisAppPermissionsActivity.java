package com.aegis.os.permission;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.server.pm.AegisPermissionStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.virtualapp.R;

/**
 * Per-guest permission sheet: every permission the app requested, with its
 * simulated grant state.  Tap to flip Granted <-> Denied; long-press to
 * reset to the Aegis default (requested = granted).
 */
public class AegisAppPermissionsActivity extends Activity {

    private String mPkg;
    private PermAdapter mAdapter;

    private static final class PermRow {
        String permission;
        String label;
        String group;
        boolean dangerous;
        Boolean override; // null = default
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aegis_app_perms);
        mPkg = getIntent() == null || getIntent().getStringExtra("pkg") == null
                ? null : getIntent().getStringExtra("pkg");
        if (mPkg == null) {
            finish();
            return;
        }
        TextView title = findViewById(R.id.aegis_app_perms_title);
        title.setText(mPkg);
        mAdapter = new PermAdapter();
        ListView list = findViewById(R.id.aegis_app_perms_list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            PermRow row = mAdapter.getItem(position);
            boolean nowGranted = row.override == null ? true : !row.override;
            AegisPermissionStore.setOverride(mPkg, 0, row.permission, nowGranted);
            row.override = nowGranted;
            mAdapter.notifyDataSetChanged();
            Toast.makeText(this, nowGranted
                            ? R.string.aegis_perm_set_granted
                            : R.string.aegis_perm_set_denied,
                    Toast.LENGTH_SHORT).show();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            PermRow row = mAdapter.getItem(position);
            AegisPermissionStore.clearOverride(mPkg, 0, row.permission);
            row.override = null;
            mAdapter.notifyDataSetChanged();
            Toast.makeText(this, R.string.aegis_perm_reset, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        new Thread(() -> {
            final List<PermRow> rows = new ArrayList<>();
            try {
                PackageInfo pi = VPackageManager.get().getPackageInfo(mPkg,
                        PackageManager.GET_PERMISSIONS, 0);
                Map<String, Boolean> overrides =
                        AegisPermissionStore.overridesFor(mPkg, 0);
                if (pi != null && pi.requestedPermissions != null) {
                    for (String perm : pi.requestedPermissions) {
                        if (perm == null) {
                            continue;
                        }
                        PermRow row = new PermRow();
                        row.permission = perm;
                        AegisPermissionCatalog.Entry e = AegisPermissionCatalog.describe(perm);
                        row.label = e.label;
                        row.group = e.group;
                        row.dangerous = AegisPermissionCatalog.isDangerousGroup(e.group);
                        row.override = overrides.containsKey(perm) ? overrides.get(perm) : null;
                        rows.add(row);
                    }
                }
                Collections.sort(rows, (a, b) -> {
                    if (a.dangerous != b.dangerous) {
                        return a.dangerous ? -1 : 1;
                    }
                    return a.group.compareToIgnoreCase(b.group);
                });
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> mAdapter.replace(rows));
        }).start();
    }

    private class PermAdapter extends BaseAdapter {
        private final List<PermRow> mRows = new ArrayList<>();

        void replace(List<PermRow> rows) {
            mRows.clear();
            mRows.addAll(rows);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public PermRow getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AegisAppPermissionsActivity.this)
                        .inflate(R.layout.item_aegis_perm, parent, false);
            }
            PermRow row = getItem(position);
            TextView label = v.findViewById(R.id.perm_label);
            TextView perm = v.findViewById(R.id.perm_raw);
            TextView group = v.findViewById(R.id.perm_group);
            TextView state = v.findViewById(R.id.perm_state);
            label.setText(row.label);
            perm.setText(row.permission);
            group.setText(row.group + (row.dangerous ? " \u2022 sensitive" : ""));
            if (row.override == null) {
                state.setText(R.string.aegis_perm_state_default);
                state.setTextColor(0xFF9AA4B0);
            } else if (row.override) {
                state.setText(R.string.aegis_perm_state_granted);
                state.setTextColor(0xFF3DDC84);
            } else {
                state.setText(R.string.aegis_perm_state_denied);
                state.setTextColor(0xFFE5704A);
            }
            return v;
        }
    }
}
