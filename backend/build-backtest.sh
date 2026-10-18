#!/bin/bash
# quant-backtest 沙箱构建脚本（Maven 在此沙箱跑不通，见 backend/README.md）
# 用法：bash build-backtest.sh [test]   # 不带参数只编译+组装；带 test 跑全部单测
set -e
cd "$(dirname "$0")"

ENGINE_CLASSES=quant-engine/target/classes
PAPER_CLASSES=quant-paper/target/classes
mkdir -p quant-backtest/target/classes quant-backtest/target/test-classes

echo "== 编译 quant-engine（增量）=="
find quant-engine/src/main/java -name "*.java" > /tmp/bt_main1_sources.txt
javac -encoding UTF-8 --release 21 -cp "libs/*" -d "$ENGINE_CLASSES" @/tmp/bt_main1_sources.txt

echo "== 编译 quant-paper（增量）=="
find quant-paper/src/main/java -name "*.java" > /tmp/bt_main2_sources.txt
javac -encoding UTF-8 --release 21 -cp "libs/*:$ENGINE_CLASSES" -d "$PAPER_CLASSES" @/tmp/bt_main2_sources.txt

echo "== 编译 quant-backtest =="
find quant-backtest/src/main/java -name "*.java" > /tmp/bt_main3_sources.txt
javac -encoding UTF-8 --release 21 -cp "libs/*:$ENGINE_CLASSES:$PAPER_CLASSES" \
  -d quant-backtest/target/classes @/tmp/bt_main3_sources.txt

echo "== 组装 fat jar =="
rm -rf /tmp/btjar && mkdir -p /tmp/btjar
cd /tmp/btjar
for j in "$OLDPWD"/libs/*.jar; do unzip -q -o "$j" -x 'META-INF/*.SF' 'META-INF/*.DSA' 'META-INF/*.RSA'; done
cp -r "$OLDPWD/$ENGINE_CLASSES"/* .
cp -r "$OLDPWD/$PAPER_CLASSES"/* .
cp -r "$OLDPWD"/quant-backtest/target/classes/* .
jar --create --file "$OLDPWD"/quant-backtest/target/quant-backtest-1.0.0.jar \
    --main-class com.quant.backtest.cli.BacktestCommand -C . .
cd "$OLDPWD"
echo "fat jar: quant-backtest/target/quant-backtest-1.0.0.jar"

if [ "$1" = "test" ]; then
  echo "== 编译测试 =="
  find quant-backtest/src/test/java -name "*.java" > /tmp/bt_test_sources.txt
  javac -encoding UTF-8 --release 21 \
    -cp "libs/*:$ENGINE_CLASSES:$PAPER_CLASSES:quant-backtest/target/classes" \
    -d quant-backtest/target/test-classes @/tmp/bt_test_sources.txt
  echo "== 运行单测 =="
  LIBS_CP=$(echo libs/*.jar | tr ' ' ':')
  java -jar libs/junit-platform-console-standalone-1.11.4.jar \
    --class-path "quant-backtest/target/classes:quant-backtest/target/test-classes:$ENGINE_CLASSES:$PAPER_CLASSES:$LIBS_CP" \
    --scan-class-path --include-classname='.*Test'
fi
