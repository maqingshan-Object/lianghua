# 部署清单（M6）：需要 Ma 配合的三件事

> 代码侧已全部就绪（Dockerfile / docker-compose / 定时脚本 / 备份脚本都在 `deploy/`）。
> 下面三件事涉及你的账号与付款，必须你本人操作，我搞不定。
> 预计总耗时：约 1 小时（无需买域名、无需等 DNS）。

---

## 一、注册 Oracle Cloud Always Free（云服务器）

**为什么选它**：永久免费档里最慷慨的一家，个人项目长期跑零成本。

**重要前提（2026 年现状）**：
- 2026 年 6 月起，免费 ARM 配额已从 4 核 24G **减半为 2 核 12G**（老教程都过时了，
以官网当前页面为准）。2 核 12G 跑本项目绰绰有余（日批处理 + 一个 Web 服务）。
- 注册需要**信用卡验证**（只做身份验证，Always Free 资源不扣费）。

**步骤**：

1. 打开 https://www.oracle.com/cloud/free/ ，点 "Start for free"，按向导注册。
- 填邮箱 → 验证 → 填信用卡信息 → 完成。**只选 "Always Free" 资源**，
试用期 30 天结束后，非免费资源会被停掉，别建错。
2. 选 Home Region（主区域，**选定后不能改**）：
- 优先选离中国近的：**新加坡** 或 **东京**（AKShare 抓国内网站延迟低）。
- 如果创建实例时提示 "Out of capacity"（热门区域常见），换区域或换个时间段重试，
别死磕。
3. 创建实例：Compute → Instances → Create instance
- Image：Canonical Ubuntu 24.04（或 22.04）
- Shape：Ampere A1 → **2 OCPU / 12 GB**（免费额度上限）
- 块存储：默认 50GB（免费额度 200GB 内）
- SSH：选 "Generate a key pair"，**下载保存好私钥文件**（丢了登不上）
- 记下实例的**公网 IP**
4. 安全组：cloudflared 是**出站**连接，不需要开放入站端口（这是它最省心的地方）；
只保留 SSH（22 端口），建议把来源 IP 限制为你自己的出口 IP。
5. 服务器上安装 Docker（Ubuntu）：
```bash
ssh -i <你的私钥>.key ubuntu@<公网IP>
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-plugin
sudo usermod -aG docker ubuntu # 重新登录生效
```

**验证**：`ssh` 能登录；`docker --version` 有输出。

**文字版"截图位"**：注册成功后控制台右上角应显示 "Free Tier" 标识；
实例列表里 Shape 一栏显示 `VM.Standard.A1.Flex` 且 OCPU=2、Memory=12GB，
即表示用的是免费额度。

---

## 二、公网访问：cloudflared 快速隧道（无需买域名）

**方案**：cloudflared 快速隧道，自动生成 `https://xxx.trycloudflare.com` 临时公网地址。
**免费、无需 Cloudflare 账号、无需买域名**，手机浏览器直接打开就能看你的选股看板。

**已知限制**：cloudflared 重启后临时域名会变（去日志里看新地址就行）。
个人自己用完全够用；**以后想要固定地址，再买域名也不迟**（进阶做法见
`deploy/cloudflared/config.yml.example` 底部）。
备选：花生壳（国内内网穿透服务）也可实现类似效果。

**步骤**：

1. 把整个 `~/workspace/quant` 目录传到服务器：
```bash
scp -r -i <私钥>.key ~/workspace/quant ubuntu@<公网IP>:/opt/quant-src
```
2. 启动全部服务（cloudflared 快速隧道随 compose 一起启动，无需额外配置）：
```bash
cd /opt/quant-src
docker compose -f deploy/docker-compose.yml up -d --build
# 首次构建约 5–15 分钟（含 Maven 拉依赖 + pip 装 akshare）
docker compose -f deploy/docker-compose.yml ps        # 两个容器都应为 Up
curl -s http://localhost:8080/api/health              # 应返回 {"status":"ok"}
```
3. 查看你的公网访问地址：
```bash
docker compose -f deploy/docker-compose.yml logs cloudflared | grep trycloudflare
```
日志里形如 `https://abc-def-1234.trycloudflare.com` 的就是，手机浏览器打开即可，
看到"今日选股"页面即成功。

**验证**：手机浏览器能打开地址并看到页面；`docker compose logs cloudflared`
无报错。注意 cloudflared 容器重启后地址会变，重新执行第 3 步看新地址。

---

## 三、设置 DeepSeek API Key（启用 AI 打分通道）

**现状**：Key 你已提供（有效）。代码侧已改为**只从环境变量 `DEEPSEEK_API_KEY` 读取**，
绝不写进代码、文档或日志。AI 真实接线开发完成后，部署时按下面设置即可启用；
不设置则保持**纯引擎模式**，不影响使用。

