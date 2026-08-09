package com.ai_photo;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ai_photo.ai.HistoryAdapter;
import com.ai_photo.ai.HistoryItem;
import com.ai_photo.net.ApiService;
import com.ai_photo.net.Models.AiAnalysis;
import com.ai_photo.net.Models.AiStatus;
import com.ai_photo.net.Models.NamedScore;
import com.ai_photo.net.Models.PhotoDetail;
import com.ai_photo.net.Models.PhotoSummary;
import com.ai_photo.net.Models.UploadResult;
import com.ai_photo.net.Models.UploadedPhoto;
import com.ai_photo.net.ImageLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;

/**
 * AI 分析页面：
 * - 上传链路：选图 → 状态计数 → 真实进度 → 4 项指标 → 失败重试
 * - AI 状态 / 任务列表 / 历史：直接走 API
 */
public class AIAnalysisActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/AI";

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    // 上传状态
    private int pickedCount = 0;
    private int uploadedCount = 0;
    private int failedCount = 0;
    private int totalToUpload = 0;     // 用户选了 N 张，本次目标上传张数
    private int successCountTotal = 0; // 累计成功张数
    private boolean uploading = false;
    private final List<Uri> pickedUris = new ArrayList<>();
    /** 最近一次成功上传返回的 photoId 列表；btnRedo 仅重分析这一批 */
    private final List<Long> lastBatchPhotoIds = new ArrayList<>();
    /** 最近分析结果预览卡当前显示的 photoId（btnViewFull 用它跳详情） */
    private long previewedPhotoId = 0;

    // ActivityResultLauncher：系统多图选择
    private ActivityResultLauncher<String> pickImagesLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_ai_analysis);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.aiScroll), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), v.getPaddingTop(), v.getPaddingEnd(), systemBars.bottom);
            return insets;
        });

        // 注册多图选择 launcher（必须在 START 前）
        pickImagesLauncher = registerForActivityResult(
                new ActivityResultContracts.GetMultipleContents(),
                uris -> onImagesPicked(uris));

        setupBottomNav();
        setupCardClicks();
        setupHeaderActions();

        loadAiStatus();
        loadRecentAsHistory();
        loadRecentPreview();

        // 初始空态
        updateSelectedCount();
        updateBatchMetrics();
        applyUploadButtonState();
    }

    // ============================================================
    //  AI 状态：从 /ai/status 拉取，刷新进度条 + 状态文本
    // ============================================================
    private void loadAiStatus() {
        Log.d(TAG, "[UI/AI] loadAiStatus start");
        io.execute(() -> {
            try {
                AiStatus s = ApiService.getAiStatus();
                int percent = (int) Math.round(s.progress * 100);
                final AiStatus fs = s;
                main.post(() -> {
                    Log.d(TAG, "[UI/AI] loadAiStatus OK percent=" + percent
                            + " done=" + fs.done + " total=" + fs.total);
                    animateTo(percent, "已完成 " + fs.done + " / " + fs.total);
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/AI] loadAiStatus EXC " + e.getMessage());
                main.post(() -> {
                    ProgressBar pb = findViewById(R.id.aiProgressBar);
                    TextView pt = findViewById(R.id.aiProgressPercent);
                    if (pb != null) pb.setProgress(0);
                    if (pt != null) pt.setText("--");
                    Toast.makeText(this,
                            "AI 状态加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void animateTo(int percent, String statusText) {
        // AI 总分析进度：写到 aiProgressBar / aiProgressPercent
        ProgressBar pb = findViewById(R.id.aiProgressBar);
        TextView pt = findViewById(R.id.aiProgressPercent);
        if (pb == null || pt == null) return;

        ValueAnimator animator = ValueAnimator.ofInt(pb.getProgress(), percent);
        animator.setDuration(900L);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> {
            int v = (int) a.getAnimatedValue();
            pb.setProgress(v);
            pt.setText(v + "%");
        });
        animator.start();

        // statusText 仅作日志（不展示，避免与百分数语义混淆）
        Log.d(TAG, "[UI/AI] animateTo percent=" + percent + " status=" + statusText);
    }

    // ============================================================
    //  历史列表：用 /photos/recent 的时间戳粗略构造
    // ============================================================
    private void loadRecentAsHistory() {
        Log.d(TAG, "[UI/AI] loadRecentAsHistory start");
        io.execute(() -> {
            final List<PhotoSummary> recents;
            try {
                recents = ApiService.listRecentPhotos(20);
            } catch (Exception e) {
                Log.e(TAG, "[UI/AI] loadRecentAsHistory EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "历史加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }
            main.post(() -> {
                List<HistoryItem> items = new ArrayList<>();
                if (!recents.isEmpty()) {
                    int total = recents.size();
                    items.add(new HistoryItem("本次会话 · " + total + " 张",
                            total, 0, HistoryItem.Status.ONGOING, "进行中"));
                    int n = Math.min(2, total);
                    for (int i = 0; i < n; i++) {
                        PhotoSummary p = recents.get(i);
                        items.add(new HistoryItem(
                                "照片 #" + p.photoId + " · " + safe(p.createdAt),
                                1, 0, HistoryItem.Status.FINISHED, "已完成"));
                    }
                }
                RecyclerView rv = findViewById(R.id.historyList);
                if (rv != null) {
                    rv.setLayoutManager(new LinearLayoutManager(this));
                    rv.setAdapter(new HistoryAdapter(items));
                    Log.d(TAG, "[UI/AI] loadRecentAsHistory OK count=" + items.size());
                }
            });
        });
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ============================================================
    //  最近分析结果预览：GET /photos/recent?limit=1 → GET /photos/{id}
    //  绑定到 previewThumb / previewScene / previewMood / previewTagRow / previewDesc
    // ============================================================

    /**
     * 拉最近一张已分析的照片 + 详情，绑定到预览卡 UI。
     * 失败时回落到空态文案（不弹 Toast，避免冷启动时刷屏）。
     */
    private void loadRecentPreview() {
        Log.d(TAG, "[UI/AI] loadRecentPreview start");
        io.execute(() -> {
            PhotoSummary recent;
            try {
                List<PhotoSummary> list = ApiService.listRecentPhotos(1);
                recent = (list == null || list.isEmpty()) ? null : list.get(0);
            } catch (Exception e) {
                Log.e(TAG, "[UI/AI] loadRecentPreview recent EXC " + e.getMessage());
                main.post(this::renderEmptyPreview);
                return;
            }
            if (recent == null) {
                Log.w(TAG, "[UI/AI] loadRecentPreview: no recent photo");
                main.post(this::renderEmptyPreview);
                return;
            }
            // 先把缩略图即时显示（避免等详情时长时间空图）
            final long pid = recent.photoId;
            final String thumb = recent.thumbnailUrl;
            main.post(() -> {
                previewedPhotoId = pid;
                ImageView thumbView = findViewById(R.id.previewThumb);
                if (thumbView != null) ImageLoader.load(thumbView, thumb);
            });
            // 拉详情，绑 scene / emotion / tags / description
            try {
                PhotoDetail detail = ApiService.getPhotoDetail(pid);
                final PhotoDetail fd = detail;
                main.post(() -> renderPreviewFromDetail(fd));
            } catch (Exception e) {
                Log.e(TAG, "[UI/AI] loadRecentPreview detail EXC " + e.getMessage());
                // 缩略图已绑，scene/mood/desc 留空（让用户至少看到图）
            }
        });
    }

    /** 空态：清空 previewedPhotoId，desc 用占位文案，按钮禁用视觉靠 alpha */
    private void renderEmptyPreview() {
        previewedPhotoId = 0;
        TextView tvScene = findViewById(R.id.previewScene);
        TextView tvMood  = findViewById(R.id.previewMood);
        TextView tvDesc  = findViewById(R.id.previewDesc);
        LinearLayout tagRow = findViewById(R.id.previewTagRow);
        if (tvScene != null) tvScene.setText("—");
        if (tvMood  != null) tvMood.setText("—");
        if (tvDesc  != null) tvDesc.setText("还没有分析结果，上传一张照片试试");
        if (tagRow != null) {
            // 保留前缀 "标签："（第 0 个子节点）
            while (tagRow.getChildCount() > 1) tagRow.removeViewAt(1);
        }
    }

    /** 把 /photos/{id} 返回的详情绑到预览卡 */
    private void renderPreviewFromDetail(@Nullable PhotoDetail detail) {
        if (detail == null) {
            renderEmptyPreview();
            return;
        }
        previewedPhotoId = detail.photoId;
        // 主图（详情再覆盖一次最新 url）
        ImageView thumbView = findViewById(R.id.previewThumb);
        if (thumbView != null) ImageLoader.load(thumbView, detail.thumbnailUrl);

        AiAnalysis ai = detail.aiAnalysis;
        TextView tvScene = findViewById(R.id.previewScene);
        TextView tvMood  = findViewById(R.id.previewMood);
        TextView tvDesc  = findViewById(R.id.previewDesc);
        LinearLayout tagRow = findViewById(R.id.previewTagRow);

        if (tvScene != null) {
            tvScene.setText(ai != null && ai.scene != null && notEmpty(ai.scene.name)
                    ? ai.scene.name : "—");
        }
        if (tvMood != null) {
            tvMood.setText(ai != null && ai.emotion != null && notEmpty(ai.emotion.name)
                    ? ai.emotion.name : "—");
        }
        if (tvDesc != null) {
            String d = (ai == null) ? null : ai.description;
            tvDesc.setText(notEmpty(d) ? d : "（暂无 AI 描述）");
        }
        // 标签行：前 3 个 chip + "+N"
        if (tagRow != null) {
            while (tagRow.getChildCount() > 1) tagRow.removeViewAt(1);
            List<NamedScore> tags = (ai == null) ? null : ai.tags;
            int total = tags == null ? 0 : tags.size();
            int shown = Math.min(3, total);
            for (int i = 0; i < shown; i++) {
                String name = tags.get(i) == null ? null : tags.get(i).name;
                if (!notEmpty(name)) continue;
                tagRow.addView(buildTagChip(name));
            }
            if (total > 3) {
                TextView more = new TextView(this);
                more.setText("+" + (total - 3));
                more.setTextColor(0xFF7B8597);
                more.setTextSize(10);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(3);
                more.setLayoutParams(lp);
                tagRow.addView(more);
            }
        }
    }

    /** 程序化构造一个标签 chip（与布局里硬编码的 chip 样式一致） */
    private TextView buildTagChip(String name) {
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextColor(0xFF1B1F2A);
        tv.setTextSize(9);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.ai_bg_tag_pill_outline);
        tv.setSingleLine(true);
        int padH = dp(6);
        tv.setPadding(padH, 0, padH, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(18));
        lp.leftMargin = dp(3);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static boolean notEmpty(@Nullable String s) {
        return s != null && !s.isEmpty();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ============================================================
    //  底部导航
    // ============================================================
    private void setupBottomNav() {
        findViewById(R.id.navHome).setOnClickListener(v -> {
            Log.d(TAG, "[UI/AI] click bottomNav→Home");
            startActivity(new Intent(AIAnalysisActivity.this, MainActivity.class));
            finish();
        });
        findViewById(R.id.navAlbum).setOnClickListener(v -> {
            Log.d(TAG, "[UI/AI] click bottomNav→Album");
            startActivity(new Intent(AIAnalysisActivity.this, AlbumActivity.class));
            finish();
        });
        findViewById(R.id.navMe).setOnClickListener(v -> {
            Log.d(TAG, "[UI/AI] click bottomNav→Me");
            startActivity(new Intent(AIAnalysisActivity.this, ProfileActivity.class));
            finish();
        });
    }

    // ============================================================
    //  卡片按钮
    // ============================================================
    private void setupCardClicks() {
        View btnReselect = findViewById(R.id.btnReselect);
        if (btnReselect != null) btnReselect.setOnClickListener(v -> {
            Log.d(TAG, "[UI/AI] click btnReselect");
            doReselect();
        });

        View btnStart = findViewById(R.id.btnStartUpload);
        if (btnStart != null) btnStart.setOnClickListener(v -> {
            Log.d(TAG, "[UI/AI] click btnStartUpload pickedCount=" + pickedCount);
            doUpload();
        });

        View btnRetry = findViewById(R.id.btnBatchRetry);
        if (btnRetry != null) btnRetry.setOnClickListener(v -> {
            Log.d(TAG, "[UI/AI] click btnBatchRetry");
            doRetryFailed();
        });

        View btnView = findViewById(R.id.btnViewFull);
        if (btnView != null) btnView.setOnClickListener(v -> {
            Log.d(TAG, "[UI/AI] click btnViewFull photoId=" + previewedPhotoId);
            if (previewedPhotoId <= 0) {
                Toast.makeText(this, "还没有可查看的照片", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(AIAnalysisActivity.this, PhotoDetailActivity.class);
            i.putExtra(PhotoDetailActivity.EXTRA_PHOTO_ID, previewedPhotoId);
            startActivity(i);
        });

        View btnRedo = findViewById(R.id.btnRedo);
        if (btnRedo != null) btnRedo.setOnClickListener(v -> {
            Log.d(TAG, "[UI/AI] click btnRedo (reanalyze all)");
            doReanalyzeAll();
        });
        // 注：原"确定保存"按钮 btnConfirmSave 已从布局移除
    }

    /**
     * 顶部右侧按钮：search → 跳搜索；more → 弹出菜单
     */
    private void setupHeaderActions() {
        View searchBtn = findViewById(R.id.headerSearchBtn);
        if (searchBtn != null) {
            searchBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/AI] click headerSearchBtn");
                startActivity(new Intent(this, com.ai_photo.search.SearchActivity.class));
            });
        }
        View moreBtn = findViewById(R.id.headerMoreBtn);
        if (moreBtn != null) {
            moreBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/AI] click headerMoreBtn");
                // 三点菜单规范：只显示"设置"
                androidx.appcompat.widget.PopupMenu menu =
                        new androidx.appcompat.widget.PopupMenu(this, moreBtn);
                menu.getMenu().add(0, 1, 0, "设置");
                menu.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    Log.d(TAG, "[UI/AI] moreMenu select id=" + id);
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
    //  上传流程
    // ============================================================

    /**
     * 选图：调系统多图选择器（GetContent）。
     * 该 Activity 不需要额外权限，权限由系统选择器进程内部处理。
     */
    private void doReselect() {
        if (uploading) {
            Toast.makeText(this, "正在上传中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            pickImagesLauncher.launch("image/*");
        } catch (Exception e) {
            Toast.makeText(this, "系统选择器不可用: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 选择回调：把选中的图片写入状态 + 更新计数。
     * 若用户没选任何图片（取消），保持之前的计数。
     */
    private void onImagesPicked(List<Uri> uris) {
        if (uris == null) {
            Log.d(TAG, "[UI/AI] onImagesPicked: null (cancel)");
            return;
        }
        Log.d(TAG, "[UI/AI] onImagesPicked: count=" + uris.size());
        pickedUris.clear();
        pickedUris.addAll(uris);
        pickedCount = uris.size();
        totalToUpload = pickedCount;
        uploadedCount = 0;
        failedCount = 0;
        updateSelectedCount();
        applyUploadButtonState();
        // 选了图后给个轻提示
        Toast.makeText(this, "已选择 " + pickedCount + " 张", Toast.LENGTH_SHORT).show();
    }

    /**
     * 把 "已选择 X 张" 里的数字 TextView 更新。
     * 数字 TextView 位于 uploadEntryCard 内的 status row（index 1）的第 2 个 TextView。
     */
    private void updateSelectedCount() {
        TextView tv = findSelectedCountText();
        if (tv != null) tv.setText(String.valueOf(pickedCount));
    }

    private @androidx.annotation.Nullable TextView findSelectedCountText() {
        View card = findViewById(R.id.uploadEntryCard);
        if (!(card instanceof android.widget.LinearLayout)) return null;
        android.widget.LinearLayout entry = (android.widget.LinearLayout) card;
        // 第一个子节点是左侧内容 LinearLayout (weight=1)
        if (entry.getChildCount() == 0) return null;
        View left = entry.getChildAt(0);
        if (!(left instanceof android.widget.LinearLayout)) return null;
        android.widget.LinearLayout leftCol = (android.widget.LinearLayout) left;
        // 第 2 个子节点是状态行（前缀 / 数字 / 后缀）
        if (leftCol.getChildCount() < 2) return null;
        View statusRow = leftCol.getChildAt(1);
        if (!(statusRow instanceof android.widget.LinearLayout)) return null;
        android.widget.LinearLayout row = (android.widget.LinearLayout) statusRow;
        if (row.getChildCount() < 2) return null;
        return (TextView) row.getChildAt(1);
    }

    /**
     * 真实 multipart 上传：
     *  1) 把 pickedUris 全部读成 JPEG 字节（在 IO 线程，子采样避免 OOM）
     *  2) 一次性 POST 到 /api/v1/photos/upload（multipart/form-data, name=files）
     *  3) 后端 Pillow 落盘 + 生成 256x256 略缩图
     *  4) 进度按 pickedCount 真实已处理 / 总数 推
     */
    private void doUpload() {
        if (uploading) {
            Log.d(TAG, "[UI/AI] doUpload blocked: uploading");
            return;
        }
        if (pickedCount == 0 || pickedUris.isEmpty()) {
            Log.d(TAG, "[UI/AI] doUpload blocked: no picked");
            Toast.makeText(this, "请先选择要上传的照片", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d(TAG, "[UI/AI] doUpload start pickedCount=" + pickedCount);
        uploading = true;
        applyUploadButtonState();

        totalToUpload = pickedUris.size();
        uploadedCount = 0;
        failedCount = 0;
        animateBatchProgress(0);

        // 复制 URI 列表（避免子线程期间被 onImagesPicked 清空）
        final List<Uri> toUpload = new ArrayList<>(pickedUris);

        io.execute(() -> {
            // 1) 子线程把所有 URI 转 JPEG 字节
            List<byte[]> jpegList = new ArrayList<>(toUpload.size());
            List<String> names = new ArrayList<>(toUpload.size());
            int readFail = 0;
            for (int i = 0; i < toUpload.size(); i++) {
                Uri u = toUpload.get(i);
                String name = "photo_" + (i + 1) + ".jpg";
                try {
                    byte[] data = uriToJpegBytes(u, 1920);
                    if (data != null && data.length > 0) {
                        jpegList.add(data);
                        names.add(name);
                    } else {
                        readFail++;
                    }
                } catch (Exception e) {
                    readFail++;
                }
            }

            // 2) 一次性 multipart POST
            UploadResult r = null;
            String errMsg = null;
            try {
                r = ApiService.uploadPhotos(jpegList, names);
            } catch (Exception e) {
                errMsg = e.getMessage();
            }

            final UploadResult finalR = r;
            final int finalReadFail = readFail;
            final String finalErrMsg = errMsg;
            main.post(() -> {
                uploading = false;
                applyUploadButtonState();
                animateBatchProgress(100);

                int ok = finalR == null ? 0 : finalR.successCount;
                int fail = (finalR == null ? 0 : finalR.failCount) + finalReadFail;
                uploadedCount = ok;
                failedCount = fail;

                // 缓存本批 photoId，供"重新分析本批"按钮用
                lastBatchPhotoIds.clear();
                if (finalR != null && finalR.uploadedPhotos != null) {
                    for (com.ai_photo.net.Models.UploadedPhoto up : finalR.uploadedPhotos) {
                        if (up != null) lastBatchPhotoIds.add(up.photoId);
                    }
                    Log.d(TAG, "[UI/AI] lastBatchPhotoIds size=" + lastBatchPhotoIds.size());
                }

                updateBatchMetrics();

                String msg;
                if (finalR == null) {
                    Log.e(TAG, "[UI/AI] doUpload FAIL err=" + finalErrMsg);
                    msg = "上传失败：" + (finalErrMsg == null ? "未知错误" : finalErrMsg);
                } else {
                    Log.d(TAG, "[UI/AI] doUpload OK success=" + ok + " fail=" + fail);
                    msg = "上传完成：成功 " + ok + " 张"
                            + (fail > 0 ? ("，失败 " + fail + " 张") : "");
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                // 拉一次 AI 状态刷新
                loadAiStatus();
                // 刷新最近分析结果预览（让用户立刻看到自己刚上传的照片）
                if (ok > 0) loadRecentPreview();
            });
        });
    }

    /**
     * 把 content:// URI 解码并压缩成长边 ≤ maxDim 的 JPEG 字节。
     * 用 BitmapFactory 两次采样：先只读尺寸，再按目标缩放解码，避开大图 OOM。
     * 返回 null 表示读取/解码失败。
     */
    private byte[] uriToJpegBytes(Uri uri, int maxDim) {
        InputStream is = null;
        try {
            // 第一次：只读尺寸
            is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opts);

            // 计算 inSampleSize
            int sample = 1;
            int w = opts.outWidth, h = opts.outHeight;
            int longer = Math.max(w, h);
            while (longer / sample > maxDim) sample *= 2;
            if (sample < 1) sample = 1;

            // 第二次：缩放解码
            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = sample;
            real.inPreferredConfig = Bitmap.Config.RGB_565;
            is.close();
            is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, real);
            if (bmp == null) return null;

            // 第三次：JPEG 压缩
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, baos);
            byte[] out = baos.toByteArray();
            bmp.recycle();
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 进度条 0→target 动画
     */
    private void animateBatchProgress(int target) {
        ProgressBar pb = findViewById(R.id.batchProgress);
        TextView tv = findViewById(R.id.batchProgressText);
        if (pb == null) return;
        int from = pb.getProgress();
        ValueAnimator anim = ValueAnimator.ofInt(from, Math.max(from, target));
        anim.setDuration(400L);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> {
            int v = (int) a.getAnimatedValue();
            pb.setProgress(v);
            if (tv != null) {
                // 优先级：AI 状态文本靠 loadAiStatus 写入；进度条旁边的 % 与目标进度共存。
                // 这里只在 tv 文本以 % 结尾（进度条 % 槽）时刷新；否则不动以保留 AI 状态文案。
                String cur = tv.getText().toString();
                if (cur.endsWith("%") || cur.isEmpty() || cur.equals("--")) {
                    tv.setText(v + "%");
                }
            }
        });
        anim.start();
    }

    /**
     * 4 个指标数字：batchUploadCard 内部 data row 里的 4 个 metric value TextView。
     * 用 TextView 的 tag 一次性标记，后续直接 findViewWithTag 取。
     */
    private void updateBatchMetrics() {
        tagBatchMetricsIfNeeded();
        setTextByTag("metricTotal",     String.valueOf(pickedCount));
        setTextByTag("metricUploaded",  String.valueOf(uploadedCount + successCountTotal));
        setTextByTag("metricSuccess",   String.valueOf(uploadedCount));
        setTextByTag("metricFailed",    String.valueOf(failedCount));

        // 失败提示区的可见性
        View failedHint = findViewById(R.id.batchFailedHint);
        if (failedHint != null) {
            failedHint.setVisibility(failedCount > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void tagBatchMetricsIfNeeded() {
        View card = findViewById(R.id.batchUploadCard);
        if (!(card instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout batch = (android.widget.LinearLayout) card;
        // 期望子节点：0=header 标题行, 1=4 等分数据行, 2=进度条行, 3=failed hint
        if (batch.getChildCount() < 2) return;
        View dataRow = batch.getChildAt(1);
        if (!(dataRow instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout row = (android.widget.LinearLayout) dataRow;
        String[] tags = { "metricTotal", "metricUploaded", "metricSuccess", "metricFailed" };
        for (int i = 0; i < Math.min(4, row.getChildCount()); i++) {
            View col = row.getChildAt(i);
            if (!(col instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout colLL = (android.widget.LinearLayout) col;
            // 每列 2 个 TextView：label (idx 0) + value (idx 1)
            if (colLL.getChildCount() < 2) continue;
            TextView value = (TextView) colLL.getChildAt(1);
            value.setTag(tags[i]);
        }
    }

    private void setTextByTag(Object tag, String text) {
        if (tag == null) return;
        View v = findViewById(android.R.id.content).findViewWithTag(tag);
        if (v instanceof TextView) ((TextView) v).setText(text);
    }

    /**
     * 启用 / 禁用 上传相关按钮，避免重复点击
     */
    private void applyUploadButtonState() {
        View btnStart = findViewById(R.id.btnStartUpload);
        if (btnStart != null) {
            boolean canStart = !uploading && pickedCount > 0;
            btnStart.setEnabled(canStart);
            btnStart.setAlpha(canStart ? 1f : 0.5f);
        }
        View btnReselect = findViewById(R.id.btnReselect);
        if (btnReselect != null) {
            btnReselect.setEnabled(!uploading);
            btnReselect.setAlpha(uploading ? 0.5f : 1f);
        }
    }

    /**
     * 失败重试：把失败计数清零，重新走一次上传（仍然受 pickedCount 约束）
     */
    private void doRetryFailed() {
        if (uploading) return;
        if (pickedCount == 0) {
            Toast.makeText(this, "请先选择要上传的照片", Toast.LENGTH_SHORT).show();
            return;
        }
        failedCount = 0;
        uploadedCount = 0;
        successCountTotal = 0;
        updateBatchMetrics();
        doUpload();
    }

    /**
     * 重新分析"本批已上传"的全部 photoId（不再误把全站最近 50 张都拉进队列）。
     * 适用于"上传完后看到 pending/failed 想再触发一次"的场景。
     */
    private void doReanalyzeAll() {
        if (lastBatchPhotoIds.isEmpty()) {
            Log.w(TAG, "[UI/AI] doReanalyzeAll: lastBatchPhotoIds is empty");
            main.post(() -> Toast.makeText(this,
                    "本批还没有成功上传的照片可重分析", Toast.LENGTH_SHORT).show());
            return;
        }
        // 复制一份避免后台线程期间被 UI 线程改
        final List<Long> ids = new ArrayList<>(lastBatchPhotoIds);
        Log.d(TAG, "[UI/AI] doReanalyzeAll start ids.size=" + ids.size());
        io.execute(() -> {
            try {
                int count = ApiService.reanalyze(ids);
                final int fc = count;
                main.post(() -> {
                    Log.d(TAG, "[UI/AI] doReanalyzeAll OK queuedCount=" + fc);
                    Toast.makeText(this,
                            "已重新加入队列 " + fc + " 张", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/AI] doReanalyzeAll EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "重新分析失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }
}
