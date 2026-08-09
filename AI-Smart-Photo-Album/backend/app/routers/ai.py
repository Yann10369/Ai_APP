"""AI 路由：分析进度查询 + 手动重新分析入队。"""
from fastapi import APIRouter, Depends
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.deps import get_current_user
from app.exceptions import BizException
from app.models import (
    AnalysisStatus, AITask, AITaskStatus, Photo, User,
)
from app.response import ok
from app.schemas.ai import AIReanalyzeRequest, AIStatusResponse
from app.models.ai_task import notify_new_task

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])


@router.get("/status")
async def status(
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """返回当前用户的 AI 分析进度（total / done / pending / progress）。"""
    base = (Photo.user_id == user.user_id, Photo.deleted_at.is_(None))
    total = (await db.execute(
        select(func.count(Photo.photo_id)).where(*base)
    )).scalar_one()
    done = (await db.execute(
        select(func.count(Photo.photo_id)).where(
            *base, Photo.analysis_status == AnalysisStatus.done,
        )
    )).scalar_one()
    pending = (await db.execute(
        select(func.count(Photo.photo_id)).where(
            *base,
            Photo.analysis_status.in_([AnalysisStatus.pending, AnalysisStatus.processing]),
        )
    )).scalar_one()
    return ok(data=AIStatusResponse(
        total=total,
        done=done,
        pending=pending,
        progress=(done / total) if total else 0.0,
    ).model_dump())


@router.post("/reanalyze")
async def reanalyze(
    body: AIReanalyzeRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """将指定照片重新加入 AI 分析队列并唤醒 worker。"""
    if not body.photoIds:
        raise BizException(400, "photoIds 必须为非空数组")
    owned = (await db.execute(
        select(Photo.photo_id).where(
            Photo.photo_id.in_(body.photoIds),
            Photo.user_id == user.user_id,
            Photo.deleted_at.is_(None),
        )
    )).scalars().all()
    if not owned:
        raise BizException(404, "未找到有效照片")

    for pid in owned:
        p = await db.get(Photo, pid)
        p.analysis_status = AnalysisStatus.pending
        db.add(AITask(photo_id=pid, status=AITaskStatus.queued))
    await db.commit()
    # 一次唤醒 worker，worker 会去抢所有 N 个
    notify_new_task()
    return ok(data={"queuedCount": len(owned), "message": "已加入分析队列"})