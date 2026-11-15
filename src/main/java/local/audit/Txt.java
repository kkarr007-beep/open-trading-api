package local.audit;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** 입력 다듬기와 작은 도구. */
public final class Txt {
    private Txt() { }

    /** 앞뒤 공백을 떼고 길이와 제어문자를 본다. 맞지 않으면 null. */
    public static String clean(String value, int max, boolean multiline) {
        if (value == null) return "";
        String s = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (!multiline) s = s.replace('\n', ' ').strip();
        if (s.length() > max) return null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' && multiline) continue;
            if (ch == '\t') continue;
            if (Character.isISOControl(ch)) return null;
        }
        return s;
    }

    /** 길면 잘라서라도 넣는다. 자동 등록에서 쓴다. */
    public static String fit(String value, int max, boolean multiline) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        String s = value.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n') sb.append(multiline ? '\n' : ' ');
            else if (ch == '\t') sb.append(multiline ? '\t' : ' ');
            else if (!Character.isISOControl(ch)) sb.append(ch);
        }
        String out = sb.toString().strip();
        if (!multiline) out = out.replaceAll("\\s{2,}", " ");
        return out.length() > max ? out.substring(0, max).strip() : out;
    }

    public static boolean blank(Object s) { return s == null || s.toString().isBlank(); }
    public static String str(Object s) { return s == null ? "" : s.toString(); }

    public static String like(String term) {
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static String newId() { return UUID.randomUUID().toString().replace("-", ""); }

    public static boolean isId(String id) { return id != null && id.matches("[0-9a-f]{32}"); }

    public static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Integer intOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (dot <= slash + 0 || dot == name.length() - 1) return "";
        String e = name.substring(dot).toLowerCase();
        return e.length() <= 8 && e.matches("\\.[a-z0-9]+") ? e : "";
    }

    public static String stem(String name) {
        String e = ext(name);
        String base = name == null ? "" : name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        base = base.substring(slash + 1);
        return e.isEmpty() ? base : base.substring(0, base.length() - e.length());
    }

    /** 성명의 가운데를 가린다. */
    public static String mask(String name) {
        String t = name == null ? "" : name.strip();
        if (t.length() <= 1) return "*";
        char[] c = t.toCharArray();
        if (c.length == 2) { c[1] = '*'; return new String(c); }
        for (int i = 1; i < c.length - 1; i++) c[i] = '*';
        return new String(c);
    }

    public static String csv(Object value) {
        String s = str(value);
        // 표 계산 프로그램이 수식으로 읽지 않게 한다.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
