package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
class Notifications {
    final Store store;

    Notifications(Store store) { this.store = store; }

    // Called inside the transaction that changes the work order or alert.
    void notifyTicket(long ticketId, String event, String message) {
        var ticket = store.one("""
            select k.assignee_id, case when s.maintenance_until>current_timestamp then true else false end as silent
              from tickets k join services s on k.service_id=s.id where k.id=?
            """,ticketId);
        if(Boolean.TRUE.equals(ticket.get("silent")))return;
        var recipients = store.rows("select id,email from accounts where active=true and id=?",ticket.get("assigneeId"));
        if (recipients.isEmpty()) recipients = store.rows("select id,email from accounts where active=true and role='ADMIN'");
        for (var user : recipients) {
            store.db.update("""
                insert into notifications(recipient_id,ticket_id,event_type,message,mail_to,mail_status)
                values (?,?,?,?,?,?)
                """,user.get("id"),ticketId,event,message,user.get("email"),user.get("email")==null ? "NO_ADDRESS" : "PENDING");
        }
    }

    @GetMapping Object list(@RequestParam(required=false) Long afterId,@RequestParam(defaultValue="mine") String scope,HttpServletRequest req) {
        var actor=Access.actor(req);
        if(afterId!=null && afterId<1) throw new Problem(400,"afterId 必须是正整数");
        String sql="select id,recipient_id,event_type,ticket_id,message,read_at,created_at,mail_status,attempts,last_error from notifications where ";
        long cursor=afterId==null ? Long.MAX_VALUE : afterId;
        Problem.choice(scope,java.util.Set.of("mine","delivery"),"scope");
        if(scope.equals("delivery")) actor.admin();
        var items=scope.equals("delivery")
            ? store.rows(sql+"mail_status in ('FAILED','UNKNOWN') and id<? order by id desc limit 51",cursor)
            : store.rows(sql+"recipient_id=? and id<? order by id desc limit 51",actor.id(),cursor);
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("items",items.subList(0,Math.min(50,items.size())));
        result.put("nextCursor",items.size()>50 ? items.get(49).get("id") : null);
        return result;
    }

    @PostMapping("/{id}/read") Object read(@PathVariable long id,HttpServletRequest req) {
        if(store.db.update("update notifications set read_at=coalesce(read_at,current_timestamp) where id=? and recipient_id=?",id,Access.actor(req).id())==0)
            throw new Problem(404,"通知不存在");
        return java.util.Map.of("id",id);
    }

    record Resend(String reason) {}
    @PostMapping("/{id}/resend") @Transactional
    public Object resend(@PathVariable long id,@RequestBody Resend input,HttpServletRequest req) {
        var actor=Access.actor(req);
        actor.admin();
        String reason=Problem.text(input.reason,500,"reason");
        int rows=store.db.update("""
            update notifications set mail_status='PENDING',attempts=0,next_attempt=current_timestamp,last_error=null
             where id=? and mail_status in ('FAILED','UNKNOWN') and mail_to is not null
            """,id);
        if(rows==0) throw new Problem(409,"只允许人工重发失败或投递结果未知的邮件");
        store.audit(actor.username(),"RESEND_EMAIL",id);
        store.db.update("insert into mail_resends(notification_id,actor,reason) values (?,?,?)",id,actor.username(),reason);
        return java.util.Map.of("id",id,"mailStatus","PENDING");
    }
}
