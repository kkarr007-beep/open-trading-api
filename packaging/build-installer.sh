#!/bin/sh
# Windows 설치 파일(dist/AuditRecord-Setup.exe)과 패치(dist/AuditRecord-Patch-<판>.exe, .zip)를 만든다.
# 패치는 실행 파일(audit-record.jar) 하나만 바꾸므로 작다. 라이브러리나 실행 환경이 바뀐 판은 설치 파일로 다시 설치한다. Linux·macOS에서 실행한다.
# 필요한 것: JDK 21(jmods 포함), Maven, makensis(nsis), pip, unzip, zip, curl
# 함께 넣는 Windows용 Java 실행 환경은 PyPI의 jdk4py 묶음에 들어 있는 OpenJDK를 꺼내 쓴다.
set -e
# makensis는 UTF-8 로캘이 아니면 마법사 화면을 넣다가 멈출 수 있다.
export LC_ALL=C.UTF-8 LANG=C.UTF-8
cd "$(dirname "$0")/.."
VERSION="${1:-$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | sed -n 2p)}"
WORK=target/installer
mkdir -p dist
mvn -q -DskipTests clean package
WORK=target/installer; mkdir -p "$WORK/stage" "$WORK/wheel"
pip download jdk4py --platform win_amd64 --only-binary=:all: --no-deps -d "$WORK/wheel" -q
unzip -q "$WORK"/wheel/*.whl 'jdk4py/java-runtime/*' -d "$WORK/unpacked"
mv "$WORK/unpacked/jdk4py/java-runtime" "$WORK/stage/jre"
rm -rf "$WORK/stage/jre/include"
[ -f "$WORK/stage/jre/bin/javaw.exe" ] || { echo "javaw.exe 없음"; exit 1; }
./packaging/fetch-fx.sh win "$WORK/stage/fx" >/dev/null
cp target/audit-record.jar "$WORK/stage/"
mkdir -p "$WORK/stage/lib" && cp target/lib/*.jar "$WORK/stage/lib/"
# 함께 넣는 Java는 64비트 Windows용이다. SQLite 라이브러리 안의 다른 운영체제용 부품을 빼서 설치 파일을 100MB 아래로 맞춘다.
for J in "$WORK"/stage/lib/sqlite-jdbc-*.jar; do
  zip -q -d "$J" 'org/sqlite/native/Linux/*' 'org/sqlite/native/Linux-Android/*' 'org/sqlite/native/Linux-Musl/*' \
    'org/sqlite/native/FreeBSD/*' 'org/sqlite/native/Mac/*' 'org/sqlite/native/Windows/armv7/*' 'org/sqlite/native/Windows/x86/*' \
    'org/sqlite/native/Windows/aarch64/*' || true
done
cp "packaging/읽어보세요.txt" "$WORK/stage/"
makensis -V2 -INPUTCHARSET UTF8 -DAPP_VERSION="$VERSION" -DSTAGE="$(pwd)/$WORK/stage" \
  -DOUTFILE="$(pwd)/dist/AuditRecord-Setup.exe" packaging/installer.nsi
makensis -V2 -INPUTCHARSET UTF8 -DAPP_VERSION="$VERSION" -DSTAGE="$(pwd)/$WORK/stage" \
  -DOUTFILE="$(pwd)/dist/AuditRecord-Patch-$VERSION.exe" packaging/patch.nsi
rm -f "dist/AuditRecord-Patch-$VERSION.zip"
(cd "$WORK/stage" && cp ../../../packaging/apply-patch.cmd . && zip -q "../../../dist/AuditRecord-Patch-$VERSION.zip" audit-record.jar apply-patch.cmd)
ls -la dist/AuditRecord-Setup.exe dist/AuditRecord-Patch-$VERSION.*
