# 项目四：AI 随身图文笔记助手 — 模型模块实施方案

> 文档版本：v1.0  |  更新日期：2026-08-08
> 目标读者：AI 模型方向开发者
> 适用范围：`AI-Mobile-Notes-App/model/` 目录

---

## 一、设计目标与原则

| 目标 | 原则 |
|------|------|
| 多模型可插拔 | 抽象 `BaseModel` 接口，支持 OpenAI / 豆包 / 千问 / 文心无缝切换 |
| 多模态统一 | 图片+文本输入统一封装，屏蔽各厂商 base64/URL 差异 |
| Prompt 可维护 | 模板集中在 YAML 文件，迭代不改代码 |
| 输出可控 | JSON 模式 + Schema 校验 + 长度兜底 |
| 成本可观测 | 每次调用记录 token、费用、耗时 |
| 容错可降级 | 主模型失败自动切换备选模型 |

---

## 二、目录结构

```
AI-Mobile-Notes-App/model/
├── README.md
├── requirements.txt
├── .env.example                    # 环境变量样例
├── config.py                       # 配置加载（pydantic-settings）
│
├── core/                           # 核心抽象
│   ├── __init__.py
│   ├── base_model.py               # BaseModel 抽象基类
│   ├── message.py                  # Message / Image / ContentPart 数据类
│   ├── response.py                 # ModelResponse / Usage 统一返回
│   └── exceptions.py               # 自定义异常（RateLimit / Auth / Timeout …）
│
├── adapters/                       # 各厂商实现
│   ├── __init__.py
│   ├── openai_adapter.py           # GPT-4o / GPT-4V
│   ├── doubao_adapter.py           # 豆包多模态（火山引擎）
│   ├── qwen_adapter.py             # 通义千问 VL
│   ├── wenxin_adapter.py           # 文心一言
│   └── registry.py                 # MODEL_REGISTRY 工厂
│
├── prompts/                        # Prompt 模板（YAML）
│   ├── ocr.yaml
│   ├── knowledge.yaml
│   ├── summary.yaml
│   ├── questions.yaml
│   ├── polish.yaml
│   ├── translate.yaml
│   └── mindmap.yaml
│
├── services/                       # 业务编排
│   ├── __init__.py
│   ├── ocr_service.py              # 图片识别
│   ├── note_ai_service.py          # 知识点 / 摘要 / 题库
│   ├── text_ai_service.py          # 润色 / 翻译 / 导图
│   └── search_service.py           # 语义检索（embedding）
│
├── utils/                          # 工具
│   ├── __init__.py
│   ├── image_encoder.py            # 图片压缩/base64
│   ├── token_counter.py            # tiktoken 估算
│   ├── logger.py                   # loguru 封装
│   └── retry.py                    # 指数退避装饰器
│
├── schemas/                        # Pydantic 输出模型
│   ├── __init__.py
│   ├── knowledge.py                # KnowledgePoints
│   ├── questions.py                # Question
│   └── mindmap.py                  # MindMapNode
│
├── cache/                          # 缓存
│   ├── __init__.py
│   └── prompt_cache.py             # 基于内容哈希的内存缓存
│
└── tests/                          # 单元测试
    ├── test_adapters.py
    ├── test_services.py
    └── test_prompts.py
```

---

## 三、核心抽象设计

### 3.1 `core/message.py` — 统一消息结构

```python
from dataclasses import dataclass
from typing import List, Literal, Union

@dataclass
class ImagePart:
    """图片内容块，支持 URL 或 base64"""
    data: str                       # URL / base64 字符串
    type: Literal["url", "base64"] = "url"
    mime_type: str = "image/jpeg"
    detail: Literal["low", "high", "auto"] = "auto"

@dataclass
class TextPart:
    text: str

ContentPart = Union[TextPart, ImagePart]

@dataclass
class Message:
    role: Literal["system", "user", "assistant"]
    content: List[ContentPart]

    @classmethod
    def user_text(cls, text: str) -> "Message":
        return cls(role="user", content=[TextPart(text=text)])

    @classmethod
    def user_multimodal(cls, text: str, images: List[ImagePart]) -> "Message":
        return cls(role="user", content=[TextPart(text=text), *images])
```

