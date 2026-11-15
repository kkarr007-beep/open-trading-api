package local.audit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

/** SQLite 파일 하나. 쓰기는 한 번에 하나씩만 한다. */
@Component
public class Db {
    /** 트랜잭션 안에서 실패를 알리고 되돌릴 때 쓴다. */
    public static class Abort extends RuntimeException {
        public final Op op;
        public Abort(Op op) { super(op.message); this.op = op; }
    }

    public interface Work<T> { T run(Connection conn) throws SQLException; }

    private final String url;
    private final ReentrantLock writeLock = new ReentrantLock();

    public Db(Paths2 paths) {
        this.url = "jdbc:sqlite:" + paths.db;
        Schema.create(this);
    }

    public Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA secure_delete=ON");
        }
        return conn;
    }

    /** 읽기. */
    public <T> T read(Work<T> work) {
        try (Connection conn = open()) {
            return work.run(conn);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 쓰기. 예외가 나면 모두 되돌린다. */
    public <T> T tx(Work<T> work) {
        writeLock.lock();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (RuntimeException | SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) { }
                if (e instanceof RuntimeException r) throw r;
                throw new IllegalStateException(e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            writeLock.unlock();
        }
    }

    /** 실패(Abort)를 결과 값으로 바꿔 주는 쓰기. */
    public Op run(Work<Op> work) {
        try {
            return tx(work);
        } catch (Abort a) {
            return a.op;
        }
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return read(conn -> rows(conn, sql, args));
    }

    public Map<String, Object> row(String sql, Object... args) {
        return read(conn -> row(conn, sql, args));
    }

    public static List<Map<String, Object>> rows(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                List<Map<String, Object>> list = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) v = l.intValue();
                        row.put(meta.getColumnLabel(i), v);
                    }
                    list.add(row);
                }
                return list;
            }
        }
    }

    public static Map<String, Object> row(Connection conn, String sql, Object... args) throws SQLException {
        List<Map<String, Object>> list = rows(conn, sql, args);
        return list.isEmpty() ? null : list.get(0);
    }

    public static int update(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    public static int intOf(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object v = args[i];
            if (v instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
            else if (v instanceof byte[] bytes) ps.setBytes(i + 1, bytes);
            else ps.setObject(i + 1, v);
        }
    }
}
