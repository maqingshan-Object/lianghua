# ============ 构建阶段：Java 多模块（需正常网络，Docker 构建环境通常满足） ============
FROM maven:3.9-eclipse-temurin-21 AS java-builder
WORKDIR /build
# 先只拷 pom，利用 Docker 层缓存依赖
COPY backend/pom.xml ./backend/
COPY backend/quant-engine/pom.xml ./backend/quant-engine/
COPY backend/quant-paper/pom.xml ./backend/quant-paper/
COPY backend/quant-backtest/pom.xml ./backend/quant-backtest/
COPY backend/quant-api/pom.xml ./backend/quant-api/
RUN cd backend && mvn -q dependency:go-offline -pl quant-api -am || true
COPY backend/ ./backend/
RUN cd backend && mvn -q -pl quant-api -am package -DskipTests \
 && ls quant-api/target/quant-api-*.jar \
       quant-engine/target/quant-engine-*.jar \
       quant-paper/target/quant-paper-*.jar \
       quant-backtest/target/quant-backtest-*.jar

# ============ 运行阶段：JRE 21 + Python 3.x + 前端静态文件 ============
# 多架构镜像（amd64/arm64），Oracle Ampere ARM 可直接用
FROM eclipse-temurin:21-jre
ENV TZ=Asia/Shanghai \
    DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-venv python3-pip sqlite3 curl tzdata \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/quant

# Python 采集环境（M1：akshare / baostock / pandas）
COPY collector/requirements.txt ./collector-requirements.txt
RUN python3 -m venv /opt/quant/venv \
 && /opt/quant/venv/bin/pip install --no-cache-dir -r collector-requirements.txt

# Java：Spring Boot API（fat jar）+ CLI 工具 jar
COPY --from=java-builder /build/backend/quant-api/target/quant-api-1.0.0.jar /opt/quant/api/
COPY --from=java-builder /build/backend/quant-engine/target/quant-engine-1.0.0.jar /opt/quant/cli/
COPY --from=java-builder /build/backend/quant-paper/target/quant-paper-1.0.0.jar /opt/quant/cli/
COPY --from=java-builder /build/backend/quant-backtest/target/quant-backtest-1.0.0.jar /opt/quant/cli/

# Python 采集脚本
COPY collector/collect.py collector/db.py /opt/quant/collector/
COPY collector/providers/ /opt/quant/collector/providers/

# 前端构建产物（M5b：frontend/dist，部署前在源码机 npm run build 生成）
COPY frontend/dist/ /opt/quant/frontend/dist/

# 运维脚本
COPY deploy/daily.sh deploy/backup.sh /opt/quant/bin/
RUN chmod +x /opt/quant/bin/*.sh

# 数据卷：quant.db / logs / reports / backup 持久化
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=10s --retries=3 \
  CMD curl -f http://localhost:8080/api/health || exit 1

CMD ["sh","-c","exec java -jar /opt/quant/api/quant-api-1.0.0.jar \
  --app.db-path=/data/quant.db \
  --app.frontend-dist=/opt/quant/frontend/dist \
  --app.collector-python=/opt/quant/venv/bin/python \
  --app.collector-script=/opt/quant/collector/collect.py \
  --app.reports-dir=/data/reports"]
