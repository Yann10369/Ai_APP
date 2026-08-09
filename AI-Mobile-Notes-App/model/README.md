# AI Mobile Notes — 模型模块

多模态大模型调用层，统一适配 OpenAI / 豆包 / 千问等厂商，支持 OCR、知识点提炼、摘要生成、自动出题、文本润色、翻译、思维导图、语义检索等业务。

## 快速开始

```bash
cd AI-Mobile-Notes-App
pip install -r model/requirements.txt
cp model/.env.example model/.env  # 填入 API Key
python -m model.main
```

## 目录速览

```
model/
├── core/         抽象接口、消息结构、Prompt 加载、异常
├── adapters/     厂商实现（OpenAI 优先，豆包/千问可扩展）
├── prompts/      Prompt YAML 模板
├── services/     业务编排（OCR / 笔记 AI / 文本 AI）
├── cache/        内存 LRU 缓存
├── utils/        日志、重试、图片编码
├── config.py     环境变量配置
└── main.py       Demo 入口
```

## 详细方案

见 `MODEL_IMPL.md`（同目录）。