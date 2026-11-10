# 감사기록 시스템 — UI·OSS 조사

- 작성: 2026-11-06 (KST)
- 확인일: 2026-11-06 23:56 KST (`date` 기준; 본 보완 반영)
- 범위: 공개 디자인 시스템·공식 문서·GitHub/NuGet LICENSE·메타데이터 읽기. **런타임 제품 통째 반입·클라우드·remote repo·회사자료 외부 업로드 없음.**
- 원설계 출처: 시스템 총괄 대화 사용자 메시지(설계서 전문) + `/workspace/audit-record-system/USER_SPEC.md` (t63u 보존본). 개선 위임은 직후 사용자 메시지(품질·UI·OSS 참고).

---

## 1. 결론 (바로 적용)

1. **UI 런타임 라이브러리 0에 가깝게**: ASP.NET Core **Razor Pages(또는 MVC)** + **자체 CSS** + **최소 native JS**. Fluent/Carbon/GOV.UK는 **패턴·원칙만** 채택. Fluent UI Blazor·`@fluentui/web-components`는 **미채택(런타임)** — self-host로 외부통신 없이 쓸 수는 있으나, 현재 스택 대비 프레임워크 복잡도·폰트/에셋 교체·접근성 재검증 비용이 커서 선택하지 않음(§4).
2. **데이터**: `Microsoft.Data.Sqlite`(+ 전이 `SQLitePCLRaw.bundle_e_sqlite3`)만 필수. EF Core·Dapper는 선택(없어도 됨). SQLite **엔진**은 public domain, **provider/wrapper**는 MIT/Apache-2.0 — 혼동 금지.
3. **타깃 런타임**: **.NET 10 LTS**(공식 표: End of support **November 14, 2028**). .NET 8 LTS는 동일 표에서 **November 10, 2026** 종료·Maintenance → 신규 채택 부적합(§5.1 원문 근거). 패키지 패치 버전은 배포 전 NuGet 재확인.
4. **화면 품질**: 4화면·조밀 표·할 일 리스트·상세 master-detail·시간순 이력에 집중. **차트·카드 나열·갤러리형 UI 금지**(원설계 불변).
5. **오프라인**: 외부 폰트·CDN·외부 스크립트·실행 중 외부 요청 0. Windows **맑은 고딕**만. CSS/패턴만 독립 구현하면 프론트 OSS 런타임 의존 ≈0.

---

## 2. 원설계와 불변 제약

| 항목 | 내용 | 출처 |
|------|------|------|
| 화면 | 할 일 / 건 목록(조밀 표) / 건 상세(+시간순 이력) / 관리 — **4개만** | USER_SPEC §7, t63u |
| 금지 UI | 차트·그래프·**카드 나열** | USER_SPEC §7 |
| 글꼴 | Windows 맑은 고딕. 인터넷 글꼴·외부 스크립트 금지 | USER_SPEC §1 |
| 네트워크 | 실행 중 **외부망 호출 0**. 내부망 브라우저 접속 | USER_SPEC §1·§8 |
| 스택 | C# ASP.NET Core + SQLite 파일 하나. Java/Python/Docker·제품 통째 반입 금지 | USER_SPEC §2·§8 |
| 이력 | append-only, 본문 전체 복사 금지, SHA-256 체인 | USER_SPEC §5 |
| 개선 범위 | t65u: 원설계보다 UI·품질·좋은 OSS **참고** 허용. 화면 수·보존·나스카·역할 정책은 유지 | 총괄 대화 t65u |

**원설계 확인 상태**: USER_SPEC.md와 시스템 총괄 대화의 설계서 전문이 일치. 메시지 식별자 t63u/t65u는 사용자 메시지 식별자이며 transcript position과 혼동하지 않음.

---

## 3. 실용적 UI 개선 5건 (4화면·검증 조건 포함)

> 원칙: 신기능 팽창 금지. 조밀성·스캔·키보드·오류·권한 가시성만 강화.

### U1. 할 일 = GOV.UK Task list 패턴 (카드 금지)

