package io.causelume;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@EnableScheduling
class InvestigationWorker {
    private final Store store;
    private final InternalClient upstream;
    private volatile boolean ready;
    InvestigationWorker(Store store, InternalClient upstream) {
        this.store = store;
        this.upstream = upstream;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // A terminated process cannot know whether its external call consumed model credits.
        store.db.update("update tasks set status='FAILED',error='服务重启中断调查，请人工确认后重新发起' where status='RUNNING'");
        ready = true;
    }

    @Scheduled(fixedDelay=500)
    public void processNext() {
        if (!ready) return;
        var jobs = store.rows("""
            select t.id,s.name from tasks t join tickets k on k.id=t.ticket_id
            join services s on s.id=k.service_id where t.status='PENDING' order by t.id fetch first 1 row only""");
        if (jobs.isEmpty()) return;
        long id = (long)jobs.getFirst().get("id");
        if (store.db.update("update tasks set status='RUNNING' where id=? and status='PENDING'",id)==0) return;
        String taskId = Long.toString(id);
        try {
            JsonNode result = upstream.call("AGENT_URL","AGENT_TOKEN","/investigate",Map.of("taskId",taskId,"service",jobs.getFirst().get("name")),95);
            if (!taskId.equals(result.path("taskId").asString("")) || !List.of("offline","holmes").contains(result.path("mode").asString("")))
                throw new Problem(502,"调查服务返回的任务标识或模式无效");
            for (String key : List.of("possibleCause","uncertainty","recommendation")) {
                if (!validText(result.get(key))) throw new Problem(502,"调查服务返回的分析字段无效");
            }
            var evidence = result.path("evidence");
            if (!evidence.isArray() || evidence.isEmpty()) throw new Problem(502,"调查服务没有返回证据");
            for (var row : evidence) {
                if (!validText(row.get("source")) || !validText(row.get("detail"))) throw new Problem(502,"证据缺少来源或内容");
            }
            store.db.update("update tasks set status='SUCCEEDED',mode=?,analysis=? where id=?",result.get("mode").asString(),result.toString(),id);
        } catch (Problem failed) {
            store.db.update("update tasks set status='FAILED',error=? where id=?",failed.getMessage(),id);
        }
    }
    private boolean validText(JsonNode value) {
        return value != null && value.isString() && !value.asString().isBlank();
    }
}