### 3.2 `core/response.py` — 统一返回结构

```python
from dataclasses import dataclass
from typing import Optional, Any

@dataclass
class Usage:
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    estimated_cost_usd: float = 0.0

@dataclass
class ModelResponse:
    content: str                                  # 文本输出
    parsed: Optional[Any] = None                  # JSON 模式解析后的对象
    usage: Optional[Usage] = None
    model: str = ""
    elapsed_ms: int = 0
    raw: Optional[dict] = None                    # 原始响应，便于排查
```

### 3.3 `core/base_model.py` — 抽象基类

```python
from abc import ABC, abstractmethod
from typing import List, AsyncIterator
from .message import Message
from .response import ModelResponse

class BaseModel(ABC):
    name: str = "base"

    def __init__(self, api_key: str, model_name: str, **kwargs):
        self.api_key = api_key
        self.model_name = model_name
        self.kwargs = kwargs

    @abstractmethod
    async def chat(
        self,
        messages: List[Message],
        *,
        temperature: float = 0.7,
        max_tokens: int = 2048,
        response_format: Optional[dict] = None,  # {"type": "json_object"}
        **extra
    ) -> ModelResponse:
        """非流式调用"""
        ...

    @abstractmethod
    async def stream_chat(
        self,
        messages: List[Message],
        *,
        temperature: float = 0.7,
        max_tokens: int = 2048,
        **extra
    ) -> AsyncIterator[str]:
        """流式调用，逐块返回文本"""
        ...

    async def close(self):
        """关闭 HTTP 连接，子类按需重写"""
        pass
```

### 3.4 `core/exceptions.py`

```python
class ModelError(Exception): pass
class AuthError(ModelError): pass
class RateLimitError(ModelError): pass
class TimeoutError(ModelError): pass
class OutputParseError(ModelError): pass
class QuotaExceededError(ModelError): pass
```

---

## 四、Adapter 实现要点

### 4.1 `adapters/registry.py` — 模型工厂

```python
from typing import Dict, Type
from .base_model import BaseModel
from .openai_adapter import OpenAIAdapter
from .doubao_adapter import DoubaoAdapter
from .qwen_adapter import QwenAdapter

MODEL_REGISTRY: Dict[str, Type[BaseModel]] = {
    "openai":   OpenAIAdapter,
    "doubao":   DoubaoAdapter,
    "qwen":     QwenAdapter,
}

def get_model(provider: str, **kwargs) -> BaseModel:
    if provider not in MODEL_REGISTRY:
        raise ValueError(f"Unknown provider: {provider}")
    return MODEL_REGISTRY[provider](**kwargs)
```

### 4.2 `adapters/openai_adapter.py` — 关键实现

