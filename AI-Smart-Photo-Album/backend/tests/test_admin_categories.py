"""管理员分类路由相关测试。"""
import pytest


@pytest.mark.asyncio
async def test_admin_list(client, registered_user):
    r = await client.get("/api/v1/admin/categories?type=tag")
    assert r.json()["code"] == 200
    assert r.json()["data"]["total"] == 20


@pytest.mark.asyncio
async def test_admin_create_update_delete(client, registered_user):
    r = await client.post("/api/v1/admin/categories", json={
        "type": "tag", "name": "测试标签"
    })
    assert r.json()["code"] == 200
    cid = r.json()["data"]["categoryId"]

    r2 = await client.post("/api/v1/admin/categories", json={"type": "tag", "name": "测试标签"})
    assert r2.json()["code"] == 400

    r3 = await client.patch(f"/api/v1/admin/categories/{cid}", json={"name": "新测试"})
    assert r3.json()["code"] == 200

    r4 = await client.delete(f"/api/v1/admin/categories/{cid}")
    assert r4.json()["code"] == 200


@pytest.mark.asyncio
async def test_admin_reset(client, registered_user):
    await client.post("/api/v1/admin/categories", json={"type": "tag", "name": "将被清除"})
    r = await client.post("/api/v1/admin/categories/reset", json={"confirm": True})
    assert r.json()["code"] == 200
    data = r.json()["data"]
    assert data["resetCount"] == 60
    assert data["removedCount"] >= 1
