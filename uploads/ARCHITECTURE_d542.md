# 내부 감사기록 시스템 아키텍처 검토본

작성: 2026-11-07 KST. **설계만 작성. 구현·설치·서비스 실행·Windows 실기시험 없음. 정책 승인서가 아니다.**
소유 범위: 이 파일과 [UI_SPEC.md](UI_SPEC.md)만. 다른 담당 문서·USER_SPEC.md는 변경하지 않는다.

## 0. 근거의 확실성과 작업 단계

- Ground: 신규 greenfield이므로 기존 코드 역공학은 생략. 사용자 원문·로컬 설계·업무/보안/UI 검토를 대조했다.
- Sketch: 아래 두 구조를 같은 작성자가 비교했다. 다른 모델·독립 심사자 실행 또는 arena 병렬 실행을 주장하지 않는다.
- Agree: 검토본으로 제출. U1/U2 운영정책과 원문 누락 구간 대조 전 확정 설계로 취급하지 않는다.
- Implement: **금지/미실행**. 의사코드만 기재한다.
- Scrap: 구현 단계가 없어 미해당. 원문과 다르면 이 검토본을 수정하며 원정책을 맞춰 바꾸지 않는다.

### 0.1 직접 확보한 근거

S = `/workspace/audit-record-system/USER_SPEC.md` 전체 229행. 읽은 당시 SHA256:
`d6ecb879fbe14f5e76622aac23d1a3d76d3e82ad2ed90d83a1d82ee10eccdd4c`.

T = 시스템 총괄 봇 `770697aa-bdcc-4954-9a12-9c7e3e6160b8` 대화.
SearchTranscripts(감사기록/NASCA) → ReadTranscript around=254,258 → raw before=466 limit=1로 직접 확인.
- t63u: 2026-11-06 23:47 KST, said-position 254 / raw-position 465. 설계서 앞뒤를 직접 읽었으나 **도구가 사용자 text 자체 중간을 `...`로 생략**한다. 역할표 중간부터 §5 조치요구 앞부분까지는 전문 확보 실패다. limit=1 재조회도 동일했다.
- t65u: 2026-11-06 23:49 KST, 원문 전체 확인. “이설계보다 뛰어나게 설계”, “특히 ui에 신경” 및 설계·구현·완성 위임. 이번 작업에는 별도 설계전용 금지가 우선한다.
- 근거 확보: 2026-11-06 23:51~23:56 KST. **T에 누락구간이 있어 S 해당 부분을 원문 대조 완료라고 할 수 없다.** 해당 정책은 S 기반 잠정 근거다. 총괄의 일치 보고는 독립 증거를 대체하지 않는다.

추가 입력(읽기만): `design/WORKFLOW_REVIEW.md`(W), `security/REVIEW.md`(R), `research/UI_OSS.md`(O). W도 T63 누락을 확인했다. O §3 U3의 raw 검색어 URL 유지 권고는 누출 위험으로 미채택한다. R/O의 “원문 전체 일치” 표기는 이번 직접 관측과 다르므로 그대로 승격하지 않는다. 기술추천은 원문 정책과 구분한다. SDK·publish는 타 담당 범위이며 이 문서는 실행 여부를 판단하지 않는다.

### 0.2 원문 제약 추적표

| ID | 근거 위치와 짧은 인용 | 설계 반영 | 확인 상태 |
|---|---|---|---|
| C01 | S 지시/§1, T 직접: “인터넷이 없는 Windows 컴퓨터 한 대” | §3 배포·외부요청0 | 직접 대조 |
| C02 | S §2/T 직접: “C#”, “SQLite 파일 하나”, 원문은 files | §3/§5 로컬 DB·파일 분리 | 직접 대조 |
| C03 | S §3/T 직접: “문서 내용을 읽지 않는다” | §8 NASCA 경계, UI 위치만 | 직접 대조 |
| C04 | S §4 행58~73: 담당·파트장·관리자, “모든 건을 다 본다” | §6 서버 권한 | **S 명시, T 일부 미확보** |
| C05 | S §4 행77~82: 확정일 기산/사유/보류/종결 | §7/§9, U1 | **S 명시, T 미확보** |
| C06 | S §5 행88~124: 건/결과/조치/이행 | §5/§7, UI 다섯 묶음 | 앞부분 S만, 후반 T 직접 |
| C07 | S §5 행126~143/T 직접: 메타·append-only·앞줄 SHA256 | §5/§10, 본문/경로 비복제 | 직접 대조 |
| C08 | S §6/T 직접: “파일과 그 저장 위치” 삭제, 목록·이력 잔존 | §9 삭제 프로토콜 | 직접 대조 |
| C09 | S §7/T 직접: “화면은 네 개만”, 조밀표·시간순이력 | UI_SPEC §2~5 | 직접 대조 |
| C10 | S §7/T 직접: data+files 내부 디스크 복사 | §11, U2 | 직접 대조 |
| C11 | S §8/T 직접: 피감부서/전자결재/AI/본문검색 등 제외 | §3/§8/§13 | 직접 대조 |
| C12 | S §9/T 직접: 가짜 둘, 2024 업무진단/2022 준법감사 | UI_SPEC §7 공통 fixture | 직접 대조, 이름만 새 가상명 |
| C13 | S §10/T 직접: 완료기준 1~7 | §12 E1~E7 | 직접 대조 |
| C14 | t65u 전체: UI·품질 개선·좋은 OSS 참고 | §2/§3, UI_SPEC | 직접 확인, 정책확장 근거 아님 |

## 1. 문제와 호출자 사용흐름 (타입보다 먼저)

종이 감사기록을 감사파트 내부에서 찾고 이어 기록하되, 결과 확정·회신 수신·이행 완료·종결·자료삭제를 혼동하지 않아야 한다. 보존기한이 지난 내용은 이력·백업·로그에서 되살아나지 않도록 경계를 드러내야 한다. 새 계획관리·결재 시스템을 만드는 작업이 아니다.

1. 담당은 할 일의 기한초과 조치 링크를 Enter로 열고, 건 상세에서 회신을 기록한다. 저장은 회신 수신이지 이행 완료가 아니다. 서버는 동일 시각·행위자의 이행과 이력을 한 트랜잭션으로 남기고 최신 Version을 돌려준다.
2. 파트장은 목록에서 초안을 열어 연한·결과를 확인하고 결과 확정을 누른다. 연한 없음은 저장 불변 422. 이후 이행확인은 별도 기록, 종결은 별도 명령이다. 담당에게는 이 버튼이 없고 직접 요청도 403이다.
3. 관리자는 삭제임박 건에서 미완료 조치와 보류 사유를 보고 관리 화면에서 보류한다. 보류 응답 성공 후 worker는 그 건을 삭제하지 않는다. 이미 수행한 삭제는 보류로 복구되지 않는다.

호출 의사코드(실행 가능한 코드 아님):
```text
reply = Records.Execute(actor, RecordReply(caseId, actionId, expectedVersion, input), requestId)
confirmed = Records.Execute(actor, ConfirmResult(caseId, expectedVersion), requestId)
held = Records.Execute(admin, SetHold(caseId, expectedVersion, neutralReason), requestId)
view = Records.Read(actor, CaseQuery(caseId))
```
클라이언트가 validate/save/audit/delete를 따로 호출하지 않는다. 응답은 Snapshot+Version 또는 FailureCode이며 DB/HTTP 타입을 도메인 경계 밖으로 노출하지 않는다.

## 2. 구조 대안과 채택 근거

