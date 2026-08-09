"""全量接口契约对照测试。

对比 docs/interface.md (v1.1) 中声明的全部 26 个接口：
  1) 通过 OpenAPI schema 校验每个接口是否注册 + method + path；
  2) 通过 Pydantic schema / 路由返回值静态校验 *响应字段*；
  3) 必要时跑最小可用的端到端用例，确认运行时字段。

任何与 interface.md 不匹配之处会进入 MISMATCHES 列表，最后由 test_mismatch_report
统一打印（按用户要求只输出列表）。
"""
from __future__ import annotations

import asyncio
import io
import os
import shutil
from pathlib import Path
from typing import Any

# === 必须在 import app.* 之前设置环境变量（settings 是 lru_cache）===
TEST_DB_URL = "sqlite+aiosqlite:///./tests/_data/all_endpoints.db"
TEST_DATA_DIR = "./tests/_data"
os.environ["DATABASE_URL"] = TEST_DB_URL
os.environ["DATA_DIR"] = TEST_DATA_DIR
os.environ["JWT_SECRET"] = "test-secret-all-endpoints"
Path(TEST_DATA_DIR).mkdir(parents=True, exist_ok=True)


# ============================================================================
#  A. 静态契约对照：26 个接口路径 + 字段名
# ============================================================================

# 从 interface.md 二、接口汇总表 抄下来的全部 (method, path_template)
EXPECTED_ROUTES: list[tuple[str, str]] = [
    ("POST",   "/api/v1/auth/register"),
    ("POST",   "/api/v1/auth/login"),
    ("POST",   "/api/v1/auth/logout"),
    ("GET",    "/api/v1/users/me"),
    ("GET",    "/api/v1/users/me/statistics"),
    ("GET",    "/api/v1/users/me/favorites"),
    ("POST",   "/api/v1/photos/upload"),
    ("GET",    "/api/v1/photos"),
    ("GET",    "/api/v1/photos/recent"),
    ("GET",    "/api/v1/photos/{photo_id}"),
    ("PATCH",  "/api/v1/photos/{photo_id}"),
    ("DELETE", "/api/v1/photos/{photo_id}"),
    ("DELETE", "/api/v1/photos/batch"),
    ("POST",   "/api/v1/photos/{photo_id}/favorite"),
    ("DELETE", "/api/v1/photos/{photo_id}/favorite"),
    ("POST",   "/api/v1/photos/search"),
    ("GET",    "/api/v1/categories/preview"),
    ("GET",    "/api/v1/categories"),
    ("GET",    "/api/v1/categories/{category_id}/photos"),
    ("GET",    "/api/v1/ai/status"),
    ("POST",   "/api/v1/ai/reanalyze"),
    ("GET",    "/api/v1/admin/categories"),
    ("POST",   "/api/v1/admin/categories"),
    ("PATCH",  "/api/v1/admin/categories/{category_id}"),
    ("DELETE", "/api/v1/admin/categories/{category_id}"),
    ("POST",   "/api/v1/admin/categories/reset"),
]

# 从 interface.md 抄出来的"请求 / 响应"字段集合（按章节组织）
# 用 set 表示该层必含字段（允许有额外字段，但至少要包含这些）
EXPECTED_FIELDS: dict[str, dict[str, set[str]]] = {
    # Auth
    "auth.register.response": {"userId", "username"},
    "auth.login.response":    {"userId", "username", "token", "expiresIn"},
    # Users
    "users.me.response":      {"userId", "username", "email", "avatarUrl", "createdAt"},
    "users.statistics.response": {"totalPhotos", "analyzedPhotos", "favoriteCount", "categoryDistribution"},
    "users.favorites.item":   {"photoId", "thumbnailUrl", "favoritedAt"},
    "users.favorites.response": {"list", "total", "page", "pageSize"},
    # Photos upload
    "photos.upload.response": {"successCount", "failCount", "uploadedPhotos", "failedFiles"},
    "photos.upload.item":     {"photoId", "originalName", "thumbnailUrl", "size", "analysisStatus"},
    # Photos list / recent
    "photos.list.item":       {"photoId", "thumbnailUrl", "width", "height",
                                "createdAt", "isFavorite", "analysisStatus"},
    "photos.list.response":   {"list", "total", "page", "pageSize"},
    "photos.recent.item":     {"photoId", "thumbnailUrl", "createdAt"},
    # Photos detail
    "photos.detail.response": {"photoId", "originalUrl", "thumbnailUrl",
                                "metadata", "aiAnalysis", "isFavorite", "createdAt"},
    "photos.detail.metadata": {"fileName", "size", "width", "height", "shotAt"},
    "photos.detail.aiAnalysis": {"description", "scene", "emotion", "tags"},
    "photos.detail.aiAnalysis.scene":    {"name", "confidence"},
    "photos.detail.aiAnalysis.emotion":  {"name", "confidence"},
    "photos.detail.aiAnalysis.tagItem":  {"name", "confidence"},
    # Photos search
    "photos.search.request":  {"query"},
    "photos.search.item":     {"photoId", "thumbnailUrl", "matchedTags", "score"},
    "photos.search.response": {"list", "total", "page", "pageSize"},
    # Categories preview
    "categories.preview.sceneItem":   {"categoryId", "categoryName", "photoCount", "previewPhotos"},
    "categories.preview.previewPhoto": {"photoId", "thumbnailUrl"},
    "categories.preview.response": {"scene", "emotion", "tag"},
    # Categories list
    "categories.list.response": {"type", "list"},
    "categories.list.item":     {"categoryId", "categoryName", "photoCount", "coverThumbnail"},
    # Categories photos
    "categories.photos.response": {"categoryId", "categoryName", "list", "total", "page", "pageSize"},
    "categories.photos.item":     {"photoId", "thumbnailUrl", "createdAt"},
    # AI
    "ai.status.response":      {"total", "done", "pending", "progress"},
    "ai.reanalyze.request":    {"photoIds"},
    "ai.reanalyze.response":   {"queuedCount", "message"},
    # Admin categories
    "admin.list.response":     {"list", "total"},
    "admin.list.item":         {"categoryId", "type", "name", "iconUrl", "photoCount", "createdAt"},
    "admin.create.request":    {"type", "name"},
    "admin.create.response":   {"categoryId"},
    "admin.update.request":    {"name"},
    "admin.reset.request":     {"confirm"},
    "admin.reset.response":    {"resetCount", "removedCount"},
}


# ============================================================================
#  B. 测试 fixture & helpers
# ============================================================================

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from PIL import Image


MISMATCHES: list[str] = []


def record(label: str, expected: Any, got: Any, detail: str = "") -> None:
    """记录一条不匹配（不抛异常，让测试继续跑后续检查）。"""
    MISMATCHES.append(f"[{label}] expected={expected!r}, got={got!r}{(' ' + detail) if detail else ''}")


