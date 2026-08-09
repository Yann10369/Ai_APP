package com.ai_photo.net;

import android.util.Log;

import androidx.annotation.Nullable;

import com.ai_photo.auth.Session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 轻量 HTTP 客户端（基于 HttpURLConnection）。
 *
 * 作用：
 *  - 统一拼接 baseUrl + path
 *  - 自动注入 Authorization 头（从 Session 拿 token）
 *  - 解析统一响应包 {code, message, data}
 *
 * 后端 baseUrl 默认 http://10.0.2.2:8000（Android 模拟器访问宿主机的回环地址）。
 * 真机调试请改成宿主机的局域网 IP，例如 http://192.168.1.100:8000。
 */
public final class ApiClient {

    /** 统一 LogCat TAG（前端排查时按 [API/HTTP] / [API/REQ] / [API/RESP] / [API/ERR] 过滤） */
    private static final String TAG = "AiPhoto.API";

    /** 后端根地址。模拟器里 10.0.2.2 等同于宿主机的 127.0.0.1,http://10.137.143.159:8000 */
    public static final String BASE_URL = "http://10.137.143.151:8000";

    public static final int CONNECT_TIMEOUT_MS = 8000;
    public static final int READ_TIMEOUT_MS = 15000;

    /**
     * 全局 401 监听器：任何请求收到 401 时回调一次。
     * App.onCreate 里注入；实现通常 = 清 Session + 跳 LoginActivity。
     * 为避免循环触发，注入前 / 登录接口路径下不会回调。
     */
    public interface OnUnauthorizedListener {
        void onUnauthorized(int httpCode, String rawBody);
    }

    @Nullable private static volatile OnUnauthorizedListener sOnUnauthorized;

    /** 注册全局 401 监听器。重复注册会覆盖（同一进程内只需一个）。 */
    public static void setOnUnauthorizedListener(@Nullable OnUnauthorizedListener l) {
        sOnUnauthorized = l;
    }

    private ApiClient() { }

    // ============================================================
    //  HTTP 通用方法
    // ============================================================

    /**
     * 发起 GET 请求，query 拼接为 ?k=v&k=v
     */
    @Nullable
    public static String get(String path, @Nullable Map<String, String> query) throws IOException {
        return execute("GET", path, query, null, true);
    }

    /**
     * 发起 JSON POST/PATCH/DELETE 请求
     */
    @Nullable
    public static String post(String path, @Nullable JSONObject body) throws IOException {
        return execute("POST", path, null, body, true);
    }

    public static String patch(String path, @Nullable JSONObject body) throws IOException {
        return execute("PATCH", path, null, body, true);
    }

    public static String delete(String path, @Nullable JSONObject body) throws IOException {
        return execute("DELETE", path, null, body, true);
    }

    /**
     * multipart/form-data 上传（files 数组）。写真实文件体，
     * 边界由调用方生成；后端只需按 multipart 规范解析。
     */
    @Nullable
    public static String upload(String path, String boundary, byte[] body) throws IOException {
        Log.d(TAG, "[API/UPLOAD] start path=" + path
                + " bytes=" + (body == null ? 0 : body.length));
        URL url = new URL(BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        injectAuth(conn);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        String resp = readBody(conn);
        Log.d(TAG, "[API/UPLOAD] done path=" + path
                + " respLen=" + (resp == null ? 0 : resp.length()));
        return resp;
    }

    /** 给外部直接下载图片 URL 用（不走 auth）。 */
    public static String absoluteUrl(String relativeOrAbsolute) {
        if (relativeOrAbsolute == null) return null;
        if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
            return relativeOrAbsolute;
        }
        if (relativeOrAbsolute.startsWith("/")) {
            return BASE_URL + relativeOrAbsolute;
        }
        return BASE_URL + "/" + relativeOrAbsolute;
    }

    /**
     * 通用 HTTP GET body 下载（不走 JSON 解析、不带 Authorization）。
     * 用途：ImageLoader 拿 /static/* 图片字节；后续视频缩略图/PDF 首页预览也可复用。
     * 失败返回 null（不抛异常）。
     */
    @Nullable
    public static byte[] getBytes(String absoluteUrl) {
        if (absoluteUrl == null || absoluteUrl.isEmpty()) return null;
        Log.d(TAG, "[API/IMG] GET " + absoluteUrl);
        java.net.HttpURLConnection conn = null;
        java.io.InputStream is = null;
        java.io.ByteArrayOutputStream baos = null;
        try {
            java.net.URL u = new java.net.URL(absoluteUrl);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setUseCaches(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "[API/IMG] FAIL code=" + code + " url=" + absoluteUrl);
                return null;
            }
            is = conn.getInputStream();
            baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            byte[] out = baos.toByteArray();
            Log.d(TAG, "[API/IMG] OK bytes=" + out.length + " url=" + absoluteUrl);
            return out;
        } catch (java.io.IOException e) {
            Log.w(TAG, "[API/IMG] EXC " + e.getMessage() + " url=" + absoluteUrl);
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
    }

