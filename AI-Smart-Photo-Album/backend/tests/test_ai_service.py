"""AI 服务（analyze_photo / extract_query_tags）相关测试。"""
import pytest


async def _seed_categories(db):
    """幂等种分类（测试用的固定 scene/emotion/tag 集合）。"""
    from sqlalchemy import select, func
    from app.models import Category, CategoryType
    existing = (await db.execute(select(func.count(Category.category_id)))).scalar_one()
    if existing:
        return
    scenes = ["🏖️ 海滩", "🏙️ 城市", "🏠 室内"]
    emotions = ["😄 快乐", "😌 平静", "🤩 兴奋"]
    tags = ["👤 人物", "👫 朋友", "🍜 美食"]
    objs = []
    max_id = existing or 0
    for i, n in enumerate(scenes):
        objs.append(Category(category_id=max_id+1+i, type=CategoryType.scene, name=n, is_enabled=1, sort_order=i+1))
    max_id += len(scenes)
    for i, n in enumerate(emotions):
        objs.append(Category(category_id=max_id+1+i, type=CategoryType.emotion, name=n, is_enabled=1, sort_order=i+1))
    max_id += len(emotions)
    for i, n in enumerate(tags):
        objs.append(Category(category_id=max_id+1+i, type=CategoryType.tag, name=n, is_enabled=1, sort_order=i+1))
    db.add_all(objs)
    await db.commit()


class _FakeAnalysisResult:
    """模拟 model.inference.schema.AIAnalysisResult。"""
    def __init__(self, description, scene, scene_conf, emotion, emotion_conf, tags):
        self.description = description
        self.scene_category_name = scene
        self.scene_confidence = scene_conf
        self.emotion_category_name = emotion
        self.emotion_confidence = emotion_conf
        self.tag_category_names = tags


class _FakeClassifier:
    """测试用假分类器（analyze + extract_query_tags）。"""
    def __init__(self, analyze_result=None, query_tags=None):
        self._analyze_result = analyze_result
        self._query_tags = query_tags or []

    async def analyze(self, photo_path, photo_id=None):
        return self._analyze_result

    async def extract_query_tags(self, query):
        return self._query_tags


@pytest.fixture
def fake_classifier(monkeypatch):
    """把 _get_classifier 替换为返回 _FakeClassifier 的函数。"""
    from app.services import ai as ai_mod

    clf = _FakeClassifier()
    monkeypatch.setattr(ai_mod, "_get_classifier", lambda: clf)
    ai_mod.reset_classifier()
    yield clf
    ai_mod.reset_classifier()


def _make_analyze_result():
    return _FakeAnalysisResult(
        description="海边朋友大笑",
        scene="🏖️ 海滩", scene_conf=0.92,
        emotion="😄 快乐", emotion_conf=0.85,
        tags=[("👫 朋友", 0.88), ("🍜 美食", 0.71)],
    )


@pytest.mark.asyncio
async def test_analyze_photo_returns_valid_categories(db, fake_classifier):
    from sqlalchemy import select
    from app.models import Category
    from app.services.ai import analyze_photo

    fake_classifier._analyze_result = _make_analyze_result()
    await _seed_categories(db)
    res = await analyze_photo("/tmp/nope.jpg", 1)

    assert res.description == "海边朋友大笑"
    assert res.scene_category_name == "🏖️ 海滩"
    assert res.scene_confidence == 0.92
    assert res.emotion_category_name == "😄 快乐"
    assert res.emotion_confidence == 0.85
    assert res.tag_category_names == [("👫 朋友", 0.88), ("🍜 美食", 0.71)]
    names = {res.scene_category_name, res.emotion_category_name,
             *[n for n, _ in res.tag_category_names]}
    rows = (await db.execute(select(Category.name).where(Category.name.in_(names)))).scalars().all()
    assert set(rows) == names


@pytest.mark.asyncio
async def test_extract_query_tags_returns_pairs(db, fake_classifier):
    from app.services.ai import extract_query_tags

    fake_classifier._query_tags = [
        ("🏖️ 海滩", 0.85),
        ("👫 朋友", 0.72),
    ]
    res = await extract_query_tags("海边玩耍")
    assert isinstance(res, list)
    assert len(res) == 2
    for item in res:
        assert isinstance(item, tuple) and len(item) == 2
        name, conf = item
        assert isinstance(name, str)
        assert 0.0 <= conf <= 1.0


@pytest.mark.asyncio
async def test_extract_query_tags_empty(fake_classifier):
    from app.services.ai import extract_query_tags

    assert await extract_query_tags("") == []
    assert await extract_query_tags("   ") == []  # strip 后空


@pytest.mark.asyncio
async def test_analyze_photo_propagates_error(db, fake_classifier):
    """真实模型失败时应该冒泡，由 worker 决定重试。"""
    from app.services.ai import analyze_photo

    async def boom(photo_path, photo_id=None):
        raise RuntimeError("API 限流")
    fake_classifier.analyze = boom

    with pytest.raises(RuntimeError, match="API 限流"):
        await analyze_photo("/tmp/x.jpg", 1)


@pytest.mark.asyncio
async def test_analyze_photo_raises_when_no_api_key(monkeypatch):
    """没配 DASHSCOPE_API_KEY 时首次调用就抛清晰错误，不静默走 mock。"""
    from app.services import ai as ai_mod
    monkeypatch.setattr(ai_mod, "_get_classifier", lambda: (_ for _ in ()).throw(
        RuntimeError("DASHSCOPE_API_KEY 未配置")
    ))
    ai_mod.reset_classifier()
    with pytest.raises(RuntimeError, match="DASHSCOPE_API_KEY"):
        await ai_mod.analyze_photo("/tmp/x.jpg", 1)