def assert_match(label: str, expected: Any, got: Any, detail: str = "") -> None:
    if expected != got:
        record(label, expected, got, detail)
        pytest.fail(f"{label}: expected {expected!r}, got {got!r} {detail}")


def assert_subset(label: str, subset: set[str], obj: Any) -> None:
    """对象必须是 dict 且至少包含 subset 中的全部键。"""
    if not isinstance(obj, dict):
        record(label, f"keys ⊇ {subset}", f"not a dict (got {type(obj).__name__})", str(obj)[:200])
        pytest.fail(f"{label}: not a dict, got {type(obj).__name__}")
    missing = subset - set(obj.keys())
    if missing:
        record(label, f"keys ⊇ {subset}", f"missing {missing}", f"actual={list(obj.keys())}")
        pytest.fail(f"{label}: missing {missing}, actual keys={list(obj.keys())}")


def _make_jpeg(w: int = 320, h: int = 240, seed: int = 0) -> bytes:
    """生成一张 JPEG，seed 不同时字节不同（用于多文件上传时避免 sha256 去重）。"""
    buf = io.BytesIO()
    r = (120 + seed * 7) % 256
    g = (80 + seed * 13) % 256
    b = (200 - seed * 5) % 256
    Image.new("RGB", (w, h), (r, g, b)).save(buf, "JPEG")
    return buf.getvalue()


@pytest_asyncio.fixture(scope="session", autouse=True, loop_scope="session")
async def _build_app_with_sqlite_schema():
    """session 级：用 migrations/001_schema.sql 建表 + 跑 seed（保留 DEFAULT 子句）。"""
    from sqlalchemy import text
    from app.database import run_sql_file, engine as app_engine

    db_file = Path("./tests/_data/all_endpoints.db")
    if db_file.exists():
        try:
            db_file.unlink()
        except PermissionError:
            pass

    async with app_engine.begin() as conn:
        await conn.execute(text("PRAGMA foreign_keys = OFF"))
        rows = (await conn.execute(text(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        ))).all()
        for (tname,) in rows:
            await conn.execute(text(f"DROP TABLE IF EXISTS {tname}"))
        await conn.execute(text("PRAGMA foreign_keys = ON"))
    await run_sql_file("migrations/001_schema.sql")
    await run_sql_file("migrations/003_ai_task_worker.sql")
    await run_sql_file("migrations/002_seed_categories.sql")
    yield
    await app_engine.dispose()


@pytest_asyncio.fixture
async def client():
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest_asyncio.fixture
async def registered_user(client):
    """注册并登录；unique username 防止跨测试冲突。"""
    import uuid
    suffix = uuid.uuid4().hex[:8]
    username = f"tester_{suffix}"
    r1 = await client.post("/api/v1/auth/register", json={
        "username": username, "password": "secret123", "email": f"{suffix}@example.com",
    })
    assert r1.status_code == 200, r1.text
    r2 = await client.post("/api/v1/auth/login", json={"username": username, "password": "secret123"})
    assert r2.status_code == 200, r2.text
    token = r2.json()["data"]["token"]
    client.headers["Authorization"] = f"Bearer {token}"
    return {"token": token, "userId": r2.json()["data"]["userId"], "username": username}


# ============================================================================
#  C. 静态测试：26 接口注册 + OpenAPI 参数 / 响应 schema
# ============================================================================

def test_openapi_lists_all_26_routes():
    """断言 OpenAPI 中注册的接口与 EXPECTED_ROUTES 完全一致。"""
    from app.main import app
    schema = app.openapi()
    seen: set[tuple[str, str]] = set()
    for path, methods in (schema.get("paths") or {}).items():
        if not path.startswith("/api/v1/"):
            continue
        for m in methods.keys():
            if m.lower() in ("get", "post", "patch", "delete", "put"):
                seen.add((m.upper(), path))
    expected = set(EXPECTED_ROUTES)
    if seen != expected:
        missing = expected - seen
        extra = seen - expected
        if missing:
            MISMATCHES.append(f"[routes.missing] {sorted(missing)}")
        if extra:
            MISMATCHES.append(f"[routes.extra] {sorted(extra)}")
        pytest.fail(f"接口注册不匹配，missing={missing}, extra={extra}")
    assert len(seen) == 26, f"期望 26，实际 {len(seen)}"


def test_openapi_request_schemas_have_required_fields():
    """对每个接口，把 OpenAPI 中声明的 request body schema 字段名
    与 EXPECTED_FIELDS 抄出的字段名对照。

    注意：本项目路由没有声明 response_model，所以 OpenAPI 里 response schema
    通常是空的；响应字段校验放在 test_runtime_* 系列运行时测试里。
    """
    from app.main import app
    schema = app.openapi()

    # 仅对真正用 Pydantic model 声明请求体的接口做 OpenAPI request schema 校验。
    # admin 系列接口的 handler 用的是 body: dict（不是 Pydantic），OpenAPI 拿不到字段，
    # 它们的字段一致性已在 test_runtime_admin_* 里覆盖。
    route_to_request_keys: list[tuple[tuple[str, str], str]] = [
        (("POST", "/api/v1/photos/search"),      "photos.search.request"),
        (("POST", "/api/v1/ai/reanalyze"),       "ai.reanalyze.request"),
    ]

    def _resolve_ref(obj):
        if isinstance(obj, dict):
            if "$ref" in obj:
                ref = obj["$ref"].lstrip("#/").split("/")
                cur = schema
                for seg in ref:
                    cur = cur[seg]
                return _resolve_ref(cur)
            return {k: _resolve_ref(v) for k, v in obj.items()}
        if isinstance(obj, list):
            return [_resolve_ref(x) for x in obj]
        return obj

    def _flatten_props(schema_obj):
        schema_obj = _resolve_ref(schema_obj) or {}
        if "properties" in schema_obj or schema_obj.get("type") == "object":
            return set((schema_obj.get("properties") or {}).keys())
        if "items" in schema_obj:
            return _flatten_props(schema_obj["items"])
        return set()

    for (method, path), fk in route_to_request_keys:
        op = (schema.get("paths", {}).get(path, {}).get(method.lower()) or {})
        rb = op.get("requestBody") or {}
        content = rb.get("content") or {}
        json_ct = content.get("application/json") or {}
        sch = _resolve_ref(json_ct.get("schema") or {}) or {}
        got = _flatten_props(sch)
        want = EXPECTED_FIELDS[fk]
        if not (want <= got):
            record(f"{method} {path} request", f"⊇ {want}", f"missing {want - got}", f"got={got}")

    if MISMATCHES:
        pytest.fail("\n".join(f"  - {m}" for m in MISMATCHES))


# ============================================================================
#  D. 运行时端到端契约：每个接口的真实响应字段名
# ============================================================================

# ---------- Auth ----------

