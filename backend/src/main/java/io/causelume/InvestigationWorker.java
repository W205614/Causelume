package io.causelume;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@EnableScheduling
class InvestigationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(InvestigationWorker.class);
    // Allow the 95-second HTTP call to finish and leave room to commit its result.
    private static final String DEADLINE = "dateadd(second,120,started_at)";
    private static final String ACTIVE = " where id=? and status='RUNNING' and current_timestamp < "+DEADLINE;
    private final Store store;
    private final InternalClient upstream;
    private volatile boolean ready;

    InvestigationWorker(Store store, InternalClient upstream) {
        this.store = store;
        this.upstream = upstream;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        store.db.update("""
            update tasks set status='FAILED',error_code='RESTART_INTERRUPTED',
              error='服务重启中断调查，请人工确认后重新发起',finished_at=current_timestamp
            where status='RUNNING'""");
        ready = true;
    }

    @Scheduled(fixedDelay=500)
    public void processNext() {
        if (!ready) return;
        // If a terminal write failed, a later poll can reconcile it once the DB is reachable.
        store.db.update("""
            update tasks set status='FAILED',error_code='DEADLINE_EXCEEDED',
              error='调查超期，最终结果未确认',finished_at=current_timestamp
            where status='RUNNING' and current_timestamp >= """ + " " + DEADLINE);
        var jobs = store.rows("""
            select t.id,s.name from tasks t join tickets k on k.id=t.ticket_id
            join services s on s.id=k.service_id where t.status='PENDING' order by t.id fetch first 1 row only""");
        if (jobs.isEmpty()) return;
        var job = jobs.getFirst();
        long id = (long) job.get("id");
        if (store.db.update("update tasks set status='RUNNING',started_at=current_timestamp where id=? and status='PENDING'",id)==0) return;
        JsonNode result;
        try {
            result = investigate(id,(String)job.get("name"));
        } catch (Problem e) {
            String code = switch (e.status) {
                case 504 -> "UPSTREAM_TIMEOUT";
                case 503 -> Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "SERVICE_UNAVAILABLE";
                default -> "UPSTREAM_ERROR";
            };
            failed(id,code,e.getMessage());
            return;
        } catch (RuntimeException e) {
            LOG.error("Task {} failed during execution ({})",id,e.getClass().getName());
            failed(id,"WORKER_ERROR","调查执行异常，请由管理员核查");
            return;
        }
        store.db.update("update tasks set status='SUCCEEDED',mode=?,analysis=?,summary=left(?,240),finished_at=current_timestamp"+ACTIVE,
                result.get("mode").asString(),result.toString(),result.get("possibleCause").asString(),id);
    }

    private void failed(long id,String code,String message) {
        store.db.update("update tasks set status='FAILED',error_code=?,error=?,finished_at=current_timestamp"+ACTIVE,
                code,message,id);
    }

    private JsonNode investigate(long id,String service) {
        String taskId = Long.toString(id);
        JsonNode result = upstream.call("AGENT_URL","AGENT_TOKEN","/investigate",Map.of("taskId",taskId,"service",service),95);
        if (!taskId.equals(result.path("taskId").asString("")) || !List.of("offline","holmes").contains(result.path("mode").asString("")))
            throw new Problem(502,"调查服务返回的任务标识或模式无效");
        for (String key : List.of("possibleCause","uncertainty","recommendation")) {
            if (!validText(result.get(key),2000)) throw new Problem(502,"调查分析字段为空或超过 2000 字符");
        }
        var evidence = result.path("evidence");
        if (!evidence.isArray() || evidence.isEmpty() || evidence.size()>10) throw new Problem(502,"调查需包含 1–10 条证据");
        for (var row : evidence) {
            if (!validText(row.get("source"),100) || !validText(row.get("detail"),2048)) throw new Problem(502,"证据来源或详情为空或超长");
        }
        return result;
    }

    private boolean validText(JsonNode value,int max) {
        if (value == null || !value.isString()) return false;
        String text=value.asString();
        return !text.isBlank() && text.codePointCount(0,text.length())<=max;
    }
}
