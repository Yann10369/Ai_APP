package com.ai_photo.search;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ai_photo.MainActivity;
import com.ai_photo.NonScrollGridView;
import com.ai_photo.PhotoListActivity;
import com.ai_photo.R;
import com.ai_photo.net.ApiService;
import com.ai_photo.net.ImageLoader;
import com.ai_photo.net.Models.CategoryList;
import com.ai_photo.net.Models.CategorySummary;
import com.ai_photo.net.Models.NamedScore;
import com.ai_photo.net.Models.PagedSearch;
import com.ai_photo.net.Models.PhotoDetail;
import com.ai_photo.net.Models.SearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智能搜索页：
 *  - 搜索栏：点击 / 回车 → 调 /photos/search，刷新 4 列照片网格 + "找到 X 张"
 *  - 推荐 chip：点击 → 用 chip 文本作为查询关键词
 *  - 筛选面板：从 /categories 拉场景/情绪/标签作为可选 chip
 *  - 支持 EXTRA_QUERY：从分类 item 跳进来时直接填到输入框并触发搜索
 */
public class SearchActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/Search";

    public static final String EXTRA_QUERY = "extra_query";

    private boolean isFilterMode = false;

    /** 类别 → 标签列表 */
    private final Map<String, List<String>> allTags = new LinkedHashMap<>();
    /** 类别 → 标签名 → categoryId 映射（用于 /photos/filter 的 tagIds） */
    private final Map<String, Map<String, Long>> tagIdByCategory = new LinkedHashMap<>();
    /** 当前已选标签：name → (category, categoryId) */
    private final Map<String, SmallTag> selectedTags = new LinkedHashMap<>();

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    // 视图
    private View panelStateA, panelStateB;
    private View resultTitleA, resultTitleB;
    private LinearLayout historyContainer, recommendContainer;
    private LinearLayout chipsScene, chipsMood, chipsTime, chipsTag;
    private LinearLayout selectedTagsContainer;
    private ImageView filterToggleBtn, clearBtn;
    private EditText searchInput;
    private TextView resultCountA;
    private TextView aiSceneValue, aiMoodValue, aiTagValue;
    private NonScrollGridView photoGrid;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scrollContent), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), v.getPaddingTop(), v.getPaddingEnd(), systemBars.bottom);
            return insets;
        });

        bindViews();
        renderHistory();
        renderRecommend();
        setupSearchInput();
        setupFilterToggle();
        setupFilterPanel();
        setupResultGrid();
        setupBottomNav();

        loadCategoryChips();

        // 从外部跳进来：直接以 EXTRA_QUERY 触发一次搜索
        String seed = getIntent().getStringExtra(EXTRA_QUERY);
        if (seed != null && !seed.isEmpty()) {
            searchInput.setText(seed);
            doSearch();
        }
    }

    // --------------------------------------------------------------------
    //  视图绑定
    // --------------------------------------------------------------------
    private void bindViews() {
        panelStateA = findViewById(R.id.panelStateA);
        panelStateB = findViewById(R.id.panelStateB);
        resultTitleA = findViewById(R.id.resultTitleA);
        resultTitleB = findViewById(R.id.resultTitleB);
        historyContainer = findViewById(R.id.historyContainer);
        recommendContainer = findViewById(R.id.recommendContainer);
        chipsScene = findViewById(R.id.chipsScene);
        chipsMood = findViewById(R.id.chipsMood);
        chipsTime = findViewById(R.id.chipsTime);
        chipsTag = findViewById(R.id.chipsTag);
        selectedTagsContainer = findViewById(R.id.selectedTagsContainer);
        filterToggleBtn = findViewById(R.id.filterToggleBtn);
        clearBtn = findViewById(R.id.clearBtn);
        searchInput = findViewById(R.id.searchInput);
        resultCountA = findViewById(R.id.resultCountA);
        photoGrid = findViewById(R.id.photoGrid);
        aiSceneValue = findViewById(R.id.aiSceneValue);
        aiMoodValue = findViewById(R.id.aiMoodValue);
        aiTagValue = findViewById(R.id.aiTagValue);
        setupSeeAllSearch();
    }

    private TextView seeAllSearchBtn;
    private String lastQuery = "";

    private void setupSeeAllSearch() {
        seeAllSearchBtn = findViewById(R.id.seeAllSearchBtn);
        if (seeAllSearchBtn != null) {
            seeAllSearchBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Search] click seeAllSearchBtn q=\"" + lastQuery + "\"");
                if (lastQuery.isEmpty()) {
                    Log.w(TAG, "[UI/Search] click seeAllSearchBtn but lastQuery empty, ignore");
                    return;
                }
                Intent it = new Intent(this, PhotoListActivity.class);
                it.putExtra(PhotoListActivity.EXTRA_MODE, PhotoListActivity.MODE_SEARCH);
                it.putExtra(PhotoListActivity.EXTRA_QUERY, lastQuery);
                Log.d(TAG, "[UI/Search] startActivity PhotoListActivity MODE_SEARCH q=\""
                        + lastQuery + "\"");
                startActivity(it);
            });
        }
    }

    // --------------------------------------------------------------------
    //  历史 / 推荐（mock）
    // --------------------------------------------------------------------
    private void renderHistory() {
        String[] history = {
                "去年夏天在海边大笑的照片",
                "和朋友聚会的照片",
                "上周末旅行",
                "狗狗",
                "美食"
        };
        LayoutInflater inflater = LayoutInflater.from(this);
        historyContainer.removeAllViews();
        for (String text : history) {
            View item = inflater.inflate(R.layout.item_search_history_chip, historyContainer, false);
            ((TextView) item.findViewById(R.id.chipText)).setText(text);
            item.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Search] click historyChip text=\"" + text + "\"");
                searchInput.setText(text);
                doSearch();
            });
            historyContainer.addView(item);
        }
    }

    private void renderRecommend() {
        String[][] recommends = {
                {"🍴", "美食探店"},
                {"🐕", "有狗狗的照片"},
                {"🏖️", "海边旅行"},
                {"👨‍👩‍👧", "家庭合影"},
                {"🌅", "日落风景"},
                {"🎉", "派对聚会"}
        };
        LayoutInflater inflater = LayoutInflater.from(this);
        recommendContainer.removeAllViews();
        for (String[] r : recommends) {
            View item = inflater.inflate(R.layout.item_search_recommend_chip, recommendContainer, false);
            ((TextView) item.findViewById(R.id.chipEmoji)).setText(r[0]);
            ((TextView) item.findViewById(R.id.chipText)).setText(r[1]);
            item.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Search] click recommendChip text=\"" + r[1] + "\"");
                searchInput.setText(r[1]);
                doSearch();
            });
            recommendContainer.addView(item);
        }
    }

    // --------------------------------------------------------------------
    //  搜索输入
    // --------------------------------------------------------------------
    private void setupSearchInput() {
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        clearBtn.setOnClickListener(v -> {
            Log.d(TAG, "[UI/Search] click clearBtn");
            searchInput.setText("");
        });
        findViewById(R.id.searchActionBtn).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Search] click searchActionBtn isFilterMode=" + isFilterMode);
            dispatchSearchAction();
        });
        // IME action（软键盘"搜索"键）也触发搜索
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            Log.d(TAG, "[UI/Search] IME action on searchInput isFilterMode=" + isFilterMode);
            dispatchSearchAction();
            return true;
        });
    }

    /**
     * 搜索按钮统一入口：按当前模式（筛选 / 自然语言）派发到 doFilter 或 doSearch。
     * - isFilterMode=true 且 selectedTags 非空 → POST /api/v1/photos/filter
     * - isFilterMode=false → POST /api/v1/photos/search（带 query）
     * - isFilterMode=true 但 selectedTags 空 → 走 doFilter 的快路径（清空结果区，不发请求）
     */
    private void dispatchSearchAction() {
        if (isFilterMode) {
            Log.d(TAG, "[UI/Search] dispatch → doFilter (filter mode)");
            doFilter();
        } else {
            Log.d(TAG, "[UI/Search] dispatch → doSearch (text mode)");
            doSearch();
        }
    }

    private void doSearch() {
        String q = searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
        Log.d(TAG, "[UI/Search] doSearch start q=\"" + q + "\"");
        if (q.isEmpty()) {
            Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        lastQuery = q;
        io.execute(() -> {
            try {
                // 搜索结果请求大一点（用于判断 total 是否 > 8 来决定是否显示"查看全部"）
                PagedSearch r = ApiService.searchPhotos(q, 1, 12);
                final PagedSearch finalR = r;
                main.post(() -> {
                    try {
                        renderPagedResult(finalR, "doSearch");
                    } catch (Throwable t) {
                        // 兜底：main.post 内部任何 RuntimeException 都不应该崩进程
                        Log.e(TAG, "[UI/Search] doSearch render EXC " + t.getClass().getName()
                                + " msg=" + t.getMessage(), t);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Search] doSearch EXC " + e.getMessage());
                main.post(() -> {
                    Toast.makeText(this,
                            "搜索失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    renderAiCardEmpty();
                    if (seeAllSearchBtn != null) seeAllSearchBtn.setVisibility(View.GONE);
                });
            } catch (Throwable t) {
                Log.e(TAG, "[UI/Search] doSearch THROW " + t.getClass().getName()
                        + " msg=" + t.getMessage(), t);
            }
        });
    }

    /**
     * 精准标签筛选：把 selectedTags 里的 categoryId 提交给 POST /api/v1/photos/filter。
     * 渲染复用 doSearch 的 PagedSearch 逻辑。
     */
    private void doFilter() {
        if (selectedTags.isEmpty()) {
            // 没选任何 chip：清空结果区，不打接口
            Log.d(TAG, "[UI/Search] doFilter skip: selectedTags empty");
            renderPagedResult(new PagedSearch(new ArrayList<>(), 0, 1, 12), "doFilter");
            return;
        }
        // 1) 按 category 分桶：后端 /filter 是跨类型 AND，每类最多 1 个 category_id。
        //    同一类选了多个 chip 时，取最后一个被选中的（后选覆盖先选）。
        //    category 在 SmallTag 里是 "scene" / "mood" / "tag"，但后端 schema 用
        //    sceneId / emotionId / tagId，所以 "mood" → emotionId。
        Long sceneId = null, emotionId = null, tagId = null;
        for (SmallTag st : selectedTags.values()) {
            switch (st.category) {
                case "scene": sceneId   = st.categoryId; break;
                case "mood":  emotionId = st.categoryId; break;
                case "tag":   tagId     = st.categoryId; break;
                default:
                    Log.w(TAG, "[UI/Search] doFilter 忽略未知 category=" + st.category);
            }
        }
        Log.d(TAG, "[UI/Search] doFilter start sceneId=" + sceneId
                + " emotionId=" + emotionId + " tagId=" + tagId);
        // 复制成 final，lambda 才能捕获
        final Long fSceneId = sceneId;
        final Long fEmotionId = emotionId;
        final Long fTagId = tagId;
        io.execute(() -> {
            try {
                PagedSearch r = ApiService.filterPhotos(fSceneId, fEmotionId, fTagId, 1, 12);
                final PagedSearch finalR = r;
                main.post(() -> {
                    try {
                        renderPagedResult(finalR, "doFilter");
                    } catch (Throwable t) {
                        Log.e(TAG, "[UI/Search] doFilter render EXC " + t.getClass().getName()
                                + " msg=" + t.getMessage(), t);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Search] doFilter EXC " + e.getMessage());
                main.post(() -> {
                    Toast.makeText(this,
                            "筛选失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    renderAiCardEmpty();
                    if (seeAllSearchBtn != null) seeAllSearchBtn.setVisibility(View.GONE);
                });
            } catch (Throwable t) {
                Log.e(TAG, "[UI/Search] doFilter THROW " + t.getClass().getName()
                        + " msg=" + t.getMessage(), t);
            }
        });
    }

    /**
     * 渲染 PagedSearch 结果到 photoGrid + seeAllSearchBtn + AI 解析卡。
     * doSearch / doFilter 共用入口。
     */
    private void renderPagedResult(PagedSearch finalR, String fromTag) {
        if (finalR.list != null) {
            List<SearchResult> top8 = finalR.list.size() > 8
                    ? new ArrayList<>(finalR.list.subList(0, 8))
                    : finalR.list;
            photoGrid.setAdapter(new PhotoAdapter(this, top8));
            resultCountA.setText(String.valueOf(finalR.total));
            if (!top8.isEmpty()) {
                SearchResult first = top8.get(0);
                Log.d(TAG, "[UI/Search] " + fromTag + " first result photoId=" + first.photoId
                        + " thumbnailUrl=" + first.thumbnailUrl
                        + " matchedTags=" + first.matchedTags);
            }
            Log.d(TAG, "[UI/Search] " + fromTag + " OK total=" + finalR.total
                    + " shown=" + top8.size());
            if (seeAllSearchBtn != null) {
                if (finalR.total > 8) {
                    seeAllSearchBtn.setVisibility(View.VISIBLE);
                    // 筛选态的"查看全部"要带上 query 让 PhotoListActivity 复现筛选
                    if (fromTag.equals("doFilter")) {
                        seeAllSearchBtn.setText("查看全部 " + finalR.total + " 张 →");
                    } else {
                        seeAllSearchBtn.setText("查看全部 " + finalR.total + " 张 →");
                    }
                } else {
                    seeAllSearchBtn.setVisibility(View.GONE);
                }
            }
            if (!finalR.list.isEmpty()) {
                loadTopResultAi(finalR.list.get(0).photoId);
            } else {
                renderAiCardEmpty();
            }
        } else {
            Log.w(TAG, "[UI/Search] " + fromTag + " response list=null");
            renderAiCardEmpty();
            if (seeAllSearchBtn != null) seeAllSearchBtn.setVisibility(View.GONE);
        }
    }

    /**
     * 拉取搜索命中第一条的详情，绑定 AI 解析卡（场景 / 情绪 / 标签）。
     * 接口：GET /api/v1/photos/{photoId} → aiAnalysis.{description, scene.name, emotion.name, tags[]}
     */
    private void loadTopResultAi(long photoId) {
        Log.d(TAG, "[UI/Search] loadTopResultAi start photoId=" + photoId);
        io.execute(() -> {
            try {
                PhotoDetail detail = ApiService.getPhotoDetail(photoId);
                final PhotoDetail fd = detail;
                main.post(() -> {
                    try {
                        Log.d(TAG, "[UI/Search] loadTopResultAi OK photoId=" + photoId
                                + " hasAI=" + (fd != null && fd.aiAnalysis != null));
                        renderAiCardFromDetail(fd);
                    } catch (Throwable t) {
                        // main.post 内部抛出的 RuntimeException / NPE 不会被外层 catch (Exception) 捕获
                        // 这里 catch Throwable 兜底，避免 AI 渲染逻辑把整个进程搞崩
                        Log.e(TAG, "[UI/Search] loadTopResultAi render EXC " + t.getClass().getName()
                                + " msg=" + t.getMessage(), t);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Search] loadTopResultAi EXC " + e.getMessage());
                main.post(this::renderAiCardEmpty);
            } catch (Throwable t) {
                // 兜底：catch Error（如 OOM/LinkageError）也能进日志
                Log.e(TAG, "[UI/Search] loadTopResultAi THROW " + t.getClass().getName()
                        + " msg=" + t.getMessage(), t);
            }
        });
    }

    /** 把 aiAnalysis 字段绑到 3 个 value TextView（scene.name / emotion.name / 前 2 个 tags 拼接） */
    private void renderAiCardFromDetail(PhotoDetail detail) {
        if (aiSceneValue == null || aiMoodValue == null || aiTagValue == null) return;
        if (detail == null || detail.aiAnalysis == null) {
            renderAiCardEmpty();
            return;
        }
        com.ai_photo.net.Models.AiAnalysis ai = detail.aiAnalysis;
        // 场景
        String sceneName = (ai.scene != null) ? ai.scene.name : null;
        aiSceneValue.setText(notEmpty(sceneName) ? sceneName : "—");
        // 情绪
        String emoName = (ai.emotion != null) ? ai.emotion.name : null;
        aiMoodValue.setText(notEmpty(emoName) ? emoName : "—");
        // 标签：前 2 个，顿号分隔
        List<NamedScore> tags = ai.tags;
        if (tags == null || tags.isEmpty()) {
            aiTagValue.setText("—");
        } else {
            StringBuilder sb = new StringBuilder();
            int n = Math.min(2, tags.size());
            for (int i = 0; i < n; i++) {
                if (tags.get(i) == null || tags.get(i).name == null) continue;
                if (sb.length() > 0) sb.append("、");
                sb.append(tags.get(i).name);
            }
            aiTagValue.setText(sb.length() > 0 ? sb.toString() : "—");
        }
    }

    /** 空态：3 个 value 都显示 "—" */
    private void renderAiCardEmpty() {
        if (aiSceneValue != null) aiSceneValue.setText("—");
        if (aiMoodValue != null)  aiMoodValue.setText("—");
        if (aiTagValue != null)   aiTagValue.setText("—");
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // --------------------------------------------------------------------
    //  筛选面板
    // --------------------------------------------------------------------
    private void setupFilterPanel() {
        // 注意：time 行 chip 没有 categoryId，不能走 /filter；用户后续可通过 searchInput 自然语言查询
        // 时间维度（如「今天」「最近一周」），由 /search 后端处理。这里只渲染可走 /filter 的 3 行。
        // chipsTime 容器保留在 layout 里但永远 GONE——避免 findViewById null 引发历史已选
        // state 关闭时的 NPE。
        if (chipsTime != null) chipsTime.setVisibility(View.GONE);
        renderChipsForCategory(chipsScene, "scene");
        renderChipsForCategory(chipsMood, "mood");
        renderChipsForCategory(chipsTag, "tag");

        findViewById(R.id.clearAllBtn).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Search] click clearAllBtn count=" + selectedTags.size());
            clearAllSelections();
        });
        findViewById(R.id.clearHistoryBtn).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Search] click clearHistoryBtn");
            historyContainer.removeAllViews();
            Toast.makeText(this, "搜索历史已清空", Toast.LENGTH_SHORT).show();
        });
    }

    private void renderChipsForCategory(LinearLayout container, String category) {
        LayoutInflater inflater = LayoutInflater.from(this);
        container.removeAllViews();
        List<String> tags = allTags.get(category);
        if (tags == null) return;
        for (String tag : tags) {
            addChipView(container, tag, category, inflater);
        }
    }

    private void addChipView(LinearLayout container, String tag, String category, LayoutInflater inflater) {
        boolean selected = selectedTags.containsKey(tag);
        View item;
        if (selected) {
            item = inflater.inflate(R.layout.item_search_filter_chip_selected, container, false);
            ((TextView) item.findViewById(R.id.chipText)).setText(tag);
        } else {
            item = inflater.inflate(R.layout.item_search_filter_chip, container, false);
            ((TextView) item.findViewById(R.id.chipText)).setText(tag);
        }
        item.setOnClickListener(v -> {
            Log.d(TAG, "[UI/Search] click filterChip category=" + category + " tag=" + tag);
            toggleTagSelection(tag, category, container, inflater);
        });
        container.addView(item);
    }

    private void toggleTagSelection(String tag, String category, LinearLayout container, LayoutInflater inflater) {
        boolean wasSelected = selectedTags.containsKey(tag);
        if (wasSelected) {
            selectedTags.remove(tag);
        } else {
            // 记录 (category, categoryId) 供 doFilter 用
            Map<String, Long> idMap = tagIdByCategory.get(category);
            if (idMap == null) {
                Log.w(TAG, "[UI/Search] toggleTag skip: no categoryId map for category="
                        + category + " tag=" + tag
                        + " (time 行 chip 没有对应 categoryId，跳过 /filter)");
                return;
            }
            long cid = idMap.get(tag) != null ? idMap.get(tag) : 0L;
            if (cid == 0L) {
                Log.w(TAG, "[UI/Search] toggleTag skip: no categoryId for tag=" + tag
                        + " (同名 chip 取不到 id)");
                return;
            }
            selectedTags.put(tag, new SmallTag(category, cid));
        }
        Log.d(TAG, "[UI/Search] toggleTag tag=" + tag + " wasSelected=" + wasSelected
                + " → selectedCount=" + selectedTags.size());
        renderChipsForCategory(container, category);
        renderSelectedTagsBar();
        // 不再即时触发 doFilter——等用户点搜索按钮统一发送
    }

    private void renderSelectedTagsBar() {
        LayoutInflater inflater = LayoutInflater.from(this);
        selectedTagsContainer.removeAllViews();
        if (selectedTags.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂未选择");
            empty.setTextColor(0xFFB5BCC8);
            empty.setTextSize(11f);
            selectedTagsContainer.addView(empty);
            return;
        }
        for (String tag : selectedTags.keySet()) {
            View item = inflater.inflate(R.layout.item_search_selected_tag, selectedTagsContainer, false);
            ((TextView) item.findViewById(R.id.selectedTagText)).setText(tag);
            final SmallTag st = selectedTags.get(tag);
            item.findViewById(R.id.selectedTagClose).setOnClickListener(v -> {
                Log.d(TAG, "[UI/Search] close selectedTag tag=" + tag);
                selectedTags.remove(tag);
                LinearLayout container = pickContainerByCategory(st.category);
                renderChipsForCategory(container, st.category);
                renderSelectedTagsBar();
                // 不再即时触发 doFilter
            });
            selectedTagsContainer.addView(item);
        }
    }

    private LinearLayout pickContainerByCategory(String category) {
        switch (category) {
            case "scene": return chipsScene;
            case "mood":  return chipsMood;
            case "time":  return chipsTime;
            case "tag":   return chipsTag;
            default:      return chipsScene;
        }
    }

    private void clearAllSelections() {
        if (selectedTags.isEmpty()) return;
        Set<String> categories = new HashSet<>();
        for (SmallTag st : selectedTags.values()) categories.add(st.category);
        selectedTags.clear();
        for (String cat : categories) {
            renderChipsForCategory(pickContainerByCategory(cat), cat);
        }
        renderSelectedTagsBar();
        // 不再即时触发 doFilter
    }

    private void setupFilterToggle() {
        filterToggleBtn.setOnClickListener(v -> {
            Log.d(TAG, "[UI/Search] click filterToggleBtn isFilterMode=" + isFilterMode);
            toggleFilterMode();
        });
    }

    private void toggleFilterMode() {
        isFilterMode = !isFilterMode;
        Log.d(TAG, "[UI/Search] toggleFilterMode → " + isFilterMode);
        panelStateA.setVisibility(isFilterMode ? View.GONE : View.VISIBLE);
        panelStateB.setVisibility(isFilterMode ? View.VISIBLE : View.GONE);
        resultTitleA.setVisibility(isFilterMode ? View.GONE : View.VISIBLE);
        resultTitleB.setVisibility(isFilterMode ? View.VISIBLE : View.GONE);
        updateResultCount();
    }

    private void updateResultCount() {
        if (isFilterMode) {
            int count = 36 + selectedTags.size() * 23;
            TextView bCount = (TextView) ((LinearLayout) resultTitleB).getChildAt(2);
            if (bCount != null) bCount.setText("共 " + count + " 张");
        } else {
            String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
            int count = query.isEmpty() ? 0 : Math.max(1, 80 - query.length() * 3);
            resultCountA.setText(String.valueOf(count));
        }
    }

    private void setupResultGrid() {
        photoGrid.setAdapter(new PhotoAdapter(this, new ArrayList<>()));
    }

    // --------------------------------------------------------------------
    //  从 /categories 拉 3 类标签，作为 chip 数据源
    // --------------------------------------------------------------------
    private void loadCategoryChips() {
        Log.d(TAG, "[UI/Search] loadCategoryChips start");
        io.execute(() -> {
            try {
                CategoryList scene = ApiService.getCategories("scene");
                CategoryList emotion = ApiService.getCategories("emotion");
                CategoryList tag = ApiService.getCategories("tag");
                Map<String, Long> sceneMap = pluckNameToIdMap(scene);
                Map<String, Long> emotionMap = pluckNameToIdMap(emotion);
                Map<String, Long> tagMap = pluckNameToIdMap(tag);

                List<String> sceneNames = new ArrayList<>(sceneMap.keySet());
                List<String> emotionNames = new ArrayList<>(emotionMap.keySet());
                List<String> tagNames = new ArrayList<>(tagMap.keySet());

                // 时间维度：从真实照片的 createdAt 抽取"今天 / 昨天 / 具体日期"
                List<String> timeNames = buildTimeChipsFromPhotos(ApiService.listRecentPhotos(50));

                main.post(() -> {
                    allTags.put("scene", sceneNames);
                    allTags.put("mood", emotionNames);
                    allTags.put("tag", tagNames);
                    allTags.put("time", timeNames);
                    tagIdByCategory.put("scene", sceneMap);
                    tagIdByCategory.put("mood", emotionMap);
                    tagIdByCategory.put("tag", tagMap);
                    Log.d(TAG, "[UI/Search] loadCategoryChips OK scene=" + sceneNames.size()
                            + " mood=" + emotionNames.size()
                            + " tag=" + tagNames.size()
                            + " time=" + timeNames.size());
                    renderChipsForCategory(chipsScene, "scene");
                    renderChipsForCategory(chipsMood, "mood");
                    renderChipsForCategory(chipsTag, "tag");
                    // time 行不再渲染（没有 categoryId，无法走 /filter）
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Search] loadCategoryChips EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "分类加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** 把 CategorySummary 列表转成 displayName → categoryId 的 map（顺手剥 emoji）。 */
    private static Map<String, Long> pluckNameToIdMap(@Nullable CategoryList list) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (list == null || list.list == null) return out;
        for (CategorySummary c : list.list) {
            String raw = c.categoryName == null ? "" : c.categoryName;
            int i = 0;
            while (i < raw.length() && !isAlnumOrCjk(raw.charAt(i))) i++;
            String display = i > 0 ? raw.substring(i).trim() : raw;
            if (display.isEmpty()) continue;
            // 同名 chip 只保留一个（取第一个 categoryId）
            if (!out.containsKey(display)) out.put(display, c.categoryId);
        }
        return out;
    }

    private static List<String> pluckNames(@Nullable CategoryList list) {
        List<String> out = new ArrayList<>();
        if (list == null || list.list == null) return out;
        for (CategorySummary c : list.list) {
            String n = c.categoryName == null ? "" : c.categoryName;
            // 去 emoji：取第一个汉字 / 字母之后的部分
            int i = 0;
            while (i < n.length() && !isAlnumOrCjk(n.charAt(i))) i++;
            out.add(i > 0 ? n.substring(i).trim() : n);
        }
        return out;
    }

    /**
     * 时间维度 chip：从真实照片列表中按"本地日期"分组，按由近到远输出。
     *  - 当天 → "今天"
     *  - 昨天 → "昨天"
     *  - 更早 → "MM-dd" 或 "yyyy-MM-dd"
     */
    private static List<String> buildTimeChipsFromPhotos(
            @Nullable List<com.ai_photo.net.Models.PhotoSummary> photos) {
        List<String> out = new ArrayList<>();
        if (photos == null || photos.isEmpty()) return out;

        java.util.Calendar today = java.util.Calendar.getInstance();
        today.set(java.util.Calendar.HOUR_OF_DAY, 0);
        today.set(java.util.Calendar.MINUTE, 0);
        today.set(java.util.Calendar.SECOND, 0);
        today.set(java.util.Calendar.MILLISECOND, 0);
        long todayMs = today.getTimeInMillis();

        java.text.SimpleDateFormat shortFmt = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault());
        java.text.SimpleDateFormat fullFmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());

        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (com.ai_photo.net.Models.PhotoSummary p : photos) {
            String iso = p.createdAt;
            if (iso == null || iso.isEmpty()) continue;
            Long ts = parseIso(iso);
            if (ts == null) continue;

            long deltaDay = (todayMs - ts) / 86_400_000L;
            String label;
            if (deltaDay <= 0) {
                label = "今天";
            } else if (deltaDay == 1) {
                label = "昨天";
            } else if (deltaDay < 365) {
                label = shortFmt.format(new java.util.Date(ts));
            } else {
                label = fullFmt.format(new java.util.Date(ts));
            }
            uniq.add(label);
            if (uniq.size() >= 12) break; // 最多 12 个时间 chip
        }
        out.addAll(uniq);
        return out;
    }

    /** 解析 "2026-06-17T10:00:00Z" / "2026-06-17T10:00:00" → epoch ms（用本地时区显示） */
    private static @Nullable Long parseIso(String iso) {
        try {
            // 1) 带时区的标准 ISO（如 Z）
            java.text.SimpleDateFormat f1 = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            f1.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f1.parse(iso).getTime();
        } catch (Exception ignored) { }
        try {
            java.text.SimpleDateFormat f2 = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            return f2.parse(iso).getTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAlnumOrCjk(char c) {
        if (Character.isLetterOrDigit(c)) return true;
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    // --------------------------------------------------------------------
    //  底部导航
    // --------------------------------------------------------------------
    private void setupBottomNav() {
        Map<Integer, Class<?>> navMap = new HashMap<>();
        navMap.put(R.id.navHome, MainActivity.class);
        navMap.put(R.id.navAI, com.ai_photo.AIAnalysisActivity.class);
        navMap.put(R.id.navMe, com.ai_photo.ProfileActivity.class);
        for (Map.Entry<Integer, Class<?>> e : navMap.entrySet()) {
            findViewById(e.getKey()).setOnClickListener(v -> {
                Log.d(TAG, "[UI/Search] click bottomNav→" + e.getValue().getSimpleName());
                startActivity(new Intent(this, e.getValue()));
                finish();
            });
        }
        // 当前页是"搜索"，相册入口 → 切到筛选模式（"用筛选代替翻找"）
        findViewById(R.id.navAlbum).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Search] click bottomNav→Album (toggle filter mode)");
            if (!isFilterMode) toggleFilterMode();
        });
    }

    // --------------------------------------------------------------------
    //  搜索结果网格适配器
    //  点击单项 → 跳到照片详情
    // --------------------------------------------------------------------
    private static class PhotoAdapter extends BaseAdapter {
        private final android.content.Context context;
        private final List<SearchResult> data;

        PhotoAdapter(android.content.Context context, List<SearchResult> data) {
            this.context = context;
            this.data = data;
        }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = View.inflate(context, R.layout.item_search_photo, null);
            }
            final SearchResult p = data.get(position);
            // 缩略图：photoPlaceholder 已是 ImageView，调 ImageLoader 真实加载
            ImageView iv = convertView.findViewById(R.id.photoPlaceholder);
            if (iv != null) ImageLoader.load(iv, p.thumbnailUrl);
            // photoId 角标（左下角）
            TextView idTag = convertView.findViewById(R.id.photoIdTag);
            if (idTag != null) idTag.setText("#" + p.photoId);
            convertView.setClickable(true);
            convertView.setFocusable(true);
            convertView.setOnClickListener(v -> {
                android.util.Log.d("AiPhoto.UI/Search",
                        "[UI/Search] click result photoId=" + p.photoId
                                + " (跳转到 PhotoDetailActivity)");
                Intent it = new Intent(context, com.ai_photo.PhotoDetailActivity.class);
                it.putExtra(com.ai_photo.PhotoDetailActivity.EXTRA_PHOTO_ID, p.photoId);
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

    /**
     * 已选标签的最小元数据：category 名（"scene"/"mood"/"tag"） + category_id。
     * 内部类，避免污染 Models.java。
     */
    private static final class SmallTag {
        final String category;
        final long categoryId;
        SmallTag(String category, long categoryId) {
            this.category = category;
            this.categoryId = categoryId;
        }
    }
}