같은 작성자가 두 구조를 비교한 설계 스케치다. 점수는 측정치가 아니라 5=적합/1=부적합의 설계 판단이다.

| 평가기준 | A 직접 트랜잭션형 현재상태 + append-only 최소이력 + 영속 삭제작업 | B 이벤트소싱 + projection + 파일삭제 saga |
|---|---|---|
| 원문추적성 | 5: 원문 테이블/삭제열과 직결 | 2: 이벤트의 본문 잔존 또는 재현손실 문제 |
| 단일서버 단순성·좁고 깊은 경계 | 5: Execute/Read로 무결성 집중 | 2: event store/replay/projection version 조정 |
| 권한·경합 무결성 | 4: 한 tx·건 gate·version | 4: aggregate version은 좋으나 projection 지연 추가 |
| 보존삭제·복구 안전성 | 4: 삭제job이 부분실패 흡수 | 2: immutable event에 본문 넣으면 연한삭제와 충돌 |
| UI 접근성과 오류회복 | 4: 동기 snapshot·409 명확 | 3: 처리중/투영지연 UI 복잡도 노출 |

**Synthesis decision:** A 채택 권고. B에서 재시도 가능한 영속 작업 개념만 가져오고, 이벤트소싱·CQRS·분산버스·ORM·범용 workflow 엔진은 배제한다. 이벤트를 영구 본문 저장소로 삼지 않는다. 두 안 모두 해시체인만으로 강력한 관리자 변조를 막을 수 없다.

- Shape: Razor 화면/HTTP 어댑터 → Records(정책·권한·tx·이력 단일 소유) → SQLite 또는 FileStore. 읽기·쓰기 정책의 단일 소유자다. Retention worker와 Backup은 Records의 gate 규약을 사용하며 SQL로 우회하지 않는다.
- Interface depth: 호출자는 requestId/expectedVersion만 알고 보류 재검사·원자적 이력·경로 검증·job을 몰라도 된다. FileStore는 storage key와 파일 존재/삭제/바이트복사만 안다. 호출체인은 보통 3경계 이내다.
- Red flags 심사: load/validate/save/audit 단계별 공개 모듈 없음(temporal decomposition 제거); UI와 worker 이중 정책 없음; 한 명령 두 저장경로 없음; SQL·ASP.NET DTO는 내부; 권한표/FailureCode 정의에서 UI capability·문구를 파생한다(수동 동기화 목록은 계약시험 대상).
- Tradeoffs accepted: 단일 프로세스 gate와 단일 SQLite writer로 높은 처리량보다 예측가능성; 백업 중 쓰기 지연으로 DB/파일 정합성; 최소이력으로 과거 본문 복원 포기; 파일/DB 비원자성을 영속 작업으로 명시.
- Next implementation step: 정책 U1/U2와 원문 대조가 끝난 뒤, 별도 승인된 작업에서 명령·권한·전이 계약시험부터 작성한다. **이번에는 구현하지 않는다.**

## 3. 배포·네트워크·인증 경계

총괄 채택방향(기술권고): .NET 10 LTS / C# / ASP.NET Core Razor Pages / 자체 CSS / 최소 native JS / Microsoft.Data.Sqlite. 구체 패치·전이 라이선스·Windows publish는 BUILD_PLAN 담당 확인 대상이다. Fluent/Carbon/GOV.UK는 패턴만 독립 구현, UI 제품/런타임 통째 반입 없음. Java/Python/Docker 제품·감사로그 라이브러리·AI·SSO·포털 연동 없음.

- Windows 전용 서버 1대, 개인업무 겸용 금지. 단일 앱 프로세스+내장 worker. 이중 인스턴스는 시작 잠금으로 거절한다. 파일 gate는 다중 서버용 잠금이 아니다.
- 내부망 HTTPS 한 origin만. 내부 CA를 사전 신뢰시키고 인증서 이름 검증; HTTP 운영은 명시 위험수용 전 보류. 방화벽은 승인된 내부 대역만 허용, 외부 출구 차단. CDN/외부폰트/telemetry/외부업데이트/원격 로그/외부 인증 검증요청 0. 인증서 검증도 내부/오프라인 환경에 맞춰 외부조회가 없도록 현장 확인한다.
- 로컬 계정만; PasswordHasher 사용, 평문/단순 SHA 비밀번호 금지. 권고 기본: 5회 실패 시 15분 잠금, 세션 유휴 30분/절대 8시간. 임시비밀은 재사용 금지·최초 변경. 예시 비밀번호는 문서에 적지 않는다.
- User.Enabled와 SecurityStamp를 모든 인증 요청마다 검증(조회 포함). 비밀번호 변경/초기화/계정끄기 시 기존 세션 전부 폐기; 권한변경도 동일. 본인 비밀번호 변경은 세 역할 가능(현재 비밀 확인).
- 쿠키 HttpOnly/Secure/SameSite=Strict, 상태변경 POST+CSRF 토큰, GET은 읽기만. 검색 POST도 CSRF 적용. 세션 ID 재발급, 로그인 성공/실패 원문입력 로그 금지. 401이면 재로그인, 자격 바뀐 요청은 재판정한다.
- DataProtection 키는 app 아래 별도 ACL 하위폴더+Windows DPAPI 권고. SQLite·일반 data/files 백업과 분리하고 클라우드 키링 금지. 운영 data/files 볼륨도 Windows at-rest 암호화 권고(건별 파기 증명은 아님). 초기 관리자 로컬 부트스트랩은 고정비번 없이 1회 수행; 마지막 활성 관리자 끄기/권한제거 금지.
- `data`와 `backup`: 서비스 계정·인가된 시스템관리자만. `files`: 서비스만 쓰기/삭제, 감사파트 읽기 전용 내부 공유만 필요 최소 허용. DB 공유 금지. NASCA는 별도 접근권한을 최종 판단하며 앱 역할만으로 원문 열림을 보증하지 않는다.

## 4. 로컬 저장·SQLite 운영

원문 디렉터리 유지: `감사기록/app`, `data/audit.db`, `files`, `backup`. 첨부 BLOB을 DB에 넣지 않는다. files 아래 미완료 바이트복사용 staging을 두되 원문과 동일 ACL/삭제대상 관리에 넣는다. 소스/문서 작성 위치는 이번 작업에서 /workspace뿐이다.

- SQLite 파일 하나는 **논리 DB 하나**라는 뜻이다. WAL 모드는 실행 중 `audit.db-wal`, `audit.db-shm` 임시 보조파일을 만들 수 있다. 이를 숨기거나 “물리 파일 항상 하나”라고 약속하지 않는다. 문자 그대로 물리 한 파일 요구라면 WAL 대신 rollback journal도 임시파일이 필요하다는 운영 차이를 총괄에 알린다.
- 권고: 로컬 NTFS, WAL, foreign_keys=ON(모든 연결), synchronous=FULL, busy_timeout=5000ms, secure_delete=ON, temp_store=MEMORY. 공유/NAS 위 SQLite 금지. 짧은 BEGIN IMMEDIATE 쓰기 tx; 파일 I/O 동안 DB tx 유지하지 않는다. 전체 gate만 유지한다.
- 쓰기는 짧게, BUSY는 제한 대기 후 503 BUSY_RETRY. 읽기는 페이지 단위로 종료하고 백업/체크포인트를 막는 장기 reader를 만들지 않는다. 메모리·디스크 부족이면 저장 완료로 답하지 않는다.
- DB 시작 시 schema version·FK·기본 무결성·이력체인 검증. 실패하면 조회 전용/정비 안내, 자동 DB 교체·이력 재작성 없음. 검증중 쓰기·자동삭제·백업완료 표시 금지.
- 정비창: 연결 정리/배타 쓰기 중지 → 필요 시 VACUUM → wal_checkpoint(TRUNCATE) 결과 busy=0 확인. VACUUM 여유는 보수적으로 DB 약 2배 추가 여유 점검. 실패를 감추지 않는다. TMP/TEMP·journal·임시폴더 ACL도 점검한다.
- 본문 NULL·File.Delete·secure_delete·VACUUM은 **물리 완전파기 증명이 아니다**. WAL/자유페이지/저널/VSS/SSD/백업 잔존 가능. BitLocker는 장치 유출 완화지 건별 파기 보증이 아니다.

