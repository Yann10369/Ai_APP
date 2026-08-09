"""照片路由相关测试。"""
import io

import pytest
from PIL import Image
from sqlalchemy import select

from app.database import AsyncSessionLocal
from app.models import Category, CategoryType, Photo, PhotoCategory


def make_jpeg_bytes() -> bytes:
    img = Image.new("RGB", (640, 480), color=(120, 80, 200))
    buf = io.BytesIO()
    img.save(buf, "JPEG")
    return buf.getvalue()


@pytest.mark.asyncio
async def test_upload_and_detail(client, registered_user):
    files = [("files", ("a.jpg", make_jpeg_bytes(), "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    assert r.status_code == 200, r.text
    data = r.json()["data"]
    assert data["successCount"] == 1
    photo_id = data["uploadedPhotos"][0]["photoId"]

    r2 = await client.get(f"/api/v1/photos/{photo_id}")
    assert r2.json()["code"] == 200
    assert r2.json()["data"]["photoId"] == photo_id


@pytest.mark.asyncio
async def test_list_photos(client, registered_user):
    files = [("files", (f"p{i}.jpg", make_jpeg_bytes(), "image/jpeg")) for i in range(3)]
    await client.post("/api/v1/photos/upload", files=files)
    r = await client.get("/api/v1/photos?page=1&pageSize=10")
    assert r.json()["code"] == 200
    assert r.json()["data"]["total"] >= 3


@pytest.mark.asyncio
async def test_favorite_unfavorite(client, registered_user):
    files = [("files", ("x.jpg", make_jpeg_bytes(), "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    pid = r.json()["data"]["uploadedPhotos"][0]["photoId"]
    fr = await client.post(f"/api/v1/photos/{pid}/favorite")
    assert fr.json()["code"] == 200
    fav = await client.get("/api/v1/users/me/favorites")
    assert any(item["photoId"] == pid for item in fav.json()["data"]["list"])
    await client.delete(f"/api/v1/photos/{pid}/favorite")
    fav2 = await client.get("/api/v1/users/me/favorites")
    assert not any(item["photoId"] == pid for item in fav2.json()["data"]["list"])


@pytest.mark.asyncio
async def test_delete_photo(client, registered_user):
    files = [("files", ("d.jpg", make_jpeg_bytes(), "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    pid = r.json()["data"]["uploadedPhotos"][0]["photoId"]
    dr = await client.delete(f"/api/v1/photos/{pid}")
    assert dr.json()["code"] == 200
    r2 = await client.get(f"/api/v1/photos/{pid}")
    assert r2.json()["code"] == 404


# ---------- /api/v1/photos/filter 多标签筛选 ----------

async def _pick_categories() -> dict[CategoryType, int]:
    """从 seed 各取一个分类 id（scene/emotion/tag）。"""
    async with AsyncSessionLocal() as db:
        out: dict[CategoryType, int] = {}
        for t in CategoryType:
            c = (await db.execute(
                select(Category).where(Category.type == t).limit(1)
            )).scalar_one_or_none()
            if c is not None:
                out[t] = c.category_id
        return out


async def _seed_photo_categories(user_id: int, photo_id: int, cat_ids: list[int]) -> None:
    """把 photo 与给定的 cat_ids 写入 photo_categories（测试 fixture 用）。"""
    async with AsyncSessionLocal() as db:
        for cid in cat_ids:
            db.add(PhotoCategory(photo_id=photo_id, category_id=cid, confidence=0.9))
        await db.commit()


@pytest.mark.asyncio
async def test_filter_all_none_returns_400(client, registered_user):
    """三个 id 全为空 → 400。"""
    r = await client.post("/api/v1/photos/filter", json={})
    assert r.json()["code"] == 400


@pytest.mark.asyncio
async def test_filter_single_scene_id(client, registered_user):
    """只传 sceneId：返回该场景下的所有照片。"""
    cats = await _pick_categories()
    scene_id = cats[CategoryType.scene]

    files = [("files", (f"s{i}.jpg", make_jpeg_bytes(), "image/jpeg")) for i in range(3)]
    r = await client.post("/api/v1/photos/upload", files=files)
    pids = [item["photoId"] for item in r.json()["data"]["uploadedPhotos"]]
    await _seed_photo_categories(registered_user["userId"], pids[0], [scene_id])
    await _seed_photo_categories(registered_user["userId"], pids[1], [scene_id])
    # pids[2] 不打 scene tag

    r2 = await client.post("/api/v1/photos/filter", json={"sceneId": scene_id})
    data = r2.json()["data"]
    assert r2.json()["code"] == 200
    assert data["total"] == 2
    assert set(x["photoId"] for x in data["list"]) == {pids[0], pids[1]}
    for item in data["list"]:
        assert item["score"] == 1.0
        assert len(item["matchedTags"]) == 1


@pytest.mark.asyncio
async def test_filter_cross_type_and_semantics(client, registered_user):
    """scene + emotion 同时传：必须两个都命中才返回。"""
    cats = await _pick_categories()
    scene_id = cats[CategoryType.scene]
    emotion_id = cats[CategoryType.emotion]

    files = [("files", (f"x{i}.jpg", make_jpeg_bytes(), "image/jpeg")) for i in range(3)]
    r = await client.post("/api/v1/photos/upload", files=files)
    pids = [item["photoId"] for item in r.json()["data"]["uploadedPhotos"]]
    # pids[0]: 只 scene；pids[1]: scene + emotion；pids[2]: 只 emotion
    await _seed_photo_categories(registered_user["userId"], pids[0], [scene_id])
    await _seed_photo_categories(registered_user["userId"], pids[1], [scene_id, emotion_id])
    await _seed_photo_categories(registered_user["userId"], pids[2], [emotion_id])

    r2 = await client.post(
        "/api/v1/photos/filter",
        json={"sceneId": scene_id, "emotionId": emotion_id},
    )
    data = r2.json()["data"]
    assert data["total"] == 1
    assert data["list"][0]["photoId"] == pids[1]
    assert len(data["list"][0]["matchedTags"]) == 2


@pytest.mark.asyncio
async def test_filter_nonexistent_id_silently_dropped(client, registered_user):
    """不存在的 category_id 静默丢弃该类型字段。"""
    cats = await _pick_categories()
    scene_id = cats[CategoryType.scene]

    files = [("files", ("n.jpg", make_jpeg_bytes(), "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    pid = r.json()["data"]["uploadedPhotos"][0]["photoId"]
    await _seed_photo_categories(registered_user["userId"], pid, [scene_id])

    # emotionId=99999 不存在 → 视为不筛选 emotion，sceneId 仍生效
    r2 = await client.post(
        "/api/v1/photos/filter",
        json={"sceneId": scene_id, "emotionId": 99999},
    )
    assert r2.json()["code"] == 200
    assert r2.json()["data"]["total"] == 1
    assert r2.json()["data"]["list"][0]["photoId"] == pid


@pytest.mark.asyncio
async def test_filter_id_type_mismatch_still_works(client, registered_user):
    """sceneId 传了一个 emotion 的 id：service 不校验类型，按 id 查照样命中。"""
    cats = await _pick_categories()
    scene_id = cats[CategoryType.scene]
    emotion_id = cats[CategoryType.emotion]

    files = [("files", ("m.jpg", make_jpeg_bytes(), "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    pid = r.json()["data"]["uploadedPhotos"][0]["photoId"]
    await _seed_photo_categories(registered_user["userId"], pid, [scene_id])

    # 把 emotion 的 id 传给 sceneId 字段 → service 不查类型，按 id 命中
    r2 = await client.post(
        "/api/v1/photos/filter",
        json={"sceneId": emotion_id, "tagId": scene_id},
    )
    data = r2.json()["data"]
    assert r2.json()["code"] == 200
    # scene_id 这张照片确实有；emotion_id 这张照片没标 → 交集为空
    assert data["total"] == 0


@pytest.mark.asyncio
async def test_filter_no_match_returns_empty(client, registered_user):
    """合法 id 但没有任何照片命中 → total=0，list=[]。"""
    cats = await _pick_categories()
    scene_id = cats[CategoryType.scene]

    # 上传一张照片但不打 scene tag
    files = [("files", ("z.jpg", make_jpeg_bytes(), "image/jpeg"))]
    await client.post("/api/v1/photos/upload", files=files)

    r2 = await client.post("/api/v1/photos/filter", json={"sceneId": scene_id})
    assert r2.json()["code"] == 200
    assert r2.json()["data"]["total"] == 0
    assert r2.json()["data"]["list"] == []
