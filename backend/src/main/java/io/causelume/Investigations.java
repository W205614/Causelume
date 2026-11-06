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
    private static final String SELECT = "select id,ticket_id,status,mode,created_at,analysis,error,feedback from tasks";
    final Store store;
    final ObjectMapper json;
    Investigations(Store store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }
    record NewTask(long ticketId, UUID requestId) {}
    record Feedback(String feedback) {}

    @GetMapping
    List<Map<String,Object>> list(@RequestParam(required=false) Long ticketId) {
        var rows = ticketId == null ? store.rows(SELECT+" order by id desc")
                : store.rows(SELECT+" where ticket_id=? order by id desc", ticketId);
        return rows.stream().map(this::result).toList();
    }
    private Map<String,Object> result(Map<String,Object> task) {
        String analysis = (String)task.remove("analysis");
        task.put("result", analysis == null ? null : json.readTree(analysis));
        return task;
    }
    Map<String,Object> get(long id) {
        return result(store.one(SELECT+" where id=?",id));
    }

    @PostMapping @Transactional
    public Object create(@RequestBody NewTask input, HttpServletRequest req) {
        var actor = Access.actor(req);
        if (input.requestId == null) throw new Problem(400,"需要 requestId");
        // Serialize requests for this ticket, including a concurrent verification or close.
        var ticket = store.one("select status,assignee_id from tickets where id=? for update",input.ticketId);
        if (!actor.role().equals("ADMIN") && !Long.valueOf(actor.id()).equals(ticket.get("assigneeId")))
            throw new Problem(403,"需要工单负责人或管理员权限");
        var old = store.rows("select id,ticket_id from tasks where creator_id=? and request_id=?",actor.id(),input.requestId);
        if (!old.isEmpty()) {
            if (!Long.valueOf(input.ticketId).equals(old.getFirst().get("ticketId")))
                throw new Problem(409,"同一 requestId 的工单不一致");
            return get((long)old.getFirst().get("id"));
        }
        if (!List.of("ASSIGNED","PROCESSING").contains(ticket.get("status")))
            throw new Problem(409,"调查需要已分派或处理中的工单");
        if (!store.rows("select id from tasks where ticket_id=? and status in ('PENDING','RUNNING')",input.ticketId).isEmpty())
            throw new Problem(409,"此工单已有调查在进行");
        long id = store.insert("insert into tasks(ticket_id,creator_id,request_id,status) values (?,?,?,'PENDING')",
                input.ticketId,actor.id(),input.requestId);
        store.audit(actor.username(),"CREATE_TASK",id);
        return get(id);
    }
    @PostMapping("/{id}/feedback") @Transactional
    public Object feedback(@PathVariable long id,@RequestBody Feedback input,HttpServletRequest req) {
        var actor = Access.actor(req);
        var task = store.one("select t.status,k.assignee_id from tasks t join tickets k on k.id=t.ticket_id where t.id=?",id);
        if (!actor.role().equals("ADMIN") && !Long.valueOf(actor.id()).equals(task.get("assigneeId")))
            throw new Problem(403,"需要工单负责人或管理员权限");
        if (!List.of("FAILED","SUCCEEDED").contains(task.get("status"))) throw new Problem(409,"请等待任务结束");
        String feedback = Problem.text(input.feedback,2000,"feedback");
        if (store.db.update("update tasks set feedback=? where id=? and feedback is null",feedback,id)==0)
            throw new Problem(409,"此任务已提交人工反馈");
        store.audit(actor.username(),"TASK_FEEDBACK",id);
        return get(id);
    }
}
