package io.github.ray204.feishuwatch;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.util.*;
import org.json.*;

public final class LocalStore {
    final SharedPreferences prefs;
    public LocalStore(Context context) { prefs = context.getSharedPreferences("agenda", Context.MODE_PRIVATE); }
    public String server() { return prefs.getString("server", BuildConfig.DEFAULT_SERVER_URL); }
    public void server(String value) { prefs.edit().putString("server", value).commit(); }
    public int minutes() { return prefs.getInt("minutes", 10); }
    public boolean summary() { return prefs.getBoolean("summary", true); }
    public String error() { return prefs.getString("error", ""); }
    public void error(String value) { prefs.edit().putString("error", value).commit(); }
    public long syncedAt() { return prefs.getLong("syncedAt", 0); }
    public long validUntil() { return prefs.getLong("validUntil", 0); }
    public void snapshot(JSONObject data) throws JSONException {
        // Parse the entire snapshot before replacing a working offline cache.
        parse(data.getJSONArray("events"));
        long synced = data.getLong("syncedAt"), valid = data.getLong("validUntil");
        if (valid <= synced || valid - synced > 24 * 3600_000L) throw new JSONException("Invalid freshness window");
        prefs.edit().putString("events", data.getJSONArray("events").toString()).putLong("syncedAt", synced).putLong("validUntil", valid).putString("error", "").commit();
    }
    public List<Event> events() {
        try { return parse(new JSONArray(prefs.getString("events", "[]"))); } catch (JSONException e) { return Collections.emptyList(); }
    }
    private static List<Event> parse(JSONArray array) throws JSONException {
        List<Event> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject e = array.getJSONObject(i);
            Event event = new Event(e.getString("id"), e.getString("title"), e.optString("location"), e.getLong("startMs"), e.getLong("endMs"), e.getBoolean("allDay"), e.optString("startDate"), e.optString("endDate"), e.optString("attendeeEventId", e.getString("id")));
            if (event.id.isEmpty() || (!event.allDay && event.endMs <= event.startMs)) throw new JSONException("Invalid event");
            list.add(event);
        }
        return list;
    }
    public Set<String> delivered() { return new HashSet<>(prefs.getStringSet("delivered", Collections.emptySet())); }
    public void delivered(Set<String> values) { prefs.edit().putStringSet("delivered", values).commit(); }
    public Map<String, Long> snoozes() {
        Map<String, Long> result = new HashMap<>();
        try {
            JSONObject object = new JSONObject(prefs.getString("snoozes", "{}"));
            for (Iterator<String> it = object.keys(); it.hasNext();) { String key = it.next(); result.put(key, object.getLong(key)); }
        } catch (JSONException ignored) { }
        return result;
    }
    public void snoozes(Map<String, Long> values) { prefs.edit().putString("snoozes", new JSONObject(values).toString()).commit(); }
    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias("feishu-watch")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("feishu-watch", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) ks.getKey("feishu-watch", null);
    }
    public void secret(String name, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!prefs.edit().putString(name, encoded).commit()) throw new java.io.IOException("凭证保存失败，请重试");
    }
    public String secret(String name) throws Exception {
        String value = prefs.getString(name, ""); if (value.isEmpty()) return "";
        String[] pieces = value.split(":");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    public boolean direct() { return prefs.contains("directAuth"); }
    public JSONObject directAuth() throws Exception { String value = secret("directAuth"); return value.isEmpty() ? new JSONObject() : new JSONObject(value); }
    public FeishuDirect directClient() {
        return new FeishuDirect(new FeishuDirect.State() {
            public JSONObject read() throws Exception { return directAuth(); }
            public void save(JSONObject value) throws Exception {
                secret("directAuth", value.toString());
                // Non-sensitive diagnostics only; tokens and app credentials remain encrypted.
                prefs.edit().putString("directStatus", new JSONObject()
                    .put("authorized", !value.optString("accessToken").isEmpty())
                    .put("pairExpires", value.optLong("pairExpires"))
                    .put("reauthorize", value.optBoolean("reauthorize"))
                    .put("tokenEnvelope", value.optString("tokenEnvelope"))
                    .put("tokenHasAccess", value.optBoolean("tokenHasAccess"))
                    .put("tokenHasRefresh", value.optBoolean("tokenHasRefresh"))
                    .put("tokenLifetime", value.optLong("tokenLifetime"))
                    .put("lastAuthError", value.optString("lastAuthError"))
                    .put("lastAuthDetail", value.optString("lastAuthDetail")).toString()).commit();
            }
        });
    }
    public boolean connected() {
        if (direct()) { try { return !directAuth().optString("accessToken").isEmpty(); } catch (Exception e) { return false; } }
        return !prefs.getString("deviceToken", "").isEmpty();
    }
    public void importDirectConfig(Context context) throws Exception {
        java.io.File file = new java.io.File(context.getFilesDir(), "direct-config.json");
        if (!file.exists()) return;
        try {
            if (connected()) throw new java.io.IOException("请先断开当前连接再导入新凭证");
            if (file.length() > 8192) throw new java.io.IOException("配置文件过大");
            JSONObject config = new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            directClient().configure(config.getString("appId"), config.getString("appSecret"));
        } finally { if (!file.delete()) throw new java.io.IOException("临时配置清理失败"); }
    }
    public void clear() { String url = server(); prefs.edit().clear().putString("server", url).commit(); }
}
