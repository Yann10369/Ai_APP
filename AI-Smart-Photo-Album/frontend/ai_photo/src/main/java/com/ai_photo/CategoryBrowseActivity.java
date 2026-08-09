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
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ai_photo.net.ApiService;
import com.ai_photo.net.Models.CategoryList;
import com.ai_photo.net.Models.CategoryPhotos;
import com.ai_photo.net.Models.CategorySummary;
import com.ai_photo.net.Models.PhotoSummary;
import com.ai_photo.net.ImageLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 分类浏览页（两模式）。
 *
 * Intent extras:
 *   EXTRA_MODE         "type_list"（默认入口）/"detail"
 *   EXTRA_CATEGORY_ID  详情模式必填
 *   EXTRA_CATEGORY_NAME  详情模式显示用
 *   EXTRA_CATEGORY_TYPE  "scene" / "emotion" / "tag"
 *                          - 详情模式：用于写副标题前缀
 *                          - type_list 模式：决定加载哪个 type 的全部分类
 *
 * type_list 模式（相册"查看全部"入口）：只显示某一 type 的全部分类，
 *           1 行 4 列网格。点击分类卡 → 跳 PhotoListActivity (MODE_CATEGORY)。
 * detail 模式：4 列照片网格（item_photo.xml）。
 */
