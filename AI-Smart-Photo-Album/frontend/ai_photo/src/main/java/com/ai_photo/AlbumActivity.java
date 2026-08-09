package com.ai_photo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import com.ai_photo.net.Models.CategorySummary;
import com.ai_photo.net.ImageLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智能分类相册页：
 * - 顶部 / 搜索栏 / 标签页（场景 / 情绪 / 标签）
 * - 三段分类列表（从 /api/v1/categories?type=... 拉取）
 * - 底部导航
 */
public class AlbumActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/Album";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView tabScene, tabEmotion, tabTag;
    private View sceneSection, emotionSection, tagSection;
    private ScrollView albumScroll;

    /** 外部（CategoryBrowseActivity）跳回本页面时，可通过此 extra 指定要滚到的 tab。 */
    public static final String EXTRA_INITIAL_TAB = "initialTab";

    private List<CategorySummary> scenes = Collections.emptyList();
    private List<CategorySummary> emotions = Collections.emptyList();
    private List<CategorySummary> tags = Collections.emptyList();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_album);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.albumScroll), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), v.getPaddingTop(), v.getPaddingEnd(), systemBars.bottom);
            return insets;
        });

        bindTabs();
        bindSeeAllButtons();
        setupBottomNav();
        setupHeaderActions();

        // 优先消费外部传入的 "initialTab"（从 CategoryBrowseActivity 跳回时滚动到对应分类段）
        String initial = getIntent().getStringExtra(EXTRA_INITIAL_TAB);
        if (initial != null) {
            TextView target;
            if ("emotion".equals(initial))      target = tabEmotion;
            else if ("tag".equals(initial))     target = tabTag;
            else if ("scene".equals(initial))   target = tabScene;
            else                               target = null;
            if (target != null) {
                selectTab(target);
                // 跳回时滚动到目标分类段
                final View sec = anchorForType(initial);
                if (sec != null) {
                    sec.post(() -> {
                        if (albumScroll != null) {
                            albumScroll.smoothScrollTo(0, Math.max(0, sec.getTop() - dp(8)));
                        }
                    });
                }
            }
        }

        loadAll();
    }

    // ============================================================
    //  Tabs：点击 → 高亮 + 平滑滚动到对应 section（不切换数据，默认全部展开）
    // ============================================================
    private void bindTabs() {
        tabScene     = findViewById(R.id.tabScene);
        tabEmotion   = findViewById(R.id.tabEmotion);
        tabTag       = findViewById(R.id.tabTag);
        sceneSection = findViewById(R.id.sceneSection);
        emotionSection = findViewById(R.id.emotionSection);
        tagSection   = findViewById(R.id.tagSection);
        albumScroll  = findViewById(R.id.albumScroll);

        View.OnClickListener listener = v -> {
            TextView tv = (TextView) v;
            Log.d(TAG, "[UI/Album] click tab " + tv.getText());
            selectTab(tv);
            View sec;
            if (tv == tabScene)        sec = sceneSection;
            else if (tv == tabEmotion) sec = emotionSection;
            else                       sec = tagSection;
            if (sec != null && albumScroll != null) {
                sec.post(() -> albumScroll.smoothScrollTo(0, Math.max(0, sec.getTop() - dp(8))));
            }
        };
        tabScene.setOnClickListener(listener);
        tabEmotion.setOnClickListener(listener);
        tabTag.setOnClickListener(listener);
        selectTab(tabScene);
    }

    /**
     * 3 段"查看全部"按钮：跳 CategoryBrowseActivity 的 type_list 模式，
     * 显示对应 type 的全部分类，1 行 4 列网格。
     */
    private void bindSeeAllButtons() {
        View.OnClickListener seeAllListener = v -> {
            String type;
            int id = v.getId();
            if (id == R.id.btnSeeAllScene)        type = "scene";
            else if (id == R.id.btnSeeAllEmotion)  type = "emotion";
            else if (id == R.id.btnSeeAllTag)      type = "tag";
            else return;
            Log.d(TAG, "[UI/Album] click seeAll type=" + type);
            Intent i = new Intent(AlbumActivity.this, CategoryBrowseActivity.class);
            i.putExtra(CategoryBrowseActivity.EXTRA_MODE,
                    CategoryBrowseActivity.MODE_TYPE_LIST);
            i.putExtra(CategoryBrowseActivity.EXTRA_CATEGORY_TYPE, type);
            startActivity(i);
        };
        View btnScene  = findViewById(R.id.btnSeeAllScene);
        View btnEmotion = findViewById(R.id.btnSeeAllEmotion);
        View btnTag    = findViewById(R.id.btnSeeAllTag);
        if (btnScene  != null) btnScene.setOnClickListener(seeAllListener);
        if (btnEmotion != null) btnEmotion.setOnClickListener(seeAllListener);
        if (btnTag    != null) btnTag.setOnClickListener(seeAllListener);
    }

    private View anchorForType(String type) {
        if ("emotion".equals(type)) return emotionSection;
        if ("tag".equals(type))     return tagSection;
        return sceneSection;
    }

    private void selectTab(TextView selected) {
        TextView[] all = { tabScene, tabEmotion, tabTag };
        for (TextView tv : all) {
            if (tv == selected) {
                tv.setBackgroundResource(R.drawable.album_bg_segmented_selected);
                tv.setTextColor(0xFF4A90E2);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                tv.setBackground(null);
                tv.setTextColor(0xFF7B8597);
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
    }

    /** 全部展开：场景 + 情绪 + 标签 一次性渲染 */
    private void rebind() {
        bindScenes(scenes);
        bindEmotions(emotions);
        bindTags(tags);
    }

    // ============================================================
    //  底部导航
    // ============================================================
    private void setupBottomNav() {
        findViewById(R.id.navHome).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Album] click bottomNav→Home");
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        findViewById(R.id.navMe).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Album] click bottomNav→Me");
            startActivity(new Intent(this, ProfileActivity.class));
            finish();
        });
        findViewById(R.id.navAI).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Album] click bottomNav→AI");
            startActivity(new Intent(this, AIAnalysisActivity.class));
            finish();
        });
    }

    // ============================================================
    //  顶部右侧按钮：搜索 / 更多
    // ============================================================
    private void setupHeaderActions() {
        View searchBtn = findViewById(R.id.headerSearchBtn);
        if (searchBtn != null) {
            searchBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Album] click headerSearchBtn");
                startActivity(new Intent(this, com.ai_photo.search.SearchActivity.class));
            });
        }
        View moreBtn = findViewById(R.id.headerMoreBtn);
        if (moreBtn != null) {
            moreBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Album] click headerMoreBtn");
                // 三点菜单规范：只显示"设置"
                androidx.appcompat.widget.PopupMenu menu =
                        new androidx.appcompat.widget.PopupMenu(this, moreBtn);
                menu.getMenu().add(0, 1, 0, "设置");
                menu.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    Log.d(TAG, "[UI/Album] moreMenu select id=" + id);
                    if (id == 1) {
                        startActivity(new Intent(this, SettingsActivity.class));
                    }
                    return true;
                });
                menu.show();
            });
        }
    }

    // ============================================================
    //  数据加载：并发拉 3 个 type
    // ============================================================
    private void loadAll() {
        Log.d(TAG, "[UI/Album] loadAll start");
        io.execute(() -> {
            final List<CategorySummary> s, e, t;
            try {
                CategoryList cs = ApiService.getCategories("scene");
                CategoryList ce = ApiService.getCategories("emotion");
                CategoryList ct = ApiService.getCategories("tag");
                s = cs.list == null ? new ArrayList<>() : cs.list;
                e = ce.list == null ? new ArrayList<>() : ce.list;
                t = ct.list == null ? new ArrayList<>() : ct.list;
            } catch (Exception ex) {
                Log.e(TAG, "[UI/Album] loadAll EXC " + ex.getMessage());
                main.post(() -> Toast.makeText(this,
                        "分类加载失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }
            main.post(() -> {
                this.scenes = s;
                this.emotions = e;
                this.tags = t;
                Log.d(TAG, "[UI/Album] loadAll OK scene=" + s.size()
                        + " emotion=" + e.size() + " tag=" + t.size());
                rebind();
            });
        });
    }

    // ============================================================
    //  渲染
    // ============================================================
    private void bindScenes(List<CategorySummary> list) {
        LinearLayout container = findViewById(R.id.sceneContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < list.size(); i++) {
            CategorySummary c = list.get(i);
            View item = inflater.inflate(R.layout.item_album_scene, container, false);

            TextView tvName = item.findViewById(R.id.sceneName);
            TextView tvCount = item.findViewById(R.id.sceneCount);
            ImageView ivCover = item.findViewById(R.id.ivCover);

            tvName.setText(c.categoryName == null ? "未命名" : c.categoryName);
            tvCount.setText(c.photoCount + " 张");

            // 封面：用 category 的 coverThumbnail（来自 /categories?type=scene）
            ImageLoader.load(ivCover, c.coverThumbnail);

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) item.getLayoutParams();
            lp.rightMargin = dp(6);
            bindCategoryItemClick(item, c, "scene");
            container.addView(item, lp);
        }
    }

    private void bindEmotions(List<CategorySummary> list) {
        LinearLayout container = findViewById(R.id.emotionContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < list.size(); i++) {
            CategorySummary c = list.get(i);
            View item = inflater.inflate(R.layout.item_album_emotion, container, false);

            TextView tvName = item.findViewById(R.id.emotionName);
            TextView tvCount = item.findViewById(R.id.emotionCount);
            ImageView ivCover = item.findViewById(R.id.ivCover);

            tvName.setText(c.categoryName == null ? "未命名" : c.categoryName);
            tvCount.setText(c.photoCount + " 张");

            ImageLoader.load(ivCover, c.coverThumbnail);

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) item.getLayoutParams();
            lp.rightMargin = dp(6);
            bindCategoryItemClick(item, c, "emotion");
            container.addView(item, lp);
        }
    }

    private void bindTags(List<CategorySummary> list) {
        LinearLayout colLeft = findViewById(R.id.tagColumnLeft);
        LinearLayout colRight = findViewById(R.id.tagColumnRight);
        colLeft.removeAllViews();
        colRight.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < list.size(); i++) {
            CategorySummary c = list.get(i);
            LinearLayout col = (i % 2 == 0) ? colLeft : colRight;
            View item = inflater.inflate(R.layout.item_album_tag, col, false);

            TextView tvName = item.findViewById(R.id.tagName);
            TextView tvCount = item.findViewById(R.id.tagCount);
            ImageView ivCover = item.findViewById(R.id.ivCover);

            tvName.setText(c.categoryName == null ? "未命名" : c.categoryName);
            tvCount.setText(c.photoCount + " 张");

            ImageLoader.load(ivCover, c.coverThumbnail);

            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) item.getLayoutParams();
            if (i > 0) lp.topMargin = dp(8);
            bindCategoryItemClick(item, c, "tag");
            col.addView(item, lp);
        }
    }

    /**
     * 分类 item 整张点击 → 跳到 CategoryBrowseActivity 详情模式。
     * type 标识当前 item 属于哪个分类段（scene / emotion / tag）。
     */
    private void bindCategoryItemClick(View item, CategorySummary c, String type) {
        item.setOnClickListener(v -> {
            Log.d(TAG, "[UI/Album] click category type=" + type
                    + " id=" + c.categoryId + " name=" + c.categoryName);
            Intent i = new Intent(this, CategoryBrowseActivity.class);
            i.putExtra(CategoryBrowseActivity.EXTRA_MODE, CategoryBrowseActivity.MODE_DETAIL);
            i.putExtra(CategoryBrowseActivity.EXTRA_CATEGORY_ID, c.categoryId);
            i.putExtra(CategoryBrowseActivity.EXTRA_CATEGORY_NAME,
                    c.categoryName == null ? "" : c.categoryName);
            i.putExtra(CategoryBrowseActivity.EXTRA_CATEGORY_TYPE, type);
            startActivity(i);
        });
    }

    /** 与 SearchActivity 一致的简易去 emoji（仅截断首段非 CJK / 非字母数字字符） */
    private static String stripEmoji(String s) {
        if (s == null) return "";
        int i = 0;
        while (i < s.length() && !isAlnumOrCjk(s.charAt(i))) i++;
        return i > 0 ? s.substring(i).trim() : s;
    }

    private static boolean isAlnumOrCjk(char c) {
        if (Character.isLetterOrDigit(c)) return true;
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    // ============================================================
    //  helpers
    // ============================================================
    // 旧 IconRegistry / 去 emoji / extractIconKey 工具方法已删除 ——
    // 分类名 "🏖️ 海滩" 现在原样展示（emoji 自带视觉效果）

    private int tagDotColor(String name) {
        int hash = name == null ? 0 : name.hashCode();
        int[] palette = {
                0xFF4A90E2, 0xFF00C4B6, 0xFF9013FE,
                0xFFFFB74D, 0xFFFF5A5A, 0xFF6FA8FF
        };
        return palette[Math.abs(hash) % palette.length];
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}