package io.github.ray204.feishuwatch;

import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;

public final class AgendaWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { update(context); }
    public static void update(Context context) {
        LocalStore store = new LocalStore(context); long now = System.currentTimeMillis();
        Event event = ReminderPolicy.upcoming(store.events(), now);
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.widget);
        String title = !store.connected() && !store.prefs.getBoolean("demo", false) ? "点击连接飞书" : now >= store.validUntil() ? "日程已过期" : event == null ? "暂无日程" : event.title;
        view.setTextViewText(R.id.widget_title, title);
        view.setTextViewText(R.id.widget_time, event != null && now < store.validUntil() ? (event.startMs <= now ? "进行中 " : "下一场 ") + ReminderEngine.time(event) : "腕上日程");
        view.setOnClickPendingIntent(R.id.widget_title, ReminderEngine.open(context));
        view.setOnClickPendingIntent(R.id.widget_refresh, ReminderEngine.action(context, "refresh", ""));
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        manager.updateAppWidget(new ComponentName(context, AgendaWidget.class), view);
    }
}
