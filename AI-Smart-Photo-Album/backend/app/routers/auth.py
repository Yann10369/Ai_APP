"""认证路由：注册 / 登录 / 登出。"""
from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.deps import get_current_user
from app.exceptions import BizException
from app.models import User
from app.response import ok
from app.schemas.auth import LoginRequest, LoginResponse, RegisterRequest, RegisterResponse
from app.security import create_access_token, hash_password, verify_password
from app.utils.time import utcnow_naive

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


@router.post("/register")
async def register(body: RegisterRequest, db: AsyncSession = Depends(get_db)):
    """注册新用户并返回用户 id。"""
    if await db.scalar(select(User).where(User.username == body.username)):
        raise BizException(400, "用户名已被占用")
    if body.email and await db.scalar(select(User).where(User.email == body.email)):
        raise BizException(400, "邮箱已被占用")
    user = User(
        username=body.username,
        password_hash=hash_password(body.password),
        email=body.email,
    )
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return ok(
        data=RegisterResponse(userId=user.user_id, username=user.username).model_dump(),
        message="注册成功",
    )


@router.post("/login")
async def login(body: LoginRequest, db: AsyncSession = Depends(get_db)):
    """校验用户名密码并返回 JWT。"""
    user = await db.scalar(select(User).where(User.username == body.username))
    if not user or not verify_password(body.password, user.password_hash):
        raise BizException(400, "用户名或密码错误")
    if user.status != 1:
        raise BizException(403, "用户已被禁用")
    user.last_login_at = utcnow_naive()
    await db.commit()
    return ok(
        data=LoginResponse(
            userId=user.user_id,
            username=user.username,
            token=create_access_token(user.user_id),
            expiresIn=settings.JWT_EXPIRE_SECONDS,
        ).model_dump(),
        message="登录成功",
    )


@router.post("/logout")
async def logout(_user: User = Depends(get_current_user)):
    """登出接口（JWT 由客户端丢弃，服务端无状态）。"""
    return ok(message="登出成功")
