"""统一响应包装：{code, message, data}。"""
from typing import Any

from fastapi.responses import JSONResponse


def ok(data: Any = None, message: str = "success", code: int = 200) -> JSONResponse:
    """构造成功响应。"""
    return JSONResponse(content={"code": code, "message": message, "data": data})


def fail(code: int, message: str, data: Any = None) -> JSONResponse:
    """构造失败响应。"""
    return JSONResponse(status_code=code, content={"code": code, "message": message, "data": data})