## 5. 엔터티·키·제약·인덱스

아래는 논리 스키마/의사 타입이며 migration/SQL 구현물이 아니다. ID는 서버생성 불투명 키, 날짜는 DateOnly 의미의 ISO 일자, 시각은 정수 UTC instant로 저장하고 UI/이력 canonical에서 KST로 표시한다. 상태·분류는 제한 enum/CHECK. 기본 삭제는 RESTRICT, cascade로 이력/업무행을 지우지 않는다.

| 엔터티 | 주요 열·키/FK·제약 | 접근 인덱스 |
|---|---|---|
| User | UserId PK, Login UNIQUE(정규화), DisplayName(비식별 운영명), Role CHECK 담당/파트장/관리자, PasswordHash, Enabled, Stamp, MustChange, LockoutUntil, FailedCount, Version>0 | Login(unique), Enabled/Role |
| CaseRecord | CaseId PK, Year, Category CHECK 업무진단/준법감사, Title, Target, OwnerUserId FK User RESTRICT, Shelf, BinderNo, RetentionYears nullable >0, ConfirmedOn nullable, Hold bool, HoldReason nullable, BusinessPhase CHECK 초안/확정/조치중/종결, PurgedAt nullable, EverConfirmed bool, Version>0 | Year/Category/BusinessPhase, ConfirmedOn, OwnerUserId; PurgedAt/Hold; BinderNo |
| Result | CaseId PK/FK CaseRecord, DocumentNo, SummaryLine, Body nullable, ConfirmerUserId FK nullable | DocumentNo, CaseId |
| ActionRequest | ActionId PK, CaseId FK, Sequence>0, UNIQUE(CaseId,Sequence), Title, Type CHECK 지적/개선요구/권고, TargetDept, RequestedOn, ReplyDueOn, Content nullable, ActionState CHECK 요구중/회신수신/이행완료(추천값) | ReplyDueOn/ActionState, CaseId/Sequence |
| Fulfillment | FulfillmentId PK, ActionId FK, At, ActorId FK, FromState, ToState, Kind CHECK 회신/이행확인, ReplyBody nullable, CheckBody nullable, HistorySeq UNIQUE FK History | ActionId/At/FulfillmentId |
| Attachment | AttachmentId PK, CaseId FK, Kind CHECK 결과원문/증빙/회신첨부, DisplayName, StorageKey nullable UNIQUE, RegisteredAt, RegisteredBy FK, DeletedAt nullable, StorageState CHECK Staged/Present/DeletePending/Missing/Deleted | CaseId/RegisteredAt, StorageState |
| History | Seq PK, CaseId FK nullable(계정/백업 시스템행위), At, ActorId FK nullable(worker는 고정 시스템주체), Action, TargetKind/TargetId, SummarySafe, CanonicalVersion, PrevHash(32byte), RowHash(32byte), CommandId FK CommandReceipt, CommandOrdinal, UNIQUE(CommandId,CommandOrdinal), WorkKey nullable UNIQUE(작업 성공 단계 중복방지) | CaseId/At/Seq, TargetKind/TargetId |
| CommandReceipt | RequestId PK, ActorId FK User nullable(시스템주체), CaseId FK nullable, TargetId nullable, ExpectedVersion nullable, CommandKind, State Pending/Complete, OutcomeCode, ResultVersion, CompletedAt; 본문/경로/입력사본 없음 | CompletedAt; 동일 requestId 다른 주체/명령 거부 |
| FileIngressJob | IngressId PK, RequestId UNIQUE FK CommandReceipt, CaseId FK, AttachmentId 서버예약 UNIQUE, StagingKey nullable, FinalKey nullable, ExpectedVersion, Status Reserved/Copying/Staged/Moved/Committed/CleanupPending/Complete, LeaseUntil, ErrorCode; 미완료키만 보유 | Status/LeaseUntil, CaseId |
| DeleteJob | JobId PK, CaseId FK, Kind Expiry/RemoveDraftAttachment/EarlyFileDelete, TargetAttachmentId nullable FK, Generation, Status Pending/Running/RetryWait/Paused/Complete, ApprovedActorId FK nullable, ReasonSafe, Attempt, NextAttemptAt, ErrorCode, LeaseUntil, CreatedAt, CompletedAt, UNIQUE(CaseId,Kind,Generation,TargetAttachmentId의 NULL 정규화 키) | Status/NextAttemptAt, CaseId |
| DeleteJobItem | 복합PK(JobId,AttachmentId), JobId FK DeleteJob, AttachmentId FK Attachment, StorageKey nullable, Status Pending/Deleted/AlreadyMissing/RetryWait, ActualDeletedAt nullable, ErrorCode; 성공 후 키 NULL | JobId/Status |
| SystemMeta | Singleton PK=1, SchemaVersion, ChainTailSeq, ChainTailHash, RetentionPolicyVersion, IntegrityState | singleton |
| BackupOperation | OperationId PK, State Pending/Running/Failed/Complete, At, ActorId FK, SafeDestinationAlias, ErrorCode; 원문/검색어/전체경로 로그복제 없음 | At |

제약:
- Result는 건 등록과 동시에 1행 생성. 본문 외 제목 등 잔존필드는 길이 상한을 검증(기술권고 제목120/한줄160/자료명120/사유240자); 민감정보 방지 안내를 하고 원문 복사하지 않는다.
- Hold=true면 중립 사유 필수. ConfirmResult에는 양수 RetentionYears 필수. 유효 ConfirmedOn/확정자/상태의 정합은 Records tx가 강제한다. 취소시 날짜의 기산 해석은 U1 전 결정하지 않는다. 모든 확정/취소 시각은 이력에 남긴다.
- `EverConfirmed`는 취소로 false로 돌리지 않는 기술 통제 권고다. 최초확정일을 기산으로 쓰겠다는 정책 결정은 아니다. 취소 뒤 첨부빼기/연한변경 권한이 담당에게 자동 재개되지 않게 한다.
- Deleted인 Attachment는 DeletedAt 존재, StorageKey NULL. Present는 키 존재, DeletedAt NULL. Missing은 삭제 성공과 구별한다. DeletePending은 재시도할 키를 보유한다. DeleteJobItem 완료키도 지운다.
- PurgedAt은 본문 4종 전체 NULL + 대상 파일 모두 Deleted/AlreadyMissing 확정 + 운영 경로/job 키 모두 NULL인 경우에만 기록. 업무행/자료행 자체는 남긴다. 결과 “한 줄”·건목록·조치 메타·이력은 유지한다.
- 업무상태는 4값, 사용자 상태 표시는 PurgedAt이 있으면 원문의 다섯번째 값 **자료삭제됨**으로 파생한다. 기존 업무상태와 남은 조치 상태/종결이력은 소실시키지 않는다. 삭제작업 상태는 업무상태에 추가하지 않는다.
- 제목/대상/문서번호/철번호만 검색, 매개변수 SQL. 본문 FTS·파일 인덱스 없음. 중소규모 단일서버에서는 제한된 LIKE 검색을 허용하되 페이지크기50/최대100, 안정적 ID tie-breaker. 한글 검색 최적화를 위해 새 전문검색 엔진을 추가하지 않는다.

