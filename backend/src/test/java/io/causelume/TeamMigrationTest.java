package io.causelume;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class TeamMigrationTest {
    @TempDir Path directory;

    @Test void migratesAndRecoversHistoricalH2Workspace() throws Exception {
        Path base=directory.resolve("workspace");
        var source=new DriverManagerDataSource("jdbc:h2:file:"+base,"sa","");
        Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/common").target("9").load().migrate();
        seed(new JdbcTemplate(source));
        Path restored=directory.resolve("restored");
        Files.copy(Path.of(base+".mv.db"),Path.of(restored+".mv.db"));
        migrate(source,"migration","latest");
        var db=new JdbcTemplate(source);
        assertMigration(db);
        var restore=new DriverManagerDataSource("jdbc:h2:file:"+restored+";IFEXISTS=TRUE","sa","");
        migrate(restore,"migration","9");
        var old=new JdbcTemplate(restore);
        assertEquals("旧工单",old.queryForObject("select title from tickets where id=1",String.class));
        assertEquals("旧复盘",old.queryForObject("select review from tickets where id=1",String.class));
        assertEquals(9,old.queryForObject("select count(*) from \"flyway_schema_history\" where \"version\" is not null",Integer.class));
    }

    static void migrate(DriverManagerDataSource db,String profile,String target) {
        Flyway.configure().dataSource(db).locations("classpath:db/"+profile,"classpath:db/common").target(target).load().migrate();
    }

    static void seed(JdbcTemplate db) {
        db.update("insert into accounts(username,password_hash,role) values ('legacy','fixture','OPERATOR')");
        db.update("insert into alerts(service_id,source,external_id,severity,title,occurred_at) values (1,'test','history','INFO','旧告警',current_timestamp)");
        db.update("insert into tickets(service_id,alert_id,title,status,assignee_id,creator_id,request_id,review) values (1,1,'旧工单','CLOSED',1,1,?,'旧复盘')",UUID.randomUUID());
        db.update("insert into tasks(ticket_id,creator_id,request_id,status,analysis) values (1,1,?,'SUCCEEDED','{}')",UUID.randomUUID());
        db.update("insert into improvement_items(ticket_id,title,owner_id,due_date) values (1,'旧改进项',1,current_date)");
        db.update("insert into notifications(recipient_id,ticket_id,event_type,message,mail_status) values (1,1,'CLOSE','旧通知','NO_ADDRESS')");
        db.execute("alter table tickets alter column id restart with 31");
    }

    static void assertMigration(JdbcTemplate db) {
        assertEquals("default",db.queryForObject("""
            select t.slug from teams t join services s on s.team_id=t.id join tickets k on k.service_id=s.id
              join team_members m on m.team_id=t.id and m.account_id=k.assignee_id
              join tasks i on i.ticket_id=k.id join improvement_items j on j.ticket_id=k.id
              join notifications n on n.ticket_id=k.id where k.id=1 and k.review='旧复盘' and j.title='旧改进项' and n.message='旧通知'
            """,String.class));
        db.update("insert into teams(slug,name) values ('two','第二团队')");
        db.update("insert into services(name,description,resource,team_id) values ('checkout','同名','test',2)");
        assertEquals(2,db.queryForObject("select count(*) from services where name='checkout'",Integer.class));
        db.update("insert into tickets(service_id,title,status,creator_id,request_id) values (1,'新工单','OPEN',1,?)",UUID.randomUUID());
        assertEquals(31,db.queryForObject("select id from tickets where title='新工单'",Integer.class));
    }
}
