"""测试公用 fixtures。"""
import asyncio
import os
import shutil
from pathlib import Path

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

# 测试环境：覆盖 settings，再 import app
TEST_DB_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "mysql+asyncmy://root:password@127.0.0.1:3306/ai_album_test",
)
TEST_DATA_DIR = "./tests/_data"


@pytest.fixture(scope="session", autouse=True)
def _setup_env():
    os.environ["DATABASE_URL"] = TEST_DB_URL
    os.environ["DATA_DIR"] = TEST_DATA_DIR
    os.environ["JWT_SECRET"] = "test-secret"
    Path(TEST_DATA_DIR).mkdir(parents=True, exist_ok=True)
    yield
    if Path(TEST_DATA_DIR).exists():
        shutil.rmtree(TEST_DATA_DIR, ignore_errors=True)


@pytest_asyncio.fixture(scope="session")
async def engine(_setup_env):
    eng = create_async_engine(TEST_DB_URL, echo=False)
    yield eng
    await eng.dispose()


@pytest_asyncio.fixture(scope="session", autouse=True)
async def _prepare_db(engine):
    from app.database import Base
    import app.models  # noqa: F401 触发模型注册

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)

    from app.database import run_sql_file
    await run_sql_file("migrations/002_seed_categories.sql")
    yield


@pytest_asyncio.fixture
async def db(engine):
    Session = async_sessionmaker(engine, expire_on_commit=False)
    async with Session() as s:
        yield s


@pytest_asyncio.fixture
async def client():
    from app.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest_asyncio.fixture
async def registered_user(client):
    resp = await client.post("/api/v1/auth/register", json={
        "username": "alice",
        "password": "secret123",
        "email": "alice@example.com",
    })
    assert resp.status_code == 200, resp.text
    login = await client.post("/api/v1/auth/login", json={"username": "alice", "password": "secret123"})
    token = login.json()["data"]["token"]
    client.headers["Authorization"] = f"Bearer {token}"
    return {"token": token, "userId": login.json()["data"]["userId"]}
