# 衣览无余 · 前端

学生演示项目：基于 Vue3 + Vite 的图片处理台前端，支持图片编辑、合并、检索与历史记录。

---

## 学生演示说明（学习路径）

本项目适合用于学习 **Vue 3 组合式 API**、**Vue Router**、**Pinia**、**Axios** 与 **Element Plus** 的配合使用。建议按以下顺序阅读代码：

1. **入口与根组件**  
   - `src/main.js`：应用如何挂载，插件注册顺序（Pinia 需在 Router 前，因路由守卫会读登录态）。  
   - `src/App.vue`：根布局如何根据是否登录切换「登录页」与「后台壳（侧栏+顶栏+内容区）」。

2. **路由与权限**  
   - `src/router/index.js`：路由表、懒加载、`meta.auth` 与 `beforeEach` 登录守卫（未登录跳转登录页并带 `redirect`）。

3. **登录态**  
   - `src/store/auth.js`：Pinia 定义、token/user 持久化到 localStorage、`setAuth`/`clear` 的用法。

4. **请求层**  
   - `src/services/http.js`：Axios 实例、请求拦截器（自动带 token）、响应拦截器（统一处理 code≠200 与网络错误）。  
   - `src/services/api.js`：按业务封装的接口（登录、上传、编辑、合并、检索、记录），以及 `postPythonForm` 对表单编码与长超时的处理。

5. **页面与组件**  
   - `views/*.vue`：各页面数据流（ref/computed）、与 api 的调用、表单校验与用户提示（ElMessage）。  
   - `components/ImageSelectModal.vue`：双模式（单选/双选）、v-model 与 @confirm 的用法。

**重点模块**：登录守卫与 redirect、Pinia 持久化、http 拦截器统一带 token 与报错、编辑/合并页的提交与重置流程、文搜图与图搜图的参数差异（query vs file）。

---

## 技术栈

- **Vue 3** + **Vite 7**
- **Vue Router 4**、**Pinia**（登录态）
- **Element Plus**、**Axios**

---

## 快速开始

### 环境要求

- Node.js（建议 18+）
- 后端服务：Java（8080）+ Python 图片处理（由 Java 转发）

### 启动步骤

```bash
# 安装依赖（首次）
npm install

# 启动开发服务
npm run dev
```

默认访问：`http://localhost:5173`。请先启动后端，再访问前端。

### 常用脚本

| 命令 | 说明 |
|------|------|
| `npm run dev` | 启动开发服务器 |
| `npm run build` | 生产构建 |
| `npm run preview` | 预览构建结果 |

---

## 项目结构

```
src/
├── main.js              # 入口：Vue、Pinia、路由、Element Plus
├── App.vue              # 根组件：登录页 / 后台布局（侧栏 + 顶栏 + 内容区）
├── style.css            # 全局样式与 CSS 变量
├── router/index.js      # 路由与登录守卫（未登录跳转登录页）
├── store/auth.js        # 登录态（token、用户信息，持久化 localStorage）
├── services/
│   ├── http.js          # Axios 实例：baseURL、自动带 token、统一错误处理
│   └── api.js           # 接口封装（登录、上传、编辑、合并、检索、记录）
├── views/
│   ├── LoginView.vue    # 登录/注册
│   ├── EditView.vue     # 图片编辑（单图 + 指令）
│   ├── MergeView.vue    # 图片合并（双图 + 指令）
│   ├── ImagesView.vue   # 我的图片 + 文搜图 / 图搜图
│   └── RecordsView.vue  # 历史记录（表格）
├── components/
│   ├── AppHeader.vue    # 顶栏
│   ├── AppSidebar.vue   # 侧栏导航
│   └── ImageSelectModal.vue  # 选择图片弹框
└── assets/              # 静态资源（图标、占位图等）
```

**建议阅读顺序**：`main.js` → `router/index.js` → `store/auth.js` → `services/http.js` → `services/api.js` → 任意 `views/*.vue`。

---

## 接口与代理

- **Java**：对外提供 `/api/**`，前端通过 Vite 代理访问，无需跨域。
- **Python 图片处理**：由 Java 的 `/api/file/python/*` 转发并保存结果。

### 开发环境代理

开发时，前端请求 `/api/xxx` 由 Vite 转发到后端，目标地址由环境变量配置：

- 在项目根目录 **`.env.development`** 中设置：
  ```bash
  PROXY_TARGET=http://localhost:8080
  ```
- 本机覆盖：新建 **`.env.development.local`** 并设置 `PROXY_TARGET=...`，该文件已被 `.gitignore` 忽略。
- 未设置时，`vite.config.js` 默认使用 `http://localhost:8080`。
- 生产环境由 Nginx 等做反向代理，不依赖此配置。

### 接口返回格式

```json
{
  "code": 200,
  "message": "成功",
  "data": { ... }
}
```

- `code === 200`：成功，前端使用 `data`。
- `code !== 200`：业务失败，前端在响应拦截器中统一提示 `message`。
- 网络异常或 4xx/5xx：提示 `response.data.message` 或「请求失败」。

---

## 功能概览

| 功能 | 说明 |
|------|------|
| 登录/注册 | 邮箱+验证码 或 用户名+密码，成功后存 JWT |
| 图片编辑 | 从「我的图片」选一张图 + 指令，调用 `/api/file/python/edit` |
| 图片合并 | 选两张图 + 指令，调用 `/api/file/python/merge` |
| 文搜图 / 图搜图 | 在「我的图片」页内，调用 `/api/file/python/search` |
| 我的图片 | 上传、列表 `/api/file/my-images` |
| 历史记录 | 表格展示 `/api/record/my`，类型为「图片编辑」「图片合并」等 |
