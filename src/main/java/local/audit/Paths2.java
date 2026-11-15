package local.audit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 데이터 폴더 배치: data(데이터베이스), files(자료 원문), inbox(수집함), backup(백업), tmp. */
@Component
public class Paths2 {
    public final Path home;
    public final Path data;
    public final Path files;
    public final Path inbox;
    public final Path backup;
    public final Path tmp;
    public final Path db;

    public Paths2(@Value("${audit.home:}") String configured) throws IOException {
        String base = configured == null || configured.isBlank() ? "var" : configured.trim();
        home = Path.of(base).toAbsolutePath().normalize();
        data = home.resolve("data");
        files = home.resolve("files");
        inbox = home.resolve("inbox");
        backup = home.resolve("backup");
        tmp = home.resolve("tmp");
        for (Path p : new Path[] { data, files, inbox, backup, tmp }) Files.createDirectories(p);
        db = data.resolve("audit.db");
    }
}
