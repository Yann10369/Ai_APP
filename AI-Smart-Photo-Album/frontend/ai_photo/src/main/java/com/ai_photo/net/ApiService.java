package com.ai_photo.net;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ai_photo.net.Models.AdminCategory;
import com.ai_photo.net.Models.AiStatus;
import com.ai_photo.net.Models.AuthResult;
import com.ai_photo.net.Models.CategoryList;
import com.ai_photo.net.Models.CategoryPhotos;
import com.ai_photo.net.Models.CategoryPreview;
import com.ai_photo.net.Models.FavoritePhoto;
import com.ai_photo.net.Models.PagedFavorites;
import com.ai_photo.net.Models.PagedPhotos;
import com.ai_photo.net.Models.PagedSearch;
import com.ai_photo.net.Models.PhotoDetail;
import com.ai_photo.net.Models.PhotoSummary;
import com.ai_photo.net.Models.UploadResult;
import com.ai_photo.net.Models.UserMe;
import com.ai_photo.net.Models.UserStatistics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 封装 docs/interface.md 中定义的全部 26 个接口。
 *
 * 每个方法都是阻塞 IO，调用方需要放到子线程。
 * 失败统一抛 ApiException，由调用方 catch 后展示 Toast。
 */
public final class ApiService {

    /** 统一 LogCat TAG，前端按 [API/CALL] / [API/OK] / [API/EXC] 过滤 */
    private static final String TAG = "AiPhoto.API";

    /** 业务异常：HTTP 非 200 / 网络错误 / 解析错误 */
    public static final class ApiException extends Exception {
        public final int code;
        public ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private ApiService() { }

    // ============================================================
    //  工具方法
    // ============================================================

    private static ApiResponse exec(String method, String path,
                                    @Nullable Map<String, String> query,
                                    @Nullable JSONObject body) throws ApiException {
        Log.d(TAG, "[API/CALL] " + method + " " + path
                + (query == null || query.isEmpty() ? "" : " q=" + query));
        try {
            String raw;
            switch (method) {
                case "GET":    raw = ApiClient.get(path, query); break;
                case "POST":   raw = ApiClient.post(path, body); break;
                case "PATCH":  raw = ApiClient.patch(path, body); break;
                case "DELETE": raw = ApiClient.delete(path, body); break;
                default: throw new ApiException(-1, "不支持的 HTTP 方法: " + method);
            }
            ApiResponse r = ApiClient.parse(raw);
            Log.d(TAG, "[API/OK] " + method + " " + path
                    + " code=" + r.code + " msg=" + r.message);
            return r;
        } catch (IOException io) {
            Log.e(TAG, "[API/EXC/IO] " + method + " " + path + " " + io.getMessage());
            throw new ApiException(-1, "网络错误: " + io.getMessage());
        } catch (JSONException je) {
            Log.e(TAG, "[API/EXC/JSON] " + method + " " + path + " " + je.getMessage());
            throw new ApiException(-1, "数据解析失败: " + je.getMessage());
        }
    }

    private static <T> T require(ApiResponse r, SafeMapper<T> mapper) throws ApiException {
        if (!r.isSuccess()) {
            Log.w(TAG, "[API/BIZ-FAIL] code=" + r.code + " msg=" + r.message);
            throw new ApiException(r.code, r.message);
        }
        try {
            return mapper.apply(r);
        } catch (JSONException je) {
            Log.e(TAG, "[API/EXC/MAP] " + je.getMessage());
            throw new ApiException(-1, "数据解析失败: " + je.getMessage());
        }
    }

    /** 类似 Function<ApiResponse, T>，但允许抛 JSONException，由 require 统一转换 */
    private interface SafeMapper<T> {
        T apply(ApiResponse r) throws JSONException;
    }

    // ============================================================
    //  一、用户认证 (3)
    // ============================================================

    public static AuthResult login(@NonNull String username, @NonNull String password) throws ApiException {
        Log.d(TAG, "[API/AUTH] login user=" + username + " pwdLen=" + password.length());
        try {
            JSONObject body = new JSONObject()
                    .put("username", username)
                    .put("password", password);
            AuthResult r = require(exec("POST", "/api/v1/auth/login", null, body),
                    rr -> AuthResult.fromJson(rr.dataAsObject()));
            Log.d(TAG, "[API/AUTH] login OK userId=" + r.userId
                    + " hasToken=" + (r.token != null));
            return r;
        } catch (JSONException e) {
            Log.e(TAG, "[API/AUTH] login build-req EXC " + e.getMessage());
            throw new ApiException(-1, "登录请求构造失败");
        }
    }

