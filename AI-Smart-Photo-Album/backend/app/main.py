"""FastAPI 入口：lifespan 跑迁移 + seed + 启 worker；注册异常处理、路由、静态文件。"""
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import select

from app.config import settings
from app.database import engine, run_sql_file
from app.exceptions import BizException
from app.models import Category
from app.models.ai_task import start_worker, stop_worker
from app.routers import admin_categories, ai, auth, categories, photos, users

logging.basicConfig(level=getattr(logging, settings.LOG_LEVEL, logging.INFO))
log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期：迁移、建表、seed、启停 worker。"""
    # 顺序敏感：先建表，再跑增量迁移（003 给 ai_tasks 加 worker 字段）
    for mig in ("migrations/001_schema.sql", "migrations/003_ai_task_worker.sql"):
        if Path(mig).exists():
            await run_sql_file(mig)

    async with engine.begin() as conn:
        if (await conn.execute(select(Category).limit(1))).first() is None:
            log.info("分类字典为空，执行 seed")
            if Path("migrations/002_seed_categories.sql").exists():
                await run_sql_file("migrations/002_seed_categories.sql")
        else:
            log.info("分类字典已存在，跳过 seed")

    await start_worker()
    yield
    await stop_worker()
    await engine.dispose()


app = FastAPI(title="AI 智能相册后端", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(BizException)
async def biz_exception_handler(_: Request, exc: BizException):
    """业务异常处理：转 JSONResponse。"""
    return JSONResponse(status_code=exc.code, content={"code": exc.code, "message": exc.message, "data": None})


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_: Request, exc: RequestValidationError):
    """请求参数校验失败处理：返回 400 与聚合错误消息。"""
    msg = "; ".join(
        ".".join(str(p) for p in e["loc"][1:]) + ":" + e["msg"]
        for e in exc.errors()
    ) or "参数错误"
    return JSONResponse(status_code=400, content={"code": 400, "message": msg, "data": None})


@app.exception_handler(Exception)
async def fallback_handler(_: Request, exc: Exception):
    """兜底异常处理：记录日志并返回 500。"""
    log.exception("未捕获异常: %s", exc)
    return JSONResponse(status_code=500, content={"code": 500, "message": "服务器错误", "data": None})


Path(settings.DATA_DIR).mkdir(parents=True, exist_ok=True)
app.mount(settings.STATIC_URL_PREFIX, StaticFiles(directory=settings.DATA_DIR), name="static")

for r in (auth.router, users.router, photos.router, categories.router, ai.router, admin_categories.router):
    app.include_router(r)


@app.get("/health")
async def health():
    """健康检查端点。"""
    return {"status": "ok"}
