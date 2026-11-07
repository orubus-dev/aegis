package com.aegis.os.apps;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridLayout;
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
 * Aegis OS > Phone: a full dialpad for the simulated handset.  "Calling"
 * never dials anything -- it records an entry in the sandbox's own call
 * log, which lives entirely on this device.
 */
public class AegisPhoneActivity extends Activity {

    private final StringBuilder mNumber = new StringBuilder();
    private TextView mDisplay;
    private final List<AegisIdentity.CallEntry> mLog = new ArrayList<>();
    private LogAdapter mLogAdapter;
    private final SimpleDateFormat mFmt =
            new SimpleDateFormat("MMM d, HH:mm", Locale.US);

    private static final String[][] PAD = {
            {"1", ""}, {"2", "ABC"}, {"3", "DEF"},
            {"4", "GHI"}, {"5", "JKL"}, {"6", "MNO"},
            {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"},
            {"*", ""}, {"0", "+"}, {"#", ""}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aegis_phone);

        mDisplay = findViewById(R.id.aegis_phone_number);
        buildPad();
        findViewById(R.id.aegis_phone_backspace).setOnClickListener(v -> {
            if (mNumber.length() > 0) {
                mNumber.deleteCharAt(mNumber.length() - 1);
                refreshDisplay();
            }
        });
        findViewById(R.id.aegis_phone_backspace).setOnLongClickListener(v -> {
            mNumber.setLength(0);
            refreshDisplay();
            return true;
        });
        findViewById(R.id.aegis_phone_call).setOnClickListener(v -> placeCall());

        mLogAdapter = new LogAdapter();
        ListView log = findViewById(R.id.aegis_phone_log);
        log.setAdapter(mLogAdapter);
        log.setOnItemClickListener((parent, view, position, id) -> {
            AegisIdentity.CallEntry e = mLog.get(position);
            mNumber.setLength(0);
            mNumber.append(e.phone);
            refreshDisplay();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> {
            final List<AegisIdentity.CallEntry> fresh = AegisIdentity.calls(this);
            runOnUiThread(() -> {
                mLog.clear();
                mLog.addAll(fresh);
                mLogAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void buildPad() {
        GridLayout pad = findViewById(R.id.aegis_phone_pad);
        for (String[] key : PAD) {
            TextView btn = new TextView(this);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(0, dp(14), 0, dp(14));
            btn.setTextSize(22);
            btn.setTextColor(0xFFE8ECF1);
            btn.setText(key[0]);
            if (!key[1].isEmpty()) {
                btn.setText(key[0] + "\n" + key[1]);
                btn.setTextSize(16);
            }
            btn.setBackgroundColor(0xFF151B23);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                if (mNumber.length() < 20) {
                    mNumber.append(key[0]);
                    refreshDisplay();
                }
            });
            pad.addView(btn);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshDisplay() {
        mDisplay.setText(mNumber.length() == 0
                ? getString(R.string.aegis_phone_enter_number)
                : mNumber.toString());
    }

    private void placeCall() {
        final String number = mNumber.toString().trim();
        if (number.isEmpty()) {
            return;
        }
        String known = lookupName(number);
        final String name = known != null ? known : number;
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage(R.string.aegis_phone_sim_notice)
                .setPositiveButton(R.string.aegis_phone_call, (d, w) -> {
                    AegisIdentity.logCall(this, known != null ? known : "", number, true);
                    mLog.clear();
                    mLog.addAll(AegisIdentity.calls(this));
                    mLogAdapter.notifyDataSetChanged();
                })
                .setNegativeButton(R.string.aegis_cancel, null)
                .show();
    }

    private String lookupName(String number) {
        for (AegisIdentity.CallEntry e : mLog) {
            if (number.equals(e.phone) && e.name != null && !e.name.isEmpty()) {
                return e.name;
            }
        }
        return null;
    }

    private class LogAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mLog.size();
        }

        @Override
        public AegisIdentity.CallEntry getItem(int position) {
            return mLog.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AegisPhoneActivity.this)
                        .inflate(R.layout.item_aegis_two_line, parent, false);
            }
            AegisIdentity.CallEntry e = getItem(position);
            String title = (e.name == null || e.name.isEmpty()) ? e.phone : e.name;
            ((TextView) v.findViewById(R.id.row_title)).setText(title);
            ((TextView) v.findViewById(R.id.row_subtitle)).setText(
                    getString(e.outgoing
                            ? R.string.aegis_phone_outgoing
                            : R.string.aegis_phone_incoming));
            ((TextView) v.findViewById(R.id.row_right)).setText(mFmt.format(new Date(e.time)));
            return v;
        }
    }
}
