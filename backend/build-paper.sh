#!/bin/bash
# quant-paper 沙箱构建脚本（Maven 在此沙箱跑不通，见 backend/README.md）
# 用法：bash build.sh [test]   # 不带参数只编译+组装；带 test 跑全部单测
set -e
cd "$(dirname "$0")"

ENGINE_CLASSES=quant-engine/target/classes
mkdir -p quant-paper/target/classes quant-paper/target/test-classes

echo "== 编译 quant-engine（增量，含 StrategyConfig 新配置项）=="
find quant-engine/src/main/java -name "*.java" > /tmp/paper_main_sources.txt
javac -encoding UTF-8 --release 21 -cp "libs/*" -d "$ENGINE_CLASSES" @/tmp/paper_main_sources.txt

echo "== 编译 quant-paper =="
find quant-paper/src/main/java -name "*.java" > /tmp/paper_main2_sources.txt
javac -encoding UTF-8 --release 21 -cp "libs/*:$ENGINE_CLASSES" -d quant-paper/target/classes @/tmp/paper_main2_sources.txt

echo "== 组装 fat jar =="
rm -rf /tmp/paperjar && mkdir -p /tmp/paperjar
cd /tmp/paperjar
for j in "$OLDPWD"/libs/*.jar; do unzip -q -o "$j" -x 'META-INF/*.SF' 'META-INF/*.DSA' 'META-INF/*.RSA'; done
cp -r "$OLDPWD/$ENGINE_CLASSES"/* .
cp -r "$OLDPWD"/quant-paper/target/classes/* .
jar --create --file "$OLDPWD"/quant-paper/target/quant-paper-1.0.0.jar \
    --main-class com.quant.paper.cli.PaperCommand -C . .
cd "$OLDPWD"
echo "fat jar: quant-paper/target/quant-paper-1.0.0.jar"

if [ "$1" = "test" ]; then
  echo "== 编译测试 =="
  find quant-paper/src/test/java -name "*.java" > /tmp/paper_test_sources.txt
  javac -encoding UTF-8 --release 21 \
    -cp "libs/*:$ENGINE_CLASSES:quant-paper/target/classes" \
    -d quant-paper/target/test-classes @/tmp/paper_test_sources.txt
  echo "== 运行单测 =="
  # JUnit Console 的 --class-path 不展开通配符，bash 里先展开
  LIBS_CP=$(echo libs/*.jar | tr ' ' ':')
  java -jar libs/junit-platform-console-standalone-1.11.4.jar \
    --class-path "quant-paper/target/classes:quant-paper/target/test-classes:$ENGINE_CLASSES:$LIBS_CP" \
    --scan-class-path --include-classname='.*Test'
fi
