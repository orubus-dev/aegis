package com.aegis.os.apps;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import io.virtualapp.R;
import com.aegis.os.identity.AegisIdentity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Aegis OS > Messages: simulated conversations that exist only inside the
 * sandbox.  Threads are generated on first run; the thread view appends
 * messages to the local store.  Nothing is ever transmitted.
 */
public class AegisMessagesActivity extends Activity {

    private final List<AegisIdentity.Thread> mThreads = new ArrayList<>();
    private ThreadAdapter mAdapter;
    private final SimpleDateFormat mFmt =
            new SimpleDateFormat("MMM d \u2022 HH:mm", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aegis_identity_list);

        ((TextView) findViewById(R.id.aegis_list_title)).setText(R.string.aegis_msg_title);
        ((TextView) findViewById(R.id.aegis_list_subtitle)).setText(R.string.aegis_msg_subtitle);
        TextView action = findViewById(R.id.aegis_list_action);
        action.setText(R.string.aegis_msg_new);
        action.setOnClickListener(v -> {
            // Quick new thread with a typed name + number
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle(R.string.aegis_msg_new);
            LinearLayout box = (LinearLayout) LayoutInflater.from(this)
                    .inflate(R.layout.dialog_aegis_new_thread, null);
            b.setView(box);
            final android.widget.EditText name = box.findViewById(R.id.thread_name);
            final android.widget.EditText phone = box.findViewById(R.id.thread_phone);
            b.setPositiveButton(R.string.aegis_save, (d, w) -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) {
                    return;
                }
                AegisIdentity.Thread t = new AegisIdentity.Thread();
                t.id = String.valueOf(System.currentTimeMillis());
                t.name = n;
                t.phone = phone.getText().toString().trim();
                mThreads.add(0, t);
                persist();
                openThread(t);
            });
            b.setNegativeButton(R.string.aegis_cancel, null);
            b.show();
        });

        mAdapter = new ThreadAdapter();
        ListView list = findViewById(R.id.aegis_list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> openThread(mThreads.get(position)));
    }

    private void openThread(AegisIdentity.Thread t) {
        Intent it = new Intent(this, AegisThreadActivity.class);
        it.putExtra("threadId", t.id);
        startActivity(it);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        new Thread(() -> {
            final List<AegisIdentity.Thread> fresh = AegisIdentity.threads(this);
            runOnUiThread(() -> {
                mThreads.clear();
                mThreads.addAll(fresh);
                mAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void persist() {
        final List<AegisIdentity.Thread> snapshot = new ArrayList<>(mThreads);
        new Thread(() -> AegisIdentity.saveThreads(this, snapshot)).start();
        mAdapter.notifyDataSetChanged();
    }

    private class ThreadAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mThreads.size();
        }

        @Override
        public AegisIdentity.Thread getItem(int position) {
            return mThreads.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AegisMessagesActivity.this)
                        .inflate(R.layout.item_aegis_two_line, parent, false);
            }
            AegisIdentity.Thread t = getItem(position);
            String preview = "";
            String right = "";
            if (!t.messages.isEmpty()) {
                AegisIdentity.Message last = t.messages.get(t.messages.size() - 1);
                preview = (last.fromMe ? "You: " : "") + last.text;
                right = mFmt.format(new Date(last.time));
            }
            ((TextView) v.findViewById(R.id.row_title)).setText(t.name);
            ((TextView) v.findViewById(R.id.row_subtitle)).setText(preview);
            ((TextView) v.findViewById(R.id.row_right)).setText(right);
            return v;
        }
    }
}
