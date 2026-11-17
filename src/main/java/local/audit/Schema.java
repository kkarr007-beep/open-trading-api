package local.audit;

import java.sql.Statement;

/** 표 구조. 없는 것만 만든다. */
final class Schema {
    private Schema() { }

    static void create(Db db) {
        db.tx(conn -> {
            try (Statement st = conn.createStatement()) {
                for (String sql : SQL.split(";\\s*\n")) {
                    if (!sql.isBlank()) st.execute(sql);
                }
            }
            if (Db.intOf(conn, "SELECT COUNT(*) FROM pragma_table_info('AttachmentText') WHERE name='Html'") == 0)
                Db.update(conn, "ALTER TABLE AttachmentText ADD COLUMN Html TEXT NULL");
            return null;
        });
        try {
            db.read(conn -> {
                try (Statement st = conn.createStatement()) { st.execute("PRAGMA journal_mode=WAL"); }
                return null;
            });
        } catch (RuntimeException ignored) {
            // WAL을 못 써도 동작한다.
        }
    }

    static final String SQL = """
        CREATE TABLE IF NOT EXISTS Meta (Key TEXT PRIMARY KEY, Value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS Cases (
          CaseId TEXT PRIMARY KEY,
          Year INTEGER NOT NULL,
          Category TEXT NOT NULL CHECK (Category IN ('업무진단','준법감사')),
          Title TEXT NOT NULL,
          Target TEXT NOT NULL,
          Owner TEXT NOT NULL DEFAULT '',
          Shelf TEXT NOT NULL DEFAULT '',
          BinderNo TEXT NOT NULL DEFAULT '',
          DocumentNo TEXT NOT NULL DEFAULT '',
          SummaryLine TEXT NOT NULL DEFAULT '',
          Body TEXT NULL,
          Confirmer TEXT NULL,
          RetentionYears INTEGER NULL CHECK (RetentionYears IS NULL OR (RetentionYears > 0 AND RetentionYears <= 100)),
          FirstConfirmedOn TEXT NULL,
          ConfirmedOn TEXT NULL,
          EverConfirmed INTEGER NOT NULL DEFAULT 0,
          Hold INTEGER NOT NULL DEFAULT 0,
          HoldReason TEXT NULL,
          HoldSetAt TEXT NULL,
          HoldReleasedAt TEXT NULL,
          Phase TEXT NOT NULL CHECK (Phase IN ('초안','확정','조치중','종결')),
          PurgedAt TEXT NULL,
          BodiesClearedAt TEXT NULL,
          Version INTEGER NOT NULL DEFAULT 1,
          CreatedAt TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS Actions (
          ActionId TEXT PRIMARY KEY,
          CaseId TEXT NOT NULL REFERENCES Cases(CaseId),
          Sequence INTEGER NOT NULL,
          Title TEXT NOT NULL,
          Type TEXT NOT NULL CHECK (Type IN ('지적','개선요구','권고')),
          TargetDept TEXT NOT NULL,
          RequestedOn TEXT NOT NULL,
          ReplyDueOn TEXT NOT NULL,
          Content TEXT NULL,
          ActionState TEXT NOT NULL CHECK (ActionState IN ('요구중','회신수신','이행완료')),
          UNIQUE (CaseId, Sequence)
        );
        CREATE TABLE IF NOT EXISTS Fulfillments (
          FulfillmentId TEXT PRIMARY KEY,
          ActionId TEXT NOT NULL REFERENCES Actions(ActionId),
          At TEXT NOT NULL,
          Actor TEXT NOT NULL,
          FromState TEXT NOT NULL,
          ToState TEXT NOT NULL,
          Kind TEXT NOT NULL CHECK (Kind IN ('회신','이행확인')),
          ReplyBody TEXT NULL,
          CheckBody TEXT NULL,
          HistorySeq INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS Attachments (
          AttachmentId TEXT PRIMARY KEY,
          CaseId TEXT NOT NULL REFERENCES Cases(CaseId),
          Kind TEXT NOT NULL CHECK (Kind IN ('결과원문','증빙','회신첨부')),
          DisplayName TEXT NOT NULL,
          StoragePath TEXT NULL,
          SizeBytes INTEGER NOT NULL DEFAULT 0,
          Sha256 TEXT NULL,
          RegisteredAt TEXT NOT NULL,
          RegisteredBy TEXT NOT NULL DEFAULT '',
          DeletedAt TEXT NULL,
          StorageState TEXT NOT NULL CHECK (StorageState IN ('Present','Missing','Deleted')),
          Encrypted INTEGER NOT NULL DEFAULT 0,
          ReadState TEXT NOT NULL DEFAULT '안읽음',
          ReadMethod TEXT NOT NULL DEFAULT '',
          ReadNote TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS AttachmentText (
          AttachmentId TEXT PRIMARY KEY REFERENCES Attachments(AttachmentId),
          Body TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS History (
          Seq INTEGER PRIMARY KEY,
          CaseId TEXT NULL,
          At TEXT NOT NULL,
          Actor TEXT NULL,
          Action TEXT NOT NULL,
          TargetKind TEXT NOT NULL,
          TargetId TEXT NULL,
          Summary TEXT NOT NULL,
          PrevHash BLOB NOT NULL,
          RowHash BLOB NOT NULL
        );
        CREATE TABLE IF NOT EXISTS CategoryCodes (
          Category TEXT PRIMARY KEY,
          Code TEXT NOT NULL UNIQUE,
          Used INTEGER NOT NULL DEFAULT 0
        );
        INSERT OR IGNORE INTO CategoryCodes(Category, Code, Used) VALUES ('업무진단','01',0), ('준법감사','02',0);
        CREATE TABLE IF NOT EXISTS DocCounters (
          Year INTEGER NOT NULL,
          Code TEXT NOT NULL,
          LastSeq INTEGER NOT NULL,
          PRIMARY KEY (Year, Code)
        );
        CREATE TABLE IF NOT EXISTS Discipline (
          DisciplineId TEXT PRIMARY KEY,
          Seq INTEGER NOT NULL,
          CaseId TEXT NULL,
          Year INTEGER NOT NULL,
          EmployeeNo TEXT NOT NULL DEFAULT '',
          PersonName TEXT NOT NULL,
          Department TEXT NOT NULL DEFAULT '',
          Position TEXT NOT NULL DEFAULT '',
          ViolationDate TEXT NOT NULL,
          ViolationText TEXT NOT NULL,
          RuleText TEXT NOT NULL DEFAULT '',
          SanctionType TEXT NOT NULL,
          SanctionDate TEXT NOT NULL DEFAULT '',
          SanctionTerm TEXT NOT NULL DEFAULT '',
          DecisionBody TEXT NOT NULL DEFAULT '',
          DocNo TEXT NOT NULL DEFAULT '',
          Status TEXT NOT NULL,
          Remark TEXT NOT NULL DEFAULT '',
          Hold INTEGER NOT NULL DEFAULT 0,
          RetentionYears INTEGER NOT NULL DEFAULT 5,
          ConfirmedOn TEXT NULL,
          Version INTEGER NOT NULL DEFAULT 1,
          CreatedAt TEXT NOT NULL,
          CreatedBy TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS Inbox (
          InboxId TEXT PRIMARY KEY,
          SourcePath TEXT NOT NULL,
          FileName TEXT NOT NULL,
          SizeBytes INTEGER NOT NULL,
          ModifiedAt INTEGER NOT NULL,
          Sha256 TEXT NOT NULL,
          DetectedAt TEXT NOT NULL,
          State TEXT NOT NULL CHECK (State IN ('대기','등록됨','제외','실패')),
          Encrypted INTEGER NOT NULL DEFAULT 0,
          ReadState TEXT NOT NULL DEFAULT '안읽음',
          ReadMethod TEXT NOT NULL DEFAULT '',
          ReadNote TEXT NOT NULL DEFAULT '',
          Body TEXT NULL,
          Suggestion TEXT NULL,
          AppliedCaseId TEXT NULL,
          AppliedNote TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS Backups (
          BackupId TEXT PRIMARY KEY,
          At TEXT NOT NULL,
          Location TEXT NOT NULL,
          State TEXT NOT NULL,
          Note TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS CaseRich (CaseId TEXT PRIMARY KEY, Html TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS InboxRich (InboxId TEXT PRIMARY KEY, Html TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS WorkItems (
          Id TEXT PRIMARY KEY,
          Kind TEXT NOT NULL,
          Title TEXT NOT NULL DEFAULT '',
          Due TEXT NOT NULL DEFAULT '',
          OnDate TEXT NOT NULL DEFAULT '',
          Ord INTEGER NOT NULL DEFAULT 0,
          Data TEXT NOT NULL,
          UpdatedAt TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS RuleRegs (
          RegId TEXT PRIMARY KEY,
          Title TEXT NOT NULL,
          CategoryId TEXT NOT NULL DEFAULT '',
          Ord INTEGER NOT NULL DEFAULT 0,
          Data TEXT NOT NULL,
          UpdatedAt TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS IX_Work_Kind ON WorkItems(Kind, Due);
        CREATE INDEX IF NOT EXISTS IX_Cases_List ON Cases(Year, Category, Phase);
        CREATE INDEX IF NOT EXISTS IX_Actions_Due ON Actions(ReplyDueOn, ActionState);
        CREATE INDEX IF NOT EXISTS IX_History_Case ON History(CaseId, Seq);
        CREATE INDEX IF NOT EXISTS IX_Att_Case ON Attachments(CaseId);
        CREATE INDEX IF NOT EXISTS IX_Att_Sha ON Attachments(Sha256);
        CREATE INDEX IF NOT EXISTS IX_Inbox_Src ON Inbox(SourcePath, SizeBytes, ModifiedAt);
        CREATE INDEX IF NOT EXISTS IX_Inbox_Sha ON Inbox(Sha256)
        """;
}
