#!/bin/sh
# 감사기록 실행 (macOS·Linux). 한글 파일 이름을 위해 UTF-8 로캘로 띄웁니다.
cd "$(dirname "$0")"
[ -f target/audit-record.jar ] || mvn -q -DskipTests package || exit 1
LC_ALL="${LC_ALL:-C.UTF-8}" exec java -Dfile.encoding=UTF-8 -jar target/audit-record.jar "$@"
