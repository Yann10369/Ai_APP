# AI 智能相册 项目介绍文档

> 适用版本：`backend v0.1.0` / `model`（独立子包） / `android v0.1.0`
> 读者：课程评审 / 组会汇报
> 前端：Android 原生实现，位于同级目录 `MyApplication/ai_photo/`。

---

## 1. 任务介绍

### 1.1 背景与目标

手机像素越来越高，"翻相册找一张三个月前在海边拍的照片"变成一件痛苦的事。传统相册 App 的痛点：

- **检索方式单一**：只能按"拍摄时间"或"文件夹"找，无法用"那天在海边和谁玩的合影"这种语义检索。
- **整理依赖人工**：分类、贴标签要用户主动操作，绝大多数人懒得做。
- **搜索关键词死板**：只能匹配文件名 / EXIF，没法理解"夜景""温馨""聚会"这种抽象语义。

本项目希望在不强迫用户做整理的前提下，让上传的照片**自动被理解、被分类**，并且用户可以用自然语言**搜出想要的瞬间**。

### 1.2 功能范围

账号（注册 / 登录）、上传（批量 + 自动去重 + 缩略图）、**AI 自动分类**（场景 + 情绪 + 标签）、列表 / 详情 / 最近 / 收藏 / 修改 / 软删除、**自然语言搜索**、分类字典管理。

### 1.3 非目标

本期不含多端同步 / 分享 / 评论、视频素材处理、纯离线端（所有 AI 推理走云端）。

---

## 2. 解决思路

### 2.1 为什么不直接做"开放词表分类"

开放词表（open-vocabulary）落地三个硬伤：

1. **存储不可控**：每张图都会被模型自由打上几十个五花八门的标签，没法建索引、没法命中排序。
2. **搜索不对齐**：用户搜"海边"，AI 给照片打的可能是 "coastline""shore""seaside"，字面对不上。
3. **成本高**：每张图都要让模型做长 prompt 输出，token 消耗大、延迟高。

所以我们采用 **有限分类集（closed-vocabulary）+ 多模态大模型** 的折中路线。

### 2.2 核心思路：优先词表 + qwen-vl分类

**核心约束**：所有"标签"必须落在我们预定义的 60 个分类里——这样**照片侧**和**搜索侧**是同一个集合，能直接 join 排序，零词表漂移。

- **预定义字典**：20 scene（海滩 / 城市 / 山景 / 夜景 …）+ 20 emotion（快乐 / 平静 / 温馨 …）+ 20 tag（人物 / 风景 / 动物 / 美食 / 合影 …），全部带 emoji 前缀防串词。
- **图片分类**：用阿里云百炼的 `qwen-vl-plus`，prompt 里枚举 60 个候选集，要求模型**只能从中选、严格输出 JSON**。
- **搜索意图抽取**：把用户的自然语言 query 丢给同一个模型，从 60 个标签里挑最相关的 5 个，按 confidence 排序。
- **统一中间表示**：AI 返回的 `(name, confidence)` 元组由后端反查分类表得到 `category_id`，写入 `photo_categories`。后续搜索就是纯 SQL join，不需要向量化。

> **架构设计原则**：为了降低部署成本和满足单机运行要求，系统采用极简架构——**进程内协程队列替代外部消息中间件，单文件 SQLite 替代重型关系型数据库，模型层独立成包以便复用**。

### 2.3 整体数据流

```
[Android 上传图片]
      │
      ▼
[FastAPI /photos/upload]
      │ ① sha256 去重（命中软删则恢复）
      │ ② 生成 webp 缩略图
      │ ③ INSERT ai_tasks(queued)
      ▼
[进程内 AIWorker] ←── notify_new_task()
      │ claim → qwen-vl-plus → 写 photo_categories
      ▼
[用户搜索 query]
      ▼
[FastAPI /photos/search]
      │ ① qwen-vl-plus 文本模型：query → tag 列表
      │ ② JOIN photo_categories 聚合命中数
      │ ③ score = anchor_conf × (1 + 0.1 × (命中数-1)) 倒序分页
```

---

## 3. 框架结构

### 3.1 系统架构图

