# AI 智能相册 接口设计文档

> 项目类型：本地 AI 分类分析照片项目
> 文档版本：v1.1
> 更新日期：2026-06-18

---

## 零、初始分类集合

> 系统初始化时默认写入 `categories` 表的分类字典，共 **60 个**（场景 20 + 情绪 20 + 标签 20）。
> 用户可在「设置页」通过分类管理接口进行增删改查与重置操作。

### 0.1 场景（scene）— 20 个

| category_id | name | 英文 |
|-------------|------|------|
| 1 | 🏖️ 海滩 | Beach |
| 2 | 🏙️ 城市 | City |
| 3 | 🏠 室内 | Indoor |
| 4 | ⛰️ 山景 | Mountain |
| 5 | 🌲 森林 | Forest |
| 6 | 🌾 草原 | Grassland |
| 7 | 🏜️ 沙漠 | Desert |
| 8 | ❄️ 雪景 | Snow |
| 9 | 🏞️ 湖泊 | Lake |
| 10 | 🌊 河流 | River |
| 11 | 🏡 乡村 | Countryside |
| 12 | 🛣️ 街景 | Street |
| 13 | 🌃 夜景 | Night |
| 14 | 🏞️ 公园 | Park |
| 15 | 🌷 花园 | Garden |
| 16 | 🏯 古镇 | Ancient Town |
| 17 | 🏝️ 海岛 | Island |
| 18 | ⚓ 码头 | Dock |
| 19 | 🎓 校园 | Campus |
| 20 | 🍽️ 餐厅 | Restaurant |

### 0.2 情绪（emotion）— 20 个

| category_id | name | 英文 |
|-------------|------|------|
| 21 | 😄 快乐 | Happy |
| 22 | 😌 平静 | Calm |
| 23 | 😢 忧伤 | Sad |
| 24 | 🤩 兴奋 | Excited |
| 25 | 🥰 温馨 | Warm |
| 26 | 😔 孤独 | Lonely |
| 27 | 💕 浪漫 | Romantic |
| 28 | 🥹 怀旧 | Nostalgic |
| 29 | 💚 治愈 | Healing |
| 30 | 🌿 清新 | Fresh |
| 31 | 🥲 感动 | Touched |
| 32 | 😲 惊喜 | Surprised |
| 33 | 🕊️ 宁静 | Peaceful |
| 34 | 🌧️ 忧郁 | Melancholy |
| 35 | 😎 放松 | Relaxed |
| 36 | ⚡ 活力 | Energetic |
| 37 | ☕ 惬意 | Cozy |
| 38 | 🌅 期待 | Hopeful |
| 39 | 🤔 沉思 | Pensive |
| 40 | 😊 愉悦 | Joyful |

### 0.3 标签（tag）— 20 个

| category_id | name | 英文 |
|-------------|------|------|
| 41 | 👤 人物 | Person |
| 42 | 🏞️ 风景 | Scenery |
| 43 | 🐾 动物 | Animal |
| 44 | 🍜 美食 | Food |
| 45 | 🏛️ 建筑 | Architecture |
| 46 | 🌿 植物 | Plant |
| 47 | 🌸 花卉 | Flower |
| 48 | 🐱 宠物 | Pet |
| 49 | 👶 孩童 | Child |
| 50 | 👴 老人 | Elder |
| 51 | 💑 情侣 | Couple |
| 52 | 👫 朋友 | Friend |
| 53 | 👨‍👩‍👧 家庭 | Family |
| 54 | 🤳 自拍 | Selfie |
| 55 | 📸 合影 | Group |
| 56 | ✈️ 旅行 | Travel |
| 57 | 🎉 节日 | Festival |
| 58 | ⚽ 运动 | Sports |
| 59 | 🎨 艺术 | Art |
| 60 | 📷 街拍 | Street Snap |

---

## 一、通用约定

### 1.1 统一响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