- **적용 화면**: 할 일
- **행동**: 역할별 대기 항목을 **한 줄 리스트**(제목·힌트·상태 태그). 각 항목은 **링크**(또는 동등 역할)로 해당 건 상세로 이동. 상태는 색만으로 전달하지 않고 **텍스트+태그**.
- **출처**: GOV.UK **Task list** (`govuk-task-list`, status/tag, hint, 행 링크).
- **접근성**: 상태 `id`를 링크 `aria-describedby`로 연결. 이동은 **Enter 또는 명시적 클릭만**. **포커스만으로 이동 금지**. 일반 링크에는 Space로 활성화를 억지 추가하지 않음(브라우저 기본 링크 동작 유지).
- **검증**: 카드 그리드 DOM 없음; 담당은 기한초과 조치요구만, 파트장/관리자는 확정대기·기한초과·삭제임박; 포커스만으로는 화면 전환 없음.

### U2. 건 목록 = Carbon Data table 밀도 + sticky header + 열너비

- **적용 화면**: 건 목록
- **행동**: 행 높이 **기본 32~40px**(맑은 고딕 가독·포커스 링·클릭/터치 영역). Carbon 문서의 xs **24px**은 **선택 고밀도**이며, 실측(대비·줄간격·포커스 가시) 통과 시에만. `thead` `position: sticky`. 제목 열 `min-width` 확보, 날짜·상태 열은 고정폭. 숫자·날짜는 우측/단일폭 정렬(GOV.UK numeric 정렬 참고).
- **출처**: Carbon **Data table** Formatting/Sizing; GOV.UK **Table** caption·`scope`·numeric·width override.
- **접근성**: `<caption>` 또는 `aria-labelledby`; `<th scope="col|row">`. **`aria-sort`는 정렬 가능한 columnheader(`th`)에** 둠(정렬 버튼이 아님). 버튼이 있으면 `th`가 상태를 갖고 버튼은 시각/클릭만.
- **검증**: 1280폭에서 핵심 열 가독; 헤더 스크롤 고정; 정렬 토글 3상태(unsorted/asc/desc)가 `th[aria-sort]`에 반영.

### U3. 필터 = 칩(chips) + URL 반영 (Linear/Notion·Carbon Filtering)

- **적용 화면**: 건 목록 (원설계 필터: 연도·구분·상태·기한초과·삭제임박 + 검색)
- **행동**: 활성 필터를 **칩으로 표시**, 칩×로 개별 해제, “필터 모두 지우기”. 필터·검색을 **쿼리스트링에 반영**(공유·뒤로가기). 즉시 갱신(서버 렌더 또는 동일 페이지 GET). AI 필터·고급 AND/OR UI는 **미채택**(원설계 범위·복잡도).
- **출처**: Carbon **Filtering**; Linear **Filters**(다중 필터·URL 반영, AI 필터 제외); Notion **Filter/Sort/Property visibility**(표 열 표시 개념만).
- **접근성**: 칩 해제 버튼명 예: “연도 2024 필터 제거”; 검색창 `label`.
- **검증**: 필터 조합 후 새로고침해도 유지; 외부 egress 0(자체 서버 GET만).

### U4. 건 상세 = master-detail + 시간순 이력 + 위험 액션 분리

- **적용 화면**: 건 상세
- **행동**: 넓은 화면에서 좌(또는 상) 다섯 묶음 요약·우(또는 하) **시간순 이력** 패널(Fluent layout의 show/hide·reposition **개념**을 자체 CSS로). 이력은 읽기 전용 한 줄 타임라인. **확정/확정취소/연한전삭제/보류**는 일반 “저장”과 시각·위치 분리. 권한 없는 버튼은 **미표시**(원설계).
- **출처**: Fluent 2 **Layout**; Notion **Side peek** 개념만; GOV.UK 내용 계층.
- **접근성**: 이력 영역 `aria-label="시간순 이력"`; 위험 버튼 `aria-describedby`로 사유 필수 안내.
- **검증**: 등록→결과→조치→회신→삭제 순 표시; 이력 수정 UI 없음; 나스카 안내에 위치만 표시.

### U5. 오류·훼손·빈상태 = GOV.UK Error summary + 상단 배너

