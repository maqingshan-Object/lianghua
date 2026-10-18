#!/bin/bash
# quant-api 沙箱构建脚本（Maven 在此沙箱跑不通，见 backend/README.md）
# 用法：bash build-api.sh        # 只编译
#      bash build-api.sh run     # 编译 + 启动（前台，Ctrl+C 停止）
set -e
cd "$(dirname "$0")"

ENG=quant-engine/target/classes
PAPER=quant-paper/target/classes
BT=quant-backtest/target/classes
for d in "$ENG" "$PAPER" "$BT"; do
  if [ ! -d "$d" ]; then
    echo "缺少 $d，请先跑对应模块的构建脚本（build-paper.sh / build-backtest.sh）"
    exit 1
  fi
done

SPRING_CP=$(echo libs-spring/*.jar | tr ' ' ':')
CP="$SPRING_CP:libs/sqlite-jdbc-3.46.1.0.jar:libs/slf4j-api-2.0.16.jar:$ENG:$PAPER:$BT"

echo "== 编译 quant-api =="
mkdir -p quant-api/target/classes
find quant-api/src/main/java -name "*.java" > /tmp/api_sources.txt
javac -encoding UTF-8 --release 21 -parameters -cp "$CP" -d quant-api/target/classes @/tmp/api_sources.txt
cp -r quant-api/src/main/resources/* quant-api/target/classes/
echo "编译通过: quant-api/target/classes"

if [ "$1" = "run" ]; then
  echo "== 启动 quant-api（:8080）=="
  # 注意：classpath 故意不含 slf4j-simple（用 logback）；不含 junit/assertj
  java -cp "quant-api/target/classes:$CP" com.quant.api.QuantApiApplication
fi
