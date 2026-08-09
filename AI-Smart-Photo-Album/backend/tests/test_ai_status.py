"""AI 状态路由相关测试。"""
import io
import pytest
from PIL import Image


def make_jpeg_bytes() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (100, 100), (0, 0, 0)).save(buf, "JPEG")
    return buf.getvalue()


@pytest.mark.asyncio
async def test_ai_status_initial(client, registered_user):
    r = await client.get("/api/v1/ai/status")
    assert r.json()["code"] == 200
    data = r.json()["data"]
    assert data["total"] == 0
    assert data["done"] == 0


@pytest.mark.asyncio
async def test_reanalyze_queue(client, registered_user):
    files = [("files", ("r.jpg", make_jpeg_bytes(), "image/jpeg"))]
    r = await client.post("/api/v1/photos/upload", files=files)
    pid = r.json()["data"]["uploadedPhotos"][0]["photoId"]
    r2 = await client.post("/api/v1/ai/reanalyze", json={"photoIds": [pid]})
    assert r2.json()["code"] == 200
    assert r2.json()["data"]["queuedCount"] == 1