- **적용 화면**: 전역(특히 관리·확정·삭제·로그인) + 이력 훼손
- **행동**: 검증 실패 시 페이지 상단 **Error summary**(문제 목록 → 필드 앵커) + 필드옆 메시지 동일 문구. 이력 체인 훼손 시 상단 **영구 경고 배너**(원설계). 빈 목록은 한 줄 설명+다음 행동 링크(카드 없음).
- **출처**: GOV.UK **Error summary** (`role="alert"`, 포커스 이동, 필드 링크).
- **접근성**: 요약으로 포커스 이동; `document.title`에 Error 접두(가능 시).
- **검증**: 사유 없는 확정취소 제출 시 요약+필드 오류; 훼손 시 모든 화면 상단 표시.

**키보드(공통)**

- 표/할 일에서 행·항목이 **링크**이면 **Enter(및 클릭)** 로만 상세 이동. **포커스=이동 금지**.
- 목록 검색 포커스용 `/` 단축키를 둘 경우 **WCAG 2.1.4(단일 문자 단축키)** 준수: (a) 끄기, (b) 재매핑, 또는 (c) **포커스가 해당 검색 컨트롤에 있을 때만** 동작. **편집 가능한 입력·textarea·contenteditable 입력 중에는 `/`를 가로채지 않음.**
- `Esc`로 검색/임시 필터 UI 해제. 관리 화면은 Tab 순서 보장으로도 충분.
- Linear 단축키는 참고만, 그대로 복제 불필요.

---

## 4. 공식 UI/OSS 채택·미채택

**구분**: self-host 가능 ≠ 외부통신 필수. Fluent/Carbon/GOV.UK 라이브러리도 정적 자산으로 내부망에 둘 수 있다. 아래 **미채택** 이유는 “반드시 인터넷이 필요해서”가 아니라 **Razor+자체 CSS 대비 복잡도·폰트/에셋 교체(맑은 고딕 강제)·접근성·라이선스 고지·제품 통째 반입에 가까워짐**이다.

