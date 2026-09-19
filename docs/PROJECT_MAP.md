# 项目目录与职责

```text
cs-demo-parser/
├─ AGENTS.md                         新线程强制规则
├─ NEW_CHAT_PROMPT.md                可直接复制的新对话开场提示
├─ docs/
│  ├─ NEW_CHAT_HANDOFF.md            当前状态、功能、缓存和已知问题
│  ├─ STATISTICS_RULES.md            不可擅改的统计口径
│  ├─ PROJECT_MAP.md                 本文件
│  └─ TESTING_HANDOFF.md             自动化测试任务、优先级与安全边界
├─ demoparser2_mysql.py              当前 MySQL Demo 解析器
├─ db_schema.py                      共享 MySQL 建库、建表与升级逻辑
├─ init_mysql.py                     首次启动数据库初始化入口
├─ match_scoring.py                  常规赛/加时换边、比分计算与历史修复
├─ steam_avatar_scraper.py           Steam 头像下载与缓存
├─ uploads/                          已上传 Demo（大文件，勿清理）
├─ avatars/                          SteamID 对应头像缓存
├─ start-demo-show.bat               一键安装、构建和启动
├─ server/
│  ├─ pom.xml                        Java/Maven 依赖
│  ├─ .cache/matches.json            运行时比赛快照（已忽略）
│  └─ src/main/
│     ├─ java/com/csdemo/
│     │  ├─ DemoShowApplication.java
│     │  ├─ controller/DemoController.java   HTTP 路由与错误响应
│     │  ├─ service/DemoService.java         统计、缓存与上传业务
│     │  └─ repository/DatabaseRepository.java  MySQL 连接配置
│     └─ resources/application.properties
└─ web/
   ├─ public/maps/                    本地 CS2 地图图标
   ├─ package.json
   ├─ vite.config.js
   └─ src/
      ├─ App.vue                     根 RouterView
      ├─ main.js
      ├─ router/index.js             懒加载路由
      ├─ layouts/AppLayout.vue       顶栏和全局解析进度
      ├─ composables/
      │  ├─ useDemoData.js           比赛/analytics 状态和跨场聚合
      │  └─ useParserJobs.js         多 Demo 队列和跨页面任务状态
      ├─ lib/
      │  ├─ presentation.js          API 地址、地图/武器显示名、比分样式
      │  └─ echarts.js               ECharts 注册与公共配置
      ├─ components/
      │  ├─ AppHeader.vue
      │  ├─ BaseChart.vue
      │  ├─ MapIcon.vue
      │  ├─ ParserProgress.vue
      │  ├─ PlayerAvatar.vue
      │  └─ UploadDrawer.vue
      └─ pages/
         ├─ MatchesPage.vue          单场详情、筛选及分析标签
         ├─ PlayersPage.vue          SteamID 聚合玩家列表
         ├─ PlayerDetailPage.vue     个人资料、地图池、武器和好友
         ├─ WeaponsPage.vue          全局武器统计
         ├─ MapsPage.vue             地图 CT/T 胜率
         ├─ StatsPage.vue            全局概览
         └─ ParserPage.vue           多 Demo 解析后台
```

## 数据链路

```text
.dem
  → demoparser2_mysql.py
  → MySQL csdemo
  → DemoController SQL/统计/缓存
  → /api/*
  → useDemoData / useParserJobs
  → Vue 页面与 ECharts
```

## MySQL 表

- `matches`：Demo 路径、hash、地图、服务器、比分、正式边界、录制时间。
- `players`：唯一 SteamID 和最近名称。
- `match_players`：比赛阵容及唯一分组依据 `team_number`。
- `rounds`：回合阶段事件。
- `events`：通用事件、玩家信息、热身标记和原始 JSON。
- `kills`：击杀事件。
- `damages`：伤害事件。
- `bomb_events`：下包、拆包和爆炸事件。

## 页面路由

- `/matches/:id?`
- `/players`
- `/players/:steamid`
- `/weapons`
- `/maps`
- `/stats`
- `/parser`

## 常用检查

```powershell
git status --short
rg -n "GetMapping|PostMapping|SELECT|FROM kills|FROM damages" server/src
rg -n "fetch|API|path:|name:" web/src
cd server; mvn -q -DskipTests package
cd ..\web; npm run build
```

不要使用 `demoshow/` 下的旧代码，不要把 `mysql_schema.sql` 当成当前完整表结构。
