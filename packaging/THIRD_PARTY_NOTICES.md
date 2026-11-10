# 제3자 고지 위치

패키지 안의 고지 원문은 `licenses/`에 있습니다. 이 파일은 그 목록을 가리킬 뿐이며, 라이선스 원문을 대신하지 않습니다.

- `licenses/INDEX.txt` — 각 파일을 어느 nupkg 또는 URL에서 그대로 복사했는지
- `licenses/runtime/<rid>/` — 그 RID의 .NET 10.0.14 런타임과 ASP.NET 런타임 nupkg에 들어 있는 LICENSE와 THIRD-PARTY-NOTICES
- `licenses/nuget/` — 직접 참조하는 NuGet의 nuspec. nupkg에 LICENSE 또는 NOTICE 멤버가 있으면 그 파일도 복사합니다. 멤버가 없으면 만들지 않습니다.
- `licenses/Apache-2.0.txt` — https://www.apache.org/licenses/LICENSE-2.0.txt 의 1–9절 전문
- `licenses/dotnet-0427b9ab-LICENSE.TXT` — Microsoft.Data.Sqlite 10.0.14 nuspec이 가리키는 커밋의 MIT 원문
- `licenses/sbom.json` — 그 패키지의 `AuditRecord.App.deps.json`으로 만든 RID별 목록

SQLitePCLRaw 2.1.13 nupkg에는 LICENSE와 NOTICE 멤버가 없습니다. 엔진과 래퍼의 구분은 nuspec 저작권 문구와 Apache-2.0 전문으로 남깁니다.

## 함께 제공하는 프런트엔드 자산

인터넷이 없는 서버에서도 쓰도록 아래 오픈 소스 파일을 `app/wwwroot/`에 함께 둡니다. 실행 중 외부 요청은 없습니다.

- Pretendard Variable 1.3.9 (글꼴) — SIL Open Font License 1.1. 원문: `app/wwwroot/fonts/LICENSE-Pretendard.txt`. 출처: npm `pretendard` 패키지 `dist/web/variable/woff2/PretendardVariable.woff2`
- Chart.js 4.4.7 (현황판 그래프) — MIT. 원문: `app/wwwroot/lib/LICENSE-ChartJS.md`. 출처: npm `chart.js` 패키지 `dist/chart.umd.js`
