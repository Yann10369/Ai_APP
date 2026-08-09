# 项目五：照片智能擦除小助手（图像实例分割与修复方向）

## 1. 项目简介

面向日常修图场景，用户通过手动涂抹框选多余元素（人物、宠物等），AI 自动完成目标区域擦除，并无缝补全背景纹理与光影。

## 2. 三人分工

- **移动端前端（1 人）**：相机/相册调用、画笔涂抹交互（支持调节粗细与撤销）、Loading 状态展示、原图/修复图滑动对比及本地历史记录管理。
- **后端（1 人）**：接口设计、蒙版与原图像素级对齐预处理、异步封装调用 AI API 处理修复请求、请求缓存去重、异常拦截及服务器端图片定期清理机制等。
- **AI 模型（1 人）**：对接专用图像修复 API（如 LaMa / Stable Diffusion Inpainting）或大模型辅助生成蒙版，分场景调优 Prompt 模板库，测试多场景效果、模型调参方案。

## 3. 目录结构

```
AI-Photo-Eraser/
├── frontend/        # Android 客户端代码
├── backend/         # Python FastAPI/Flask 后端
├── model/           # 图像修复模型与 Prompt 模板
├── docs/            # 接口文档与项目说明
└── README.md
```

## 4. 技术选型

- **移动端**：Android / Flutter / 微信小程序（先选择 Android）
- **后端**：Python FastAPI / Flask（轻量接口，轻量架构）
- **AI 模型**：
  - 方案 A：优先接入专用图像修复 API（如 LaMa / Stable Diffusion Inpainting），端侧部署。
  - 方案 B：备选豆包 / 千问等大模型 API，API 密钥存放于后端，Android 端不存储密钥。