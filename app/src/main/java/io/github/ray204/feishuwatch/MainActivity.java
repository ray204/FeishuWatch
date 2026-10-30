package io.github.ray204.feishuwatch;

import android.Manifest;
import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private LocalStore store;
    private boolean active, busy, pairing;
    private String screenTitle = "";
    private TextView pairingStatus;
    private final Map<String, JSONObject> attendeeCache = new HashMap<>();
    private static final int INK = 0xFFF1F5F3, MUTED = 0xFF99ABA7, ACCENT = 0xFFB9F68B, PANEL = 0xFF172321;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (preferences, key) -> {
        if (active && "接下来".equals(screenTitle) && ("events".equals(key) || "error".equals(key))) handler.post(this::render);
    };
    private final Runnable poll = this::pollPair;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store = new LocalStore(this); ReminderEngine.channels(this);
        try { store.importDirectConfig(this); } catch (Exception e) { Toast.makeText(this, "配置导入失败，请检查本地配置或先断开连接", Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onResume() {
        super.onResume(); active = true; store.prefs.registerOnSharedPreferenceChangeListener(changes); render();
        if (store.connected()) { SyncJob.schedule(this); SyncJob.refresh(this); }
    }
    @Override protected void onPause() { super.onPause(); active = false; store.prefs.unregisterOnSharedPreferenceChangeListener(changes); handler.removeCallbacks(poll); }
    private void base(String title) {
        screenTitle = title;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xFF0C1418);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        boolean round = getResources().getConfiguration().isScreenRound();
        content.setPadding(dp(round ? 28 : 16), dp(round ? 24 : 14), dp(round ? 28 : 16), dp(round ? 38 : 24));
        scroll.addView(content); setContentView(scroll);
        TextView heading = label(title, 13, INK); heading.setGravity(Gravity.CENTER);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); heading.setPadding(0, 0, 0, dp(9));
    }
    private TextView label(String text, int size, int color) {
        TextView view = text(text, size, color);
        view.setPadding(0, dp(3), 0, dp(3)); content.addView(view); return view;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setIncludeFontPadding(false); view.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE); return view;
    }
    private void limit(TextView view, int lines) { view.setMaxLines(lines); view.setEllipsize(TextUtils.TruncateAt.END); }
    private GradientDrawable surface(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11), dp(10), dp(11), dp(10)); box.setBackground(surface(PANEL, 14));
        box.setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, surface(Color.WHITE, 14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); content.addView(box, lp); return box;
    }
    private TextView cardText(LinearLayout box, String value, int size, int color, int lines) {
        TextView view = text(value, size, color); if (lines > 0) limit(view, lines);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = box.getChildCount() == 0 ? 0 : dp(5);
        box.addView(view, lp); return view;
    }
    private void detail(String text) { label(text, 10, MUTED); }
    private Button action(String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(11); button.setTextColor(INK);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setPadding(dp(6), 0, dp(6), 0); button.setStateListAnimator(null);
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), surface(PANEL, 20), null));
        button.setOnClickListener(v -> { if (!busy) action.run(); }); return button;
    }
    private void button(String title, Runnable action) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44)); lp.topMargin = dp(5); content.addView(action(title, action), lp);
    }
    private void navigation() {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40)); lp.topMargin = dp(4); content.addView(row, lp);
        Button agenda = action("日程列表", this::agenda); agenda.setTextColor(0xFF0C1418);
        agenda.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000), surface(ACCENT, 22), null));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, 1); left.rightMargin = dp(6); row.addView(agenda, left);
        row.addView(action("设置", this::settings), new LinearLayout.LayoutParams(0, -1, 1));
    }
    private String dateLabel(long when) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date(when));
        Calendar today = Calendar.getInstance();
        if (day.equals(new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(today.getTime()))) return "今天";
        today.add(Calendar.DATE, 1);
        if (day.equals(new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(today.getTime()))) return "明天";
        return new SimpleDateFormat("M月d日 E", Locale.getDefault()).format(new Date(when));
    }
    private void render() {
        if (!active) return;
        pairing = false;
        if (!store.connected() && pairData().optLong("pairExpires") > System.currentTimeMillis()) { showPair(); return; }
        if (!store.connected() && !store.prefs.getBoolean("demo", false)) { onboarding(); return; }
        base("接下来");
        LinearLayout page = content, glance = new LinearLayout(this); glance.setOrientation(LinearLayout.VERTICAL);
        // Reserve one complete viewport for agenda content; actions start below it.
        glance.setMinimumHeight(Math.max(dp(160), getResources().getDisplayMetrics().heightPixels - dp(getResources().getConfiguration().isScreenRound() ? 48 : 38)));
        page.addView(glance, new LinearLayout.LayoutParams(-1, -2)); content = glance;
        long now = System.currentTimeMillis(); Event next = ReminderPolicy.upcoming(store.events(), now);
        if (store.prefs.getBoolean("demo", false)) detail("示例模式 · 非真实日程");
        if (next != null) {
            LinearLayout box = card();
            cardText(box, dateLabel(next.startMs) + (next.startMs <= now ? " · 进行中" : " · 下一场"), 10, MUTED, 1);
            cardText(box, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(next.startMs)) + " – " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(next.endMs)), 18, ACCENT, 1);
            cardText(box, next.title, 13, INK, 2).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            if (!next.location.isEmpty()) cardText(box, next.location, 10, MUTED, 1);
            box.setOnClickListener(v -> eventDetails(next, this::render));
            box.setContentDescription("查看日程详情：" + next.title);
        } else { LinearLayout box = card(); cardText(box, "暂无待办日程", 14, INK, 1); cardText(box, "空闲时光，轻松安排", 10, MUTED, 1); }
        if (next != null) for (Event later : store.events()) {
            if (later.allDay || later.startMs <= next.startMs || later.endMs <= now) continue;
            LinearLayout box = card();
            cardText(box, dateLabel(later.startMs) + " · " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(later.startMs)), 11, ACCENT, 1);
            cardText(box, later.title, 12, INK, 2); box.setOnClickListener(v -> eventDetails(later, this::render));
            box.setContentDescription("查看日程详情：" + later.title); break;
        }
        TextView hint = label("向下滑动 · 更多操作", 9, MUTED); hint.setGravity(Gravity.CENTER);
        content = page;
        if (store.syncedAt() == 0) detail("正在等待首次同步");
        else if (now >= store.validUntil()) detail("缓存已过期 · 提醒已暂停");
        else { TextView status = label(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(store.syncedAt())) + " 已同步 · 提前 " + store.minutes() + " 分钟", 9, MUTED); status.setGravity(Gravity.CENTER); }
        if (!store.error().isEmpty()) detail(store.error());
        if (store.direct() && pairData().optBoolean("reauthorize")) button("重新扫码授权", () -> work(() -> store.directClient().begin(), this::showPair));
        navigation();
        button("立即同步", () -> work(() -> SyncJob.sync(this), this::render));
    }
    private JSONObject pairData() {
        try {
            if (store.direct()) return store.directAuth();
            return new JSONObject().put("pairExpires", store.prefs.getLong("pairExpires", 0))
                .put("pairUrl", store.prefs.getString("pairUrl", "")).put("pairCode", store.prefs.getString("pairCode", ""));
        } catch (Exception e) { return new JSONObject(); }
    }
    private void onboarding() {
        base("直接连接飞书"); detail("凭证保存在这块手表，无需服务器或域名。首次用手机扫码授权。");
        if (store.direct()) {
            detail("自建应用已配置");
            button("扫码授权飞书", () -> work(() -> store.directClient().begin(), this::showPair));
            button("清除应用凭证", () -> new AlertDialog.Builder(this).setMessage("删除此表保存的应用密钥和授权？")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> work(() -> ReminderEngine.clear(this), this::onboarding)).show());
        }
        button(store.direct() ? "修改应用凭证" : "配置自建应用", this::credentials);
        button("15 秒测试提醒", this::testReminder);
        if (BuildConfig.DEBUG) button("体验示例日程", () -> work(this::demo, this::render));
        button("旧版服务器连接", this::bridgeOnboarding);
        detail("可以在电脑上通过 USB 配置 App ID 和 App Secret，免去手表输入。息屏提醒仍需通过真机测试。");
    }
    private EditText field(String hint, boolean secret) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextSize(12); input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF9DB1B7); input.setHint(hint); input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (secret ? android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD : android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        content.addView(input); return input;
    }
    private void credentials() {
        base("配置自建应用"); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        detail("仅用于你自己的应用。密钥加密存储在手表，设备被攻破时仍可能泄露。");
        EditText appId = field("App ID：cli_…", false), secret = field("App Secret", true);
        appId.setText(pairData().optString("appId"));
        button("保存应用配置", () -> {
            String id = appId.getText().toString(), value = secret.getText().toString();
            work(() -> store.directClient().configure(id, value), () -> { secret.setText(""); onboarding(); });
        });
        button("返回", this::onboarding);
    }
    private void bridgeOnboarding() {
        base("抬腕，准时赴约"); detail("会前震动提醒，无需每天打开。用手机扫码连接一次飞书。");
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextSize(12); input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF9DB1B7); input.setHint("https://你的服务域名"); input.setText(store.server());
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); content.addView(input);
        button("连接飞书", () -> {
            final String value = input.getText().toString();
            work(() -> {
                store.server(Bridge.validate(value));
                store.prefs.edit().remove("directAuth").commit();
                JSONObject result = Bridge.request(store, "POST", "/v1/pair", "");
                store.secret("pollToken", result.getString("pollToken"));
                store.prefs.edit().putString("pairUrl", result.getString("verificationUrl")).putString("pairCode", result.getString("pairingCode"))
                    .putLong("pairExpires", result.getLong("expiresAt")).commit();
            }, this::showPair);
        });
        button("15 秒测试提醒", this::testReminder);
        if (BuildConfig.DEBUG) button("体验示例日程", () -> work(this::demo, this::render));
        detail("服务只需部署一次。App Secret 仅保存在服务端，不填入手表。");
    }
    private void showPair() {
        if (!active) return;
        pairing = true; base("用手机扫码");
        try {
            JSONObject pair = pairData();
            String url = pair.optString("pairUrl");
            int pixels = dp(132);
            BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, pixels, pixels);
            Bitmap bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < pixels; y++) for (int x = 0; x < pixels; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView image = new ImageView(this); image.setImageBitmap(bitmap); image.setContentDescription("飞书连接二维码"); image.setAdjustViewBounds(true);
            content.addView(image, new LinearLayout.LayoutParams(-1, dp(132)));
            TextView code = label("连接码 " + pair.optString("pairCode"), 11, ACCENT); code.setGravity(Gravity.CENTER);
            detail("用手机飞书扫码，核对连接码。授权完成后自动同步。剩余约 " + Math.max(1, (pair.optLong("pairExpires") - System.currentTimeMillis()) / 60000) + " 分钟。");
            pairingStatus = label("等待手机授权…", 12, 0xFF9DB1B7);
            button("取消连接", () -> { pairing = false; handler.removeCallbacks(poll); work(() -> {
                if (store.direct()) store.directClient().cancelPair();
                else store.prefs.edit().remove("pairExpires").remove("pollToken").commit();
            }, this::onboarding); });
            handler.removeCallbacks(poll); handler.postDelayed(poll, 4000);
        } catch (Exception e) { detail("二维码生成失败，请重新连接"); }
    }
    private void pollPair() {
        if (!active || !pairing) return;
        if (pairData().optLong("pairExpires") <= System.currentTimeMillis()) { pairing = false; render(); return; }
        if (busy) { handler.postDelayed(poll, 4000); return; }
        busy = true;
        SyncJob.WORK.execute(() -> {
            boolean connected = false; String error = null;
            try {
                if (store.direct()) {
                    connected = store.directClient().poll();
                    if (connected) { store.prefs.edit().putBoolean("demo", false).commit(); SyncJob.schedule(this); SyncJob.sync(this); }
                } else {
                JSONObject result = Bridge.request(store, "POST", "/v1/pair/poll", store.secret("pollToken"));
                if ("connected".equals(result.getString("status"))) {
                    store.secret("deviceToken", result.getString("deviceToken"));
                    store.prefs.edit().remove("pairExpires").remove("pollToken").remove("pairUrl").remove("pairCode").putBoolean("demo", false).commit();
                    SyncJob.schedule(this); SyncJob.sync(this); connected = true;
                }
                }
            } catch (Exception e) { error = e instanceof java.io.IOException ? e.getMessage() : "连接暂未完成，请检查应用配置或重新扫码"; }
            final boolean done = connected; final String message = error;
            handler.post(() -> {
                busy = false; if (!active) return;
                if (done) { pairing = false; requestNotifications(); render(); }
                else if (pairing) {
                    if (message != null && pairingStatus != null) pairingStatus.setText(message);
                    if (pairData().optLong("pairExpires") <= System.currentTimeMillis()) {
                        pairing = false; button("重新连接", this::onboarding);
                    } else handler.postDelayed(poll, Math.max(1000, store.direct() ? pairData().optLong("nextPoll") - System.currentTimeMillis() : 4000));
                }
            });
        });
    }
    private void settings() {
        pairing = false; base("提醒与组件");
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = nm.getNotificationChannel(ReminderEngine.ALERTS);
        boolean notifications = nm.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        detail("通知权限 · " + (notifications ? "已开启" : "未开启") + "\n精确闹钟权限 · " + (ReminderEngine.exact(this) ? "已开启" : "未开启"));
        detail("请先测试息屏提醒，确认本机能正常收到。");
        button("提前提醒 · " + store.minutes() + " 分钟", () -> new AlertDialog.Builder(this).setTitle("提前提醒")
            .setItems(new String[]{"准点", "5 分钟", "10 分钟", "15 分钟"}, (dialog, which) -> work(() -> {
                store.prefs.edit().putInt("minutes", new int[]{0, 5, 10, 15}[which]).commit(); ReminderEngine.reconcile(this, false);
            }, this::settings)).show());
        button("15 秒测试提醒", this::testReminder);
        button("常驻摘要：" + (store.summary() ? "开" : "关"), () -> work(() -> {
            store.prefs.edit().putBoolean("summary", !store.summary()).commit(); ReminderEngine.reconcile(this, false);
        }, this::settings));
        button("系统通知设置", () -> {
            requestNotifications(); launch(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, ReminderEngine.ALERTS));
        });
        if (Build.VERSION.SDK_INT >= 31 && !ReminderEngine.exact(this)) button("允许精确提醒", () -> launch(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))));
        button("电池与后台设置", () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        AppWidgetManager widgets = AppWidgetManager.getInstance(this);
        if (widgets.isRequestPinAppWidgetSupported()) button("添加下一场组件", () -> widgets.requestPinAppWidget(new ComponentName(this, AgendaWidget.class), null, null));
        else detail("当前桌面未提供标准组件添加入口；可先使用通知中心摘要。OPPO 原生卡片需独立适配。");
        button(store.connected() ? "断开飞书连接" : "退出示例", () -> new AlertDialog.Builder(this).setTitle("断开连接？")
            .setMessage("将清除此表的应用密钥、授权和日程。飞书端的授权可在飞书中撤销。").setNegativeButton("取消", null).setPositiveButton("断开", (d, w) -> work(() -> {
                if (store.connected() && !store.direct()) Bridge.request(store, "DELETE", "/v1/device", store.secret("deviceToken"));
                ReminderEngine.clear(this);
            }, this::render)).show());
        button("返回", this::render);
    }
    private void agenda() {
        base("未来日程"); long now = System.currentTimeMillis();
        if (now >= store.validUntil()) detail("缓存已过期，以下内容可能已有变化");
        String previousDay = ""; int count = 0;
        for (Event event : store.events()) {
            if (!event.allDay && event.endMs <= now) continue;
            String day = event.allDay ? event.startDate : dateLabel(event.startMs);
            if (!day.equals(previousDay)) { TextView date = label(day, 10, MUTED); date.setPadding(dp(4), dp(8), 0, dp(5)); previousDay = day; }
            LinearLayout box = card();
            cardText(box, event.allDay ? "全天" : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(event.startMs)) + " – " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(event.endMs)), 12, ACCENT, 1);
            cardText(box, event.title, 12, INK, 2);
            box.setOnClickListener(v -> eventDetails(event, this::agenda));
            box.setContentDescription("查看日程详情：" + event.title); count++;
        }
        if (count == 0) detail("未来七天暂无日程");
        button("返回", this::render);
    }
    private void eventDetails(Event event, Runnable back) {
        base("日程详情");
        detail(event.allDay ? event.startDate + " · 全天" : dateLabel(event.startMs));
        if (!event.allDay) label(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(event.startMs)) + " – " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(event.endMs)), 17, ACCENT);
        label(event.title, 14, INK);
        if (!event.location.isEmpty()) { detail("地点"); label(event.location, 11, INK); }
        TextView people = label("参会人\n正在读取…", 10, MUTED); people.setLineSpacing(dp(3), 1);
        if (store.direct()) {
            JSONObject cached = attendeeCache.get(event.id);
            if (cached != null && System.currentTimeMillis() - cached.optLong("cachedAt") < 300_000) people.setText(attendeeText(cached));
            else {
                LinearLayout detailScreen = content;
                SyncJob.WORK.execute(() -> {
                    String message;
                    try {
                        JSONObject result = store.directClient().attendees(event.attendeeEventId); result.put("cachedAt", System.currentTimeMillis());
                        message = attendeeText(result);
                        handler.post(() -> attendeeCache.put(event.id, result));
                    } catch (Exception e) { message = "参会人\n" + (e instanceof java.io.IOException ? e.getMessage() : "暂时无法读取，请稍后重试"); }
                    final String value = message;
                    handler.post(() -> { if (active && content == detailScreen) people.setText(value); });
                });
            }
        } else people.setText("参会人\n直连飞书后可查看");
        button("返回", back);
    }
    private String attendeeText(JSONObject result) {
        JSONArray names = result.optJSONArray("names"); StringBuilder text = new StringBuilder("参会人");
        if (names == null || names.length() == 0) text.append("\n未返回可见名单");
        else for (int i = 0; i < Math.min(10, names.length()); i++) text.append('\n').append(names.optString(i));
        if (result.optBoolean("hasMore")) text.append("\n仅显示前 10 位");
        if (result.optBoolean("partial")) text.append("\n部分群成员名单不可见");
        return text.toString();
    }
    @Override public void onBackPressed() {
        if (store.connected() && !"接下来".equals(screenTitle)) render(); else super.onBackPressed();
    }
    private void demo() throws Exception {
        long now = System.currentTimeMillis();
        JSONObject event = new JSONObject().put("id", "demo").put("title", "产品同步会 · 示例").put("location", "线上会议 · 非真实日程")
            .put("startMs", now + 11 * 60_000).put("endMs", now + 41 * 60_000).put("allDay", false).put("startDate", "").put("endDate", "");
        store.snapshot(new JSONObject().put("syncedAt", now).put("validUntil", now + 3600_000).put("events", new JSONArray().put(event)));
        store.prefs.edit().putBoolean("demo", true).putInt("minutes", 10).commit(); ReminderEngine.reconcile(this, false);
    }
    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
    private void testReminder() { requestNotifications(); SyncJob.WORK.execute(() -> ReminderEngine.test(this)); Toast.makeText(this, "15 秒后测试，请关闭屏幕", Toast.LENGTH_LONG).show(); }
    private void launch(Intent intent) { try { startActivity(intent); } catch (ActivityNotFoundException e) { Toast.makeText(this, "本机未提供此设置入口", Toast.LENGTH_LONG).show(); } }
    interface Task { void run() throws Exception; }
    private void work(Task task, Runnable success) {
        if (busy) return; busy = true;
        Toast.makeText(this, "正在处理…", Toast.LENGTH_SHORT).show();
        SyncJob.WORK.execute(() -> {
            String error = null;
            try { task.run(); } catch (Exception e) { error = e instanceof java.io.IOException ? e.getMessage() : "操作失败，请检查应用配置或重新连接"; }
            final String message = error;
            handler.post(() -> { busy = false; if (!active) return; if (message != null) new AlertDialog.Builder(this).setMessage(message).setPositiveButton("知道了", null).show(); else success.run(); });
        });
    }
}
