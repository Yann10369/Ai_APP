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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ai_photo.net.ApiService;
import com.ai_photo.net.Models.AiStatus;
import com.ai_photo.net.Models.CategoryPreview;
import com.ai_photo.net.Models.CategoryPreviewItem;
import com.ai_photo.net.Models.PhotoSummary;
import com.ai_photo.net.Models.PreviewPhoto;
import android.widget.ImageView;
import com.ai_photo.net.ImageLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/Main";

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private ProgressBar progressBar;
    private TextView tvPercent;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 未登录 → 跳 LoginActivity，不显示主页内容
        if (!com.ai_photo.auth.Session.isLoggedIn()) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scrollContent), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), v.getPaddingTop(), v.getPaddingEnd(), systemBars.bottom);
            return insets;
        });

        progressBar = findViewById(R.id.progressBar);
        tvPercent = findViewById(R.id.tvPercent);
        tvStatus = findViewById(R.id.tvStatus);

        setupImportButton();
        setupCategoryNavigation();
        setupSearchNavigation();
        setupBottomNav();
        setupHeaderActions();
        setupFilterButton();
        setupFilterChips();
        setupRecentSeeAll();

        // 数据加载
        loadAiStatus();
        loadRecentPhotos();
        loadCategories();
    }

    // --------------------------------------------------------------------
    //  底部导航 / 跳转
    // --------------------------------------------------------------------
    private void setupBottomNav() {
        findViewById(R.id.navAlbum).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Main] click bottomNav→Album");
            startActivity(new Intent(MainActivity.this, AlbumActivity.class));
            finish();
        });
        findViewById(R.id.navAI).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Main] click bottomNav→AI");
            startActivity(new Intent(MainActivity.this, AIAnalysisActivity.class));
            finish();
        });
        findViewById(R.id.navMe).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Main] click bottomNav→Me");
            startActivity(new Intent(MainActivity.this, ProfileActivity.class));
            finish();
        });
    }

    private void setupSearchNavigation() {
        View bar = findViewById(R.id.searchBar);
        if (bar != null) {
            bar.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Main] click searchBar→Search");
                startActivity(new Intent(MainActivity.this, com.ai_photo.search.SearchActivity.class));
            });
        }
    }

    private void setupCategoryNavigation() {
        View section = findViewById(R.id.smartCategories);
        if (section != null) {
            section.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Main] click smartCategories→AlbumActivity");
                startActivity(new Intent(MainActivity.this, AlbumActivity.class));
            });
        }
        // 智能分类卡片内的 "查看全部" TextView：跳到 AlbumActivity（相册主页 = 全部分类）
        bindSeeAllCategory();
    }

    private void bindSeeAllCategory() {
        View parent = findViewById(R.id.smartCategories);
        if (!(parent instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout section = (android.widget.LinearLayout) parent;
        if (section.getChildCount() == 0) return;
        View header = section.getChildAt(0);
        if (!(header instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout headerRow = (android.widget.LinearLayout) header;
        for (int i = 0; i < headerRow.getChildCount(); i++) {
            View c = headerRow.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) c;
                if ("查看全部".contentEquals(tv.getText().toString().trim())) {
                    tv.setClickable(true);
                    tv.setFocusable(true);
                    tv.setOnClickListener(v -> {
                        Log.d(TAG, "[UI/Main] click smartCategories→see all→AlbumActivity");
                        startActivity(new Intent(MainActivity.this, AlbumActivity.class));
                    });
                    return;
                }
            }
        }
    }

    private void setupRecentSeeAll() {
        // "最近照片 → 查看全部" 跳到 PhotoListActivity（全部照片模式）
        View parent = findViewById(R.id.recentPhotos);
        if (!(parent instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout section = (android.widget.LinearLayout) parent;
        if (section.getChildCount() == 0) return;
        View header = section.getChildAt(0);
        if (!(header instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout headerRow = (android.widget.LinearLayout) header;
        for (int i = 0; i < headerRow.getChildCount(); i++) {
            View c = headerRow.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) c;
                if ("查看全部".contentEquals(tv.getText().toString().trim())) {
                    tv.setClickable(true);
                    tv.setFocusable(true);
                    tv.setOnClickListener(v -> {
                        Log.d(TAG, "[UI/Main] click recent→查看全部");
                        Intent it = new Intent(MainActivity.this, PhotoListActivity.class);
                        it.putExtra(PhotoListActivity.EXTRA_MODE, PhotoListActivity.MODE_ALL);
                        startActivity(it);
                    });
                }
            }
        }
    }

    /**
     * 在已知父容器（card / section）里寻找 TextView "查看全部"，绑成跳转到目标 Activity。
     * 父容器必须是 LinearLayout；其第一个子节点是 header 横向 LinearLayout，里面第 2 个 TextView 即是。
     */
    private void bindSeeAllButton(int parentId, Class<?> target) {
        View parent = findViewById(parentId);
        if (!(parent instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout section = (android.widget.LinearLayout) parent;
        if (section.getChildCount() == 0) return;
        View header = section.getChildAt(0);
        if (!(header instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout headerRow = (android.widget.LinearLayout) header;
        for (int i = 0; i < headerRow.getChildCount(); i++) {
            View c = headerRow.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) c;
                if ("查看全部".contentEquals(tv.getText().toString().trim())) {
                    tv.setClickable(true);
                    tv.setFocusable(true);
                    tv.setOnClickListener(v -> {
                        Log.d(TAG, "[UI/Main] click section→查看全部 target=" + target.getSimpleName());
                        startActivity(new Intent(this, target));
                    });
                    return;
                }
            }
        }
    }

    private void setupImportButton() {
        findViewById(R.id.importBtn).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Main] click importBtn");
            doImportPhotos();
        });
    }

    /**
     * 入口：复用 AI 页面的多图选图能力，跳到 AI 页让用户继续走真实上传流程。
     * 上传协议已由 ApiService.uploadPhotos(multipart) 真实实现。
     */
    private void doImportPhotos() {
        try {
            Intent i = new Intent(this, com.ai_photo.AIAnalysisActivity.class);
            i.putExtra("fromImport", true);
            startActivity(i);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "跳转 AI 页失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 7 个筛选 chip（全部 / 最近导入 / 已分析 / 未分析 / 人物 / 地点 / 情绪）。
     * 都没设 id，所以从 filterBar 拿到 HorizontalScrollView 内部 LinearLayout 一次遍历。
     * 点击：把当前 chip 切换为 selected drawable，并把 photoGrid 重新按 chip 语义过滤。
     */
    private void setupFilterChips() {
        View filterBar = findViewById(R.id.filterBar);
        if (!(filterBar instanceof android.widget.FrameLayout)) return;
        android.widget.FrameLayout bar = (android.widget.FrameLayout) filterBar;
        View scroll = bar.getChildAt(0);
        if (!(scroll instanceof android.widget.HorizontalScrollView)) return;
        android.widget.HorizontalScrollView hsv = (android.widget.HorizontalScrollView) scroll;
        View group = hsv.getChildAt(0);
        if (!(group instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout chipGroup = (android.widget.LinearLayout) group;

        String[] labels = { "全部", "最近导入", "已分析", "未分析", "人物", "地点", "情绪" };
        for (int i = 0; i < Math.min(labels.length, chipGroup.getChildCount()); i++) {
            final int idx = i;
            View c = chipGroup.getChildAt(i);
            if (!(c instanceof android.widget.TextView)) continue;
            c.setClickable(true);
            c.setFocusable(true);
            c.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Main] click chip idx=" + idx + " label=" + labels[idx]);
                onChipClicked(chipGroup, idx, labels);
            });
        }
    }

    private void onChipClicked(android.widget.LinearLayout group, int idx, String[] labels) {
        // 先把所有 chip 恢复成 unselected（首个除外，单独保留蓝色 selected 即可）
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) c;
                tv.setSelected(false);
                tv.setBackgroundResource(R.drawable.public_bg_chip_unselected);
                tv.setTextColor(0xFF1B1F2A);
            }
        }
        View picked = group.getChildAt(idx);
        if (picked instanceof android.widget.TextView) {
            ((android.widget.TextView) picked).setSelected(true);
            picked.setBackgroundResource(R.drawable.public_bg_chip_selected);
            ((android.widget.TextView) picked).setTextColor(0xFFFFFFFF);
        }
        currentChip = labels[idx];
        applyChipFilter();
    }

    private String currentChip = "全部";

    /**
     * 按 chip 过滤 photoGrid：
     *  - 全部/已分析/未分析/最近导入：本地缓存
     *  - 人物/地点/情绪：调 ApiService.filterPhotos(维度) 真实后端
     */
    private void applyChipFilter() {
        switch (currentChip) {
            case "全部":
            case "已分析":
            case "未分析":
            case "最近导入":
                loadRecentPhotos();   // 走 /photos/recent 后本地过滤
                return;
            case "人物":
            case "地点":
            case "情绪":
            default:
                loadFilteredPhotos(currentChip);
        }
    }

    private void loadFilteredPhotos(String dim) {
        Log.d(TAG, "[UI/Main] loadFilteredPhotos dim=" + dim);
        io.execute(() -> {
            String tag = null, scene = null, emotion = null;
            switch (dim) {
                case "人物":   tag = "person"; break;
                case "地点":   tag = "place";  break;
                case "情绪":   emotion = "any"; break;
                default: break;
            }
            List<PhotoSummary> out = new ArrayList<>();
            String err = null;
            try {
                com.ai_photo.net.Models.PagedPhotos p =
                        com.ai_photo.net.ApiService.filterByDimension(tag, scene, emotion, 1, 24);
                if (p != null && p.list != null) out = p.list;
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<PhotoSummary> fOut = out;
            final String fErr = err;
            main.post(() -> {
                NonScrollGridView grid = findViewById(R.id.photoGrid);
                TextView empty = findViewById(R.id.recentEmpty);
                if (grid != null) {
                    grid.setAdapter(new PhotoAdapter(this, fOut));
                    grid.setVisibility(fOut.isEmpty() ? View.GONE : View.VISIBLE);
                }
                if (empty != null) {
                    empty.setVisibility(fOut.isEmpty() ? View.VISIBLE : View.GONE);
                }
                if (fErr != null) {
                    Toast.makeText(this, "筛选失败：" + fErr, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /**
     * 本地客户端 chip 过滤：按 status / 最近导入；不调后端。
     */
    private List<PhotoSummary> filterByChip(List<PhotoSummary> all) {
        if (all == null) return new ArrayList<>();
        switch (currentChip) {
            case "全部":
                return all;
            case "已分析":
                return filterByStatus(all, "done");
            case "未分析":
                return filterByStatus(all, "pending");
            case "最近导入":
                int n = Math.min(8, all.size());
                return new ArrayList<>(all.subList(0, n));
            default:
                return all;
        }
    }

    private static List<PhotoSummary> filterByStatus(List<PhotoSummary> all, String status) {
        List<PhotoSummary> out = new ArrayList<>();
        for (PhotoSummary p : all) {
            if (status == null || status.equalsIgnoreCase(p.analysisStatus)
                    || ("done".equals(status) && (p.analysisStatus == null
                            || "done".equalsIgnoreCase(p.analysisStatus)))) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 顶部右侧两个 squircle 按钮：
     *  - searchBtn → 跳 SearchActivity
     *  - moreBtn   → 弹出操作菜单（AI 分析 / 设置）
     */
    private void setupHeaderActions() {
        View searchBtn = findViewById(R.id.headerSearchBtn);
        if (searchBtn != null) {
            searchBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Main] click headerSearchBtn");
                startActivity(new Intent(MainActivity.this, com.ai_photo.search.SearchActivity.class));
            });
        }
        View moreBtn = findViewById(R.id.headerMoreBtn);
        if (moreBtn != null) {
            moreBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Main] click headerMoreBtn");
                showMoreMenu(moreBtn);
            });
        }
    }

    private void showMoreMenu(View anchor) {
        // 三点菜单规范：只显示"设置"
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "设置");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            Log.d(TAG, "[UI/Main] moreMenu select id=" + id);
            if (id == 1) {
                startActivity(new Intent(this, SettingsActivity.class));
            }
            return true;
        });
        menu.show();
    }

    /**
     * "筛选" 按钮：弹出简单的过滤类型菜单（场景 / 情绪 / 标签）
     * 选中后跳到 AlbumActivity，由那边继续渲染。
     */
    private void setupFilterButton() {
        View filterBtn = findViewById(R.id.filterBtn);
        if (filterBtn == null) return;
        filterBtn.setOnClickListener(v -> {
            String[] options = { "按场景分类", "按情绪分类", "按标签分类" };
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("筛选")
                    .setItems(options, (d, w) -> {
                        Intent i = new Intent(this, AlbumActivity.class);
                        startActivity(i);
                        finish();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    // --------------------------------------------------------------------
    //  AI 状态：从 /ai/status 拉取，刷新进度条 + 状态文本 + 3 个指标数字
    // --------------------------------------------------------------------
    private void loadAiStatus() {
        io.execute(() -> {
            AiStatus s = null;
            String aiMsg = null;
            try {
                s = ApiService.getAiStatus();
            } catch (Exception e) {
                aiMsg = e.getMessage();
            }

            // 标签数量：来自 /categories?type=tag 的 list.size
            int tagCount = -1;
            String tagMsg = null;
            try {
                com.ai_photo.net.Models.CategoryList cl =
                        ApiService.getCategories("tag");
                tagCount = (cl == null || cl.list == null) ? 0 : cl.list.size();
            } catch (Exception e) {
                tagMsg = e.getMessage();
            }

            final AiStatus finalS = s;
            final String finalAiMsg = aiMsg;
            final int finalTag = tagCount;
            final String finalTagMsg = tagMsg;
            main.post(() -> {
                if (finalS != null) {
                    int percent = (int) Math.round(finalS.progress * 100);
                    progressBar.setProgress(percent);
                    tvPercent.setText(percent + "%");
                    tvStatus.setText("已完成 " + finalS.done + " / " + finalS.total
                            + "，待分析 " + finalS.pending);
                    updateHomeMetrics(finalS, finalTag);
                } else {
                    String msg = finalAiMsg == null ? "AI 状态获取失败" : finalAiMsg;
                    tvStatus.setText("AI 状态获取失败: " + msg);
                    progressBar.setProgress(0);
                    tvPercent.setText("--");
                    // 即使 AI 状态失败，也尝试把标签数字写进去
                    updateHomeMetrics(null, finalTag);
                }
                // 标签接口失败时静默（前端默认 0 即可）
                if (finalTagMsg != null && finalTag < 0) { /* no-op */ }
            });
        });
    }

    /**
     * 把首页看板 3 个数字按 /ai/status + /categories?type=tag 拉到的真实数据写进去。
     * 3 个 TextView 没有 id，所以首次进入时按结构打 tag，后续直接 findViewWithTag 写值。
     */
    private void updateHomeMetrics(AiStatus s, int tagCount) {
        tagHomeMetricsIfNeeded();
        setTextByTag("homeMetricAnalyzed",
                s == null ? "--" : formatCount(s.done));
        setTextByTag("homeMetricPending",
                s == null ? "--" : formatCount(s.pending));
        if (tagCount >= 0) {
            setTextByTag("homeMetricTags", formatCount(tagCount));
        }
    }

    private void tagHomeMetricsIfNeeded() {
        View dashboard = findViewById(R.id.dashboard);
        if (!(dashboard instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout root = (android.widget.LinearLayout) dashboard;
        // 0=左侧 import FrameLayout, 1=右侧 AI 状态卡 LinearLayout
        if (root.getChildCount() < 2) return;
        View right = root.getChildAt(1);
        if (!(right instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout card = (android.widget.LinearLayout) right;
        // 0=header row, 1=3-column data row, 2=progress, 3=status row
        if (card.getChildCount() < 2) return;
        View dataRow = card.getChildAt(1);
        if (!(dataRow instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout row = (android.widget.LinearLayout) dataRow;
        // 0=col1, 1=divider, 2=col2, 3=divider, 4=col3
        String[] tags = { "homeMetricAnalyzed", "homeMetricPending", "homeMetricTags" };
        int[] colIdx = { 0, 2, 4 };
        for (int k = 0; k < 3; k++) {
            if (row.getChildCount() <= colIdx[k]) continue;
            View col = row.getChildAt(colIdx[k]);
            if (!(col instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout colLL = (android.widget.LinearLayout) col;
            // 每列：0=label TextView, 1=LinearLayout(value + unit)
            if (colLL.getChildCount() < 2) continue;
            View valueGroup = colLL.getChildAt(1);
            if (!(valueGroup instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout vg = (android.widget.LinearLayout) valueGroup;
            if (vg.getChildCount() == 0) continue;
            TextView value = (TextView) vg.getChildAt(0);
            value.setTag(tags[k]);
        }
    }

    private void setTextByTag(Object tag, String text) {
        if (tag == null) return;
        View v = findViewById(android.R.id.content).findViewWithTag(tag);
        if (v instanceof TextView) ((TextView) v).setText(text);
    }

    /** 千分位格式：3560 → "3,560"；0/负数原样 */
    private static String formatCount(int n) {
        if (n <= 0) return String.valueOf(n);
        return String.format("%,d", n);
    }

    // --------------------------------------------------------------------
    //  最近照片网格：拉 /photos (page=1, pageSize=20)，渲染 4 列
    //  说明：原 /photos/recent 接口返回结构与客户端 dataAsArray() 假设不一致，
    //       这里改用已确认存在的分页接口 /photos，行为等价（按时间倒序）
    // --------------------------------------------------------------------
    private void loadRecentPhotos() {
        io.execute(() -> {
            final List<PhotoSummary> raw;
            try {
                raw = ApiService.listPhotos(1, 20).list;
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "最近照片加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }
            final List<PhotoSummary> filtered = filterByChip(raw);
            main.post(() -> {
                NonScrollGridView grid = findViewById(R.id.photoGrid);
                TextView empty = findViewById(R.id.recentEmpty);
                if (grid != null) {
                    grid.setAdapter(new PhotoAdapter(this, filtered));
                    grid.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
                }
                if (empty != null) {
                    empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
                }
                Log.d(TAG, "[UI/Main] loadRecentPhotos total=" + (raw == null ? 0 : raw.size())
                        + " shown=" + filtered.size());
            });
        });
    }

    // --------------------------------------------------------------------
    //  智能分类：拉 /categories/preview，每类展示前 4 张
    // --------------------------------------------------------------------
    private void loadCategories() {
        io.execute(() -> {
            final CategoryPreview preview;
            try {
                preview = ApiService.getCategoriesPreview(4);
            } catch (Exception e) {
                main.post(() -> bindCategories(null));
                return;
            }
            main.post(() -> bindCategories(preview));
        });
    }

    /**
     * 把分类预览渲染到 categoriesContainer。
     * 三个分类区（场景 / 情绪 / 标签）各显示前 4 张缩略图。
     */
    private void bindCategories(@Nullable CategoryPreview preview) {
        View section = findViewById(R.id.smartCategories);
        LinearLayout container = findViewById(R.id.categoriesContainer);
        container.removeAllViews();

        if (preview == null
                || (isEmpty(preview.scene) && isEmpty(preview.emotion) && isEmpty(preview.tag))) {
            section.setVisibility(View.GONE);
            return;
        }
        section.setVisibility(View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(this);

        // 优先级：场景 → 情绪 → 标签；每个取前若干条
        List<CategoryPreviewItem> merged = new ArrayList<>();
        if (preview.scene != null) merged.addAll(preview.scene);
        if (preview.emotion != null) merged.addAll(preview.emotion);
        if (preview.tag != null) merged.addAll(preview.tag);

        for (int i = 0; i < merged.size(); i++) {
            CategoryPreviewItem c = merged.get(i);
            View item = inflater.inflate(R.layout.item_category, container, false);
            bindCategoryItem(item, c);

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) item.getLayoutParams();
            lp.bottomMargin = 0;
            // 横向滑动相册：每张分类封面之间留 12dp 间隔，避免紧贴
            lp.rightMargin = dp(12);
            container.addView(item, lp);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static boolean isEmpty(@Nullable List<?> l) {
        return l == null || l.isEmpty();
    }

    private void bindCategoryItem(View item, CategoryPreviewItem c) {
        TextView tvName = item.findViewById(R.id.categoryName);
        ImageView ivCover = item.findViewById(R.id.ivCover);

        // 分类名直接展示（含 emoji，例如 "🏖️ 海滩"），不再做去 emoji 处理
        tvName.setText(c.categoryName == null ? "未命名" : c.categoryName);

        // 封面：取第一张预览图作为代表；没图就保持占位 drawable
        String coverUrl = null;
        if (c.previewPhotos != null && !c.previewPhotos.isEmpty()) {
            PreviewPhoto pp = c.previewPhotos.get(0);
            if (pp != null) coverUrl = pp.thumbnailUrl;
        }
        ImageLoader.load(ivCover, coverUrl);
    }

    // 旧 IconRegistry / 去 emoji 工具方法已删除 ——
    // 分类名 "🏖️ 海滩" 现在原样展示（emoji 自带视觉效果，不需要再查找本地图标）

    // --------------------------------------------------------------------
    //  照片网格适配器（缩略图占位 + 真实 URL 后续接 Glide）
    //  点击单项 → 跳到照片详情
    // --------------------------------------------------------------------
    private static class PhotoAdapter extends BaseAdapter {
        private final android.content.Context context;
        private final List<PhotoSummary> data;

        PhotoAdapter(android.content.Context ctx, List<PhotoSummary> data) {
            this.context = ctx;
            this.data = data;
        }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = View.inflate(context, R.layout.item_photo, null);
            }
            TextView tvTime = convertView.findViewById(R.id.tvTime);
            ImageView ivThumb = convertView.findViewById(R.id.ivThumb);
            final PhotoSummary p = data.get(position);
            tvTime.setText("#" + p.photoId);
            // 真实缩略图（来自 /photos/recent 接口的 thumbnailUrl）
            ImageLoader.load(ivThumb, p.thumbnailUrl);
            convertView.setClickable(true);
            convertView.setFocusable(true);
            convertView.setOnClickListener(v -> {
                Intent it = new Intent(context, PhotoDetailActivity.class);
                it.putExtra(PhotoDetailActivity.EXTRA_PHOTO_ID, p.photoId);
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).startActivity(it);
                } else {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(it);
                }
            });
            return convertView;
        }
    }
}