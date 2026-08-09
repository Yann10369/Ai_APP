package com.ai_photo.ai;

/**
 * AI 任务历史项 (RecyclerView Item)
 *
 * 状态：ONGOING / FINISHED
 * 进行中：左侧蓝色云朵图标 + 蓝色状态文字 + 继续分析按钮
 * 已完成：左侧绿色勾号图标 + 灰色状态文字 + 查看按钮
 */
public class HistoryItem {

    public enum Status {
        ONGOING, FINISHED
    }

    private final String title;
    private final int success;
    private final int failed;
    private final Status status;
    private final String subtitle;

    public HistoryItem(String title, int success, int failed, Status status, String subtitle) {
        this.title = title;
        this.success = success;
        this.failed = failed;
        this.status = status;
        this.subtitle = subtitle;
    }

    public String getTitle() {
        return title;
    }

    public int getSuccess() {
        return success;
    }

    public int getFailed() {
        return failed;
    }

    public Status getStatus() {
        return status;
    }

    public String getSubtitle() {
        return subtitle;
    }
}
