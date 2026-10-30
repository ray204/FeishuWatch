package io.github.ray204.feishuwatch;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

public final class Bridge {
    public static String validate(String input) throws Exception {
        URI uri = new URI(input.trim());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IOException("请输入 HTTPS 服务地址，不包含路径");
        return input.trim().replaceAll("/+$", "");
    }
    public static JSONObject request(LocalStore store, String method, String path, String token) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(validate(store.server()) + path).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IOException("服务暂时不可用");
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            try (InputStream in = stream) {
                byte[] buf = new byte[4096]; int count;
                while ((count = in.read(buf)) != -1) { if (data.size() + count > 2_000_000) throw new IOException("日程响应过大"); data.write(buf, 0, count); }
            }
            JSONObject result = new JSONObject(data.toString(StandardCharsets.UTF_8.name()));
            if (status == 401) throw new IOException("连接已失效，请重新连接飞书");
            if (status == 403) throw new IOException("飞书日历权限不足，请检查应用授权");
            if (status < 200 || status >= 300) throw new IOException("同步失败（" + status + "），保留已有日程");
            return result;
        } finally { connection.disconnect(); }
    }
}