```
┌──────────────────────────────────────────────────────────┐
│  Android (Java + AndroidX，无第三方依赖)                  │
│   ├─ 10 个 Activity（Login / Main / Album / PhotoList …） │
│   ├─ 自研 ImageLoader（LRU + 边长采样，OOM 安全）         │
│   └─ 自绘 DonutChartView / NonScrollGridView              │
└────────────────────────────┬─────────────────────────────┘
                             │ HTTP/JSON + JWT
                             ▼
┌──────────────────────────────────────────────────────────┐
│  FastAPI（异步、模块化）                                  │
│   ├─ routers:   auth / photos / categories / users        │
│   ├─ services:  photo / favorite / file_storage / ai     │
│   ├─ models:    ORM + AIWorker（抢占 / 重试 / 心跳 / 恢复）│
│   └─ lifespan:  迁移 + seed + 启 worker                   │
└────────────────────────────┬─────────────────────────────┘
                             │ asyncio + HTTP
                             ▼
┌──────────────────────────────────────────────────────────┐
│  model/  （独立 Python 包，可被任意后端复用）              │
│   ├─ inference.classifier  → PhotoClassifier              │
│   ├─ inference.prompts     → 分类 / 搜索 prompt 模板      │
│   ├─ inference.aliyun      → DashScope 兼容客户端        │
│   └─ postprocess           → JSON 解析 + 标签归一         │
└────────────────────────────┬─────────────────────────────┘
                             │ OpenAI 兼容协议
                             ▼
                ┌────────────────────────┐
                │  阿里云百炼 qwen-vl-plus │
                └────────────────────────┘
```

### 3.2 后端技术栈与选型理由


| 层        | 选型                                      | 理由                               |
| -------- | --------------------------------------- | -------------------------------- |
| Web      | FastAPI                                 | 异步原生 + Pydantic v2 + 自动 OpenAPI  |
| ORM / DB | SQLAlchemy 2.0 async + **SQLite (WAL)** | 单文件零部署，aiosqlite 异步成熟；单机 demo 足够 |
| 配置       | pydantic-settings                       | 强类型环境变量                          |
| 鉴权       | python-jose (JWT) + passlib bcrypt      | 无状态 token + 强哈希                  |
| 推理       | DashScope → qwen-vl-plus                | 国内访问稳定，视觉能力强                     |
| 后台任务     | **进程内 asyncio AIWorker**                | 单进程足够，零外部依赖（不引 Celery / Redis）   |
| 测试       | pytest + pytest-asyncio + httpx         | 端到端 + 单元                         |


### 3.3 Android 技术栈与选型理由


| 层       | 选型                                              | 理由                          |
| ------- | ----------------------------------------------- | --------------------------- |
| 语言 / UI | Java 11 + AndroidX AppCompat + ConstraintLayout | 团队熟；兼容到 Android 5.0（API 21） |
| 网络      | HttpURLConnection + 手写 JSON 解析（`org.json`）      | **零第三方依赖**；后端 8 个接口可控       |
| 图片      | 自研 ImageLoader                                  | LRU + 边长采样，OOM 安全（详见 5.3）   |
| 异步      | ExecutorService + Handler                       | 替代 RxJava；IO 任务数 ≤ 2        |
| 图表      | 自绘 DonutChartView                               | 主页 AI 状态仪表盘                 |


> **显式不引入**：OkHttp、Retrofit、Glide、Picasso、Material Components、Hilt/ButterKnife/Gson。**项目零三方 jar**，APK 体积小，每行代码可讲清。

---

## 4. 数据如何保存

### 4.1 数据库选型


| 维度   | SQLite       | MySQL       | PostgreSQL |
| ---- | ------------ | ----------- | ---------- |
| 部署成本 | 零            | 需服务进程       | 需服务进程      |
| 异步驱动 | aiosqlite 成熟 | aiomysql 较慢 | asyncpg 成熟 |
| 并发写  | 单写者 + WAL 仍可 | 强           | 强          |


单机 demo 阶段**单文件 SQLite + WAL + foreign_keys + busy_timeout=5000** 稳定扛住 AI 写 + API 读并发。要上云端再迁 PostgreSQL（见第 6 节）。

### 4.2 表结构核心巧思

7 张表（users / photos / categories / photo_ai_analysis / photo_categories / ai_tasks / favorites），核心关系：

```
users ─┬─ photos ─┬─ photo_ai_analysis  (1:1)
       │         ├─ photo_categories ── categories  (N:N，source ∈ {ai, user})
       │         ├─ favorites  (用户-照片 N:N)
       │         └─ ai_tasks   (处理历史 + worker 状态机)
```

**最关键的一个设计**：`photo_categories.source ∈ {ai, user}`。AI 重新分析时**只替换 ai-source 的行、保留 user-source 的行**——保证用户手动改过的标签永远不会被 AI 覆盖。这是后面所有"AI 标签 + 用户手工标签共存"功能的根基。

### 4.3 文件存储

