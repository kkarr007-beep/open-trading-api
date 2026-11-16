#!/bin/sh
# Windows 설치 파일(dist/AuditRecord-Setup.exe)을 만든다. Linux·macOS에서 실행한다.
# 필요한 것: JDK 17+, Maven, makensis(nsis), pip, unzip
# 함께 넣는 Windows용 Java 실행 환경은 PyPI의 jdk4py 묶음에 들어 있는 OpenJDK를 꺼내 쓴다.
set -e
cd "$(dirname "$0")/.."
VERSION="${1:-1.1.0}"
WORK=target/installer
rm -rf "$WORK" && mkdir -p "$WORK/stage" "$WORK/wheel" dist
mvn -q -DskipTests package
pip download jdk4py --platform win_amd64 --only-binary=:all: --no-deps -d "$WORK/wheel" -q
unzip -q "$WORK"/wheel/*.whl 'jdk4py/java-runtime/*' -d "$WORK/unpacked"
mv "$WORK/unpacked/jdk4py/java-runtime" "$WORK/stage/jre"
rm -rf "$WORK/stage/jre/include"
[ -f "$WORK/stage/jre/bin/javaw.exe" ] || { echo "javaw.exe 없음"; exit 1; }
cp target/audit-record.jar "$WORK/stage/"
cp "packaging/읽어보세요.txt" "$WORK/stage/"
makensis -V2 -INPUTCHARSET UTF8 -DAPP_VERSION="$VERSION" -DSTAGE="$(pwd)/$WORK/stage" \
  -DOUTFILE="$(pwd)/dist/AuditRecord-Setup.exe" packaging/installer.nsi
ls -la dist/AuditRecord-Setup.exe
