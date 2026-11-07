package com.aegis.os.apps;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import io.virtualapp.R;
import com.aegis.os.identity.AegisIdentity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One simulated conversation.  Messages live in the sandbox only; the send
 * button appends to the local store -- nothing is transmitted anywhere.
 */
public class AegisThreadActivity extends Activity {

    private String mThreadId;
    private AegisIdentity.Thread mThread;
    private final List<AegisIdentity.Message> mMessages = new ArrayList<>();
    private MsgAdapter mAdapter;
    private final SimpleDateFormat mFmt =
            new SimpleDateFormat("HH:mm", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aegis_thread);
        mThreadId = getIntent() != null ? getIntent().getStringExtra("threadId") : null;
        mAdapter = new MsgAdapter();
        ListView list = findViewById(R.id.aegis_thread_list);
        list.setAdapter(mAdapter);
        findViewById(R.id.aegis_thread_send).setOnClickListener(v -> send());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        new Thread(() -> {
            List<AegisIdentity.Thread> threads = AegisIdentity.threads(this);
            AegisIdentity.Thread found = null;
            for (AegisIdentity.Thread t : threads) {
                if (t.id != null && t.id.equals(mThreadId)) {
                    found = t;
                    break;
                }
            }
            final AegisIdentity.Thread f = found;
            runOnUiThread(() -> {
                if (f == null) {
                    finish();
                    return;
                }
                mThread = f;
                ((TextView) findViewById(R.id.aegis_thread_name)).setText(f.name);
                ((TextView) findViewById(R.id.aegis_thread_phone)).setText(
                        getString(R.string.aegis_thread_local_only,
                                f.phone == null ? "" : f.phone));
                mMessages.clear();
                mMessages.addAll(f.messages);
                mAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void send() {
        EditText input = findViewById(R.id.aegis_thread_input);
        String text = input.getText().toString().trim();
        if (text.isEmpty() || mThread == null) {
            return;
        }
        AegisIdentity.Message m = new AegisIdentity.Message();
        m.time = System.currentTimeMillis();
        m.fromMe = true;
        m.text = text;
        mThread.messages.add(m);
        input.setText("");
        final List<AegisIdentity.Thread> snapshot = new ArrayList<>();
        snapshot.add(mThread);
        // persist just this thread back into the store
        new Thread(() -> {
            List<AegisIdentity.Thread> all = AegisIdentity.threads(this);
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id != null && all.get(i).id.equals(mThread.id)) {
                    all.set(i, mThread);
                    break;
                }
            }
            AegisIdentity.saveThreads(this, all);
        }).start();
        mMessages.clear();
        mMessages.addAll(mThread.messages);
        mAdapter.notifyDataSetChanged();
    }

    private class MsgAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mMessages.size();
        }

        @Override
        public AegisIdentity.Message getItem(int position) {
            return mMessages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AegisThreadActivity.this)
                        .inflate(R.layout.item_aegis_bubble, parent, false);
            }
            AegisIdentity.Message m = getItem(position);
            TextView text = v.findViewById(R.id.bubble_text);
            TextView time = v.findViewById(R.id.bubble_time);
            text.setText(m.text);
            time.setText(mFmt.format(new Date(m.time)));
            View bubble = v.findViewById(R.id.bubble_box);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bubble.getLayoutParams();
            if (m.fromMe) {
                lp.gravity = android.view.Gravity.END;
                text.setTextColor(0xFF0B0F14);
                bubble.setBackgroundColor(0xFF3DDC84);
            } else {
                lp.gravity = android.view.Gravity.START;
                text.setTextColor(0xFFE8ECF1);
                bubble.setBackgroundColor(0xFF1C242E);
            }
            bubble.setLayoutParams(lp);
            return v;
        }
    }
}