### 1.2 分页响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "list": [],
    "total": 100,
    "page": 1,
    "pageSize": 20
  }
}
```

### 1.3 错误码约定

| Code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未登录 / Token 失效 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器错误 |

### 1.4 鉴权方式

请求头携带：`Authorization: Bearer {token}`

---

## 二、接口汇总表

| 模块 | # | 接口名称 | 方法 | URL |
|------|---|---------|------|-----|
| **认证** | 1 | 用户注册 | POST | `/api/v1/auth/register` |
| | 2 | 用户登录 | POST | `/api/v1/auth/login` |
| | 3 | 用户登出 | POST | `/api/v1/auth/logout` |
| **用户** | 4 | 获取当前用户信息 | GET | `/api/v1/users/me` |
| | 5 | 获取用户统计数据 | GET | `/api/v1/users/me/statistics` |
| | 6 | 获取收藏列表 | GET | `/api/v1/users/me/favorites` |
| **照片** | 7 | 批量上传照片 | POST | `/api/v1/photos/upload` |
| | 8 | 获取照片列表 | GET | `/api/v1/photos` |
| | 9 | 获取最近照片 | GET | `/api/v1/photos/recent` |
| | 10 | 获取照片详情 | GET | `/api/v1/photos/{photoId}` |
| | 11 | 修改照片信息 | PATCH | `/api/v1/photos/{photoId}` |
| | 12 | 删除单张照片 | DELETE | `/api/v1/photos/{photoId}` |
| | 13 | 批量删除照片 | DELETE | `/api/v1/photos/batch` |
| | 14 | 收藏照片 | POST | `/api/v1/photos/{photoId}/favorite` |
| | 15 | 取消收藏 | DELETE | `/api/v1/photos/{photoId}/favorite` |
| | 16 | 搜索照片 | POST | `/api/v1/photos/search` |
| **分类** | 17 | 获取首页分类预览 | GET | `/api/v1/categories/preview` |
| | 18 | 获取分类列表 | GET | `/api/v1/categories` |
| | 19 | 获取分类下照片 | GET | `/api/v1/categories/{categoryId}/photos` |
| **AI** | 20 | 获取 AI 分析进度 | GET | `/api/v1/ai/status` |
| | 21 | 手动触发重新分析 | POST | `/api/v1/ai/reanalyze` |
| **分类管理** | 24 | 获取分类管理列表 | GET | `/api/v1/admin/categories` |
| | 25 | 添加分类 | POST | `/api/v1/admin/categories` |
| | 26 | 修改分类 | PATCH | `/api/v1/admin/categories/{categoryId}` |
| | 27 | 删除分类 | DELETE | `/api/v1/admin/categories/{categoryId}` |
| | 28 | 重置为初始分类集合 | POST | `/api/v1/admin/categories/reset` |

**共 26 个接口**

---

## 三、模块一：用户认证 Auth

### 1.1 用户注册

```
POST /api/v1/auth/register
```

**Request:**
```json
{
  "username": "alice",
  "password": "123456",
  "email": "alice@example.com"
}
```

**Response:**
```json
{
  "code": 200,
  "message": "注册成功",
  "data": {
    "userId": 1,
    "username": "alice"
  }
}
```

---

### 1.2 用户登录

```
POST /api/v1/auth/login
```

**Request:**
```json
{
  "username": "alice",
  "password": "123456"
}
```

**Response:**
```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "userId": 1,
    "username": "alice",
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 7200
  }
}
```

---

### 1.3 用户登出

```
POST /api/v1/auth/logout
Header: Authorization: Bearer {token}
```

**Response:**
```json
{
  "code": 200,
  "message": "登出成功",
  "data": null
}
```

---

## 四、模块二：用户信息 User

### 2.1 获取当前用户信息

```
GET /api/v1/users/me
```

**Response:**
```json
{
  "code": 200,
  "data": {
    "userId": 1,
    "username": "alice",
    "email": "alice@example.com",
    "avatarUrl": "/static/avatar/1.jpg",
    "createdAt": "2026-01-01T00:00:00Z"
  }
}
```

---

### 2.2 获取用户统计数据

```
GET /api/v1/users/me/statistics
```

**说明：** 照片总量、场景/情绪/标签三大类的占比分析

**Response:**
```json
{
  "code": 200,
  "data": {
    "totalPhotos": 1280,
    "analyzedPhotos": 1200,
    "favoriteCount": 56,
    "categoryDistribution": {
      "scene": [
        {"name": "海滩", "count": 25, "percentage": 0.02},
        {"name": "城市", "count": 100, "percentage": 0.08}
      ],
      "emotion": [
        {"name": "快乐", "count": 300, "percentage": 0.23},
        {"name": "平静", "count": 200, "percentage": 0.16}
      ],
      "tag": [
        {"name": "人物", "count": 500, "percentage": 0.39},
        {"name": "风景", "count": 300, "percentage": 0.23}
      ]
    }
  }
}
```

---

### 2.3 获取收藏列表

```
GET /api/v1/users/me/favorites?page=1&pageSize=20
```

**Response:**
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "photoId": 1,
        "thumbnailUrl": "/static/thumb/1.webp",
        "favoritedAt": "2026-06-10T10:00:00Z"
      }
    ],
    "total": 56,
    "page": 1,
    "pageSize": 20
  }
}
```