```python
import httpx, time, base64
from typing import List, AsyncIterator
from ..core.base_model import BaseModel
from ..core.message import Message, ImagePart, TextPart
from ..core.response import ModelResponse, Usage
from ..core.exceptions import AuthError, RateLimitError, TimeoutError

class OpenAIAdapter(BaseModel):
    name = "openai"
    BASE_URL = "https://api.openai.com/v1"

    def __init__(self, api_key: str, model_name: str = "gpt-4o-mini", **kw):
        super().__init__(api_key, model_name, **kw)
        self.client = httpx.AsyncClient(
            base_url=self.BASE_URL,
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=httpx.Timeout(60.0, connect=10.0),
        )

    def _convert_messages(self, messages: List[Message]) -> List[dict]:
        """统一消息 -> OpenAI Chat Completions 格式"""
        out = []
        for m in messages:
            if all(isinstance(p, TextPart) for p in m.content):
                out.append({"role": m.role, "content": "".join(p.text for p in m.content)})
            else:
                content = []
                for p in m.content:
                    if isinstance(p, TextPart):
                        content.append({"type": "text", "text": p.text})
                    elif isinstance(p, ImagePart):
                        if p.type == "url":
                            content.append({"type": "image_url", "image_url": {"url": p.data, "detail": p.detail}})
                        else:
                            url = f"data:{p.mime_type};base64,{p.data}"
                            content.append({"type": "image_url", "image_url": {"url": url, "detail": p.detail}})
                out.append({"role": m.role, "content": content})
        return out

    async def chat(self, messages, *, temperature=0.7, max_tokens=2048, response_format=None, **extra):
        t0 = time.time()
        payload = {
            "model": self.model_name,
            "messages": self._convert_messages(messages),
            "temperature": temperature,
            "max_tokens": max_tokens,
            **({"response_format": response_format} if response_format else {}),
            **extra,
        }
        try:
            r = await self.client.post("/chat/completions", json=payload)
        except httpx.TimeoutException as e:
            raise TimeoutError(str(e)) from e

        if r.status_code == 401: raise AuthError(r.text)
        if r.status_code == 429: raise RateLimitError(r.text)
        r.raise_for_status()
        data = r.json()

        choice = data["choices"][0]
        usage = Usage(
            prompt_tokens=data["usage"]["prompt_tokens"],
            completion_tokens=data["usage"]["completion_tokens"],
            total_tokens=data["usage"]["total_tokens"],
        )
        return ModelResponse(
            content=choice["message"]["content"],
            usage=usage,
            model=data["model"],
            elapsed_ms=int((time.time() - t0) * 1000),
            raw=data,
        )

    async def stream_chat(self, messages, *, temperature=0.7, max_tokens=2048, **extra):
        payload = {
            "model": self.model_name,
            "messages": self._convert_messages(messages),
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": True,
            **extra,
        }
        async with self.client.stream("POST", "/chat/completions", json=payload) as r:
            r.raise_for_status()
            async for line in r.aiter_lines():
                if line.startswith("data: "):
                    chunk = line[6:]
                    if chunk == "[DONE]": break
                    import json
                    delta = json.loads(chunk)["choices"][0]["delta"].get("content", "")
                    if delta: yield delta

    async def close(self):
        await self.client.aclose()
```

### 4.3 豆包 / 千问适配差异点

| 厂商 | 关键差异 | 处理方式 |
|------|----------|----------|
| **豆包（火山引擎）** | 鉴权用 Bearer；图片字段 `image_url` 而非 `image`；模型名 `ep-xxx` | 单独 endpoint 模板，复用 OpenAI 思路 |
| **千问（DashScope）** | OpenAI 兼容模式；图像走 `image` 字段而非 `image_url` | `_convert_messages` 分支处理 |
| **文心一言** | 独立 REST 接口，access_token 需先获取；图片用 base64 | 完全独立的 client，封装 `get_access_token()` |

**实施建议**：先打通 OpenAI → 豆包 → 千问（OpenAI 兼容协议），文心放最后阶段。

---

## 五、Prompt 模板管理

### 5.1 文件格式：`prompts/knowledge.yaml`

```yaml
name: knowledge_extraction
version: "1.2"
description: 从文本中提炼知识点
system: |
  你是一位资深学习助手，擅长从课堂笔记、教材或试卷中提炼核心知识点。
  输出的每个知识点应包含「标题」与「简要说明」。
  严格遵守 JSON 格式输出，不要包含任何额外文字。
user: |
  请从以下内容中提炼 3~5 个最重要的知识点：

  {{ text }}

  要求：
  1. 数量控制在 3~5 个；
  2. 每个知识点用一句话说明；
  3. 输出 JSON 数组，结构为 [{"title": "...", "content": "..."}]；
  4. 严禁输出 JSON 之外的任何字符。

json_schema:
  type: array
  items:
    type: object
    properties:
      title: {type: string, maxLength: 30}
      content: {type: string, maxLength: 200}
    required: [title, content]
temperature: 0.3
max_tokens: 800
```

