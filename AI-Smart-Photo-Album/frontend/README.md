# AI 智能相册 · Android 客户端（frontend）

> 本目录是 `AI-Smart-Photo-Album` 项目的 **Android 客户端** 源码。
> 对应仓库根的 `frontend/` 位置；后端在 `../backend/`，模型层在 `../model/`。

一个能自动给照片分类、检索、统计的智能相册 Android App。后端用 FastAPI，
AI 推理走阿里云百炼（DashScope）多模态视觉模型。

---

## 一、功能概览

| 页面 / Activity | 主要功能 | 对应后端接口 |
|---|---|---|
| `LoginActivity` | 登录 / 注册入口（启动页） | `POST /api/v1/auth/login`、`POST /api/v1/auth/register` |
| `MainActivity` | 主页：搜索框、AI 分析状态卡片、智能分类相册入口 | `GET /api/v1/ai/status`、`GET /api/v1/categories/preview` |
| `AlbumActivity` | 相册视图：按场景 / 情绪 / 标签 3 个 Tab 展示分类 | `GET /api/v1/categories` 等分类相关接口 |
| `CategoryBrowseActivity` | 单一分类下的照片列表 | `GET /api/v1/categories/{id}/photos` |
| `PhotoListActivity` | 通用照片分页列表（按筛选条件） | `GET /api/v1/photos` |
| `PhotoDetailActivity` | 照片详情：标签、描述、相册归属、相似照片、收藏 | `GET /api/v1/photos/{id}`、`POST /photos/{id}/favorite` |
| `search.SearchActivity` | 自然语言搜索（"上周的海边照片"）+ 多维标签筛选 | `POST /api/v1/photos/search`、`POST /api/v1/photos/filter` |
| `AIAnalysisActivity` | AI 分析任务面板：查看进行中任务 / 历史结果 / 重新分析 | `GET /api/v1/ai/status`、`POST /api/v1/ai/reanalyze` |
| `ProfileActivity` | 个人资料：统计、收藏夹、头像、用户名编辑 | `GET /api/v1/users/me`、`GET /api/v1/users/me/statistics`、`GET /api/v1/users/me/favorites` |
| `SettingsActivity` | 设置：分类管理（admin 类目 CRUD + 重置） | `GET/PATCH/DELETE /api/v1/admin/categories` 等 |

辅助模块：
- `App` — `Application` 入口；安装全局 401 监听器（收到 401 自动清 Session 跳登录页）
- `Session` — token / userId / username 持久化到 SharedPreferences
- `ApiClient` — 基于 `HttpURLConnection` 的轻量 HTTP 客户端；自动注入 `Authorization: Bearer <token>`；统一响应包 `{code, message, data}` 解析
- `ApiService` — 封装全部 26 个后端接口，调用方零感知
- `ImageLoader` — 异步图片下载 / 缓存（用于头像、缩略图、相册预览）
- `DonutChartView` — 自定义 View：分析状态环形进度条
- `NonScrollGridView` — 自定义 View：嵌在 ScrollView 里不滚动的 GridView

---

## 二、项目结构

```
frontend/
├── build.gradle.kts            # 顶层 Gradle 配置
├── settings.gradle.kts         # 模块聚合（包含 :app、:ai_photo）
├── gradle.properties           # JVM 参数等
├── gradlew / gradlew.bat       # Gradle Wrapper
├── gradle/wrapper/             # Wrapper JAR + 配置
│
├── app/                        # ⚠️ 占位模块，不要 Run 这个
│   ├── build.gradle.kts        # applicationId = com.example.myapplication
│   └── src/main/AndroidManifest.xml
│
└── ai_photo/                   # ✅ 真正在用的主模块，请 Run 这个
    ├── build.gradle.kts        # applicationId = com.ai_photo
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/ai_photo/
        │   │   ├── App.java
        │   │   ├── MainActivity.java
        │   │   ├── LoginActivity.java
        │   │   ├── AlbumActivity.java
        │   │   ├── CategoryBrowseActivity.java
        │   │   ├── PhotoListActivity.java
        │   │   ├── PhotoDetailActivity.java
        │   │   ├── AIAnalysisActivity.java
        │   │   ├── ProfileActivity.java
        │   │   ├── SettingsActivity.java
        │   │   ├── DonutChartView.java          # 自定义 View
        │   │   ├── NonScrollGridView.java       # 自定义 View
        │   │   ├── auth/Session.java            # token 持久化
        │   │   ├── net/ApiClient.java           # ★ 需要改 BASE_URL
        │   │   ├── net/ApiResponse.java
        │   │   ├── net/ApiService.java          # 26 个接口
        │   │   ├── net/ImageLoader.java
        │   │   ├── net/Models.java              # 全部数据模型 fromJson
        │   │   ├── search/SearchActivity.java
        │   │   ├── ai/{HistoryAdapter, HistoryItem, TaskAdapter, TaskItem}.java
        │   │   ├── album/  category/  ...        # 业务子包
        │   └── res/
        │       ├── layout/                      # 25 个 layout
        │       ├── drawable/ mipmap-*/ values/ values-night/
        └── androidTest/  test/                   # 单元 / 仪器测试
```