---

## 五、模块三：照片管理 Photos

### 3.1 批量上传照片

```
POST /api/v1/photos/upload
Content-Type: multipart/form-data
```

**Request:** `files[]`（支持多文件）

**Response:**
```json
{
  "code": 200,
  "data": {
    "successCount": 3,
    "failCount": 0,
    "uploadedPhotos": [
      {
        "photoId": 101,
        "originalName": "IMG_001.jpg",
        "thumbnailUrl": "/static/thumb/101.webp",
        "size": 2048576,
        "analysisStatus": "pending"
      }
    ],
    "failedFiles": []
  }
}
```

---

### 3.2 获取照片列表（时间倒序分页）

```
GET /api/v1/photos?page=1&pageSize=20
```

**说明：** 按照时间顺序每次返回 20 张略缩图

**Response:**
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "photoId": 101,
        "thumbnailUrl": "/static/thumb/101.webp",
        "width": 200,
        "height": 200,
        "createdAt": "2026-06-17T10:00:00Z",
        "isFavorite": false,
        "analysisStatus": "done"
      }
    ],
    "total": 1280,
    "page": 1,
    "pageSize": 20
  }
}
```

---

### 3.3 获取最近照片

```
GET /api/v1/photos/recent?limit=10
```

**说明：** 用于首页"最近照片"区域展示

**Response:**
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "photoId": 101,
        "thumbnailUrl": "/static/thumb/101.webp",
        "createdAt": "2026-06-17T10:00:00Z"
      }
    ]
  }
}
```

---

### 3.4 获取照片详情

```
GET /api/v1/photos/{photoId}
```

**说明：** 返回 AI 标签、AI 描述、场景识别、人物情绪等完整信息

**Response:**
```json
{
  "code": 200,
  "data": {
    "photoId": 101,
    "originalUrl": "/static/origin/101.jpg",
    "thumbnailUrl": "/static/thumb/101.webp",
    "metadata": {
      "fileName": "IMG_001.jpg",
      "size": 2048576,
      "width": 4032,
      "height": 3024,
      "shotAt": "2026-06-15T15:30:00Z"
    },
    "aiAnalysis": {
      "description": "在海滩边奔跑的孩童",
      "scene": {"name": "海滩", "confidence": 0.95},
      "emotion": {"name": "快乐", "confidence": 0.82},
      "tags": [
        {"name": "海滩", "confidence": 0.95},
        {"name": "孩童", "confidence": 0.88},
        {"name": "夏天", "confidence": 0.75}
      ]
    },
    "isFavorite": false,
    "createdAt": "2026-06-15T15:30:00Z"
  }
}
```

---

### 3.5 修改照片信息

```
PATCH /api/v1/photos/{photoId}
```

**Request:**
```json
{
  "tags": ["新标签1", "新标签2"],
  "description": "用户自定义描述"
}
```