### 5.1 핵심 도메인 타입·시그니처 (의사코드만)
```text
CaseId, AttachmentId, ActionId, RequestId, ExpectedVersion = 서로 다른 값 타입
Actor = ActiveUser(UserId, Role, SecurityStamp)
RetentionDecision = NotDue | Due(DueOn, PolicyVersion) | Held | Undecidable
CaseView = { DisplayState, BusinessPhase, ActionSummary, Hold, DueOn?, PurgeProgress?, Version, Capabilities }
Command = RegisterCase | SaveDraft | AddAction | RecordReply | VerifyAction | ConfirmResult
        | CancelConfirmation | CloseCase | ChangeRetention | AddAttachment | RemoveDraftAttachment
        | EarlyFileDelete | SetHold | ReleaseHold | CreateAccount | DisableAccount | ResetPassword
        | ChangeOwnPassword | Backup
Records.Read(Actor, Query) -> View or Failure
Records.Execute(Actor, Command(ExpectedVersion?), RequestId) -> Snapshot or Failure
Records.ProcessDue(now) -> WorkSummary  // 서비스 주체만, 동일 규칙
Records.ResumeJobs(now) -> WorkSummary // 중단작업 재검사, 직접 SQL 우회 없음
FileStore.StageOpaqueBytes(stream) -> ServerStorageKey // 의미 분석 없음
FileStore.Delete(ServerStorageKey) -> Deleted | AlreadyMissing | Retryable | UnsafePath
Backup.Take(actor, approvedLocalDestination) -> OperationResult
```
등록/계정생성은 ExpectedVersion 없음, 기존건 변경은 반드시 있음. 외부 HTTP 입력은 어댑터에서 enum/길이/ID로 파싱. 권한·상태·기산·보류·tx·이력은 Records가 한 번만 판정한다. FileStore key 생성자 외에는 임의 문자열을 키로 승격할 수 없다.

## 6. 역할×행위 권한 (서버 강제)

정확 명칭은 **담당 / 파트장 / 관리자**. 아래는 S §4 기준이고 T 누락구간은 원문 재대조 게이트다. 전 역할 모든 건 열람; 담당자 필터는 권한범위가 아니다. 관리자는 파트장 권한 포함. worker는 로그인 역할을 추가한 것이 아니라 만료정책 실행 서비스주체다.

| 행위 | 담당 | 파트장 | 관리자 | 추가 조건 |
|---|---|---|---|---|
| 목록/상세/자료메타/이력 보기 | 가능 | 가능 | 가능 | 활성 계정 |
| 등록/초안/조치요구/회신/이행기록/자료추가 | 가능 | 가능 | 가능 | 전이·보존 차단 검사 |
| 결과 확정/확정 취소/종결 | 불가 | 가능 | 가능 | 취소 사유 필수, 연한 필수 |
| 보존연한 입력 | 확정 전만 | 확정 후에도 | 확정 후에도 | 확정 후 변경 사유와 앞뒤 연수 |
| 확정 전 첨부 빼기 | 가능 | 가능 | 가능 | EverConfirmed=false 권고; 취소로 우회 금지 |
| 확정 후 연한 전 파일 삭제 | 불가 | 불가 | 가능 | 사유 필수, 파일만/본문삭제로 확장 금지 |
| 삭제 보류 설정/해제 | 불가 | 불가 | 가능 | 이력 시각, 사유 안전 작성 |
| 계정/백업/비번 초기화 | 불가 | 불가 | 가능 | 관리 화면만 |
| 본인 비밀번호 변경 | 가능 | 가능 | 가능 | 본인 인증, 세션폐기 |
| 이력 수정/삭제 | 불가 | 불가 | 불가 | API 자체 없음 |

UI Capability는 최신 역할/상태에서 서버가 계산한다. 비권한 동작버튼은 숨기되 권한 설명은 보여준다. 조작 요청은 동일 표로 403이며 UI 미노출은 보안수단이 아니다. 계정/백업/보류 등 시스템행위도 최소이력에 남기되 비밀·경로를 넣지 않는다.

## 7. 상태·명령·전이와 오류

아래 **업무 세부전이/조치 상태값은 기술·업무 추천**이다. 원문은 건 상태 5값과 조치의 “상태”만 정했다. 새로운 계획/조사/결재요청/종결취소 상태를 추가하지 않는다. 총괄은 W F02/F03/F12와 대조해 세부규칙을 기록한다. 정책 U1/U2 미해결 영향 명령은 자동 확정/삭제로 진행시키지 않는다.

공통 실패: 비로그인401, 비권한403, 없음404, stale Version409, 입력422, CSRF400, DB busy503. 성공한 건 변경 명령은 Case Version 증가+이력+receipt 같은 tx(계정은 User.Version, 백업은 Operation 상태를 사용). 실패는 업무데이터와 성공이력을 남기지 않는다.

| 명령 | 입력 상태 → 결과 상태 | 필수 조건·이력 | 금지/오류 |
|---|---|---|---|
| RegisterCase | 없음 → 초안 | Result1행, 등록 | 누락/잘못된 분류422 |
| SaveDraft | 초안 → 초안 | 최소 필드 변경 적요만 | 확정 결과 실질수정은 취소 후 권고, 409/422 |
| ConfirmResult | 초안 → 확정(기존 조치 있으면 조치중 추천) | 파트장+, 연한, 확정자/일, 결과확정 이력 | 연한없음422; 기산결정 미완료 POLICY_UNDECIDED |
| CancelConfirmation | 확정/조치중/종결 → 초안 추천(자료삭제됨 표시가 겹친 건도 같은 명령 허용) | 파트장+, 사유, 조치/회신/삭제사실 유지 | 삭제파일 복구없음; 날짜정책 U1; 새 종결취소 기능 아님 |
| ChangeRetention | 상태 유지 | 미확정 담당도 가능, 그 외 파트장+; 확정이력 있으면 사유·연수 앞뒤만 | 취소로 권한 초기화 금지; 정책 미결정503 |
| AddAction | 초안 유지 / 확정→조치중 / 조치중 유지 | 순번 유일, 조치요구 이력 | 종결/자료삭제 후 신규내용은 차단 권고 |
| RecordReply | 건 상태 유지 | 이행+회신 이력 같은 At/actor | 회신만으로 이행완료/종결 금지 |
| VerifyAction | 건 상태 유지 | 이행확인, 명시적 완료판정 | 종결/삭제상태에서 신규본문 입력 차단 권고 |
| CloseCase | 확정/조치중 → 종결 | 파트장+, 모든 조치 완료 확인 추천 | 미완료422, 무조치건 권한자 명시 확인 추천; 파일삭제 없음 |
| AddAttachment | 초안/확정/조치중 → 유지 | opaque stage→finalize, 자료추가 이력 | 만료·미보류/삭제중/자료삭제 후 차단 권고 |
| RemoveDraftAttachment | 건 상태 유지 | 확정전만, job 생성, 자료삭제 이력 | EverConfirmed이면 불가(권고) |
| EarlyFileDelete | 건 상태 유지 | 관리자+사유, 지정 파일만 job | 보류중 금지 권고; 본문까지 지우지 않음 |
| SetHold/ReleaseHold | 건 상태 유지 | 관리자, 보류/보류해제 시각, 해제시 만료 경고 | 이전 삭제 복구없음; 해제시 유예 자동 부여 없음 |
| Expire | 어느 업무단계든 → 완료 후 표시 자료삭제됨 | 만료+미보류, job+최종 재검사 | 종결/미완료만으로 자동 연장·보류 금지 |
| Create/Disable/Reset/ChangePassword | 업무상태 유지 | §3/§6 | 마지막관리자 차단, 원비밀 이력금지 |
| Backup | 업무상태 유지 | 관리자, 공통 gate/스냅샷 | 부분복사 완료표시 금지 |

