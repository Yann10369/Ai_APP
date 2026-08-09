package com.ai_photo.ai;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ai_photo.R;

import java.util.List;

/**
 * 任务历史列表 Adapter
 *
 * 状态颜色：
 * - ONGOING  → 蓝色云朵 + "未完成"(橙) / "进行中"(蓝) + "继续分析 >" 蓝边胶囊
 * - FINISHED → 绿色勾号 + "今天 09:32 完成"(灰) / "已完成"(灰) + "查看 >" 蓝边胶囊
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    private final List<HistoryItem> data;

    public HistoryAdapter(List<HistoryItem> data) {
        this.data = data;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ai_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        HistoryItem item = data.get(position);

        h.title.setText(item.getTitle());
        h.success.setText(String.valueOf(item.getSuccess()));
        h.failed.setText(String.valueOf(item.getFailed()));
        h.subtitle.setText(item.getSubtitle());

        if (item.getStatus() == HistoryItem.Status.ONGOING) {
            // 进行中：蓝色云朵 + 淡蓝圆形底
            h.icon.setImageResource(R.drawable.ai_ic_cloud_upload_big);
            h.iconBg.setBackgroundResource(R.drawable.ai_bg_node_active);
            h.status.setText(R.string.ai_history_status_ongoing);
            h.status.setTextColor(0xFF4A90E2);
            h.subtitle.setTextColor(0xFFFF8A33);
            h.actionBtn.setText(R.string.ai_history_btn_continue);
        } else {
            // 已完成：绿色勾号 + 绿色圆形底
            h.icon.setImageResource(R.drawable.ai_ic_check_big);
            h.iconBg.setBackgroundResource(R.drawable.ai_bg_node_done);
            // 给已完成的图标底加绿色 (复用 ai_bg_node_active, 用 tint 着色)
            h.iconBg.getBackground().setTint(0xCC1FBA82);
            h.status.setText(R.string.ai_history_status_finished);
            h.status.setTextColor(0xFF7B8597);
            h.subtitle.setTextColor(0xFF7B8597);
            h.actionBtn.setText(R.string.ai_history_btn_view);
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View iconBg;
        final ImageView icon;
        final TextView title;
        final TextView success;
        final TextView failed;
        final TextView status;
        final TextView subtitle;
        final TextView actionBtn;

        VH(@NonNull View itemView) {
            super(itemView);
            iconBg = itemView.findViewById(R.id.historyIconBg);
            icon = itemView.findViewById(R.id.historyIcon);
            title = itemView.findViewById(R.id.historyTitle);
            success = itemView.findViewById(R.id.historySuccess);
            failed = itemView.findViewById(R.id.historyFailed);
            status = itemView.findViewById(R.id.historyStatus);
            subtitle = itemView.findViewById(R.id.historySubtitle);
            actionBtn = itemView.findViewById(R.id.historyActionBtn);
        }
    }
}
