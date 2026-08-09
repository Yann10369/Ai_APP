# Frontend

前端代码目录。

用于存放所有面向用户的前端项目代码，例如 Web 前端、移动端 H5、小程序、管理后台 UI 等。

## 建议子结构

```
frontend/
├── src/                # 源代码
│   ├── assets/         # 静态资源（图片、字体等）
│   ├── components/     # 通用组件
│   ├── pages/          # 页面
│   ├── router/         # 路由
│   ├── store/          # 状态管理
│   ├── utils/          # 工具函数
│   ├── api/            # 与后端交互的接口封装
│   ├── App.vue         # 入口组件
│   └── main.js         # 入口文件
├── public/             # 不经过构建的静态资源
├── tests/              # 前端单元测试 / E2E 测试
├── index.html          # HTML 入口
├── package.json        # 依赖与脚本
└── vite.config.js      # 构建工具配置（示例）
```

## 常用技术栈参考

- **Vue 3 + Vite + Pinia**  （轻量、上手快）
- **React + Vite + Redux/Zustand** （生态丰富）
- **Next.js / Nuxt.js** （需要 SSR / SSG 时使用）
- **uni-app / Taro** （跨端：小程序 + H5 + App）

## 本地开发

```bash
cd frontend
npm install
npm run dev
```