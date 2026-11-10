# 감사기록

감사파트 전용 기록 프로그램입니다. Windows x64 서버 한 대에서 실행하고, 같은 내부망의 다른 컴퓨터는 브라우저로만 접속합니다. 화면은 할 일, 건 목록, 건 상세, 관리 네 개입니다.

이 저장소의 기본 설정은 운영 모드입니다. 예시 데이터와 예시 계정은 `Audit:Mode`가 `Demo`일 때만 만들어집니다.

## 로컬에서 실행

.NET 10 SDK가 필요합니다.

```bash
dotnet run --project src/AuditRecord.App --urls http://127.0.0.1:47321
```

개발 환경(`DOTNET_ENVIRONMENT=Development`)은 예시 모드로 가짜 건 둘을 넣습니다. 로그인 값은 `src/AuditRecord.App/var/data/demo-access.local`에만 있고, 문서나 화면에 다시 적지 않습니다.

운영 모드로 처음 켜면 `/account/setup`에서 관리자 한 명을 만듭니다. 가입 페이지는 그 다음에 닫힙니다.

## 테스트

```bash
dotnet test tests/AuditRecord.Tests
```

## 배포

`packaging/`의 안내와 스크립트를 사용합니다. 대상 Windows에는 .NET, Java, Python, Docker, Node를 설치하지 않습니다. 자체 포함 게시물과 `e_sqlite3.dll`을 같이 둡니다.

폴더 배치는 `감사기록/app`, `data`, `files`, `backup`입니다.
