package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class Collaborations {
    private final Store store;
    private final TeamAccess access;
    private final Notifications notifications;

    Collaborations(Store store,TeamAccess access,Notifications notifications) {
        this.store=store; this.access=access; this.notifications=notifications;
    }

    record Invitation(String username,List<Long> taskIds) {}
    record Note(String note) {}

    @GetMapping("/collaborations")
    Object mine(@RequestParam(required=false) Long afterId,HttpServletRequest req) {
        return store.rows("""
            select k.id,k.title,k.status,k.priority,i.id as invite_id from ticket_invites i join tickets k on k.id=i.ticket_id
            where i.account_id=? and i.revoked_at is null and i.id<?
              and exists(select 1 from team_members m where m.account_id=i.account_id)
            order by i.id desc limit 100
            """,Access.actor(req).id(),afterId==null?Long.MAX_VALUE:afterId);
    }

    @GetMapping("/collaborations/{ticket}") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Object detail(@PathVariable long ticket,HttpServletRequest req) {
        return sharedTicket(Access.actor(req),ticket);
    }

    Map<String,Object> sharedTicket(Actor actor,long ticket) {
        if(!access.invited(actor.id(),ticket)) throw new Problem(404,"记录不存在");
        var result=store.one("select id,title,status,priority,created_at from tickets where id=?",ticket);
        result.put("collaboration",true);
        result.put("notes",store.rows("""
            select * from (select id,actor,note,created_at from timeline where ticket_id=? and action='COLLAB_NOTE'
              order by id desc limit 100) recent order by id
            """,ticket));
        result.put("tasks",store.rows("""
            select t.id,t.created_at,t.finished_at,t.summary from tasks t join ticket_shared_tasks x on x.task_id=t.id
            join ticket_invites i on i.id=x.invite_id
            where i.ticket_id=? and i.account_id=? and i.revoked_at is null and t.ticket_id=i.ticket_id and t.status='SUCCEEDED'
            order by t.id desc
            """,ticket,actor.id()));
        return result;
    }

    @PostMapping("/collaborations/{ticket}/notes") @Transactional
    public Object note(@PathVariable long ticket,@RequestBody Note input,HttpServletRequest req) {
        var actor=Access.actor(req);
        long team=access.ticketTeam(ticket);
        access.lock(team);
        access.requireActive(actor);
        boolean internal=access.internal(actor,team);
        if(!internal && !access.invited(actor.id(),ticket)) throw new Problem(404,"记录不存在");
        if(store.one("select status from tickets where id=?",ticket).get("status").equals("CLOSED"))
            throw new Problem(409,"已关闭工单不能追加记录");
        store.db.update("insert into timeline(ticket_id,action,actor,note) values (?,'COLLAB_NOTE',?,?)",
            ticket,actor.username(),Problem.text(input.note(),2000,"留言"));
        store.db.update("update tickets set version=version+1 where id=?",ticket);
        store.audit(actor.username(),"COLLAB_NOTE",ticket);
        return Map.of("ok",true);
    }

    @GetMapping("/tickets/{ticket}/invites") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Object invites(@PathVariable long ticket,HttpServletRequest req) {
        var actor=Access.actor(req);
        long team=access.ticketTeam(ticket);
        access.requireTeam(actor,team);
        if(!access.manager(actor,team)) IncidentRecords.requireOwner(actor,store.one("select assignee_id from tickets where id=?",ticket));
        return store.rows("""
            select i.id,a.username,i.created_at from ticket_invites i join accounts a on a.id=i.account_id
            where i.ticket_id=? and i.revoked_at is null order by i.id
            """,ticket);
    }

    @PostMapping("/tickets/{ticket}/invites") @Transactional
    public Object invite(@PathVariable long ticket,@RequestBody Invitation input,HttpServletRequest req) {
        var actor=Access.actor(req);
        long team=access.ticketTeam(ticket);
        access.ticketMutation(actor,ticket,team);
        var work=store.one("select assignee_id,status from tickets where id=?",ticket);
        if(!Long.valueOf(actor.id()).equals(work.get("assigneeId"))) throw new Problem(403,"仅工单负责人可以邀请协作者");
        if(work.get("status").equals("CLOSED")) throw new Problem(409,"已关闭工单不能邀请协作者");
        var recipient=store.one("""
            select a.id,a.email from accounts a where a.username=? and a.active=true and a.role<>'ADMIN'
              and exists(select 1 from team_members m where m.account_id=a.id and m.team_id<>?)
              and not exists(select 1 from team_members m where m.account_id=a.id and m.team_id=?)
            for update
            """,Problem.text(input.username(),64,"协作账号"),team,team);
        long account=(long)recipient.get("id");
        var tasks=input.taskIds()==null?List.<Long>of():input.taskIds();
        if(tasks.size()>100 || new HashSet<>(tasks).size()!=tasks.size()) throw new Problem(400,"请提供最多 100 个不同的调查编号");
        for(var task:tasks) store.one("select id from tasks where id=? and ticket_id=? and status='SUCCEEDED'",task,ticket);
        if(access.invited(account,ticket)) throw new Problem(409,"已有有效邀请，请撤销后重新邀请");
        long invite=store.insert("insert into ticket_invites(ticket_id,account_id,invited_by) values (?,?,?)",ticket,account,actor.id());
        for(var task:tasks) store.db.update("insert into ticket_shared_tasks(invite_id,task_id) values (?,?)",invite,task);
        notifications.invitation(ticket,invite,account,(String)recipient.get("email"));
        store.audit(actor.username(),"INVITE_COLLABORATOR",invite);
        return Map.of("id",invite);
    }

    @PostMapping("/tickets/{ticket}/invites/{invite}/revoke") @Transactional
    public Object revoke(@PathVariable long ticket,@PathVariable long invite,HttpServletRequest req) {
        var actor=Access.actor(req);
        long team=access.ticketTeam(ticket);
        access.ticketMutation(actor,ticket,team);
        if(!access.manager(actor,team)) IncidentRecords.requireOwner(actor,store.one("select assignee_id from tickets where id=?",ticket));
        if(store.db.update("update ticket_invites set revoked_at=current_timestamp where id=? and ticket_id=? and revoked_at is null",invite,ticket)==0)
            throw new Problem(404,"邀请不存在");
        store.db.update("""
            update notifications set mail_status='CANCELLED',last_error='ACCESS_REVOKED'
            where invite_id=? and mail_status in ('PENDING','RETRY','FAILED','UNKNOWN')
            """,invite);
        store.audit(actor.username(),"REVOKE_COLLABORATOR",invite);
        return Map.of("ok",true);
    }
}
