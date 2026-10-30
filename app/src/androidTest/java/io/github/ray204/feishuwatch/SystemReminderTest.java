package io.github.ray204.feishuwatch;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import static org.junit.Assert.*;

/** Runs on an API 30 emulator; these assertions do not establish OPPO OEM behavior. */
@RunWith(AndroidJUnit4.class)
public class SystemReminderTest {
    private Context context;
    private LocalStore store;
    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext(); store = new LocalStore(context);
        ReminderEngine.clear(context); ReminderEngine.channels(context);
    }
    @After public void cleanup() { ReminderEngine.clear(context); }
    private JSONObject snapshot(long start) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject event = new JSONObject().put("id", "instrumented-fixture").put("title", "集成测试日程").put("startMs", start).put("endMs", start + 3600000)
            .put("allDay", false).put("startDate", "").put("endDate", "").put("location", "虚构会议室");
        return new JSONObject().put("syncedAt", now).put("validUntil", now + 86400000).put("events", new JSONArray().put(event));
    }
    private StatusBarNotification eventNotification() {
        for (StatusBarNotification n : context.getSystemService(NotificationManager.class).getActiveNotifications()) if (n.getId() == 20) return n;
        return null;
    }
    private StatusBarNotification awaitEvent(boolean present) {
        StatusBarNotification value = eventNotification();
        for (int i = 0; i < 80 && (value != null) != present; i++) { SystemClock.sleep(50); value = eventNotification(); }
        assertEquals("NotificationManager should finish the asynchronous update", present, value != null);
        return value;
    }
    @Test public void cachedReminderPostsWithoutActivityAndSnoozesThroughSystemAction() throws Exception {
        long start = System.currentTimeMillis() + 60000;
        store.snapshot(snapshot(start)); store.prefs.edit().putBoolean("demo", true).commit();
        ReminderEngine.reconcile(context, true);
        StatusBarNotification notification = awaitEvent(true);
        assertEquals("集成测试日程", notification.getNotification().extras.getString(Notification.EXTRA_TITLE));
        assertEquals(ReminderEngine.ALERTS, notification.getNotification().getChannelId());
        assertTrue(store.delivered().contains("instrumented-fixture@" + start + ":10"));
        assertEquals(1, notification.getNotification().actions.length);
        long beforeAction = System.currentTimeMillis();
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        notification.getNotification().actions[0].actionIntent.send(context, 0, null,
            (pending, intent, code, data, extras) -> finished.countDown(), new android.os.Handler(android.os.Looper.getMainLooper()));
        assertTrue("System must finish delivering the snooze broadcast", finished.await(20, java.util.concurrent.TimeUnit.SECONDS));
        Long snoozed = store.snoozes().get("instrumented-fixture@" + start);
        assertNotNull(snoozed);
        assertTrue(snoozed >= beforeAction + 300000);
        assertTrue(snoozed <= System.currentTimeMillis() + 300000);
        assertNull(awaitEvent(false));
        ReminderEngine.reconcile(context, true); assertNull(eventNotification());
    }
    @Test public void cancellationRemovesDeliveredNotificationAndSnapshotReplacementPersists() throws Exception {
        store.snapshot(snapshot(System.currentTimeMillis() + 60000)); store.prefs.edit().putBoolean("demo", true).commit();
        ReminderEngine.reconcile(context, true); assertNotNull(awaitEvent(true));
        JSONObject empty = snapshot(System.currentTimeMillis()).put("events", new JSONArray()); store.snapshot(empty);
        ReminderEngine.reconcile(context, true);
        assertNull(awaitEvent(false)); assertTrue(new LocalStore(context).events().isEmpty()); assertTrue(store.delivered().isEmpty());
    }
    @Test public void systemAlarmDeliversAfterActivityLeavesForeground() throws Exception {
        long start = System.currentTimeMillis() + 5000;
        store.snapshot(snapshot(start)); store.prefs.edit().putBoolean("demo", true).putInt("minutes", 0).commit();
        ReminderEngine.reconcile(context, false); assertNull(eventNotification());
        for (int i = 0; i < 200 && eventNotification() == null; i++) SystemClock.sleep(100);
        assertNotNull("AlarmManager should launch the receiver without an Activity", eventNotification());
    }
    @Test public void deviceSecretUsesKeystoreAndSurvivesStoreRecreation() throws Exception {
        store.secret("deviceToken", "fixture-only-device-token");
        assertFalse(store.prefs.getString("deviceToken", "").contains("fixture-only-device-token"));
        assertEquals("fixture-only-device-token", new LocalStore(context).secret("deviceToken"));
    }
    @Test public void watchSizedDashboardRendersWithFixtureData() throws Exception {
        store.snapshot(snapshot(System.currentTimeMillis() + 660000)); store.prefs.edit().putBoolean("demo", true).commit();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync(); SystemClock.sleep(500);
        android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        java.io.File directory = new java.io.File(context.getExternalFilesDir(null), "screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, "dashboard.png"))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        }
        instrumentation.runOnMainSync(activity::finish);
    }
}
