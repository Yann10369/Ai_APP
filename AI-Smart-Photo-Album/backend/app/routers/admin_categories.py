"""Admin 分类管理：列表 / 创建 / 修改 / 删除 / 重置为默认 seed。"""
from fastapi import APIRouter, Depends
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db, run_sql_file
from app.deps import get_current_user
from app.exceptions import BizException
from app.models import Category, CategoryType, PhotoCategory, User
from app.response import ok
from app.utils.time import to_iso

router = APIRouter(prefix="/api/v1/admin/categories", tags=["admin-categories"])

MAX_DEFAULT_ID = 60


def _parse_type(raw: str | None) -> CategoryType | None:
    """将 scene/emotion/tag 字符串转 CategoryType enum；非法抛 400。"""
    if not raw:
        return None
    try:
        return CategoryType(raw)
    except ValueError:
        raise BizException(400, "type 必须是 scene/emotion/tag")


@router.get("")
async def list_admin(
    type: str | None = None,
    db: AsyncSession = Depends(get_db),
    _user: User = Depends(get_current_user),
):
    """后台列出分类及其关联照片数。"""
    stmt = select(Category).order_by(Category.type, Category.sort_order, Category.category_id)
    type_enum = _parse_type(type)
    if type_enum:
        stmt = stmt.where(Category.type == type_enum)
    cats = list((await db.execute(stmt)).scalars().all())

    if cats:
        rows = (await db.execute(
            select(PhotoCategory.category_id, func.count(PhotoCategory.photo_id))
            .where(PhotoCategory.category_id.in_([c.category_id for c in cats]))
            .group_by(PhotoCategory.category_id)
        )).all()
        count_map = dict(rows)
    else:
        count_map = {}

    items = [
        {
            "categoryId": c.category_id,
            "type": c.type.value,
            "name": c.name,
            "iconUrl": c.icon_url,
            "photoCount": count_map.get(c.category_id, 0),
            "createdAt": to_iso(c.created_at),
        } for c in cats
    ]
    return ok(data={"list": items, "total": len(items)})


@router.post("")
async def create(
    body: dict,
    db: AsyncSession = Depends(get_db),
    _user: User = Depends(get_current_user),
):
    """新建分类。"""
    name = (body.get("name") or "").strip()
    if not (1 <= len(name) <= 20):
        raise BizException(400, "name 长度 1~20")
    type_enum = _parse_type(body.get("type"))
    if not type_enum:
        raise BizException(400, "type 必须是 scene/emotion/tag")
    exists = await db.scalar(
        select(Category).where(Category.type == type_enum, Category.name == name)
    )
    if exists:
        raise BizException(400, "同类型下名称已存在")
    cat = Category(type=type_enum, name=name, icon_url=body.get("iconUrl"))
    db.add(cat)
    await db.commit()
    await db.refresh(cat)
    return ok(data={"categoryId": cat.category_id}, message="添加成功")


@router.patch("/{category_id}")
async def update(
    category_id: int,
    body: dict,
    db: AsyncSession = Depends(get_db),
    _user: User = Depends(get_current_user),
):
    """修改分类名称或图标。"""
    cat = await db.get(Category, category_id)
    if not cat:
        raise BizException(404, "分类不存在")
    if "name" in body and body["name"] is not None:
        new_name = (body["name"] or "").strip()
        if not (1 <= len(new_name) <= 20):
            raise BizException(400, "name 长度 1~20")
        dup = await db.scalar(
            select(Category).where(
                Category.type == cat.type,
                Category.name == new_name,
                Category.category_id != category_id,
            )
        )
        if dup:
            raise BizException(400, "同类型下名称已存在")
        cat.name = new_name
    if body.get("iconUrl") is not None:
        cat.icon_url = body["iconUrl"]
    await db.commit()
    return ok(message="修改成功")


@router.delete("/{category_id}")
async def delete(
    category_id: int,
    db: AsyncSession = Depends(get_db),
    _user: User = Depends(get_current_user),
):
    """删除分类（需先解除所有照片关联）。"""
    cat = await db.get(Category, category_id)
    if not cat:
        raise BizException(404, "分类不存在")
    cnt = (await db.execute(
        select(func.count(PhotoCategory.photo_id)).where(PhotoCategory.category_id == category_id)
    )).scalar_one()
    if cnt > 0:
        raise BizException(400, f"该分类下存在 {cnt} 张照片，请先移除关联")
    await db.delete(cat)
    await db.commit()
    return ok(message="删除成功")


@router.post("/reset")
async def reset(
    body: dict,
    db: AsyncSession = Depends(get_db),
    _user: User = Depends(get_current_user),
):
    """删除自定义分类并重新执行默认 seed。"""
    if not body.get("confirm"):
        raise BizException(400, "需要 confirm=true")

    custom_count = (await db.execute(
        select(func.count(Category.category_id)).where(Category.category_id > MAX_DEFAULT_ID)
    )).scalar_one()

    await db.execute(
        PhotoCategory.__table__.delete().where(
            PhotoCategory.category_id.in_(
                select(Category.category_id).where(Category.category_id > MAX_DEFAULT_ID)
            )
        )
    )
    await db.execute(
        Category.__table__.delete().where(Category.category_id > MAX_DEFAULT_ID)
    )
    await db.commit()

    await run_sql_file("migrations/002_seed_categories.sql")

    return ok(
        data={"resetCount": MAX_DEFAULT_ID, "removedCount": custom_count},
        message="已重置为初始分类集合",
    )