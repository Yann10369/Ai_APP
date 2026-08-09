"""密码哈希 + JWT 编解码。"""
from datetime import datetime, timedelta, timezone

from jose import JWTError, jwt
from passlib.context import CryptContext

from app.config import settings
from app.exceptions import BizException

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def hash_password(password: str) -> str:
    """对明文密码做 bcrypt 哈希。"""
    return pwd_context.hash(password)


def verify_password(plain: str, hashed: str) -> bool:
    """校验明文密码与哈希是否匹配。"""
    return pwd_context.verify(plain, hashed)


def create_access_token(user_id: int) -> str:
    """为用户签发 JWT access token。"""
    now = datetime.now(timezone.utc)
    expire = now + timedelta(seconds=settings.JWT_EXPIRE_SECONDS)
    payload = {"sub": str(user_id), "iat": int(now.timestamp()), "exp": int(expire.timestamp())}
    return jwt.encode(payload, settings.JWT_SECRET, algorithm="HS256")


def decode_token(token: str) -> int:
    """解析 JWT 并返回 user_id。"""
    try:
        payload = jwt.decode(token, settings.JWT_SECRET, algorithms=["HS256"])
        user_id_str = payload.get("sub")
        if not user_id_str:
            raise BizException(401, "Token 无效")
        return int(user_id_str)
    except JWTError:
        raise BizException(401, "Token 无效或已过期")
