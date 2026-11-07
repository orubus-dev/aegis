package io.virtualapp.settings;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.view.Gravity;
import android.view.View;

import java.util.Calendar;

import io.virtualapp.R;
import io.virtualapp.abs.ui.VActivity;
import mehdi.sakout.aboutpage.AboutPage;
import mehdi.sakout.aboutpage.Element;

/**
 * Aegis About page.
 *
 * Aegis is a fork of VirtualXposed (which itself builds on VirtualApp);
 * upstream credits live in the Credits dialog.  Everything here points at
 * the Aegis project: https://github.com/orubus-dev/aegis
 */
public class AboutActivity extends VActivity {

    public static final String GITHUB_REPO = "https://github.com/orubus-dev/aegis";
    public static final String GITHUB_ISSUES = "https://github.com/orubus-dev/aegis/issues";

    private AboutPage mPage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPage = new AboutPage(this)
                .isRTL(false)
                .setImage(R.mipmap.ic_launcher)
                .addItem(getCopyRightsElement())
                .addItem(getVersionElement())
                .addItem(getGitHubElement())
                .addItem(getIssuesElement())
                .addItem(getThanksElement())
                .addGitHub("orubus-dev/aegis");

        View aboutPage = mPage.create();

        setContentView(aboutPage);
    }

    Element getCopyRightsElement() {
        Element copyRightsElement = new Element();
        final String copyrights = String.format(getString(R.string.copy_right), Calendar.getInstance().get(Calendar.YEAR));
        copyRightsElement.setTitle(copyrights);
        copyRightsElement.setGravity(Gravity.START);
        return copyRightsElement;
    }

    Element getVersionElement() {
        Element version = new Element();
        String versionName = "unknown";
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        version.setTitle(getResources().getString(R.string.about_version_title, versionName));
        return version;
    }

    Element getGitHubElement() {
        Element github = new Element();
        github.setTitle(getResources().getString(R.string.about_github_title));
        github.setOnClickListener(v -> openUrl(GITHUB_REPO));
        return github;
    }

    Element getIssuesElement() {
        Element issues = new Element();
        issues.setTitle(getResources().getString(R.string.about_issues_title));
        issues.setOnClickListener(v -> openUrl(GITHUB_ISSUES));
        return issues;
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        try {
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    Element getThanksElement() {
        Element thanks = new Element();
        thanks.setTitle(getResources().getString(R.string.about_thanks));
        thanks.setOnClickListener(v -> {
            AlertDialog alertDialog = new AlertDialog.Builder(this, R.style.Theme_AppCompat_DayNight_Dialog_Alert)
                    .setTitle(R.string.thanks_dialog_title)
                    .setMessage(R.string.thanks_dialog_content)
                    .setPositiveButton(R.string.about_icon_yes, null)
                    .create();
            try {
                alertDialog.show();
            } catch (Throwable ignored) {
                // BadTokenException.
            }
        });
        return thanks;
    }
}
