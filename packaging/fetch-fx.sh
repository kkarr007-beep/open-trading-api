#!/bin/sh
# 자체 창에 쓰는 화면 엔진(JavaFX)을 한 폴더에 모은다.  사용: fetch-fx.sh <win|linux|mac|mac-aarch64> <폴더>
# 함께 넣는 Java 실행 환경에 빠져 있는 jdk.xml.dom 모듈은 이 컴퓨터의 JDK에서 꺼내 넣는다(운영체제와 무관한 인터페이스 묶음).
set -e
PLATFORM="$1"; OUT="$2"; V="${FX_VERSION:-25.0.1}"
REPO=https://repo.maven.apache.org/maven2/org/openjfx
mkdir -p "$OUT"
# 저장소가 잠시 막으면(429) 쉬었다가 다시 받는다.
get() { for t in 1 2 3 4 5 6; do curl -fsS -o "$1" "$2" && return 0; sleep $((t * 15)); done; return 1; }
for a in base graphics controls media web; do
  get "$OUT/javafx-$a.jar" "$REPO/javafx-$a/$V/javafx-$a-$V-$PLATFORM.jar"
done
get "$OUT/jdk-jsobject.jar" "$REPO/jdk-jsobject/$V/jdk-jsobject-$V-$PLATFORM.jar"
JMOD="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/jmods/jdk.xml.dom.jmod"
[ -f "$JMOD" ] || { echo "jdk.xml.dom.jmod 을 찾지 못했습니다: $JMOD"; exit 1; }
TMP="$(mktemp -d)"
jmod extract --dir "$TMP" "$JMOD"
jar --create --file "$OUT/jdk.xml.dom.jar" -C "$TMP/classes" .
rm -rf "$TMP"
ls -la "$OUT"
