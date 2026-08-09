"""SQLAlchemy async engine、Session 工厂、SQL 文件执行器。"""
import re
from pathlib import Path

from sqlalchemy import event, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.config import settings


class Base(DeclarativeBase):
    """ORM 模型基类。"""
    pass


_is_sqlite = settings.DATABASE_URL.startswith("sqlite")

engine = create_async_engine(
    settings.DATABASE_URL,
    echo=False,
    pool_pre_ping=not _is_sqlite,
    pool_recycle=3600,
)


# SQLite 默认外键 OFF；多 writer 需要 WAL + busy_timeout
if _is_sqlite:
    @event.listens_for(engine.sync_engine, "connect")
    def _enable_sqlite_fk(dbapi_conn, _):
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA foreign_keys = ON")
        cur.execute("PRAGMA journal_mode = WAL")
        cur.execute("PRAGMA synchronous = NORMAL")
        cur.execute("PRAGMA busy_timeout = 5000")
        cur.close()


AsyncSessionLocal = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_db():
    """FastAPI 依赖：yield 一个 AsyncSession。"""
    async with AsyncSessionLocal() as session:
        yield session


_SQL_COMMENT_LINE = re.compile(r"--.*$", re.MULTILINE)
_IDEMPOTENT_HINTS = ("duplicate column", "already exists")


async def run_sql_file(path: str | Path) -> None:
    """按 ; 分隔执行 SQL，跳过空行与行注释，吞掉幂等错误。"""
    cleaned = _SQL_COMMENT_LINE.sub("", Path(path).read_text(encoding="utf-8"))
    statements = [s.strip() for s in cleaned.split(";") if s.strip()]

    async with engine.begin() as conn:
        for stmt in statements:
            try:
                await conn.execute(text(stmt))
            except Exception as e:
                msg = str(e).lower()
                if any(h in msg for h in _IDEMPOTENT_HINTS):
                    continue
                raise