### 5.2 `core/prompt_loader.py`

```python
import yaml, jinja2
from pathlib import Path
from typing import Dict

class PromptLoader:
    def __init__(self, prompts_dir: str = "prompts"):
        self.env = jinja2.Environment(trim_blocks=True, lstrip_blocks=True)
        self.prompts: Dict[str, dict] = {}
        for f in Path(prompts_dir).glob("*.yaml"):
            data = yaml.safe_load(f.read_text(encoding="utf-8"))
            self.prompts[data["name"]] = data

    def render(self, name: str, variables: dict) -> tuple[str, str, dict]:
        """返回 (system, user, meta)"""
        p = self.prompts[name]
        sys_tmpl = self.env.from_string(p["system"])
        user_tmpl = self.env.from_string(p["user"])
        return (
            sys_tmpl.render(**variables),
            user_tmpl.render(**variables),
            {k: v for k, v in p.items() if k not in ("system", "user")}
        )
```

### 5.3 全部 Prompt 清单

| 文件 | 用途 | 关键参数 |
|------|------|----------|
| `ocr.yaml` | 图文识别 | 温度 0.1，JSON 否 |
| `knowledge.yaml` | 知识点提炼 | 温度 0.3，JSON 是 |
| `summary.yaml` | 摘要生成 | 温度 0.5，max_tokens 500 |
| `questions.yaml` | 自动出题 | 温度 0.7，JSON 是 |
| `polish.yaml` | 文本润色 | 温度 0.6，多模式参数 |
| `translate.yaml` | 中英互译 | 温度 0.2，max_tokens 按需 |
| `mindmap.yaml` | 思维导图 | 温度 0.4，Markdown 树 |

---

## 六、业务 Service 层

### 6.1 `services/ocr_service.py`

```python
from ..core.base_model import BaseModel
from ..core.message import Message, ImagePart
from ..core.prompt_loader import PromptLoader

class OCRService:
    def __init__(self, model: BaseModel, prompts: PromptLoader):
        self.model = model
        self.prompts = prompts

    async def recognize(self, image_b64: str, mime: str = "image/jpeg") -> str:
        sys_msg, user_msg, meta = self.prompts.render("ocr", {})
        messages = [
            Message(role="system", content=[TextPart(sys_msg)]),
            Message(
                role="user",
                content=[
                    TextPart(user_msg),
                    ImagePart(data=image_b64, type="base64", mime_type=mime),
                ],
            ),
        ]
        resp = await self.model.chat(messages, temperature=meta["temperature"], max_tokens=meta["max_tokens"])
        return resp.content.strip()
```

### 6.2 `services/note_ai_service.py`

```python
import json, hashlib
from ..schemas.knowledge import KnowledgePoints
from ..schemas.questions import QuestionList
from ..core.exceptions import OutputParseError

class NoteAIService:
    def __init__(self, model, prompts, cache):
        self.model = model
        self.prompts = prompts
        self.cache = cache

    async def extract_knowledge(self, text: str) -> KnowledgePoints:
        key = "knowledge:" + hashlib.md5(text.encode()).hexdigest()
        if cached := await self.cache.get(key):
            return KnowledgePoints.parse_raw(cached)
        sys_msg, user_msg, meta = self.prompts.render("knowledge_extraction", {"text": text})
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta["temperature"],
            response_format={"type": "json_object"},
        )
        try:
            data = json.loads(resp.content)
            result = KnowledgePoints(items=data)
        except Exception as e:
            raise OutputParseError(f"knowledge parse failed: {e}")
        await self.cache.set(key, result.json(), ttl=3600)
        return result

    async def generate_questions(self, text: str, count: int = 5) -> QuestionList:
        sys_msg, user_msg, meta = self.prompts.render(
            "question_generation", {"text": text, "count": count}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta["temperature"],
            response_format={"type": "json_object"},
            max_tokens=meta["max_tokens"],
        )
        return QuestionList.parse_raw(resp.content)

    async def summarize(self, text: str, max_chars: int = 200) -> str:
        sys_msg, user_msg, meta = self.prompts.render(
            "summarization", {"text": text, "max_chars": max_chars}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta["temperature"],
            max_tokens=meta["max_tokens"],
        )
        return resp.content.strip()
```