**Response:**
```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

---

### 3.6 删除单张照片

```
DELETE /api/v1/photos/{photoId}
```

**Response:**
```json
{
  "code": 200,
  "message": "删除成功",
  "data": null
}
```

---

### 3.7 批量删除照片

```
DELETE /api/v1/photos/batch
```

**Request:**
```json
{
  "photoIds": [101, 102, 103]
}
```

**Response:**
```json
{
  "code": 200,
  "data": {
    "successCount": 3,
    "failCount": 0
  }
}
```

---

### 3.8 收藏照片

```
POST /api/v1/photos/{photoId}/favorite
```

**Response:**
```json
{
  "code": 200,
  "message": "已收藏",
  "data": null
}
```

---

### 3.9 取消收藏

```
DELETE /api/v1/photos/{photoId}/favorite
```

**Response:**
```json
{
  "code": 200,
  "message": "已取消收藏",
  "data": null
}
```

---

### 3.10 搜索照片

```
POST /api/v1/photos/search
```

**说明：** 后端逻辑：调用大模型从查询语句中提取标签 → 对标签进行搜索 → 返回照片 ID 和匹配标签

**Request:**
```json
{
  "query": "海边日落",
  "page": 1,
  "pageSize": 20
}
```

**Response:**
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "photoId": 101,
        "thumbnailUrl": "/static/thumb/101.webp",
        "matchedTags": ["海滩", "日落"],
        "score": 0.92
      }
    ],
    "total": 15,
    "page": 1,
    "pageSize": 20
  }
}
```

---

## 六、模块四：分类相册 Categories

### 4.1 获取首页分类预览

```
GET /api/v1/categories/preview?previewSize=4
```

**说明：** 从场景、情绪、标签三大类中各抽取分类，每个分类展示 N 张略缩图（用于首页智能相册区域）

**Response:**
```json
{
  "code": 200,
  "data": {
    "scene": [
      {
        "categoryId": 1,
        "categoryName": "海滩",
        "photoCount": 25,
        "previewPhotos": [
          {"photoId": 101, "thumbnailUrl": "/static/thumb/101.webp"},
          {"photoId": 102, "thumbnailUrl": "/static/thumb/102.webp"},
          {"photoId": 103, "thumbnailUrl": "/static/thumb/103.webp"},
          {"photoId": 104, "thumbnailUrl": "/static/thumb/104.webp"}
        ]
      }
    ],
    "emotion": [
      {
        "categoryId": 10,
        "categoryName": "快乐",
        "photoCount": 300,
        "previewPhotos": [
          {"photoId": 201, "thumbnailUrl": "/static/thumb/201.webp"}
        ]
      }
    ],
    "tag": [
      {
        "categoryId": 20,
        "categoryName": "人物",
        "photoCount": 500,
        "previewPhotos": [
          {"photoId": 301, "thumbnailUrl": "/static/thumb/301.webp"}
        ]
      }
    ]
  }
}
```

---

### 4.2 获取分类列表

```
GET /api/v1/categories?type=scene
```

**说明：** 根据类型返回完整的分类列表
**参数：** `type` 枚举：`scene`（场景）| `emotion`（情绪）| `tag`（标签）

**Response:**
```json
{
  "code": 200,
  "data": {
    "type": "scene",
    "list": [
      {
        "categoryId": 1,
        "categoryName": "海滩",
        "photoCount": 25,
        "coverThumbnail": "/static/thumb/101.webp"
      },
      {
        "categoryId": 2,
        "categoryName": "城市",
        "photoCount": 100,
        "coverThumbnail": "/static/thumb/201.webp"
      }
    ]
  }
}
```

---

### 4.3 获取分类下照片

```
GET /api/v1/categories/{categoryId}/photos?page=1&pageSize=20
```

**说明：** 时间顺序返回该分类下的照片略缩图

**Response:**
```json
{
  "code": 200,
  "data": {
    "categoryId": 1,
    "categoryName": "海滩",
    "list": [
      {
        "photoId": 101,
        "thumbnailUrl": "/static/thumb/101.webp",
        "createdAt": "2026-06-17T10:00:00Z"
      }
    ],
    "total": 25,
    "page": 1,
    "pageSize": 20
  }
}
```

---

## 七、模块五：AI 分析 AI

> 本模块仅保留 2 个核心接口：
> - `status` 用于展示分析进度
> - `reanalyze` 用于手动重新分析
>
> 待分析 / 已分析列表均可通过 `photos` 模块的接口加状态过滤获得，无需重复定义。

