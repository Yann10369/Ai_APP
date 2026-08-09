"""认证路由相关测试。"""
import pytest


@pytest.mark.asyncio
async def test_register_login_logout(client):
    r = await client.post("/api/v1/auth/register", json={
        "username": "bob", "password": "secret123", "email": "bob@example.com"
    })
    assert r.json()["code"] == 200

    r = await client.post("/api/v1/auth/login", json={"username": "bob", "password": "secret123"})
    assert r.json()["code"] == 200
    token = r.json()["data"]["token"]

    r2 = await client.get("/api/v1/users/me")
    assert r2.json()["code"] == 401

    client.headers["Authorization"] = f"Bearer {token}"
    r3 = await client.get("/api/v1/users/me")
    assert r3.json()["code"] == 200
    assert r3.json()["data"]["username"] == "bob"

    r4 = await client.post("/api/v1/auth/logout")
    assert r4.json()["code"] == 200


@pytest.mark.asyncio
async def test_register_duplicate(client):
    await client.post("/api/v1/auth/register", json={"username": "carol", "password": "secret123"})
    r = await client.post("/api/v1/auth/register", json={"username": "carol", "password": "secret123"})
    assert r.json()["code"] == 400


@pytest.mark.asyncio
async def test_login_wrong_password(client):
    await client.post("/api/v1/auth/register", json={"username": "dave", "password": "secret123"})
    r = await client.post("/api/v1/auth/login", json={"username": "dave", "password": "wrong"})
    assert r.json()["code"] == 400