조치 상태 추천표: `요구중 → 회신수신 → 이행완료`; 회신추가기록은 회신수신 유지, 확인미완료면 회신수신 유지, 확인완료 명령만 이행완료로 전이. 완료 후 되돌림·종결 후 새 수정은 원문 미정이므로 임의 기능으로 제공하지 않는다. 업무적으로 필요하면 총괄이 기존 상태/명령 범위에서 결정하며 원정책 확장은 별도 승인한다.

자료 상태: Staged→Present(등록 tx), Present→DeletePending(job), DeletePending→Deleted 또는 DeletePending 유지(job/item=RetryWait), Present→Missing(열람시 존재 메타 검사만으로 확인). Missing은 곧바로 정상삭제로 위장하지 않는다. 삭제요청 중 이미없음이 확인되면 그 확인시각에 Deleted로 확정하고 적요에 이미없음 확인을 표시한다. 실제 unlink 시각을 알 수 없는 경우 과거 삭제시각을 발명하지 않는다.

자료삭제됨은 표시 overlay이므로 확정취소/연한변경을 허용하더라도 PurgedAt·삭제본문·삭제키는 되돌리지 않는다. 삭제된 건의 확정취소 후에도 표시는 자료삭제됨이고 이력에 취소 사실을 추가한다.

삭제job 상태: Pending→Running→Complete / RetryWait / Paused. 보류/정책변경은 Paused로 재검사, 해제 후 조건 맞으면 Pending. 프로세스 중단으로 Running lease가 만료되면 Pending 재검사; 파일이 이미 없으면 멱등 완료한다. 내부 job 상태는 원문 업무상태 5개에 섞지 않는다.

## 8. NASCA·파일·누출 경계

허용: 자료 종류/표시 이름/위치/등록·삭제일·등록자 관리, 존재 메타 확인, 암호화된 바이트의 의미를 해석하지 않는 저장·복사·삭제. 서버는 NASCA 복호화대상에 등록하지 않는다. **금지: 문서 본문읽기, ReadAll/파서, 해시용 내용 스캔, 바이러스검사 명목의 자동 내용열람, 미리보기, PDF 변환, OCR, 썸네일, 본문검색, AI, 열림 성공 자동시험.** 백업 byte copy는 의미적 본문열람과 구별하되 파일을 읽어 내용을 검사하는 기능은 없다. OS 보안제품 운용은 별도 현장 경계로, 앱이 예외·복호화를 자동 설정하지 않는다.

- 서버생성 StorageKey = 검증된 CaseId 세그먼트 + 무작위 AttachmentId 세그먼트(필요한 안전 확장자만). 클라이언트는 attachmentId/자료 메타/opaque bytes만 제출, 서버 경로·키를 지정하지 못한다. 표시이름을 디스크 파일명으로 사용하지 않는다.
- 표시용 내부 공유 위치는 현재 StorageKey에서 서버가 계산, 삭제명령은 표시문자열을 사용하지 않는다. 삭제하면 키/계산 위치가 사라진다. 추가 표시경로를 영구 이력에 복사하지 않는다.
- root의 separator 경계+Windows OrdinalIgnoreCase 검증; `..`, 절대/UNC 입력, ADS, 잘못된 세그먼트 거절. GetFullPath만으로 junction/reparse를 해석했다고 판단하지 않는다. 경로 구성요소 재분석점 거절, 서비스만 쓰기, 실제 handle/최종경로 검증 방식을 Windows에서 시험한다. 검사-삭제 사이 TOCTOU와 관리자 전면 통제는 잔여위험이다.
- 앱은 file:// 자동열기·파일 다운로드/preview를 제공하지 않고 위치만 제공. 사용자가 자기 PC에서 열며, “이 위치의 파일은 사용자 컴퓨터의 나스카로 연다” 안내. 실제 포털 로그인·암호문 열림은 승인된 환경에서 사람이 확인; 이번에는 가짜 빈 파일 계획만 쓴다.
- 로그: 상관ID/명령 종류/오류코드/불투명 대상ID/상태·소요만. 본문·검색어·표시파일명·전체경로·쿠키·비번·토큰·입력 DTO/SQL parameter·예외 전문 덤프 금지. 요청 본문 로깅/응답 캐시/개발 예외화면 금지. 검색 raw query는 URL·접근로그·히스토리에 넣지 않는다(UI_SPEC §1).
- 이력 적요는 허용된 템플릿, 본문/파일경로/검색원문/입력 원값 복제 금지. 예외적으로 원문이 요구한 연수 `5년 → 7년` 등 비민감 정책값과 상태 앞뒤만 허용한다. 본문 digest도 영구 이력에 넣지 않는다.
- 남기는 제목/대상/결과한줄/조치제목/자료명/사유는 연한 뒤에도 남는다고 작성 전 안내. 실명·사번·진술·민감상세를 쓰지 않는다. 자동 비식별화/기존이력 수정 기능은 추가하지 않는다.

## 9. 보존·경합·부분실패·재시작

### 9.1 날짜와 정책 경계

원문: 건당 연한 하나, 기산일=결과확정일. 종결일/연도/파일등록일로 바꾸지 않는다. 종결·조치미완료는 자동보류/기산연장 근거가 아니다. 보류 중 만료일은 그대로, 해제 후 이미 만료면 처리대상, 임의 유예 없음. 파일/본문 기삭제는 확정취소·연장·보류로 복구하지 않는다.

기술추천 기본(회사 보존규정 승인이라는 뜻 아님): DateOnly의 달력 N년, 대응일 00:00 KST, 2/29의 대응일 없으면 2월 말일. 예: 가상 2024-02-29+1년→2025-02-28 00:00 KST. 총괄이 기술설정/문서로 확정하며 법정연한 숫자는 발명하지 않는다. U1 재확정 기산 미결정은 자동삭제 실행 차단 게이트다.

삭제 주기 추천: 기동 검증 직후·보류해제 직후 enqueue, 가동중 60초마다 대상 탐색. 이는 삭제까지의 가동 지연 목표이며 꺼진 서버에서 정시 실행을 보장하지 않는다. UI는 예정일·실제처리시각·보류·지연을 구분한다. 시간 역행/시계 오차 발견시 경고하고 신규 만료판정 중단, 운영자가 시각을 확인한다.

### 9.2 잠금 순서와 단일 트랜잭션

