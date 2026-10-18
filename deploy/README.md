# M6 部署（deploy/）

本目录是"部署就绪"的全部产出：**不做真实云资源操作**（Oracle 账号需 Ma 自己注册），
所有脚本与配置在部署机上按 `DEPLOY_CHECKLIST.md` 执行即可。

## 目录

```
deploy/
├── Dockerfile # 多阶段构建：Maven 打 Java 包 → JRE21+Python3 运行镜像
├── docker-compose.yml # 一键起：api + cloudflared，数据卷持久化
├── cloudflared/
│ └── config.yml.example # Tunnel 说明：默认快速隧道（免账号免域名）；底部有命名隧道进阶模板
├── daily.sh # 每日任务：采集 → 选股 → 模拟调仓（分步，可单独跑）
├── backup.sh # 每日备份 quant.db（SQLite 在线备份，保留 30 天）
├── DEPLOY_CHECKLIST.md # ★ 给 Ma 的部署清单（三件需配合的事 + 验证方法）
└── README.md # 本文件
```

## 部署架构

```
┌─────────────────────────────────────────────┐
手机/电脑浏览器 ──▶ │ Cloudflare Edge (HTTPS, 自动证书) │
└──────────────┬──────────────────────────────┘
│ 快速隧道 trycloudflare（出站连接，无需开防火墙端口）
┌──────────────▼──────────────────────────────┐
│ cloudflared 容器 │
└──────────────┬──────────────────────────────┘
┌──────────────▼──────────────────────────────┐
│ api 容器 (:8080) │
│ ├─ Spring Boot quant-api（REST + 托管前端） │
│ ├─ CLI：quant-engine / quant-paper（定时调仓）│
│ ├─ Python venv：collect.py（每日采集） │
│ └─ /opt/quant/bin：daily.sh / backup.sh │
└──────────────┬──────────────────────────────┘
┌──────────────▼──────────────────────────────┐
│ 卷../data → /data │
│ quant.db / logs / reports / backup │
└─────────────────────────────────────────────┘
```

## 每日链路（生产）

| 时间 | 动作 | 实现 |
|---|---|---|
| 15:30 | 采集 | cron → `daily.sh collect` → `collect.py` 增量拉日线 |
| 16:00 | 选股 | cron → `daily.sh select` → quant-engine CLI（候选清单入库） |
| 16:10 | 模拟调仓 | cron → `daily.sh paper` → quant-paper CLI（T+1 撮合、理由入库） |
| 次日 8:30 | 简报 | 旺财定时任务：读库 → 聊天发候选清单 + 模拟盈亏 + 页面链接 |
| 02:00 | 备份 | cron → `backup.sh`（SQLite `.backup`，保留 30 天） |
| 周日 20:00 | 基本面 | cron → `collect.py --full --crosscheck` |

失败处理：`daily.sh` 每步失败记日志并打印 ``；
可配 `ALERT_WEBHOOK`（企业微信/钉钉机器人）推送到手机。

## 镜像说明

- 构建阶段 `maven:3.9-eclipse-temurin-21`：`mvn -pl quant-api -am package -DskipTests`
产出 4 个 jar（api fat jar 含 Spring Boot repackage）。
- 运行阶段 `eclipse-temurin:21-jre` + `apt install python3/venv/pip/sqlite3`。
- 两个基础镜像均为多架构（amd64/arm64），Oracle Ampere ARM 直接可用。
- 前端 `frontend/dist` 为预构建产物，Dockerfile 直接 COPY；
改了前端代码后在源码机 `npm run build` 再重新 `docker compose build`。

## 本地验证（不动云资源可做的）

```bash
# 校验 compose 文件语法（需本机有 docker）
docker compose -f deploy/docker-compose.yml config
# 校验脚本语法
bash -n deploy/daily.sh && bash -n deploy/backup.sh && echo OK
```

## 已知限制（诚实）

1. Dockerfile 未在沙箱实际构建（构建需 5–15 分钟且沙箱代理干扰 Maven；
构建逻辑与各模块 README 的常规网络流程一致，部署机上有正常网络即可）。
2. `quant-api-1.0.0.jar` 等 jar 名以 `mvn package` 实际产出为准，
若版本号变化，同步改 Dockerfile 里的 `COPY` 路径。
3. AI 通道为 Mock；DeepSeek 真实接线是后续开发项，Key 到时走环境变量。
4. "下一交易日生效"为自然日+1 近似（无交易日历），接入交易日历后修正。
5. 输出非投资建议；模拟交易不代表实盘收益。