### 6.3 `services/text_ai_service.py`

```python
class TextAIService:
    def __init__(self, model, prompts):
        self.model = model
        self.prompts = prompts

    async def polish(self, text: str, mode: str = "polish") -> str:
        """mode: polish / expand / shorten / formal"""
        sys_msg, user_msg, meta = self.prompts.render(
            "text_polish", {"text": text, "mode": mode}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta["temperature"],
            max_tokens=meta["max_tokens"],
        )
        return resp.content.strip()

    async def translate(self, text: str, src: str = "zh", tgt: str = "en") -> str:
        sys_msg, user_msg, meta = self.prompts.render(
            "translation", {"text": text, "src_lang": src, "tgt_lang": tgt}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta["temperature"],
            max_tokens=meta["max_tokens"],
        )
        return resp.content.strip()

    async def mindmap(self, text: str, fmt: str = "markdown") -> str:
        sys_msg, user_msg, meta = self.prompts.render(
            "mindmap_generation", {"text": text, "format": fmt}
        )
        resp = await self.model.chat(
            [Message.user_text(sys_msg + "\n" + user_msg)],
            temperature=meta["temperature"],
            max_tokens=meta["max_tokens"],
        )
        return resp.content.strip()
```

### 6.4 `services/search_service.py` — 语义检索

```python
import numpy as np
from typing import List

class SearchService:
    """向量检索：调用 Embedding API + 余弦相似度"""

    def __init__(self, embed_model: BaseModel):
        self.embed_model = embed_model

    async def embed(self, text: str) -> List[float]:
        resp = await self.embed_model.chat(
            [Message.user_text(text)], max_tokens=1
        )  # 部分模型有独立 embed 接口，按需替换
        # 实际应由独立 embedding 模型返回向量，这里仅示意
        return resp.parsed["embedding"] if resp.parsed else []

    async def search(self, query: str, candidates: List[dict], top_k: int = 10) -> List[dict]:
        """
        candidates: [{"id": ..., "text": ..., "vector": [...]}, ...]
        """
        query_v = np.array(await self.embed(query))
        scored = []
        for c in candidates:
            v = np.array(c["vector"])
            score = float(np.dot(query_v, v) / (np.linalg.norm(query_v) * np.linalg.norm(v)))
            scored.append({**c, "score": score})
        scored.sort(key=lambda x: x["score"], reverse=True)
        return scored[:top_k]
```

> ⚠️ 注：OpenAI 有独立 `/v1/embeddings` 接口，建议专门封装 `EmbeddingAdapter`，不要复用 Chat 接口。

---

## 七、缓存策略

### 7.1 `cache/prompt_cache.py`

```python
import asyncio, hashlib, json
from typing import Optional

class PromptCache:
    """基于内容哈希的内存 LRU 缓存，可替换为 Redis 实现"""

    def __init__(self, max_size: int = 500, default_ttl: int = 3600):
        self.store = {}                  # key -> (value, expire_at)
        self.max_size = max_size
        self.default_ttl = default_ttl
        self.lock = asyncio.Lock()

    def _key(self, prefix: str, content: str) -> str:
        return f"{prefix}:{hashlib.sha256(content.encode()).hexdigest()}"

    async def get(self, key: str) -> Optional[str]:
        async with self.lock:
            item = self.store.get(key)
            if not item: return None
            value, expire_at = item
            if expire_at < asyncio.get_event_loop().time():
                self.store.pop(key, None)
                return None
            return value

    async def set(self, key: str, value: str, ttl: Optional[int] = None):
        async with self.lock:
            if len(self.store) >= self.max_size:
                # LRU：删除最早过期的 10%
                items = sorted(self.store.items(), key=lambda x: x[1][1])[:max(1, self.max_size // 10)]
                for k, _ in items: self.store.pop(k, None)
            ttl = ttl or self.default_ttl
            self.store[key] = (value, asyncio.get_event_loop().time() + ttl)
```

