package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/tasks")
class Investigations {
    private static final String SELECT = "select id,ticket_id,status,mode,created_at,started_at,finished_at,error_code,analysis,error,feedback,feedback_rating,source_id,window_start,window_end from tasks";
    final Store store;
    final ObjectMapper json;
    final ModelBudgets budgets;
    Investigations(Store store, ObjectMapper json, ModelBudgets budgets) {
        this.store = store;
        this.json = json;
        this.budgets = budgets;
    }
    record NewTask(long ticketId, UUID requestId, java.time.Instant windowStart, java.time.Instant windowEnd) {}
    record Feedback(String feedback, String rating) {}

    @GetMapping
    List<Map<String,Object>> list(@RequestParam(required=false) Long ticketId) {
        String fields = "select id,ticket_id,status,mode,created_at,started_at,finished_at,error_code,coalesce(summary,left(error,240)) as summary from tasks";
        return ticketId == null ? store.rows(fields+" order by id desc limit 100")
                : store.rows(fields+" where ticket_id=? order by id desc limit 100", ticketId);
    }
    private Map<String,Object> result(Map<String,Object> task) {
        String analysis = (String)task.remove("analysis");
        task.put("result", analysis == null ? null : json.readTree(analysis));
        return task;
    }
    @GetMapping("/{id}")
    Map<String,Object> get(@PathVariable long id) {
        return result(store.one(SELECT+" where id=?",id));
    }

    @PostMapping @Transactional
    public Object create(@RequestBody NewTask input, HttpServletRequest req) {
        var actor = Access.actor(req);
        if (input.requestId == null) throw new Problem(400,"需要 requestId");
        // Serialize requests for this ticket, including a concurrent verification or close.
        var ticket = store.one("select status,assignee_id,service_id,alert_id from tickets where id=? for update",input.ticketId);
        if (!actor.role().equals("ADMIN") && !Long.valueOf(actor.id()).equals(ticket.get("assigneeId")))
            throw new Problem(403,"需要工单负责人或管理员权限");
        var old = store.rows("select id,ticket_id,window_start,window_end from tasks where creator_id=? and request_id=?",actor.id(),input.requestId);
        if (!old.isEmpty()) {
            if (!Long.valueOf(input.ticketId).equals(old.getFirst().get("ticketId")))
                throw new Problem(409,"同一 requestId 的工单不一致");
            var original=old.getFirst();
            if(input.windowStart!=null || input.windowEnd!=null) {
                if(input.windowStart==null || input.windowEnd==null || original.get("windowStart")==null || original.get("windowEnd")==null ||
                   !input.windowStart.equals(((java.time.OffsetDateTime)original.get("windowStart")).toInstant()) ||
                   !input.windowEnd.equals(((java.time.OffsetDateTime)original.get("windowEnd")).toInstant()))
                    throw new Problem(409,"同一 requestId 的调查时间范围冲突");
            }
            return get((long)original.get("id"));
        }
        if (!List.of("ASSIGNED","PROCESSING").contains(ticket.get("status")))
            throw new Problem(409,"调查需要已分派或处理中的工单");
        if (!store.rows("select id from tasks where ticket_id=? and status in ('PENDING','RUNNING')",input.ticketId).isEmpty())
            throw new Problem(409,"此工单已有调查在进行");
        // Admissions on different tickets compete for the same finite worker queue.
        store.one("select id from task_admission where id=1 for update");
        if (store.db.queryForObject("select count(*) from tasks where status in ('PENDING','RUNNING')",Long.class) >= 32)
            throw new Problem(429,"待处理调查已达上限，请稍后再试");
        var service=store.one("select name,source_id,service_label,environment,active from services where id=?",ticket.get("serviceId"));
        if(!Boolean.TRUE.equals(service.get("active"))) throw new Problem(409,"服务已停用");
        if(service.get("sourceId")==null) throw new Problem(422,"服务尚未绑定批准的数据源");
        java.time.Instant end=input.windowEnd, start=input.windowStart;
        if((start==null)!=(end==null)) throw new Problem(400,"时间范围需要同时填写起止时间");
        if(start==null) {
            if(ticket.get("alertId")!=null) {
                var at=store.one("select occurred_at from alerts where id=?",ticket.get("alertId")).get("occurredAt");
                var occurred=((java.time.OffsetDateTime)at).toInstant();
                start=occurred.minusSeconds(300);
                end=occurred.plusSeconds(300);
            } else {
                end=java.time.Instant.now();
                start=end.minusSeconds(600);
            }
        }
        if(!start.isBefore(end) || java.time.Duration.between(start,end).compareTo(java.time.Duration.ofHours(1))>0)
            throw new Problem(400,"调查时间范围必须大于零且不超过一小时");
        long id = store.insert("insert into tasks(ticket_id,creator_id,request_id,status,source_id,service_label,environment,window_start,window_end) values (?,?,?,'PENDING',?,?,?,?,?)",
                input.ticketId,actor.id(),input.requestId,service.get("sourceId"),service.get("serviceLabel"),service.get("environment"),
                start.atOffset(java.time.ZoneOffset.UTC),end.atOffset(java.time.ZoneOffset.UTC));
        budgets.reserve(id);
        store.audit(actor.username(),"CREATE_TASK",id);
        return get(id);
    }
    @PostMapping("/{id}/feedback") @Transactional
    public Object feedback(@PathVariable long id,@RequestBody Feedback input,HttpServletRequest req) {
        var actor = Access.actor(req);
        var task = store.one("select t.status,k.assignee_id from tasks t join tickets k on k.id=t.ticket_id where t.id=? for update",id);
        if (!actor.role().equals("ADMIN") && !Long.valueOf(actor.id()).equals(task.get("assigneeId")))
            throw new Problem(403,"需要工单负责人或管理员权限");
        if (!List.of("FAILED","SUCCEEDED").contains(task.get("status"))) throw new Problem(409,"请等待任务结束");
        String feedback = Problem.text(input.feedback,2000,"feedback");
        String rating=input.rating==null ? null : Problem.choice(input.rating,java.util.Set.of("HELPFUL","PARTIAL","UNHELPFUL"),"rating");
        if (store.db.update("update tasks set feedback=?,feedback_rating=? where id=? and feedback is null",feedback,rating,id)==0)
            throw new Problem(409,"此任务已提交人工反馈");
        store.audit(actor.username(),"TASK_FEEDBACK",id);
        return get(id);
    }
}
