"""认证接口入参/出参。"""
from pydantic import BaseModel, EmailStr, Field


class RegisterRequest(BaseModel):
    username: str = Field(min_length=3, max_length=50)
    password: str = Field(min_length=6, max_length=128)
    email: EmailStr | None = None


class RegisterResponse(BaseModel):
    userId: int
    username: str


class LoginRequest(BaseModel):
    username: str
    password: str


class LoginResponse(BaseModel):
    userId: int
    username: str
    token: str
    expiresIn: int