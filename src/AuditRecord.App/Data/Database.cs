using AuditRecord.Storage;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Data;

public sealed class Database
{
    readonly string _path;
    readonly string _connectionString;

    public Database(AppPaths paths)
    {
        _path = paths.DatabasePath;
        var builder = new SqliteConnectionStringBuilder
        {
            DataSource = _path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared,
            Pooling = false
        };
        _connectionString = builder.ToString();
    }

    public SqliteConnection Open()
    {
        var conn = new SqliteConnection(_connectionString);
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = """
            PRAGMA foreign_keys=ON;
            PRAGMA busy_timeout=5000;
            PRAGMA secure_delete=ON;
            PRAGMA synchronous=FULL;
            PRAGMA temp_store=MEMORY;
            """;
        cmd.ExecuteNonQuery();
        return conn;
    }

    public void Initialize(string mode)
    {
        var created = !File.Exists(_path);
        using var conn = Open();
        using (var wal = conn.CreateCommand())
        {
            wal.CommandText = "PRAGMA journal_mode=WAL;";
            wal.ExecuteNonQuery();
        }
        using (var check = conn.CreateCommand())
        {
            check.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='SystemMeta';";
            var exists = Convert.ToInt32(check.ExecuteScalar(), System.Globalization.CultureInfo.InvariantCulture) > 0;
            if (!exists)
            {
                using var create = conn.CreateCommand();
                create.CommandText = SchemaSql;
                create.ExecuteNonQuery();
                using var meta = conn.CreateCommand();
                meta.CommandText = """
                    INSERT INTO SystemMeta(
                      Id, SchemaVersion, ChainTailSeq, ChainTailHash, ChainRowCount, IntegrityState,
                      PolicyAcknowledged, BackupRetentionDays, BackupRetentionGenerations, Mode, SetupComplete)
                    VALUES (1, 1, 0, $hash, 0, 'ok', 0, 7, 7, $mode, 0);
                    """;
                meta.Parameters.Add("$hash", SqliteType.Blob).Value = HashChain.Genesis();
                meta.Parameters.AddWithValue("$mode", mode);
                meta.ExecuteNonQuery();
            }
        }
        int schema;
        string storedMode;
        using (var ver = conn.CreateCommand())
        {
            ver.CommandText = "SELECT SchemaVersion, Mode FROM SystemMeta WHERE Id=1;";
            using var reader = ver.ExecuteReader();
            if (!reader.Read())
                throw new InvalidOperationException("database metadata is missing");
            schema = reader.GetInt32(0);
            storedMode = reader.GetString(1);
        }
        if (schema != 1)
            throw new InvalidOperationException("unsupported schema");
        using (var index = conn.CreateCommand())
        {
            index.CommandText = """
                CREATE UNIQUE INDEX IF NOT EXISTS UX_DeleteJobs_Open
                  ON DeleteJobs(CaseId, Kind, IFNULL(TargetAttachmentId, ''))
                  WHERE Status <> 'Complete';
                """;
            try { index.ExecuteNonQuery(); }
            catch (SqliteException) { }
        }
        using (var incident = conn.CreateCommand())
        {
            incident.CommandText = """
                CREATE TABLE IF NOT EXISTS IntegrityIncidents (
                  IncidentId TEXT PRIMARY KEY,
                  DetectedAt TEXT NOT NULL,
                  ReasonCode TEXT NOT NULL,
                  SummarySafe TEXT NOT NULL
                );
                """;
            incident.ExecuteNonQuery();
        }
        if (!string.Equals(storedMode, mode, StringComparison.Ordinal))
            throw new StartupFailure("데이터베이스 모드가 이 프로세스와 다릅니다. Production과 Demo를 같은 데이터 폴더에서 바꾸지 마세요.");
        _ = created;
    }

    public void Checkpoint(bool vacuum)
    {
        using var conn = Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "PRAGMA wal_checkpoint(TRUNCATE);";
        cmd.ExecuteNonQuery();
        if (!vacuum) return;
        using var vac = conn.CreateCommand();
        vac.CommandText = "VACUUM;";
        vac.ExecuteNonQuery();
    }

    public static SqliteCommand Cmd(SqliteConnection conn, string sql, params (string Name, object? Value)[] args)
    {
        var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        foreach (var (name, value) in args)
        {
            if (value is byte[] bytes)
            {
                var p = cmd.Parameters.Add(name, SqliteType.Blob);
                p.Value = bytes;
            }
            else
            {
                cmd.Parameters.AddWithValue(name, value ?? DBNull.Value);
            }
        }
        return cmd;
    }

