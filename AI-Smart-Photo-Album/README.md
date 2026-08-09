# AI Smart Photo Album

> 一个智能相册服务项目。基于 AI 的照片分类、检索与人脸识别能力，提供 Web 前端、后端服务与机器学习模型三层架构。

This repository hosts the **AI Smart Photo Album** service. It follows a standard 4-layer directory layout for separation of concerns between UI, server logic, data / ML models, and documentation.

## 📁 项目结构

```
AI-Smart-Photo-Album/
├── frontend/           # 前端代码（Web / H5 / 小程序 / 管理后台）
├── backend/            # 后端服务（API / 业务逻辑 / 数据处理）
├── model/              # 数据模型与机器学习 / 深度学习模型
├── docs/               # 项目文档（需求 / 架构 / API / 部署 / 变更日志）
│   ├── design/         # UI / 接口设计文档
│   ├── design_ui/      # 设计稿截图
│   └── 项目3.pdf        # 项目原始需求 PDF
└── README.md           # 当前文件
```

## 📂 各目录说明

| 目录                | 作用                                             | 详细说明                                                  |
|---------------------|--------------------------------------------------|-----------------------------------------------------------|
| `frontend/`         | 前端代码                                         | [frontend/README.md](./frontend/README.md)               |
| `backend/`          | 后端服务代码                                     | [backend/README.md](./backend/README.md)                 |
| `model/`            | 数据结构与 ML / DL 模型                          | [model/README.md](./model/README.md)                     |
| `docs/`             | 项目相关文档（含 design / design_ui / PDF）     | [docs/README.md](./docs/README.md)                       |
| `docs/pr_git.md`    | 智能体技能：自动提交代码并申请 PR                | [docs/pr_git.md](./docs/pr_git.md)                       |

## 🎯 项目目标

构建一个具备以下能力的智能相册服务：

- 📸 自动按人物 / 场景 / 时间对照片进行分类
- 🔍 基于自然语言或样例图片的照片检索
- 👤 人脸识别与聚类
- 🖼️ Web 端相册浏览、搜索、分享
- 🧠 可扩展的模型推理服务

## 🚀 快速开始

1. **克隆仓库**
   ```bash
   git clone https://github.com/Yann10369/AI-Smart-Photo-Album.git
   cd AI-Smart-Photo-Album
   ```

2. **按需进入子目录**
   ```bash
   cd frontend   # 或 backend / model / docs
   ```

3. **查看子目录说明**
   每个子目录都包含一份 `README.md`，描述其用途、推荐技术栈与本地运行方式。

## 🤝 贡献

- 提交前请阅读 [docs/pr_git.md](./docs/pr_git.md) 工作流规范。
- 严格按模块归属（frontend / backend / model / docs / root）提交，避免越界。
- 提交信息使用 `<type>(<module>): <subject>` 格式。

## 📝 License

请在此处补充项目的开源许可证（如 MIT / Apache-2.0 等），或标注为内部 / 课程项目。