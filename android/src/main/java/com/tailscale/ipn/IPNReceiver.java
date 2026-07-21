// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.tailscale.ipn.wear.TailwareActivationCapability;
import com.tailscale.ipn.wear.WearMainActivity;

import java.util.Objects;

/**
 * IPNReceiver allows external applications to start the VPN.
 */
public class IPNReceiver extends BroadcastReceiver {

    public static final String INTENT_CONNECT_VPN = "com.tailscale.ipn.CONNECT_VPN";
    public static final String INTENT_DISCONNECT_VPN = "com.tailscale.ipn.DISCONNECT_VPN";
    public static final String INTENT_INTEGRATION_LOGIN = "com.tailscale.ipn.integration.LOGIN";
    private static final String INTENT_USE_EXIT_NODE = "com.tailscale.ipn.USE_EXIT_NODE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        final String action = intent.getAction();

        if (Objects.equals(action, INTENT_CONNECT_VPN)) {
            // A notification/broadcast cannot mint process authority or launch platform VPN
            // consent. It may reuse only an exact still-live Connect capability; otherwise route
            // the user through the passive Activity and its separate Connect button.
            if (!TailwareActivationCapability.hasVpnCapability()
                    || !UninitializedApp.get().startVPN()) {
                openPassiveApp(context);
            }

        } else if (Objects.equals(action, INTENT_DISCONNECT_VPN)) {
            // Reserve ordering now, then persist/dispatch on IO. The coordinator makes a later
            // Connect supersede this queued request before it can write OFF; the broadcast thread
            // never waits behind SharedPreferences or a slow LocalAPI operation.
            if (!TailwareActivationCapability.hasEngineCapability()) {
                openPassiveApp(context);
                return;
            }
            UninitializedApp app = UninitializedApp.get();
            PendingResult pendingResult = goAsync();
            app.stopVPNCommandAsync(
                    dispatch -> {
                        try {
                            if (!dispatch.getSuperseded() && !dispatch.getStopSafelyHandled()) {
                                StartVPNWorker.notifyVpnStopFailed(app);
                            }
                        } finally {
                            pendingResult.finish();
                        }
                    });

        } else if (Objects.equals(action, INTENT_USE_EXIT_NODE)) {
            // Retired for P0 containment. A persisted/background Worker cannot prove a current
            // foreground activation gesture.
            openPassiveApp(context);
        } else if (BuildConfig.DEBUG && Objects.equals(action, INTENT_INTEGRATION_LOGIN)) {
            // Debug automation is intentionally unavailable in the production component graph.
            openPassiveApp(context);
        }
    }

    private static void openPassiveApp(Context context) {
        Intent activity = new Intent(context, WearMainActivity.class);
        try {
            PendingIntent.getActivity(
                            context,
                            0,
                            activity,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    .send();
        } catch (PendingIntent.CanceledException | RuntimeException ignored) {
            // Background-start policy may refuse the launch. The important invariant is that the
            // receiver still performs no engine, login, VPN, or WorkManager action.
        }
    }
}
