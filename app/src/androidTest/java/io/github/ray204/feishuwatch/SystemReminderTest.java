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
        store.prefs.edit().putBoolean("remindersEnabled", true).commit();
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
    @Test public void viewOnlyModeClearsNotificationsAndKeepsCalendarAndCredentials() throws Exception {
        store.prefs.edit().remove("remindersEnabled").commit(); assertFalse(store.remindersEnabled());
        long start = System.currentTimeMillis() + 60000;
        store.snapshot(snapshot(start)); store.secret("deviceToken", "fixture-preserved-token");
        store.prefs.edit().putBoolean("remindersEnabled", true).commit();
        ReminderEngine.reconcile(context, true); assertNotNull(awaitEvent(true)); long syncedAt = store.syncedAt();
        store.prefs.edit().putBoolean("remindersEnabled", false).commit(); ReminderEngine.reconcile(context, true);
        assertNull(awaitEvent(false));
        ReminderEngine.snooze(context, "instrumented-fixture@" + start);
        ReminderEngine.showTest(context); ReminderEngine.reconcile(context, true);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (int i = 0; i < 80 && manager.getActiveNotifications().length != 0; i++) SystemClock.sleep(50);
        assertEquals(0, manager.getActiveNotifications().length); assertTrue(store.snoozes().isEmpty());
        assertEquals(1, store.events().size()); assertEquals(syncedAt, store.syncedAt());
        assertEquals("fixture-preserved-token", store.secret("deviceToken"));
        assertFalse(new LocalStore(context).remindersEnabled());
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
        notification.getNotification().actions[0].actionIntent.send();
        // OnFinished is not the user-visible outcome. Wait for the receiver's
        // persisted result, then check the full time window and notification.
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (!store.snoozes().containsKey("instrumented-fixture@" + start) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
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
    @Test public void directCredentialsAreEncryptedImportedOnceAndClearedOnDisconnect() throws Exception {
        java.io.File file = new java.io.File(context.getFilesDir(), "direct-config.json");
        java.nio.file.Files.write(file.toPath(), "{\"appId\":\"cli_fixture\",\"appSecret\":\"fixture-only-app-secret\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        store.importDirectConfig(context); assertFalse(file.exists()); assertTrue(store.direct()); assertFalse(store.connected());
        String stored = store.prefs.getString("directAuth", "");
        assertFalse(stored.contains("fixture-only-app-secret")); assertFalse(stored.contains("appSecret"));
        assertEquals("fixture-only-app-secret", new LocalStore(context).directAuth().getString("appSecret"));
        JSONObject auth = store.directAuth().put("accessToken", "fixture-access").put("refreshToken", "fixture-refresh");
        store.secret("directAuth", auth.toString()); assertTrue(new LocalStore(context).connected());
        assertFalse(store.prefs.getString("directAuth", "").contains("fixture-refresh"));
        ReminderEngine.clear(context); assertFalse(store.direct()); assertFalse(store.connected()); assertEquals("",store.secret("directAuth"));
    }
    @Test public void malformedImportIsDeletedWithoutReplacingExistingCredentials() throws Exception {
        store.directClient().configure("cli_fixture", "original-fixture-secret");
        java.io.File file = new java.io.File(context.getFilesDir(), "direct-config.json");
        java.nio.file.Files.write(file.toPath(), "{\"appId\":\"bad\",\"appSecret\":\"\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try { store.importDirectConfig(context); fail("Invalid config must fail"); } catch (java.io.IOException expected) { }
        assertFalse(file.exists()); assertEquals("original-fixture-secret",store.directAuth().getString("appSecret"));
    }
    private android.widget.TextView textView(android.view.View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return (android.widget.TextView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { android.widget.TextView found = textView(group.getChildAt(i), text); if (found != null) return found; }
        }
        return null;
    }
    @Test public void dashboardActionsStayBelowFirstScreenAndRemainReachableByScrolling() throws Exception {
        store.snapshot(snapshot(System.currentTimeMillis() + 660000)); store.prefs.edit().putBoolean("demo", true).commit();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            android.view.ViewGroup root = activity.findViewById(android.R.id.content);
            android.widget.TextView agenda = textView(root, "日程列表"), settings = textView(root, "设置");
            assertNotNull(agenda); assertNotNull(settings);
            assertFalse(agenda.getGlobalVisibleRect(new android.graphics.Rect())); assertFalse(settings.getGlobalVisibleRect(new android.graphics.Rect()));
            assertTrue(textView(root, "集成测试日程").getGlobalVisibleRect(new android.graphics.Rect()));
            android.widget.ScrollView scroll = (android.widget.ScrollView) root.getChildAt(0);
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            android.view.ViewGroup root = activity.findViewById(android.R.id.content);
            android.widget.TextView agenda = textView(root, "日程列表");
            assertTrue(agenda.getGlobalVisibleRect(new android.graphics.Rect())); agenda.performClick();
            assertNotNull(textView(activity.findViewById(android.R.id.content), "未来日程")); activity.finish();
        });
    }
    @Test public void watchSizedDashboardRendersWithFixtureData() throws Exception {
        store.snapshot(snapshot(System.currentTimeMillis() + 660000)); store.prefs.edit().putBoolean("demo", true).commit();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync(); SystemClock.sleep(500);
        android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        java.io.File directory = new java.io.File(context.getFilesDir(), "screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, "dashboard.png"))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        }
        instrumentation.runOnMainSync(activity::finish);
    }
}