---

### 5.1 获取 AI 分析进度

```
GET /api/v1/ai/status
```

**说明：** 前端 1 秒轮询一次，用于展示分析进度

**Response:**
```json
{
  "code": 200,
  "data": {
    "total": 1280,
    "done": 1200,
    "pending": 80,
    "progress": 0.9375
  }
}
```

**字段说明**：
- `total`：照片总量
- `done`：已完成分析的照片数
- `pending`：待分析 / 正在分析的照片数（含 `pending` + `processing` 状态）
- `progress`：完成进度（`done / total`）

---

### 5.2 手动触发重新分析

```
POST /api/v1/ai/reanalyze
```

**说明：** 用户可手动重新分析某张或某批照片

**Request:**
```json
{
  "photoIds": [101, 102, 103]
}
```

**Response:**
```json
{
  "code": 200,
  "data": {
    "queuedCount": 3,
    "message": "已加入分析队列"
  }
}
```

---

## 八、模块六：分类管理 Categories Admin

> 位于「设置页」，供用户对分类字典进行增删改查、重置、批量导入等管理操作。
> 所有接口需鉴权（`Authorization: Bearer {token}`）。

---

### 6.1 获取分类管理列表

```
GET /api/v1/admin/categories?type=scene
```

**说明：** 管理后台拉取分类列表，可按类型过滤
**参数：**
- `type`（可选）：枚举 `scene` / `emotion` / `tag`，不传则返回全部

**Response:**
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "categoryId": 1,
        "type": "scene",
        "name": "海滩",
        "iconUrl": "/static/icon/scene_beach.png",
        "photoCount": 25,
        "createdAt": "2026-01-01T00:00:00Z"
      }
    ],
    "total": 20
  }
}
```

---

### 6.2 添加分类

```
POST /api/v1/admin/categories
```

**Request:**
```json
{
  "type": "tag",
  "name": "雨天",
  "iconUrl": "/static/icon/tag_rain.png"
}
```

**Response:**
```json
{
  "code": 200,
  "message": "添加成功",
  "data": {
    "categoryId": 61
  }
}
```

**校验规则：**
- 同一 `type` 下 `name` 不可重复
- `name` 长度 1~20

---

### 6.3 修改分类

```
PATCH /api/v1/admin/categories/{categoryId}
```

**Request:**
```json
{
  "name": "海边",
  "iconUrl": "/static/icon/scene_sea.png"
}
```

**Response:**
```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

---

### 6.4 删除分类

```
DELETE /api/v1/admin/categories/{categoryId}
```

**说明：** 删除分类前需校验该分类下无关联照片，否则返回错误

**Response:**
```json
{
  "code": 200,
  "message": "删除成功",
  "data": null
}
```

**异常场景：**
- 分类下仍有照片 → `code: 400`，提示"该分类下存在 X 张照片，请先移除关联"

---

### 6.5 重置为初始分类集合

```
POST /api/v1/admin/categories/reset
```

**说明：** 将分类字典恢复为「零、初始分类集合」中定义的 60 项；操作前会先清空现有自定义分类及关联关系（需二次确认）

**Request:**
```json
{
  "confirm": true
}
```

**Response:**
```json
{
  "code": 200,
  "message": "已重置为初始分类集合",
  "data": {
    "resetCount": 60,
    "removedCount": 3
  }
}
```

---

## 九、附录

### 8.1 字段命名规范

- 使用 **camelCase**（小驼峰）
- 时间字段统一使用 **ISO 8601 格式**：`2026-06-17T10:00:00Z`
- 文件大小单位：**字节（Byte）**
- 置信度 confidence：**0.0 ~ 1.0** 的浮点数
- 分页参数：`page` 从 1 开始，`pageSize` 默认 20

### 8.2 略缩图规范

- 格式：WebP
- 尺寸：200x200（首页/列表）、400x400（详情预览）
- 路径前缀：`/static/thumb/{photoId}.webp`

### 8.3 原图规范

- 路径前缀：`/static/origin/{photoId}.{ext}`
- 支持格式：JPG、PNG、HEIC、WebP

### 8.4 AI 分类的三大类型

