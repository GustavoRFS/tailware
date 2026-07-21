// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * A worker that exists to support IPNReceiver.
 */
public final class StopVPNWorker extends Worker {

    public StopVPNWorker(
            Context appContext,
            WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        /*
         * Retired compatibility shell. See StartVPNWorker.doWork(). An old persisted
         * "ipn-disconnect-vpn" request must not become a newer OFF generation after an APK
         * update and cancel the user's current ON choice.
         */
        return Result.success();
    }
}
