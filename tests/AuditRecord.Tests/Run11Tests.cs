using System.Globalization;
using AuditRecord.Services;
using AuditRecord.Storage;

namespace AuditRecord.Tests;

/// <summary>간편 설치, 접속 비밀번호, 등재 항목, 감사번호 연결, 파일 이름과 형식.</summary>
public class Run11Tests
{
    [Fact]
    public void Simple_install_sets_one_access_password_and_one_admin()
    {
        using var h = new Harness();
        Assert.False(h.Svc.GetAccessState().AccessPasswordSet);

        var bad = h.Svc.InstallSimple("감사파트", Harness.Password, "다른값");
        Assert.False(bad.Ok);
        var tooShort = h.Svc.InstallSimple("감사파트", "short", "short");
        Assert.False(tooShort.Ok);

        var done = h.Svc.InstallSimple("감사파트", Harness.Password, Harness.Password);
        Assert.True(done.Ok, done.Message);
        var state = h.Svc.GetAccessState();
        Assert.True(state.AccessPasswordSet);
        Assert.Equal("감사파트", state.TeamName);

        var only = Assert.Single(h.Svc.ListUsers());
        Assert.Equal("관리자", only.Role);
        Assert.Equal("admin", only.Login);

        // 한 번 설치하면 다시 열리지 않는다.
        Assert.False(h.Svc.InstallSimple("다시", Harness.Password, Harness.Password).Ok);
    }

    [Fact]
    public void Access_password_signs_in_without_a_login_name()
    {
        using var h = new Harness();
        Assert.True(h.Svc.InstallSimple("감사파트", Harness.Password, Harness.Password).Ok);

        var ok = h.Svc.LoginWithAccessPassword(Harness.Password);
        Assert.True(ok.Ok);
        Assert.Equal("관리자", ok.User!.Role);

        Assert.False(h.Svc.LoginWithAccessPassword("wrong-password-value").Ok);

        // 다섯 번 틀리면 바른 비밀번호도 잠시 막는다.
        for (var i = 0; i < 5; i++) h.Svc.LoginWithAccessPassword("wrong-password-value");
        Assert.False(h.Svc.LoginWithAccessPassword(Harness.Password).Ok);

        h.Clock.Advance(TimeSpan.FromMinutes(16));
        Assert.True(h.Svc.LoginWithAccessPassword(Harness.Password).Ok);
    }

    [Fact]
    public void Admin_changes_the_access_password_and_the_old_one_stops()
    {
        using var h = new Harness();
        Assert.True(h.Svc.InstallSimple("감사파트", Harness.Password, Harness.Password).Ok);
        var admin = h.Svc.ListUsers().Single().ToActor();
        const string next = "another-correct-horse";

        Assert.False(h.Svc.ChangeAccessPassword(admin, "wrong", next, next, Guid.NewGuid().ToString("N")).Ok);
        Assert.True(h.Svc.ChangeAccessPassword(admin, Harness.Password, next, next, Guid.NewGuid().ToString("N")).Ok);
        Assert.False(h.Svc.LoginWithAccessPassword(Harness.Password).Ok);
        Assert.True(h.Svc.LoginWithAccessPassword(next).Ok);
    }

    [Fact]
    public void Discipline_keeps_every_register_column_and_numbers_rows()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var lead = h.Account(admin, "lead11", "시험파트장", "파트장");
        var first = h.Svc.SaveDiscipline(lead, Sample("가상갑", "A-1001", "2026-01-02"), Guid.NewGuid().ToString("N"));
        var second = h.Svc.SaveDiscipline(lead, Sample("가상을", "A-1002", "2026-02-03"), Guid.NewGuid().ToString("N"));
        Assert.True(first.Ok && second.Ok);