- 原图 `data/origin/<photo_id>.<ext>`；缩略图 `data/thumb/<photo_id>.webp`（webp / 质量 75 / 最长边 512）。
- 上传走**两阶段写入**：先在内存算 sha256 → 去重 → 真要写盘时才 `aiofiles` 落原图 + Pillow 生成缩略图。

---

## 5. 难点与解决

### 5.1 后端难点

#### 难点 1：文件哈希去重与软删除的底层数据库冲突

**问题**：用户删除一张照片后再次上传同一文件 → 触发 `UNIQUE` 约束 500。
**根因**：SQLite 的 `UNIQUE(user_id, file_hash)` 不考虑 `deleted_at`（它是普通列，不是 partial index）。
**解决**：INSERT 前先 `recycle_by_hash()`——找到该用户已软删的同 hash 行就 **restore**（清 `deleted_at`、重写文件、重做缩略图），而不是新建。这样既不破坏唯一性，也不让 `photo_id` 一直增长。

#### 难点 2：进程内 AIWorker 的可靠性（抢占 / 重试 / 崩溃恢复）

**问题**：上传要立刻 200，但要异步分析；AI 调用失败要重试；进程崩溃后重启要把 stuck 的任务捞回来。
**解决**：自研 `AIWorker` 协程管理器，用 SQLite 自己当队列：


| 机制   | 实现                                                                                      |
| ---- | --------------------------------------------------------------------------------------- |
| 抢占任务 | `UPDATE ai_tasks SET status=processing WHERE status=queued AND next_retry_at<=now` 原子抢占 |
| 失败重试 | 指数退避 `base=10s / cap=300s / max=3`，超过置 `failed`                                         |
| 心跳   | 处理中定期写 `heartbeat_at`                                                                   |
| 崩溃恢复 | 启动时 `claimed_at < now()-120s` 的 processing 视为 orphan，重置回 queued                         |
| 唤醒   | `notify_new_task()` 用 `asyncio.Event.set()` 退出 idle poll                                |


#### 难点 3：多模态大模型的"输出治理"（防范乱吐 Markdown / 字段漂移）

**问题**：`qwen-vl-plus` 偶尔把 JSON 外面包一层 ````json ... ````、偶尔串出额外解释、偶尔 confidence 给到 3 位小数——直接 `json.loads` 会崩。
**解决（综合治理方案）**：

1. prompt 顶部写"最高指令"反复强调禁止 markdown 包裹，并直接给出 **JSON Schema 字符串**让模型照着填空；
2. `parse_json_answer()` 容错剥离 markdown + 兜底正则抓第一个 `{...}`；
3. postprocess 阶段强制 `round(x, 2)`、对 `< 0.1` 的 confidence 视为无效丢弃；
4. 字段枚举加 `enum` 约束（prompt 里直接列 60 个候选集），从源头消除"自造词"风险。

### 5.2 前端难点

#### 难点 1：图片加载

**问题**：图片加载，内存越出，程序闪退
---解决采用：略缩图展示所有的照片。

#### 难点 2：AI分析的可视化

## 6. 改进点

按优先级从高到低：

1. **数据库升级**：SQLite → PostgreSQL（asyncpg + pgvector）。`DATABASE_URL` 一行切换；ORM 层基本不改。
2. **任务队列升级**：进程内 worker → arq / Redis Stream，保留 `ai_tasks` 表做审计日志。
3. **AI 分析进度实时推送**：后端 WebSocket `/ws/ai-tasks` push 每完成一张；前端按 photoId 增量更新列表，断线重连 + 退后台停止监听。
4. **Android 列表性能**：`PhotoListActivity` 当前 LinearLayout 嵌套多个 `NonScrollGridView`，到 1000 张会卡——改单 RecyclerView + GridLayoutManager(4) + DiffUtil 增量更新；按日期分组 header 用 ConcatAdapter 拼装。
5. **大文件上传进度条 + 断点续传**：客户端按 4MB 切片并发，服务端 `tus` 协议接收合并。
6. **模型能力扩展**：模型在分析时给"候选集之外的标签"打建议分数，由 admin 审核入库；1 张图支持多 scene / 多 emotion。

---

## 附录 A：本地一键启动

```bash
# 后端
cd backend
pip install -e .
cp .env.example .env   # 填入 DASHSCOPE_API_KEY
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

# 测试
pytest -q
```

打开 `http://localhost:8000/docs` 看所有接口。Android 用 Android Studio 打开 `MyApplication/ai_photo/`，修改 `ApiClient.BASE_URL`（模拟器 `10.0.2.2` / 真机电脑 IP），`./gradlew installDebug` 即可。