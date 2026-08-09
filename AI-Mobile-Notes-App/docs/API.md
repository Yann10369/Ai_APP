# 项目四：AI 随身图文笔记助手 — 接口文档

> Base URL：`https://api.your-domain.com/v1`
> 协议：HTTPS + JSON
> 鉴权：JWT Bearer Token（除登录/注册外，所有接口需 Header `Authorization: Bearer <token>`）
> 字符编码：UTF-8
> 文档版本：v1.0  |  更新日期：2026-08-08

---

## 0. 通用约定

### 0.1 通用 Header
| Header | 必填 | 说明 |
|--------|------|------|
| Authorization | 是 | `Bearer {token}` |
| Content-Type | 是 | `application/json` 或 `multipart/form-data` |
| X-Platform | 否 | `android` / `ios` / `web` |
| X-App-Version | 否 | 客户端版本号，如 `1.0.0` |

### 0.2 统一响应格式
```json
{
  "code": 0,
  "msg": "ok",
  "data": { ... },
  "trace_id": "abc123def456"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 0=成功，非 0 见错误码表 |
| msg | string | 提示信息 |
| data | object/null | 业务数据 |
| trace_id | string | 服务端追踪 ID |

### 0.3 错误码表
| code | 含义 |
|------|------|
| 0 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未登录 / Token 失效 |
| 403 | 无权限访问 |
| 404 | 资源不存在 |
| 413 | 文件过大 |
| 429 | 请求频率超限 |
| 500 | 服务器内部错误 |
| 503 | AI 服务暂时不可用 |

---

## 1. 用户模块

### 1.1 发送验证码
**POST** `/user/sms/send`

请求体：
```json
{
  "phone": "13800138000",
  "type": "login"  // login / register / reset
}
```

响应：
```json
{
  "code": 0,
  "msg": "验证码已发送",
  "data": {
    "expire_seconds": 300
  }
}
```

### 1.2 手机号登录/注册
**POST** `/user/login`

请求体：
```json
{
  "phone": "13800138000",
  "code": "654321"
}
```

响应：
```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "user": {
      "id": 10001,
      "nickname": "用户_5f8a",
      "avatar": "https://cdn.example.com/u/10001.png",
      "is_new": true
    },
    "expires_in": 2592000
  }
}
```

### 1.3 获取当前用户信息
**GET** `/user/me`

响应：
```json
{
  "code": 0,
  "data": {
    "id": 10001,
    "phone": "138****8000",
    "nickname": "学习达人",
    "avatar": "https://cdn.example.com/u/10001.png",
    "created_at": "2026-07-01T10:00:00Z"
  }
}
```

### 1.4 修改用户信息
**PATCH** `/user/me`

请求体：
```json
{
  "nickname": "新昵称",
  "avatar_base64": "data:image/png;base64,..."
}
```

### 1.5 退出登录
**POST** `/user/logout`

---

## 2. 图片上传模块

### 2.1 上传单张图片
**POST** `/image/upload`
Content-Type：`multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | binary | 是 | 图片二进制，≤ 10MB |
| compress | bool | 否 | 默认 true，自动压缩到 1080p |

响应：
```json
{
  "code": 0,
  "data": {
    "image_id": "img_20260808_a1b2c3",
    "url": "https://cdn.example.com/img/20260808/a1b2c3.jpg",
    "width": 1920,
    "height": 1080,
    "size_kb": 824
  }
}
```

### 2.2 批量上传图片
**POST** `/image/upload/batch`
Content-Type：`multipart/form-data`，字段名 `files`

响应：
```json
{
  "code": 0,
  "data": {
    "images": [
      {"image_id": "img_xxx1", "url": "...", "width": 1920, "height": 1080},
      {"image_id": "img_xxx2", "url": "...", "width": 1080, "height": 1920}
    ]
  }
}
```

### 2.3 删除图片
**DELETE** `/image/{image_id}`

---

## 3. 笔记模块

### 3.1 创建笔记
**POST** `/note/create`