**部署时在服务器上设置环境变量**（二选一）：

```bash
# 方式 A：写进 compose 的 env 文件（推荐）
cat >> /opt/quant-src/deploy/.env <<'EOF'
DEEPSEEK_API_KEY=YOUR_DEEPSEEK_API_KEY_HERE
EOF
# deploy/docker-compose.yml 的 api 服务 environment 段已配好
# `- DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY:-}`（compose 会自动读取同目录 .env 文件或 shell 环境变量）

# 方式 B：当前 shell 临时导出（重启 shell 后失效，仅调试用）
export DEEPSEEK_API_KEY=YOUR_DEEPSEEK_API_KEY_HERE
```

然后重启服务生效：
```bash
cd /opt/quant-src && docker compose -f deploy/docker-compose.yml up -d
```

**验证**：`docker compose exec -T api env | grep DEEPSEEK` 能看到变量名
（值会被打码显示首尾，属正常）；策略配置页可切换到 AI/融合模式。

**安全提醒**：Key 只放在服务器环境变量里，不要再贴到聊天、文档或代码里。

---

## 四、首次数据回填（部署机上跑一次）

沙箱连不上行情源，所以真实数据必须在部署机上拉。服务启动后执行：

```bash
# 日线回填（全市场约 1.5 小时，收盘后跑；可先小批量验证）
docker compose -f deploy/docker-compose.yml exec -T api \
/opt/quant/venv/bin/python /opt/quant/collector/collect.py \
--db /data/quant.db --start-date 2023-01-01 --with-stocks --limit 20
# 确认 20 只没问题后，去掉 --limit 全量跑
# 周末跑一次基本面全量 + 校验：
docker compose -f deploy/docker-compose.yml exec -T api \
/opt/quant/venv/bin/python /opt/quant/collector/collect.py \
--db /data/quant.db --full --crosscheck --with-stocks
```

**注意**：首跑时盯一下日志里的 `warning`（AKShare 列名偶发变更，
见 `collector/README.md` 已知坑 #4）。

## 五、定时任务（部署机 crontab -e）

```cron
# 交易日：15:30 采集 → 16:00 选股 → 16:10 模拟调仓
30 15 * * 1-5 docker compose -f /opt/quant-src/deploy/docker-compose.yml exec -T api /opt/quant/bin/daily.sh collect >> /opt/quant-src/data/logs/cron.log 2>&1
0 16 * * 1-5 docker compose -f /opt/quant-src/deploy/docker-compose.yml exec -T api /opt/quant/bin/daily.sh select >> /opt/quant-src/data/logs/cron.log 2>&1
10 16 * * 1-5 docker compose -f /opt/quant-src/deploy/docker-compose.yml exec -T api /opt/quant/bin/daily.sh paper >> /opt/quant-src/data/logs/cron.log 2>&1
# 每天凌晨 2 点备份数据库（保留 30 天）
0 2 * * * docker compose -f /opt/quant-src/deploy/docker-compose.yml exec -T api /opt/quant/bin/backup.sh >> /opt/quant-src/data/logs/backup.log 2>&1
# 每周日 20:00 基本面全量 + baostock 校验
0 20 * * 0 docker compose -f /opt/quant-src/deploy/docker-compose.yml exec -T api /opt/quant/venv/bin/python /opt/quant/collector/collect.py --db /data/quant.db --full --crosscheck --with-stocks >> /opt/quant-src/data/logs/cron.log 2>&1
```

## 六、早 8:30 简报

部署上线后，旺财会在聊天里每天早 8:30 给你发选股简报（候选清单 + 模拟账户盈亏 + 页面链接），
由旺财的定时任务负责，你不用管。**这是部署完成后才生效的事项**，现在不用设置。

---

## 快速自查表

| 检查项 | 命令/方法 | 期望结果 |
|---|---|---|
| 容器运行 | `docker compose -f deploy/docker-compose.yml ps` | api + cloudflared 均为 Up |
| API 健康 | `curl -s http://localhost:8080/api/health` | `{"status":"ok"}` |
| 隧道地址 | `docker compose logs cloudflared \| grep trycloudflare` | 日志里有 `https://xxx.trycloudflare.com` |
| 页面可访问 | 手机浏览器打开上面的 trycloudflare 地址 | 看到"今日选股"页 |
| 数据在更新 | `ls -lh data/quant.db` / 看 `data/logs/daily-*.log` | 库文件每天变大、日志无 |
| 备份在跑 | `ls data/backup/` | 每天一个 `quant-日期.db.gz` |

**免责**：本系统所有输出仅为量化规则计算结果，用于研究验证，
不构成投资建议；模拟交易不代表实盘收益。
