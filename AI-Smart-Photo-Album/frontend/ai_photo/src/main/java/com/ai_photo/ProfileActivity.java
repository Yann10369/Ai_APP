package com.ai_photo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ai_photo.auth.Session;
import com.ai_photo.net.ApiService;
import com.ai_photo.net.Models.CategoryDistribution;
import com.ai_photo.net.Models.FavoritePhoto;
import com.ai_photo.net.Models.PagedFavorites;
import com.ai_photo.net.Models.UserMe;
import com.ai_photo.net.Models.UserStatistics;

import android.content.res.ColorStateList;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "我的" 个人中心页：
 * - 头部信息（从 /users/me 拉）
 * - 数据统计（从 /users/me/statistics 拉）
 * - Donut 图：按场景分布前 5 项
 * - 收藏（从 /users/me/favorites 拉首页 + 翻页）
 * - 底部导航
 */
public class ProfileActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/Profile";

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    // 收藏分页状态
    private static final int FAV_PAGE_SIZE = 9; // 3 列 x 3 行
    private int favPage = 1;
    private int favTotal = 0;
    private List<FavoritePhoto> favCache = Collections.emptyList();

    // 头像选择 launcher
    private ActivityResultLauncher<String> pickAvatarLauncher;
    private ImageView profileAvatarView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.profileScroll), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), v.getPaddingTop(), v.getPaddingEnd(), systemBars.bottom);
            return insets;
        });

        setupHeaderActions();
        setupBottomNav();
        setupPagination();
        setupFavoritesSeeAll();
        setupAvatarLauncher();

        loadUserInfo();
        loadStatistics();
        loadFavorites();
        loadAvatarFromLocal();
    }

    // ============================================================
    //  顶部 Header：用户名 / 头像相关
    // ============================================================
    private void setupHeaderActions() {
        // 右上角齿轮按钮 → 跳到 SettingsActivity
        View setting = findViewById(R.id.headerSettingBtn);
        if (setting != null) {
            setting.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Profile] click headerSettingBtn");
                startActivity(new Intent(this, SettingsActivity.class));
            });
        }
        // 左上角 AI sparkle 图标 → 退出登录（按结构定位，没有 id）
        bindSparkleAsLogout();
        TextView editBtn = findViewById(R.id.profileEditBtn);
        if (editBtn != null) {
            editBtn.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Profile] click profileEditBtn");
                showEditProfileDialog();
            });
        }
        // 头像：点击直接打开系统选择器（也能在 edit dialog 里换）
        profileAvatarView = findViewById(R.id.profileAvatar);
        View avatarFrame = findViewById(R.id.profileAvatarFrame);
        if (avatarFrame != null) {
            avatarFrame.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Profile] click profileAvatarFrame");
                try {
                    pickAvatarLauncher.launch("image/*");
                } catch (Exception e) {
                    Toast.makeText(this,
                            "系统选择器不可用: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * 注册头像选择 launcher：用户从图库选一张图片后，
     * 由子线程解码 → 缩放 → 写到 filesDir/avatar.jpg，主线程刷新 ImageView。
     */
    private void setupAvatarLauncher() {
        pickAvatarLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> onAvatarPicked(uri));
    }

    private void onAvatarPicked(@Nullable Uri uri) {
        if (uri == null) {
            Log.d(TAG, "[UI/Profile] onAvatarPicked: null (cancel)");
            return;
        }
        Log.d(TAG, "[UI/Profile] onAvatarPicked: " + uri);
        io.execute(() -> {
            File out = null;
            try {
                Bitmap bm = decodeAvatarBitmap(uri, 256);
                if (bm == null) {
                    main.post(() -> Toast.makeText(this,
                            R.string.profile_avatar_toast_pick_fail, Toast.LENGTH_SHORT).show());
                    return;
                }
                out = new File(getFilesDir(), "avatar.jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    bm.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                }
                bm.recycle();
                final File finalOut = out;
                // 本地先刷新
                main.post(() -> {
                    if (profileAvatarView != null) {
                        Bitmap round = decodeFileBitmap(finalOut, 256);
                        if (round != null) {
                            profileAvatarView.setImageBitmap(round);
                        }
                    }
                    Toast.makeText(this, R.string.profile_avatar_toast_saved,
                            Toast.LENGTH_SHORT).show();
                });
                // 同步到云端：POST /api/v1/users/me/avatar
                String err = null;
                try {
                    com.ai_photo.net.ApiService.uploadAvatar(finalOut);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String finalErr = err;
                if (finalErr != null) {
                    main.post(() -> Toast.makeText(this,
                            "云端同步失败：" + finalErr, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception ex) {
                Log.e(TAG, "[UI/Profile] onAvatarPicked EXC " + ex.getMessage());
                main.post(() -> Toast.makeText(this,
                        "头像处理失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** 进入页面时恢复本地头像（若存在） */
    private void loadAvatarFromLocal() {
        if (profileAvatarView == null) return;
        File f = new File(getFilesDir(), "avatar.jpg");
        if (!f.exists()) return;
        Bitmap bm = decodeFileBitmap(f, 256);
        if (bm != null) profileAvatarView.setImageBitmap(bm);
    }

    /** 把 content URI 解码为最长边 ≤ maxDim 的 Bitmap；失败返回 null */
    @Nullable
    private Bitmap decodeAvatarBitmap(Uri uri, int maxDim) {
        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opts);
            int sample = 1;
            int longer = Math.max(opts.outWidth, opts.outHeight);
            while (longer / sample > maxDim) sample *= 2;
            if (sample < 1) sample = 1;
            is.close();
            is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = sample;
            real.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(is, null, real);
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) {}
        }
    }

    /** 直接从文件解码（不再需要采样，因为原文件已被压到 ≤256 边长） */
    @Nullable
    private static Bitmap decodeFileBitmap(File f, int maxDim) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            int sample = 1;
            int longer = Math.max(opts.outWidth, opts.outHeight);
            while (longer / sample > maxDim) sample *= 2;
            if (sample < 1) sample = 1;
            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = sample;
            real.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), real);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * profile 头部左上角的 AI sparkle 图标（layout 里没给 id）作为退出登录入口。
     * 定位：header → 左 LinearLayout → 内层横向 LinearLayout → 第 2 个子节点（ImageView）。
     */
    private void bindSparkleAsLogout() {
        View header = findViewById(R.id.header);
        if (!(header instanceof LinearLayout)) return;
        LinearLayout hl = (LinearLayout) header;
        if (hl.getChildCount() == 0) return;
        View left = hl.getChildAt(0);
        if (!(left instanceof LinearLayout)) return;
        LinearLayout leftLL = (LinearLayout) left;
        if (leftLL.getChildCount() == 0) return;
        View titleRow = leftLL.getChildAt(0);
        if (!(titleRow instanceof LinearLayout)) return;
        LinearLayout tr = (LinearLayout) titleRow;
        // 0=TextView 标题，1=ImageView sparkle
        if (tr.getChildCount() < 2) return;
        View sparkle = tr.getChildAt(1);
        if (!(sparkle instanceof android.widget.ImageView)) return;
        sparkle.setClickable(true);
        sparkle.setFocusable(true);
        // ?attr/selectableItemBackground 是 attr 引用，不能直接当 drawable id 用，
        // 必须先通过当前主题解析为实际的 resourceId 再 set；否则会抛
        // Resources$NotFoundException: Resource ID #0x101030e (complex map type)。
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            sparkle.setBackgroundResource(tv.resourceId);
        }
        sparkle.setOnClickListener(v -> {
            Log.d(TAG, "[UI/Profile] click sparkle=logout");
            showLogoutDialog();
        });
    }

    /**
     * 退出登录确认弹窗：点击确定 → 清除 Session，跳 LoginActivity 并关闭当前页。
     */
    private void showLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("确定要退出当前账号吗？")
                .setNegativeButton("取消", (d, w) -> Log.d(TAG, "[UI/Profile] logout dialog cancel"))
                .setPositiveButton("确定", (d, w) -> {
                    Log.d(TAG, "[UI/Profile] logout dialog confirm");
                    doLogout();
                })
                .show();
    }

    private void doLogout() {
        Log.d(TAG, "[UI/Profile] doLogout start");
        // 先通知后端失效 token；后端失败不影响本地清空（兜底仍然能退出）
        io.execute(() -> {
            String err = null;
            try {
                ApiService.logout();
                Log.d(TAG, "[UI/Profile] doLogout backend OK");
            } catch (Exception e) {
                err = e.getMessage();
                Log.w(TAG, "[UI/Profile] doLogout backend EXC " + err);
            }
            // 不论后端成败，本地 session 都清掉
            Session.clear(ProfileActivity.this);
            final String fErr = err;
            main.post(() -> {
                if (fErr != null) {
                    Toast.makeText(ProfileActivity.this,
                            "已退出登录（服务端通知失败，本地已清理）", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ProfileActivity.this,
                            "已退出登录", Toast.LENGTH_SHORT).show();
                }
                Intent i = new Intent(ProfileActivity.this, LoginActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            });
        });
    }

    /**
     * "编辑资料"对话框：先给两个选项（更换头像 / 修改用户名）。
     * - 更换头像：调 pickAvatarLauncher 走系统图库
     * - 修改用户名：弹 EditText，保存到本地 + 尝试 PATCH 后端
     */
    private void showEditProfileDialog() {
        final CharSequence[] options = new CharSequence[]{
                getString(R.string.profile_edit_change_avatar),
                getString(R.string.profile_edit_username_label)
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.profile_edit_title)
                .setItems(options, (d, w) -> {
                    int idx = w;
                    if (idx == 0) {
                        Log.d(TAG, "[UI/Profile] editProfile → change avatar");
                        try {
                            pickAvatarLauncher.launch("image/*");
                        } catch (Exception e) {
                            Toast.makeText(this,
                                    "系统选择器不可用: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    } else if (idx == 1) {
                        Log.d(TAG, "[UI/Profile] editProfile → change username");
                        showEditUsernameDialog();
                    }
                })
                .setNegativeButton(R.string.profile_edit_cancel, (d, w) ->
                        Log.d(TAG, "[UI/Profile] editProfile cancel"))
                .show();
    }

    /**
     * "编辑资料 → 修改用户名"子对话框：
     * 调 PATCH /api/v1/users/me（后端暂未提供，这里降级：先保存本地，下次拉取时仍为后端值）。
     * 不管后端是否支持，都把新名字写进本地 SharedPreferences，并刷新顶部显示。
     */
    private void showEditUsernameDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        TextView nameView = findViewById(R.id.profileName);
        if (nameView != null) {
            input.setText(nameView.getText());
            input.setSelection(input.getText().length());
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.profile_edit_username_label)
                .setView(input)
                .setPositiveButton(R.string.profile_edit_save, (d, w) -> {
                    String newName = input.getText().toString().trim();
                    Log.d(TAG, "[UI/Profile] editUsername save newName=" + newName);
                    if (newName.isEmpty()) return;
                    persistUsername(newName);
                })
                .setNegativeButton(R.string.profile_edit_cancel, (d, w) ->
                        Log.d(TAG, "[UI/Profile] editUsername cancel"))
                .show();
    }

    private void persistUsername(String newName) {
        if (newName == null || newName.isEmpty()) return;
        TextView nameView = findViewById(R.id.profileName);
        if (nameView != null) nameView.setText(newName);
        getSharedPreferences("ai_photo_session", MODE_PRIVATE)
                .edit().putString("displayName", newName).apply();

        // 同步到后端：PATCH /api/v1/users/me；失败不阻塞本地显示
        io.execute(() -> {
            String err = null;
            try {
                com.ai_photo.net.ApiService.updateUserProfile(newName);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String finalErr = err;
            main.post(() -> {
                if (finalErr == null) {
                    Toast.makeText(this, "已保存到云端", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "已保存到本地（云端同步失败：" + finalErr + "）",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void setupBottomNav() {
        findViewById(R.id.navHome).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Profile] click bottomNav→Home");
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        findViewById(R.id.navAlbum).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Profile] click bottomNav→Album");
            startActivity(new Intent(this, AlbumActivity.class));
            finish();
        });
        findViewById(R.id.navAI).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Profile] click bottomNav→AI");
            startActivity(new Intent(this, AIAnalysisActivity.class));
            finish();
        });
    }

    /**
     * 翻页按钮：默认禁用 prev；点击 next/prev 调整 favPage 然后重新拉。
     */
    private void setupPagination() {
        TextView prev = findViewById(R.id.btnPrevPage);
        TextView next = findViewById(R.id.btnNextPage);
        if (prev != null) {
            prev.setAlpha(0.4f);
            prev.setEnabled(false);
            prev.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Profile] click btnPrevPage favPage=" + favPage);
                if (favPage > 1) {
                    favPage--;
                    loadFavorites();
                } else {
                    Log.d(TAG, "[UI/Profile] btnPrevPage disabled (already first page)");
                }
            });
        }
        if (next != null) {
            next.setOnClickListener(v -> {
                Log.d(TAG, "[UI/Profile] click btnNextPage favPage=" + favPage);
                int totalPages = Math.max(1, (favTotal + FAV_PAGE_SIZE - 1) / FAV_PAGE_SIZE);
                if (favPage < totalPages) {
                    favPage++;
                    loadFavorites();
                } else {
                    Log.d(TAG, "[UI/Profile] btnNextPage at last page totalPages=" + totalPages);
                    Toast.makeText(this, "已经是最后一页", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * favoritesSection 内 "查看全部" 的 TextView 没有 id；
     * 通过 favoritesSection 父节点找 header LinearLayout，再取最后那个 TextView。
     */
    private void setupFavoritesSeeAll() {
        LinearLayout favSection = findViewById(R.id.favoritesSection);
        if (favSection == null) return;
        View header = favSection.getChildAt(0);
        if (!(header instanceof LinearLayout)) return;
        LinearLayout headerRow = (LinearLayout) header;
        TextView seeAll = null;
        for (int i = 0; i < headerRow.getChildCount(); i++) {
            View c = headerRow.getChildAt(i);
            if (c instanceof TextView && "查看全部".contentEquals(((TextView) c).getText().toString().trim())) {
                seeAll = (TextView) c;
                break;
            }
        }
        if (seeAll == null) return;
        seeAll.setClickable(true);
        seeAll.setFocusable(true);
        seeAll.setOnClickListener(v -> {
            Log.d(TAG, "[UI/Profile] click favorites→查看全部");
            startActivity(new Intent(this, AlbumActivity.class));
        });
    }

    // ============================================================
    //  /users/me
    // ============================================================
    private void loadUserInfo() {
        Log.d(TAG, "[UI/Profile] loadUserInfo start");
        io.execute(() -> {
            try {
                UserMe me = ApiService.getMe();
                final String createdAt = me.createdAt;
                main.post(() -> {
                    TextView nameView = findViewById(R.id.profileName);
                    if (nameView != null) {
                        // 优先用本地编辑过的本地显示名
                        String local = getSharedPreferences("ai_photo_session", MODE_PRIVATE)
                                .getString("displayName", null);
                        nameView.setText(local != null ? local : safe(me.username, "用户"));
                        Log.d(TAG, "[UI/Profile] loadUserInfo OK username=" + me.username);
                    }
                    bindCompanionDays(createdAt);
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Profile] loadUserInfo EXC " + e.getMessage());
                main.post(() -> {
                    // 网络失败时也尝试用本地显示名
                    String local = getSharedPreferences("ai_photo_session", MODE_PRIVATE)
                            .getString("displayName", null);
                    if (local != null) {
                        TextView nameView = findViewById(R.id.profileName);
                        if (nameView != null) nameView.setText(local);
                    }
                    bindCompanionDays(null);
                });
            }
        });
    }

    // ============================================================
    //  /users/me/statistics
    // ============================================================
    private void loadStatistics() {
        Log.d(TAG, "[UI/Profile] loadStatistics start");
        io.execute(() -> {
            try {
                UserStatistics stats = ApiService.getMyStatistics();
                main.post(() -> {
                    Log.d(TAG, "[UI/Profile] loadStatistics OK total=" + stats.totalPhotos
                            + " analyzed=" + stats.analyzedPhotos
                            + " favorites=" + stats.favoriteCount);
                    bindStatsCards(stats);
                    bindDonutFromDistribution(stats.scene);
                    bindSceneList(stats.scene);
                    bindEmotionList(stats.emotion);
                    bindTagList(stats.tag);
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Profile] loadStatistics EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "统计数据加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * 把数字写进三个指标卡：照片总量 / 已分析 / 收藏。
     * 布局里靠 android:tag 直接定位 TextView，findViewWithTag 即可拿到。
     */
    private void bindStatsCards(UserStatistics s) {
        setTextByTag("metricTotalValue",     formatCount(s.totalPhotos));
        setTextByTag("metricAnalyzedValue",  formatCount(s.analyzedPhotos));
        setTextByTag("metricFavoritesValue", formatCount(s.favoriteCount));
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

    /**
     * 把场景分布塞进 Donut（取前 5 项）。
     * 用后端给的 percentage 字段（已是 0-100 的 double），四舍五入到整数；
     * 总和不足 100 时把差值补到最大段，避免圆环出现"开口"。
     */
    private void bindDonutFromDistribution(@Nullable List<CategoryDistribution> dist) {
        DonutChartView donut = findViewById(R.id.donutScene);
        if (donut == null) return;

        if (dist == null || dist.isEmpty()) {
            // 没数据时画一个空圆环占位
            donut.setData(new DonutChartView.Segment[]{ new DonutChartView.Segment(0xFFE0E5EE, 1) });
            return;
        }
        int[] palette = { 0xFF4A90E2, 0xFF00C4B6, 0xFF9013FE, 0xFFFFB74D, 0xFFFF7AAE };
        int n = Math.min(5, dist.size());
        DonutChartView.Segment[] segs = new DonutChartView.Segment[n];
        for (int i = 0; i < n; i++) {
            int p = (int) Math.round(Math.max(0, dist.get(i).percentage));
            segs[i] = new DonutChartView.Segment(palette[i % palette.length], p);
        }
        // 补齐到 100：差额加到最大段；超过 100 则从最大段扣
        int sum = 0; int maxIdx = 0;
        for (int i = 0; i < n; i++) {
            sum += segs[i].percent;
            if (segs[i].percent > segs[maxIdx].percent) maxIdx = i;
        }
        if (sum != 100 && n > 0) {
            int adjust = 100 - sum;
            int newP = segs[maxIdx].percent + adjust;
            if (newP < 0) newP = 0;
            segs[maxIdx] = new DonutChartView.Segment(segs[maxIdx].color, newP);
        }
        // 全 0 兜底：避免空环，给最大段至少 1%
        boolean allZero = true;
        for (DonutChartView.Segment s : segs) if (s.percent > 0) { allZero = false; break; }
        if (allZero) segs[0] = new DonutChartView.Segment(segs[0].color, 100);
        donut.setData(segs);
    }

    /**
     * 场景分布列表（5 行）：从 stats.scene 取前 5 项写入名称 + 百分比 + 圆点颜色。
     * 数据不足 5 项时多余行 GONE。
     */
    private void bindSceneList(@Nullable List<CategoryDistribution> list) {
        int[] palette = { 0xFF4A90E2, 0xFF00C4B6, 0xFF9013FE, 0xFFFFB74D, 0xFFFF7AAE };
        int n = Math.min(5, list == null ? 0 : list.size());
        View root = findViewById(android.R.id.content);
        for (int i = 0; i < 5; i++) {
            View dot = root.findViewWithTag("sceneDot" + (i + 1));
            TextView nameView = (TextView) root.findViewWithTag("sceneName" + (i + 1));
            TextView pctView = (TextView) root.findViewWithTag("scenePct" + (i + 1));
            View row = nameView != null ? (View) nameView.getParent().getParent() : null;
            if (i < n && list != null) {
                CategoryDistribution d = list.get(i);
                if (row != null) row.setVisibility(View.VISIBLE);
                if (dot != null) dot.setBackgroundTintList(ColorStateList.valueOf(palette[i]));
                if (nameView != null) nameView.setText(safe(d.name, "--"));
                if (pctView != null) pctView.setText(formatPct(d.percentage));
            } else {
                if (row != null) row.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 情绪分布列表（3 行）：从 stats.emotion 取前 3 项写入名称 + 百分比 + 进度条宽度 + 动态图标。
     */
    private void bindEmotionList(@Nullable List<CategoryDistribution> list) {
        int n = Math.min(3, list == null ? 0 : list.size());
        View root = findViewById(android.R.id.content);
        for (int i = 0; i < 3; i++) {
            TextView nameView = (TextView) root.findViewWithTag("emotionName" + (i + 1));
            TextView pctView = (TextView) root.findViewWithTag("emotionPct" + (i + 1));
            ImageView iconView = (ImageView) root.findViewWithTag("emotionIcon" + (i + 1));
            int trackId = getResources().getIdentifier("emotionTrack" + (i + 1), "id", getPackageName());
            int fillId = getResources().getIdentifier("emotionFill" + (i + 1), "id", getPackageName());
            FrameLayout track = (FrameLayout) findViewById(trackId);
            View fill = findViewById(fillId);
            View row = nameView != null ? (View) nameView.getParent() : null;
            if (i < n && list != null) {
                CategoryDistribution d = list.get(i);
                if (row != null) row.setVisibility(View.VISIBLE);
                if (nameView != null) nameView.setText(safe(d.name, "--"));
                if (pctView != null) pctView.setText(formatPct(d.percentage));
                if (iconView != null) iconView.setImageResource(pickEmotionIcon(d.name));
                applyProgressFill(track, fill, d.percentage);
            } else {
                if (row != null) row.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 情绪名 → emoji drawable。未匹配时回退到 happy（视觉上不出错即可）。
     * 支持的常见名字：快乐/开心/happy → happy；平静/放松/calm → calm；搞怪/silly → silly；其他 → happy。
     */
    private int pickEmotionIcon(@Nullable String name) {
        if (name == null) return R.drawable.profile_ic_emoji_happy;
        String n = name.trim();
        if (n.contains("快乐") || n.contains("开心") || n.equalsIgnoreCase("happy")) {
            return R.drawable.profile_ic_emoji_happy;
        }
        if (n.contains("平静") || n.contains("放松") || n.equalsIgnoreCase("calm")) {
            return R.drawable.profile_ic_emoji_calm;
        }
        if (n.contains("搞怪") || n.equalsIgnoreCase("silly")) {
            return R.drawable.profile_ic_emoji_silly;
        }
        return R.drawable.profile_ic_emoji_happy;
    }

    /**
     * 标签分布列表（6 列）：从 stats.tag 取前 6 项写入名称 + 百分比 + 数量 + 进度条宽度。
     */
    private void bindTagList(@Nullable List<CategoryDistribution> list) {
        int n = Math.min(6, list == null ? 0 : list.size());
        View root = findViewById(android.R.id.content);
        for (int i = 0; i < 6; i++) {
            TextView nameView = (TextView) root.findViewWithTag("tagName" + (i + 1));
            TextView pctView = (TextView) root.findViewWithTag("tagPct" + (i + 1));
            TextView countView = (TextView) root.findViewWithTag("tagCount" + (i + 1));
            int trackId = getResources().getIdentifier("tagTrack" + (i + 1), "id", getPackageName());
            int fillId = getResources().getIdentifier("tagFill" + (i + 1), "id", getPackageName());
            FrameLayout track = (FrameLayout) findViewById(trackId);
            View fill = findViewById(fillId);
            // 标签柱根节点是 nameView 的爷爷（badge 横排 → 列垂直）
            View col = nameView != null ? (View) nameView.getParent().getParent() : null;
            if (i < n && list != null) {
                CategoryDistribution d = list.get(i);
                if (col != null) col.setVisibility(View.VISIBLE);
                if (nameView != null) nameView.setText(safe(d.name, "--"));
                if (pctView != null) pctView.setText(formatPct(d.percentage));
                if (countView != null) countView.setText(formatCount(d.count) + "张");
                applyProgressFill(track, fill, d.percentage);
            } else {
                if (col != null) col.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 把 fill 的宽度设为 track.width * percentage / 100。
     * 用 track.post 保证 track 已经 layout 完，能拿到宽度。
     */
    private void applyProgressFill(@Nullable FrameLayout track, @Nullable View fill, double percentage) {
        if (track == null || fill == null) return;
        final double pct = Math.max(0, Math.min(100, percentage));
        if (pct <= 0) {
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = 0;
            fill.setLayoutParams(lp);
            return;
        }
        track.post(() -> {
            int trackWidth = track.getWidth();
            if (trackWidth <= 0) return;
            int w = (int) Math.round(trackWidth * pct / 100.0);
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = w;
            fill.setLayoutParams(lp);
        });
    }

    /** 把 percentage (0-100) 格式化为 "32" 形式，<=0 显示 "--" */
    private static String formatPct(double pct) {
        if (pct <= 0) return "--";
        long v = Math.round(pct);
        if (v > 100) v = 100;
        return String.valueOf(v);
    }

    /**
     * 陪伴天数：从 me.createdAt (ISO 8601) 计算与今天的差值。
     * 无法解析或为 null 时显示 "--"。
     */
    private void bindCompanionDays(@Nullable String createdAt) {
        TextView days = findViewById(R.id.profileDays);
        if (days == null) return;
        if (createdAt == null || createdAt.isEmpty()) {
            days.setText("--");
            return;
        }
        long dayCount = computeDaysFromNow(createdAt);
        if (dayCount < 0) {
            days.setText("--");
        } else {
            days.setText(String.valueOf(dayCount));
        }
    }

    /**
     * 解析 ISO 8601 / 简易时间戳，返回与今天相差的天数（向下取整，<0 视为 0）。
     * 支持 "2025-01-15T08:00:00" / "2025-01-15T08:00:00Z" / "2025-01-15 08:00:00"。
     */
    private static long computeDaysFromNow(@NonNull String createdAt) {
        String[] formats = {
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String fmt : formats) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(fmt, java.util.Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date d = sdf.parse(createdAt);
                if (d != null) {
                    long diffMs = System.currentTimeMillis() - d.getTime();
                    long days = diffMs / (24L * 60L * 60L * 1000L);
                    return Math.max(0, days);
                }
            } catch (Exception ignored) { }
        }
        Log.w(TAG, "[UI/Profile] computeDaysFromNow failed: " + createdAt);
        return -1;
    }

    // ============================================================
    //  /users/me/favorites  →  渲染 3x3 网格
    // ============================================================
    private void loadFavorites() {
        Log.d(TAG, "[UI/Profile] loadFavorites page=" + favPage + " size=" + FAV_PAGE_SIZE);
        io.execute(() -> {
            final PagedFavorites p;
            try {
                p = ApiService.getMyFavorites(favPage, FAV_PAGE_SIZE);
            } catch (Exception e) {
                Log.e(TAG, "[UI/Profile] loadFavorites EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "收藏加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }
            main.post(() -> {
                favCache = p.list == null ? Collections.emptyList() : p.list;
                favTotal = p.total;
                Log.d(TAG, "[UI/Profile] loadFavorites OK total=" + p.total
                        + " returned=" + favCache.size());
                renderFavoritesGrid();
                updatePaginationUi();
            });
        });
    }

    private void renderFavoritesGrid() {
        LinearLayout grid = findViewById(R.id.favoritesGrid);
        if (grid == null) return;
        grid.removeAllViews();

        if (favCache.isEmpty()) {
            // 空状态：3x3 占位
            addFavPlaceholderRow(grid, 0);
            return;
        }

        int rows = (int) Math.ceil(favCache.size() / 3.0);
        for (int r = 0; r < rows; r++) {
            addFavRow(grid, r * 3, r);
        }
    }

    private void addFavRow(LinearLayout grid, int startIndex, int rowIndex) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100));
        if (rowIndex > 0) rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        for (int i = 0; i < 3; i++) {
            int idx = startIndex + i;
            boolean hasPhoto = idx < favCache.size();
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i == 0) clp.setMarginEnd(dp(4));
            else if (i == 2) clp.setMarginStart(dp(4));
            else { clp.setMarginStart(dp(4)); clp.setMarginEnd(dp(4)); }
            cell.setLayoutParams(clp);

            View photo = new View(this);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            photo.setLayoutParams(plp);
            photo.setBackgroundResource(R.drawable.profile_bg_photo_placeholder);
            cell.addView(photo);

            if (hasPhoto) {
                FavoritePhoto fp = favCache.get(idx);
                // 角标
                TextView badge = new TextView(this);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(18));
                blp.gravity = Gravity.TOP | Gravity.END;
                blp.setMargins(0, dp(6), dp(6), 0);
                badge.setLayoutParams(blp);
                badge.setBackgroundResource(R.drawable.profile_ic_heart_filled);
                badge.setPadding(dp(4), 0, dp(4), 0);
                cell.addView(badge);

                // id label（待图片加载接入后可去掉）
                TextView idLabel = new TextView(this);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ilp.gravity = Gravity.CENTER;
                idLabel.setLayoutParams(ilp);
                idLabel.setText("#" + fp.photoId);
                idLabel.setTextColor(0xFF999999);
                idLabel.setTextSize(11);
                cell.addView(idLabel);

                final long photoId = fp.photoId;
                cell.setOnClickListener(v -> {
                    Log.d(TAG, "[UI/Profile] click favorite cell photoId=" + photoId);
                    Intent it = new Intent(this, PhotoDetailActivity.class);
                    it.putExtra(PhotoDetailActivity.EXTRA_PHOTO_ID, photoId);
                    startActivity(it);
                });
            }

            row.addView(cell);
        }
        grid.addView(row);
    }

    /**
     * 占位：没有数据时也保持 3x3 网格的外观
     */
    private void addFavPlaceholderRow(LinearLayout grid, int rowIndex) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100));
        if (rowIndex > 0) rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);
        for (int i = 0; i < 3; i++) {
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i == 0) clp.setMarginEnd(dp(4));
            else if (i == 2) clp.setMarginStart(dp(4));
            else { clp.setMarginStart(dp(4)); clp.setMarginEnd(dp(4)); }
            cell.setLayoutParams(clp);
            View photo = new View(this);
            photo.setBackgroundResource(R.drawable.profile_bg_photo_placeholder);
            cell.addView(photo, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(cell);
        }
        grid.addView(row);
    }

    private void updatePaginationUi() {
        TextView prev = findViewById(R.id.btnPrevPage);
        TextView next = findViewById(R.id.btnNextPage);
        TextView info = findViewById(R.id.tvPageInfo);
        int totalPages = Math.max(1, (favTotal + FAV_PAGE_SIZE - 1) / FAV_PAGE_SIZE);
        if (prev != null) {
            boolean canPrev = favPage > 1;
            prev.setEnabled(canPrev);
            prev.setAlpha(canPrev ? 1f : 0.4f);
        }
        if (next != null) {
            boolean canNext = favPage < totalPages;
            next.setEnabled(canNext);
            next.setAlpha(canNext ? 1f : 0.4f);
        }
        if (info != null) info.setText(favPage + " / " + totalPages);
    }

    private static String safe(@Nullable String s, String fallback) {
        return s == null || s.isEmpty() ? fallback : s;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
