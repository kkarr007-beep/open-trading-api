# BUILD_PLAN — 감사기록 시스템 (오프라인 Windows 배포 준비)

작성 시각: **2026-11-06 23:56 KST** (Asia/Seoul)  
보완 시각: **2026-11-06 23:59 KST** (Asia/Seoul) — 문서만 보완. 추가 다운로드·코딩·restore/build/publish 없음.  
작성 범위: 환경 실측 · 공식 SDK/공개 NuGet 사전확보 · 빌드·패키징·TLS/서비스/ACL·검증 계획.  
정합 참고: [`research/UI_OSS.md`](../research/UI_OSS.md) (.NET 10 LTS · Razor+자체 CSS/JS · `Microsoft.Data.Sqlite` 최소의존).  
**하지 않은 것:** 앱 소스 작성, `dotnet restore`/`build`/`publish`, 전역/시스템 SDK 설치, CloudAgent, git, 원격 repo, 설계·코드·사내자료 외부 업로드, Windows 설치·다른 PC·나스카 실시험.

**버전 표기 규칙:** 아래에 적힌 SDK/패키지 숫자는 **이 박스에서 실측 확보·관측한 후보**이다. 문서 표·HTTP 200만으로 “지원 패치 확정”하지 **않는다**. `dotnet restore`/`publish`로 잠근 최종 패치는 **미검증**이며, 향후 승인된 restore 시점에 **당시 최신 지원 패치**로 재확인·확정한다. “권장” ≠ “배포 확정”.

---

## 0. 사용자 요구 확인 (원문 우선)

| 출처 | 확인 |
|------|------|
| 시스템 총괄 대화 `770697aa-bdcc-4954-9a12-9c7e3e6160b8` **t63u** (user cu:452174738) | 설계서 전문 붙여넣기. C# ASP.NET Core + SQLite, Windows 단독 내부망 서버, 브라우저만 클라이언트, 4화면·3역할, 연한삭제·보류, append-only SHA-256 이력, 나스카 미열람, 예시 2건, 외부 배포 금지. |
| 동일 대화 **t65u** | “이 설계보다 뛰어나게… 깃 등 OSS 참고, UI 신경, 설계·구현·완성까지” — **품질/UI 개선 위임**. 보안·범위(실자료 금지·외부업로드 금지·나스카 비복호화 등)는 완화되지 않음. |
| `/workspace/audit-record-system/USER_SPEC.md` | **존재** (10282B, mtime 2026-11-06 23:51 KST). t63u 원문 보존본. 상태: “설계 1~4와 반영 방식 승인. 이 문서가 구현 범위의 전부”. **23:56·본 보완 시 재확인: 본문 변경 없음.** |
| 사규관리 앱 | **별도 프로젝트.** 본 계획과 무관. |

**문서 내용 미열람(원문·총괄 제약):** 감사/나스카 **업로드 파일·실문서 본문**을 열거나 처리하지 않는다는 뜻이다. 공개 NuGet 패키지 안의 **라이선스·nuspec·readme** 확인은 허용·수행함.

**원문 승인 없는 코드 착수 금지.** 구현은 총괄 안전조건 확인 후 별도 지시.

---

## 1. 확인 시각 · 환경 실측

### 1.1 호스트 (box = “my computer”, 등록된 사용자 Windows PC 비접촉)

| 항목 | 실측 | 근거 |
|------|------|------|
| 시각 | 2026-11-06 23:56 KST (보완 23:59) | `date` |
| OS | Debian GNU/Linux 13 (trixie), kernel 6.12.113+, **x86_64** | `/etc/os-release`, `uname -a` |
| 초기 `dotnet` | **없음** (PATH·표준 경로 모두 부재) | `which`/`type` 실패; `/usr/share/dotnet`, `/usr/local/share/dotnet`, `~/.dotnet`, `/opt/dotnet`, `/usr/lib/dotnet` 부재 |
| 초기 NuGet 캐시 | **없음** | `~/.nuget/packages`, `/root/.nuget/packages` 등 부재 |
| mono / msbuild / Wine / Docker | **없음** (총괄 ENVIRONMENT.md와 일치; 본 Task도 표준 경로로 재확인) | |

**주의:** 캐시/SDK 초기 부재를 “Windows 패키지 제작 불가”로 단정하지 **않음**.

### 1.2 본 Task에서 수행한 준비 (완료 · 실측)