모든 변경/등록 finalize/삭제는 `GlobalMaintenanceGate(shared) → CaseGate(caseId) → 짧은 SQLite tx` 순서. Backup은 global exclusive를 DB 스냅샷+files 복사 끝까지 보유. multi-case 변경은 ID순 잠금, case gate 잡고 global을 나중에 기다리지 않는다. 단일 프로세스 강제. stage 바이트복사도 shared gate에 포함해 백업이 반쪽 staging을 집어가지 않게 하며, 미완료 stage는 snapshot 대상에서 제외·기동 정리한다.

- **두 사용자 수정:** expectedVersion을 모든 건하위 명령에 요구. 최신 Version 조건부 갱신이 0행이면 rollback/409, 자동 덮어쓰기·본문 merge 없음. UI는 입력을 현재 페이지 메모리에만 유지하고 새 snapshot 비교 후 사용자가 다시 제출한다.
- **보류 vs 삭제:** 같은 case gate를 보류설정 commit, 본문 NULL tx, 파일 삭제 직전 검사부터 실제 unlink/완료 메타까지 공유. 보류 commit이 먼저면 삭제 안 함. 삭제가 먼저 시작했으면 보류 요청은 기다린 후 최신 삭제현황을 받고 성공; 소급복구 불가를 표시한다. 파일별로 gate를 풀어 보류가 후속 파일 삭제를 중단할 수 있게 한다. UI 성공응답 이전 클릭시각을 보류기준으로 주장하지 않는다.
- **삭제 vs 파일추가:** finalize가 먼저면 worker가 새 파일까지 포함하도록 job 재검사. worker가 먼저면 AddAttachment를 PURGE_IN_PROGRESS/EXPIRED_CONTENT_LOCKED로 거절하고 staged bytes를 정리한다. 삭제후 재입력은 차단 권고이며 U1 결정에 포함한다. 만료·미보류 건을 입력으로 다시 살리지 않는다.
- **연한변경/취소 vs 삭제:** 동일 case gate와 Version. 실제 실행 직전 최신 보류·기산·연한·승인조건 재검사, 조건 변경시 Paused. 이미 지운 항목은 복구하지 않는다. 현재 초안이라는 이유로 과거 확정 통제를 해제하지 않는다.
- **중복 요청:** requestId는 actor+command+target+version과 결합, receipt와 변경 동시 commit. 같은 actor/command/target/version의 재전송은 최초 접수 결과를 반환하고 다시 변경하지 않는다. 다른 actor/command/target/version으로 재사용하면409. 본문 원문·digest를 receipt에 저장해 비교하지 않는다. 동일 requestId로 본문만 바뀌어도 최초 요청 결과를 반환한다(수정 제출은 새 requestId). client는 확정 응답 전 requestId를 유지한다. Pending 접수 후 crash한 입력은 임의 재생하지 않고 실제 commit 여부를 확인한 뒤 미수행 응답으로 종료하며, 사용자가 최신 Version으로 다시 제출한다.

### 9.3 영속 삭제 프로토콜

1. 대상 결정과 job/item 생성을 tx로 영속화. UI 요청은 접수 성공202+jobId를 받으며 완료를 뜻하지 않는다. 접수 receipt는 Accepted 결과를 보존하고, 이후 진행/실패는 job 조회에서 전달한다. worker의 단계별 성공은 별도 시스템 requestId/WorkKey로 기록하여 원요청 receipt를 성공완료로 다시 쓰지 않는다. 확정전 첨부빼기·관리자 연한전 파일삭제도 동일 메커니즘. 삭제 전 이력 성공으로 기록하지 않는다.
2. global shared+case gate 안에서 최신 권한(수동작업은 승인자 계정/역할), 보류·정책·Version을 다시 확인. 자동만료는 시스템주체+만료정책. 중단/취소된 승인을 그대로 실행하지 않는다.
3. 만료 job만: 결과 Body/조치 Content/ReplyBody/CheckBody를 NULL로 하고 행위=자료삭제/적요=“본문 삭제”인 최소 처리이력+Version tx commit. 파일삭제와 DB 원자성을 거짓 주장하지 않는다. 파일실패 때 본문만 지워질 수 있음을 UI에 보인다. 조기 파일삭제는 본문에 손대지 않는다.
4. 파일 item마다 case gate 유지, StorageKey 재검증 후 File.Delete(휴지통 없음). 성공/이미없음 확인 뒤 item·Attachment DeletedAt/키NULL·최소 자료삭제이력을 tx로 commit. unlink 후 commit 실패면 재시작에서 이미없음 확인으로 완료한다. 실제 삭제시각을 알 수 없으면 확인시각으로 명시한다.
5. 실패 item은 키 보유+RetryWait, 실제 성공한 파일만 삭제일/위치비움. 오류는 코드만. 권고 retry 1분→5분→30분, 이후 30분 간격, 디스크/권한/unsafe path는 자동 무한시도 대신 운영점검 대기. 재시도마다 보류/연한 재검사. 동일 성공이력 중복은 History.WorkKey=`(job,item,phase)` 유일 제약으로 방지한다.
6. 마지막에 만료(Expiry) job은 모든 삭제대상 본문NULL/파일없음/저장키·job키NULL을 재검사하고 job Complete/PurgedAt+Version+완료이력을 tx. 확정전 첨부빼기/조기파일삭제 job은 지정파일 성공만으로 Complete 처리하며 **PurgedAt을 설정하지 않고 본문·건상태를 유지**한다. 하나라도 실패하면 **자료삭제됨 표시 금지**, 기존 업무상태+“삭제 미완료 n건” 보조표시. 경로가 위험하거나 존재검사 권한오류면 없음으로 간주하지 않는다.
7. 기동시 Running lease 만료 재개, orphan stage/final 파일을 등록 완료파일과 대조. ID는 있지만 파일없음이면 Missing으로 표시하고 관리자 점검; 무조건 등록성공/정상삭제로 고치지 않는다. orphan 제거도 검증된 키·FileIngressJob의 영속 CleanupPending 단계·유예된 미완료 요청 확인을 거친다. 본문을 열어 유효성 검사하지 않는다.

파일추가 프로토콜: Pending request receipt+FileIngressJob 예약 영속 → opaque byte copy → case gate에서 최신보존/버전 검사 → 파일이동 → Attachment+이력+receipt tx. 이동 직후 crash면 staged 작업의 키로 재검사해 commit 또는 orphan cleanup. DB 참조만 먼저 확정하지 않으며, stage의 임시 경로를 이력에 남기지 않는다. 업로드 최대크기·타임아웃은 기술추천 기본 100MiB/10분으로 제한하고 유효성은 메타/크기만 검사한다(본문 4종 입력은 각 20,000자 상한 추천). ingest 상태는 Reserved→Copying→Staged→Moved→Committed→Complete, 중단/실패는 CleanupPending→Complete; Complete에서 staging/final 작업키는 NULL, 정상자료키는 Attachment에만 남긴다. 기동시 lease 만료를 재검사하고 파일 존재/DB commit으로 성공 또는 안전한 cleanup을 판정한다. 백업에는 Committed Attachment만 복사한다.

## 10. SHA256 체인: 가능한 탐지와 불가능한 보장

권고 canonical v1: UTF-8, 타입·길이접두어 고정 필드열, 시각은 명시적 `+09:00` ISO8601. `H_i=SHA256(version,seq,caseId,at,actorId,action,targetKind,targetId,summarySafe,prevHash)`. 자기 행의 의미필드도 포함하여 마지막행 단순수정을 탐지한다. 앞줄 hash만 저장해서는 마지막행 변조를 충분히 검사할 수 없다. global sequence/tail 갱신은 업무변경과 같은 tx, rollback된 sequence는 소비하지 않아 정상 갭을 만들지 않는 설계를 권고한다. schema migration이 기존 이력을 다시 쓰지 않도록 canonical version별 검사기를 보존한다.

