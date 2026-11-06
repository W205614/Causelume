package io.causelume;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkerRecoveryTest {
    @Test void upgradeRecoverAndFenceLateResults() {
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl("jdbc:h2:mem:"+UUID.randomUUID());
            Flyway.configure().dataSource(source).target("2").load().migrate();
            var store = new Store(new JdbcTemplate(source));
            store.db.update("insert into accounts(username,password_hash,role) values ('admin','test','ADMIN')");
            store.db.update("insert into tickets(service_id,title,status,creator_id,request_id) values (1,'test','ASSIGNED',1,random_uuid())");
            long legacy = task(store,"SUCCEEDED");
            long interrupted = task(store,"RUNNING");
            long unexpected = task(store,"PENDING");
            Flyway.configure().dataSource(source).load().migrate();
            assertNull(store.one("select * from tasks where id=?",legacy).get("finishedAt"));
            var calls = new AtomicInteger();
            var worker = new InvestigationWorker(store, new InternalClient(null,new ObjectMapper()) {
                @Override JsonNode call(String base,String token,String path,Map<String,?> body,int timeout) {
                    int call = calls.incrementAndGet();
                    if(call==1) throw new IllegalStateException("test-only private detail");
                    if(call==3) {
                        // Simulate another recovery owner settling this call before the reply.
                        store.db.update("update tasks set status='FAILED',finished_at=current_timestamp,error_code='RESTART_INTERRUPTED' where id=?",body.get("taskId"));
                    }
                    if(call==4) {
                        store.db.update("update tasks set started_at=dateadd(second,-121,current_timestamp) where id=?",body.get("taskId"));
                    }
                    var evidence=Map.of("source","test","detail",call==7 ? "x".repeat(2049) : "test");
                    return new ObjectMapper().valueToTree(Map.of("taskId",body.get("taskId"),"mode","offline",
                            "possibleCause",call==5 ? "x".repeat(2001) : "test","recommendation","test","uncertainty","test",
                            "evidence",java.util.Collections.nCopies(call==6 ? 11 : 1,evidence)));
                }
            });
            worker.recover();
            assertEquals("RESTART_INTERRUPTED",field(store,interrupted,"error_code"));
            worker.processNext();
            assertEquals("WORKER_ERROR",field(store,unexpected,"error_code"));
            assertFalse(field(store,unexpected,"error").contains("private"));
            var row=store.one("select started_at,finished_at from tasks where id=?",unexpected);
            assertNotNull(row.get("startedAt"));
            assertNotNull(row.get("finishedAt"));

            long storageFailure = task(store,"PENDING");
            store.db.update("alter table tasks add constraint simulate_write_outage check (id<>"+storageFailure+" or status<>'SUCCEEDED')");
            assertThrows(DataAccessException.class,worker::processNext);
            assertEquals("RUNNING",field(store,storageFailure,"status"));
            assertNull(store.one("select finished_at from tasks where id=?",storageFailure).get("finishedAt"));
            store.db.update("alter table tasks drop constraint simulate_write_outage");
            store.db.update("update tasks set started_at=dateadd(second,-121,current_timestamp) where id=?",storageFailure);
            worker.processNext();
            assertEquals("DEADLINE_EXCEEDED",field(store,storageFailure,"error_code"));
            long late = task(store,"PENDING");
            worker.processNext();
            assertEquals("RESTART_INTERRUPTED",field(store,late,"error_code"));
            assertNull(store.one("select analysis from tasks where id=?",late).get("analysis"));
            long stale = task(store,"PENDING");
            worker.processNext();
            worker.processNext();
            assertEquals("DEADLINE_EXCEEDED",field(store,stale,"error_code"));
            assertEquals(4,calls.get());
            for(int i=0;i<3;i++) {
                long oversized=task(store,"PENDING");
                worker.processNext();
                assertEquals("UPSTREAM_ERROR",field(store,oversized,"error_code"));
                assertNull(store.one("select analysis from tasks where id=?",oversized).get("analysis"));
            }
        }
    }

    private long task(Store store,String status) {
        return store.insert("insert into tasks(ticket_id,creator_id,request_id,status) values (1,1,random_uuid(),?)",status);
    }
    private String field(Store store,long id,String column) {
        return store.db.queryForObject("select "+column+" from tasks where id=?",String.class,id);
    }
}
