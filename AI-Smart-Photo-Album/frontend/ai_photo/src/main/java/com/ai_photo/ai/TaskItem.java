package com.ai_photo.ai;

/**
 * AI 单体任务项 (RecyclerView Item)
 *
 * 状态：ANALYZING / FAILED / DONE
 */
public class TaskItem {

    public enum Status {
        ANALYZING, FAILED, DONE
    }

    private final String title;
    private final String desc;
    private final Status status;

    public TaskItem(String title, String desc, Status status) {
        this.title = title;
        this.desc = desc;
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public String getDesc() {
        return desc;
    }

    public Status getStatus() {
        return status;
    }
}
