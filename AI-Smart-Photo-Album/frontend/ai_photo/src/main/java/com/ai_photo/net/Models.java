package com.ai_photo.net;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据模型层：把后端 JSON 转换为 Java 对象。
 *
 * 所有 DTO 都是不可变的（字段 final），提供静态 fromJson(...) 工厂方法。
 * 不使用 Gson/Jackson，保持零三方依赖。
 */
public final class Models {
    private Models() { }

    // ============================================================
    //  通用辅助
    // ============================================================

    @Nullable
    private static String optString(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        return o.optString(key, null);
    }

    private static long optLong(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return 0;
        return o.optLong(key, 0);
    }

    private static int optInt(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return 0;
        return o.optInt(key, 0);
    }

    private static double optDouble(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return 0d;
        return o.optDouble(key, 0d);
    }

    @Nullable
    private static JSONObject optObj(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        return o.optJSONObject(key);
    }

    @Nullable
    private static JSONArray optArr(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        return o.optJSONArray(key);
    }

    private static List<JSONObject> objList(JSONArray arr) throws JSONException {
        List<JSONObject> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            list.add(arr.getJSONObject(i));
        }
        return list;
    }

    // ============================================================
    //  认证
    // ============================================================
    public static final class AuthResult {
        public final long userId;
        public final String username;
        @Nullable public final String token;
        public final int expiresIn;

        public AuthResult(long userId, String username, String token, int expiresIn) {
            this.userId = userId;
            this.username = username;
            this.token = token;
            this.expiresIn = expiresIn;
        }

        public static AuthResult fromJson(JSONObject o) throws JSONException {
            return new AuthResult(
                    optLong(o, "userId"),
                    optString(o, "username"),
                    optString(o, "token"),
                    optInt(o, "expiresIn"));
        }
    }

    // ============================================================
    //  用户
    // ============================================================
    public static final class UserMe {
        public final long userId;
        @Nullable public final String username;
        @Nullable public final String email;
        @Nullable public final String avatarUrl;
        @Nullable public final String createdAt;

        public UserMe(long userId, String username, String email, String avatarUrl, String createdAt) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.avatarUrl = avatarUrl;
            this.createdAt = createdAt;
        }

        public static UserMe fromJson(JSONObject o) {
            return new UserMe(
                    optLong(o, "userId"),
                    optString(o, "username"),
                    optString(o, "email"),
                    optString(o, "avatarUrl"),
                    optString(o, "createdAt"));
        }
    }

    public static final class CategoryDistribution {
        @Nullable public final String name;
        public final int count;
        /** 0~100（接口约定 0~1，fromJson 已自动 *100） */
        public final double percentage;

        public CategoryDistribution(String name, int count, double percentage) {
            this.name = name;
            this.count = count;
            this.percentage = percentage;
        }

        public static CategoryDistribution fromJson(JSONObject o) {
            // interface.md 2.2 / 6.4 约定 percentage 是 0~1；统一换算成 0~100 给 UI 用
            double raw = optDouble(o, "percentage");
            double pct = raw <= 1.0001 ? raw * 100.0 : raw;
            return new CategoryDistribution(
                    optString(o, "name"),
                    optInt(o, "count"),
                    pct);
        }
    }

    public static final class UserStatistics {
        public final int totalPhotos;
        public final int analyzedPhotos;
        public final int favoriteCount;
        @Nullable public final List<CategoryDistribution> scene;
        @Nullable public final List<CategoryDistribution> emotion;
        @Nullable public final List<CategoryDistribution> tag;

        public UserStatistics(int totalPhotos, int analyzedPhotos, int favoriteCount,
                              List<CategoryDistribution> scene,
                              List<CategoryDistribution> emotion,
                              List<CategoryDistribution> tag) {
            this.totalPhotos = totalPhotos;
            this.analyzedPhotos = analyzedPhotos;
            this.favoriteCount = favoriteCount;
            this.scene = scene;
            this.emotion = emotion;
            this.tag = tag;
        }

        public static UserStatistics fromJson(JSONObject o) throws JSONException {
            JSONObject dist = optObj(o, "categoryDistribution");
            return new UserStatistics(
                    optInt(o, "totalPhotos"),
                    optInt(o, "analyzedPhotos"),
                    optInt(o, "favoriteCount"),
                    parseDist(dist, "scene"),
                    parseDist(dist, "emotion"),
                    parseDist(dist, "tag"));
        }

        private static List<CategoryDistribution> parseDist(JSONObject dist, String key) throws JSONException {
            JSONArray arr = optArr(dist, key);
            if (arr == null) return new ArrayList<>();
            List<CategoryDistribution> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(CategoryDistribution.fromJson(arr.getJSONObject(i)));
            }
            return list;
        }
    }

    public static final class FavoritePhoto {
        public final long photoId;
        @Nullable public final String thumbnailUrl;
        @Nullable public final String favoritedAt;

        public FavoritePhoto(long photoId, String thumbnailUrl, String favoritedAt) {
            this.photoId = photoId;
            this.thumbnailUrl = thumbnailUrl;
            this.favoritedAt = favoritedAt;
        }

        public static FavoritePhoto fromJson(JSONObject o) {
            return new FavoritePhoto(
                    optLong(o, "photoId"),
                    optString(o, "thumbnailUrl"),
                    optString(o, "favoritedAt"));
        }
    }

    public static final class PagedFavorites {
        public final List<FavoritePhoto> list;
        public final int total;
        public final int page;
        public final int pageSize;

        public PagedFavorites(List<FavoritePhoto> list, int total, int page, int pageSize) {
            this.list = list;
            this.total = total;
            this.page = page;
            this.pageSize = pageSize;
        }
    }

    // ============================================================
    //  照片
    // ============================================================
    public static final class PhotoSummary {
        public final long photoId;
        @Nullable public final String thumbnailUrl;
        public final int width;
        public final int height;
        @Nullable public final String createdAt;
        public final boolean isFavorite;
        @Nullable public final String analysisStatus;

        public PhotoSummary(long photoId, String thumbnailUrl, int width, int height,
                            String createdAt, boolean isFavorite, String analysisStatus) {
            this.photoId = photoId;
            this.thumbnailUrl = thumbnailUrl;
            this.width = width;
            this.height = height;
            this.createdAt = createdAt;
            this.isFavorite = isFavorite;
            this.analysisStatus = analysisStatus;
        }

        public static PhotoSummary fromJson(JSONObject o) {
            return new PhotoSummary(
                    optLong(o, "photoId"),
                    optString(o, "thumbnailUrl"),
                    optInt(o, "width"),
                    optInt(o, "height"),
                    optString(o, "createdAt"),
                    o.optBoolean("isFavorite", false),
                    optString(o, "analysisStatus"));
        }
    }

    public static final class PagedPhotos {
        public final List<PhotoSummary> list;
        public final int total;
        public final int page;
        public final int pageSize;

        public PagedPhotos(List<PhotoSummary> list, int total, int page, int pageSize) {
            this.list = list;
            this.total = total;
            this.page = page;
            this.pageSize = pageSize;
        }
    }

    public static final class AiAnalysis {
        @Nullable public final String description;
        @Nullable public final NamedScore scene;
        @Nullable public final NamedScore emotion;
        @Nullable public final List<NamedScore> tags;

        public AiAnalysis(String description, NamedScore scene, NamedScore emotion, List<NamedScore> tags) {
            this.description = description;
            this.scene = scene;
            this.emotion = emotion;
            this.tags = tags;
        }

        public static AiAnalysis fromJson(JSONObject o) throws JSONException {
            JSONArray tagArr = optArr(o, "tags");
            List<NamedScore> tagList = new ArrayList<>();
            if (tagArr != null) {
                for (int i = 0; i < tagArr.length(); i++) {
                    tagList.add(NamedScore.fromJson(tagArr.getJSONObject(i)));
                }
            }
            return new AiAnalysis(
                    optString(o, "description"),
                    NamedScore.fromJsonOrNull(optObj(o, "scene")),
                    NamedScore.fromJsonOrNull(optObj(o, "emotion")),
                    tagList);
        }
    }

    public static final class NamedScore {
        @Nullable public final String name;
        public final double confidence;

        public NamedScore(String name, double confidence) {
            this.name = name;
            this.confidence = confidence;
        }

        @Nullable
        public static NamedScore fromJsonOrNull(@Nullable JSONObject o) {
            if (o == null) return null;
            return new NamedScore(optString(o, "name"), optDouble(o, "confidence"));
        }

        public static NamedScore fromJson(JSONObject o) {
            return new NamedScore(optString(o, "name"), optDouble(o, "confidence"));
        }
    }

    public static final class PhotoMetadata {
        @Nullable public final String fileName;
        public final long size;
        public final int width;
        public final int height;
        @Nullable public final String shotAt;

        public PhotoMetadata(String fileName, long size, int width, int height, String shotAt) {
            this.fileName = fileName;
            this.size = size;
            this.width = width;
            this.height = height;
            this.shotAt = shotAt;
        }

        public static PhotoMetadata fromJson(JSONObject o) {
            return new PhotoMetadata(
                    optString(o, "fileName"),
                    optLong(o, "size"),
                    optInt(o, "width"),
                    optInt(o, "height"),
                    optString(o, "shotAt"));
        }
    }

    public static final class PhotoDetail {
        public final long photoId;
        @Nullable public final String originalUrl;
        @Nullable public final String thumbnailUrl;
        @Nullable public final PhotoMetadata metadata;
        @Nullable public final AiAnalysis aiAnalysis;
        public final boolean isFavorite;
        @Nullable public final String createdAt;

        public PhotoDetail(long photoId, String originalUrl, String thumbnailUrl,
                           PhotoMetadata metadata, AiAnalysis aiAnalysis,
                           boolean isFavorite, String createdAt) {
            this.photoId = photoId;
            this.originalUrl = originalUrl;
            this.thumbnailUrl = thumbnailUrl;
            this.metadata = metadata;
            this.aiAnalysis = aiAnalysis;
            this.isFavorite = isFavorite;
            this.createdAt = createdAt;
        }

        public static PhotoDetail fromJson(JSONObject o) throws JSONException {
            return new PhotoDetail(
                    optLong(o, "photoId"),
                    optString(o, "originalUrl"),
                    optString(o, "thumbnailUrl"),
                    PhotoMetadata.fromJson(optObj(o, "metadata")),
                    o.has("aiAnalysis") && !o.isNull("aiAnalysis")
                            ? AiAnalysis.fromJson(o.getJSONObject("aiAnalysis")) : null,
                    o.optBoolean("isFavorite", false),
                    optString(o, "createdAt"));
        }
    }

    public static final class SearchResult {
        public final long photoId;
        @Nullable public final String thumbnailUrl;
        @Nullable public final List<String> matchedTags;
        public final double score;

        public SearchResult(long photoId, String thumbnailUrl, List<String> matchedTags, double score) {
            this.photoId = photoId;
            this.thumbnailUrl = thumbnailUrl;
            this.matchedTags = matchedTags;
            this.score = score;
        }

        public static SearchResult fromJson(JSONObject o) throws JSONException {
            JSONArray tagsArr = optArr(o, "matchedTags");
            List<String> tags = new ArrayList<>();
            if (tagsArr != null) {
                for (int i = 0; i < tagsArr.length(); i++) tags.add(tagsArr.getString(i));
            }
            return new SearchResult(
                    optLong(o, "photoId"),
                    optString(o, "thumbnailUrl"),
                    tags,
                    optDouble(o, "score"));
        }
    }

    public static final class PagedSearch {
        public final List<SearchResult> list;
        public final int total;
        public final int page;
        public final int pageSize;

        public PagedSearch(List<SearchResult> list, int total, int page, int pageSize) {
            this.list = list;
            this.total = total;
            this.page = page;
            this.pageSize = pageSize;
        }
    }

    public static final class UploadedPhoto {
        public final long photoId;
        @Nullable public final String originalName;
        @Nullable public final String thumbnailUrl;
        public final long size;
        @Nullable public final String analysisStatus;

        public UploadedPhoto(long photoId, String originalName, String thumbnailUrl,
                             long size, String analysisStatus) {
            this.photoId = photoId;
            this.originalName = originalName;
            this.thumbnailUrl = thumbnailUrl;
            this.size = size;
            this.analysisStatus = analysisStatus;
        }

        public static UploadedPhoto fromJson(JSONObject o) {
            return new UploadedPhoto(
                    optLong(o, "photoId"),
                    optString(o, "originalName"),
                    optString(o, "thumbnailUrl"),
                    optLong(o, "size"),
                    optString(o, "analysisStatus"));
        }
    }

    public static final class UploadResult {
        public final int successCount;
        public final int failCount;
        @Nullable public final List<UploadedPhoto> uploadedPhotos;

        public UploadResult(int successCount, int failCount, List<UploadedPhoto> uploadedPhotos) {
            this.successCount = successCount;
            this.failCount = failCount;
            this.uploadedPhotos = uploadedPhotos;
        }

        public static UploadResult fromJson(JSONObject o) throws JSONException {
            JSONArray arr = optArr(o, "uploadedPhotos");
            List<UploadedPhoto> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(UploadedPhoto.fromJson(arr.getJSONObject(i)));
                }
            }
            return new UploadResult(
                    optInt(o, "successCount"),
                    optInt(o, "failCount"),
                    list);
        }
    }

    // ============================================================
    //  分类
    // ============================================================
    public static final class PreviewPhoto {
        public final long photoId;
        @Nullable public final String thumbnailUrl;

        public PreviewPhoto(long photoId, String thumbnailUrl) {
            this.photoId = photoId;
            this.thumbnailUrl = thumbnailUrl;
        }

        public static PreviewPhoto fromJson(JSONObject o) {
            return new PreviewPhoto(
                    optLong(o, "photoId"),
                    optString(o, "thumbnailUrl"));
        }
    }

    public static final class CategoryPreviewItem {
        public final long categoryId;
        @Nullable public final String categoryName;
        @Nullable public final List<PreviewPhoto> previewPhotos;
        /** preview 接口里没有 photoCount 字段，list 里兼容 */
        public final int photoCount;

        public CategoryPreviewItem(long categoryId, String categoryName,
                                   List<PreviewPhoto> previewPhotos, int photoCount) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.previewPhotos = previewPhotos;
            this.photoCount = photoCount;
        }

        public static CategoryPreviewItem fromJson(JSONObject o) throws JSONException {
            JSONArray arr = optArr(o, "previewPhotos");
            List<PreviewPhoto> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(PreviewPhoto.fromJson(arr.getJSONObject(i)));
                }
            }
            return new CategoryPreviewItem(
                    optLong(o, "categoryId"),
                    optString(o, "categoryName"),
                    list,
                    optInt(o, "photoCount"));
        }
    }

    public static final class CategoryPreview {
        @Nullable public final List<CategoryPreviewItem> scene;
        @Nullable public final List<CategoryPreviewItem> emotion;
        @Nullable public final List<CategoryPreviewItem> tag;

        public CategoryPreview(List<CategoryPreviewItem> scene,
                               List<CategoryPreviewItem> emotion,
                               List<CategoryPreviewItem> tag) {
            this.scene = scene;
            this.emotion = emotion;
            this.tag = tag;
        }

        public static CategoryPreview fromJson(JSONObject o) throws JSONException {
            return new CategoryPreview(
                    parseList(o, "scene"),
                    parseList(o, "emotion"),
                    parseList(o, "tag"));
        }

        private static List<CategoryPreviewItem> parseList(JSONObject o, String key) throws JSONException {
            JSONArray arr = optArr(o, key);
            List<CategoryPreviewItem> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(CategoryPreviewItem.fromJson(arr.getJSONObject(i)));
                }
            }
            return list;
        }
    }

    public static final class CategorySummary {
        public final long categoryId;
        @Nullable public final String categoryName;
        public final int photoCount;
        @Nullable public final String coverThumbnail;

        public CategorySummary(long categoryId, String categoryName, int photoCount, String coverThumbnail) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.photoCount = photoCount;
            this.coverThumbnail = coverThumbnail;
        }

        public static CategorySummary fromJson(JSONObject o) {
            return new CategorySummary(
                    optLong(o, "categoryId"),
                    optString(o, "categoryName"),
                    optInt(o, "photoCount"),
                    optString(o, "coverThumbnail"));
        }
    }

    public static final class CategoryList {
        @Nullable public final String type;
        @Nullable public final List<CategorySummary> list;

        public CategoryList(String type, List<CategorySummary> list) {
            this.type = type;
            this.list = list;
        }

        public static CategoryList fromJson(JSONObject o) throws JSONException {
            JSONArray arr = optArr(o, "list");
            List<CategorySummary> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(CategorySummary.fromJson(arr.getJSONObject(i)));
                }
            }
            return new CategoryList(optString(o, "type"), list);
        }
    }

    public static final class CategoryPhotos {
        public final long categoryId;
        @Nullable public final String categoryName;
        @Nullable public final List<PhotoSummary> list;
        public final int total;
        public final int page;
        public final int pageSize;

        public CategoryPhotos(long categoryId, String categoryName, List<PhotoSummary> list,
                              int total, int page, int pageSize) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.list = list;
            this.total = total;
            this.page = page;
            this.pageSize = pageSize;
        }

        public static CategoryPhotos fromJson(JSONObject o) throws JSONException {
            JSONArray arr = optArr(o, "list");
            List<PhotoSummary> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    list.add(PhotoSummary.fromJson(arr.getJSONObject(i)));
                }
            }
            return new CategoryPhotos(
                    optLong(o, "categoryId"),
                    optString(o, "categoryName"),
                    list,
                    optInt(o, "total"),
                    optInt(o, "page"),
                    optInt(o, "pageSize"));
        }
    }

    // ============================================================
    //  AI
    // ============================================================
    public static final class AiStatus {
        public final int total;
        public final int done;
        public final int pending;
        public final double progress;

        public AiStatus(int total, int done, int pending, double progress) {
            this.total = total;
            this.done = done;
            this.pending = pending;
            this.progress = progress;
        }

        public static AiStatus fromJson(JSONObject o) {
            return new AiStatus(
                    optInt(o, "total"),
                    optInt(o, "done"),
                    optInt(o, "pending"),
                    optDouble(o, "progress"));
        }
    }

    // ============================================================
    //  分类管理
    // ============================================================
    public static final class AdminCategory {
        public final long categoryId;
        @Nullable public final String type;
        @Nullable public final String name;
        @Nullable public final String iconUrl;
        public final int photoCount;
        @Nullable public final String createdAt;

        public AdminCategory(long categoryId, String type, String name, String iconUrl,
                             int photoCount, String createdAt) {
            this.categoryId = categoryId;
            this.type = type;
            this.name = name;
            this.iconUrl = iconUrl;
            this.photoCount = photoCount;
            this.createdAt = createdAt;
        }

        public static AdminCategory fromJson(JSONObject o) {
            return new AdminCategory(
                    optLong(o, "categoryId"),
                    optString(o, "type"),
                    optString(o, "name"),
                    optString(o, "iconUrl"),
                    optInt(o, "photoCount"),
                    optString(o, "createdAt"));
        }
    }
}