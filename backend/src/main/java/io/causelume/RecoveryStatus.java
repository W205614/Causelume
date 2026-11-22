package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RecoveryStatus {
    private static final Set<String> HEARTBEATS = Set.of("NOT_CONFIGURED","NOT_SENT","SENT","FAILED");
    private final Environment env;

    RecoveryStatus(Environment env) {
        this.env=env;
    }

    record Report(String result,Instant finishedAt,Instant lastSuccessAt,String heartbeat) {}

    @GetMapping("/api/recovery/status")
    Object status(HttpServletRequest req) {
        Access.actor(req).admin();
        String directory=env.getProperty("RECOVERY_STATUS_DIR");
        if(directory==null || directory.isBlank()) return Map.of("configured",false);

        var backup=read(Path.of(directory,"backup"));
        var restore=read(Path.of(directory,"restore"));
        String heartbeat=env.getProperty("RECOVERY_HEARTBEAT_CONFIGURED",Boolean.class,false)
            ? ("NOT_CONFIGURED".equals(backup.heartbeat()) ? "NOT_SENT" : backup.heartbeat())
            : "NOT_CONFIGURED";
        boolean overdue=backup.lastSuccessAt()!=null &&
            backup.lastSuccessAt().isBefore(Instant.now().minus(Duration.ofHours(24)));
        return Map.of("configured",true,"backup",backup,"restore",restore,"backupOverdue",overdue,"heartbeat",heartbeat);
    }

    private Report read(Path file) {
        try {
            var lines=Files.readAllLines(file);
            if(lines.size()!=4 || !Set.of("SUCCEEDED","FAILED").contains(lines.get(0)) || !HEARTBEATS.contains(lines.get(3)))
                throw new Problem(503,"备份或恢复状态记录格式无效");
            Instant finished=Instant.parse(lines.get(1));
            Instant lastSuccess=lines.get(2).isBlank() ? null : Instant.parse(lines.get(2));
            return new Report(lines.get(0),finished,lastSuccess,lines.get(3));
        } catch(NoSuchFileException absent) {
            return new Report("NEVER_RUN",null,null,"NOT_CONFIGURED");
        } catch(IOException | DateTimeParseException invalid) {
            throw new Problem(503,"无法读取备份或恢复状态记录");
        }
    }
}