- 시작·로그인·이력표시 및 변경 전 최신 committed tail까지 검증. 재계산 불일치/행순서오류면 상단 **이력 훼손** 경고. 기술권고는 읽기만 허용하고 상태변경/worker/백업 완료처리를 동결하여 “변경했는데 이력이 없다”를 막는다. 자동 체인수리/재서명 금지.
- E6 수락은 **한 행의 summary 등 단순 DB수정 후 hash/head를 같이 고치지 않은 경우 탐지**다. 마지막 행도 동일하게 시험한다. 모든 관리자 공격 방지라는 뜻이 아니다.
- 꼬리삭제 후 남은 prefix는 유효하다. 동일 DB에 expected tail/count가 그대로 있으면 불일치 탐지 가능하지만 공격자가 그 값도 맞추면 **재계산 없이 우회** 가능. 외부 독립 앵커 없이는 완전 탐지를 보장하지 않는다.
- 전체 이력 재계산+tail/count 재작성, 코드·DB·백업 전체교체는 같은 기기 권한의 공격자가 수행할 수 있다. DB와 같은 기기에 둔 HMAC키/서명키도 탈취/교체 가능하므로 키 추가만으로 관리자 대항 무결성을 약속하지 않는다. 체인은 업무 본문 테이블의 직접변조 전체까지 자동 보호하지 않는다(본문 hash 영구보관도 금지).
- 선택안 A: 현재 로컬 체인+ACL, 탐지 범위를 명확히 한 최소구현(권고). 선택안 B: 별도 ACL 장치/인쇄본에 head+seq+시각을 사람이 보관하여 탐지력 보완. **미승인 대안**, 외부 업로드/클라우드 앵커 금지, 추가 보관절차 영향 있음. 원문 필수가 아니므로 구현 전 별도 결정 필요. 이 선택은 원문 SHA256 기능 자체를 대체하지 않는다.

## 11. 백업·암호화·연한삭제 충돌 (U2)

원문은 관리자 지정 **내부 디스크**에 data+files를 복사한다. 백업 자동삭제주기/삭제전파/복구정책은 명시하지 않았다. NASCA 파일은 암호문 바이트 그대로, 본문 해석 없음. 백업 목적지를 사용자 입력경로로 무조건 열지 않으며 허용된 내부 로컬 볼륨 아래 관리자 지정경로만; files/DB 밖 임의 경로 접근·재분석점 거부, backup이 files 안에 중첩되지 않게 검증한다.

정합성 기술추천: global exclusive로 모든 변경/finalize/삭제를 막음 → SQLite Backup API로 consistent snapshot → 같은 gate에서 snapshot이 참조한 files를 opaque copy → 파일 존재/길이·DB 무결성 확인 → 완료마커 원자적 확정 → finally gate 해제. 실행중 db 파일만 File.Copy 금지(WAL 누락). 실패본은 “미완료”, 완료이력 없음; 다음 쓰기는 gate 해제 후 재개. 백업대상에 staging/실패복사본/이전 backup을 포함하지 않는다.

**암호화는 삭제가 아니다.** NASCA 암호문도 원본자료이며, DB 본문은 별도 암호화 보호가 필요하다. 권고는 backup 디스크의 BitLocker 등 Windows at-rest 암호화+제한 ACL; 클라우드 키 저장소 없음. 키 관리·장애복구는 내부 운영절차. 같은 디스크에 암호화 백업이 남아도 연한삭제 충돌이 해결되지 않는다.

| 미승인 대안 | 영향 | 권고 |
|---|---|---|
| 기존 백업 무기한 유지 | 단순하나 만료자료 영구잔존/복구 노출, 원문 삭제취지 충돌 | 채택하지 않음 |
| 승인된 최대수명 뒤 내부 백업 폐기 + 복구전 최신삭제 재적용 | 복구가능기간 축소, 최신정책 기록 필요 | **권고, 기간·절차 대표 승인 필요** |
| 모든 백업에 즉시 삭제 전파 | 삭제충실도 높으나 여러 snapshot 수정·검증·장애복구 복잡 | 기본 미채택, 추가승인 필요 |

복구안(구현 전 운영정책 결정 필요): 복구본을 사용자 접속과 분리 → 최신 삭제완료/조기파일삭제/보류설정·해제·연한변경 메타 확보 → 복구본에 재적용 → 현재 날짜 만료 재검사 → 파일/본문/경로/이력 정합검증 → 관리자 승인 후 접속개방. **현재날짜 만료계산만으로는 조기삭제·이후 보류해제를 복원할 수 없다.** 낡은 백업의 hold=true를 최신 보류로 믿지 않는다. 최신 기준이 없으면 일반사용자 개방 금지/정비상태, 단순 관리자 클릭으로 기삭제노출을 승인하지 않는다.

최신 메타를 별도 내부 기록으로 남기는 방법·최대수명·폐기증적은 추가 정책이므로 자동 생성/삭제를 이번에 구현하지 않는다. 해당 기록은 본문·파일경로 없는 최소정보이고 두번째 업무 DB를 도입하지 않는 방식으로 총괄이 결정한다. 낡은 백업만으로 전손 복구시 기삭제 사실을 완전히 재구성할 수 없다는 한계를 명시한다.

## 12. 실패코드와 수락 시나리오 (설계 검증 계약, 실행결과 아님)

| 코드/HTTP | 의미 | UI/재시도 |
|---|---|---|
| AUTH_REQUIRED/401 | 만료·폐기 세션/비활성 계정 | 재로그인, 본문 자동저장 없음 |
| FORBIDDEN/403 | 활성 세션의 역할 불가 | 버튼없음, 관리자 문의 |
| NOT_FOUND/404 | 건 없음 | 목록복귀, 상세정보 유출없음 |
| STALE_VERSION/409 | 다른 수정 선행 | 입력 보존(메모리), 최신보기/재제출 |
| RETENTION_REQUIRED/422 | 연한없음 | 필드/상단오류 |
| REASON_REQUIRED/422 | 취소/연한후변경/조기삭제 사유없음 | 중립사유 안내 |
| HOLD_ACTIVE/409 | 보류중 삭제시도 | 삭제안함, 보류정보 |
| PURGE_IN_PROGRESS/409 | 삭제와 입력 경합 | 최신 진행표시 |
| EXPIRED_CONTENT_LOCKED/409 | 만료 미보류/기삭제에 재입력 | 복구없음/관리자 검토 |
| POLICY_UNDECIDED/503 | U1/U2 운영 게이트 미결정 | 완료표시 금지, 총괄설정 필요 |
| FILE_MISSING/409 | 등록자료 실제없음 | Missing, 정상삭제와 구별 |
| FILE_DELETE_FAILED/503 | 잠김/권한/디스크오류 | 성공분만 날짜, 영속재시도 |
| UNSAFE_STORAGE_KEY/422 | 경로 탈출/재분석점 | 작업중단·운영점검, 경로본문 비표시 |
| BUSY_RETRY/503 | DB잠김 제한초과 | 재시도, 동일 requestId |
| STORAGE_FULL/507 | 디스크/메모리 부족 | 저장실패, 입력 현재화면 유지 |
| INTEGRITY_FAILED/503 | 이력불일치 | 상단훼손, 읽기전용 권고 |
| BACKUP_INCOMPLETE/503 | 백업 부분실패 | 완료아님, gate 해제 |
| SERVICE_UNAVAILABLE/503 | 중단·검증·정비 | 재연결, 전송결과 미확정이면 receipt조회 |

