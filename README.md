# Ai_APP — AI 应用项目集合

> 一个用于托管**多个 AI 应用项目**的 GitHub 仓库。每个顶层目录对应一个独立项目。

## 📁 仓库结构

```
Ai_APP/
├── README.md                  # 当前文件（仓库总览）
├── AI-Smart-Photo-Album/      # 项目：智能相册（Web 端，含 AI 图像分类 / 人脸识别）
├── AI-Mobile-Notes-App/       # 项目：AI 随身图文笔记助手（Android，大模型方向）
└── AI-Photo-Eraser/           # 项目：照片智能擦除小助手（Android，图像修复方向）
```

每个项目目录遵循统一的内部布局：

```
<项目名>/
├── README.md        # 项目说明、技术选型、分工
├── frontend/        # 前端 / 客户端代码
├── backend/         # 后端服务
├── model/           # 模型、数据集、Prompt 工程
└── docs/            # 项目专属文档（需求 / 接口 / 设计 / 原始 PDF）
```

## 🚀 如何添加新项目

1. 在仓库根目录创建新文件夹，命名为 `<项目名>`（使用 PascalCase 或 kebab-case）。
2. 复制上面的标准布局（含空的 `README.md` 占位）。
3. 在项目根的 `README.md` 中补充项目简介、技术选型、分工。
4. 提交时建议使用 `chore(repo): add <项目名>` 形式的提交信息。

## 📝 各项目入口

| 项目 | 方向 | 平台 | 入口 |
|------|------|------|------|
| AI-Smart-Photo-Album | 图像识别 / 分类 | Web | [README](./AI-Smart-Photo-Album/README.md) |
| AI-Mobile-Notes-App  | 多模态大模型       | Android | [README](./AI-Mobile-Notes-App/README.md) |
| AI-Photo-Eraser      | 图像修复 / Inpainting | Android | [README](./AI-Photo-Eraser/README.md) |