### 7.2 缓存键设计

| 业务 | 缓存键 | TTL |
|------|--------|-----|
| OCR 结果 | `ocr:{sha256(image)}` | 24h |
| 知识点 | `knowledge:{sha256(text)}` | 1h |
| 摘要 | `summary:{sha256(text)}:{max_chars}` | 1h |
| 题库 | `questions:{sha256(text)}:{count}` | 1h |
| 翻译 | `translate:{sha256(text)}:{src}:{tgt}` | 24h |
| 润色 | `polish:{sha256(text)}:{mode}` | 30min |

---

## 八、配置与日志

### 8.1 `.env.example`

```env
# ====== 默认模型 ======
MODEL_PROVIDER=openai
OPENAI_API_KEY=sk-xxx
OPENAI_MODEL=gpt-4o-mini

# ====== 备用模型（自动降级） ======
FALLBACK_PROVIDER=doubao
DOUBAO_API_KEY=xxx
DOUBAO_MODEL=ep-xxx
DOUBAO_ENDPOINT=https://ark.cn-beijing.volces.com/api/v3

# ====== Embedding ======
EMBED_PROVIDER=openai
EMBED_MODEL=text-embedding-3-small

# ====== 通用 ======
REQUEST_TIMEOUT=60
MAX_RETRIES=3
CACHE_SIZE=500
LOG_LEVEL=INFO
LOG_DIR=./logs
```

### 8.2 `config.py`

```python
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    model_provider: str = "openai"
    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"

    fallback_provider: str = "doubao"
    doubao_api_key: str = ""

    request_timeout: int = 60
    max_retries: int = 3
    cache_size: int = 500
    log_level: str = "INFO"

    class Config:
        env_file = ".env"

settings = Settings()
```

### 8.3 `utils/logger.py`

```python
from loguru import logger
import sys
from pathlib import Path

def setup_logger(log_dir: str = "./logs", level: str = "INFO"):
    Path(log_dir).mkdir(parents=True, exist_ok=True)
    logger.remove()
    logger.add(sys.stderr, level=level,
               format="<green>{time:HH:mm:ss}</green> | <level>{level: <8}</level> | <cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - <level>{message}</level>")
    logger.add(f"{log_dir}/{{time:YYYY-MM-DD}}.log",
               rotation="100 MB", retention="30 days",
               encoding="utf-8", enqueue=True)

# 自动记录每次调用
def log_call(provider: str, model: str, tokens: int, cost: float, elapsed_ms: int, status: str):
    logger.info(f"[{provider}/{model}] tokens={tokens} cost=${cost:.4f} elapsed={elapsed_ms}ms status={status}")
```

---

## 九、容错与降级

### 9.1 `utils/retry.py`

```python
import asyncio, random
from functools import wraps
from ..core.exceptions import RateLimitError, TimeoutError

def retry_with_backoff(max_retries: int = 3, base_delay: float = 1.0):
    def decorator(func):
        @wraps(func)
        async def wrapper(*args, **kwargs):
            for attempt in range(max_retries):
                try:
                    return await func(*args, **kwargs)
                except (RateLimitError, TimeoutError) as e:
                    if attempt == max_retries - 1: raise
                    delay = base_delay * (2 ** attempt) + random.uniform(0, 0.5)
                    await asyncio.sleep(delay)
            return None
        return wrapper
    return decorator
```

### 9.2 自动降级

