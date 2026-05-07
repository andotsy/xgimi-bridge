package com.xgimi.bridge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BridgeJobService extends JobService {
    public static final int JOB_ID = 7331;
    private static final String TAG = "XgimiBridge";

    /** Schedule with deadline=0 -- fires ASAP. Use for install/launch (not from onStartJob). */
    public static void scheduleSelf(Context ctx) {
        scheduleWith(ctx, 0L, 0L, "ASAP");
    }

    /** Schedule with a short delay so the system does not infinite-loop while the bridge is
     *  already running. After any reboot, the persisted absolute target time is in the past
     *  (or within the 30-90s window), so the job fires almost immediately on boot. */
    public static void scheduleNextBoot(Context ctx) {
        scheduleWith(ctx, 30_000L, 90_000L, "30-90s");
    }

    private static void scheduleWith(Context ctx, long minLatency, long deadline, String label) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        // Cancel any stale persisted state from previous installs to avoid surprises
        js.cancel(JOB_ID);
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, BridgeJobService.class))
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setMinimumLatency(minLatency)
                .setOverrideDeadline(deadline);
        int r = js.schedule(b.build());
        Log.i(TAG, "BridgeJobService scheduled (" + label + "), result=" + r);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "JobService onStartJob -- starting BridgeService");
        Intent svc = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        // Re-arm so the job survives the next reboot. 30-90s window is the loop breaker;
        // after a reboot, the persisted absolute deadline will already be past, so it fires ASAP.
        scheduleNextBoot(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; }
}