| 대상 | 구분 | 결정 | 이유 | 라이선스 |
|------|------|------|------|----------|
| Fluent 2 디자인 원칙 | **패턴** | **채택** | 간격·의미색+텍스트·레이아웃 | 가이드(코드 아님) |
| `@fluentui/web-components` | 런타임 | **미채택** | self-host 가능하나 peer/번들·Blazor/WC 도입 비용·에셋 라이선스 재검증 | MIT ([package.json](https://github.com/microsoft/fluentui/blob/master/packages/web-components/package.json) `license`) — Fluent **assets** 별도 문구는 [루트 LICENSE](https://github.com/microsoft/fluentui/blob/master/LICENSE) 주석만 확인, **assets URL 본문은 미열람** |
| `microsoft/fluentui-blazor` | 런타임 | **미채택** | Blazor 전환·의존 증가 | MIT 전문 확인: [LICENSE.TXT](https://github.com/microsoft/fluentui-blazor/blob/main/LICENSE.TXT) |
| Carbon Data table / Filtering | **패턴** | **채택(독립 CSS)** | 조밀 표·필터 | Apache-2.0 전문: [LICENSE](https://github.com/carbon-design-system/carbon/blob/main/LICENSE). **상당 코드 복사·재배포 시** LICENSE(+NOTICE 있으면 NOTICE) **포함 의무**. 개념만 독립 구현 시 해당 의무 없음 |
| Carbon 런타임 패키지 | 런타임 | **미채택** | React/WC 스택·복잡도 | — |
| GOV.UK Table / Task list / Error summary | **패턴** | **채택(독립 마크업·CSS)** | 할 일·표·오류 | MIT 전문: [LICENSE.txt](https://github.com/alphagov/govuk-frontend/blob/main/LICENSE.txt). **상당 코드 복사·재배포 시** copyright/permission notice **포함 의무**. 패턴만 독립 구현 시 해당 의무 없음 |
| GOV.UK Frontend 통째 | 런타임 | **미채택** | 기본 폰트/에셋 관행을 맑은 고딕·무외부요청에 맞게 재작업하는 비용 | self-host 자체는 가능 |
| Linear Filters 등 | **패턴** | **일부 채택** | 칩·URL. Board/AI 제외 | 상용 문서 |
| Notion Table/Filter/Side peek | **패턴** | **일부 채택** | 열·필터·peek. **Gallery/Chart 금지** | 상용 문서 |
| Lucide SVG 소수 인라인 | 선택 | **조건부** | CDN 금지, 인라인만 | ISC(+Feather MIT) 전문: [LICENSE](https://github.com/lucide-icons/lucide/blob/main/LICENSE) — 재배포 시 고지 **의무** |
| Fluent UI 아이콘 패키지 | 런타임 | **미채택** | assets 별도 조건; 본문 URL 미열람 | 루트 LICENSE 주석만 |

---

## 5. .NET / SQLite 최소 패키지·라이선스

### 5.1 런타임 권장 (.NET 지원표 원문)

출처: [.NET and .NET Core Support Policy](https://dotnet.microsoft.com/en-us/platform/support/policy/dotnet-core) — 페이지 표기 **Last updated: October 25, 2026**. 조회: 2026-11-06 KST.

Supported versions 표에서 관측한 수치(임의 변경 없음):

| Version | Latest patch | Patch release date | Type | Phase | End of support |
|---------|--------------|--------------------|------|-------|----------------|
| .NET 10 | 10.0.14 | October 25, 2026 | LTS | Active | November 14, 2028 |
| .NET 9 | 9.0.22 | October 25, 2026 | STS | Maintenance | November 10, 2026 |
| .NET 8 | 8.0.33 | October 25, 2026 | LTS | Maintenance | November 10, 2026 |

- **권장**: .NET 10 LTS.
- .NET 8/9: 확인일 기준 EOS가 임박 → 신규 비권장.
- .NET 11: Go-live RC만 → 채택·단정 금지.
- ASP.NET Core·EF Core는 정책상 .NET과 동일 수명주기(페이지 본문).
- **배포 전** 동일 URL에서 패치·EOS 재확인.

### 5.2 Microsoft.Data.Sqlite 10.0.14 재확인 notes

조회: 2026-11-06 23:55–23:56 KST.

| 항목 | 관측 |
|------|------|
| 패키지 페이지 | https://www.nuget.org/packages/microsoft.data.sqlite/10.0.14 |
| Catalog JSON | https://api.nuget.org/v3/catalog0/data/2026.10.25.17.33.06/microsoft.data.sqlite.10.0.14.json |
| `version` | `10.0.14` |
| `published` | `2026-10-25T17:07:18.587Z` → KST **2026-10-26 02:07** (확인일 2026-11-06 **이전** → 미래일 아님, 채택 후보로 유지) |
| `licenseExpression` | `MIT` |
| `licenseUrl` | https://licenses.nuget.org/MIT |
| 의존(.NETStandard2.0) | `Microsoft.Data.Sqlite.Core` `[10.0.14, )`; `SQLitePCLRaw.bundle_e_sqlite3` `[2.1.13, )`; `SQLitePCLRaw.core` `[2.1.13, )` |
| 저장소 LICENSE 전문 | [dotnet/efcore LICENSE.txt @ main](https://github.com/dotnet/efcore/blob/main/LICENSE.txt) — MIT 전문 확인(“The above copyright notice and this permission notice shall be included…”) |
| 지원 정책과의 정합 | 정책 표 Latest patch **10.0.14** / Patch release date **October 25, 2026**과 일치 |

**표시**: 위는 **도구가 반환한 관측 사실**. 오프라인 캐시·미러·사내 feed가 다를 수 있으므로 **배포 전 NuGet/캐시에서 재확인**. nupkg 내부 LICENSE 파일 바이트는 이번 턴에서 풀지 않음(표현식·repo LICENSE·licenses.nuget.org로 확인).

### 5.3 최소 패키지 표

| 패키지 | 역할 | 채택 | 버전 | 라이선스 | 직접/전이 | 배포·고지 |
|--------|------|------|------|----------|-----------|-----------|
| ASP.NET Core | 호스트·Razor | **필수** | .NET 10 | Microsoft 배포 조건 | 런타임 | self-contained 시 런타임 포함 |
| Microsoft.Data.Sqlite | ADO.NET | **필수 권장** | **10.0.14**(§5.2) | MIT | 직접 | **상당 부분 재배포 시** MIT notice **의무** |
| → Microsoft.Data.Sqlite.Core | 코어 | 전이 | 10.0.14 | MIT(동일 계열) | 전이 | 상동 |
| → SQLitePCLRaw.bundle_e_sqlite3 | native 번들 | 전이 | ≥2.1.13 | Apache-2.0 ([nuspec 2.1.13](https://api.nuget.org/v3-flatcontainer/sqlitepclraw.bundle_e_sqlite3/2.1.13/sqlitepclraw.bundle_e_sqlite3.nuspec) `license type=expression`; 전문 [LICENSE.TXT](https://github.com/ericsink/SQLitePCL.raw/blob/master/LICENSE.TXT)) | 전이 | **재배포 시** Apache §4: LICENSE 사본 **의무**; Work에 NOTICE가 있으면 NOTICE 내용 **의무** — 실제 nupkg NOTICE 유무는 **미확인(배포 전 확인)** |
| → SQLitePCLRaw.core 등 | P/Invoke | 전이 | 2.1.13대 | Apache-2.0 | 전이 | 상동 |
| SQLite 엔진 | DB | 번들 내 | native | **Public domain** ([copyright](https://www.sqlite.org/copyright.html)) | 엔진≠wrapper | wrapper와 별도 기록 |
| EF Core Sqlite | ORM | **선택·기본 비권장** | 미핀 | MIT(efcore) | 다수 전이 | 최소의존과 충돌 → 보류 |
| Dapper | 마이크로 ORM | **선택** | 미핀 | Apache-2.0 ([License.txt](https://github.com/DapperLib/Dapper/blob/main/License.txt)) | 직접 | 채택·재배포 시 Apache 의무 |

**Baseline**: `Microsoft.Data.Sqlite` + 손 SQL. Dapper는 필요 시. EF Core는 명확한 이득 있을 때만.

**SBOM**: 전체 전이 inventory·NOTICE 파일 존재 여부는 **미확인 유지**. `dotnet restore`/publish 후 검증.

### 5.4 라이선스 의무 vs 패턴 독립 구현

| 행위 | MIT | Apache-2.0 |
|------|-----|------------|
| 공식 문서의 **개념·패턴만** 보고 CSS/HTML을 **처음부터 독립 작성** | 해당 OSS 코드 재배포 아님 → 그 패키지 notice 의무 없음 | 동일 |
| 상당 부분 소스/바이너리 **복사·수정·재배포** | copyright·permission notice **포함 의무**(“shall be included”) | LICENSE 제공 **의무**; 원본에 NOTICE 있으면 NOTICE **의무** |
| “동봉 권장” 표현 | **부정확** — 재배포 시 **의무** | NOTICE는 **있을 때** 의무 |

---

## 6. Offline 검증 체크리스트

| # | 조건 | 구분 | 통과 기준 |
|---|------|------|-----------|
| 1 | 외부 호스트 요청 | **외부 egress** | 0건 |
| 2 | 로그인·CRUD·백업 | **자체 서버** | 내부망 origin만(외부 요청 아님) |
| 3 | 외부 스크립트·스타일·폰트 | 정적 | 0건. `Malgun Gothic`만 |
| 4 | self-contained 폴더 | 패키징 | 대상 PC 기동(빌드 계획에서) |
| 5 | data/files | 데이터 | 로컬 경로만 |

---

## 7. 남은 검증 (미확인)

- [ ] 실제 `dotnet restore`/publish 후 **전이 의존·NOTICE 전체** inventory
- [ ] `e_sqlite3` Windows x64 native가 publish 출력에 포함되는지
- [ ] Fluent assets 라이선스 URL 본문(아이콘 패키지 미사용 시 생략 가능)
- [ ] nupkg 내부 LICENSE 바이트 vs licenseExpression 일치
- [ ] NVDA/내레이터로 Task list·표·Error summary 1회
- [ ] 오프라인 NuGet 캐시와 공식 10.0.14 정합

---

## 8. Source URL · 확인일

| 링크 | 내용 | 확인 | 비고 |
|------|------|------|------|
| [Support policy](https://dotnet.microsoft.com/en-us/platform/support/policy/dotnet-core) | .NET 8/9/10 표 | 2026-11-06 KST | 원문 수치 §5.1 |
| [Fluent 2](https://fluent2.microsoft.design/) | 홈 | 2026-11-06 KST | |
| [Fluent color](https://fluent2.microsoft.design/color) | 의미색 | 2026-11-06 KST | |
| [Fluent layout](https://fluent2.microsoft.design/layout) | 간격·레이아웃 | 2026-11-06 KST | |
| [Carbon data-table](https://carbondesignsystem.com/components/data-table/usage/) | 표 | 2026-11-06 KST | |
| [Carbon filtering](https://carbondesignsystem.com/patterns/filtering/) | 필터 | 2026-11-06 KST | |
| [GOV.UK table](https://design-system.service.gov.uk/components/table/) | Table | 2026-11-06 KST | |
| [GOV.UK task-list](https://design-system.service.gov.uk/components/task-list/) | Task list | 2026-11-06 KST | |
| [GOV.UK error-summary](https://design-system.service.gov.uk/components/error-summary/) | Error summary | 2026-11-06 KST | |
| [Linear filters](https://linear.app/docs/filters) | Filters | 2026-11-06 KST | |
| [Notion views/filters](https://www.notion.com/help/views-filters-and-sorts) | Views 등 | 2026-11-06 KST | |
| [SQLite copyright](https://www.sqlite.org/copyright.html) | public domain | 2026-11-06 KST | |
| [NuGet Microsoft.Data.Sqlite 10.0.14](https://www.nuget.org/packages/microsoft.data.sqlite/10.0.14) | 패키지 UI | 2026-11-06 KST | |
| [Catalog 10.0.14 JSON](https://api.nuget.org/v3/catalog0/data/2026.10.25.17.33.06/microsoft.data.sqlite.10.0.14.json) | published·license·deps | 2026-11-06 KST | §5.2 |
| [licenses.nuget.org/MIT](https://licenses.nuget.org/MIT) | licenseUrl | 2026-11-06 KST | 표현식 연결 |
| [efcore LICENSE.txt @ main](https://github.com/dotnet/efcore/blob/main/LICENSE.txt) | MIT 전문 | 2026-11-06 KST | 읽기도구 확인 |
| [SQLitePCL.raw LICENSE.TXT @ master](https://github.com/ericsink/SQLitePCL.raw/blob/master/LICENSE.TXT) | Apache-2.0 전문 | 2026-11-06 KST | |
| [bundle_e_sqlite3 2.1.13 nuspec](https://api.nuget.org/v3-flatcontainer/sqlitepclraw.bundle_e_sqlite3/2.1.13/sqlitepclraw.bundle_e_sqlite3.nuspec) | Apache-2.0 expression | 2026-11-06 KST | |
| [fluentui LICENSE @ master](https://github.com/microsoft/fluentui/blob/master/LICENSE) | MIT + assets 주석 | 2026-11-06 KST | assets 본문 미열람 |
| [web-components package.json @ master](https://github.com/microsoft/fluentui/blob/master/packages/web-components/package.json) | `"license":"MIT"`, version 필드 관측 | 2026-11-06 KST | |
| [fluentui-blazor LICENSE.TXT @ main](https://github.com/microsoft/fluentui-blazor/blob/main/LICENSE.TXT) | MIT 전문 | 2026-11-06 KST | |
| [carbon LICENSE @ main](https://github.com/carbon-design-system/carbon/blob/main/LICENSE) | Apache-2.0 전문 | 2026-11-06 KST | |
| [govuk-frontend LICENSE.txt @ main](https://github.com/alphagov/govuk-frontend/blob/main/LICENSE.txt) | MIT 전문 | 2026-11-06 KST | |
| [lucide LICENSE @ main](https://github.com/lucide-icons/lucide/blob/main/LICENSE) | ISC+MIT | 2026-11-06 KST | |
| [Dapper License.txt @ main](https://github.com/DapperLib/Dapper/blob/main/License.txt) | Apache-2.0 | 2026-11-06 KST | |
| `/workspace/audit-record-system/USER_SPEC.md` | 원설계 | 2026-11-06 KST | |

---

## 9. 한 줄 설계 지시 (개발·설계 봇용)

> **Razor + 자체 CSS(Fluent/Carbon/GOV.UK 패턴 독립 구현) + Microsoft.Data.Sqlite on .NET 10. UI NuGet/CDN 0. 기본 행고 32–40px sticky 표·필터 칩·Task-list 할 일·상세 이력·Error summary. 차트·카드·외부 egress 없음. Enter/클릭만 이동, aria-sort는 th.**
