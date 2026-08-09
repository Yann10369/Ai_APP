# 接口变更记录

> 项目名称：AI 智能相册
> 文档维护：接口变更说明

---

## 变更记录

| 版本 | 日期 | 变更模块 | 变更类型 | 变更摘要 |
|------|------|----------|----------|----------|
| v1.1 | 2026-06-18 | 分类相册（Categories） | 修改 | 首页分类预览接口调整 |

---

## 1. 【v1.1】首页分类预览接口调整

**变更时间**：2026-06-18
**影响接口**：`GET /api/v1/categories/preview`
**变更类型**：响应结构调整 + 采样策略调整

### 1.1 问题说明

原接口在随机采样时存在以下问题：
- 子标签 `categoryName` 在三大分类（场景/情绪/标签）中是固定返回的，不符合"随机采样"的预期
- 每个子标签下返回了多张 `previewPhotos`（默认 4 张），数据冗余
- 响应中携带 `photoCount` 字段，首页预览场景下无需展示数量信息

### 1.2 变更内容

| # | 变更点 | 变更前 | 变更后 |
|---|--------|--------|--------|
| 1 | 子标签采样 | 固定返回全部子分类 | **从三大分类中随机抽取子标签** |
| 2 | 预览图数量 | 每个子标签下返回 N 张（默认 4 张） | **每个子标签仅返回 1 张预览图** |
| 3 | 字段精简 | 包含 `photoCount` 字段 | **移除 `photoCount` 字段** |

### 1.3 变更前 Response（v1.0）

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

### 1.4 变更后 Response（v1.1）

```json
{
  "code": 200,
  "data": {
    "scene": [
      {
        "categoryId": 1,
        "categoryName": "海滩",
        "previewPhotos": [
          {"photoId": 101, "thumbnailUrl": "/static/thumb/101.webp"}
        ]
      }
    ],
    "emotion": [
      {
        "categoryId": 10,
        "categoryName": "快乐",
        "previewPhotos": [
          {"photoId": 201, "thumbnailUrl": "/static/thumb/201.webp"}
        ]
      }
    ],
    "tag": [
      {
        "categoryId": 20,
        "categoryName": "人物",
        "previewPhotos": [
          {"photoId": 301, "thumbnailUrl": "/static/thumb/301.webp"}
        ]
      }
    ]
  }
}
```

### 1.5 字段变更对照

| 字段 | v1.0 | v1.1 | 说明 |
|------|------|------|------|
| `categoryId` | ✅ 保留 | ✅ 保留 | 分类唯一标识 |
| `categoryName` | ✅ 保留 | ✅ 保留 | 分类名称 |
| `photoCount` | ✅ 保留 | ❌ **移除** | 首页预览无需展示数量 |
| `previewPhotos` | N 张 | **1 张** | 每个子标签仅展示 1 张代表图 |

### 1.6 采样策略说明

- **采样范围**：从场景、情绪、标签三大分类中**随机抽取**子标签（`categoryName`）
- **采样结果**：每次请求返回的子标签内容可能不同
- **预览图策略**：每个被采中的子标签，从其下照片中随机选取 **1 张**作为预览图
- **接口地址**：`GET /api/v1/categories/preview`（URL 不变，移除 `previewSize` 参数）

### 1.7 影响范围

| 影响项 | 说明 |
|--------|------|
| 前端首页 | "智能分类"预览区需调整渲染逻辑：每个子标签只展示 1 张缩略图，不再展示数量 |
| 前端缓存 | 由于子标签为随机返回，原有缓存策略需调整为"短期缓存"或"禁用缓存" |
| 前端入参 | 不再需要 `previewSize` 参数 |
| 后端逻辑 | 采样函数需重构：从固定返回改为随机采样 |
| 数据表 | 无需变更 |

### 1.8 兼容性说明

- 本次为**不兼容变更**（Breaking Change）
- 前端需同步更新，否则会出现字段缺失或多于的渲染问题
- 建议发布时同步更新前端版本，避免线上报错

---

## 附录：变更原因总结

> 首页"智能分类"区域的定位是"快速浏览 + 视觉吸引"，**不需要展示数量信息**，也**无需多张预览图**。改为随机采样后，每次进入首页都能看到不同的分类组合，提升了探索趣味性和发现感。
