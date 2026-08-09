package com.ai_photo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ai_photo.net.ApiService;
import com.ai_photo.net.Models.CategoryPhotos;
import com.ai_photo.net.Models.FavoritePhoto;
import com.ai_photo.net.Models.PagedFavorites;
import com.ai_photo.net.Models.PagedPhotos;
import com.ai_photo.net.Models.PagedSearch;
import com.ai_photo.net.Models.PhotoSummary;
import com.ai_photo.net.ImageLoader;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 照片列表页（时间顺序 + 4 列网格）。
 *
 * 通过 Intent extra {@link #EXTRA_MODE} 切换三种模式：
 *  - {@link #MODE_ALL}        调 /api/v1/photos?page=&pageSize=，按 createdAt 分组
 *  - {@link #MODE_FAVORITES}  调 /api/v1/users/me/favorites?page=&pageSize=，按 favoritedAt 分组
 *  - {@link #MODE_CATEGORY}   调 /api/v1/categories/{id}/photos?page=&pageSize=，按 createdAt 分组
 *                              必传 EXTRA_CATEGORY_ID / EXTRA_CATEGORY_NAME / EXTRA_CATEGORY_TYPE
 *
 * 通用行为：4 列网格 + 触底自动加载下一页 + 单击进入 PhotoDetailActivity。
 */
public class PhotoListActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/PhotoList";

    /** 列出当前用户全部照片（按时间倒序） */
    public static final String MODE_ALL = "all";
    /** 列出当前用户收藏的照片（按收藏时间倒序） */
    public static final String MODE_FAVORITES = "favorites";
    /** 列出某一分类下的照片（按时间倒序） */
    public static final String MODE_CATEGORY = "category";
    /** 列出搜索结果照片（按相关度倒序，分页加载） */
    public static final String MODE_SEARCH = "search";

    /** Intent extra：模式字符串，默认 {@link #MODE_ALL} */
    public static final String EXTRA_MODE = "extra_mode";
    /** Intent extra：分类 ID（MODE_CATEGORY 必填） */
    public static final String EXTRA_CATEGORY_ID = "categoryId";
    /** Intent extra：分类名（MODE_CATEGORY 显示用） */
    public static final String EXTRA_CATEGORY_NAME = "categoryName";
    /** Intent extra：分类 type（scene/emotion/tag，MODE_CATEGORY 显示用） */
    public static final String EXTRA_CATEGORY_TYPE = "categoryType";
    /** Intent extra：搜索关键词（MODE_SEARCH 必填） */
    public static final String EXTRA_QUERY = "extra_query";

    private static final int PAGE_SIZE = 40;

    /** dp → px（基于当前 Activity 的资源密度） */
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
    /** ISO 8601 解析器（UTC） */
    private static final SimpleDateFormat ISO_PARSER;
    /** yyyy-MM-dd 用于日期分组（按设备本地时区） */
    private static final SimpleDateFormat DATE_KEY_FMT;
    /** yyyy-MM-dd 历史日期显示（本地时区） */
    private static final SimpleDateFormat DATE_DISPLAY_FMT;
    /** M月d日 历史日期显示（本地时区） */
    private static final SimpleDateFormat M_DAY_DISPLAY_FMT;
    /** HH:mm 角标时间（本地时区） */
    private static final SimpleDateFormat TIME_TAG_FMT;

    static {
        ISO_PARSER = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        ISO_PARSER.setTimeZone(TimeZone.getTimeZone("UTC"));
        // 本地时区：API 给的是 UTC 时间，分组应按用户"当地日历日"分
        DATE_KEY_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        DATE_KEY_FMT.setTimeZone(TimeZone.getDefault());
        DATE_DISPLAY_FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        DATE_DISPLAY_FMT.setTimeZone(TimeZone.getDefault());
        M_DAY_DISPLAY_FMT = new SimpleDateFormat("M月d日", Locale.CHINA);
        M_DAY_DISPLAY_FMT.setTimeZone(TimeZone.getDefault());
        TIME_TAG_FMT = new SimpleDateFormat("HH:mm", Locale.US);
        TIME_TAG_FMT.setTimeZone(TimeZone.getDefault());
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout photoListContainer;
    private TextView headerSubtitle, emptyText, headerTitle;

    private final List<PhotoSummary> data = new ArrayList<>();
    private boolean loading = false;
    private boolean finished = false;
    private int currentPage = 0;
    private int totalPages = 1;
    /** 当前列表模式（MODE_ALL / MODE_FAVORITES / MODE_CATEGORY / MODE_SEARCH） */
    private String mode = MODE_ALL;
    /** 分类模式下的分类 ID */
    private long categoryId = 0;
    /** 分类模式下的分类名（标题用） */
    private String categoryName = "";
    /** 分类模式下的 type（scene/emotion/tag，副标题用） */
    private String categoryType = "scene";
    /** 搜索模式下的关键词 */
    private String queryText = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_photo_list);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.photoListRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), systemBars.top, v.getPaddingEnd(), 0);
            return insets;
        });

        // 读取模式（默认 all）
        String m = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_CATEGORY.equals(m)) {
            mode = MODE_CATEGORY;
            categoryId = getIntent().getLongExtra(EXTRA_CATEGORY_ID, 0);
            categoryName = getIntent().getStringExtra(EXTRA_CATEGORY_NAME);
            categoryType = getIntent().getStringExtra(EXTRA_CATEGORY_TYPE);
            if (categoryName == null) categoryName = "";
            if (categoryType == null) categoryType = "scene";
        } else if (MODE_FAVORITES.equals(m)) {
            mode = MODE_FAVORITES;
        } else if (MODE_SEARCH.equals(m)) {
            mode = MODE_SEARCH;
            queryText = getIntent().getStringExtra(EXTRA_QUERY);
            if (queryText == null) queryText = "";
        } else {
            mode = MODE_ALL;
        }

        bindViews();
        setupHeader();
        applyModeTitle();
        setupBottomNav();

        loadNextPage();
    }

    // ============================================================
    //  视图绑定
    // ============================================================
    private void bindViews() {
        photoListContainer = findViewById(R.id.photoListContainer);
        headerTitle        = findViewById(R.id.headerTitle);
        headerSubtitle     = findViewById(R.id.headerSubtitle);
        emptyText          = findViewById(R.id.photoListEmpty);
    }

    private void setupHeader() {
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoList] click btnBack");
            finish();
        });
        View searchBtn = findViewById(R.id.headerSearchBtn);
        if (searchBtn != null) {
            searchBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/PhotoList] click headerSearchBtn");
                startActivity(new Intent(this, com.ai_photo.search.SearchActivity.class));
            });
        }
    }

    /**
     * 根据模式设置标题与初始副标题。
     * 副标题在数据到达后会被"共 N 张 · 第 X/Y 页"覆盖。
     */
    private void applyModeTitle() {
        if (headerTitle == null) return;
        if (MODE_CATEGORY.equals(mode)) {
            headerTitle.setText(categoryName == null || categoryName.isEmpty()
                    ? getString(R.string.photo_list_title) : categoryName);
            if (headerSubtitle != null) headerSubtitle.setText("按时间倒序");
        } else if (MODE_FAVORITES.equals(mode)) {
            headerTitle.setText("我的收藏");
            if (headerSubtitle != null) headerSubtitle.setText("按收藏时间倒序");
        } else if (MODE_SEARCH.equals(mode)) {
            headerTitle.setText("\"" + (queryText == null ? "" : queryText) + "\" 的搜索结果");
            if (headerSubtitle != null) headerSubtitle.setText("按相关度倒序");
        } else {
            headerTitle.setText(getString(R.string.photo_list_title));
            if (headerSubtitle != null) headerSubtitle.setText(getString(R.string.photo_list_subtitle));
        }
    }

    private void setupBottomNav() {
        findViewById(R.id.navHome).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoList] click bottomNav→Home");
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        findViewById(R.id.navAlbum).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoList] click bottomNav→Album");
            startActivity(new Intent(this, AlbumActivity.class));
            finish();
        });
        findViewById(R.id.navAI).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoList] click bottomNav→AI");
            startActivity(new Intent(this, AIAnalysisActivity.class));
            finish();
        });
        findViewById(R.id.navMe).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoList] click bottomNav→Me");
            startActivity(new Intent(this, ProfileActivity.class));
            finish();
        });
    }

    // ============================================================
    //  数据加载（分页 + 触底加载）
    // ============================================================
    private void loadNextPage() {
        if (loading || finished) {
            Log.d(TAG, "[UI/PhotoList] loadNextPage skip loading=" + loading + " finished=" + finished);
            return;
        }
        loading = true;
        int pageToLoad = currentPage + 1;
        Log.d(TAG, "[UI/PhotoList] loadNextPage mode=" + mode + " page=" + pageToLoad);

        if (MODE_CATEGORY.equals(mode)) {
            loadCategoryPage(pageToLoad);
        } else if (MODE_FAVORITES.equals(mode)) {
            loadFavoritesPage(pageToLoad);
        } else if (MODE_SEARCH.equals(mode)) {
            loadSearchPage(pageToLoad);
        } else {
            loadAllPhotosPage(pageToLoad);
        }
    }

    private void loadSearchPage(int pageToLoad) {
        if (queryText == null || queryText.isEmpty()) {
            main.post(() -> onPageLoaded(null, 0, 0, 0));
            return;
        }
        io.execute(() -> {
            final PagedSearch result;
            try {
                result = ApiService.searchPhotos(queryText, pageToLoad, PAGE_SIZE);
            } catch (Exception e) {
                main.post(() -> onPageError(e.getMessage()));
                return;
            }
            // SearchResult 不是 PhotoSummary，按 id/thumb 适配（PhotoList 用的是 PhotoSummary），
            // 这里把 SearchResult 转换为 PhotoSummary
            List<PhotoSummary> converted = new ArrayList<>();
            List<com.ai_photo.net.Models.SearchResult> src =
                    result == null ? null : result.list;
            if (src != null) {
                for (com.ai_photo.net.Models.SearchResult sr : src) {
                    if (sr == null) continue;
                    converted.add(new PhotoSummary(
                            sr.photoId, sr.thumbnailUrl,
                            0, 0, null, false, null));
                }
            }
            final int p = result == null ? 0 : result.page;
            final int t = result == null ? 0 : result.total;
            main.post(() -> onPageLoaded(converted, p, t, PAGE_SIZE));
        });
    }

    private void loadAllPhotosPage(int pageToLoad) {
        io.execute(() -> {
            final PagedPhotos result;
            try {
                result = ApiService.listPhotos(pageToLoad, PAGE_SIZE);
            } catch (Exception e) {
                main.post(() -> onPageError(e.getMessage()));
                return;
            }
            main.post(() -> onPageLoaded(
                    result == null ? null : result.list,
                    result == null ? 0 : result.page,
                    result == null ? 0 : result.total,
                    result == null ? 0 : result.pageSize));
        });
    }

    private void loadFavoritesPage(int pageToLoad) {
        io.execute(() -> {
            final PagedFavorites result;
            try {
                result = ApiService.getMyFavorites(pageToLoad, PAGE_SIZE);
            } catch (Exception e) {
                main.post(() -> onPageError(e.getMessage()));
                return;
            }
            main.post(() -> onPageLoaded(
                    result == null ? null : normalizeFavorites(result.list),
                    result == null ? 0 : result.page,
                    result == null ? 0 : result.total,
                    result == null ? 0 : result.pageSize));
        });
    }

    private void loadCategoryPage(int pageToLoad) {
        if (categoryId <= 0) {
            onPageError("分类 ID 缺失");
            return;
        }
        final long cid = categoryId;
        io.execute(() -> {
            final CategoryPhotos result;
            try {
                result = ApiService.getCategoryPhotos(cid, pageToLoad, PAGE_SIZE);
            } catch (Exception e) {
                main.post(() -> onPageError(e.getMessage()));
                return;
            }
            main.post(() -> onPageLoaded(
                    result == null ? null : result.list,
                    result == null ? 0 : result.page,
                    result == null ? 0 : result.total,
                    result == null ? 0 : result.pageSize));
        });
    }

    /**
     * 把 FavoritePhoto 归一化成 PhotoSummary：复用 photoId / thumbnailUrl，
     * 用 favoritedAt 作为 createdAt（用于按日期分组）。
     */
    private static List<PhotoSummary> normalizeFavorites(@Nullable List<FavoritePhoto> src) {
        if (src == null) return new ArrayList<>();
        List<PhotoSummary> out = new ArrayList<>(src.size());
        for (FavoritePhoto fp : src) {
            if (fp == null) continue;
            out.add(new PhotoSummary(
                    fp.photoId,
                    fp.thumbnailUrl,
                    0, 0,                 // 列表页用不到尺寸
                    fp.favoritedAt,       // 分组按 favoritedAt
                    true,                 // 收藏列表里默认 isFavorite=true
                    "favorited"));
        }
        return out;
    }

    private void onPageError(String err) {
        Log.e(TAG, "[UI/PhotoList] onPageError err=" + err);
        loading = false;
        if (data.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText("加载失败：" + err);
        }
    }

    private void onPageLoaded(@Nullable List<PhotoSummary> list, int apiPage, int total, int pageSize) {
        loading = false;
        if (list == null || list.isEmpty()) {
            Log.d(TAG, "[UI/PhotoList] onPageLoaded empty page=" + apiPage);
            finished = true;
            if (data.isEmpty()) {
                emptyText.setVisibility(View.VISIBLE);
                emptyText.setText(MODE_FAVORITES.equals(mode)
                        ? "还没有收藏的照片"
                        : getString(R.string.photo_list_empty));
            }
            return;
        }
        currentPage = apiPage > 0 ? apiPage : currentPage + 1;
        if (pageSize > 0) {
            totalPages = (total + pageSize - 1) / pageSize;
        }
        if (currentPage >= totalPages) finished = true;

        data.addAll(list);
        Log.d(TAG, "[UI/PhotoList] onPageLoaded OK added=" + list.size()
                + " total=" + total + " page=" + currentPage + "/" + totalPages);
        renderGroups();
        emptyText.setVisibility(View.GONE);

        if (headerSubtitle != null) {
            headerSubtitle.setText("共 " + total + " 张 · 第 " + currentPage + " / " + totalPages + " 页");
        }

        // 触底检测：当前 container 高度已超出 ScrollView 视口 + 内容
        scheduleAutoLoadIfNeeded();
    }

    /**
     * 触底自动加载：监听 ScrollView 滚动到底部事件（简化：每次 render 后检查）。
     * 用 post 把检查推迟到布局完成。
     */
    private void scheduleAutoLoadIfNeeded() {
        if (finished) return;
        final android.widget.ScrollView scroll = findViewById(R.id.photoListScroll);
        if (scroll == null) return;
        scroll.post(() -> {
            if (finished) return;
            View child = photoListContainer.getChildAt(photoListContainer.getChildCount() - 1);
            if (child == null) return;
            int bottom = child.getBottom();
            int scrollY = scroll.getScrollY();
            int height = scroll.getHeight();
            if (bottom - (scrollY + height) < 200) {
                loadNextPage();
            }
        });
    }

    // ============================================================
    //  分组渲染（按"日"分组：今天 / 昨天 / 每个具体日期各一段）
    //  段顺序：今天 → 昨天 → 历史日期（按日期从新到旧）
    //  每段最小单位为日，不显示精准时间（精准时间通过 item 角标展示）
    // ============================================================
    private static final String SEC_TODAY     = "today";
    private static final String SEC_YESTERDAY = "yesterday";

    /** 一个分组单元：日期 key + 展示标题 + 照片列表 */
    private static final class DateBucket {
        final String key;        // yyyy-MM-dd
        final String title;      // "今天" / "昨天" / "6月20日"
        final List<PhotoSummary> photos;
        DateBucket(String key, String title, List<PhotoSummary> photos) {
            this.key = key; this.title = title; this.photos = photos;
        }
    }

    /**
     * 把 data 按"日"分组：
     *  - 今天
     *  - 昨天
     *  - 更早：按 yyyy-MM-dd 一日一组
     * 排序：今天 → 昨天 → 历史日期（按日期从新到旧）
     */
    private void renderGroups() {
        String today = todayKey();
        String yesterday = shiftDayKey(today, -1);

        // key=yyyy-MM-dd → photos
        Map<String, List<PhotoSummary>> grouped = new LinkedHashMap<>();
        for (PhotoSummary p : data) {
            String key = dateKey(p.createdAt);
            if (key == null) key = "未知日期";
            List<PhotoSummary> bucket = grouped.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                grouped.put(key, bucket);
            }
            bucket.add(p);
        }

        // 排序：今天 → 昨天 → 历史日期从新到旧
        List<DateBucket> ordered = new ArrayList<>();
        List<PhotoSummary> todayList    = grouped.remove(today);
        List<PhotoSummary> yesterdayList = grouped.remove(yesterday);
        if (todayList    != null) ordered.add(new DateBucket(today, getString(R.string.photo_list_section_today), todayList));
        if (yesterdayList != null) ordered.add(new DateBucket(yesterday, getString(R.string.photo_list_section_yesterday), yesterdayList));

        // 历史日期：按 key 字符串倒序（yyyy-MM-dd 字典序 == 日期倒序）
        List<String> historyKeys = new ArrayList<>(grouped.keySet());
        Collections.sort(historyKeys, Collections.reverseOrder());
        for (String key : historyKeys) {
            ordered.add(new DateBucket(key, formatHistoryTitle(key), grouped.get(key)));
        }

        // 重建：每次都重新填充 container
        photoListContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DateBucket bucket : ordered) {
            if (bucket.photos.isEmpty()) continue;

            // section header
            TextView header = (TextView) inflater.inflate(
                    R.layout.item_photo_list_section, photoListContainer, false);
            header.setText(bucket.title);
            photoListContainer.addView(header);

            // 4 列 grid（程序化创建）
            NonScrollGridView grid = new NonScrollGridView(this);
            grid.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            grid.setNumColumns(4);
            grid.setHorizontalSpacing(dp(3));
            grid.setVerticalSpacing(dp(3));
            grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
            grid.setScrollBarStyle(android.view.View.SCROLLBARS_OUTSIDE_OVERLAY);
            grid.setAdapter(new PhotoGridAdapter(bucket.photos));
            photoListContainer.addView(grid);
        }
    }

    /**
     * 历史日期显示：
     *  - 当年：M月d日（如 "6月20日"）
     *  - 跨年：yyyy-MM-dd（如 "2025-12-31"）
     */
    private String formatHistoryTitle(String key) {
        try {
            Date d;
            synchronized (DATE_KEY_FMT) {
                d = DATE_KEY_FMT.parse(key);
            }
            if (d == null) return key;
            Calendar c = Calendar.getInstance(TimeZone.getDefault());
            int thisYear = c.get(Calendar.YEAR);
            c.setTime(d);
            int year = c.get(Calendar.YEAR);
            if (year == thisYear) {
                synchronized (M_DAY_DISPLAY_FMT) {
                    return M_DAY_DISPLAY_FMT.format(d);
                }
            }
            synchronized (DATE_DISPLAY_FMT) {
                return DATE_DISPLAY_FMT.format(d);
            }
        } catch (Exception e) {
            return key;
        }
    }

    // ============================================================
    //  适配器（4 列网格）
    // ============================================================
    private class PhotoGridAdapter extends BaseAdapter {
        private final List<PhotoSummary> list;
        PhotoGridAdapter(List<PhotoSummary> list) { this.list = list; }

        @Override public int getCount() { return list.size(); }
        @Override public Object getItem(int p) { return list.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(PhotoListActivity.this)
                        .inflate(R.layout.item_photo_list, parent, false);
                h = new ViewHolder();
                h.time = convertView.findViewById(R.id.listTimeTag);
                h.thumb = convertView.findViewById(R.id.ivThumb);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            PhotoSummary p = list.get(position);
            h.time.setText(timeOf(p.createdAt));

            // 真实缩略图（/photos 或 /users/me/favorites 返回的 thumbnailUrl）
            ImageLoader.load(h.thumb, p.thumbnailUrl);

            final long pid = p.photoId;
            convertView.setOnClickListener(v -> {
                Log.d(TAG, "[UI/PhotoList] click photo item photoId=" + pid);
                Intent it = new Intent(PhotoListActivity.this, PhotoDetailActivity.class);
                it.putExtra(PhotoDetailActivity.EXTRA_PHOTO_ID, pid);
                startActivity(it);
            });
            return convertView;
        }

        private class ViewHolder {
            TextView time;
            ImageView thumb;
        }
    }

    // ============================================================
    //  日期工具
    // ============================================================
    /** 把 createdAt 解析为 yyyy-MM-dd key；解析失败返回 null。 */
    @Nullable
    private static String dateKey(@Nullable String iso) {
        if (iso == null || iso.isEmpty()) return null;
        Date d = parseIso(iso);
        if (d == null) return null;
        synchronized (DATE_KEY_FMT) {
            return DATE_KEY_FMT.format(d);
        }
    }

    private static String timeOf(@Nullable String iso) {
        if (iso == null || iso.isEmpty()) return "";
        Date d = parseIso(iso);
        if (d == null) return "";
        synchronized (TIME_TAG_FMT) {
            return TIME_TAG_FMT.format(d);
        }
    }

    @Nullable
    private static Date parseIso(String iso) {
        try {
            // 截到秒：去掉毫秒 / 时区后缀
            String s = iso;
            int dot = s.indexOf('.');
            if (dot > 0) s = s.substring(0, dot);
            int z = s.indexOf('Z');
            if (z > 0) s = s.substring(0, z);
            int plus = s.lastIndexOf('+');
            if (plus > 10) s = s.substring(0, plus);
            int minus = s.lastIndexOf('-');
            if (minus > 10) s = s.substring(0, minus);
            return ISO_PARSER.parse(s);
        } catch (ParseException e) {
            return null;
        }
    }

    /** 今天 yyyy-MM-dd（按设备本地时区）。 */
    private String todayKey() {
        synchronized (DATE_KEY_FMT) {
            return DATE_KEY_FMT.format(new Date());
        }
    }

    /** yyyy-MM-dd key 偏移 delta 天。 */
    private static String shiftDayKey(String key, int delta) {
        try {
            Date d = DATE_KEY_FMT.parse(key);
            Calendar c = Calendar.getInstance(TimeZone.getDefault());
            c.setTime(d);
            c.add(Calendar.DAY_OF_YEAR, delta);
            return DATE_KEY_FMT.format(c.getTime());
        } catch (Exception e) {
            return key;
        }
    }
}