| 类型 | type 值 | 说明 | 示例 |
|------|---------|------|------|
| 场景 | `scene` | 拍摄场景识别 | 海滩、城市、室内、山景 |
| 情绪 | `emotion` | 照片传达的情绪 | 快乐、平静、忧伤 |
| 标签 | `tag` | 物体/主题标签 | 人物、风景、动物、美食 |

---

## 十、数据库表设计

### 10.1 表汇总

| # | 表名 | 说明 |
|---|------|------|
| 1 | `users` | 用户表 |
| 2 | `photos` | 照片主表 |
| 3 | `photo_ai_analysis` | 照片 AI 分析摘要表（存主结果） |
| 4 | `categories` | 分类字典表（场景/情绪/标签） |
| 5 | `photo_categories` | 照片-分类关联表（多对多，存全部分类） |
| 6 | `ai_tasks` | AI 分析任务表（支持重试/锁/进度） |
| 7 | `favorites` | 收藏表（组合主键） |

---

### 10.2 表 1：`users` 用户表

```sql
CREATE TABLE users (
  user_id        BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',

  username       VARCHAR(50)  NOT NULL COMMENT '用户名',
  password_hash  VARCHAR(255) NOT NULL COMMENT '密码哈希',
  email          VARCHAR(100) NULL COMMENT '邮箱',
  avatar_url     VARCHAR(500) NULL COMMENT '头像URL',

  status         TINYINT NOT NULL DEFAULT 1 COMMENT '用户状态：1正常，0禁用',
  last_login_at  DATETIME(3) NULL COMMENT '最后登录时间',

  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_email (email),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
```

---

### 10.3 表 2：`photos` 照片主表

```sql
CREATE TABLE photos (
  photo_id        BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '照片ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID',

  file_name       VARCHAR(255) NOT NULL COMMENT '原始文件名',
  file_hash       CHAR(64) NOT NULL COMMENT '文件SHA256哈希',
  file_size       BIGINT UNSIGNED NOT NULL COMMENT '文件大小，单位字节',

  original_path   VARCHAR(500) NOT NULL COMMENT '原图存储路径或对象存储Key',
  thumbnail_path  VARCHAR(500) NULL COMMENT '缩略图存储路径或对象存储Key',

  width           INT UNSIGNED NULL COMMENT '图片宽度',
  height          INT UNSIGNED NULL COMMENT '图片高度',
  shot_at         DATETIME(3) NULL COMMENT '拍摄时间，来自EXIF',

  analysis_status ENUM('pending','processing','done','failed')
                  NOT NULL DEFAULT 'pending' COMMENT '最新AI分析状态',

  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '上传时间',
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  deleted_at      DATETIME(3) NULL COMMENT '软删除时间',

  UNIQUE KEY uk_user_file_hash (user_id, file_hash),

  KEY idx_user_created (user_id, deleted_at, created_at DESC),
  KEY idx_user_shot_at (user_id, deleted_at, shot_at DESC),
  KEY idx_user_status (user_id, analysis_status),

  CONSTRAINT fk_photos_user
    FOREIGN KEY (user_id) REFERENCES users(user_id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='照片主表';
```

---

### 10.4 表 3：`photo_ai_analysis` AI 分析摘要表

```sql
CREATE TABLE photo_ai_analysis (
  photo_id        BIGINT UNSIGNED PRIMARY KEY COMMENT '照片ID，同时作为主键',

  description     TEXT NULL COMMENT 'AI生成的图片描述',

  dominant_scene_id       BIGINT UNSIGNED NULL COMMENT '主场景分类ID',
  scene_confidence        DECIMAL(5,4) NULL COMMENT '主场景置信度',

  dominant_emotion_id     BIGINT UNSIGNED NULL COMMENT '主情绪分类ID',
  emotion_confidence      DECIMAL(5,4) NULL COMMENT '主情绪置信度',

  analyzed_at     DATETIME(3) NULL COMMENT '分析完成时间',

  CONSTRAINT fk_analysis_photo
    FOREIGN KEY (photo_id) REFERENCES photos(photo_id)
    ON DELETE CASCADE,

  CONSTRAINT fk_analysis_scene
    FOREIGN KEY (dominant_scene_id) REFERENCES categories(category_id),

  CONSTRAINT fk_analysis_emotion
    FOREIGN KEY (dominant_emotion_id) REFERENCES categories(category_id),

  CONSTRAINT ck_scene_confidence
    CHECK (scene_confidence IS NULL OR scene_confidence BETWEEN 0 AND 1),

  CONSTRAINT ck_emotion_confidence
    CHECK (emotion_confidence IS NULL OR emotion_confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='照片AI分析结果表';
```

