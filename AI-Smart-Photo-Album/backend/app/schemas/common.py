"""通用分页响应包装。"""
from typing import Generic, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class Pagination(BaseModel, Generic[T]):
    list: list[T]
    total: int
    page: int
    pageSize: int