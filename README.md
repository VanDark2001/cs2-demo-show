# CS2 Demo Show

CS2 Demo Show 用于解析 Counter-Strike 2 Demo，并通过网页展示比赛、玩家、地图、武器、高光和残局统计。

## 架构

`.dem → Python/demoparser2 → MySQL → Spring Boot MVC → Vue 3/ECharts`

- `demoparser2_mysql.py`：解析 Demo 并写入 MySQL。
- `steam_avatar_scraper.py`：下载并缓存 Steam 头像。
- `server/`：Java 17 + Spring Boot，Controller → Service → Repository。
- `web/`：Vue 3 + Vite。
- `docs/STATISTICS_RULES.md`：正式统计口径。

## 环境要求

Java 17、Maven 3.9+、Node.js 22+、Python 3.12、MySQL 8。

## 数据库与配置

```powershell
Copy-Item .env.example .env
```

编辑 `.env`：将 `MYSQL_ADMIN_PASSWORD` 填为本机 MySQL 管理员密码，并为
`MYSQL_PASSWORD` 设置应用账号密码。首次启动不需要手工执行 SQL。

`MYSQL_ADMIN_USER` 和 `MYSQL_ADMIN_PASSWORD` 仅用于创建数据库、应用账号和授权；
后端及解析器日常只使用 `MYSQL_USER`。真实 `.env` 不要提交。

## 一键启动

```powershell
.\start-demo-show.bat
```

首次运行会创建 `.venv312`、安装 Python/npm 依赖、自动创建 MySQL 数据库、应用
账号和全部数据表，随后构建并启动前后端。电脑上仍需预先安装并启动 MySQL 8，
且 `.env` 中的管理员账号必须拥有建库、建用户和授权权限。

Steam 头像缓存会在服务启动后并发后台更新，不会阻塞后端和前端启动。如果本机
无法访问 Steam，可在 `.env` 设置 `UPDATE_STEAM_AVATARS_ON_START=false`；已有
缓存和 Demo 解析不受影响。

- 前端：`http://localhost:5173`
- 后端：`http://localhost:8090`
- 健康检查：`http://localhost:8090/api/health/db`

## 手动验证

```powershell
cd server
mvn -q -DskipTests package
cd ..\web
npm ci
npm run build
cd ..
.\.venv312\Scripts\python.exe -m py_compile demoparser2_mysql.py steam_avatar_scraper.py db_schema.py init_mysql.py
```

发布包不包含 Demo、头像、数据库、日志、依赖缓存、构建产物或个人文件。项目与 Valve、Steam、Counter-Strike 或 HLTV 无官方关联；Rating 和 WE 是自定义近似指标。
