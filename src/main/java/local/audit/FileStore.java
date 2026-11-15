package local.audit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;

/** 자료 원문은 데이터베이스에 넣지 않고 files 폴더에 둔다. 암호화된 파일은 암호화된 그대로 둔다. */
@Component
public class FileStore {
    private final Paths2 paths;

    public FileStore(Paths2 paths) { this.paths = paths; }

    public record Stored(String key, long size, String sha256) { }

    public Stored save(String caseId, String attachmentId, String ext, InputStream in) throws IOException {
        if (!Txt.isId(caseId) || !Txt.isId(attachmentId)) throw new IOException("unsafe key");
        String key = caseId + "/" + attachmentId + (ext == null ? "" : ext);
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream din = new DigestInputStream(in, md)) {
                Files.copy(din, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Stored(key, Files.size(target), Txt.hex(md.digest()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Path resolve(String key) {
        if (key == null || !key.matches("[0-9a-f]{32}/[0-9a-f]{32}(\\.[a-z0-9]{1,8})?")) return null;
        return paths.files.resolve(key).normalize();
    }

    public boolean exists(String key) {
        Path p = resolve(key);
        return p != null && Files.isRegularFile(p);
    }

    /** 휴지통에 넣지 않고 지운다. 없으면 지운 것으로 본다. */
    public boolean delete(String key) {
        Path p = resolve(key);
        if (p == null) return true;
        try {
            Files.deleteIfExists(p);
            try { Files.delete(p.getParent()); } catch (IOException ignored) { }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return Txt.hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String contentType(String name) {
        return switch (Txt.ext(name)) {
            case ".pdf" -> "application/pdf";
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            case ".txt", ".log", ".md" -> "text/plain; charset=utf-8";
            case ".csv" -> "text/csv; charset=utf-8";
            case ".rtf" -> "application/rtf";
            case ".doc" -> "application/msword";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xls" -> "application/vnd.ms-excel";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".ppt" -> "application/vnd.ms-powerpoint";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".hwp" -> "application/x-hwp";
            case ".hwpx" -> "application/hwp+zip";
            case ".zip" -> "application/zip";
            case ".7z" -> "application/x-7z-compressed";
            case ".eml" -> "message/rfc822";
            case ".msg" -> "application/vnd.ms-outlook";
            default -> "application/octet-stream";
        };
    }

    /** 화면 안에서 펼쳐 볼 수 있는 종류. 실행될 수 있는 형식은 내려받기만 한다. */
    public static String openKind(String name) {
        return switch (Txt.ext(name)) {
            case ".png", ".jpg", ".jpeg", ".gif", ".webp" -> "image";
            case ".pdf" -> "pdf";
            case ".txt", ".csv", ".md", ".log" -> "text";
            default -> "download";
        };
    }

    /** 표시 이름에 확장자가 없으면 올린 파일의 확장자를 붙인다. */
    public static String nameWithExt(String display, String uploaded) {
        String up = safeName(uploaded);
        String shown = display == null ? "" : display.strip();
        if (shown.isEmpty()) return up;
        shown = safeName(shown);
        if (!Txt.ext(shown).isEmpty()) return shown;
        return shown + Txt.ext(up);
    }

    public static String safeName(String name) {
        String n = name == null ? "" : name;
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
        n = n.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").strip();
        if (n.isEmpty()) n = "파일";
        return n.length() > 120 ? n.substring(0, 112) + Txt.ext(n) : n;
    }
}
