package com.ai_photo.ai;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ai_photo.R;

import java.util.List;

/**
 * 单体任务列表 Adapter
 *
 * 状态颜色根据 Status 切换：
 * - ANALYZING → 淡蓝底 + 蓝字
 * - FAILED    → 淡红底 + 红字
 * - DONE      → 淡绿底 + 绿字
 */
public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.VH> {

    private final List<TaskItem> data;

    public TaskAdapter(List<TaskItem> data) {
        this.data = data;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ai_task, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        TaskItem item = data.get(position);
        h.title.setText(item.getTitle());
        h.desc.setText(item.getDesc());

        switch (item.getStatus()) {
            case ANALYZING:
                h.status.setText(R.string.ai_task_status_analyzing);
                h.status.setBackgroundResource(R.drawable.ai_bg_status_blue);
                h.status.setTextColor(0xFF4A90E2);
                break;
            case FAILED:
                h.status.setText(R.string.ai_task_status_failed);
                h.status.setBackgroundResource(R.drawable.ai_bg_status_red);
                h.status.setTextColor(0xFFE02020);
                break;
            case DONE:
                h.status.setText(R.string.ai_task_status_done);
                h.status.setBackgroundResource(R.drawable.ai_bg_status_green);
                h.status.setTextColor(0xFF1FBA82);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView desc;
        final TextView status;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.taskTitle);
            desc = itemView.findViewById(R.id.taskDesc);
            status = itemView.findViewById(R.id.taskStatus);
        }
    }
}