    public const string SchemaSql = """
        CREATE TABLE Users (
          UserId TEXT PRIMARY KEY,
          Login TEXT NOT NULL UNIQUE,
          DisplayName TEXT NOT NULL,
          Role TEXT NOT NULL CHECK (Role IN ('담당','파트장','관리자')),
          PasswordHash TEXT NOT NULL,
          Enabled INTEGER NOT NULL CHECK (Enabled IN (0,1)),
          Stamp TEXT NOT NULL,
          MustChange INTEGER NOT NULL CHECK (MustChange IN (0,1)),
          LockoutUntil TEXT NULL,
          FailedCount INTEGER NOT NULL DEFAULT 0,
          Version INTEGER NOT NULL CHECK (Version > 0)
        );
        CREATE TABLE Cases (
          CaseId TEXT PRIMARY KEY,
          Year INTEGER NOT NULL,
          Category TEXT NOT NULL CHECK (Category IN ('업무진단','준법감사')),
          Title TEXT NOT NULL,
          Target TEXT NOT NULL,
          OwnerUserId TEXT NOT NULL REFERENCES Users(UserId),
          Shelf TEXT NOT NULL DEFAULT '',
          BinderNo TEXT NOT NULL DEFAULT '',
          RetentionYears INTEGER NULL CHECK (RetentionYears IS NULL OR (RetentionYears > 0 AND RetentionYears <= 100)),
          FirstConfirmedOn TEXT NULL,
          ConfirmedOn TEXT NULL,
          EverConfirmed INTEGER NOT NULL CHECK (EverConfirmed IN (0,1)),
          Hold INTEGER NOT NULL CHECK (Hold IN (0,1)),
          HoldReason TEXT NULL,
          HoldSetAt TEXT NULL,
          HoldReleasedAt TEXT NULL,
          Phase TEXT NOT NULL CHECK (Phase IN ('초안','확정','조치중','종결')),
          PurgedAt TEXT NULL,
          BodiesClearedAt TEXT NULL,
          PurgeIncomplete INTEGER NOT NULL DEFAULT 0 CHECK (PurgeIncomplete IN (0,1)),
          Version INTEGER NOT NULL CHECK (Version > 0),
          CreatedAt TEXT NOT NULL
        );
        CREATE TABLE Results (
          CaseId TEXT PRIMARY KEY REFERENCES Cases(CaseId),
          DocumentNo TEXT NOT NULL DEFAULT '',
          SummaryLine TEXT NOT NULL DEFAULT '',
          Body TEXT NULL,
          ConfirmerUserId TEXT NULL REFERENCES Users(UserId)
        );
        CREATE TABLE Actions (
          ActionId TEXT PRIMARY KEY,
          CaseId TEXT NOT NULL REFERENCES Cases(CaseId),
          Sequence INTEGER NOT NULL CHECK (Sequence > 0),
          Title TEXT NOT NULL,
          Type TEXT NOT NULL CHECK (Type IN ('지적','개선요구','권고')),
          TargetDept TEXT NOT NULL,
          RequestedOn TEXT NOT NULL,
          ReplyDueOn TEXT NOT NULL,
          Content TEXT NULL,
          ActionState TEXT NOT NULL CHECK (ActionState IN ('요구중','회신수신','이행완료')),
          UNIQUE (CaseId, Sequence)
        );
        CREATE TABLE Fulfillments (
          FulfillmentId TEXT PRIMARY KEY,
          ActionId TEXT NOT NULL REFERENCES Actions(ActionId),
          At TEXT NOT NULL,
          ActorId TEXT NOT NULL REFERENCES Users(UserId),
          FromState TEXT NOT NULL,
          ToState TEXT NOT NULL,
          Kind TEXT NOT NULL CHECK (Kind IN ('회신','이행확인')),
          ReplyBody TEXT NULL,
          CheckBody TEXT NULL,
          HistorySeq INTEGER NOT NULL
        );
        CREATE TABLE Attachments (
          AttachmentId TEXT PRIMARY KEY,
          CaseId TEXT NOT NULL REFERENCES Cases(CaseId),
          Kind TEXT NOT NULL CHECK (Kind IN ('결과원문','증빙','회신첨부')),
          DisplayName TEXT NOT NULL,
          StorageKey TEXT NULL UNIQUE,
          RegisteredAt TEXT NOT NULL,
          RegisteredBy TEXT NOT NULL REFERENCES Users(UserId),
          DeletedAt TEXT NULL,
          StorageState TEXT NOT NULL CHECK (StorageState IN ('Present','DeletePending','Missing','Deleted'))
        );
        CREATE TABLE History (
          Seq INTEGER PRIMARY KEY,
          CaseId TEXT NULL,
          At TEXT NOT NULL,
          ActorId TEXT NULL,
          Action TEXT NOT NULL,
          TargetKind TEXT NOT NULL,
          TargetId TEXT NULL,
          SummarySafe TEXT NOT NULL,
          CanonicalVersion INTEGER NOT NULL,
          PrevHash BLOB NOT NULL,
          RowHash BLOB NOT NULL,
          WorkKey TEXT NULL UNIQUE
        );
        CREATE TABLE SystemMeta (
          Id INTEGER PRIMARY KEY CHECK (Id = 1),
          SchemaVersion INTEGER NOT NULL,
          ChainTailSeq INTEGER NOT NULL,
          ChainTailHash BLOB NOT NULL,
          ChainRowCount INTEGER NOT NULL,
          IntegrityState TEXT NOT NULL,
          PolicyAcknowledged INTEGER NOT NULL,
          PolicyAcknowledgedAt TEXT NULL,
          PolicyAcknowledgedBy TEXT NULL,
          BackupRetentionDays INTEGER NOT NULL,
          BackupRetentionGenerations INTEGER NOT NULL,
          Mode TEXT NOT NULL,
          SetupComplete INTEGER NOT NULL
        );
        CREATE TABLE DeleteJobs (
          JobId TEXT PRIMARY KEY,
          CaseId TEXT NOT NULL REFERENCES Cases(CaseId),
          Kind TEXT NOT NULL CHECK (Kind IN ('Expiry','RemoveDraft','EarlyFile')),
          TargetAttachmentId TEXT NULL,
          Generation INTEGER NOT NULL,
          Status TEXT NOT NULL CHECK (Status IN ('Pending','Running','RetryWait','Paused','Complete')),
          ApprovedActorId TEXT NULL,
          ReasonSafe TEXT NULL,
          Attempt INTEGER NOT NULL,
          NextAttemptAt TEXT NULL,
          ErrorCode TEXT NULL,
          LeaseUntil TEXT NULL,
          CreatedAt TEXT NOT NULL,
          CompletedAt TEXT NULL
        );
        CREATE TABLE DeleteJobItems (
          JobId TEXT NOT NULL REFERENCES DeleteJobs(JobId),
          AttachmentId TEXT NOT NULL,
          StorageKey TEXT NULL,
          Status TEXT NOT NULL,
          ActualDeletedAt TEXT NULL,
          ErrorCode TEXT NULL,
          PRIMARY KEY (JobId, AttachmentId)
        );
        CREATE TABLE CommandReceipts (
          RequestId TEXT PRIMARY KEY,
          ActorId TEXT NOT NULL,
          CaseId TEXT NULL,
          CommandKind TEXT NOT NULL,
          ExpectedVersion INTEGER NULL,
          OutcomeCode TEXT NOT NULL,
          HttpStatus INTEGER NOT NULL,
          ResultVersion INTEGER NULL,
          MessageKey TEXT NOT NULL,
          CompletedAt TEXT NOT NULL
        );
        CREATE TABLE BackupOperations (
          OperationId TEXT PRIMARY KEY,
          State TEXT NOT NULL,
          At TEXT NOT NULL,
          ActorId TEXT NOT NULL,
          DestinationAlias TEXT NOT NULL,
          ErrorCode TEXT NULL,
          BackupId TEXT NULL
        );
        CREATE INDEX IX_Cases_List ON Cases(Year, Category, Phase);
        CREATE INDEX IX_Actions_Due ON Actions(ReplyDueOn, ActionState);
        CREATE INDEX IX_History_Case ON History(CaseId, Seq);
        CREATE INDEX IX_Jobs_Status ON DeleteJobs(Status, NextAttemptAt);
        CREATE TABLE IF NOT EXISTS IntegrityIncidents (
          IncidentId TEXT PRIMARY KEY,
          DetectedAt TEXT NOT NULL,
          ReasonCode TEXT NOT NULL,
          SummarySafe TEXT NOT NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS UX_DeleteJobs_Open
          ON DeleteJobs(CaseId, Kind, IFNULL(TargetAttachmentId, ''))
          WHERE Status <> 'Complete';
        """;
}

public sealed class UnitOfWork : IDisposable
{
    public SqliteConnection Conn { get; }
    bool _done;

    public UnitOfWork(SqliteConnection conn)
    {
        Conn = conn;
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "BEGIN IMMEDIATE;";
        cmd.ExecuteNonQuery();
    }

    public void Commit()
    {
        using var cmd = Conn.CreateCommand();
        cmd.CommandText = "COMMIT;";
        cmd.ExecuteNonQuery();
        _done = true;
    }

    public void Dispose()
    {
        if (!_done)
        {
            try
            {
                using var cmd = Conn.CreateCommand();
                cmd.CommandText = "ROLLBACK;";
                cmd.ExecuteNonQuery();
            }
            catch (SqliteException)
            {
                // connection already unusable
            }
        }
        Conn.Dispose();
    }
}