```python
class FallbackModel:
    """主模型 + 备用模型包装器"""
    def __init__(self, primary: BaseModel, fallback: BaseModel):
        self.primary = primary
        self.fallback = fallback

    async def chat(self, messages, **kw):
        try:
            return await self.primary.chat(messages, **kw)
        except (AuthError, RateLimitError, TimeoutError) as e:
            logger.warning(f"primary model failed: {e}, falling back to {self.fallback.name}")
            return await self.fallback.chat(messages, **kw)

    async def stream_chat(self, messages, **kw):
        try:
            async for chunk in self.primary.stream_chat(messages, **kw):
                yield chunk
        except (AuthError, RateLimitError, TimeoutError):
            logger.warning(f"primary stream failed, falling back")
            async for chunk in self.fallback.stream_chat(messages, **kw):
                yield chunk
```

---

## 十、测试方案

### 10.1 单元测试要点

| 测试文件 | 覆盖点 |
|----------|--------|
| `test_adapters.py` | OpenAI / 豆包 / 千问的 chat、stream、错误处理 |
| `test_prompts.py` | Prompt 模板渲染、变量替换、长度控制 |
| `test_services.py` | 各业务服务的输入输出、JSON 解析 |
| `test_cache.py` | 缓存命中率、过期清理、LRU 淘汰 |
| `test_retry.py` | 重试次数、指数退避、降级路径 |

### 10.2 评估脚本

```python
# tests/eval_quality.py
"""批量评估 OCR / 摘要 / 题库质量"""
import json, asyncio
from pathlib import Path

CASES = json.loads(Path("tests/fixtures/eval_set.json").read_text(encoding="utf-8"))

async def run_eval():
    ocr = OCRService(get_model("openai"), PromptLoader())
    results = []
    for case in CASES:
        text = await ocr.recognize(case["image_b64"])
        score = compute_text_similarity(text, case["ground_truth"])
        results.append({"id": case["id"], "score": score})
    avg = sum(r["score"] for r in results) / len(results)
    print(f"OCR Avg Score: {avg:.3f}")
```

### 10.3 指标看板

| 指标 | 采集方式 |
|------|----------|
| 调用成功率 | logger 日志聚合 |
| 平均响应时间 | elapsed_ms 字段 |
| Token 消耗 | Usage 字段 |
| 费用估算 | token × 单价 |
| 缓存命中率 | cache hit/miss 计数 |

---

## 十一、最小可运行示例

### 11.1 `main.py`（demo）

```python
import asyncio, base64
from pathlib import Path
from model.adapters.registry import get_model
from model.core.prompt_loader import PromptLoader
from model.services.ocr_service import OCRService
from model.services.note_ai_service import NoteAIService
from model.cache.prompt_cache import PromptCache

async def main():
    model = get_model("openai", api_key="sk-xxx", model_name="gpt-4o-mini")
    prompts = PromptLoader("model/prompts")
    cache = PromptCache()

    ocr = OCRService(model, prompts)
    note_ai = NoteAIService(model, prompts, cache)

    # 测试 OCR
    img_bytes = Path("tests/fixtures/sample.jpg").read_bytes()
    img_b64 = base64.b64encode(img_bytes).decode()
    text = await ocr.recognize(img_b64)
    print(f"OCR: {text[:100]}...")

    # 测试知识点提炼
    knowledge = await note_ai.extract_knowledge(text)
    print(f"Knowledge: {knowledge}")

if __name__ == "__main__":
    asyncio.run(main())
```

---

## 十二、上线 Checklist

- [ ] 至少打通 2 个模型厂商（OpenAI + 豆包/千问）
- [ ] 所有 Prompt 模板入库 + 版本号管理
- [ ] 输出 JSON 全部用 Pydantic 校验
- [ ] 失败重试 + 主备降级全部跑通
- [ ] Token / 费用日志接入监控
- [ ] 缓存命中率 ≥ 15%
- [ ] 关键路径单测覆盖率 ≥ 70%
- [ ] 评估脚本可在 50 条样本上输出分数