    public static long register(@NonNull String username, @NonNull String password,
                                @Nullable String email) throws ApiException {
        Log.d(TAG, "[API/AUTH] register user=" + username + " email=" + email);
        try {
            JSONObject body = new JSONObject().put("username", username).put("password", password);
            if (email != null) body.put("email", email);
            return require(exec("POST", "/api/v1/auth/register", null, body),
                    r -> r.dataAsObject().optLong("userId"));
        } catch (JSONException e) {
            Log.e(TAG, "[API/AUTH] register build-req EXC " + e.getMessage());
            throw new ApiException(-1, "注册请求构造失败");
        }
    }

    public static void logout() throws ApiException {
        Log.d(TAG, "[API/AUTH] logout");
        exec("POST", "/api/v1/auth/logout", null, null);
    }

    // ============================================================
    //  二、用户信息 (3)
    // ============================================================

    public static UserMe getMe() throws ApiException {
        return require(exec("GET", "/api/v1/users/me", null, null),
                r -> UserMe.fromJson(r.dataAsObject()));
    }

    public static UserStatistics getMyStatistics() throws ApiException {
        return require(exec("GET", "/api/v1/users/me/statistics", null, null),
                r -> UserStatistics.fromJson(r.dataAsObject()));
    }

    public static PagedFavorites getMyFavorites(int page, int pageSize) throws ApiException {
        Map<String, String> q = new HashMap<>();
        q.put("page", String.valueOf(page));
        q.put("pageSize", String.valueOf(pageSize));
        return require(exec("GET", "/api/v1/users/me/favorites", q, null), r -> {
            JSONObject obj = r.dataAsObject();
            JSONArray arr = obj.optJSONArray("list");
            List<FavoritePhoto> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(FavoritePhoto.fromJson(arr.getJSONObject(i)));
                }
            }
            return new PagedFavorites(list,
                    obj.optInt("total"), obj.optInt("page"), obj.optInt("pageSize"));
        });
    }

    // ============================================================
    //  三、照片管理 (10)
    // ============================================================

    public static UploadResult uploadPhotos() throws ApiException {
        return require(exec("POST", "/api/v1/photos/upload", null, null),
                r -> UploadResult.fromJson(r.dataAsObject()));
    }

    /**
     * 真实 multipart 上传：把已压缩好的 JPEG 字节数组作为 files 字段一次性提交。
     *
     * @param jpegBytesList 每张图的 JPEG 字节数组（按选中顺序），可为空列表（= 空批次）
     * @param filenames     与字节数组一一对应的原始文件名（用于后端日志/调试）
     */
    public static UploadResult uploadPhotos(
            @NonNull List<byte[]> jpegBytesList,
            @NonNull List<String> filenames) throws ApiException {
        Log.d(TAG, "[API/UPLOAD] count=" + jpegBytesList.size()
                + " totalBytes=" + sumBytes(jpegBytesList));
        if (jpegBytesList.isEmpty()) {
            // 空批次：给后端一个空 files 数组，避免 multipart 解析报错
            return require(exec("POST", "/api/v1/photos/upload", null, null),
                    r -> UploadResult.fromJson(r.dataAsObject()));
        }
        String boundary = "----aiPhotoBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipartBody(boundary, jpegBytesList, filenames);
        String raw;
        try {
            raw = ApiClient.upload("/api/v1/photos/upload", boundary, body);
        } catch (IOException io) {
            Log.e(TAG, "[API/UPLOAD] IO " + io.getMessage());
            throw new ApiException(-1, "网络错误: " + io.getMessage());
        }
        try {
            UploadResult r = UploadResult.fromJson(ApiClient.parse(raw).dataAsObject());
            Log.d(TAG, "[API/UPLOAD] OK success=" + r.successCount + " fail=" + r.failCount);
            return r;
        } catch (JSONException je) {
            Log.e(TAG, "[API/UPLOAD] parse EXC " + je.getMessage());
            throw new ApiException(-1, "上传响应解析失败");
        }
    }

    private static long sumBytes(List<byte[]> list) {
        long t = 0;
        for (byte[] b : list) if (b != null) t += b.length;
        return t;
    }

    /** 构造 multipart/form-data body（files 字段多文件） */
    private static byte[] buildMultipartBody(String boundary,
                                             List<byte[]> jpegBytesList,
                                             List<String> filenames) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        String crlf = "\r\n";
        try {
            for (int i = 0; i < jpegBytesList.size(); i++) {
                byte[] data = jpegBytesList.get(i);
                String name = i < filenames.size() ? filenames.get(i) : ("photo_" + i + ".jpg");
                baos.write(("--" + boundary).getBytes("UTF-8"));
                baos.write(crlf.getBytes("UTF-8"));
                baos.write(("Content-Disposition: form-data; name=\"files\"; filename=\"" + name + "\"").getBytes("UTF-8"));
                baos.write(crlf.getBytes("UTF-8"));
                baos.write("Content-Type: image/jpeg".getBytes("UTF-8"));
                baos.write(crlf.getBytes("UTF-8"));
                baos.write(crlf.getBytes("UTF-8"));
                baos.write(data);
                baos.write(crlf.getBytes("UTF-8"));
            }
            baos.write(("--" + boundary + "--").getBytes("UTF-8"));
            baos.write(crlf.getBytes("UTF-8"));
        } catch (java.io.IOException ignored) { /* ByteArrayOutputStream 不抛 */ }
        return baos.toByteArray();
    }

    public static PagedPhotos listPhotos(int page, int pageSize) throws ApiException {
        Map<String, String> q = new HashMap<>();
        q.put("page", String.valueOf(page));
        q.put("pageSize", String.valueOf(pageSize));
        return require(exec("GET", "/api/v1/photos", q, null), r -> {
            JSONObject obj = r.dataAsObject();
            JSONArray arr = obj.optJSONArray("list");
            List<PhotoSummary> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(PhotoSummary.fromJson(arr.getJSONObject(i)));
                }
            }
            return new PagedPhotos(list, obj.optInt("total"),
                    obj.optInt("page"), obj.optInt("pageSize"));
        });
    }

    public static List<PhotoSummary> listRecentPhotos(int limit) throws ApiException {
        Map<String, String> q = new HashMap<>();
        q.put("limit", String.valueOf(limit));
        // /photos/recent 实际返回 {list:[...]} 分页包装（不是裸数组）
        return require(exec("GET", "/api/v1/photos/recent", q, null), r -> {
            JSONObject obj = r.dataAsObject();
            JSONArray arr = obj.optJSONArray("list");
            List<PhotoSummary> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(PhotoSummary.fromJson(arr.getJSONObject(i)));
                }
            }
            Log.d(TAG, "[API/RECENT] OK limit=" + limit + " returned=" + list.size());
            return list;
        });
    }

    public static PhotoDetail getPhotoDetail(long photoId) throws ApiException {
        return require(exec("GET", "/api/v1/photos/" + photoId, null, null),
                r -> PhotoDetail.fromJson(r.dataAsObject()));
    }

    public static void updatePhoto(long photoId, @Nullable List<String> tags,
                                   @Nullable String description) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            if (tags != null) {
                JSONArray arr = new JSONArray();
                for (String t : tags) arr.put(t);
                body.put("tags", arr);
            }
            if (description != null) body.put("description", description);
            exec("PATCH", "/api/v1/photos/" + photoId, null, body);
        } catch (JSONException e) {
            throw new ApiException(-1, "修改请求构造失败");
        }
    }

    public static void deletePhoto(long photoId) throws ApiException {
        exec("DELETE", "/api/v1/photos/" + photoId, null, null);
    }

    public static int[] batchDelete(@NonNull List<Long> photoIds) throws ApiException {
        try {
            JSONArray arr = new JSONArray();
            for (Long id : photoIds) arr.put(id);
            JSONObject body = new JSONObject().put("photoIds", arr);
            return require(exec("DELETE", "/api/v1/photos/batch", null, body), r -> {
                JSONObject obj = r.dataAsObject();
                return new int[]{ obj.optInt("successCount"), obj.optInt("failCount") };
            });
        } catch (JSONException e) {
            throw new ApiException(-1, "批量删除请求构造失败");
        }
    }

    public static void favorite(long photoId) throws ApiException {
        exec("POST", "/api/v1/photos/" + photoId + "/favorite", null, null);
    }

    public static void unfavorite(long photoId) throws ApiException {
        exec("DELETE", "/api/v1/photos/" + photoId + "/favorite", null, null);
    }

    public static PagedSearch searchPhotos(@NonNull String query, int page, int pageSize) throws ApiException {
        Log.d(TAG, "[API/SEARCH] q=\"" + query + "\" page=" + page + " pageSize=" + pageSize);
        try {
            JSONObject body = new JSONObject()
                    .put("query", query)
                    .put("page", page)
                    .put("pageSize", pageSize);
            PagedSearch r = require(exec("POST", "/api/v1/photos/search", null, body), rr -> {
                JSONObject obj = rr.dataAsObject();
                JSONArray arr = obj.optJSONArray("list");
                List<com.ai_photo.net.Models.SearchResult> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        list.add(com.ai_photo.net.Models.SearchResult.fromJson(arr.getJSONObject(i)));
                    }
                }
                return new PagedSearch(list, obj.optInt("total"),
                        obj.optInt("page"), obj.optInt("pageSize"));
            });
            Log.d(TAG, "[API/SEARCH] OK total=" + r.total
                    + " returned=" + (r.list == null ? 0 : r.list.size()));
            return r;
        } catch (JSONException e) {
            Log.e(TAG, "[API/SEARCH] build-req EXC " + e.getMessage());
            throw new ApiException(-1, "搜索请求构造失败");
        }
    }

    /**
     * 精准标签筛选（无 AI 调用）：调 POST /api/v1/photos/filter。
     *
     * 后端 schema（FilterRequest）跨类型 AND 语义：
     *   - sceneId / emotionId / tagId 三个独立字段，每类最多一个 category_id
     *   - 至少一个字段非空（全空时前端不发请求，本函数校验全空抛 400）
     *   - 不存在的 id 静默丢弃该类型
     *   - 跨类型 AND（照片必须同时命中所有非空类型）
     *
     * @param sceneId   scene 类 category_id；null 表示不筛选该类型
     * @param emotionId emotion 类 category_id；null 表示不筛选该类型
     * @param tagId     tag 类 category_id；null 表示不筛选该类型
     * @param page      从 1 起
     * @param pageSize  ≤ 100
     * @return PagedSearch（复用搜索的响应结构，前端零成本接入）
     */
    public static PagedSearch filterPhotos(@Nullable Long sceneId,
                                          @Nullable Long emotionId,
                                          @Nullable Long tagId,
                                          int page, int pageSize) throws ApiException {
        Log.d(TAG, "[API/FILTER] sceneId=" + sceneId + " emotionId=" + emotionId
                + " tagId=" + tagId + " page=" + page + " pageSize=" + pageSize);
        if (sceneId == null && emotionId == null && tagId == null) {
            throw new ApiException(400, "sceneId / emotionId / tagId 至少需要一个非空");
        }
        try {
            JSONObject body = new JSONObject().put("page", page).put("pageSize", pageSize);
            if (sceneId != null)   body.put("sceneId",   sceneId);
            if (emotionId != null) body.put("emotionId", emotionId);
            if (tagId != null)     body.put("tagId",     tagId);

            PagedSearch r = require(exec("POST", "/api/v1/photos/filter", null, body), rr -> {
                JSONObject obj = rr.dataAsObject();
                JSONArray listArr = obj.optJSONArray("list");
                List<com.ai_photo.net.Models.SearchResult> list = new ArrayList<>();
                if (listArr != null) {
                    for (int i = 0; i < listArr.length(); i++) {
                        list.add(com.ai_photo.net.Models.SearchResult.fromJson(listArr.getJSONObject(i)));
                    }
                }
                return new PagedSearch(list, obj.optInt("total"),
                        obj.optInt("page"), obj.optInt("pageSize"));
            });
            Log.d(TAG, "[API/FILTER] OK total=" + r.total
                    + " returned=" + (r.list == null ? 0 : r.list.size()));
            return r;
        } catch (JSONException e) {
            Log.e(TAG, "[API/FILTER] build-req EXC " + e.getMessage());
            throw new ApiException(-1, "筛选请求构造失败");
        }
    }

    // ============================================================
    //  四、分类相册 (3)
    // ============================================================

    public static CategoryPreview getCategoriesPreview(int previewSize) throws ApiException {
        Map<String, String> q = new HashMap<>();
        q.put("previewSize", String.valueOf(previewSize));
        return require(exec("GET", "/api/v1/categories/preview", q, null),
                r -> CategoryPreview.fromJson(r.dataAsObject()));
    }

    public static CategoryList getCategories(@Nullable String type) throws ApiException {
        Map<String, String> q = new HashMap<>();
        if (type != null) q.put("type", type);
        return require(exec("GET", "/api/v1/categories", q, null),
                r -> CategoryList.fromJson(r.dataAsObject()));
    }

    public static CategoryPhotos getCategoryPhotos(long categoryId, int page, int pageSize) throws ApiException {
        Map<String, String> q = new HashMap<>();
        q.put("page", String.valueOf(page));
        q.put("pageSize", String.valueOf(pageSize));
        return require(exec("GET", "/api/v1/categories/" + categoryId + "/photos", q, null),
                r -> CategoryPhotos.fromJson(r.dataAsObject()));
    }

    // ============================================================
    //  五、AI 分析 (2)
    // ============================================================

    public static AiStatus getAiStatus() throws ApiException {
        return require(exec("GET", "/api/v1/ai/status", null, null),
                r -> AiStatus.fromJson(r.dataAsObject()));
    }

    public static int reanalyze(@NonNull List<Long> photoIds) throws ApiException {
        try {
            JSONArray arr = new JSONArray();
            for (Long id : photoIds) arr.put(id);
            JSONObject body = new JSONObject().put("photoIds", arr);
            return require(exec("POST", "/api/v1/ai/reanalyze", null, body),
                    r -> r.dataAsObject().optInt("queuedCount"));
        } catch (JSONException e) {
            throw new ApiException(-1, "重新分析请求构造失败");
        }
    }

    // ============================================================
    //  六、分类管理 (5)
    // ============================================================

    public static List<AdminCategory> adminListCategories(@Nullable String type) throws ApiException {
        Map<String, String> q = new HashMap<>();
        if (type != null) q.put("type", type);
        return require(exec("GET", "/api/v1/admin/categories", q, null), r -> {
            JSONObject obj = r.dataAsObject();
            JSONArray arr = obj.optJSONArray("list");
            List<AdminCategory> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(AdminCategory.fromJson(arr.getJSONObject(i)));
                }
            }
            return list;
        });
    }

    public static long adminAddCategory(@NonNull String type, @NonNull String name,
                                        @Nullable String iconUrl) throws ApiException {
        try {
            JSONObject body = new JSONObject().put("type", type).put("name", name);
            if (iconUrl != null) body.put("iconUrl", iconUrl);
            return require(exec("POST", "/api/v1/admin/categories", null, body),
                    r -> r.dataAsObject().optLong("categoryId"));
        } catch (JSONException e) {
            throw new ApiException(-1, "添加分类请求构造失败");
        }
    }

    public static void adminUpdateCategory(long categoryId, @Nullable String name,
                                           @Nullable String iconUrl) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            if (name != null) body.put("name", name);
            if (iconUrl != null) body.put("iconUrl", iconUrl);
            exec("PATCH", "/api/v1/admin/categories/" + categoryId, null, body);
        } catch (JSONException e) {
            throw new ApiException(-1, "更新分类请求构造失败");
        }
    }

    public static void adminDeleteCategory(long categoryId) throws ApiException {
        exec("DELETE", "/api/v1/admin/categories/" + categoryId, null, null);
    }

    public static int adminResetCategories() throws ApiException {
        try {
            JSONObject body = new JSONObject().put("confirm", true);
            return require(exec("POST", "/api/v1/admin/categories/reset", null, body),
                    r -> r.dataAsObject().optInt("resetCount"));
        } catch (JSONException e) {
            throw new ApiException(-1, "重置分类请求构造失败");
        }
    }

    // ============================================================
    //  七、个人资料扩展 (3) — 前端封装，后端接口就绪时切换实现
    // ============================================================

    /**
     * PATCH /api/v1/users/me：更新用户名/昵称。
     * 当前实现：直接走 ApiClient.patch（后端未提供字段时由后端决定 4xx）。
     */
    public static void updateUserProfile(@Nullable String username) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            if (username != null) body.put("username", username);
            exec("PATCH", "/api/v1/users/me", null, body);
        } catch (JSONException e) {
            throw new ApiException(-1, "更新资料请求构造失败");
        }
    }

    /**
     * POST /api/v1/users/me/avatar (multipart/form-data, field=file)。
     * 当前实现：复用 ApiClient.upload，按单文件协议提交本地 avatar.jpg。
     * 后端就绪后即可真实上传；现阶段不阻塞 UI（调用方忽略异常即可）。
     */
    public static void uploadAvatar(@NonNull java.io.File avatarJpeg) throws ApiException {
        byte[] data;
        try {
            data = readAllBytes(avatarJpeg);
        } catch (java.io.IOException io) {
            throw new ApiException(-1, "读取本地头像失败: " + io.getMessage());
        }
        String boundary = "----aiPhotoBoundary" + System.currentTimeMillis();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        String crlf = "\r\n";
        try {
            baos.write(("--" + boundary).getBytes("UTF-8"));
            baos.write(crlf.getBytes("UTF-8"));
            baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"avatar.jpg\"").getBytes("UTF-8"));
            baos.write(crlf.getBytes("UTF-8"));
            baos.write("Content-Type: image/jpeg".getBytes("UTF-8"));
            baos.write(crlf.getBytes("UTF-8"));
            baos.write(crlf.getBytes("UTF-8"));
            baos.write(data);
            baos.write(crlf.getBytes("UTF-8"));
            baos.write(("--" + boundary + "--").getBytes("UTF-8"));
            baos.write(crlf.getBytes("UTF-8"));
        } catch (java.io.IOException ignored) { }
        byte[] body = baos.toByteArray();
        Log.d(TAG, "[API/AVATAR] upload bytes=" + body.length);
        try {
            ApiClient.upload("/api/v1/users/me/avatar", boundary, body);
        } catch (java.io.IOException io) {
            throw new ApiException(-1, "网络错误: " + io.getMessage());
        }
    }

    private static byte[] readAllBytes(java.io.File f) throws java.io.IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try { fis.close(); } catch (java.io.IOException ignored) { }
        }
    }

    /**
     * GET /api/v1/photos?tag=&scene=&emotion=&page=&pageSize=
     * 维度筛选（人物/地点/情绪等 chip 触发）。
     * 当前阶段：保留为可调用入口，待后端 schema 就绪时把 allPhotos + chipKey 透传。
     */
    public static PagedPhotos filterByDimension(@Nullable String tag,
                                                @Nullable String scene,
                                                @Nullable String emotion,
                                                int page,
                                                int pageSize) throws ApiException {
        Map<String, String> q = new HashMap<>();
        if (tag != null) q.put("tag", tag);
        if (scene != null) q.put("scene", scene);
        if (emotion != null) q.put("emotion", emotion);
        q.put("page", String.valueOf(page));
        q.put("pageSize", String.valueOf(pageSize));
        return require(exec("GET", "/api/v1/photos", q, null), r -> {
            JSONObject obj = r.dataAsObject();
            JSONArray arr = obj.optJSONArray("list");
            List<PhotoSummary> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(PhotoSummary.fromJson(arr.getJSONObject(i)));
                }
            }
            return new PagedPhotos(list, obj.optInt("total"),
                    obj.optInt("page"), obj.optInt("pageSize"));
        });
    }

    /**
     * GET /api/v1/photos/{id}/similar
     * 视觉相似照片：当前阶段保留入口，后端 embedding 模型就绪后切换真实结果。
     */
    public static PagedPhotos similarPhotos(long photoId, int limit) throws ApiException {
        Map<String, String> q = new HashMap<>();
        q.put("limit", String.valueOf(limit));
        return require(exec("GET", "/api/v1/photos/" + photoId + "/similar", q, null), r -> {
            JSONObject obj = r.dataAsObject();
            JSONArray arr = obj.optJSONArray("list");
            List<PhotoSummary> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(PhotoSummary.fromJson(arr.getJSONObject(i)));
                }
            }
            return new PagedPhotos(list, obj.optInt("total"),
                    obj.optInt("page"), obj.optInt("pageSize"));
        });
    }
}