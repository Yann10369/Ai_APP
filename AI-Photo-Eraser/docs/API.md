# 项目五：照片智能擦除小助手 — 接口文档

> Base URL：`https://api.your-domain.com/v1`
> 协议：HTTPS + JSON / multipart/form-data
> 鉴权：JWT Bearer Token
> 字符编码：UTF-8
> 文档版本：v1.0  |  更新日期：2026-08-08

---

## 0. 通用约定

### 0.1 通用 Header
| Header | 必填 | 说明 |
|--------|------|------|
| Authorization | 是 | `Bearer {token}`（匿名端点除外） |
| X-Platform | 否 | `android` / `ios` / `web` |
| X-App-Version | 否 | 客户端版本号 |

### 0.2 统一响应格式
```json
{
  "code": 0,
  "msg": "ok",
  "data": { ... },
  "trace_id": "abc123def456"
}
```

### 0.3 错误码表
| code | 含义 |
|------|------|
| 0 | 成功 |
| 400 | 请求参数错误（图片/蒙版格式错误、尺寸不一致） |
| 401 | 未登录 / Token 失效 |
| 413 | 文件过大（> 10MB） |
| 415 | 文件类型不支持 |
| 422 | 蒙版与原图无法对齐 |
| 429 | 请求频率超限 |
| 500 | 服务器内部错误 |
| 503 | AI 模型暂时不可用 |
| 504 | AI 调用超时 |

### 0.4 隐私原则（重要）
- ❌ **服务端不持久化任何用户原图与蒙版**
- ✅ 临时文件 1 小时后自动清理（每 30 分钟扫描）
- ✅ 服务重启时强制清空临时目录
- ✅ 数据库仅保存"操作日志"，不保存图像内容
- ✅ API Key 仅存放于服务端 `.env`

---

## 1. 用户模块（轻量，可选）

### 1.1 匿名设备登录
**POST** `/user/anonymous`

请求体：
```json
{
  "device_id": "ANDROID_abc123def456"
}
```

响应：
```json
{
  "code": 0,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "user_id": "user_anonymous_5f8a",
    "expires_in": 2592000
  }
}
```

> 本项目可选用匿名模式：用户无需注册也能使用，便于降低使用门槛。

---

## 2. 图片上传模块

### 2.1 上传原图
**POST** `/image/upload`
Content-Type：`multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | binary | 是 | 原图二进制，≤ 10MB |
| type | string | 否 | `origin` / `mask`，默认 `origin` |

支持的格式：`image/jpeg`、`image/png`、`image/webp`

响应：
```json
{
  "code": 0,
  "data": {
    "file_id": "f_20260808_a1b2c3",
    "url": "https://cdn.example.com/temp/.../f_20260808_a1b2c3.jpg",
    "width": 1920,
    "height": 1080,
    "size_kb": 1842,
    "expire_at": "2026-08-08T17:30:00Z"
  }
}
```

> ⚠️ 返回的 URL 是临时地址，1 小时后失效。

---

## 3. 擦除核心模块

### 3.1 提交擦除任务（同步）
**POST** `/erase/sync`
Content-Type：`multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| image | binary | 是 | 原图 |
| mask | binary | 是 | 蒙版 PNG，白色=待擦除，黑色=保留 |
| scene | string | 否 | `person` / `pet` / `object` / `text` / `watermark`，默认 `object` |
| quality | string | 否 | `standard` / `hd`，默认 `standard` |
| mask_blur | int | 否 | 边缘羽化像素数，0~20，默认 5 |

响应（耗时 3~15s）：
```json
{
  "code": 0,
  "data": {
    "task_id": "task_20260808_d4e5f6",
    "status": "done",
    "result": {
      "image_id": "result_xxx",
      "url": "https://cdn.example.com/temp/.../result.jpg",
      "width": 1920,
      "height": 1080,
      "size_kb": 1620,
      "elapsed_ms": 7820,
      "cached": false
    }
  }
}
```

### 3.2 提交擦除任务（异步）
**POST** `/erase/async`
Content-Type：`multipart/form-data`

字段同上。

响应（立即返回任务 ID）：
```json
{
  "code": 0,
  "data": {
    "task_id": "task_20260808_g7h8i9",
    "status": "pending",
    "estimated_seconds": 12
  }
}
```

### 3.3 查询异步任务
**GET** `/erase/task/{task_id}`

响应：
```json
{
  "code": 0,
  "data": {
    "task_id": "task_xxx",
    "status": "done",            // pending / running / done / failed / cancelled
    "progress": 100,             // 0~100
    "result": {
      "image_id": "result_xxx",
      "url": "https://cdn.example.com/temp/.../result.jpg",
      "width": 1920,
      "height": 1080,
      "size_kb": 1620,
      "elapsed_ms": 7820
    },
    "error": null
  }
}
```

### 3.4 取消任务
**POST** `/erase/task/{task_id}/cancel`

响应：
```json
{
  "code": 0,
  "msg": "任务已取消"
}
```

### 3.5 局部重新涂抹（基于已有结果）
**POST** `/erase/refine`
Content-Type：`multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| base_image_id | string | 是 | 上一次的 result.image_id |
| mask | binary | 是 | 新的蒙版 |
| scene | string | 否 | 同 3.1 |
| quality | string | 否 | `standard` / `hd` |

响应：同 3.1。

---

## 4. Prompt 场景接口

### 4.1 获取场景列表
**GET** `/erase/scenes`

响应：
```json
{
  "code": 0,
  "data": {
    "scenes": [
      {"key": "person",   "name": "擦除人物", "icon": "ic_person"},
      {"key": "pet",      "name": "擦除宠物", "icon": "ic_pet"},
      {"key": "object",   "name": "擦除杂物", "icon": "ic_object"},
      {"key": "text",     "name": "擦除文字", "icon": "ic_text"},
      {"key": "watermark","name": "擦除水印", "icon": "ic_watermark"}
    ]
  }
}
```

### 4.2 大模型辅助生成蒙版（可选）
**POST** `/mask/auto-generate`
Content-Type：`multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| image | binary | 是 | 原图 |
| target | string | 是 | 自然语言描述，如"穿红衣服的男孩" |