请求体：
```json
{
  "title": "高数极限章节总结",
  "content": "# 极限\n\n本节课讲了...",
  "content_format": "markdown",
  "category": "study",
  "tags": ["高数", "极限", "复习"],
  "image_ids": ["img_xxx1", "img_xxx2"],
  "is_top": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| category | string | 是 | `study` / `work` / `life` |
| content_format | string | 否 | `markdown` / `html` / `plain`，默认 `markdown` |

响应：
```json
{
  "code": 0,
  "data": {
    "note_id": "note_20260808_d4e5f6",
    "created_at": "2026-08-08T15:30:00Z"
  }
}
```

### 3.2 查询笔记详情
**GET** `/note/{note_id}`

响应：
```json
{
  "code": 0,
  "data": {
    "note_id": "note_xxx",
    "title": "高数极限章节总结",
    "content": "...",
    "category": "study",
    "tags": ["高数", "极限"],
    "images": [
      {"image_id": "img_xxx1", "url": "..."}
    ],
    "ai_summary": "本笔记讲解了极限的定义与基本运算...",
    "ai_questions": [...],
    "created_at": "2026-08-08T15:30:00Z",
    "updated_at": "2026-08-08T15:30:00Z"
  }
}
```

### 3.3 更新笔记
**PUT** `/note/{note_id}`

请求体（任选字段）：
```json
{
  "title": "新标题",
  "content": "新内容",
  "category": "work",
  "tags": ["新标签"],
  "is_top": true
}
```

### 3.4 删除笔记
**DELETE** `/note/{note_id}`

软删除，30 天内可调用 `/note/{note_id}/restore` 恢复。

### 3.5 分页查询笔记列表
**GET** `/note/list`

Query 参数：
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| category | string | 否 | `study` / `work` / `life` |
| tag | string | 否 | 单标签过滤 |
| keyword | string | 否 | 标题/正文模糊匹配 |
| start_date | string | 否 | ISO8601 |
| end_date | string | 否 | ISO8601 |
| page | int | 否 | 默认 1 |
| page_size | int | 否 | 默认 20，≤ 100 |
| order_by | string | 否 | `created_desc` / `updated_desc` |

响应：
```json
{
  "code": 0,
  "data": {
    "total": 128,
    "page": 1,
    "page_size": 20,
    "list": [
      {
        "note_id": "note_xxx",
        "title": "...",
        "category": "study",
        "cover": "https://cdn.../cover.jpg",
        "summary": "AI 摘要前 80 字",
        "tags": ["..."],
        "created_at": "2026-08-08T15:30:00Z"
      }
    ]
  }
}
```

### 3.6 语义搜索
**POST** `/note/search`

请求体：
```json
{
  "query": "上周英语错题",
  "top_k": 10,
  "filters": {
    "category": "study",
    "date_range": ["2026-07-01", "2026-08-08"]
  }
}
```

响应：
```json
{
  "code": 0,
  "data": {
    "results": [
      {
        "note_id": "note_xxx",
        "title": "英语完形填空错题",
        "score": 0.92,
        "highlights": "...错题集中在<em>时态</em>与<em>语态</em>..."
      }
    ]
  }
}
```

### 3.7 导出笔记
**GET** `/note/{note_id}/export?format=pdf`

Query：
| 参数 | 类型 | 说明 |
|------|------|------|
| format | string | `pdf` / `markdown` |

响应：二进制流，`Content-Disposition: attachment; filename=note_xxx.pdf`

---

## 4. AI 调用模块

### 4.1 图片解析（异步）
**POST** `/ai/image/parse`

请求体：
```json
{
  "image_ids": ["img_xxx1", "img_xxx2"],
  "features": ["ocr", "summary", "knowledge", "questions"]
}
```

| features | 说明 |
|----------|------|
| ocr | 图文识别 |
| summary | 内容摘要 |
| knowledge | 知识点提炼 |
| questions | 自动出题 |

响应（立即返回任务 ID）：
```json
{
  "code": 0,
  "data": {
    "task_id": "task_20260808_g7h8i9",
    "estimated_seconds": 8
  }
}
```

### 4.2 查询异步任务结果
**GET** `/ai/task/{task_id}`

响应：
```json
{
  "code": 0,
  "data": {
    "task_id": "task_xxx",
    "status": "done",          // pending / running / done / failed
    "progress": 100,            // 0~100
    "result": {
      "ocr_text": "...",
      "knowledge_points": [
        {"title": "极限定义", "content": "..."}
      ],
      "summary": "本节课讲解...",
      "questions": [
        {
          "type": "choice",
          "question": "下列关于极限的描述正确的是？",
          "options": ["A", "B", "C", "D"],
          "answer": "A",
          "explanation": "因为..."
        }
      ]
    },
    "error": null
  }
}
```

### 4.3 文本润色
**POST** `/ai/text/polish`

请求体：
```json
{
  "text": "今天上课老师讲了极限，我觉得很难。",
  "mode": "polish",  // polish / expand / shorten / formal
  "max_length": 500
}
```

响应：
```json
{
  "code": 0,
  "data": {
    "result": "在本次课堂中，老师系统性地讲解了极限的相关概念，内容具有一定的挑战性。",
    "diff": {
      "added_chars": 23,
      "removed_chars": 8
    }
  }
}
```

### 4.4 中英互译
**POST** `/ai/text/translate`

请求体：
```json
{
  "text": "本节课讲解了极限的定义与基本运算。",
  "src_lang": "zh",
  "tgt_lang": "en"
}
```

响应：
```json
{
  "code": 0,
  "data": {
    "result": "This lesson explains the definition and basic operations of limits.",
    "src_lang": "zh",
    "tgt_lang": "en"
  }
}
```

### 4.5 思维导图生成
**POST** `/ai/text/mindmap`

请求体：
```json
{
  "text": "极限章节内容...",
  "format": "markdown"  // markdown / json
}
```

响应（markdown 格式）：
```json
{
  "code": 0,
  "data": {
    "mindmap": "# 极限\n  ## 定义\n    - ε-δ 定义\n    - 序列极限\n  ## 运算\n    - 四则运算\n    - 复合运算"
  }
}
```

### 4.6 流式输出（SSE）
**POST** `/ai/text/stream`
Content-Type：`text/event-stream`

请求体同 4.3 / 4.4 / 4.5。

响应：标准 SSE 事件流，每个 `data:` 行为一个 JSON：
```
data: {"delta": "今"}
data: {"delta": "天"}
data: {"delta": "上课"}
...
data: {"done": true}
```

---

## 5. 分类与标签模块

### 5.1 获取分类列表
**GET** `/category/list`

响应：
```json
{
  "code": 0,
  "data": {
    "categories": [
      {"key": "study", "name": "学习", "icon": "ic_study", "count": 56},
      {"key": "work", "name": "工作", "icon": "ic_work", "count": 12},
      {"key": "life", "name": "生活", "icon": "ic_life", "count": 8}
    ]
  }
}
```

### 5.2 标签联想
**GET** `/tag/suggest?keyword=数`

响应：
```json
{
  "code": 0,
  "data": {
    "tags": ["数学", "数字电路", "数据结构"]
  }
}
```

---

## 6. 统计模块

### 6.1 用户笔记统计
**GET** `/stats/overview`

响应：
```json
{
  "code": 0,
  "data": {
    "total_notes": 76,
    "total_images": 312,
    "by_category": {"study": 56, "work": 12, "life": 8},
    "this_month_created": 15,
    "ai_calls_this_month": 42
  }
}
```

---

## 7. 附录

### 7.1 限流策略
| 接口 | 限流 |
|------|------|
| `/user/sms/send` | 1 次/分钟，5 次/天 |
| `/ai/image/parse` | 30 次/小时 |
| `/ai/text/*` | 60 次/分钟 |
| 其他 | 120 次/分钟 |

### 7.2 媒体资源 URL
所有返回的图片 URL 有效期 7 天，前端需在过期前重新请求。

### 7.3 数据模型

**Note**
```ts
{
  note_id: string;          // 主键
  user_id: number;
  title: string;
  content: string;
  content_format: 'markdown' | 'html' | 'plain';
  category: 'study' | 'work' | 'life';
  tags: string[];
  image_ids: string[];
  ai_summary?: string;
  ai_questions?: Question[];
  is_top: boolean;
  is_deleted: boolean;
  created_at: string;       // ISO8601
  updated_at: string;
}
```

**Image**
```ts
{
  image_id: string;
  user_id: number;
  url: string;
  width: number;
  height: number;
  size_kb: number;
  created_at: string;
}
```

**Question**
```ts
{
  type: 'choice' | 'fill' | 'short_answer';
  question: string;
  options?: string[];       // 选择题使用
  answer: string;
  explanation: string;
}
```