package io.github.ray204.feishuwatch;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.*;

public final class ReminderEngine {
    static final String ALERTS = "event_reminders_v1", SUMMARY = "agenda_summary_v1";
    static final int SUMMARY_ID = 10, TEST_ID = 12;
    public static void channels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel alert = new NotificationChannel(ALERTS, "日程提醒", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("会前震动及稍后提醒"); alert.enableVibration(true); alert.setVibrationPattern(new long[]{0, 200, 100, 300});
        alert.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationChannel summary = new NotificationChannel(SUMMARY, "下一场日程", NotificationManager.IMPORTANCE_LOW);
        summary.setSound(null, null); summary.enableVibration(false);
        manager.createNotificationChannel(alert); manager.createNotificationChannel(summary);
    }
    public static PendingIntent action(Context context, String action, String key) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(action).setData(Uri.parse("feishuwatch://" + action + "/" + Uri.encode(key))).putExtra("key", key);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static PendingIntent open(Context context) { return PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
    public static boolean exact(Context context) { return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms(); }
    static void alarm(Context context, long time, PendingIntent intent) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        try {
            if (exact(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent);
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent);
        } catch (SecurityException e) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent); }
    }
    public static String time(Event event) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate date = java.time.Instant.ofEpochMilli(event.startMs).atZone(zone).toLocalDate();
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        String day = date.equals(today) ? "今天 " : date.equals(today.plusDays(1)) ? "明天 " : new SimpleDateFormat("MM/dd ", Locale.getDefault()).format(new Date(event.startMs));
        return day + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(event.startMs));
    }
    private static Notification.Builder builder(Context context, String channel) {
        return new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_calendar).setColor(0xFFB9F68B)
            .setContentIntent(open(context)).setVisibility(Notification.VISIBILITY_PRIVATE).setLocalOnly(true);
    }
    static boolean notify(Context context, String tag, int id, Notification notification) {
        if (!new LocalStore(context).remindersEnabled()) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(notification.getChannelId());
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        try { manager.notify(tag, id, notification); return true; } catch (SecurityException e) { return false; }
    }
    // Local delivery never waits behind network I/O (BroadcastReceiver has a short deadline).
    public static synchronized void reconcile(Context context, boolean deliver) {
        channels(context);
        LocalStore store = new LocalStore(context);
        if (!store.remindersEnabled()) {
            context.getSystemService(AlarmManager.class).cancel(action(context, "tick", ""));
            context.getSystemService(AlarmManager.class).cancel(action(context, "test", ""));
            context.getSystemService(NotificationManager.class).cancelAll();
            store.snoozes(Collections.emptyMap());
            AgendaWidget.update(context); return;
        }
        long now = System.currentTimeMillis();
        List<Event> events = store.events();
        Set<String> delivered = store.delivered();
        Map<String, Long> snoozes = store.snoozes();
        Set<String> activeKeys = new HashSet<>(), deliveryKeys = new HashSet<>();
        for (Event e : events) if (e.endMs > now && !e.allDay) { activeKeys.add(e.key()); deliveryKeys.add(ReminderPolicy.deliveryKey(e, store.minutes())); }
        delivered.retainAll(deliveryKeys); snoozes.keySet().retainAll(activeKeys);
        // Cancel notifications whose event was removed, rescheduled, or whose cache expired.
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications()) {
            if (n.getId() == 20 && (!activeKeys.contains(n.getTag()) || now >= store.validUntil())) manager.cancel(n.getTag(), 20);
        }
        boolean blocked = false;
        if (deliver) {
            for (Event event : ReminderPolicy.due(events, delivered, snoozes, store.minutes(), now, store.validUntil())) {
                String detail = time(event) + (event.location.isEmpty() ? "" : " · " + event.location);
                Notification.Builder notification = builder(context, ALERTS).setContentTitle(event.title).setContentText(detail)
                    .setStyle(new Notification.BigTextStyle().bigText(detail)).setCategory(Notification.CATEGORY_EVENT).setAutoCancel(true)
                    .setTimeoutAfter(Math.max(1, event.endMs - now));
                if (now + 5 * ReminderPolicy.MINUTE < event.endMs) notification.addAction(new Notification.Action.Builder(null, "稍后 5 分钟", action(context, "snooze", event.key())).build());
                if (notify(context, event.key(), 20, notification.build())) {
                    delivered.add(ReminderPolicy.deliveryKey(event, store.minutes())); snoozes.remove(event.key());
                } else blocked = true;
            }
        }
        store.delivered(delivered); store.snoozes(snoozes);
        PendingIntent tick = action(context, "tick", "");
        context.getSystemService(AlarmManager.class).cancel(tick);
        if (store.connected() || store.prefs.getBoolean("demo", false)) {
            long next = ReminderPolicy.next(events, delivered, snoozes, store.minutes(), now, store.validUntil());
            if (next != Long.MAX_VALUE) alarm(context, blocked ? Math.max(next, now + 15 * ReminderPolicy.MINUTE) : next, tick);
        }
        if (store.summary() && store.syncedAt() > 0) {
            Event upcoming = ReminderPolicy.upcoming(events, now);
            String title = now >= store.validUntil() ? "日程已过期，请联网同步" : upcoming == null ? "近期暂无日程" : upcoming.title;
            String detail = upcoming == null ? "腕上日程" : (upcoming.startMs <= now ? "进行中 · " : "下一场 · ") + time(upcoming);
            if (!store.error().isEmpty()) detail += " · 同步异常";
            Notification n = builder(context, SUMMARY).setContentTitle(title).setContentText(detail).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "刷新", action(context, "refresh", "")).build()).build();
            notify(context, null, SUMMARY_ID, n);
        } else manager.cancel(SUMMARY_ID);
        AgendaWidget.update(context);
    }
    public static synchronized void snooze(Context context, String key) {
        LocalStore store = new LocalStore(context); long now = System.currentTimeMillis();
        if (!store.remindersEnabled()) return;
        if (now >= store.validUntil()) return;
        for (Event event : store.events()) if (event.key().equals(key) && now + 5 * ReminderPolicy.MINUTE < event.endMs) {
            Map<String, Long> snoozes = store.snoozes(); snoozes.put(key, now + 5 * ReminderPolicy.MINUTE); store.snoozes(snoozes);
            context.getSystemService(NotificationManager.class).cancel(key, 20); break;
        }
        reconcile(context, false);
    }
    public static void test(Context context) { if (!new LocalStore(context).remindersEnabled()) return; channels(context); alarm(context, System.currentTimeMillis() + 15_000, action(context, "test", "")); }
    static void showTest(Context context) {
        channels(context); notify(context, null, TEST_ID, builder(context, ALERTS).setContentTitle("测试提醒已到达")
            .setContentText("这是测试，不是真实日程").setAutoCancel(true).build());
    }
    public static synchronized void clear(Context context) {
        context.getSystemService(AlarmManager.class).cancel(action(context, "tick", ""));
        context.getSystemService(AlarmManager.class).cancel(action(context, "test", ""));
        context.getSystemService(NotificationManager.class).cancelAll();
        context.getSystemService(android.app.job.JobScheduler.class).cancelAll();
        new LocalStore(context).clear(); AgendaWidget.update(context);
    }
}
