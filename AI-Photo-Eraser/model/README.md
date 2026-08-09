# AI Photo Eraser — 模型模块

图像修复（Inpainting）模型调用层，支持 **LaMa（本地）**、**Stable Diffusion Inpainting（云端）**、**豆包（云端）** 三种后端，提供蒙版预处理、后处理、缓存去重、效果评估。

## 快速开始

```bash
cd AI-Photo-Eraser
pip install -r model/requirements.txt
cp model/.env.example model/.env  # 填入配置

# 1. 准备 LaMa 权重（见 model/weights/README.md）
# 2. 准备测试图片：tests/fixtures/sample.jpg
python -m model.main
```

## 目录速览

```
model/
├── core/           抽象接口、数据类型、异常
├── backends/       后端实现（LaMa / SD / Doubao）
├── preprocessing/  蒙版预处理（blur / dilate / normalize）
├── postprocessing/ 后处理（alpha blend / poisson clone）
├── prompts/        场景化 Prompt 模板
├── services/       业务编排（InpaintService）
├── utils/          IO、哈希、日志
├── weights/        模型权重（需自行下载）
├── config.py
└── main.py
```

## 详细方案

见 `MODEL_IMPL.md`（同目录）。