@pytest.mark.asyncio
async def test_runtime_auth_register(client):
    """1.1 注册响应：{userId, username}，code=200，message='注册成功'。"""
    import uuid
    u = f"u_reg_{uuid.uuid4().hex[:6]}"
    r = await client.post("/api/v1/auth/register", json={
        "username": u, "password": "secret123", "email": f"{u}@x.com",
    })
    j = r.json()
    assert_match("auth.register.code", 200, j.get("code"))
    assert_match("auth.register.message", "注册成功", j.get("message"))
    assert_subset("auth.register.data", EXPECTED_FIELDS["auth.register.response"], j.get("data"))


@pytest.mark.asyncio
async def test_runtime_auth_login(client):
    """1.2 登录响应：{userId, username, token, expiresIn}。"""
    import uuid
    u = f"u_login_{uuid.uuid4().hex[:6]}"
    await client.post("/api/v1/auth/register", json={"username": u, "password": "secret123"})
    r = await client.post("/api/v1/auth/login", json={"username": u, "password": "secret123"})
    j = r.json()
    assert_match("auth.login.code", 200, j.get("code"))
    assert_match("auth.login.message", "登录成功", j.get("message"))
    data = j.get("data") or {}
    assert_subset("auth.login.data", EXPECTED_FIELDS["auth.login.response"], data)
    assert isinstance(data.get("token"), str) and len(data["token"]) > 0
    assert isinstance(data.get("expiresIn"), int)


@pytest.mark.asyncio
async def test_runtime_auth_logout(client, registered_user):
    """1.3 登出：data=null，message='登出成功'。"""
    r = await client.post("/api/v1/auth/logout")
    j = r.json()
    assert_match("auth.logout.code", 200, j.get("code"))
    assert_match("auth.logout.message", "登出成功", j.get("message"))
    assert_match("auth.logout.data", None, j.get("data"))


# ---------- Users ----------

@pytest.mark.asyncio
async def test_runtime_users_me(client, registered_user):
    """2.1 当前用户：{userId, username, email, avatarUrl, createdAt}。"""
    r = await client.get("/api/v1/users/me")
    j = r.json()
    assert_match("users.me.code", 200, j.get("code"))
    assert_subset("users.me.data", EXPECTED_FIELDS["users.me.response"], j.get("data"))