| 시험 ID | S §10 연결 / 조작 | 기대 불변식 |
|---|---|---|
| E1 | 완료1: Windows 1대 설치+다른PC 접속 | 내부 HTTPS만; **현장 미검증** |
| E2 | 완료2: 4화면/로그인/검색/백업 흐름 | 외부요청0·맑은고딕·CDN0; 실행시험 미실시 |
| E3 | 완료3: F01을 담당/파트장/관리자 요청으로 변형 | 서버403, 사유422, 계정끄기/초기화 구세션401, CSRF400 |
| E4 | 완료4: F01 회신→이행확인→종결 | 같은 시각 이행/이력, 회신≠완료, 시간+Seq 정렬 |
| E5 | 완료5: F01 날짜만 가상 만료로 바꿔 잠금파일 실패→재시작 | 성공분만 삭제일, job키 보유, 전체완료 오표시0; 재시도후 모든본문NULL/경로NULL/파일없음 |
| E6 | 완료6: F02 이력 중간/마지막행 단순수정 | 훼손배너; 꼬리+head동시변조/전체재계산 미탐지 한계 기록 |
| E7 | 완료7: F01 빈자료의 등록/위치/삭제/백업 경로 검토 | 내용파서/preview/서버열림시험0; **실NASCA 사람이 추후** |
| X1 | F01 동시 수정 두 요청 | 1성공+1 409, 성공이력1 |
| X2 | F01 보류commit vs unlink / 보류해제 | commit선행시 삭제0, 삭제선행분 복구없음, 이후 재검사 |
| X3 | F01 삭제 vs 첨부 finalize / 연한변경 vs worker | 키잃음0, 새파일 누락완료0, 정책 최신판정 |
| X4 | F01 unlink직후 crash / DBcommit직전 crash | 이미없음 멱등, 중복성공이력0 |
| X5 | F01 backup 중 업로드/삭제 + 복사실패 | 정합 snapshot 또는 미완료, finally gate 해제 |
| X6 | F01/F02 낡은 backup 복원 | 최신 조기삭제/보류해제 재적용 전 일반접속0 |
| X7 | F01 취소→재확정/연장/본문재입력 | U1 정책일치, 담당 권한부활0, 기삭제복구0 |
| X8 | F01 윤일/기산 경계·서버중단 | KST 기술기본 일관, 기동후 실제처리시각 |
| X9 | F01 악성키/ADS/UNC/재분석점/파일권한오류 | root밖삭제0, 오류를 없음으로 취급0 |
| X10 | 두 fixture UI 200%/키보드/스크린리더 | UI_SPEC §8 계약 만족, 실제 브라우저시험 미실시 |

모든 시험은 UI_SPEC §7 **같은 가짜2건을 초기화·변형**하는 계획이며 상시 건을 늘리지 않는다. DB변조·잠금 등은 향후 격리 fixture에만 수행한다. 이번에 실제 DB/파일을 만들거나 시험하지 않았다.

## 13. 구현 전 결정·미결정과 범위 방어

### 대표님에게 물을 핵심 질문 (U1/U2)

- **U1:** “확정 취소 후 재확정해도 보존 기산일을 최초 확정일로 유지할까요, 아니면 재확정일로 새로 계산할까요? 권고는 최초 기산 유지입니다. 취소로 담당의 연한변경/첨부빼기 권한이 되살아나지 않고, 기삭제자료 재입력도 차단하는 경계로 정해도 될까요?” 최초기산 유지와 취소시 기산중지 여부는 원문 미명시이므로 승인 전 자동삭제/날짜정책 확정 금지. 새 날짜필드/최초일 표시는 승인결과에 따라 최소 도입하며 현재 설계가 승인으로 간주하지 않는다.
- **U2:** “내부 백업은 최대 얼마 동안 보관하고 어떤 주기로 폐기할까요? 복구 전 최신 조기삭제·보류해제·만료삭제를 재적용하기 위한 최소 내부 기록을 유지하고, 최신 기준을 확인할 수 없으면 일반접속을 막는 운영안을 승인할까요?” 백업 자동정리/삭제전파는 승인 전 넣지 않는다. 암호화만으로 연한삭제가 해결되지 않는 영향도 함께 설명한다.

### 총괄이 기술·업무 기본으로 문서화할 것 (질문 팽창 금지)

- 원문 T63 §4~§5 생략 없는 대조 확보: 역할/확정취소/연한/보류/건상태·결과항목. 현재 S를 잠정 기준으로 썼다는 표시 유지.
- 기술기본: HTTPS/CA·ACL·세션폐기/CSRF, 달력년·윤년말일·KST, 탐색60초/재시도1·5·30분, 공통 gate, pending 성공분 확정, 훼손시 읽기전용. 대표에게 TLS/잠금횟수 하나하나를 묻지 않는다.
- 업무추천 확정: 확정대기=연한·최소결과가 입력된 초안(결재요청상태 신설없음), 조치 요구중/회신수신/이행완료, 모든조치 완료 후 종결·무조치 명시확인, 확정결과 수정은 취소후, 종결취소 추가없음. 원문 정책을 넓히는 변경이면 대표 승인으로 승격한다.
- 체인 체크포인트는 선택 미승인 대안. 외부앵커/별도비밀을 기본 필수기능으로 만들지 않는다. 잔존필드·이력 정정삭제는 원정책과 충돌하므로 이번 범위 제외.
- 별도 계획화면·워크페이퍼·진술확인서·전자결재·피감부서로그인·징계판정·AI·새알림·원문검색·preview·포털/SSO·외부업로드·클라우드·원격저장소 모두 제외.

## 14. 문서 자체 검증 기록

원문추적/권한/전이/삭제부분실패/복구/UI 계약을 수동 교차대조했다. 최종 재읽기와 표·참조 점검 결과는 아래 검증 부록에 남긴다. **코드나 실제 시스템이 검증되었다는 뜻이 아니다.**

### 검증 부록 — 2026-11-07 00:09 KST

- 두 파일 전체 재읽기 후 권한·전이·실패·삭제결과를 수동 대조했다. 조기파일삭제의 PurgedAt 오설정 위험, 첨부와 job 재시도 상태 혼동, 기삭제건 확정취소 표시, receipt/ingress 영속성, 401/403, 검색복귀/확대스크롤 문구를 보정했다.
- 로컬 문서 정적점검 25/25 충족: 원설계 해시불변, 근거누락표시, 구조2안/평가기준5개, 역할3/화면4, 영속작업/공통gate, 부분실패, 체인·물리잔존·백업한계, 검색/접근성, 가짜2건, E1~E7/X1~X10, U1/U2, 코드펜스짝/상대링크/표열수. 이는 **문서 구조·필수표현 점검**이지 동작시험 통과가 아니다.
- 남은 근거부족: T63 §4 일부~§5 앞부분 전문 미확보. S의 해당정책을 잠정 기준으로 유지. 최초/재확정 기산(U1)·백업수명/복구삭제기준(U2)은 미승인. 조치 세부전이·확정대기 기준은 추천으로 표시했다.
- 실제 코드/DB/서비스/네트워크/브라우저/Windows/NASCA/스크린리더 시험은 전부 미실시. 설치·외부업로드·원격저장소·커넥터 쓰기·타인 메시지 발송 없음.
