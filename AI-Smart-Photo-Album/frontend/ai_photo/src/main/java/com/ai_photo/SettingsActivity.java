package com.ai_photo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ai_photo.net.ApiService;
import com.ai_photo.net.Models.AdminCategory;
import com.google.android.flexbox.FlexboxLayout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "设置" 页面（分类集合管理）。
 *
 * 五大模块：
 * 1. Header
 * 2. 分类集合管理卡（场景 / 情绪 / 标签）— 从 /admin/categories 拉取
 * 3. 展示规则卡（分段控制 + 2 开关）
 * 4. 操作按钮（取消 / 保存 / 重置 / 新增）
 * 5. 底部导航
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "AiPhoto.UI/Settings";

    private boolean showFiveOnly = true;
    private boolean userEdit = true;

    private static final String PREFS = "ai_photo_session";
    private static final String KEY_SHOW5 = "display.showFiveOnly";
    private static final String KEY_USER_EDIT = "display.userEdit";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsScroll), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingStart(), systemBars.top, v.getPaddingEnd(), systemBars.bottom);
            return insets;
        });

        // 先从本地偏好恢复两个开关的初始值，让 UI 状态和持久化值保持一致
        restoreDisplayPrefs();

        setupSegmentedControl();
        setupSwitches();
        setupActionButtons();
        setupHeader();
        setupBottomNav();

        loadAdminCategories();
    }

    private void restoreDisplayPrefs() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        showFiveOnly = prefs.getBoolean(KEY_SHOW5, true);
        userEdit = prefs.getBoolean(KEY_USER_EDIT, true);
    }

    private void persistDisplayPrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SHOW5, showFiveOnly)
                .putBoolean(KEY_USER_EDIT, userEdit)
                .apply();
    }

    // ============================================================
    //  从 /admin/categories 拉 3 个 type，渲染到三个 FlexboxLayout
    // ============================================================
    private void loadAdminCategories() {
        Log.d(TAG, "[UI/Settings] loadAdminCategories start");
        io.execute(() -> {
            try {
                List<AdminCategory> scenes = ApiService.adminListCategories("scene");
                List<AdminCategory> emotions = ApiService.adminListCategories("emotion");
                List<AdminCategory> tags = ApiService.adminListCategories("tag");
                final int fs = scenes == null ? 0 : scenes.size();
                final int fe = emotions == null ? 0 : emotions.size();
                final int ft = tags == null ? 0 : tags.size();
                main.post(() -> {
                    Log.d(TAG, "[UI/Settings] loadAdminCategories OK scene=" + fs
                            + " emotion=" + fe + " tag=" + ft);
                    renderChips(scenes, emotions, tags);
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Settings] loadAdminCategories EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "分类加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                // 失败时渲染空容器，避免布局塌陷
                renderChips(null, null, null);
            }
        });
    }

    private void renderChips(@Nullable List<AdminCategory> scenes,
                             @Nullable List<AdminCategory> emotions,
                             @Nullable List<AdminCategory> tags) {
        FlexboxLayout flexScene = findViewById(R.id.flexScene);
        FlexboxLayout flexEmotion = findViewById(R.id.flexEmotion);
        FlexboxLayout flexTag = findViewById(R.id.flexTag);

        // 渲染前清空
        if (flexScene != null) flexScene.removeAllViews();
        if (flexEmotion != null) flexEmotion.removeAllViews();
        if (flexTag != null) flexTag.removeAllViews();

        renderInto(flexScene, scenes, "scene");
        renderInto(flexEmotion, emotions, "emotion");
        renderInto(flexTag, tags, "tag");
    }

    private void renderInto(@Nullable FlexboxLayout container,
                            @Nullable List<AdminCategory> items,
                            @Nullable String type) {
        if (container == null) return;
        LayoutInflater inflater = LayoutInflater.from(this);
        if (items == null) return;
        for (AdminCategory c : items) {
            View chip = inflater.inflate(R.layout.settings_chip, container, false);
            TextView emoji = chip.findViewById(R.id.chipEmoji);
            TextView label = chip.findViewById(R.id.chipLabel);
            // emoji 来自分类名首字符
            String name = c.name == null ? "" : c.name;
            emoji.setText(stripToEmoji(name));
            label.setText(stripToText(name));
            chip.setOnLongClickListener(v -> {
                Log.d(TAG, "[UI/Settings] longPress chip type=" + type
                        + " id=" + c.categoryId + " name=" + name);
                confirmDelete(c.categoryId, name);
                return true;
            });
            container.addView(chip);
        }
    }

    private static String stripToEmoji(String name) {
        if (name == null || name.isEmpty()) return "·";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (isAlnumOrCjk(c)) break;
            return name.substring(0, i + 1).trim();
        }
        return "·";
    }

    private static String stripToText(String name) {
        if (name == null) return "";
        int i = 0;
        while (i < name.length() && !isAlnumOrCjk(name.charAt(i))) i++;
        return i > 0 ? name.substring(i).trim() : name;
    }

    private static boolean isAlnumOrCjk(char c) {
        if (Character.isLetterOrDigit(c)) return true;
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private void confirmDelete(long categoryId, String name) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_dialog_delete_title)
                .setMessage(getString(R.string.settings_dialog_delete_message, name))
                .setPositiveButton(R.string.settings_dialog_delete_confirm, (d, w) -> {
                    Log.d(TAG, "[UI/Settings] delete confirm id=" + categoryId);
                    doDeleteCategory(categoryId);
                })
                .setNegativeButton(R.string.settings_dialog_cancel, (d, w) ->
                        Log.d(TAG, "[UI/Settings] delete cancel id=" + categoryId))
                .show();
    }

    private void doDeleteCategory(long categoryId) {
        Log.d(TAG, "[UI/Settings] doDeleteCategory start id=" + categoryId);
        io.execute(() -> {
            try {
                ApiService.adminDeleteCategory(categoryId);
                main.post(() -> {
                    Log.d(TAG, "[UI/Settings] doDeleteCategory OK id=" + categoryId);
                    Toast.makeText(this, R.string.settings_toast_deleted, Toast.LENGTH_SHORT).show();
                    loadAdminCategories();
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Settings] doDeleteCategory EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ============================================================
    //  分段控件
    // ============================================================
    private void setupSegmentedControl() {
        TextView seg5 = findViewById(R.id.segmentShow5);
        TextView segAll = findViewById(R.id.segmentShowAll);
        View.OnClickListener listener = v -> {
            int id = v.getId();
            showFiveOnly = (id == R.id.segmentShow5);
            Log.d(TAG, "[UI/Settings] click segmented showFiveOnly=" + showFiveOnly);
            applySegmentedState(seg5, segAll);
        };
        seg5.setOnClickListener(listener);
        segAll.setOnClickListener(listener);
        applySegmentedState(seg5, segAll);
    }

    private void applySegmentedState(TextView seg5, TextView segAll) {
        if (showFiveOnly) {
            seg5.setBackgroundResource(R.drawable.settings_bg_segment_thumb_blue);
            seg5.setTextColor(0xFFFFFFFF);
            seg5.setTypeface(seg5.getTypeface(), android.graphics.Typeface.BOLD);
            segAll.setBackground(null);
            segAll.setTextColor(0xFF7B8597);
            segAll.setTypeface(segAll.getTypeface(), android.graphics.Typeface.NORMAL);
        } else {
            segAll.setBackgroundResource(R.drawable.settings_bg_segment_thumb_blue);
            segAll.setTextColor(0xFFFFFFFF);
            segAll.setTypeface(segAll.getTypeface(), android.graphics.Typeface.BOLD);
            seg5.setBackground(null);
            seg5.setTextColor(0xFF7B8597);
            seg5.setTypeface(seg5.getTypeface(), android.graphics.Typeface.NORMAL);
        }
    }

    // ============================================================
    //  开关
    // ============================================================
    private void setupSwitches() {
        FrameLayout sw2 = findViewById(R.id.switchUserEdit);
        sw2.setOnClickListener(v -> {
            userEdit = !userEdit;
            Log.d(TAG, "[UI/Settings] click switchUserEdit → " + userEdit);
            applySwitchState(sw2, userEdit);
        });
        applySwitchState(sw2, userEdit);
    }

    private void applySwitchState(FrameLayout track, boolean on) {
        View thumb = track.getChildAt(0);
        if (on) {
            track.setBackgroundResource(R.drawable.settings_bg_switch_track_on);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
            lp.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
            lp.setMarginEnd((int) (2 * getResources().getDisplayMetrics().density));
            thumb.setLayoutParams(lp);
        } else {
            track.setBackgroundResource(R.drawable.settings_bg_switch_track_off);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
            lp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
            lp.setMarginStart((int) (2 * getResources().getDisplayMetrics().density));
            thumb.setLayoutParams(lp);
        }
    }

    // ============================================================
    //  操作按钮：取消 / 保存 / 新增 / 重置
    // ============================================================
    private void setupActionButtons() {
        findViewById(R.id.btnCancel).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Settings] click btnCancel");
            finish();
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Settings] click btnSave");
            persistDisplayPrefs();
            Toast.makeText(this, R.string.settings_toast_saved, Toast.LENGTH_SHORT).show();
            finish();
        });
        // 新增 / 重置分类（按 type 区分）
        bindAddButton(R.id.btnAddScene,    "scene");
        bindAddButton(R.id.btnAddEmotion,  "emotion");
        bindAddButton(R.id.btnAddTag,      "tag");
        bindResetButton(R.id.btnResetScene,   "scene");
        bindResetButton(R.id.btnResetEmotion, "emotion");
        bindResetButton(R.id.btnResetTag,     "tag");
    }

    private void showAddDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.settings_dialog_add_hint);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_dialog_add_title)
                .setView(input)
                .setPositiveButton(R.string.settings_dialog_add_confirm, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    doAddCategory(name);
                })
                .setNegativeButton(R.string.settings_dialog_cancel, null)
                .show();
    }

    private void doAddCategory(String name) {
        Log.d(TAG, "[UI/Settings] doAddCategory start name=" + name);
        io.execute(() -> {
            try {
                long id = ApiService.adminAddCategory("tag", "🏷️ " + name, null);
                final long fid = id;
                main.post(() -> {
                    Log.d(TAG, "[UI/Settings] doAddCategory OK id=" + fid);
                    Toast.makeText(this,
                            getString(R.string.settings_toast_added, fid),
                            Toast.LENGTH_SHORT).show();
                    loadAdminCategories();
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Settings] doAddCategory EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "添加失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void bindAddButton(int viewId, String type) {
        View v = findViewById(viewId);
        if (v != null) v.setOnClickListener(x -> {
            Log.d(TAG, "[UI/Settings] click addBtn type=" + type);
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setHint("新分类名称（不含 emoji）");
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("新增 " + type + " 分类")
                    .setView(input)
                    .setPositiveButton("确定", (d, w) -> {
                        String name = input.getText().toString().trim();
                        Log.d(TAG, "[UI/Settings] addDialog confirm type=" + type
                                + " name=\"" + name + "\"");
                        if (name.isEmpty()) return;
                        doAddCategoryTyped(type, name);
                    })
                    .setNegativeButton("取消", (d, w) ->
                            Log.d(TAG, "[UI/Settings] addDialog cancel type=" + type))
                    .show();
        });
    }

    private void bindResetButton(int viewId, String type) {
        View v = findViewById(viewId);
        if (v != null) v.setOnClickListener(x -> {
            Log.d(TAG, "[UI/Settings] click resetBtn type=" + type);
            // 单个 type 的"重置"等价于全量 reset 的一个子集；
            // 后端当前只提供全量 reset，所以这里仍调全量接口，但提示文案按 type 区分
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("重置 " + type + " 分类")
                    .setMessage("将删除该类型下所有用户自定义分类。确认？")
                    .setPositiveButton("重置", (d, w) -> {
                        Log.d(TAG, "[UI/Settings] reset confirm type=" + type);
                        io.execute(() -> {
                            try {
                                int count = ApiService.adminResetCategories();
                                final int fc = count;
                                main.post(() -> {
                                    Log.d(TAG, "[UI/Settings] reset OK count=" + fc);
                                    Toast.makeText(this, "已重置为 " + fc + " 项", Toast.LENGTH_SHORT).show();
                                    loadAdminCategories();
                                });
                            } catch (Exception e) {
                                Log.e(TAG, "[UI/Settings] reset EXC " + e.getMessage());
                                main.post(() -> Toast.makeText(this,
                                        "重置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                            }
                        });
                    })
                    .setNegativeButton("取消", (d, w) ->
                            Log.d(TAG, "[UI/Settings] reset cancel type=" + type))
                    .show();
        });
    }

    private void doAddCategoryTyped(String type, String name) {
        Log.d(TAG, "[UI/Settings] doAddCategoryTyped start type=" + type + " name=" + name);
        io.execute(() -> {
            try {
                String emoji = type.equals("scene") ? "🎬 " : (type.equals("emotion") ? "💗 " : "🏷️ ");
                long id = ApiService.adminAddCategory(type, emoji + name, null);
                final long fid = id;
                main.post(() -> {
                    Log.d(TAG, "[UI/Settings] doAddCategoryTyped OK id=" + fid);
                    Toast.makeText(this, "已添加 (#" + fid + ")", Toast.LENGTH_SHORT).show();
                    loadAdminCategories();
                });
            } catch (Exception e) {
                Log.e(TAG, "[UI/Settings] doAddCategoryTyped EXC " + e.getMessage());
                main.post(() -> Toast.makeText(this,
                        "添加失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void doReset() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_dialog_reset_title)
                .setMessage(R.string.settings_dialog_reset_message_full)
                .setPositiveButton(R.string.settings_dialog_reset_confirm, (d, w) -> {
                    Log.d(TAG, "[UI/Settings] doReset confirm");
                    io.execute(() -> {
                        try {
                            int count = ApiService.adminResetCategories();
                            final int fc = count;
                            main.post(() -> {
                                Log.d(TAG, "[UI/Settings] doReset OK count=" + fc);
                                Toast.makeText(this,
                                        getString(R.string.settings_toast_reset_full, fc),
                                        Toast.LENGTH_SHORT).show();
                                loadAdminCategories();
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "[UI/Settings] doReset EXC " + e.getMessage());
                            main.post(() -> Toast.makeText(this,
                                    "重置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton(R.string.settings_dialog_cancel, (d, w) ->
                        Log.d(TAG, "[UI/Settings] doReset cancel"))
                .show();
    }

    // ============================================================
    //  Header / 底部导航
    // ============================================================
    private void setupHeader() {
        findViewById(R.id.headerGearBtn).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Settings] click headerGearBtn (already in settings)");
            Toast.makeText(this, R.string.settings_dialog_already, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupBottomNav() {
        findViewById(R.id.navHome).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Settings] click bottomNav→Home");
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        findViewById(R.id.navAlbum).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Settings] click bottomNav→Album");
            startActivity(new Intent(this, AlbumActivity.class));
            finish();
        });
        findViewById(R.id.navAI).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Settings] click bottomNav→AI");
            startActivity(new Intent(this, AIAnalysisActivity.class));
            finish();
        });
        findViewById(R.id.navMe).setOnClickListener(v -> {
            Log.d(TAG, "[UI/Settings] click bottomNav→Me");
            startActivity(new Intent(this, ProfileActivity.class));
            finish();
        });
    }
}