# CS2 Demo Show 项目规则

- 正式项目由根目录 Python 解析器、`server/` Spring Boot 后端和 `web/` Vue 前端组成。
- MySQL `csdemo` 是统计数据的唯一真实来源；队伍严格按 `match_players.team_number` 分组。
- 正式统计规则见 `docs/STATISTICS_RULES.md`。
- 后端采用 Controller → Service → Repository 分层，不把 SQL 或统计逻辑放回 Controller。
- 后端修改后运行 `mvn -q -DskipTests package`；前端修改后运行 `npm run build`。
- 不提交 `.env`、Demo、头像、数据库、日志、虚拟环境、`node_modules`、`target` 或 `dist`。
- 保留无关用户修改，不执行破坏性 Git 操作。
