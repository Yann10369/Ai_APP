# Model —— 智能相册模型端

> 调用 **阿里云百炼（DashScope）多模态视觉模型 `qwen3-vl-plus`**，
> 对照片做 **场景 / 情绪 / 标签 / 描述** 智能分类，并对 **自然语言搜索 query** 做关键词解析。

模型端运行在 `backend/.venv` 中（与后端共享依赖），
通过 `backend/app/services/ai/real_service.py` 接入后端 `AIService` 协议，
调用方零侵入。

---

## 1. 两条调用路径

| 路径 | 入口 | 用途 |
|------|------|------|
| **vision**（视觉） | `AliyunVisionClient.vision(image_path, text)` | 给图片 + prompt，返回模型回答 |
| **text**（文本）   | `AliyunVisionClient.text(text, system=None)`   | 纯文本对话，返回模型回答 |

模型端主入口 `PhotoClassifier` 把这两条路径包装成 `analyze(photo_path)` 和
`extract_query_tags(query)` 两个异步方法，分别对应后端：
- `AIService.analyze(...)` —— 照片分析任务
- `AIService.extract_query_tags(...)` —— 搜索意图理解

---

## 2. 接入核心代码（与用户提供的完全一致）

```python
import os
from openai import OpenAI

client = OpenAI(
    api_key=os.getenv("DASHSCOPE_API_KEY"),
    base_url="https://{workspace_id}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
)
completion = client.chat.completions.create(
    model="qwen3-vl-plus",
    messages=[{
        "role": "user",
        "content": [
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}},
            {"type": "text", "text": "请按以下 JSON Schema 分类..."},
        ],
    }],
)
print(completion.choices[0].message.content)
```

封装在 `model/inference/aliyun/client.py`，本地图自动转 base64 data URL。

---

## 3. 配置

在 `backend/.env`（已在 `.gitignore`）中填：

```env
# 必填
DASHSCOPE_API_KEY=sk-xxx

# 二选一：
DASHSCOPE_WORKSPACE_ID=ws-xxxxxxxxxx    # 推荐：用 workspace id 拼 base_url
# DASHSCOPE_BASE_URL=https://xxx.maas.aliyuncs.com/compatible-mode/v1   # 或者直接给完整 URL

# 可选（有默认值）
DASHSCOPE_MODEL=qwen3-vl-plus
```

切换后端到真实模式：

```env
AI_SERVICE=real
```

缺任一必填项时，分析任务会自动 fallback 到 mock（写日志，不抛异常）。

---

## 4. 在 backend venv 中运行 CLI

```bash
cd backend
source .venv/Scripts/activate          # PowerShell: .venv\Scripts\Activate.ps1
pip install -r ../model/requirements.txt

# 单张（需配置 DASHSCOPE_API_KEY + DASHSCOPE_WORKSPACE_ID）
PYTHONPATH=.. python -m model.inference.cli --image ../docs/design_ui/some.jpg

# mock 模式（无需任何配置）
PYTHONPATH=.. python -m model.inference.cli --image x.jpg --provider mock

# 批量
PYTHONPATH=.. python -m model.inference.cli --dir ../docs/design_ui --out result.json
```

---

## 5. 目录结构

```
model/inference/
├── __init__.py
├── classifier.py            # 主入口 PhotoClassifier
├── config.py                # ClassifierConfig（60 个默认分类）
├── schema.py                # AIAnalysisResult
├── postprocess.py           # 模型输出归一化 / 兜底
├── cli.py                   # python -m model.inference.cli
├── aliyun/
│   ├── __init__.py
│   └── client.py            # AliyunVisionClient（vision + text 两条路径）
└── prompts/
    ├── __init__.py
    └── classify.py          # Prompt 模板
```

---

## 6. 单元测试

```bash
cd backend
source .venv/Scripts/activate
PYTHONPATH=.. pytest ../model/tests -v
```

8 个测试：归一化（5）+ Prompt（3）。

---

## 7. 容错

`PhotoClassifier` 在以下情况 fallback 到 mock（写日志，不抛异常）：
- `DASHSCOPE_API_KEY` / `DASHSCOPE_WORKSPACE_ID` 未配置
- 网络异常 / 阿里云返回非 2xx
- 模型返回非 JSON

`analyze_photo_task` 不会因 AI 故障导致全量失败。
