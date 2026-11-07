package com.aegis.os.apps;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import io.virtualapp.R;
import com.aegis.os.identity.AegisIdentity;

import java.util.ArrayList;
import java.util.List;

/**
 * Aegis OS > Contacts: the sandbox's own address book.  Generated on first
 * run, fully editable, stored on-device only and never synced anywhere.
 */
public class AegisContactsActivity extends Activity {

    private final List<AegisIdentity.Contact> mContacts = new ArrayList<>();
    private ContactAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_aegis_identity_list);

        ((TextView) findViewById(R.id.aegis_list_title)).setText(R.string.aegis_contacts_title);
        ((TextView) findViewById(R.id.aegis_list_subtitle)).setText(R.string.aegis_contacts_subtitle);
        TextView action = findViewById(R.id.aegis_list_action);
        action.setText(R.string.aegis_contacts_add);
        action.setOnClickListener(v -> editContact(null));

        mAdapter = new ContactAdapter();
        ListView list = findViewById(R.id.aegis_list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> editContact(mContacts.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            AegisIdentity.Contact c = mContacts.get(position);
            new AlertDialog.Builder(this)
                    .setTitle(c.name)
                    .setMessage(R.string.aegis_contacts_delete_confirm)
                    .setPositiveButton(R.string.aegis_delete, (d, w) -> {
                        mContacts.remove(position);
                        persist();
                    })
                    .setNegativeButton(R.string.aegis_cancel, null)
                    .show();
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
            final List<AegisIdentity.Contact> fresh = AegisIdentity.contacts(this);
            runOnUiThread(() -> {
                mContacts.clear();
                mContacts.addAll(fresh);
                mAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void persist() {
        final List<AegisIdentity.Contact> snapshot = new ArrayList<>(mContacts);
        new Thread(() -> AegisIdentity.saveContacts(this, snapshot)).start();
        mAdapter.notifyDataSetChanged();
    }

    private void editContact(final AegisIdentity.Contact existing) {
        LinearLayout box = (LinearLayout) LayoutInflater.from(this)
                .inflate(R.layout.dialog_aegis_contact, null);
        final EditText name = box.findViewById(R.id.contact_name);
        final EditText phone = box.findViewById(R.id.contact_phone);
        final EditText email = box.findViewById(R.id.contact_email);
        final EditText org = box.findViewById(R.id.contact_org);
        if (existing != null) {
            name.setText(existing.name);
            phone.setText(existing.phone);
            email.setText(existing.email);
            org.setText(existing.org);
        }
        new AlertDialog.Builder(this)
                .setTitle(existing == null
                        ? R.string.aegis_contacts_add
                        : R.string.aegis_contacts_edit)
                .setView(box)
                .setPositiveButton(R.string.aegis_save, (d, w) -> {
                    String n = name.getText().toString().trim();
                    String p = phone.getText().toString().trim();
                    if (n.isEmpty() || p.isEmpty()) {
                        Toast.makeText(this, R.string.aegis_contacts_need_name_phone,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (existing != null) {
                        existing.name = n;
                        existing.phone = p;
                        existing.email = email.getText().toString().trim();
                        existing.org = org.getText().toString().trim();
                    } else {
                        AegisIdentity.Contact c = new AegisIdentity.Contact();
                        c.id = String.valueOf(System.currentTimeMillis());
                        c.name = n;
                        c.phone = p;
                        c.email = email.getText().toString().trim();
                        c.org = org.getText().toString().trim();
                        mContacts.add(0, c);
                    }
                    persist();
                })
                .setNegativeButton(R.string.aegis_cancel, null)
                .show();
    }

    private class ContactAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mContacts.size();
        }

        @Override
        public AegisIdentity.Contact getItem(int position) {
            return mContacts.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AegisContactsActivity.this)
                        .inflate(R.layout.item_aegis_two_line, parent, false);
            }
            AegisIdentity.Contact c = getItem(position);
            ((TextView) v.findViewById(R.id.row_title)).setText(c.name);
            String sub = c.phone;
            if (c.org != null && !c.org.isEmpty()) {
                sub += " \u2022 " + c.org;
            }
            if (c.email != null && !c.email.isEmpty()) {
                sub += " \u2022 " + c.email;
            }
            ((TextView) v.findViewById(R.id.row_subtitle)).setText(sub);
            ((TextView) v.findViewById(R.id.row_right)).setText("");
            return v;
        }
    }
}
