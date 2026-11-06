package com.aegis.os.vault;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;

import io.virtualapp.R;

/**
 * Decrypt-on-view screen.  The plaintext exists only in the private cache
 * dir; VaultListActivity shreds it on exit.
 */
public class VaultViewActivity extends Activity {

    private File mPlainFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vault_view);
        String path = getIntent().getStringExtra("path");
        String name = getIntent().getStringExtra("name");
        String mime = getIntent().getStringExtra("mime");
        TextView title = findViewById(R.id.vault_view_name);
        title.setText(name != null ? name : "");
        if (path == null) {
            finish();
            return;
        }
        mPlainFile = new File(path);
        if (mime != null && mime.startsWith("video/")) {
            VideoView video = findViewById(R.id.vault_view_video);
            ImageView image = findViewById(R.id.vault_view_image);
            image.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            MediaController mc = new MediaController(this);
            mc.setAnchorView(video);
            video.setMediaController(mc);
            video.setVideoURI(Uri.fromFile(mPlainFile));
            video.setOnPreparedListener(MediaPlayer::start);
        } else {
            VideoView video = findViewById(R.id.vault_view_video);
            video.setVisibility(View.GONE);
            ImageView image = findViewById(R.id.vault_view_image);
            image.setImageBitmap(BitmapFactory.decodeFile(mPlainFile.getAbsolutePath()));
        }
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mPlainFile != null) {
            VaultCrypto.shred(mPlainFile);
        }
        VaultStore.shredViewCache(this);
        super.onDestroy();
    }
}
