"""用户侧分类路由相关测试。"""
import io
import pytest
from PIL import Image


def make_jpeg_bytes() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (100, 100), (50, 50, 50)).save(buf, "JPEG")
    return buf.getvalue()


@pytest.mark.asyncio
async def test_categories_preview(client, registered_user):
    files = [("files", ("c.jpg", make_jpeg_bytes(), "image/jpeg"))]
    await client.post("/api/v1/photos/upload", files=files)
    r = await client.get("/api/v1/categories/preview?previewSize=2")
    assert r.json()["code"] == 200
    data = r.json()["data"]
    assert "scene" in data and "emotion" in data and "tag" in data


@pytest.mark.asyncio
async def test_list_categories_by_type(client, registered_user):
    r = await client.get("/api/v1/categories?type=scene")
    assert r.json()["code"] == 200
    assert r.json()["data"]["type"] == "scene"
    assert len(r.json()["data"]["list"]) == 20


@pytest.mark.asyncio
async def test_category_photos(client, registered_user):
    files = [("files", ("cp.jpg", make_jpeg_bytes(), "image/jpeg"))]
    await client.post("/api/v1/photos/upload", files=files)
    r = await client.get("/api/v1/categories/1/photos?page=1&pageSize=10")
    assert r.json()["code"] == 200
    assert r.json()["data"]["categoryId"] == 1