### 模块说明

`settings.gradle.kts` 里同时 include 了两个模块：

```kotlin
include(":app")           // 占位，applicationId = com.example.myapplication
include(":ai_photo")      // 主模块，applicationId = com.ai_photo
```

**⚠️ 在 Android Studio 顶部菜单确认 Run 配置选中的是 `:ai_photo`，不是 `:app`。**

---

## 三、技术栈

- **语言**：Java 11（`sourceCompatibility = VERSION_11`）
- **构建**：Gradle 9.3.1 + Android Gradle Plugin
- **compileSdk**：36（`minorApiLevel = 1`）
- **minSdk**：29（Android 10）
- **targetSdk**：36
- **UI 库**：AppCompat / Material Components / ConstraintLayout / RecyclerView / Flexbox
- **网络**：自研轻量 `ApiClient`（基于 `HttpURLConnection`），不依赖 OkHttp / Retrofit
- **JSON**：`org.json`（系统自带）
- **图片加载**：自研 `ImageLoader`（不依赖 Glide / Picasso）
- **持久化**：SharedPreferences（`Session`）

---

## 四、构建环境

| 工具 | 版本 | 备注 |
|---|---|---|
| Android Studio | Hedgehog (2023.1) 或更新 | 推荐 Iguana / Jellyfish |
| JDK | 11 | 项目里 `compileOptions` 写死 11，不要用 8 或 17 |
| Android SDK Platform | API 36 | `compileSdk = 36` |
| Android Build-Tools | 36.0.0+ | |
| Gradle | 9.3.1 | 项目自带 Wrapper，无需本地装 |
| Python | 3.10+ | 仅运行后端需要 |

---

## 五、需要自己改 / 自己建的位置（⚠️ 重点）

> 这一节列出**接收方在自己机器上跑起来前必须改完**的所有点。
> 改完一个就划掉一个。

### 5.1 根目录的 `local.properties`（**新建**）

**位置**：`frontend/local.properties`（仓库里没有，要自己创建）

**原因**：每台机器的 Android SDK 装在不同路径。

**最小内容**：

```properties
# Windows 示例（注意反斜杠要转义）
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk

# macOS / Linux 示例
# sdk.dir=/Users/yourname/Library/Android/sdk
```

或者直接用 Android Studio 打开项目 → File → Sync，第一次会自动生成这个文件，你只需确认 `sdk.dir` 路径正确即可。

### 5.2 后端 API 地址（**必改**）

**文件**：`frontend/ai_photo/src/main/java/com/ai_photo/net/ApiClient.java`
**行号**：第 43 行附近

```java
public static final String BASE_URL = "http://10.137.143.151:8000";
```

把这一行替换成你后端实际监听的地址：

| 场景 | 推荐值 |
|---|---|
| Android 模拟器 + 后端跑在宿主机 | `http://10.0.2.2:8000`（10.0.2.2 = 宿主机回环） |
| Android 真机 + 后端在同一 WiFi 局域网 | `http://192.168.x.x:8000`（换成后端机器的局域网 IP） |
| 后端部署到云服务器 | `http://你的公网IP:8000` 或 `https://你的域名` |

> **重要**：
> - 这是**全工程唯一**的硬编码地址。改完重新 Sync + Rebuild 即可。
> - 上面 `AndroidManifest.xml` 已经声明了 `android:usesCleartextTraffic="true"`，所以 `http://` 不会被系统拦；如果你换成 `https://`，可以保留也可以删掉这一行。
> - `AndroidManifest.xml` 已经声明了 `INTERNET` 权限，正常不需要再动。

### 5.3 后端 `.env`（**新建**，在仓库另一处）

**位置**：`../backend/.env`（**不**在 `frontend/` 下，**不**在 `frontend/` 下，**不**在 `frontend/` 下）

阿里云百炼走的是 **workspace 模式**（不是普通 sk- 模式）：

```bash
# ../backend/.env
DASHSCOPE_API_KEY=sk-ws-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
DASHSCOPE_WORKSPACE_ID=ws-xxxxxxxxx
DASHSCOPE_REGION=cn-beijing
ALIYUN_MODEL=qwen3-vl-plus
AI_SERVICE=real
```