    // ============================================================
    //  内部执行
    // ============================================================

    private static String execute(String method, String path,
                                  @Nullable Map<String, String> query,
                                  @Nullable JSONObject body,
                                  boolean useAuth) throws IOException {
        StringBuilder full = new StringBuilder(BASE_URL).append(path);
        if (query != null && !query.isEmpty()) {
            full.append('?');
            Iterator<Map.Entry<String, String>> it = query.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                full.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
                if (it.hasNext()) full.append('&');
            }
        }
        long startMs = System.currentTimeMillis();
        Log.d(TAG, "[API/REQ] " + method + " " + full
                + (body == null ? " (no-body)" : " body=" + truncate(body.toString(), 300)));
        URL url = new URL(full.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        if (useAuth) injectAuth(conn);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        String resp = readBody(conn);
        long costMs = System.currentTimeMillis() - startMs;
        Log.d(TAG, "[API/RESP] " + method + " " + path
                + " cost=" + costMs + "ms len=" + (resp == null ? 0 : resp.length())
                + " body=" + truncate(resp, 300));
        return resp;
    }

    /** 截断超长字符串，便于 logcat 阅读 */
    private static String truncate(String s, int limit) {
        if (s == null) return "null";
        if (s.length() <= limit) return s;
        return s.substring(0, limit) + "…(+" + (s.length() - limit) + ")";
    }

    private static void injectAuth(HttpURLConnection conn) {
        String token = Session.getToken();
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            conn.disconnect();
        }
        if (code < 200 || code >= 300) {
            Log.w(TAG, "[API/HTTP] non-2xx code=" + code
                    + " bodyLen=" + sb.length()
                    + " preview=" + truncate(sb.toString(), 200));
        }
        // 401：触发全局未授权监听器（清 session + 跳登录页）
        if (code == 401) {
            OnUnauthorizedListener l = sOnUnauthorized;
            if (l != null) {
                try {
                    Log.w(TAG, "[API/401] fire OnUnauthorizedListener");
                    l.onUnauthorized(code, sb.toString());
                } catch (Exception e) {
                    Log.e(TAG, "[API/401] listener EXC " + e.getMessage());
                }
            }
        }
        return sb.toString();
    }

    // ============================================================
    //  解析工具
    // ============================================================

    /**
     * 解析统一响应包为 ApiResponse。code != 200 时 message 通过 ApiResponse.getMessage 暴露。
     */
    public static ApiResponse parse(String raw) throws JSONException {
        JSONObject obj = new JSONObject(raw);
        int code = obj.optInt("code", -1);
        String message = obj.optString("message", "");
        JSONObject data = obj.optJSONObject("data");
        JSONArray dataArr = obj.optJSONArray("data");
        Object dataVal;
        if (data != null) dataVal = data;
        else if (dataArr != null) dataVal = dataArr;
        else dataVal = obj.isNull("data") ? null : obj.opt("data");
        Log.d(TAG, "[API/PARSE] code=" + code + " msg=" + message
                + " dataType=" + (dataVal == null ? "null"
                        : dataVal instanceof JSONObject ? "object"
                        : dataVal instanceof JSONArray ? "array"
                        : dataVal.getClass().getSimpleName()));
        return new ApiResponse(code, message, dataVal);
    }

    /** 解析 HTTP 错误响应里的 message（FastAPI 默认 detail 字段或我们的 message） */
    public static String extractErrorMessage(String raw) {
        if (raw == null || raw.isEmpty()) return "网络错误";
        try {
            JSONObject obj = new JSONObject(raw);
            if (obj.has("message")) return obj.optString("message", "网络错误");
            if (obj.has("detail")) {
                Object d = obj.get("detail");
                if (d instanceof String) return (String) d;
                return d.toString();
            }
        } catch (JSONException ignored) { }
        return raw;
    }

    /** 构造一个空 query map，便于调用 */
    public static Map<String, String> query() {
        return new HashMap<>();
    }
}