---

### 10.5 表 4：`categories` 分类字典表

```sql
CREATE TABLE categories (
  category_id   BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '分类ID',

  type          ENUM('scene','emotion','tag') NOT NULL COMMENT '分类类型',
  name          VARCHAR(100) NOT NULL COMMENT '分类显示名称，如 🏖️ 海滩、😄 快乐、👤 人物',

  icon_url      VARCHAR(500) NULL COMMENT '分类图标',
  sort_order    INT NOT NULL DEFAULT 0 COMMENT '排序值',
  is_enabled    TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',

  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

  UNIQUE KEY uk_type_name (type, name),
  KEY idx_type_sort (type, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分类字典表';
```

**示例数据**（对应「零、初始分类集合」）：
| category_id | type | name |
|-------------|------|------|
| 1 | scene | 🏖️ 海滩 |
| 2 | scene | 🏙️ 城市 |
| 21 | emotion | 😄 快乐 |
| 22 | emotion | 😌 平静 |
| 41 | tag | 👤 人物 |
| 42 | tag | 🏞️ 风景 |

---

### 10.6 表 5：`photo_categories` 照片-分类关联表

```sql
CREATE TABLE photo_categories (
  photo_id       BIGINT UNSIGNED NOT NULL COMMENT '照片ID',
  category_id    BIGINT UNSIGNED NOT NULL COMMENT '分类ID',

  confidence     DECIMAL(5,4) NOT NULL DEFAULT 1.0000 COMMENT '置信度',
  source         ENUM('ai','user') NOT NULL DEFAULT 'ai' COMMENT '来源：AI识别或用户手动',
  is_primary     TINYINT NOT NULL DEFAULT 0 COMMENT '是否主分类',

  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',

  PRIMARY KEY (photo_id, category_id),

  KEY idx_category_photo (category_id, photo_id),

  CONSTRAINT fk_photo_categories_photo
    FOREIGN KEY (photo_id) REFERENCES photos(photo_id)
    ON DELETE CASCADE,

  CONSTRAINT fk_photo_categories_category
    FOREIGN KEY (category_id) REFERENCES categories(category_id)
    ON DELETE CASCADE,

  CONSTRAINT ck_photo_category_confidence
    CHECK (confidence BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='照片-分类多对多关联表';
```

---

### 10.7 表 6：`ai_tasks` AI 分析任务表

```sql
CREATE TABLE ai_tasks (
  task_id        BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '任务ID',
  photo_id       BIGINT UNSIGNED NOT NULL COMMENT '照片ID',

  status         ENUM('queued','processing','succeeded','failed')
                 NOT NULL DEFAULT 'queued' COMMENT '任务状态',

  error_message  TEXT NULL COMMENT '错误信息',

  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',

  CONSTRAINT fk_ai_tasks_photo
    FOREIGN KEY (photo_id) REFERENCES photos(photo_id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI分析任务表';
```

---

### 10.8 表 7：`favorites` 收藏表

```sql
CREATE TABLE favorites (
  user_id      BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  photo_id     BIGINT UNSIGNED NOT NULL COMMENT '照片ID',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '收藏时间',

  PRIMARY KEY (user_id, photo_id),

  KEY idx_user_created (user_id, created_at DESC),

  CONSTRAINT fk_favorites_user
    FOREIGN KEY (user_id) REFERENCES users(user_id)
    ON DELETE CASCADE,

  CONSTRAINT fk_favorites_photo
    FOREIGN KEY (photo_id) REFERENCES photos(photo_id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户收藏表';
```

---

### 10.9 表关系图（ER 简图）

