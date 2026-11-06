package io.causelume;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class OperationsMetrics implements MeterBinder {
    private final JdbcTemplate db;

    OperationsMetrics(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String state : new String[]{"PENDING", "RUNNING"}) {
            Gauge.builder("causelume.investigations", db,
                        jdbc -> jdbc.queryForObject("select count(*) from tasks where status=?", Long.class, state))
                    .tag("state", state)
                    .register(registry);
        }
        Gauge.builder("causelume.investigation.queue.oldest", db, jdbc -> jdbc.queryForObject("""
                    select coalesce(extract(epoch from (current_timestamp - min(created_at))), 0)
                    from tasks where status='PENDING'
                    """, Double.class))
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("causelume.investigations.failed.recent", db, jdbc -> jdbc.queryForObject("""
                    select count(*) from tasks
                    where status='FAILED' and finished_at >= current_timestamp - interval '15' minute
                    """, Long.class))
                .description("Failed investigations completed within the last 15 minutes")
                .register(registry);
        for (String state : new String[]{"FAILED", "UNKNOWN"}) {
            Gauge.builder("causelume.mail.delivery.attention", db, jdbc -> jdbc.queryForObject(
                            "select count(*) from notifications where mail_status=?", Long.class, state))
                    .tag("state", state)
                    .register(registry);
        }
    }
}
