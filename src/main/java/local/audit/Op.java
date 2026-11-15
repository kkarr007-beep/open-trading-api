package local.audit;

/** 한 작업의 결과. */
public class Op {
    public final boolean ok;
    public final int status;
    public final String message;
    public String id;
    public String extra;

    private Op(boolean ok, int status, String message) {
        this.ok = ok;
        this.status = status;
        this.message = message;
    }

    public static Op ok(String message) { return new Op(true, 200, message); }
    public static Op ok(String message, String id) { Op o = ok(message); o.id = id; return o; }
    public static Op fail(int status, String message) { return new Op(false, status, message); }
    public static Op bad(String message) { return fail(422, message); }
    public Op extra(String value) { this.extra = value; return this; }

    public static Db.Abort abort(int status, String message) { return new Db.Abort(fail(status, message)); }
    public static Db.Abort abort(String message) { return new Db.Abort(bad(message)); }
}