```
users (1) ─────< (N) photos (1) ────── (1) photo_ai_analysis
   │                  │                          │
   │                  │                          │
   │                  │ (N)                      │
   │                  v                          │
   │            photo_categories ──────> (1) categories
   │                  │                       (scene/emotion/tag)
   │                  │
   │                  v (N)
   │              ai_tasks (1) ──────> (1) photos
   │
   └─────< (N) favorites >───── (1) photos
```

**关系说明**：
| 关系 | 类型 | 说明 |
|------|------|------|
| users → photos | 1 : N | 一个用户拥有多张照片 |
| photos → photo_ai_analysis | 1 : 1 | 一张照片只有一条 AI 摘要记录 |
| photos ↔ categories | N : N | 通过 `photo_categories` 实现多对多 |
| photos → ai_tasks | 1 : N | 一张照片可有多次分析任务（首次、重新、缩略图） |
| users ↔ photos | N : N | 通过 `favorites` 实现收藏关系 |

---

### 10.10 数据统计查询（实时）

**1. 照片总量**
```sql
SELECT COUNT(*) AS total_count
FROM photos
WHERE user_id = ?
  AND deleted_at IS NULL;
```

**2. 已分析数量**
```sql
SELECT COUNT(*) AS analyzed_count
FROM photos
WHERE user_id = ?
  AND deleted_at IS NULL
  AND analysis_status = 'done';
```

**3. 收藏数量**
```sql
SELECT COUNT(*) AS favorite_count
FROM favorites f
JOIN photos p ON f.photo_id = p.photo_id
WHERE f.user_id = ?
  AND p.deleted_at IS NULL;
```

**4. 场景分布**
```sql
SELECT 
  c.category_id,
  c.name,
  COUNT(*) AS photo_count
FROM photo_categories pc
JOIN categories c ON pc.category_id = c.category_id
JOIN photos p ON pc.photo_id = p.photo_id
WHERE p.user_id = ?
  AND p.deleted_at IS NULL
  AND c.type = 'scene'
GROUP BY c.category_id, c.name
ORDER BY photo_count DESC;
```

**5. 情绪分布**
```sql
SELECT 
  c.category_id,
  c.name,
  COUNT(*) AS photo_count
FROM photo_categories pc
JOIN categories c ON pc.category_id = c.category_id
JOIN photos p ON pc.photo_id = p.photo_id
WHERE p.user_id = ?
  AND p.deleted_at IS NULL
  AND c.type = 'emotion'
GROUP BY c.category_id, c.name
ORDER BY photo_count DESC;
```

**6. 标签分布**
```sql
SELECT 
  c.category_id,
  c.name,
  COUNT(*) AS photo_count
FROM photo_categories pc
JOIN categories c ON pc.category_id = c.category_id
JOIN photos p ON pc.photo_id = p.photo_id
WHERE p.user_id = ?
  AND p.deleted_at IS NULL
  AND c.type = 'tag'
GROUP BY c.category_id, c.name
ORDER BY photo_count DESC;
```

---

### 10.12 统计缓存表（可选，后期扩展）

> 当单用户照片超过 1 万张、全站超过 100 万张、统计页面打开明显变慢时启用。

**用户照片统计缓存表**
```sql
CREATE TABLE user_photo_stats (
  user_id          BIGINT UNSIGNED PRIMARY KEY COMMENT '用户ID',
  total_count      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '照片总数',
  analyzed_count   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已分析数量',
  favorite_count   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '收藏数量',
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  CONSTRAINT fk_user_photo_stats_user
    FOREIGN KEY (user_id) REFERENCES users(user_id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户照片统计缓存表';
```

**用户分类统计缓存表**
```sql
CREATE TABLE user_category_stats (
  user_id        BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  category_id    BIGINT UNSIGNED NOT NULL COMMENT '分类ID',
  photo_count    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '照片数量',
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (user_id, category_id),

  CONSTRAINT fk_user_category_stats_user
    FOREIGN KEY (user_id) REFERENCES users(user_id)
    ON DELETE CASCADE,

  CONSTRAINT fk_user_category_stats_category
    FOREIGN KEY (category_id) REFERENCES categories(category_id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户分类统计缓存表';
```
