"""FastAPI 依赖：JWT 解析 + 取当前用户。"""
from typing import Annotated

from fastapi import Depends, Header
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.exceptions import BizException
from app.models import User
from app.security import decode_token


async def get_current_user(
    authorization: Annotated[str | None, Header()] = None,
    db: AsyncSession = Depends(get_db),
) -> User:
    """从 Bearer Token 解析并返回当前登录用户。"""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise BizException(401, "未登录")
    token = authorization.split(" ", 1)[1].strip()
    user = await db.get(User, decode_token(token))
    if not user or user.status != 1:
        raise BizException(401, "用户不存在或已禁用")
    return user