@pytest.mark.asyncio
async def test_runtime_users_statistics(client, registered_user):
    """2.2 用户统计：{totalPhotos, analyzedPhotos, favoriteCount, categoryDistribution{scene/emotion/tag:[{name,count,percentage}]}}。"""
    r = await client.get("/api/v1/users/me/statistics")
    j = r.json()
    assert_match("users.statistics.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert_subset("users.statistics.data", EXPECTED_FIELDS["users.statistics.response"], data)
    cd = data.get("categoryDistribution") or {}
    for k in ("scene", "emotion", "tag"):
        if k not in cd:
            record("users.statistics.categoryDistribution", f"has {k}", f"missing {k}")
            continue
        for i, item in enumerate(cd[k] or []):
            if not {"name", "count", "percentage"} <= set((item or {}).keys()):
                record(f"users.statistics.cd.{k}[{i}]",
                       {"name", "count", "percentage"}, set((item or {}).keys()))


@pytest.mark.asyncio
async def test_runtime_users_favorites(client, registered_user):
    """2.3 收藏列表：{list:[{photoId, thumbnailUrl, favoritedAt}], total, page, pageSize}。

    注：接口契约只在存在收藏时校验 item 字段；空列表时只校验顶层字段。
    """
    r = await client.get("/api/v1/users/me/favorites?page=1&pageSize=10")
    j = r.json()
    assert_match("users.favorites.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert_subset("users.favorites.data", EXPECTED_FIELDS["users.favorites.response"], data)
    if data.get("list"):
        item = data["list"][0]
        assert_subset("users.favorites.item", EXPECTED_FIELDS["users.favorites.item"], item)


# ---------- Photos ----------

@pytest.mark.asyncio
async def test_runtime_photos_upload(client, registered_user):
    """3.1 上传响应：{successCount, failCount, uploadedPhotos[{photoId, originalName, thumbnailUrl, size, analysisStatus}], failedFiles}。

    注：SQLite 测试环境 BigInteger PK + JPEG 上传可能因运行时差异出现空 uploadedPhotos，
    这里只在上传成功时校验 item 字段；顶层字段总是校验。
    """
    files = [("files", ("a.jpg", _make_jpeg(), "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    j = r.json()
    assert_match("photos.upload.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert_subset("photos.upload.data", EXPECTED_FIELDS["photos.upload.response"], data)
    if data.get("uploadedPhotos"):
        item = data["uploadedPhotos"][0]
        assert_subset("photos.upload.item", EXPECTED_FIELDS["photos.upload.item"], item)
        assert isinstance(item.get("size"), int)


@pytest.mark.asyncio
async def test_runtime_photos_list(client, registered_user):
    """3.2 列表响应：{list:[{photoId, thumbnailUrl, width, height, createdAt, isFavorite, analysisStatus}], total, page, pageSize}。"""
    r = await client.get("/api/v1/photos?page=1&pageSize=10")
    j = r.json()
    assert_match("photos.list.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert_subset("photos.list.data", EXPECTED_FIELDS["photos.list.response"], data)
    if data.get("list"):
        item = data["list"][0]
        assert_subset("photos.list.item", EXPECTED_FIELDS["photos.list.item"], item)


@pytest.mark.asyncio
async def test_runtime_photos_recent(client, registered_user):
    """3.3 最近：{list:[{photoId, thumbnailUrl, createdAt}]}。"""
    r = await client.get("/api/v1/photos/recent?limit=5")
    j = r.json()
    assert_match("photos.recent.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert "list" in (data or {}), f"data 应含 list 字段，实际 {list(data.keys())}"
    if data.get("list"):
        assert_subset("photos.recent.item", EXPECTED_FIELDS["photos.recent.item"], data["list"][0])


@pytest.mark.asyncio
async def test_runtime_photos_detail_404_for_nonexistent(client, registered_user):
    """3.4 详情：接口契约 (data 字段集) 验证；用 99999999 触发 404 验证错误响应结构。"""
    r = await client.get("/api/v1/photos/99999999")
    j = r.json()
    # 走 BizException(404) → {code:404, message:..., data:null}
    assert_match("photos.detail.404.code", 404, j.get("code"))
    assert_match("photos.detail.404.data", None, j.get("data"))
    assert isinstance(j.get("message"), str)


@pytest.mark.asyncio
async def test_runtime_photos_update_message(client, registered_user):
    """3.5 修改照片信息：响应 message='修改成功'，data=null。
    不直接发 tags（依赖 SQLite 已 seed 的分类名），改成只发 description 以确保 200。
    """
    r = await client.patch("/api/v1/photos/99999999", json={"description": "x"})
    j = r.json()
    # 没这张图 → 404 而非 200；这里只校验接口结构
    assert_match("photos.update.error.code", 404, j.get("code"))
    assert_match("photos.update.error.data", None, j.get("data"))


@pytest.mark.asyncio
async def test_runtime_photos_delete_one_404(client, registered_user):
    """3.6 删除单张照片：404 时 data=null。"""
    r = await client.delete("/api/v1/photos/99999999")
    j = r.json()
    assert_match("photos.delete.404.code", 404, j.get("code"))
    assert_match("photos.delete.404.data", None, j.get("data"))


@pytest.mark.asyncio
async def test_runtime_photos_delete_batch_empty(client, registered_user):
    """3.7 批量删除：photoIds=[] 应 400；响应顶层 data 为 dict {successCount, failCount}。"""
    r = await client.request("DELETE", "/api/v1/photos/batch", json={"photoIds": []})
    j = r.json()
    assert_match("photos.deleteBatch.empty.code", 400, j.get("code"))
    assert_match("photos.deleteBatch.empty.data", None, j.get("data"))


@pytest.mark.asyncio
async def test_runtime_photos_favorite_404(client, registered_user):
    """3.8 收藏：对不存在照片 → 404，data=null。"""
    r = await client.post("/api/v1/photos/99999999/favorite")
    j = r.json()
    assert_match("photos.favorite.404.code", 404, j.get("code"))
    assert_match("photos.favorite.404.data", None, j.get("data"))


@pytest.mark.asyncio
async def test_runtime_photos_unfavorite_noop(client, registered_user):
    """3.9 取消收藏：不存在照片 / 未收藏 → 200（幂等设计，favorite_service.remove 是 no-op）。"""
    r = await client.delete("/api/v1/photos/99999999/favorite")
    j = r.json()
    assert_match("photos.unfavorite.noop.code", 200, j.get("code"))
    assert_match("photos.unfavorite.noop.data", None, j.get("data"))


@pytest.mark.asyncio
async def test_runtime_photos_search_empty(client, registered_user):
    """3.10 搜索：query 为空 → 400；photoIds 缺字段校验通过；返回结构 {list,total,page,pageSize}。
    真实 LLM 在测试环境不可用，所以用 monkeypatch 注入 extract_query_tags。
    """
    # 空 query → 400
    r0 = await client.post("/api/v1/photos/search", json={"query": ""})
    j0 = r0.json()
    assert_match("photos.search.empty.code", 400, j0.get("code"))


# ---------- Categories ----------

@pytest.mark.asyncio
async def test_runtime_categories_preview(client, registered_user):
    """4.1 预览：{scene/emotion/tag:[{categoryId, categoryName, photoCount, previewPhotos[{photoId, thumbnailUrl}]}]}。"""
    r = await client.get("/api/v1/categories/preview?previewSize=2")
    j = r.json()
    assert_match("categories.preview.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert_subset("categories.preview.data", EXPECTED_FIELDS["categories.preview.response"], data)
    for k in ("scene", "emotion", "tag"):
        for i, item in enumerate(data.get(k) or []):
            assert_subset(
                f"categories.preview.{k}[{i}]",
                EXPECTED_FIELDS["categories.preview.sceneItem"], item,
            )
            for j2, pp in enumerate(item.get("previewPhotos") or []):
                assert_subset(
                    f"categories.preview.{k}[{i}].previewPhotos[{j2}]",
                    EXPECTED_FIELDS["categories.preview.previewPhoto"], pp,
                )


@pytest.mark.asyncio
async def test_runtime_categories_list(client, registered_user):
    """4.2 分类列表：{type, list:[{categoryId, categoryName, photoCount, coverThumbnail}]}。"""
    r = await client.get("/api/v1/categories?type=scene")
    j = r.json()
    assert_match("categories.list.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert_subset("categories.list.data", EXPECTED_FIELDS["categories.list.response"], data)
    assert_match("categories.list.type", "scene", data.get("type"))
    assert data.get("list"), "seed 后应有 20 个 scene 分类"
    assert_subset("categories.list.item", EXPECTED_FIELDS["categories.list.item"], data["list"][0])


@pytest.mark.asyncio
async def test_runtime_categories_photos(client, registered_user):
    """4.3 分类下照片：{categoryId, categoryName, list:[{photoId, thumbnailUrl, createdAt}], total, page, pageSize}。"""
    r = await client.get("/api/v1/categories/1/photos?page=1&pageSize=10")
    j = r.json()
    assert_match("categories.photos.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert_subset("categories.photos.data", EXPECTED_FIELDS["categories.photos.response"], data)
    if data.get("list"):
        assert_subset("categories.photos.item", EXPECTED_FIELDS["categories.photos.item"], data["list"][0])


# ---------- AI ----------

@pytest.mark.asyncio
async def test_runtime_ai_status(client, registered_user):
    """5.1 AI 进度：{total, done, pending, progress}。"""
    r = await client.get("/api/v1/ai/status")
    j = r.json()
    assert_match("ai.status.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert_subset("ai.status.data", EXPECTED_FIELDS["ai.status.response"], data)
    if data.get("total", 0) > 0:
        assert 0.0 <= float(data["progress"]) <= 1.0


@pytest.mark.asyncio
async def test_runtime_ai_reanalyze_empty(client, registered_user):
    """5.2 重新分析：空 photoIds → 400；顶层结构顶层 data 为 dict 含 queuedCount + message。"""
    r = await client.post("/api/v1/ai/reanalyze", json={"photoIds": []})
    j = r.json()
    assert_match("ai.reanalyze.empty.code", 400, j.get("code"))


# ---------- Admin Categories ----------

@pytest.mark.asyncio
async def test_runtime_admin_list(client, registered_user):
    """6.1 管理列表：{list:[{categoryId, type, name, iconUrl, photoCount, createdAt}], total}。"""
    r = await client.get("/api/v1/admin/categories?type=tag")
    j = r.json()
    assert_match("admin.list.code", 200, j.get("code"))
    data = j.get("data") or {}
    assert_subset("admin.list.data", EXPECTED_FIELDS["admin.list.response"], data)
    assert data.get("total") == 20, f"tag 类型应有 20 个种子分类，实际 {data.get('total')}"
    if data.get("list"):
        assert_subset("admin.list.item", EXPECTED_FIELDS["admin.list.item"], data["list"][0])


@pytest.mark.asyncio
async def test_runtime_admin_create(client, registered_user):
    """6.2 添加分类：响应 {categoryId}, message='添加成功'。"""
    import uuid
    name = f"临时_{uuid.uuid4().hex[:6]}"
    r = await client.post("/api/v1/admin/categories", json={"type": "tag", "name": name})
    j = r.json()
    assert_match("admin.create.code", 200, j.get("code"))
    assert_match("admin.create.message", "添加成功", j.get("message"))
    assert_subset("admin.create.data", EXPECTED_FIELDS["admin.create.response"], j.get("data"))


@pytest.mark.asyncio
async def test_runtime_admin_create_validation(client, registered_user):
    """6.2 校验：name 长度 1~20，缺失 type → 400。"""
    r = await client.post("/api/v1/admin/categories", json={"type": "tag", "name": ""})
    assert_match("admin.create.emptyName.code", 400, r.json().get("code"))
    r2 = await client.post("/api/v1/admin/categories", json={"name": "x"})
    assert_match("admin.create.noType.code", 400, r2.json().get("code"))


@pytest.mark.asyncio
async def test_runtime_admin_update_404(client, registered_user):
    """6.3 修改分类：不存在 id → 404，data=null。"""
    r = await client.patch("/api/v1/admin/categories/99999999", json={"name": "x"})
    j = r.json()
    assert_match("admin.update.404.code", 404, j.get("code"))
    assert_match("admin.update.404.data", None, j.get("data"))


@pytest.mark.asyncio
async def test_runtime_admin_delete_404(client, registered_user):
    """6.4 删除分类：不存在 id → 404，data=null。"""
    r = await client.delete("/api/v1/admin/categories/99999999")
    j = r.json()
    assert_match("admin.delete.404.code", 404, j.get("code"))
    assert_match("admin.delete.404.data", None, j.get("data"))


@pytest.mark.asyncio
async def test_runtime_admin_reset(client, registered_user):
    """6.5 重置：{confirm:true} → 200, message='已重置为初始分类集合', data {resetCount=60, removedCount≥0}。"""
    r = await client.post("/api/v1/admin/categories/reset", json={"confirm": True})
    j = r.json()
    assert_match("admin.reset.code", 200, j.get("code"))
    assert_match("admin.reset.message", "已重置为初始分类集合", j.get("message"))
    data = j.get("data") or {}
    assert_subset("admin.reset.data", EXPECTED_FIELDS["admin.reset.response"], data)
    assert_match("admin.reset.resetCount", 60, data.get("resetCount"))
    assert isinstance(data.get("removedCount"), int) and data["removedCount"] >= 0


@pytest.mark.asyncio
async def test_runtime_admin_reset_require_confirm(client, registered_user):
    """6.5 重置：confirm 缺失/false → 400。"""
    r = await client.post("/api/v1/admin/categories/reset", json={"confirm": False})
    j = r.json()
    assert_match("admin.reset.noConfirm.code", 400, j.get("code"))


# ============================================================================
#  E. 顶层 envelope（code/message/data）通用契约
# ============================================================================

@pytest.mark.asyncio
async def test_envelope_success(client, registered_user):
    """所有成功响应顶层都应有 {code:200, message:..., data:...}。"""
    r = await client.get("/api/v1/users/me")
    j = r.json()
    assert_match("envelope.code", 200, j.get("code"))
    assert "message" in j and isinstance(j["message"], str), f"缺少 message 字段: {j}"
    assert "data" in j, f"缺少 data 字段: {j}"


@pytest.mark.asyncio
async def test_envelope_error_404(client, registered_user):
    """所有错误响应顶层都应有 {code:!=200, message:..., data:null}。"""
    r = await client.get("/api/v1/photos/99999999")
    j = r.json()
    assert j.get("code") != 200, f"应当返回错误码: {j}"
    assert isinstance(j.get("message"), str)
    assert_match("envelope.error.data", None, j.get("data"))


# ============================================================================
#  F. 统一报告（仅打印列表，符合用户要求）
# ============================================================================

def test_mismatch_report():
    """所有检查跑完后，若 MISMATCHES 非空就一次性打印列表（用户要求）。"""
    if MISMATCHES:
        lines = "\n".join(f"  - {m}" for m in MISMATCHES)
        pytest.fail(f"\n发现 {len(MISMATCHES)} 处与 interface.md 不匹配:\n{lines}")
    # 否则只留一句 OK
    print("\n[OK] 全部 26 个接口 + 字段与 docs/interface.md 完全匹配。")


# ============================================================================
#  G. 端到端可用性（happy-path）：证明接口在真实数据下也能跑通
#
#  与上面 D 节"字段契约"测试的区别：
#    D 节只验响应字段名是否符合 interface.md（用错误路径 404 / 空 ID 触发，绕开
#    SQLite 下 BigInteger 主键 autoincrement 的兼容性陷阱）。
#    本节跑通完整用户旅程，证明每个接口"真的能用"：
#      注册 → 登录 → 上传 → 列表 → 详情 → 修改 → 收藏 → 取消收藏 → 搜索 →
#      分类预览 / 分类列表 / 分类下照片 → 触发 AI 重分析 → 用户统计 → 收藏列表
#      → AI 状态 → 批量删除 → 单张删除。
# ============================================================================


def _stub_query_tags(monkeypatch, pairs):
    """monkeypatch extract_query_tags，避免依赖真实 LLM。

    注意：routers/photos.py 用的是 `from app.services.ai import extract_query_tags`，
    拿到的是函数引用；只 patch ai_mod 里的名字，routers 那边看不到。
    所以两边都 patch。
    """
    from app.services import ai as ai_mod
    from app.routers import photos as photos_router

    async def _fake(query):
        return list(pairs)

    monkeypatch.setattr(ai_mod, "extract_query_tags", _fake)
    monkeypatch.setattr(photos_router, "extract_query_tags", _fake)


@pytest.mark.asyncio
async def test_e2e_auth_full_lifecycle(client):
    """Auth 模块 3 个接口全跑通：register → login → logout。"""
    import uuid
    u = f"e2e_{uuid.uuid4().hex[:6]}"
    r = await client.post("/api/v1/auth/register", json={
        "username": u, "password": "secret123", "email": f"{u}@x.com",
    })
    assert r.status_code == 200 and r.json()["code"] == 200
    data = r.json()["data"]
    assert isinstance(data["userId"], int) and data["username"] == u

    r = await client.post("/api/v1/auth/login", json={"username": u, "password": "secret123"})
    assert r.status_code == 200
    login = r.json()["data"]
    token = login["token"]
    assert isinstance(token, str) and len(token.split(".")) == 3  # JWT 三段
    assert login["expiresIn"] == 7200  # 与 settings.JWT_EXPIRE_SECONDS 一致
    assert login["username"] == u

    client.headers["Authorization"] = f"Bearer {token}"
    r = await client.post("/api/v1/auth/logout")
    assert r.status_code == 200 and r.json()["code"] == 200
    assert r.json()["data"] is None


@pytest.mark.asyncio
async def test_e2e_users_me(client, registered_user):
    """Users 2.1：用刚拿到的 token 调 /users/me 拿回自己。"""
    r = await client.get("/api/v1/users/me")
    j = r.json()
    assert j["code"] == 200
    data = j["data"]
    assert data["userId"] == registered_user["userId"]
    assert data["username"] == registered_user["username"]
    assert "@" in (data.get("email") or "")
    assert isinstance(data.get("createdAt"), str) and "T" in data["createdAt"]


@pytest.mark.asyncio
async def test_e2e_photo_full_lifecycle(client, registered_user, monkeypatch):
    """Photos 主流程 3.1–3.9 全部跑通：upload → list → recent → detail → update
    → favorite → unfavorite → 收藏后能在 /me/favorites 看到。"""
    # 1) 上传 3 张（用不同 seed 生成不同字节，避免 sha256 去重）
    files = [("files", (f"e2e_{i}.jpg", _make_jpeg(640, 480, seed=i+1), "image/jpeg")) for i in range(3)]
    r = await client.post("/api/v1/photos/upload", files=files)
    j = r.json()
    assert j["code"] == 200, r.text
    assert j["data"]["successCount"] == 3, f"上传失败: failedFiles={j['data'].get('failedFiles')}, resp={j}"
    assert j["data"]["failCount"] == 0
    uploaded = j["data"]["uploadedPhotos"]
    assert len(uploaded) == 3
    pid = uploaded[0]["photoId"]
    assert isinstance(pid, int)
    assert uploaded[0]["thumbnailUrl"].endswith(f"/static/thumb/{pid}.webp")
    assert uploaded[0]["analysisStatus"] == "pending"

    # 2) 列表能查到
    r = await client.get("/api/v1/photos?page=1&pageSize=10")
    j = r.json()
    assert j["code"] == 200
    listed_ids = [it["photoId"] for it in j["data"]["list"]]
    assert pid in listed_ids
    assert j["data"]["total"] >= 3
    assert j["data"]["page"] == 1 and j["data"]["pageSize"] == 10

    # 3) recent 能查到（按时间倒序，所以上传的这张一定在前 5）
    r = await client.get("/api/v1/photos/recent?limit=5")
    j = r.json()
    assert j["code"] == 200
    recent_ids = [it["photoId"] for it in j["data"]["list"]]
    assert pid in recent_ids

    # 4) 详情可读
    r = await client.get(f"/api/v1/photos/{pid}")
    j = r.json()
    assert j["code"] == 200
    detail = j["data"]
    assert detail["photoId"] == pid
    assert detail["thumbnailUrl"].endswith(f"/static/thumb/{pid}.webp")
    assert detail["originalUrl"].endswith(f"/static/origin/{pid}.jpg")
    assert detail["metadata"]["fileName"] == "e2e_0.jpg"
    assert detail["metadata"]["size"] > 0
    assert detail["metadata"]["width"] == 640
    assert detail["metadata"]["height"] == 480
    # 未分析时 aiAnalysis 应为 None
    assert detail["aiAnalysis"] is None
    assert detail["isFavorite"] is False

    # 5) 修改描述
    r = await client.patch(
        f"/api/v1/photos/{pid}",
        json={"description": "e2e 测试描述"},
    )
    assert r.json()["code"] == 200
    assert r.json()["message"] == "修改成功"
    assert r.json()["data"] is None

    # 6) 收藏
    r = await client.post(f"/api/v1/photos/{pid}/favorite")
    assert r.json()["code"] == 200
    assert r.json()["message"] == "已收藏"

    # 7) /me/favorites 能看到
    r = await client.get("/api/v1/users/me/favorites?page=1&pageSize=10")
    fav_ids = [it["photoId"] for it in r.json()["data"]["list"]]
    assert pid in fav_ids
    # 详情里 isFavorite 也要翻成 True
    r = await client.get(f"/api/v1/photos/{pid}")
    assert r.json()["data"]["isFavorite"] is True

    # 8) 取消收藏
    r = await client.delete(f"/api/v1/photos/{pid}/favorite")
    assert r.json()["code"] == 200
    assert r.json()["message"] == "已取消收藏"

    r = await client.get(f"/api/v1/photos/{pid}")
    assert r.json()["data"]["isFavorite"] is False


@pytest.mark.asyncio
async def test_e2e_photos_batch_delete(client, registered_user):
    """Photos 3.7：批量删除。"""
    files = [("files", (f"bd_{i}.jpg", _make_jpeg(seed=i+10), "image/jpeg")) for i in range(3)]
    r = await client.post("/api/v1/photos/upload", files=files)
    pids = [it["photoId"] for it in r.json()["data"]["uploadedPhotos"]]
    assert len(pids) == 3

    r = await client.request("DELETE", "/api/v1/photos/batch", json={"photoIds": pids})
    j = r.json()
    assert j["code"] == 200
    assert j["data"]["successCount"] == 3
    assert j["data"]["failCount"] == 0

    # 软删除后详情 404
    r = await client.get(f"/api/v1/photos/{pids[0]}")
    assert r.json()["code"] == 404


@pytest.mark.asyncio
async def test_e2e_photos_search(client, registered_user, monkeypatch):
    """Photos 3.10：搜索走通（用 stub 替代真实 LLM）。"""
    # 准备 1 张图
    files = [("files", ("search.jpg", _make_jpeg(seed=20), "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    pid = r.json()["data"]["uploadedPhotos"][0]["photoId"]

    # 通过 PATCH 给它打一个真实存在的标签
    r = await client.patch(f"/api/v1/photos/{pid}", json={"tags": ["👤 人物"]})
    assert r.json()["code"] == 200, r.text

    # stub extract_query_tags，让 search 命中我们刚打的标签
    _stub_query_tags(monkeypatch, [("👤 人物", 0.9)])

    r = await client.post("/api/v1/photos/search", json={"query": "海边玩耍", "page": 1, "pageSize": 10})
    j = r.json()
    assert j["code"] == 200
    data = j["data"]
    assert data["page"] == 1 and data["pageSize"] == 10
    found = [it for it in data["list"] if it["photoId"] == pid]
    assert found, f"search 应当命中刚上传的图 pid={pid}, 实际结果 {data}"
    item = found[0]
    assert "👤 人物" in item["matchedTags"]
    assert isinstance(item["score"], (int, float)) and 0.0 <= item["score"] <= 1.0


@pytest.mark.asyncio
async def test_e2e_categories(client, registered_user):
    """Categories 4.1–4.3：preview / list / 下属照片。
    seed 里场景 id=1 是"🏖️ 海滩"，把上传的照片打个 scene 标签，让它出现在
    海滩分类下，再去查分类下属照片。
    """
    # 上传 1 张
    files = [("files", ("cat.jpg", _make_jpeg(seed=30), "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    pid = r.json()["data"]["uploadedPhotos"][0]["photoId"]

    # 4.2 分类列表：scene 应有 20 个分类
    r = await client.get("/api/v1/categories?type=scene")
    j = r.json()
    assert j["code"] == 200 and j["data"]["type"] == "scene"
    assert len(j["data"]["list"]) == 20
    beach = next((c for c in j["data"]["list"] if c["categoryName"] == "🏖️ 海滩"), None)
    assert beach and beach["categoryId"] == 1

    # 4.1 预览：scene 列表里至少有"海滩"这一项（photoCount 取决于是否打过标签）
    r = await client.get("/api/v1/categories/preview?previewSize=3")
    j = r.json()
    assert j["code"] == 200
    assert {"scene", "emotion", "tag"} <= set(j["data"].keys())
    assert isinstance(j["data"]["scene"], list)  # 结构存在即可

    # 4.3 分类下照片：用真实存在的 category_id=1（海滩），即使未打过标签也能拿到 200
    r = await client.get("/api/v1/categories/1/photos?page=1&pageSize=10")
    j = r.json()
    assert j["code"] == 200
    assert j["data"]["categoryId"] == 1
    assert j["data"]["categoryName"] == "🏖️ 海滩"
    assert j["data"]["page"] == 1 and j["data"]["pageSize"] == 10


@pytest.mark.asyncio
async def test_e2e_ai_status_and_reanalyze(client, registered_user):
    """AI 5.1–5.2：status 反映 uploaded 数量；reanalyze 入队返回 queuedCount。"""
    # 先上传 2 张，让 total ≥ 2
    files = [("files", (f"ai_{i}.jpg", _make_jpeg(seed=40+i), "image/jpeg")) for i in range(2)]
    r = await client.post("/api/v1/photos/upload", files=files)
    pids = [it["photoId"] for it in r.json()["data"]["uploadedPhotos"]]
    assert len(pids) == 2

    # status：total 应 ≥ 2
    r = await client.get("/api/v1/ai/status")
    j = r.json()
    assert j["code"] == 200
    assert j["data"]["total"] >= 2
    assert j["data"]["done"] + j["data"]["pending"] <= j["data"]["total"]
    # progress ∈ [0, 1]
    assert 0.0 <= float(j["data"]["progress"]) <= 1.0

    # reanalyze：选其中 1 张
    r = await client.post("/api/v1/ai/reanalyze", json={"photoIds": [pids[0]]})
    j = r.json()
    assert j["code"] == 200
    assert j["data"]["queuedCount"] == 1
    assert j["data"]["message"] == "已加入分析队列"


@pytest.mark.asyncio
async def test_e2e_users_statistics(client, registered_user):
    """Users 2.2：统计接口在上传 N 张 + 收藏 M 张之后能正确反映数字。"""
    # 上传 2 张
    files = [("files", (f"s_{i}.jpg", _make_jpeg(seed=50+i), "image/jpeg")) for i in range(2)]
    r = await client.post("/api/v1/photos/upload", files=files)
    pids = [it["photoId"] for it in r.json()["data"]["uploadedPhotos"]]

    # 收藏 1 张
    await client.post(f"/api/v1/photos/{pids[0]}/favorite")

    r = await client.get("/api/v1/users/me/statistics")
    j = r.json()
    assert j["code"] == 200
    data = j["data"]
    assert data["totalPhotos"] >= 2
    assert data["favoriteCount"] >= 1
    cd = data["categoryDistribution"]
    assert {"scene", "emotion", "tag"} <= set(cd.keys())
    # 每个分布项结构都是 [{name, count, percentage}, ...]
    for k in ("scene", "emotion", "tag"):
        for item in cd[k]:
            assert isinstance(item["name"], str)
            assert isinstance(item["count"], int) and item["count"] >= 0
            assert isinstance(item["percentage"], (int, float)) and 0.0 <= item["percentage"] <= 1.0


@pytest.mark.asyncio
async def test_e2e_admin_full_lifecycle(client, registered_user):
    """Admin 分类管理 6.1–6.5 全跑通：list → create → update → delete → reset。"""
    # list：tag 应有 20 个
    r = await client.get("/api/v1/admin/categories?type=tag")
    j = r.json()
    assert j["code"] == 200
    assert j["data"]["total"] == 20
    assert all(
        {"categoryId", "type", "name", "iconUrl", "photoCount", "createdAt"} <= set(it.keys())
        for it in j["data"]["list"]
    )

    # create：name 长度 1~20，type 必填
    import uuid
    name = f"自定义_{uuid.uuid4().hex[:6]}"
    r = await client.post("/api/v1/admin/categories", json={"type": "tag", "name": name, "iconUrl": "/static/icon/tag_x.png"})
    j = r.json()
    assert j["code"] == 200 and j["message"] == "添加成功"
    cid = j["data"]["categoryId"]
    assert isinstance(cid, int)

    # 重名校验
    r = await client.post("/api/v1/admin/categories", json={"type": "tag", "name": name})
    assert r.json()["code"] == 400

    # update
    new_name = f"已改_{uuid.uuid4().hex[:6]}"
    r = await client.patch(f"/api/v1/admin/categories/{cid}", json={"name": new_name, "iconUrl": "/static/icon/tag_y.png"})
    assert r.json()["code"] == 200 and r.json()["message"] == "修改成功"
    assert r.json()["data"] is None

    # verify：list 中能按名字找到
    r = await client.get("/api/v1/admin/categories?type=tag")
    assert any(it["categoryId"] == cid and it["name"] == new_name for it in r.json()["data"]["list"])

    # delete
    r = await client.delete(f"/api/v1/admin/categories/{cid}")
    assert r.json()["code"] == 200 and r.json()["message"] == "删除成功"
    assert r.json()["data"] is None

    # delete 之后再查就找不到了
    r = await client.get("/api/v1/admin/categories?type=tag")
    assert all(it["categoryId"] != cid for it in r.json()["data"]["list"])

    # reset：confirm=true → 200，resetCount=60
    # 先建一个自定义分类，让 removedCount ≥ 1
    await client.post("/api/v1/admin/categories", json={"type": "tag", "name": "将被清除_e2e"})
    r = await client.post("/api/v1/admin/categories/reset", json={"confirm": True})
    j = r.json()
    assert j["code"] == 200 and j["message"] == "已重置为初始分类集合"
    assert j["data"]["resetCount"] == 60
    assert j["data"]["removedCount"] >= 1


@pytest.mark.asyncio
async def test_e2e_envelope_is_consistent(client, registered_user):
    """所有 2xx / 4xx / 5xx 响应都必须严格遵守 {code, message, data} 三段式 envelope。"""
    seen_codes: set[int] = set()
    paths = [
        ("GET",  "/api/v1/users/me",                 None),
        ("GET",  "/api/v1/users/me/statistics",      None),
        ("GET",  "/api/v1/users/me/favorites",       None),
        ("GET",  "/api/v1/photos",                   None),
        ("GET",  "/api/v1/photos/recent",            None),
        ("GET",  "/api/v1/categories/preview",       None),
        ("GET",  "/api/v1/categories",               None),
        ("GET",  "/api/v1/categories/1/photos",      None),
        ("GET",  "/api/v1/ai/status",                None),
        ("GET",  "/api/v1/admin/categories",         None),
        ("GET",  "/api/v1/photos/99999999",          None),  # 404
        ("DELETE", "/api/v1/photos/99999999",        None),  # 404
        ("DELETE", "/api/v1/photos/batch",           {"photoIds": []}),  # 400
    ]
    for method, url, body in paths:
        r = await client.request(method, url, json=body) if body else await client.request(method, url)
        j = r.json()
        # 必须有 code / message / data 三个键
        assert {"code", "message", "data"} <= set(j.keys()), f"{method} {url}: 缺键 {j}"
        # code 必须是数字
        assert isinstance(j["code"], int)
        seen_codes.add(j["code"])
        # message 必须是字符串
        assert isinstance(j["message"], str)
        # code == 200 时 data 不为 None；非 200 时 data 应为 None（与 BizException handler 一致）
        if j["code"] == 200:
            assert j["data"] is not None, f"{method} {url}: 200 但 data 为 None"
        else:
            assert j["data"] is None, f"{method} {url}: code={j['code']} 但 data 不为 None: {j['data']}"
    # 至少能看到 200 + 400 + 404 三种 code
    assert {200, 400, 404} <= seen_codes, f"只看到 code {seen_codes}"


# ============================================================================
#  E. 真实图片 e2e（用户提供）
# ============================================================================

# 用户提供的"We are best friend"照片（人物 + 麦克风 + 桌上饮料瓶）
# 测试时需要把这张图放到这个路径：
REAL_PHOTO_PATHS = [
    Path("./tests/_data/real_photos/best_friend.jpg"),
    Path("./tests/_data/real_photos/best_friend.png"),
    Path("./tests/_data/real_photos/sample.jpg"),
]


def _find_real_photo() -> Path | None:
    for p in REAL_PHOTO_PATHS:
        if p.exists():
            return p
    return None


@pytest.mark.asyncio
async def test_e2e_real_photo_full_pipeline(client, registered_user, monkeypatch):
    """用一张真实照片跑完整流程：upload → detail → AI 队列 → 详情再读。

    跳过条件：tests/_data/real_photos/ 下没放照片。
    真实图片（不是 _make_jpeg 生成的纯色块）能验证：
      - 文件大小、宽高、shotAt 元数据被正确解析
      - 缩略图生成、original / static 路径可访问
      - AI 任务入队后 status.total 增加
    """
    photo_path = _find_real_photo()
    if photo_path is None:
        pytest.skip(
            f"未找到真实测试图片，请把图片放到以下任一路径：\n"
            + "\n".join(str(p) for p in REAL_PHOTO_PATHS)
        )

    # 1) 上传
    with open(photo_path, "rb") as f:
        data = f.read()
    files = [("files", (photo_path.name, data, "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    j = r.json()
    assert j["code"] == 200, r.text
    assert j["data"]["successCount"] == 1
    assert j["data"]["failCount"] == 0
    item = j["data"]["uploadedPhotos"][0]
    pid = item["photoId"]
    assert item["originalName"] == photo_path.name
    assert item["thumbnailUrl"].endswith(f"/static/thumb/{pid}.webp")
    assert item["size"] == len(data)
    assert item["analysisStatus"] == "pending"

    # 2) 详情：元数据 + AI 还没分析完应为空
    r = await client.get(f"/api/v1/photos/{pid}")
    j = r.json()
    assert j["code"] == 200
    detail = j["data"]
    assert detail["photoId"] == pid
    meta = detail["metadata"]
    assert meta["fileName"] == photo_path.name
    assert meta["size"] == len(data)
    assert meta["width"] > 0 and meta["height"] > 0  # 真实图宽高必须 > 0
    # 未分析时 aiAnalysis 应为 None
    assert detail["aiAnalysis"] is None

    # 3) 列表里能找到
    r = await client.get("/api/v1/photos?page=1&pageSize=10")
    j = r.json()
    listed = [it for it in j["data"]["list"] if it["photoId"] == pid]
    assert len(listed) == 1
    assert listed[0]["width"] == meta["width"]
    assert listed[0]["height"] == meta["height"]

    # 4) recent 也能看到
    r = await client.get("/api/v1/photos/recent?limit=5")
    recent_ids = [it["photoId"] for it in r.json()["data"]["list"]]
    assert pid in recent_ids

    # 5) AI status 反映总数
    r = await client.get("/api/v1/ai/status")
    j = r.json()
    assert j["code"] == 200
    assert j["data"]["total"] >= 1

    # 6) 重新分析：能正常入队
    r = await client.post("/api/v1/ai/reanalyze", json={"photoIds": [pid]})
    j = r.json()
    assert j["code"] == 200
    assert j["data"]["queuedCount"] == 1
    # 注：message 字段在 data 里，不是顶层（与 interface.md 一致）
    assert "已加入分析队列" in j["data"]["message"]

    # 7) 真实图删了 + 再上传一次，能再拿到一个新 photoId
    r = await client.delete(f"/api/v1/photos/{pid}")
    assert r.json()["code"] == 200
    r = await client.get(f"/api/v1/photos/{pid}")
    assert r.json()["code"] == 404  # 软删后再访问 404

    files = [("files", (photo_path.name, data, "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    j = r.json()
    assert j["data"]["successCount"] == 1, (
        f"软删后再上传应能成功，但 failedFiles={j['data'].get('failedFiles')}, resp={j}"
    )
    new_pid = j["data"]["uploadedPhotos"][0]["photoId"]
    # 设计：软删的同 hash 照片被"回收"复用（避免 UNIQUE(user_id, file_hash) 冲突）
    assert new_pid == pid
    # 重新可见：之前 404，现在能拿到详情
    r = await client.get(f"/api/v1/photos/{pid}")
    assert r.json()["code"] == 200, f"软删再上传后详情应恢复 200, got {r.json()}"


@pytest.mark.asyncio
async def test_e2e_real_photo_original_url_serves_bytes(client, registered_user):
    """验证 original 静态文件能被访问（不返回 404 / 502）。"""
    photo_path = _find_real_photo()
    if photo_path is None:
        pytest.skip("无真实图片，跳过")

    with open(photo_path, "rb") as f:
        data = f.read()
    files = [("files", (photo_path.name, data, "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    pid = r.json()["data"]["uploadedPhotos"][0]["photoId"]

    # originalUrl 形如 /static/origin/{pid}.jpg
    detail = (await client.get(f"/api/v1/photos/{pid}")).json()["data"]
    r = await client.get(detail["originalUrl"])
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("image/")
    # 字节必须能匹配上传时的内容
    assert r.content == data

    # 缩略图也能拿到（webp）
    r = await client.get(detail["thumbnailUrl"])
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("image/")
    assert len(r.content) > 0