响应：
```json
{
  "code": 0,
  "data": {
    "mask_url": "https://cdn.example.com/temp/.../mask.png",
    "bbox": {"x": 320, "y": 180, "w": 240, "h": 410},
    "confidence": 0.94
  }
}
```

> 该接口调用多模态大模型自动识别并生成蒙版，便于不擅长涂抹的用户。

---

## 5. 历史记录模块（本地为主，可选云端）

> 历史记录**默认存储在客户端本地**（Room/SQLite），仅在用户开启云端备份时上传元数据。

### 5.1 同步历史记录
**POST** `/history/sync`

请求体：
```json
{
  "records": [
    {
      "local_id": "h_local_001",
      "origin_hash": "sha256:abc...",
      "result_hash": "sha256:def...",
      "scene": "person",
      "quality": "standard",
      "created_at": "2026-08-08T15:30:00Z"
    }
  ],
  "delete_local_ids": ["h_local_099"]
}
```

### 5.2 获取历史列表
**GET** `/history/list?page=1&page_size=20`

> 注意：本接口**只返回元数据**，不返回图像内容，图像仍在本地。

响应：
```json
{
  "code": 0,
  "data": {
    "total": 32,
    "page": 1,
    "list": [
      {
        "record_id": "h_xxx",
        "scene": "person",
        "created_at": "2026-08-08T15:30:00Z"
      }
    ]
  }
}
```

---

## 6. 系统状态模块

### 6.1 健康检查
**GET** `/health`

响应：
```json
{
  "code": 0,
  "data": {
    "status": "ok",
    "model_backend": "lama",
    "model_version": "1.0.0",
    "queue_size": 2,
    "cache_hit_rate": 0.18
  }
}
```

### 6.2 模型配置查询
**GET** `/config/model`

响应：
```json
{
  "code": 0,
  "data": {
    "backend": "lama",
    "max_image_size": 4096,
    "supported_formats": ["jpeg", "png", "webp"],
    "max_file_mb": 10,
    "scenes": ["person", "pet", "object", "text", "watermark"]
  }
}
```

---

## 7. 附录

### 7.1 限流策略
| 接口 | 限流 |
|------|------|
| `/erase/sync` | 30 次/小时/IP |
| `/erase/async` | 60 次/小时/IP |
| `/image/upload` | 100 次/小时/IP |
| `/mask/auto-generate` | 20 次/小时/IP |

### 7.2 蒙版生成规则
- **颜色**：白色（255,255,255）= 待擦除，黑色（0,0,0）= 保留
- **格式**：必须 PNG（无损，便于边缘羽化）
- **分辨率**：必须与原图**像素级一致**，否则返回 422

### 7.3 错误处理建议
| 场景 | 客户端处理 |
|------|-----------|
| 413 | 提示用户"图片过大，请压缩后再试" |
| 422 | 提示"涂抹区域与原图不一致" |
| 429 | 提示"操作过于频繁，请稍后再试" |
| 503 | 引导重试或切换到离线模式 |
| 504 | 自动重试一次，仍失败则提示稍后重试 |

### 7.4 数据模型

**EraseTask**
```ts
{
  task_id: string;
  user_id: string;
  status: 'pending' | 'running' | 'done' | 'failed' | 'cancelled';
  progress: number;          // 0~100
  scene: string;
  quality: 'standard' | 'hd';
  input_image_id: string;
  input_mask_hash: string;
  result?: {
    image_id: string;
    url: string;
    width: number;
    height: number;
    size_kb: number;
    elapsed_ms: number;
  };
  error?: {
    code: string;
    message: string;
  };
  created_at: string;
  finished_at?: string;
}
```

**HistoryRecord**（本地）
```ts
{
  local_id: string;
  origin_path: string;       // 本地路径
  result_path: string;       // 本地路径
  origin_hash: string;
  result_hash: string;
  scene: string;
  quality: string;
  created_at: string;
}
```

### 7.5 隐私清理策略
| 时间点 | 动作 |
|--------|------|
| 服务启动时 | `rm -rf /tmp/eraser/*` |
| 每 30 分钟 | 删除 > 1h 的临时文件 |
| 任务完成 1h 后 | 自动清理对应输入/输出 |
| 用户主动退出 | 立即清理本次会话临时文件 |

### 7.6 SDK 集成示例（Android Kotlin）

```kotlin
// 1. 构建请求体
val requestBody = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart(
        "image", "photo.jpg",
        RequestBody.create(MediaType.parse("image/jpeg"), imageBytes)
    )
    .addFormDataPart(
        "mask", "mask.png",
        RequestBody.create(MediaType.parse("image/png"), maskBytes)
    )
    .addFormDataPart("scene", "person")
    .addFormDataPart("quality", "standard")
    .build()

// 2. 发起请求
val response = api.erase(requestBody).execute()

// 3. 解析结果
val result = response.body()!!.data.result
Glide.with(this).load(result.url).into(resultImageView)

// 4. 保存到相册
val saved = MediaStoreUtil.saveToAlbum(this, result.url)
```