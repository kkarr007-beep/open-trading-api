#!/bin/sh
# 감사기록 실행 (macOS·Linux). JDK 21 이상. 자체 창에 쓸 화면 엔진을 처음 한 번 받아 둔다.
cd "$(dirname "$0")"
[ -f target/audit-record.jar ] || mvn -q -DskipTests package || exit 1
case "$(uname -s)-$(uname -m)" in Darwin-arm64) P=mac-aarch64 ;; Darwin-*) P=mac ;; *) P=linux ;; esac
[ -f target/fx/javafx-web.jar ] || ./packaging/fetch-fx.sh "$P" target/fx >/dev/null || echo "화면 엔진을 받지 못해 브라우저로 엽니다."
LC_ALL="${LC_ALL:-C.UTF-8}" exec java --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 \
  -cp "target/audit-record.jar:target/fx/*" org.springframework.boot.loader.launch.JarLauncher "$@"
