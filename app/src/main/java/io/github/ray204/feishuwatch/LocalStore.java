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
            Event event = new Event(e.getString("id"), e.getString("title"), e.optString("location"), e.getLong("startMs"), e.getLong("endMs"), e.getBoolean("allDay"), e.optString("startDate"), e.optString("endDate"));
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
        prefs.edit().putString(name, encoded).commit();
    }
    public String secret(String name) throws Exception {
        String value = prefs.getString(name, ""); if (value.isEmpty()) return "";
        String[] pieces = value.split(":");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    public boolean connected() { return !prefs.getString("deviceToken", "").isEmpty(); }
    public void clear() { String url = server(); prefs.edit().clear().putString("server", url).commit(); }
}
