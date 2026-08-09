-- SQLite schema for AI 智能相册
-- 注：SQLite 不支持 BIGINT UNSIGNED / ENUM / ON UPDATE CURRENT_TIMESTAMP / COMMENT / KEY。
--     全部用 INTEGER / TEXT + CHECK / DEFAULT CURRENT_TIMESTAMP。

CREATE TABLE IF NOT EXISTS users (
  user_id        INTEGER PRIMARY KEY AUTOINCREMENT,
  username       TEXT NOT NULL UNIQUE,
  password_hash  TEXT NOT NULL,
  email          TEXT UNIQUE,
  avatar_url     TEXT,
  status         INTEGER NOT NULL DEFAULT 1,
  last_login_at  TEXT,
  created_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS photos (
  photo_id        INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id         INTEGER NOT NULL,
  file_name       TEXT NOT NULL,
  file_hash       TEXT NOT NULL,
  file_size       INTEGER NOT NULL,
  original_path   TEXT NOT NULL,
  thumbnail_path  TEXT,
  width           INTEGER,
  height          INTEGER,
  shot_at         TEXT,
  analysis_status TEXT NOT NULL DEFAULT 'pending'
                    CHECK (analysis_status IN ('pending','processing','done','failed')),
  created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at      TEXT,
  UNIQUE (user_id, file_hash),
  FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_photos_user_created
  ON photos (user_id, deleted_at, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_photos_user_shot_at
  ON photos (user_id, deleted_at, shot_at DESC);
CREATE INDEX IF NOT EXISTS idx_photos_user_status
  ON photos (user_id, analysis_status);

CREATE TABLE IF NOT EXISTS categories (
  category_id   INTEGER PRIMARY KEY AUTOINCREMENT,
  type          TEXT NOT NULL
                  CHECK (type IN ('scene','emotion','tag')),
  name          TEXT NOT NULL,
  icon_url      TEXT,
  sort_order    INTEGER NOT NULL DEFAULT 0,
  is_enabled    INTEGER NOT NULL DEFAULT 1,
  created_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (type, name)
);

CREATE INDEX IF NOT EXISTS idx_categories_type_sort
  ON categories (type, sort_order);

CREATE TABLE IF NOT EXISTS photo_ai_analysis (
  photo_id        INTEGER PRIMARY KEY,
  description     TEXT,
  dominant_scene_id     INTEGER,
  scene_confidence      REAL
                         CHECK (scene_confidence IS NULL OR (scene_confidence >= 0 AND scene_confidence <= 1)),
  dominant_emotion_id   INTEGER,
  emotion_confidence    REAL
                         CHECK (emotion_confidence IS NULL OR (emotion_confidence >= 0 AND emotion_confidence <= 1)),
  analyzed_at     TEXT,
  FOREIGN KEY (photo_id)            REFERENCES photos(photo_id) ON DELETE CASCADE,
  FOREIGN KEY (dominant_scene_id)   REFERENCES categories(category_id),
  FOREIGN KEY (dominant_emotion_id) REFERENCES categories(category_id)
);

CREATE TABLE IF NOT EXISTS photo_categories (
  photo_id       INTEGER NOT NULL,
  category_id    INTEGER NOT NULL,
  confidence     REAL NOT NULL DEFAULT 1.0
                   CHECK (confidence >= 0 AND confidence <= 1),
  source         TEXT NOT NULL DEFAULT 'ai'
                   CHECK (source IN ('ai','user')),
  is_primary     INTEGER NOT NULL DEFAULT 0,
  created_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (photo_id, category_id),
  FOREIGN KEY (photo_id)    REFERENCES photos(photo_id) ON DELETE CASCADE,
  FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_photo_categories_category_photo
  ON photo_categories (category_id, photo_id);

CREATE TABLE IF NOT EXISTS ai_tasks (
  task_id        INTEGER PRIMARY KEY AUTOINCREMENT,
  photo_id       INTEGER NOT NULL,
  status         TEXT NOT NULL DEFAULT 'queued'
                   CHECK (status IN ('queued','processing','succeeded','failed')),
  error_message  TEXT,
  created_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (photo_id) REFERENCES photos(photo_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS favorites (
  user_id      INTEGER NOT NULL,
  photo_id     INTEGER NOT NULL,
  created_at   TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, photo_id),
  FOREIGN KEY (user_id)  REFERENCES users(user_id)  ON DELETE CASCADE,
  FOREIGN KEY (photo_id) REFERENCES photos(photo_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_favorites_user_created
  ON favorites (user_id, created_at DESC);
