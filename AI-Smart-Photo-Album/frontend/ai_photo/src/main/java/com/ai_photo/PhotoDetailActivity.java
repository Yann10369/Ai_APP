package com.ai_photo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ai_photo.net.ApiService;
import com.ai_photo.net.Models.AiAnalysis;
import com.ai_photo.net.Models.NamedScore;
import com.ai_photo.net.Models.PhotoDetail;
import com.ai_photo.net.ImageLoader;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 照片详情页：
 * - 启动时通过 Intent extra photoId 拉取 /api/v1/photos/{photoId}
 * - AI 标签 / 描述 / 场景 / 情绪等均由接口数据填充
 */
public class PhotoDetailActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/PhotoDetail";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public static final String EXTRA_PHOTO_ID = "photoId";

    private long currentPhotoId;
    @Nullable private PhotoDetail currentDetail;
    /** 胶卷用的 recent 列表缓存：避免每次切页重新拉 /photos/recent */
    @Nullable private List<com.ai_photo.net.Models.PhotoSummary> filmstripPhotos;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_photo_detail);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.detailScroll), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), v.getPaddingTop(), v.getPaddingEnd(), systemBars.bottom);
            return insets;
        });

        currentPhotoId = getIntent().getLongExtra(EXTRA_PHOTO_ID, 1);

        setupTopBar();
        setupBottomNav();
        setupActionBar();
        setupSecondaryActions();

        loadDetail();
    }

    // ============================================================
    //  /api/v1/photos/{photoId}
    // ============================================================
    private void loadDetail() {
        Log.d(TAG, "[UI/PhotoDetail] loadDetail start photoId=" + currentPhotoId);
        io.execute(() -> {
            try {
                PhotoDetail d = ApiService.getPhotoDetail(currentPhotoId);
                final PhotoDetail fd = d;
                main.post(() -> {
                    Log.d(TAG, "[UI/PhotoDetail] loadDetail OK photoId=" + fd.photoId
                            + " favorite=" + fd.isFavorite);
                    bindDetail(fd);
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/PhotoDetail] loadDetail EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "照片详情加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void bindDetail(PhotoDetail d) {
        currentDetail = d;
        // 收藏初始态
        favorited = d.isFavorite;
        updateFavoriteIcon();

        // 主图：用 PhotoDetail.originalUrl 加载（之前是 <View> 占位，现在改成 ImageView）
        ImageView mainImage = findViewById(R.id.mainImage);
        if (mainImage != null) ImageLoader.load(mainImage, d.originalUrl);

        // 缩略图胶卷：拉同一场景下的其他照片填满
        loadFilmstrip(d);

        // 1/N 角标（默认 1/1，胶卷渲染后再覆盖）
        TextView badge = findViewById(R.id.imageIndexBadge);
        if (badge != null) badge.setText("1/1");

        // AI 标签 / 场景 / 情绪
        AiAnalysis ai = d.aiAnalysis;
        List<NamedScore> tags = ai == null ? new ArrayList<>() : ai.tags;

        // AI 标签胶囊行
        LinearLayout tagsContainer = findViewById(R.id.aiTagsContainer);
        if (tagsContainer != null) {
            tagsContainer.removeAllViews();
            if (tags != null) {
                for (NamedScore ns : tags) {
                    TextView tv = new TextView(this);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, dp(22));
                    lp.setMarginEnd(dp(6));
                    tv.setLayoutParams(lp);
                    tv.setBackgroundResource(R.drawable.detail_bg_tag_pill);
                    tv.setGravity(Gravity.CENTER);
                    tv.setPadding(dp(9), 0, dp(9), 0);
                    tv.setText(ns.name == null ? "" : ns.name);
                    tv.setTextColor(0xFF4A90E2);
                    tv.setTextSize(11);
                    tagsContainer.addView(tv);
                }
            }
        }

        // 描述 / 场景 / 情绪：复用 detail 页面里能识别的 TextView，没有就跳过
        TextView tvDesc = findViewById(R.id.aiDescText);
        if (tvDesc != null && ai != null) tvDesc.setText(safe(ai.description, "（无 AI 描述）"));

        TextView tvScene = findViewById(R.id.sceneText);
        if (tvScene != null) tvScene.setText(ai != null && ai.scene != null && ai.scene.name != null
                ? stripEmoji(ai.scene.name) : "—");

        TextView tvEmotion = findViewById(R.id.emotionText);
        if (tvEmotion != null) tvEmotion.setText(ai != null && ai.emotion != null && ai.emotion.name != null
                ? stripEmoji(ai.emotion.name) : "—");
    }

    /**
     * 胶卷：调 /photos/recent 拉一次，缓存到 filmstripPhotos 供 in-page 切换使用。
     * 点击缩略图不再 startActivity + finish，而是直接调 loadDetailFor(pid) 切到对应照片，
     * 配合 bindDetail 重渲染所有 AI 字段 + 角标 + 相似照片。
     */
    private void loadFilmstrip(PhotoDetail current) {
        Log.d(TAG, "[UI/PhotoDetail] loadFilmstrip start currentPhotoId=" + current.photoId);
        io.execute(() -> {
            final List<com.ai_photo.net.Models.PhotoSummary> recent;
            try {
                recent = ApiService.listRecentPhotos(12);
            } catch (Exception e) {
                Log.e(TAG, "[UI/PhotoDetail] loadFilmstrip EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "胶卷加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }
            main.post(() -> {
                Log.d(TAG, "[UI/PhotoDetail] loadFilmstrip OK count=" + recent.size());
                filmstripPhotos = recent;
                renderFilmstrip(current.photoId);
            });
        });
    }

    /** 渲染胶卷（从缓存的 filmstripPhotos 取数），把 currentPhotoId 对应项高亮 */
    private void renderFilmstrip(long activePhotoId) {
        LinearLayout filmstrip = findViewById(R.id.thumbnailContainer);
        if (filmstrip == null) return;
        List<com.ai_photo.net.Models.PhotoSummary> recent =
                filmstripPhotos == null ? java.util.Collections.emptyList() : filmstripPhotos;
        filmstrip.removeAllViews();
        if (recent.isEmpty()) {
            TextView badge = findViewById(R.id.imageIndexBadge);
            if (badge != null) badge.setText("1/1");
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        int currentIndex = 0;
        for (int i = 0; i < recent.size(); i++) {
            if (recent.get(i).photoId == activePhotoId) {
                currentIndex = i;
                break;
            }
        }
        for (int i = 0; i < recent.size(); i++) {
            final int idx = i;
            final long pid = recent.get(i).photoId;
            View item = inflater.inflate(R.layout.item_detail_thumbnail, filmstrip, false);
            View root = item.findViewById(R.id.thumbRoot);
            root.setSelected(i == currentIndex);
            // 缩略图：item_detail_thumbnail.xml 已把 thumbImage 改为 ImageView，这里真实加载
            ImageView thumb = (ImageView) item.findViewById(R.id.thumbImage);
            com.ai_photo.net.Models.PhotoSummary p = recent.get(i);
            if (thumb != null) ImageLoader.load(thumb, p.thumbnailUrl);
            root.setOnClickListener(v -> {
                if (pid == currentPhotoId) return; // 已经在这一张
                Log.d(TAG, "[UI/PhotoDetail] click filmstrip thumb idx=" + idx
                        + " photoId=" + pid);
                loadDetailFor(pid);
            });
            filmstrip.addView(item);
        }
        TextView badge = findViewById(R.id.imageIndexBadge);
        if (badge != null) badge.setText(
                (currentIndex + 1) + "/" + recent.size());
    }

    /** 给 filmstrip 切页用：在不重启 Activity 的前提下换 photoId 重新拉详情 */
    private void loadDetailFor(long pid) {
        currentPhotoId = pid;
        currentDetail = null;
        // 立刻把角标和胶卷高亮更新（不必等服务端）
        renderFilmstrip(pid);
        // 拉对应 photoId 的详情
        io.execute(() -> {
            try {
                PhotoDetail d = ApiService.getPhotoDetail(pid);
                final PhotoDetail fd = d;
                main.post(() -> {
                    Log.d(TAG, "[UI/PhotoDetail] loadDetailFor OK photoId=" + fd.photoId);
                    bindDetail(fd);
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/PhotoDetail] loadDetailFor EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "切换照片失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static String safe(@Nullable String s, String fallback) {
        return s == null || s.isEmpty() ? fallback : s;
    }

    private static String stripEmoji(@Nullable String s) {
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
    //  顶部 / 底部导航
    // ============================================================
    private void setupTopBar() {
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoDetail] click btnBack");
            finish();
        });
        findViewById(R.id.headerMoreBtn).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoDetail] click headerMoreBtn");
            androidx.appcompat.widget.PopupMenu menu =
                    new androidx.appcompat.widget.PopupMenu(this, v);
            menu.getMenu().add(0, 1, 0, "重新分析");
            menu.getMenu().add(0, 2, 1, "修改标签");
            menu.getMenu().add(0, 3, 2, "删除照片");
            menu.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                Log.d(TAG, "[UI/PhotoDetail] moreMenu select id=" + id);
                if (id == 1) doReanalyze();
                else if (id == 2) doEditTag();
                else if (id == 3) confirmDelete();
                return true;
            });
            menu.show();
        });
    }

    private void confirmDelete() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除照片")
                .setMessage("确认删除这张照片？此操作不可撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    Log.d(TAG, "[UI/PhotoDetail] delete dialog confirm");
                    doDelete();
                })
                .setNegativeButton("取消", (d, w) -> Log.d(TAG, "[UI/PhotoDetail] delete dialog cancel"))
                .show();
    }

    private void setupBottomNav() {
        findViewById(R.id.navHome).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoDetail] click bottomNav→Home");
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
    }

    // ============================================================
    //  操作按钮：收藏 / 删除 / 重新分析 / 改标签
    // ============================================================
    private void setupActionBar() {
        findViewById(R.id.actionFavorite).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoDetail] click actionFavorite favorited=" + favorited);
            doToggleFavorite();
        });
        findViewById(R.id.actionShare).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoDetail] click actionShare");
            doShare();
        });
        findViewById(R.id.actionDelete).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoDetail] click actionDelete");
            confirmDelete();
        });
    }

    /**
     * 收藏按钮：根据当前详情接口返回的 isFavorite 切换。
     * 先记住初始状态，每次点击翻转；切换失败时回滚。
     */
    private boolean favorited = false;
    private void doToggleFavorite() {
        boolean target = !favorited;
        Log.d(TAG, "[UI/PhotoDetail] doToggleFavorite target=" + target
                + " photoId=" + currentPhotoId);
        io.execute(() -> {
            try {
                if (target) ApiService.favorite(currentPhotoId);
                else        ApiService.unfavorite(currentPhotoId);
                favorited = target;
                main.post(() -> {
                    Log.d(TAG, "[UI/PhotoDetail] doToggleFavorite OK favorited=" + target);
                    updateFavoriteIcon();
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/PhotoDetail] doToggleFavorite EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "收藏操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void updateFavoriteIcon() {
        View fav = findViewById(R.id.actionFavorite);
        if (fav == null) return;
        // 仅有 ImageView 子节点可设置 imageResource；这里简单用 alpha 表示选中态
        if (fav instanceof android.widget.ImageView) {
            ((android.widget.ImageView) fav).setImageResource(
                    favorited ? R.drawable.app_ic_star_five : R.drawable.app_ic_star_four);
        } else {
            fav.setAlpha(favorited ? 1f : 0.5f);
        }
    }

    /**
     * 分享：拼一段文案 + 调系统分享面板
     */
    private void doShare() {
        String text = "来自 AI 智能相册的照片 #" + currentPhotoId;
        Log.d(TAG, "[UI/PhotoDetail] doShare text=\"" + text + "\"");
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, "分享照片"));
    }

    private void setupSecondaryActions() {
        findViewById(R.id.btnReanalyze).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoDetail] click btnReanalyze");
            doReanalyze();
        });
        findViewById(R.id.btnEditTag).setOnClickListener(v -> {
            Log.d(TAG, "[UI/PhotoDetail] click btnEditTag");
            doEditTag();
        });
    }

    private void doFavorite() {
        io.execute(() -> {
            try {
                ApiService.favorite(currentPhotoId);
                main.post(() -> Toast.makeText(this, "已收藏", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        "收藏失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void doDelete() {
        Log.d(TAG, "[UI/PhotoDetail] doDelete start photoId=" + currentPhotoId);
        io.execute(() -> {
            try {
                ApiService.deletePhoto(currentPhotoId);
                main.post(() -> {
                    Log.d(TAG, "[UI/PhotoDetail] doDelete OK");
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/PhotoDetail] doDelete EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void doReanalyze() {
        Log.d(TAG, "[UI/PhotoDetail] doReanalyze start photoId=" + currentPhotoId);
        io.execute(() -> {
            try {
                List<Long> ids = new ArrayList<>();
                ids.add(currentPhotoId);
                ApiService.reanalyze(ids);
                main.post(() -> Toast.makeText(this, "已加入重新分析队列", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "[UI/PhotoDetail] doReanalyze EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "重新分析失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * 修改标签/描述：弹两个输入框（标签逗号分隔、描述多行），回填当前 detail 的旧值。
     * 不再用硬编码字符串覆盖真实数据。
     */
    private void doEditTag() {
        Log.d(TAG, "[UI/PhotoDetail] doEditTag start photoId=" + currentPhotoId);
        PhotoDetail d = currentDetail;

        // 旧值：tag 列表 → "tag1, tag2"；描述 → description
        String oldTags = "";
        String oldDesc = "";
        if (d != null && d.aiAnalysis != null && d.aiAnalysis.tags != null) {
            List<String> names = new ArrayList<>();
            for (NamedScore ns : d.aiAnalysis.tags) {
                if (ns != null && ns.name != null) names.add(ns.name);
            }
            oldTags = android.text.TextUtils.join(", ", names);
        }
        if (d != null && d.aiAnalysis != null) {
            oldDesc = d.aiAnalysis.description == null ? "" : d.aiAnalysis.description;
        }

        // 构建 dialog 内容：两个 EditText 垂直堆叠
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        container.setPadding(pad, dp(8), pad, 0);

        TextView labelTags = new TextView(this);
        labelTags.setText("标签（逗号分隔）");
        labelTags.setTextSize(12);
        labelTags.setTextColor(0xFF1B1F2A);
        container.addView(labelTags);

        EditText etTags = new EditText(this);
        etTags.setText(oldTags);
        etTags.setHint("例如：海滩, 旅行, 朋友");
        etTags.setSingleLine(true);
        etTags.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(etTags);

        TextView labelDesc = new TextView(this);
        labelDesc.setText("描述");
        labelDesc.setTextSize(12);
        labelDesc.setTextColor(0xFF1B1F2A);
        LinearLayout.LayoutParams lpDescLabel = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpDescLabel.topMargin = dp(12);
        labelDesc.setLayoutParams(lpDescLabel);
        container.addView(labelDesc);

        EditText etDesc = new EditText(this);
        etDesc.setText(oldDesc);
        etDesc.setHint("一句话描述这张照片");
        etDesc.setMinLines(2);
        etDesc.setMaxLines(4);
        etDesc.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etDesc.setGravity(Gravity.TOP);
        container.addView(etDesc);

        new AlertDialog.Builder(this)
                .setTitle("修改标签 / 描述")
                .setView(container)
                .setNegativeButton("取消", (dd, w) ->
                        Log.d(TAG, "[UI/PhotoDetail] doEditTag dialog cancel"))
                .setPositiveButton("保存", (dd, w) -> {
                    String tagsRaw = etTags.getText().toString().trim();
                    String descRaw = etDesc.getText().toString().trim();
                    // 空描述传 null 给后端，标签按逗号切
                    List<String> tags = parseTagsInput(tagsRaw);
                    String desc = descRaw.isEmpty() ? null : descRaw;
                    Log.d(TAG, "[UI/PhotoDetail] doEditTag submit tags=" + tags
                            + " desc.len=" + (desc == null ? 0 : desc.length()));
                    submitEdit(currentPhotoId, tags, desc);
                })
                .show();
    }

    /** "海滩, 旅行, 朋友" → ["海滩","旅行","朋友"]；空串返回空列表 */
    private static List<String> parseTagsInput(String raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void submitEdit(long photoId, @Nullable List<String> tags, @Nullable String desc) {
        io.execute(() -> {
            try {
                // PATCH /photos/{id}：tags + description 都允许为 null（后端按字段更新）
                ApiService.updatePhoto(photoId, tags, desc);
                main.post(() -> {
                    Log.d(TAG, "[UI/PhotoDetail] doEditTag submit OK photoId=" + photoId);
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    // 刷新当前 detail，让 AI 标签 / 描述 / 场景 / 情绪 重新展示
                    loadDetail();
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/PhotoDetail] doEditTag submit EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}