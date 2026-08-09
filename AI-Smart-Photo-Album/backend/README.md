# Backend

后端服务代码目录。

用于存放服务端业务逻辑、API 接口、鉴权、数据处理、定时任务等服务端代码。

## 建议子结构

```
backend/
├── src/
│   ├── main.py         # 入口文件（示例：Python / FastAPI）
│   ├── api/            # 路由 / Controller 层
│   │   └── v1/         # API 版本
│   ├── services/       # 业务逻辑层（Service）
│   ├── core/           # 配置、安全、中间件等核心模块
│   ├── db/             # 数据库连接、ORM 模型
│   ├── schemas/        # 请求 / 响应数据模型（Pydantic / DTO）
│   ├── utils/          # 工具函数
│   └── config.py       # 配置加载
├── tests/              # 单元测试 / 集成测试
├── scripts/            # 运维脚本（迁移、初始化等）
├── requirements.txt    # Python 依赖（示例）
└── Dockerfile          # 容器化部署
```

## 常用技术栈参考

- **Python：** FastAPI / Flask / Django
- **Node.js：** Express / NestJS / Koa
- **Java：** Spring Boot
- **Go：** Gin / Echo
- **数据库：** MySQL / PostgreSQL / MongoDB / Redis

## 本地开发

```bash
cd backend
pip install -r requirements.txt   # Python 示例
uvicorn src.main:app --reload     # 启动开发服务器
```