// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.tailscale.ipn.util.TSLog;
import com.tailscale.ipn.wear.localization.TailwareStrings;

/**
 * A worker that exists to support IPNReceiver.
 */
public final class StartVPNWorker extends Worker {
    public static final String NOTIFICATION_CHANNEL_ID = "start_vpn_channel";
    public static final int NOTIFICATION_ID = 7104;
    public static final String STOP_FAILURE_NOTIFICATION_CHANNEL_ID = "stop_vpn_failure_channel";
    public static final int STOP_FAILURE_NOTIFICATION_ID = 7105;

    public StartVPNWorker(Context appContext, WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        /*
         * Retired compatibility shell.
         *
         * Builds before Tailware private.6 queued this Worker as unique work named
         * "ipn-connect-vpn". WorkManager persists that request across an in-place APK update and
         * instantiates this class from the new APK afterwards. Executing the old request here
         * would create a brand-new desired-state generation and could therefore override the
         * user's newer choice. Current connect broadcasts claim their generation synchronously in
         * IPNReceiver; legacy persisted work must never mutate VPN state.
         */
        return Result.success();
    }

    public static void notifyVpnSetupRequired(@NonNull UninitializedApp app) {
        try {
            NotificationManager notificationManager =
                    (NotificationManager)
                            app.getSystemService(Context.NOTIFICATION_SERVICE);
            app.createNotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    TailwareStrings.selectedText(app, R.string.vpn_start),
                    TailwareStrings.selectedText(
                            app,
                            R.string.notifications_delivered_when_user_interaction_is_required_to_establish_the_vpn_tunnel),
                    NotificationManager.IMPORTANCE_HIGH);
            notificationManager.notify(NOTIFICATION_ID, buildNotification(app));
        } catch (RuntimeException error) {
            // Notification permission and OEM notification services can fail independently of the
            // receiver action. The caller has already kept the VPN stopped; never crash it here.
            TSLog.e("StartVPNWorker", "Unable to publish the VPN setup requirement: " + error);
        }
    }

    public static void notifyVpnStopFailed(@NonNull UninitializedApp app) {
        try {
            NotificationManager notificationManager =
                    (NotificationManager)
                            app.getSystemService(Context.NOTIFICATION_SERVICE);
            app.createNotificationChannel(
                    STOP_FAILURE_NOTIFICATION_CHANNEL_ID,
                    TailwareStrings.selectedText(app, R.string.wear_error),
                    TailwareStrings.selectedText(
                            app,
                            R.string.wear_android_did_not_allow_tailware_to_stop_the_vpn_service),
                    NotificationManager.IMPORTANCE_HIGH);
            notificationManager.notify(
                    STOP_FAILURE_NOTIFICATION_ID, buildStopFailureNotification(app));
        } catch (RuntimeException error) {
            // Notification permission can be denied independently of the receiver action. The
            // failed service stop has already been logged by the caller; never crash the receiver.
            TSLog.e("StartVPNWorker", "Unable to publish the VPN stop failure: " + error);
        }
    }

    public static void refreshActiveNotifications(
            @NonNull Context context,
            boolean refreshVpnSetupRequired,
            boolean refreshVpnStopFailure) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        UninitializedApp app = UninitializedApp.get();
        if (refreshVpnSetupRequired) {
            try {
                manager.notify(NOTIFICATION_ID, buildNotification(app));
            } catch (RuntimeException error) {
                TSLog.e(
                        "StartVPNWorker",
                        "Unable to refresh the VPN setup notification: " + error);
            }
        }
        if (refreshVpnStopFailure) {
            try {
                manager.notify(
                        STOP_FAILURE_NOTIFICATION_ID, buildStopFailureNotification(app));
            } catch (RuntimeException error) {
                TSLog.e(
                        "StartVPNWorker",
                        "Unable to refresh the VPN stop failure notification: " + error);
            }
        }
    }

    private static Notification buildNotification(UninitializedApp app) {
        return buildNotification(
                app,
                NOTIFICATION_CHANNEL_ID,
                TailwareStrings.selectedText(app, R.string.wear_vpn_setup_required),
                TailwareStrings.selectedText(app, R.string.wear_open_tailware));
    }

    private static Notification buildStopFailureNotification(UninitializedApp app) {
        return buildNotification(
                app,
                STOP_FAILURE_NOTIFICATION_CHANNEL_ID,
                TailwareStrings.selectedText(app, R.string.wear_error),
                TailwareStrings.selectedText(
                        app,
                        R.string.wear_android_did_not_allow_tailware_to_stop_the_vpn_service));
    }

    private static Notification buildNotification(
            UninitializedApp app, String channelId, String title, String text) {
        // Use prepareIntent if available.
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (intent == null) {
            intent = new Intent(app, com.tailscale.ipn.wear.WearMainActivity.class);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int pendingIntentFlags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(app, 0, intent, pendingIntentFlags);

        return new Notification.Builder(app, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
    }
}
