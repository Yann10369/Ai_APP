-- 003_ai_task_worker.sql
-- AI 任务队列：加 retry / 抢占 / 心跳字段，支持多 worker 协同 + 启动恢复。
-- 用 ALTER TABLE 兼容已有库；新字段都带 DEFAULT，老行安全。

ALTER TABLE ai_tasks ADD COLUMN retry_count    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_tasks ADD COLUMN max_retries    INTEGER NOT NULL DEFAULT 3;
ALTER TABLE ai_tasks ADD COLUMN next_retry_at  TEXT;
ALTER TABLE ai_tasks ADD COLUMN claimed_at     TEXT;
ALTER TABLE ai_tasks ADD COLUMN claimed_by     TEXT;
ALTER TABLE ai_tasks ADD COLUMN heartbeat_at   TEXT;
ALTER TABLE ai_tasks ADD COLUMN finished_at    TEXT;

-- worker 拉取：状态 + 创建时间
CREATE INDEX IF NOT EXISTS idx_ai_tasks_status_created
  ON ai_tasks (status, created_at);

-- 找超时 processing（worker 崩溃后恢复用）
CREATE INDEX IF NOT EXISTS idx_ai_tasks_status_claimed_at
  ON ai_tasks (status, claimed_at);