| 항목 | 결과 |
|------|------|
| SDK | **.NET SDK 10.0.403** (포함 런타임 Host/ASP.NET Core **10.0.14**) — **실측 확보 버전 · restore/publish 미검증 후보** |
| 설치 방식 | Microsoft 공식 tarball 다운로드 → SHA-512 검증 → `/workspace` 전용 경로에만 압축 해제 (**전역/apt 설치 없음**) |
| 경로 | `DOTNET_ROOT=/workspace/audit-record-system/tools/dotnet` |
| tarball | `/workspace/audit-record-system/tools/downloads/dotnet-sdk-10.0.403-linux-x64.tar.gz` (240199984 bytes) |
| 공식 checksum 파일 | [10.0.14-sha.txt](https://builds.dotnet.microsoft.com/dotnet/checksums/10.0.14-sha.txt) (HTTP 200) |
| SHA-512 (실측=공식) | `b7b3d8461cf98b07d2552ce9199702fa3ad010ac2671a5f8cd5136f7534e50f6855ba7bbb02b11494563f1d04156276a31cda1103b900e5eecd0d9473dc7b9c5` → `sha512sum -c … --ignore-missing` **OK** |
| 다운로드 URL | [dotnet-sdk-10.0.403-linux-x64.tar.gz](https://builds.dotnet.microsoft.com/dotnet/Sdk/10.0.403/dotnet-sdk-10.0.403-linux-x64.tar.gz) (HTTP 200) |
| CLI home | `/workspace/audit-record-system/tools/cli-home` (`DOTNET_CLI_HOME`, telemetry opt-out) |
| `dotnet --list-sdks` | `10.0.403 [/workspace/audit-record-system/tools/dotnet/sdk]` |
| `dotnet --list-runtimes` | `Microsoft.AspNetCore.App 10.0.14`, `Microsoft.NETCore.App 10.0.14` |
| SDK 내 win-x64 packs | **없음** (linux-x64 host/ref만). 교차 publish용 win packs는 NuGet에서 **사전확보(후보)** (§3). |

공개 피드 차단·프록시 오류는 **발생하지 않음** (모두 HTTP 200). 우회 시도 없음.

---

## 2. 권장 TFM / RID · Linux→win-x64 publish

### 2.1 권장 최소선 (후보 · 미확정)

| 선택 | 권장(후보) | 근거 |
|------|------------|------|
| UI/앱 형태 | **Razor Pages(또는 MVC) + 자체 CSS/최소 JS** | [`UI_OSS.md`](../research/UI_OSS.md): Fluent/Carbon/GOV.UK는 패턴만; UI NuGet/CDN 0 |
| TFM | **`net10.0`** (.NET 10 LTS 방향) | [.NET support policy](https://dotnet.microsoft.com/en-us/platform/support/policy) — .NET 10 LTS EOS 2028-11-14; .NET 8/9 EOS 2026-11-10. **패치 핀은 restore 시 재확정** |
| RID (1차) | **`win-x64`** | 내부망 Windows 가정. **배포 PC OS/아키텍처 원문 미기재 → 현장 확정 필요.** `win-arm64`는 별도 |
| 배포 모드 | **`--self-contained true`** | 생산 Windows에 .NET 별도 설치 금지와 정합. [.NET publishing](https://learn.microsoft.com/en-us/dotnet/core/deploying/) |
| 단일파일 / Trim / Native AOT | **기본 비사용(보수적)** | 교차 AOT·Trim·단일파일+native SQLite 위험. 후속 승인 후만 |
| apphost | 기본 사용 | Windows `.exe` + SCM |
| Runtime/Framework 고정 | **계획만** | 확보 SDK 10.0.403 / runtime 10.0.14와 KnownFrameworkReference를 **조율할 예정**. **`RuntimeFrameworkVersion` 등을 실제로 읽어 고정한 실증은 없음**(restore 금지). |

### 2.2 “문서상 가능” vs “현 환경에서 실증”

| 주장 | 상태 |
|------|------|
| Linux SDK에서 `dotnet publish -r win-x64 --self-contained true`가 **문서상** 지원 | **가능(문서).** |
| 본 박스에서 win-x64 **restore/publish 실행·산출물 실행** | **미실시** |
| 로컬 SDK에 win-x64 pack 내장 | **없음** (실측) |
| 공개 NuGet에서 win-x64 Runtime/Host **10.0.14** nupkg GET | **사전확보됨(후보)**. HTTP 200·파일 존재 ≠ restore 그래프 확정 |
| `tools/nuget-offline`만으로 **완전 offline publish** | **미보증.** 143 nupkg는 **사전확보 superset**. 실제 배포 포함 집합은 **locked restore 후** 확정 |

---

## 3. NuGet 후보 · 라이선스 · 오프라인 피드

### 3.1 직접 참조 후보 (앱 작성 시 · 버전=실측 후보)

| 패키지 ID | 역할 | 라이선스(근거) | 비고 |
|-----------|------|----------------|------|
| *(공유 프레임워크)* `Microsoft.AspNetCore.App` | Kestrel, Razor/MVC, Cookie auth, Data Protection | Microsoft .NET 배포 조건 | self-contained 시 런타임 포함. 별도 PackageReference 일반적으로 불필요 |
| `Microsoft.Data.Sqlite` (**확보 관측 10.0.14**) | ADO.NET SQLite | **MIT** — 받은 nupkg nuspec `license type=expression` MIT; [licenses.nuget.org/MIT](https://licenses.nuget.org/MIT) | 전이(nuspec): `Microsoft.Data.Sqlite.Core` 10.0.14, `SQLitePCLRaw.bundle_e_sqlite3` **2.1.13**, `SQLitePCLRaw.core` 2.1.13. **최종 패치는 restore 시 재핀** |
| `Microsoft.Extensions.Hosting.WindowsServices` (**확보 관측 10.0.14**) | Windows 서비스 | **MIT** — 받은 nupkg nuspec | 전이: Hosting / Logging.EventLog / ServiceController 등 |
| *(BCL)* SHA-256 | 이력 체인 | .NET 라이선스 | 추가 NuGet 불필요 |

**의도적 비채택:** EF Core 기본, Identity UI 전체, Fluent UI Blazor/WC 런타임, Carbon/GOV.UK 런타임 패키지, git 감사로그 라이브러리, Docker/Java/Python, 제품 통째 반입. ([`UI_OSS.md`](../research/UI_OSS.md)와 정합)

비밀번호: Cookie 인증=공유 프레임워크; 해시는 BCL/`PasswordHasher` 계열 — BCrypt 패키지 불필요(최소 원칙).

### 3.2 SQLitePCLRaw · native 엔진 라이선스 구분 (받은 nupkg 근거)

| 구성 | 라이선스 | 근거 | 배포 고지 |
|------|----------|------|-----------|
| `Microsoft.Data.Sqlite` / `.Core` (관리 코드) | **MIT** | 받은 `Microsoft.Data.Sqlite.10.0.14.nupkg` nuspec | MIT notice 보존 |
| `SQLitePCLRaw.*` **관리/프로바이더** (`core`, `bundle_e_sqlite3`, `provider.*`, `lib.e_sqlite3` **패키지 메타**) | **Apache-2.0** (MIT 아님) | 받은 nupkg nuspec `license type=expression` Apache-2.0; [licenses.nuget.org/Apache-2.0](https://licenses.nuget.org/Apache-2.0). 전문 참고: [SQLitePCL.raw LICENSE.TXT](https://github.com/ericsink/SQLitePCL.raw/blob/master/LICENSE.TXT) | 재배포 시 LICENSE 사본 의무; Work에 NOTICE 있으면 NOTICE 의무 — **이 nupkg들 안에 별도 LICENSE/NOTICE 파일 엔트리는 없음(실측)** → 배포 시 SPDX 표현식+공식 LICENSE 텍스트를 `licenses\`에 동봉하는 계획 |
| **SQLite native 엔진** (`e_sqlite3.dll` 등, 예: `runtimes/win-x64/native/e_sqlite3.dll` in `SQLitePCLRaw.lib.e_sqlite3.2.1.13.nupkg`) | 엔진≠wrapper. 공개 SQLite 정책은 [sqlite.org/copyright](https://www.sqlite.org/copyright.html) **public domain** ([`UI_OSS.md`](../research/UI_OSS.md)도 동일). **본 nupkg 내 엔진 전용 LICENSE/NOTICE/readme 파일은 없음 → 패키지 동봉 고지 문구는 미확인.** | 바이너리 경로만 실측 | 배포 `THIRD_PARTY_NOTICES`에 (1) Apache-2.0 wrapper (2) SQLite 엔진 public-domain 안내+출처 URL을 **구분 기재**할 계획. 동봉 NOTICE 파일 유무는 locked restore 산출물에서 재확인 |

**win-x64 native:** 사전확보 `SQLitePCLRaw.lib.e_sqlite3.2.1.13.nupkg`에 `runtimes/win-x64/native/e_sqlite3.dll` 존재(실측). publish 시 RID 자산으로 포함되는지는 **restore/publish 미검증**.

### 3.3 공개 피드 접근 실측

| 대상 | 결과 |
|------|------|
| [api.nuget.org/v3/index.json](https://api.nuget.org/v3/index.json) | HTTP **200** |
| flatcontainer `Microsoft.Data.Sqlite` / `WindowsServices` / win-x64 Runtime·Host | HTTP **200**, 관측 시점에 `10.0.14` 존재 |
| 샘플 nupkg GET | HTTP **200** → 로컬 저장 완료 |

### 3.4 로컬 확보 위치 · 143개 = superset

- 디렉터리: `/workspace/audit-record-system/tools/nuget-offline/` (~**130M**, nupkg **143개** + `SHA256SUMS.txt`)
- **143개는 최소 직접의존성 목록이 아니다.** 후보 직접 패키지 + 전이 그래프 사전확보 + 구형 netstandard 전이 + 모바일(ios/android/tvos) lib 등 **superset**.
- **실제 배포에 들어가는 패키지 집합은 승인 후 locked restore(`project.assets.json` / packages.lock.json)로만 확정.**
- 핀 후보(현재 확보): SQLitePCLRaw **2.1.13** (nuspec 요구). `2.1.11`은 초기 수집분·비권장.
- 포함 예: win-x64 Runtime/Host/AspNetCore Runtime **10.0.14** nupkg(후보).

### 3.5 라이선스·SBOM 보존 절차 (계획)

1. 승인된 **RID+self-contained restore** 후 lockfile·assets로 포함 집합 고정.  
2. 포함 패키지마다 SPDX/nuspec + (있으면) nupkg 내 LICENSE/NOTICE → `감사기록\app\licenses\` 및 `THIRD_PARTY_NOTICES`에 **wrapper vs SQLite 엔진** 구분 기록.  
3. `win-x64` `e_sqlite3.dll` 및 기타 RID native 경로를 inventory.  
4. `SHA256SUMS`로 publish 산출·licenses 해시.  
5. SBOM/취약점: 온라인 개발 PC에서만 advisory(소스·설계 미업로드). 오프라인 서버에는 요약만.

---

## 4. 재현 가능한 offline restore → publish 단계 (초안 · **미실행**)

### 4.0 승인·완료 구분 (모순 정리)

| 단계 | 상태 |
|------|------|
| A. 공식 SDK tarball + checksum 검증·전용 경로 압축해제 | **완료** (허용된 다운로드 수행) |
| B. 공개 NuGet 후보·전이·win-x64 runtime/native 관련 nupkg **사전확보** | **완료** (허용된 다운로드 수행; **superset**) |
| C. 앱 프로젝트 생성 · PackageReference · **restore / lockfile / publish** | **미실시 · 추가 승인 필요** |
| D. 추가 NuGet/SDK 버전 변경·재다운로드 | **추가 승인 필요** (현재 확보분으로 우선 시도) |
| E. 온라인 nuget.org를 기본 restore 소스로 쓰는 경로 | **채택하지 않음**(우회 제안 아님). 기본=local feed only |

아래 명령은 **초안**이다. 실행하지 않았다.

### 4.1 외부접속 통제 vs 네트워크 차단 테스트 (별개)

| 수단 | 역할 | 한계 |
|------|------|------|
| `DOTNET_CLI_TELEMETRY_OPTOUT=1`, `DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1`, `DOTNET_NOLOGO=1`, workload advertising 억제, `NuGetAudit=false`(또는 동등) | CLI가 **자발적으로** 외부(텔레메트리·audit 등)를 호출하지 않도록 **완화** | **실제 네트워크 차단을 대체하지 않음** |
| `NuGet.Config` `<clear/>` + local feed만 | restore 시 패키지 **소스**를 로컬로 제한 | 다른 프로세스/환경변수 소스 잔존 주의 |
| OS/방화벽·nic down·빈 라우팅 등으로 **네트워크 차단 후** restore 재실행 | **실증**용 | 승인 후 별도 실시. CLI opt-out만으로 “오프라인 증명”이라 쓰지 않음 |

### 4.2 NuGet.Config (local feed 전용 · 초안)

```xml
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <clear />
    <add key="audit-local" value="/workspace/audit-record-system/tools/nuget-offline-feed" />
  </packageSources>
</configuration>
```

hierarchical feed가 필요하면 승인 후 `nuget init`/`nuget add`로 flat nupkg → 버전 트리 구성 ([Local feeds](https://learn.microsoft.com/en-us/nuget/hosting-packages/local-feeds)). 현재 폴더는 flat `*.nupkg` 모음.

프로젝트에 `RestorePackagesWithLockFile=true` 등 lock 사용(승인 후). 감사 조회 억제는 미확인 NuGet.Config 키가 아니라 아래 MSBuild `-p:NuGetAudit=false`로 지정한다. 누락 패키지를 숨기는 `--ignore-failed-sources`는 사용하지 않는다.

### 4.3 명령 초안 (RID · self-contained · lock · --no-restore)

```bash
export DOTNET_ROOT=/workspace/audit-record-system/tools/dotnet
export PATH="$DOTNET_ROOT:$PATH"
export DOTNET_CLI_HOME=/workspace/audit-record-system/tools/cli-home
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1
export DOTNET_NOLOGO=1
# workload 광고 매니페스트의 자동 갱신 억제는 승인 후 공식 SDK 동작을 확인한다.
# 무결성 검사 자체는 끄지 않는다.

# 빈 packages 캐시에서 local feed만으로 restore (경로 예)
PKG_CACHE=$(mktemp -d /tmp/audit-packages.XXXXXX)

# 1) RID + self-contained에 맞춘 restore → lockfile 생성
dotnet restore path/to/App.csproj \
  --runtime win-x64 \
  --force-evaluate \
  --packages "$PKG_CACHE" \
  --configfile path/to/NuGet.Config \
  -p:Configuration=Release \
  -p:SelfContained=true \
  -p:NuGetAudit=false \
  -p:RestorePackagesWithLockFile=true \
  -p:RestoreLockedMode=false
# → packages.lock.json 생성·로컬 보관(현재 git 작업은 금지)

# 2) 동일 빈(또는 새) 캐시 + RestoreLockedMode 로 재검증 (local feed만)
PKG_CACHE=$(mktemp -d /tmp/audit-packages.XXXXXX)
dotnet restore path/to/App.csproj \
  --runtime win-x64 \
  --packages "$PKG_CACHE" \
  --configfile path/to/NuGet.Config \
  -p:Configuration=Release \
  -p:SelfContained=true \
  -p:NuGetAudit=false \
  -p:RestorePackagesWithLockFile=true \
  -p:RestoreLockedMode=true

# 3) (별개) 네트워크 인터페이스 차단 상태에서 2) 재실행 → 실패 없이 끝나면 offline 실증

# 4) publish는 restore 성공 후에만, --no-restore
dotnet publish path/to/App.csproj \
  -c Release \
  -r win-x64 \
  --self-contained true \
  --no-restore \
  -o path/to/out/app \
  -p:NuGetAudit=false \
  -p:RestorePackagesWithLockFile=true
```

`RuntimeFrameworkVersion` / 공유 프레임워크 핀은 확보 SDK(10.0.403 / 10.0.14)와 SDK KnownFrameworkReference를 **조율할 계획**이나, **파일을 열어 확정한 실증은 아직 없음**. 온라인 restore를 기본으로 두는 우회는 **하지 않음**.

생산 Windows: publish 산출 `app\`만 복사 — .NET/DB/Java/Python/Docker 설치 없음.

---

## 5. 배포 패키지 구성 (계획)

```text
감사기록\
  app\          # self-contained 출력 + wwwroot(로컬 CSS/JS만) + licenses/ + THIRD_PARTY_NOTICES
  data\         # SQLite 한 파일 (예시 시드만)
  files\        # 결과원문/증빙/회신첨부 — DB에는 메타만; 웹은 내용 미오픈
  backup\       # 관리자 지정 내부 경로 복사본
  scripts\      # install/start/stop/uninstall (SCM) — 초안만
  config\       # appsettings.example.json (비밀·실경로 플레이스홀더)
  checksums\    # SHA256SUMS
  runbook.md
```

금지: 비밀 실값, 실제 문서, 실제 개인정보. 시드 = USER_SPEC §9 가짜 2건만.

---

## 6. TLS · Windows 서비스 · NTFS ACL (계획 · 값 미날조)

### 6.1 TLS

- 내부 CA 서버 인증서. **DNS/SAN·포트·드라이브 문자는 현장 확정 전 고정 금지.**
- 개인키: LocalMachine 또는 ACL 제한 PFX. 개발용 인증서 생산 금지.
- Kestrel: [Configure endpoints for Kestrel](https://learn.microsoft.com/en-us/aspnet/core/fundamentals/servers/kestrel/endpoints)
- 방화벽: 내부망 HTTPS만 최소. 아웃바운드 외부망 0.

### 6.2 Windows 서비스

- `Microsoft.Extensions.Hosting.WindowsServices` + content root = BaseDirectory.  
  [Host ASP.NET Core in a Windows Service](https://learn.microsoft.com/en-us/aspnet/core/host-and-deploy/windows-service)
- SCM 등록 스크립트는 **계획만**. 본 Task에서 설치·실행 안 함.
- 서비스 계정: 최소권한(이름 미정). LocalSystem 지양.

### 6.3 ACL · 경로 분리

| 경로 | 권한 방향 |
|------|-----------|
| `app\` | 서비스 Read+Execute; 관리자 Full |
| `data\` | 서비스 Modify; 일반/익명 **불가** |
| `files\` | 서비스 Modify; **웹은 바이트 스트리밍·미리보기 금지**(표시용 위치 문자열만; 로그·이력에 **전체 실경로·본문 복사 금지** — §7) |
| `backup\` | 관리자(+백업 담당); 서비스는 백업 시에만 쓰기 |

나스카 복호화·서버 미리보기 = 원문 금지 유지. Windows/LAN/NASCA = **미검증**.

---

## 7. SHA-256 append-only · 보존연한 · 검증 계획 (최소안)

### 7.1 한계

- 키 없는 해시 체인은 DB 통째 교체·재작성을 막지 못함. 탐지 목표=우연·단순 직접 수정.
- 추가 통제(로컬 HMAC 등)는 보안 설계와 조율 — 외부 서비스 금지.

### 7.2 보존연한 (원문 USER_SPEC §6 정확한 구분)

**만료 시 제거:** 결과 **본문**, 조치요구 **내용**, 회신 본문·확인 본문, 원문·증빙·회신첨부 **파일과 그 저장 위치**.  
**남김:** 건 **목록** 정보, 결과 **한 줄**, 조치요구의 제목·유형·대상·날짜·상태, 자료의 **이름과 삭제일**, **이력 전체**.  
보류 건은 만료해도 건너뜀. 기동 중일 때 만료분 처리 + 처리 시각 이력. 휴지통 없이 삭제.

### 7.3 이력·로그·삭제 실패 (계획 수준만)

- 이력 INSERT만; UI/API로 수정·삭제 없음.  
- 이력·앱 로그에 **민감 본문 전체** 및 **파일 전체 실경로**를 복사하지 않음(한 줄 적요·대상 id 수준).  
- 각 행: `prev_hash` + 정규화 필드 → SHA-256; 훼손 시 상단 배너.  
- 업무 변경+이력 append는 한 트랜잭션.  
- **파일 삭제 실패 시 성공으로 처리하지 않음**; 재시도·실패 이력(본문/경로 미포함) — **검증 항목은 계획**, 미구현.  
- 역할 매트릭스 = USER_SPEC §4 (서버 권한 검사).

---

## 8. 검증 매트릭스

| 항목 | Linux box | Windows/현장 |
|------|-----------|--------------|
| SDK checksum·전용 설치 | **완료** | — |
| 공개 NuGet 사전확보 (superset) | **완료(후보)** | — |
| locked restore (빈 캐시+local feed) | 승인 후 | — |
| 네트워크 차단 하 restore 재검증 | 승인 후(**opt-out과 별개**) | — |
| `publish -r win-x64 --self-contained --no-restore` | 승인 후 | 산출물 **실행** 미실시 |
| 역할·체인·연한·**삭제 실패≠성공** | 승인 후 테스트 계획 | — |
| Windows 서비스·재부팅·TLS·LAN·한글·NASCA | — | **미실시·미검증** |
| 백업/복구 드릴 | — | **미실시** |

**주장하지 않음:** Windows 설치 완료, 다른 PC 접속, 나스카 시험 수행.

---

## 9. 코드 착수 전 승인·확정 / 막히는 사항

### 9.1 이미 확보·확인

- t63u/t65u + USER_SPEC.  
- 전용 SDK 10.0.403 + 공식 SHA-512.  
- nuget-offline **143 nupkg superset** + win-x64 runtime/native 후보.  
- UI_OSS와 스택 방향 정합(Razor+자체 CSS + Microsoft.Data.Sqlite + .NET 10 LTS 방향).  
- 문서상 Linux→win-x64 self-contained 경로.

### 9.2 미해결 / 추가 승인

1. 구현·**restore/lock/publish** 착수 승인.  
2. 배포 Windows arch·호스트/포트/계정/드라이브.  
3. TLS CA·SAN·갱신.  
4. locked restore로 **배포 포함 패키지·패치 확정**(현재 숫자는 후보). KnownFrameworkReference/`RuntimeFrameworkVersion` 실증 조율.  
5. SHA-256 한계 고지 vs 추가 통제.  
6. t65u UI vs 4화면 IA — 설계 산출과 대조.  
7. Windows/LAN/NASCA **미검증**.

### 9.3 정책

- 설계/코드/사내자료 외부 업로드·원격 repo·클라우드 배포 금지.  
- 공개 URL GET으로 SDK/NuGet **사전확보는 완료**; 추가 변경·restore/publish는 승인 후.  
- 프록시 차단 시 우회 금지 — 이번 확보에서 차단 없음.

---

## 부록 A. 경로·용량 요약

| 경로 | 용도 | 대략 용량 |
|------|------|-----------|
| `/workspace/audit-record-system/design/BUILD_PLAN.md` | 본 문서 | (이 파일) |
| `/workspace/audit-record-system/USER_SPEC.md` | 원설계 | 10282 B |
| `/workspace/audit-record-system/research/UI_OSS.md` | UI·OSS 조사(정합 참고) | ~20 KB |
| `/workspace/audit-record-system/tools/dotnet/` | SDK 10.0.403 (확보 후보) | ~624 M |
| `/workspace/audit-record-system/tools/downloads/` | 공식 tarball+checksum | ~230 M |
| `/workspace/audit-record-system/tools/nuget-offline/` | 공개 nupkg **superset** | ~130 M |
| `/workspace/audit-record-system/tools/cli-home/` | CLI home | 최소 |

## 부록 B. 공식 근거 URL (Markdown 링크 · 원 URL 유지)

- [.NET application publishing](https://learn.microsoft.com/en-us/dotnet/core/deploying/)
- [.NET support policy](https://dotnet.microsoft.com/en-us/platform/support/policy)
- [.NET 8/9 end of support blog](https://devblogs.microsoft.com/dotnet/dotnet-8-9-end-of-support/)
- [Download .NET 10.0](https://dotnet.microsoft.com/en-us/download/dotnet/10.0)
- [Checksums 10.0.14-sha.txt](https://builds.dotnet.microsoft.com/dotnet/checksums/10.0.14-sha.txt)
- [SDK 10.0.403 linux-x64 tarball](https://builds.dotnet.microsoft.com/dotnet/Sdk/10.0.403/dotnet-sdk-10.0.403-linux-x64.tar.gz)
- [Install .NET on Linux (scripted/manual)](https://learn.microsoft.com/en-us/dotnet/core/install/linux-scripted-manual)
- [Local NuGet feeds](https://learn.microsoft.com/en-us/nuget/hosting-packages/local-feeds)
- [Host ASP.NET Core in a Windows Service](https://learn.microsoft.com/en-us/aspnet/core/host-and-deploy/windows-service)
- [Cookie authentication without Identity](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/cookie)
- [Configure endpoints for Kestrel](https://learn.microsoft.com/en-us/aspnet/core/fundamentals/servers/kestrel/endpoints)
- [Microsoft.Data.Sqlite custom SQLite versions](https://learn.microsoft.com/en-us/dotnet/standard/data/sqlite/custom-versions)
- [SQLite copyright (public domain)](https://www.sqlite.org/copyright.html)
- [SQLitePCL.raw LICENSE.TXT](https://github.com/ericsink/SQLitePCL.raw/blob/master/LICENSE.TXT)
- [NuGet Microsoft.Data.Sqlite](https://www.nuget.org/packages/microsoft.data.sqlite/)
- [NuGet API index](https://api.nuget.org/v3/index.json)
