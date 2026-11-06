package com.aegis.os.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.virtualapp.R;

/**
 * The Aegis Vault browser.  All entries on disk are AES-256-GCM blobs; this
 * screen decrypts on demand into a private cache that is shredded when the
 * viewer closes.
 */
public class VaultListActivity extends Activity {

    private static final int REQ_IMPORT = 4100;
    private static final int REQ_EXPORT = 4101;
    private static final int REQ_VIEW = 4102;

    private List<File> mItems;
    private VaultAdapter mAdapter;
    private File mExportSource;
    private int mExportIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vault_list);
        TextView title = findViewById(R.id.vault_title);
        title.setText(R.string.vault_title);
        TextView hint = findViewById(R.id.vault_hint);
        hint.setText(R.string.vault_hint);

        findViewById(R.id.vault_add_camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(VaultListActivity.this, VaultCameraActivity.class));
            }
        });
        findViewById(R.id.vault_add_import).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        });

        ListView list = findViewById(R.id.vault_list);
        mItems = VaultStore.list(this);
        mAdapter = new VaultAdapter();
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> viewItem(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(position);
            return true;
        });
        TextView empty = findViewById(R.id.vault_empty);
        empty.setText(R.string.vault_empty);
        empty.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void pick() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            startActivityForResult(it, REQ_IMPORT);
        } catch (Throwable e) {
            Toast.makeText(this, R.string.vault_picker_missing, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT) {
            importUri(uri);
        } else if (requestCode == REQ_EXPORT && mExportSource != null) {
            exportTo(uri, mExportSource);
            mExportSource = null;
        } else if (requestCode == REQ_VIEW) {
            VaultStore.shredViewCache(this);
        }
    }

    private void importUri(Uri uri) {
        Toast.makeText(this, R.string.vault_importing, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(VaultStore.viewCacheDir(VaultListActivity.this),
                        "import_" + VaultCrypto.newId());
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    String name = queryName(uri);
                    String mime = getContentResolver().getType(uri);
                    if (mime == null) mime = guessMime(name);
                    VaultCrypto.encryptFile(VaultListActivity.this, tmp,
                            VaultStore.newVaultFile(VaultListActivity.this),
                            name, mime, System.currentTimeMillis());
                    runOnUiThread(() -> {
                        Toast.makeText(VaultListActivity.this, R.string.vault_saved, Toast.LENGTH_SHORT).show();
                        refresh();
                    });
                } catch (Throwable e) {
                    runOnUiThread(() -> Toast.makeText(VaultListActivity.this,
                            R.string.vault_import_failed, Toast.LENGTH_SHORT).show());
                } finally {
                    VaultCrypto.shred(tmp);
                }
            }
        }).start();
    }

    private String queryName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) return c.getString(idx);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return "imported_" + System.currentTimeMillis();
    }

    private static String guessMime(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    private void viewItem(int position) {
        File f = mItems.get(position);
        VaultStore.shredViewCache(this);
        File tmp = new File(VaultStore.viewCacheDir(this), "view_" + VaultCrypto.newId());
        try {
            VaultCrypto.Header h = VaultCrypto.decryptFile(this, f, new java.io.FileOutputStream(tmp));
            tmp.setReadable(true, true);
            Intent it = new Intent(this, VaultViewActivity.class);
            it.putExtra("path", tmp.getAbsolutePath());
            it.putExtra("name", h.name);
            it.putExtra("mime", h.mime);
            startActivityForResult(it, REQ_VIEW);
        } catch (Throwable e) {
            VaultCrypto.shred(tmp);
            Toast.makeText(this, R.string.vault_decrypt_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(int position) {
        final File f = mItems.get(position);
        final int idx = position;
        new AlertDialog.Builder(this)
                .setTitle(R.string.vault_item_actions)
                .setItems(new CharSequence[]{
                        getString(R.string.vault_action_export),
                        getString(R.string.vault_action_delete)
                }, (dialog, which) -> {
                    if (which == 0) {
                        mExportSource = f;
                        mExportIndex = idx;
                        try {
                            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            it.addCategory(Intent.CATEGORY_OPENABLE);
                            it.setType("*/*");
                            it.putExtra(Intent.EXTRA_TITLE, decryptName(f));
                            startActivityForResult(it, REQ_EXPORT);
                        } catch (Throwable e) {
                            Toast.makeText(VaultListActivity.this, R.string.vault_picker_missing,
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 1) {
                        new AlertDialog.Builder(VaultListActivity.this)
                                .setTitle(R.string.vault_delete_confirm_title)
                                .setMessage(R.string.vault_delete_confirm_body)
                                .setPositiveButton(android.R.string.ok, (d2, w2) -> {
                                    VaultStore.delete(VaultListActivity.this, f);
                                    refresh();
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                })
                .show();
    }

    private String decryptName(File f) {
        try {
            VaultCrypto.Header h = VaultCrypto.readHeader(new java.io.FileInputStream(f), this);
            if (h.fileKey != null) VaultCrypto.zero(h.fileKey);
            return h.name;
        } catch (Throwable e) {
            return "aegis_export";
        }
    }

    private void exportTo(Uri target, File source) {
        try {
            java.io.OutputStream out = getContentResolver().openOutputStream(target, "w");
            VaultCrypto.Header h = VaultCrypto.decryptFile(this, source, out);
            out.close();
            VaultCrypto.zero(h.fileKey);
            Toast.makeText(this, R.string.vault_exported, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, R.string.vault_decrypt_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void refresh() {
        mItems = VaultStore.list(this);
        mAdapter.notifyDataSetChanged();
        TextView empty = findViewById(R.id.vault_empty);
        empty.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        VaultStore.shredViewCache(this);
        super.onDestroy();
    }

    private class VaultAdapter extends BaseAdapter {
        private final SimpleDateFormat mFmt =
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public File getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_vault, parent, false);
            }
            File f = getItem(position);
            String name = f.getName();
            long size = f.length();
            String when = mFmt.format(new Date(f.lastModified()));
            boolean isVideo = size > 0 && looksVideo(f);
            ImageView icon = v.findViewById(R.id.vault_item_icon);
            icon.setImageResource(isVideo
                    ? android.R.drawable.ic_menu_camera
                    : android.R.drawable.ic_menu_gallery);
            TextView nameView = v.findViewById(R.id.vault_item_name);
            TextView metaView = v.findViewById(R.id.vault_item_meta);
            nameView.setText(readableName(f, name));
            metaView.setText(when + "  •  " + VaultStore.humanSize(size) + "  •  AES-256-GCM");
            return v;
        }

        private boolean looksVideo(File f) {
            // Peek the decrypted name via header only (cheap, no payload decrypt).
            try {
                VaultCrypto.Header h = VaultCrypto.readHeader(new java.io.FileInputStream(f),
                        VaultListActivity.this);
                if (h.fileKey != null) VaultCrypto.zero(h.fileKey);
                return h.mime != null && h.mime.startsWith("video/");
            } catch (Throwable e) {
                return false;
            }
        }

        private String readableName(File f, String fallback) {
            try {
                VaultCrypto.Header h = VaultCrypto.readHeader(new java.io.FileInputStream(f),
                        VaultListActivity.this);
                if (h.fileKey != null) VaultCrypto.zero(h.fileKey);
                return h.name != null && h.name.length() > 0 ? h.name : fallback;
            } catch (Throwable e) {
                return fallback;
            }
        }
    }
}
