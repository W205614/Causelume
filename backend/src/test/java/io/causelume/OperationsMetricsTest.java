package io.causelume;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opsMetrics;DB_CLOSE_DELAY=-1",
        "APP_ADMIN_PASSWORD=metrics-admin-test", "APP_OPERATOR_PASSWORD=metrics-operator-test"})
class OperationsMetricsTest {
    @Autowired Store store;
    @Autowired OperationsMetrics metrics;

    @Test
    @Transactional
    void scrapeReflectsPersistedStateAndExcludesOlderFailures() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            metrics.bindTo(registry);
            String empty = registry.scrape();
            assertTrue(empty.contains("causelume_investigation_queue_oldest_seconds 0.0"), empty);
            long account = store.db.queryForObject("select id from accounts where username='operator'", Long.class);
            long service = store.db.queryForObject("select id from services where name='checkout'", Long.class);
            long ticket = store.insert("insert into tickets(service_id,creator_id,title,status,request_id) values(?,?,'probe','OPEN',?)",
                    service, account, UUID.randomUUID());
            for (String state : new String[]{"PENDING", "RUNNING", "FAILED", "FAILED"}) {
                store.db.update("""
                        insert into tasks(ticket_id,creator_id,request_id,status,created_at)
                        values(?,?,?,?,current_timestamp - interval '3' minute)
                        """, ticket, account, UUID.randomUUID(), state);
            }
            store.db.update("update tasks set finished_at=current_timestamp where status='FAILED'");
            store.db.update("""
                    update tasks set created_at=current_timestamp - interval '20' minute,
                        finished_at=current_timestamp - interval '16' minute
                    where id=(select max(id) from tasks where status='FAILED')
                    """);
            for (String state : new String[]{"FAILED", "UNKNOWN", "SENT"}) {
                store.db.update("insert into notifications(recipient_id,event_type,message,mail_status) values(?,'test','probe',?)",
                        account, state);
            }
            String output = registry.scrape();
            assertTrue(output.contains("causelume_investigations{state=\"PENDING\"} 1.0"), output);
            assertTrue(output.contains("causelume_investigations{state=\"RUNNING\"} 1.0"), output);
            assertTrue(output.contains("causelume_investigations_failed_recent 1.0"), output);
            assertTrue(output.contains("causelume_mail_delivery_attention{state=\"FAILED\"} 1.0"), output);
            assertTrue(output.contains("causelume_mail_delivery_attention{state=\"UNKNOWN\"} 1.0"), output);
            assertTrue(registry.get("causelume.investigation.queue.oldest").gauge().value() >= 179, output);
        } finally {
            registry.close();
        }
    }
}
