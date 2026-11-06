package io.causelume;

import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PostgresMigrationIT {
    @TempDir Path tmp;
    @Test void preservesV5DataSequencesAndRefusesNonemptyTarget() throws Exception {
        Path base=tmp.resolve("legacy");
        var h2=new DriverManagerDataSource("jdbc:h2:file:"+base,"","");
        Flyway.configure().dataSource(h2).target("5").load().migrate();
        String hash=new BCryptPasswordEncoder().encode("legacy-local-pass");
        var legacy=new JdbcTemplate(h2);
        legacy.update("insert into accounts(username,password_hash,role) values ('admin',?,'ADMIN')",hash);
        legacy.update("insert into alerts(service_id,source,external_id,severity,title,occurred_at) values(1,'probe','key','WARNING','CPU','2026-06-10 17:12:30+08')");
        legacy.update("insert into tickets(service_id,alert_id,title,status,creator_id,request_id) values (1,1,'旧工单','OPEN',1,?)",UUID.randomUUID());
        legacy.update("insert into timeline(ticket_id,action,note,actor) values (1,'NOTE','旧记录','admin')");
        legacy.update("insert into tasks(ticket_id,creator_id,request_id,status,analysis,feedback) values (1,1,?,'SUCCEEDED','{}','反馈')",UUID.randomUUID());
        legacy.update("insert into audit(actor,action,target_id) values ('admin','CREATE',1)");
        legacy.execute("alter table tickets alter column id restart with 27");
        Path dump=tmp.resolve("backup.jsonl");
        OfflineStorage.run(new String[]{"storage","export-h2",base.toString(),dump.toString()});
        var corrupt=tmp.resolve("bad.jsonl");
        Files.writeString(corrupt,Files.readString(dump).replace("旧记录","已篡改"));
        assertThrows(IllegalArgumentException.class,()->OfflineStorage.run(new String[]{"storage","import-pg",corrupt.toString()}));
        OfflineStorage.run(new String[]{"storage","import-pg",dump.toString()});
        assertThrows(IllegalStateException.class,()->OfflineStorage.run(new String[]{"storage","import-pg",dump.toString()}));
        var runtime=new DriverManagerDataSource(System.getenv("APP_DB_URL"),"causelume_app",System.getenv("APP_DB_PASSWORD"));
        var db=new JdbcTemplate(runtime);
        assertEquals(hash,db.queryForObject("select password_hash from accounts where id=1",String.class));
        assertEquals("反馈",db.queryForObject("select feedback from tasks where id=1",String.class));
        assertEquals("旧记录",db.queryForObject("select note from timeline where id=1",String.class));
        assertEquals(27,db.queryForObject("insert into tickets(service_id,title,status,creator_id,request_id) values(1,'new','OPEN',1,?) returning id",Long.class,UUID.randomUUID()));
        assertFalse(db.queryForObject("select has_schema_privilege(current_user,'public','CREATE')",Boolean.class));
        try(var connection=runtime.getConnection()) { OfflineStorage.recoverAdmin(connection,"admin","changed-by-local-cli"); }
        assertTrue(new BCryptPasswordEncoder().matches("changed-by-local-cli",db.queryForObject("select password_hash from accounts where id=1",String.class)));
        assertEquals(1,db.queryForObject("select auth_version from accounts where id=1",Long.class));
    }
}
