# AI 智能相册后端

## 启动

```bash
# 1. 准备 MySQL（需先创建数据库）
mysql -uroot -p -e "CREATE DATABASE ai_album CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. 复制环境变量
cp .env.example .env
# 编辑 .env 填入正确 DATABASE_URL 与 JWT_SECRET

# 3. 安装依赖
pip install -e ".[dev]"

# 4. 启动
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

启动时自动建表并 seed 60 个分类。访问 http://localhost:8000/docs 查看 OpenAPI 文档。

## 测试

```bash
pytest -v
```

## 配置项

见 `.env.example`。关键变量：
- `AI_SERVICE`：mock / real
- `MAX_UPLOAD_SIZE_MB`：单文件大小上限
