package io.github.ray204.feishuwatch;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

public final class SyncJob extends JobService {
    public static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    public static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        jobs.schedule(new JobInfo.Builder(41, new ComponentName(context, SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60_000L).setPersisted(true).build());
    }
    public static void refresh(Context context) {
        context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(42, new ComponentName(context, SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1).build());
    }
    public static void sync(Context context) {
        LocalStore store = new LocalStore(context);
        if (!store.connected()) return;
        try {
            org.json.JSONObject snapshot = Bridge.request(store, "GET", "/v1/agenda", store.secret("deviceToken"));
            synchronized (ReminderEngine.class) { store.snapshot(snapshot); }
        }
        catch (Exception e) { store.error(e instanceof java.io.IOException ? e.getMessage() : "同步失败，请检查服务或重新连接"); }
        ReminderEngine.reconcile(context, true);
    }
    @Override public boolean onStartJob(JobParameters params) {
        WORK.execute(() -> { sync(this); jobFinished(params, false); }); return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
}