> ⚠️ **绝对不要**把自己的真实 key 提交到 git。这 4 个变量接收方自己去阿里云百炼控制台申请。
> `DASHSCOPE_REGION` 可选：`cn-beijing` / `cn-shanghai` / `cn-shenzhen` / `cn-hangzhou`。
> 端点 URL 模板：`https://{WorkspaceId}.{region}.maas.aliyuncs.com/compatible-mode/v1`

### 5.4 占位模块 `:app`（**别理它**）

`settings.gradle.kts` include 了 `:app` 模块（`com.example.myApplication`），这是开发期占位用的，没有实际业务代码。**不要 Run 它**，会因 `applicationId` 冲突或 `MainActivity` 重复导致装不上。

如果觉得碍眼，可以直接：
1. 从 `settings.gradle.kts` 删掉 `include(":app")`
2. 删掉 `frontend/app/` 整个目录
3. 在 Android Studio 顶部下拉框里就只剩 `:ai_photo` 一个选项

### 5.5（可选）release 签名

当前 `release` buildType 没有配 `signingConfig`，所以 `assembleRelease` 出来是 debug 签名。要正式发版的话需要：
1. 生成自己的 keystore（**绝对不要把 keystore 提交到仓库**）
2. 在 `ai_photo/build.gradle.kts` 加 `signingConfigs { ... }` 并让 `release` 引用

---

## 六、启动顺序

1. **克隆仓库**（如果还没）
   ```bash
   git clone <repo-url>
   cd AI-Smart-Photo-Album
   ```

2. **先启后端**（Android 客户端没后端等于砖头）
   ```bash
   cd backend
   python -m venv .venv
   .venv\Scripts\activate           # Windows
   # source .venv/bin/activate      # macOS / Linux
   pip install -r requirements.txt
   cp .env.example .env             # 然后按 5.3 填好 4 个 DashScope 变量
   PYTHONPATH=.. python -m model.inference.cli --image test.jpg   # 自检
   uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
   ```
   后端起来后访问 `http://localhost:8000/docs` 应能看到 Swagger。

3. **改 Android 客户端的 BASE_URL**（见 5.2）

4. **新建 `local.properties`**（见 5.1）

5. **Android Studio 打开 `frontend/` 这个目录**
   - 顶部模块下拉选 **`:ai_photo`**
   - 选好真机或启动一个 API ≥ 29 的模拟器
   - 点 Run（▶︎）

6. **登录**
   - 默认测试账号（后端 seed 数据决定，以 `backend/README.md` 为准）

---

## 七、常见坑

| 现象 | 原因 | 解决 |
|---|---|---|
| Gradle Sync 失败：`SDK location not found` | 缺 `local.properties` | 见 5.1 |
| App 装不上：`INSTALL_FAILED_USER_RESTRICTED` | 选错了模块 | 顶部下拉切到 `:ai_photo` |
| 启动后白屏 / 所有接口 404 | `BASE_URL` 没改对 | 见 5.2 |
| 接口返回 `Connection refused` | 后端没启 / IP 不通 | 模拟器用 `10.0.2.2`，真机用后端局域网 IP |
| 接口返回 `CLEARTEXT communication ... not permitted` | `usesCleartextTraffic` 没开 | 检查 `ai_photo/src/main/AndroidManifest.xml` 里的 `application` 标签 |
| Logcat 看到 `CLEARTEXT` 但仍然连不上 | 后端监听了 `127.0.0.1` 而不是 `0.0.0.0` | 后端启动加 `--host 0.0.0.0` |
| 401 后没跳登录页 | 启用了多进程 / 自定义 Application 没继承 | 确认 `AndroidManifest.xml` 里 `android:name=".App"` |
| 报 `java.lang.NoClassDefFoundError: ...` | 改了 `build.gradle.kts` 之后没 Sync | File → Sync Project with Gradle Files |

---

## 八、目录约定

- `app/src/main/java/com/ai_photo/net/` — 所有网络层代码不要绕过 `ApiService` 直接 `new HttpURLConnection`
- 所有 IO 调用都在子线程；UI 线程里看到的 `ApiService.xxx` 都被 `AppCompatActivity` 包了 AsyncTask / Thread
- 业务异常统一是 `ApiService.ApiException(int code, String message)`
- 调试时 LogCat 过滤：`tag:AiPhoto.API` / `tag:AiPhoto.App` 可以看到全链路

---

## 九、版本

- Android 客户端 `versionName = 1.0`，`versionCode = 1`
- API 协议见 `../docs/interface.md`
- 完整 4 层架构说明见仓库根 `README.md`