        var rows = h.Svc.ListDiscipline(null, null, null);
        Assert.Equal(2, rows.Count);
        var row = rows.Single(r => r.PersonName == "가상갑");
        Assert.Equal(1, row.Seq);
        Assert.Equal("A-1001", row.EmployeeNo);
        Assert.Equal("예시부서", row.Department);
        Assert.Equal("대리", row.Position);
        Assert.Equal("2026-01-02", row.ViolationDate);
        Assert.Equal("예시 내부 기준 제3호", row.RuleText);
        Assert.Equal("견책", row.SanctionType);
        Assert.Equal("2026-03-04", row.SanctionDate);
        Assert.Equal("1개월", row.SanctionTerm);
        Assert.Equal("예시 인사위원회", row.DecisionBody);
        Assert.Equal("예시-인사-2026-001", row.DocNo);
        Assert.Equal("초안", row.Status);
        Assert.Equal("예시 비고", row.Remark);
        Assert.Equal("시험파트장", row.OwnerName);
        Assert.Equal(10, row.CreatedDate.Length);
        Assert.Equal(2, rows.Single(r => r.PersonName == "가상을").Seq);

        var csv = h.Svc.ExportDisciplineCsv(admin, Guid.NewGuid().ToString("N"));
        Assert.Contains("순번,사번,성명,부서,직위,위반일자,위반내용,관련규정,징계종류,징계일자,징계기간,결정기관,근거문서번호,상태,비고,등록일자,담당자,감사번호", csv, StringComparison.Ordinal);
        Assert.Contains("A-1001", csv, StringComparison.Ordinal);
    }

    [Fact]
    public void Discipline_links_to_a_case_by_its_audit_number()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var lead = h.Account(admin, "lead12", "시험파트장", "파트장");
        var made = h.Svc.RegisterCase(admin, 2026, "업무진단", "연결 대상 건", "예시조직", admin.UserId,
            "서가", "철", "", "한 줄", "본문", 3, Guid.NewGuid().ToString("N"));
        Assert.True(made.Ok, made.Message);
        var caseId = made.CaseId!;
        var documentNo = h.Svc.GetCase(admin, caseId)!.DocumentNo;
        Assert.False(string.IsNullOrEmpty(documentNo));

        // 없는 번호는 거절한다.
        var missing = Sample("가상병", "A-1003", "2026-04-05");
        var rejected = h.Svc.SaveDiscipline(lead, With(missing, "2999-99-99"), Guid.NewGuid().ToString("N"));
        Assert.False(rejected.Ok);

        // 감사번호를 적으면 그 건으로 이어진다.
        var linked = h.Svc.SaveDiscipline(lead, With(missing, documentNo), Guid.NewGuid().ToString("N"));
        Assert.True(linked.Ok, linked.Message);
        var row = h.Svc.OpenDiscipline(lead, linked.Extra, false)!;
        Assert.Equal(caseId, row.CaseId);
        Assert.Equal(documentNo, row.CaseDocumentNo);

        // 건 상세에서도 그 징계기록이 보인다.
        var onCase = Assert.Single(h.Svc.DisciplineForCase(caseId));
        Assert.Equal("가*병", onCase.PersonMasked);

        // 제목으로도 찾는다.
        var byTitle = h.Svc.SaveDiscipline(lead, With(Sample("가상정", "A-1004", "2026-05-06"), "연결 대상 건"), Guid.NewGuid().ToString("N"));
        Assert.True(byTitle.Ok, byTitle.Message);
        Assert.Equal(2, h.Svc.DisciplineForCase(caseId).Count);
    }

    [Fact]
    public void Discipline_search_covers_the_register_columns()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var lead = h.Account(admin, "lead13", "시험파트장", "파트장");
        h.Svc.SaveDiscipline(lead, Sample("가상갑", "A-7777", "2026-01-02"), Guid.NewGuid().ToString("N"));
        h.Svc.SaveDiscipline(lead, Sample("가상을", "B-8888", "2025-01-02"), Guid.NewGuid().ToString("N"));

        Assert.Single(h.Svc.ListDiscipline("A-7777", null, null));
        Assert.Equal(2, h.Svc.ListDiscipline("예시-인사-2026-001", null, null).Count); // 근거문서번호도 찾는다
        Assert.Equal(2, h.Svc.ListDiscipline("예시부서", null, null).Count);
        Assert.Single(h.Svc.ListDiscipline(null, null, 2025));
        Assert.Equal(2, h.Svc.ListDiscipline(null, "견책", null).Count);
        Assert.Empty(h.Svc.ListDiscipline(null, "파면", null));
    }

    [Fact]
    public void Expired_discipline_keeps_the_row_but_clears_the_person()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var lead = h.Account(admin, "lead14", "시험파트장", "파트장");
        var input = Sample("가상갑", "A-1001", "2020-01-02");
        var saved = h.Svc.SaveDiscipline(lead, With(input, null, status: "확정", sanctionDate: "2020-03-04", year: 2020), Guid.NewGuid().ToString("N"));
        Assert.True(saved.Ok, saved.Message);

        h.Clock.Set(AuditRecord.Domain.Kst.At(2026, 1, 1, 9, 0));
        h.Svc.PurgeDueDiscipline(h.Clock.UtcNow);

        var row = h.Svc.ListDiscipline(null, null, null).Single();
        Assert.Equal("삭제됨", row.Status);
        Assert.Equal("삭제됨", row.PersonName);
        Assert.Equal("", row.EmployeeNo);
        Assert.Equal("", row.ViolationText);
        Assert.Equal(1, row.Seq);                       // 대장 줄과 순번은 남는다
        Assert.Equal("2020-01-02", row.ViolationDate);  // 날짜도 남는다
    }

    [Theory]
    [InlineData("증빙", "보고서.pdf", "증빙.pdf")]
    [InlineData("증빙.hwp", "보고서.pdf", "증빙.hwp")]
    [InlineData("", "보고서.pdf", "보고서.pdf")]
    [InlineData("   ", "report.XLSX", "report.XLSX")]
    public void Display_name_keeps_an_extension(string shown, string uploaded, string expected) =>
        Assert.Equal(expected, FilePreview.NameWithExtension(shown, uploaded));

    [Theory]
    [InlineData("보고서.pdf", "application/pdf")]
    [InlineData("표.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")]
    [InlineData("문서.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")]
    [InlineData("한글.hwp", "application/x-hwp")]
    [InlineData("묶음.zip", "application/zip")]
    [InlineData("무엇.unknown", "application/octet-stream")]
    public void Content_type_follows_the_extension(string name, string expected) =>
        Assert.Equal(expected, FilePreview.ContentTypeFor(name));

    [Theory]
    [InlineData("증빙서류.pdf", "file.pdf")]
    [InlineData("report-2026.xlsx", "report-2026.xlsx")]
    [InlineData("한글.hwp", "file.hwp")]
    [InlineData("이름없음", "file")]
    public void Ascii_fallback_name_keeps_the_extension(string name, string expected) =>
        Assert.Equal(expected, FilePreview.AsciiFallbackName(name));

    [Fact]
    public void Download_of_an_unknown_binary_still_names_its_type()
    {
        var head = new byte[] { 0x50, 0x4B, 0x03, 0x04 };
        var (type, inline) = FilePreview.Decide("표.xlsx", head);
        Assert.False(inline);
        Assert.Equal("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", type);
    }

    static DisciplineInput Sample(string name, string employeeNo, string violationDate) => new()
    {
        Year = int.Parse(violationDate[..4], CultureInfo.InvariantCulture),
        EmployeeNo = employeeNo,
        PersonName = name,
        Department = "예시부서",
        Position = "대리",
        ViolationDate = violationDate,
        ViolationText = "예시 위반내용",
        RuleText = "예시 내부 기준 제3호",
        SanctionType = "견책",
        SanctionDate = "2026-03-04",
        SanctionTerm = "1개월",
        DecisionBody = "예시 인사위원회",
        DocNo = "예시-인사-2026-001",
        Status = "초안",
        Remark = "예시 비고"
    };

    static DisciplineInput With(DisciplineInput input, string? caseRef, string? status = null, string? sanctionDate = null, int? year = null) => new()
    {
        Id = input.Id,
        Version = input.Version,
        CaseRef = caseRef,
        Year = year ?? input.Year,
        EmployeeNo = input.EmployeeNo,
        PersonName = input.PersonName,
        Department = input.Department,
        Position = input.Position,
        ViolationDate = input.ViolationDate,
        ViolationText = input.ViolationText,
        RuleText = input.RuleText,
        SanctionType = input.SanctionType,
        SanctionDate = sanctionDate ?? input.SanctionDate,
        SanctionTerm = input.SanctionTerm,
        DecisionBody = input.DecisionBody,
        DocNo = input.DocNo,
        Status = status ?? input.Status,
        Remark = input.Remark
    };
}
