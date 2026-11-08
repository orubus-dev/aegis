package io.virtualapp.home;

import android.app.Activity;

import java.io.File;

import io.virtualapp.home.repo.AppDataSource;
import io.virtualapp.home.repo.AppRepository;

/**
 * @author Lody
 */
class ListAppPresenterImpl implements ListAppContract.ListAppPresenter {

        private Activity mActivity;
        private ListAppContract.ListAppView mView;
        private AppDataSource mRepository;

        private File from;

        ListAppPresenterImpl(Activity activity, ListAppContract.ListAppView view, File fromWhere) {
                mActivity = activity;
                mView = view;
                mRepository = new AppRepository(activity);
                mView.setPresenter(this);
                this.from = fromWhere;
        }

        @Override
        public void start() {
                mView.setPresenter(this);
                mView.startLoading();
                if (from == null) {
                        mRepository.getInstalledApps(mActivity)
                                // Aegis: a list-build failure used to be an
                                // unhandled rejection (silent empty drawer);
                                // surface it in diagnostics + a toast.
                                .fail(err -> {
                                        try {
                                                com.aegis.os.Diagnostics.log(mActivity,
                                                        "app list failed to build", err);
                                        } catch (Throwable ignored) {
                                        }
                                        mActivity.runOnUiThread(() -> android.widget.Toast
                                                .makeText(mActivity, "App list error: "
                                                        + err, android.widget.Toast.LENGTH_LONG)
                                                .show());
                                })
                                .done(mView::loadFinish);
                }
        }
}
