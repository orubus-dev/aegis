package com.aegis.os.permission;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.pm.AegisPermissionStore;

import java.util.ArrayList;
import java.util.List;

import io.virtualapp.R;

/**
 * Aegis OS > Permissions.
 *
 * Master screen of the simulated permission system: a global on/off switch
 * and one row per guest app.  Tapping a guest opens its permission sheet.
 */
public class AegisPermissionsActivity extends Activity {

    private TextView mSimToggle;
    private AppAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aegis_permissions);

        mSimToggle = findViewById(R.id.aegis_perm_sim_toggle);
        mSimToggle.setOnClickListener(v -> {
            boolean next = !AegisPermissionStore.isSimulationEnabled();
            AegisPermissionStore.setSimulationEnabled(next);
            Toast.makeText(this, next
                    ? R.string.aegis_perm_sim_on
                    : R.string.aegis_perm_sim_off, Toast.LENGTH_SHORT).show();
            refreshSimLabel();
        });
        refreshSimLabel();

        ListView list = findViewById(R.id.aegis_perm_list);
        mAdapter = new AppAdapter();
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            InstalledAppInfo info = mAdapter.getItem(position);
            if (info != null) {
                Intent it = new Intent(this, AegisAppPermissionsActivity.class);
                it.putExtra("pkg", info.packageName);
                startActivity(it);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSimLabel();
        reload();
    }

    private void refreshSimLabel() {
        boolean on = AegisPermissionStore.isSimulationEnabled();
        mSimToggle.setText(on
                ? getString(R.string.aegis_perm_sim_enabled)
                : getString(R.string.aegis_perm_sim_disabled));
        mSimToggle.setTextColor(on ? 0xFF3DDC84 : 0xFFE5704A);
    }

    private void reload() {
        new Thread(() -> {
            final List<InstalledAppInfo> apps = new ArrayList<>();
            try {
                List<InstalledAppInfo> infos = VirtualCore.get().getInstalledApps(0);
                for (InstalledAppInfo info : infos) {
                    if (info.packageName.equals(VirtualCore.get().getHostPkg())) {
                        continue;
                    }
                    apps.add(info);
                }
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> mAdapter.replace(apps));
        }).start();
    }

    private class AppAdapter extends BaseAdapter {
        private final List<InstalledAppInfo> mApps = new ArrayList<>();
        private final PackageManager mPm = getPackageManager();

        void replace(List<InstalledAppInfo> apps) {
            mApps.clear();
            mApps.addAll(apps);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mApps.size();
        }

        @Override
        public InstalledAppInfo getItem(int position) {
            return mApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AegisPermissionsActivity.this)
                        .inflate(R.layout.item_aegis_perm_app, parent, false);
            }
            InstalledAppInfo info = getItem(position);
            TextView name = v.findViewById(R.id.perm_app_name);
            TextView pkg = v.findViewById(R.id.perm_app_pkg);
            ImageView icon = v.findViewById(R.id.perm_app_icon);
            String label = info.packageName;
            Drawable iconDrawable = null;
            try {
                ApplicationInfo ai = info.getApplicationInfo(0);
                if (ai != null) {
                    CharSequence cs = mPm.getApplicationLabel(ai);
                    if (cs != null && cs.length() > 0) {
                        label = cs.toString();
                    }
                    iconDrawable = mPm.getApplicationIcon(ai);
                }
            } catch (Throwable ignored) {
            }
            name.setText(label);
            pkg.setText(info.packageName);
            if (iconDrawable != null) {
                icon.setImageDrawable(iconDrawable);
            } else {
                icon.setImageResource(R.drawable.ic_launcher);
            }
            return v;
        }
    }
}
