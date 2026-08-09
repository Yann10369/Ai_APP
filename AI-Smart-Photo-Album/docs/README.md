# Docs

项目文档目录。

用于存放项目相关的所有文档：需求说明、架构设计、API 文档、部署指南、变更日志、贡献指南等。

## 建议子结构

```
docs/
├── architecture/       # 架构设计文档（含架构图）
│   ├── overview.md
│   └── diagrams/
├── api/                # API 接口文档（也可由 Swagger / OpenAPI 自动生成）
│   └── openapi.yaml
├── requirements/       # 需求文档
│   ├── prd.md          # 产品需求文档
│   └── user-stories.md
├── deployment/         # 部署 / 运维文档
│   ├── docker.md
│   └── k8s.md
├── development/        # 开发规范、编码约定
│   ├── coding-style.md
│   └── git-workflow.md
├── changelog/          # 变更日志
│   └── CHANGELOG.md
└── assets/             # 文档中引用的图片、附件等
```

## 推荐文档类型

- **README**：项目入口说明（放在仓库根目录）
- **PRD**：产品需求文档
- **API 文档**：自动生成（Swagger / Redoc / Apifox）+ 关键接口补充说明
- **架构文档**：系统架构图、技术选型理由、数据流图
- **Runbook**：上线 / 回滚 / 故障处理手册
- **ADR (Architecture Decision Records)**：重要架构决策记录

## 写作建议

- 使用 Markdown 编写，便于版本管理与协作。
- 图片统一放在 `assets/` 或各子目录下，避免散落。
- 中英文混排时保持风格一致，全英文文档优先使用英文标点。