public class CategoryBrowseActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/Category";

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_TYPE_LIST = "type_list";
    public static final String MODE_DETAIL = "detail";
    public static final String EXTRA_CATEGORY_ID = "categoryId";
    public static final String EXTRA_CATEGORY_NAME = "categoryName";
    public static final String EXTRA_CATEGORY_TYPE = "categoryType";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // 顶部 / 视图
    private TextView headerTitle, headerSubtitle;
    private NonScrollGridView photoGrid;
    private NonScrollGridView categoryGrid;
    private TextView emptyText;

    // 模式 / 数据
    private String mode = MODE_TYPE_LIST;
    private long categoryId = 0;
    private String categoryName = "";
    private String categoryType = "scene";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_category);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.categoryScroll), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), v.getPaddingTop(), v.getPaddingEnd(), systemBars.bottom);
            return insets;
        });

        Intent it = getIntent();
        String rawMode = it == null ? null : it.getStringExtra(EXTRA_MODE);
        if (MODE_DETAIL.equals(rawMode))        mode = MODE_DETAIL;
        else                                    mode = MODE_TYPE_LIST;
        if (it != null) {
            categoryId   = it.getLongExtra(EXTRA_CATEGORY_ID, 0);
            categoryName = it.getStringExtra(EXTRA_CATEGORY_NAME);
            categoryType = it.getStringExtra(EXTRA_CATEGORY_TYPE);
            if (categoryType == null) categoryType = "scene";
            if (categoryName == null) categoryName = "";
        }

        bindViews();
        bindBack();
        setupBottomNav();
        setupHeaderActions();

        if (MODE_DETAIL.equals(mode)) {
            enterDetailMode();
        } else {
            enterTypeListMode();
        }
    }

    // ============================================================
    //  视图绑定
    // ============================================================
    private void bindViews() {
        headerTitle    = findViewById(R.id.headerTitle);
        headerSubtitle = findViewById(R.id.headerSubtitle);
        photoGrid      = findViewById(R.id.photoGrid);
        categoryGrid   = findViewById(R.id.categoryGrid);
        emptyText      = findViewById(R.id.emptyText);
    }

    private void bindBack() {
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Category] click btnBack");
            finish();
        });
    }

    private void setupBottomNav() {
        findViewById(R.id.navHome).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Category] click bottomNav→Home");
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        findViewById(R.id.navAlbum).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Category] click bottomNav→Album");
            startActivity(new Intent(this, AlbumActivity.class));
            finish();
        });
        findViewById(R.id.navAI).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Category] click bottomNav→AI");
            startActivity(new Intent(this, AIAnalysisActivity.class));
            finish();
        });
        findViewById(R.id.navMe).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Category] click bottomNav→Me");
            startActivity(new Intent(this, ProfileActivity.class));
            finish();
        });
    }

    private void setupHeaderActions() {
        View searchBtn = findViewById(R.id.headerSearchBtn);
        if (searchBtn != null) {
            searchBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Category] click headerSearchBtn");
                startActivity(new Intent(this, com.ai_photo.search.SearchActivity.class));
            });
        }
        View moreBtn = findViewById(R.id.headerMoreBtn);
        if (moreBtn != null) {
            moreBtn.setOnClickListener(v -> {
                // 三点菜单规范：只显示"设置"
                androidx.appcompat.widget.PopupMenu menu =
                        new androidx.appcompat.widget.PopupMenu(this, moreBtn);
                menu.getMenu().add(0, 1, 0, "设置");
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        startActivity(new Intent(this, SettingsActivity.class));
                    }
                    return true;
                });
                menu.show();
            });
        }
    }

    // ============================================================
    //  type_list 模式：1 行 4 列分类网格（相册"查看全部"入口）
    // ============================================================
    private void enterTypeListMode() {
        Log.d(TAG, "[UI/Category] enterTypeListMode type=" + categoryType);
        mode = MODE_TYPE_LIST;

        // 顶部：标题 = 当前 type 的中文名
        headerTitle.setText(typeListTitle(categoryType));
        headerSubtitle.setText(getString(R.string.category_browse_subtitle));

        // 详情模式视图 GONE
        photoGrid.setVisibility(View.GONE);

        // 显示 categoryGrid + 加载态
        categoryGrid.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.VISIBLE);
        emptyText.setText("加载中…");

        loadTypeList();
    }

    private void loadTypeList() {
        final String type = categoryType == null ? "scene" : categoryType;
        io.execute(() -> {
            final List<CategorySummary> list;
            try {
                CategoryList cl = ApiService.getCategories(type);
                list = cl.list == null ? new ArrayList<>() : cl.list;
            } catch (Exception ex) {
                Log.e(TAG, "[UI/Category] loadTypeList EXC " + ex.getMessage());
                main.post(() -> {
                    emptyText.setVisibility(View.VISIBLE);
                    emptyText.setText("分类加载失败: " + ex.getMessage());
                });
                return;
            }
            main.post(() -> {
                Log.d(TAG, "[UI/Category] loadTypeList OK type=" + type
                        + " count=" + list.size());
                categoryGrid.setAdapter(new CategoryGridAdapter(type, list));
                if (list.isEmpty()) {
                    emptyText.setVisibility(View.VISIBLE);
                    emptyText.setText(getString(R.string.category_browse_empty));
                } else {
                    emptyText.setVisibility(View.GONE);
                }
            });
        });
    }

    private String typeListTitle(String type) {
        if ("emotion".equals(type)) return "情绪分类";
        if ("tag".equals(type))     return "标签分类";
        return "场景分类";
    }

    // ============================================================
    //  详情模式
    // ============================================================
    private void enterDetailMode() {
        Log.d(TAG, "[UI/Category] enterDetailMode id=" + categoryId + " name=" + categoryName);
        mode = MODE_DETAIL;

        // 顶部：标题 = 分类名
        headerTitle.setText(categoryName);
        headerSubtitle.setText(getString(R.string.category_detail_count, 0));

        categoryGrid.setVisibility(View.GONE);
        photoGrid.setVisibility(View.VISIBLE);
        photoGrid.setAdapter(new CategoryPhotoAdapter(new ArrayList<>()));

        emptyText.setVisibility(View.VISIBLE);
        emptyText.setText("加载中…");

        loadCategoryPhotos();
    }

    private void loadCategoryPhotos() {
        if (categoryId <= 0) {
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText("分类信息缺失");
            return;
        }
        final long cid = categoryId;
        io.execute(() -> {
            final CategoryPhotos result;
            try {
                result = ApiService.getCategoryPhotos(cid, 1, 60);
            } catch (Exception ex) {
                Log.e(TAG, "[UI/Category] loadCategoryPhotos EXC " + ex.getMessage());
                main.post(() -> {
                    emptyText.setVisibility(View.VISIBLE);
                    emptyText.setText("照片加载失败: " + ex.getMessage());
                });
                return;
            }
            main.post(() -> {
                List<PhotoSummary> photos = result == null || result.list == null
                        ? new ArrayList<>() : result.list;
                Log.d(TAG, "[UI/Category] loadCategoryPhotos OK count=" + photos.size());
                headerSubtitle.setText(getString(R.string.category_browse_photos_subtitle,
                        categoryName, photos.size()));
                photoGrid.setAdapter(new CategoryPhotoAdapter(photos));
                if (photos.isEmpty()) {
                    emptyText.setVisibility(View.VISIBLE);
                    emptyText.setText(getString(R.string.category_browse_empty_detail));
                } else {
                    emptyText.setVisibility(View.GONE);
                }
            });
        });
    }

    // ============================================================
    //  type_list 模式：1 行 4 列分类卡适配器（item_category_grid_card.xml）
    // ============================================================
    private class CategoryGridAdapter extends BaseAdapter {
        private final String type;
        private final List<CategorySummary> list;

        CategoryGridAdapter(String type, List<CategorySummary> list) {
            this.type = type == null ? "scene" : type;
            this.list = list == null ? new ArrayList<>() : list;
        }

        @Override public int getCount() { return list.size(); }
        @Override public Object getItem(int p) { return list.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(CategoryBrowseActivity.this)
                        .inflate(R.layout.item_category_grid_card, parent, false);
                h = new ViewHolder();
                h.ivCover = convertView.findViewById(R.id.ivCover);
                h.tvName  = convertView.findViewById(R.id.tvName);
                h.tvCount = convertView.findViewById(R.id.tvCount);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            final CategorySummary c = list.get(position);
            h.tvName.setText(c.categoryName == null ? "未命名" : c.categoryName);
            h.tvCount.setText(c.photoCount + " 张");
            // 封面：load 真实缩略图；失败则用 drawable 背景
            ImageLoader.load(h.ivCover, c.coverThumbnail);
            // 切回主线程：让 onCategoryCardClick 直接读 this.categoryType 即可
            final String capturedType = CategoryBrowseActivity.this.categoryType;
            convertView.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Category] click categoryCard pos=" + position
                        + " id=" + c.categoryId + " type=" + capturedType);
                Intent i = new Intent(CategoryBrowseActivity.this, PhotoListActivity.class);
                i.putExtra(PhotoListActivity.EXTRA_MODE, PhotoListActivity.MODE_CATEGORY);
                i.putExtra(PhotoListActivity.EXTRA_CATEGORY_ID, c.categoryId);
                i.putExtra(PhotoListActivity.EXTRA_CATEGORY_NAME,
                        c.categoryName == null ? "" : c.categoryName);
                i.putExtra(PhotoListActivity.EXTRA_CATEGORY_TYPE, capturedType);
                startActivity(i);
            });
            return convertView;
        }

        private class ViewHolder {
            ImageView ivCover;
            TextView tvName;
            TextView tvCount;
        }
    }

    // ============================================================
    //  详情模式照片适配器（item_photo.xml：ivThumb + tvTime）
    // ============================================================
    private class CategoryPhotoAdapter extends BaseAdapter {
        private final List<PhotoSummary> list;

        CategoryPhotoAdapter(List<PhotoSummary> list) {
            this.list = list == null ? new ArrayList<>() : list;
        }

        @Override public int getCount() { return list.size(); }
        @Override public Object getItem(int p) { return list.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = View.inflate(CategoryBrowseActivity.this,
                        R.layout.item_photo, null);
            }
            TextView tvTime = convertView.findViewById(R.id.tvTime);
            ImageView ivThumb = convertView.findViewById(R.id.ivThumb);
            final PhotoSummary p = list.get(position);
            tvTime.setText("#" + p.photoId);
            ImageLoader.load(ivThumb, p.thumbnailUrl);
            convertView.setOnClickListener(v -> {
                Intent it = new Intent(CategoryBrowseActivity.this, PhotoDetailActivity.class);
                it.putExtra(PhotoDetailActivity.EXTRA_PHOTO_ID, p.photoId);
                startActivity(it);
            });
            return convertView;
        }
    }

    // ============================================================
    //  helpers
    // ============================================================
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int tagDotColor(String name) {
        int hash = name == null ? 0 : name.hashCode();
        int[] palette = {
                0xFF4A90E2, 0xFF00C4B6, 0xFF9013FE,
                0xFFFFB74D, 0xFFFF5A5A, 0xFF6FA8FF
        };
        return palette[Math.abs(hash) % palette.length];
    }
}