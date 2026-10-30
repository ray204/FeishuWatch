package io.github.ray204.feishuwatch;

import android.content.*;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("snooze".equals(action)) ReminderEngine.snooze(context, intent.getStringExtra("key"));
        else if ("test".equals(action)) ReminderEngine.showTest(context);
        else if ("refresh".equals(action)) SyncJob.refresh(context);
        else {
            ReminderEngine.reconcile(context, true);
            if (new LocalStore(context).connected()) { SyncJob.schedule(context); if (!"tick".equals(action)) SyncJob.refresh(context); }
        }
    }
}
