package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class IncidentRecords {
    final Store store;
    final TeamAccess access;
    IncidentRecords(Store store,TeamAccess access){this.store=store;this.access=access;}

    static void requireOwner(Actor actor,Map<String,Object> ticket) {
        if(!actor.role().equals("ADMIN") && !Long.valueOf(actor.id()).equals(ticket.get("assigneeId")))
            throw new Problem(403,"需要工单负责人或管理员权限");
    }

    record Item(String title,Long ownerId,LocalDate dueDate){}
    record Update(String status,String evidence){}
    record Reassign(Long ownerId){}

    @GetMapping("/improvements/page") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    Browse.Page myItems(@RequestParam(defaultValue="OPEN") String status,
                        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
                        @RequestParam(required=false) Long teamId,HttpServletRequest req) {
        var actor=Access.actor(req);
        Problem.choice(status,Set.of("OPEN","DONE"),"status");
        if(page<1 || size<1 || size>100) throw new Problem(400,"页码须为正，每页 1–100 条");
        var scope=TeamAccess.scope(actor,"s.team_id",teamId);
        String from=" from improvement_items i join tickets k on k.id=i.ticket_id join services s on s.id=k.service_id where "
            +scope.sql()+" and i.owner_id=? and i.status=?";
        long total=store.db.queryForObject("select count(*)"+from,Long.class,scope.with(actor.id(),status));
        var items=store.rows("select i.id,i.title,i.due_date,i.status,i.ticket_id,k.title as ticket_title"+from+
            " order by i.due_date,i.id limit ? offset ?",scope.with(actor.id(),status,size,(long)(page-1)*size));
        return new Browse.Page(items,total,page,size);
    }
    @PostMapping("/tickets/{id}/improvements") @Transactional
    public Object add(@PathVariable long id,@RequestBody Item in,HttpServletRequest req) {
        var actor=Access.actor(req);
        long team=access.ticketTeam(id);
        access.ticketMutation(actor,id,team);
        requireOwner(actor,store.one("select assignee_id from tickets where id=? for update",id));
        if(in.ownerId()==null || in.dueDate()==null) throw new Problem(400,"改进项需要负责人和到期日期");
        access.assignee(team,in.ownerId());
        long item=store.insert("insert into improvement_items(ticket_id,title,owner_id,due_date) values (?,?,?,?)",
            id,Problem.text(in.title(),500,"title"),in.ownerId(),in.dueDate());
        record(id,actor,"ADD_ITEM","新增改进项 #"+item+"："+in.title());
        return store.one("select * from improvement_items where id=?",item);
    }

    @PostMapping("/tickets/{id}/improvements/{item}") @Transactional
    public Object update(@PathVariable long id,@PathVariable long item,@RequestBody Update in,HttpServletRequest req){
        var actor=Access.actor(req);
        access.ticketMutation(actor,id,access.ticketTeam(id));
        var ticket=store.one("select assignee_id from tickets where id=? for update",id);
        var row=store.one("select * from improvement_items where ticket_id=? and id=?",id,item);
        if(!Long.valueOf(actor.id()).equals(row.get("ownerId"))) requireOwner(actor,ticket);
        String status=Problem.choice(in.status(),Set.of("OPEN","DONE"),"status");
        String evidence=in.evidence()==null ? null : Problem.text(in.evidence(),1500,"完成依据");
        if(evidence!=null && !status.equals("DONE")) throw new Problem(400,"完成依据仅用于标记完成");
        if(!status.equals(row.get("status"))){
            store.db.update("update improvement_items set status=? where id=?",status,item);
            record(id,actor,"UPDATE_ITEM","改进项 #"+item+" → "+status+(evidence==null ? "" : "\n完成依据："+evidence));
        }
        return store.one("select * from improvement_items where id=?",item);
    }

    @PostMapping("/tickets/{id}/improvements/{item}/assign") @Transactional
    public Object assign(@PathVariable long id,@PathVariable long item,@RequestBody Reassign in,HttpServletRequest req) {
        var actor=Access.actor(req);
        long team=access.ticketTeam(id);
        access.ticketMutation(actor,id,team);
        if(!access.manager(actor,team)) requireOwner(actor,store.one("select assignee_id from tickets where id=?",id));
        if(in.ownerId()==null) throw new Problem(400,"请选择接手人");
        access.assignee(team,in.ownerId());
        if(store.db.update("update improvement_items set owner_id=? where id=? and ticket_id=? and status='OPEN'",in.ownerId(),item,id)==0)
            throw new Problem(404,"待交接改进项不存在");
        record(id,actor,"ASSIGN_ITEM","改进项 #"+item+" 已交接给账号 #"+in.ownerId());
        return store.one("select * from improvement_items where id=?",item);
    }
    private void record(long ticket,Actor actor,String action,String note){
        store.db.update("update tickets set version=version+1 where id=?",ticket);
        store.db.update("insert into timeline(ticket_id,actor,action,note) values (?,?,?,?)",ticket,actor.username(),action,note);
        store.audit(actor.username(),action,ticket);
    }

    @GetMapping("/tickets/{id}/timeline") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Object timeline(@PathVariable long id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest req){
        if(page<1 || size<1 || size>100)throw new Problem(400,"页码须为正，每页 1–100 条");
        access.requireTeam(Access.actor(req),access.ticketTeam(id));
        long total=store.db.queryForObject("select count(*) from timeline where ticket_id=?",Long.class,id);
        var rows=store.rows("select id,actor,action,note,created_at,outcome from timeline where ticket_id=? order by id desc limit ? offset ?",
            id,size,(long)(page-1)*size);
        return Map.of("items",rows,"total",total,"page",page,"size",size);
    }

    @GetMapping("/retention/preview") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Object preview(@RequestParam(defaultValue="90")int days,HttpServletRequest req){
        Access.actor(req).admin();
        if(days<1 || days>3650)throw new Problem(400,"保留期范围为 1 至 3650 天");
        var cutoff=OffsetDateTime.now(ZoneOffset.UTC).minusDays(days);
        return Map.of("days",days,"cutoff",cutoff,"previewOnly",true,
            "audit",store.db.queryForObject("select count(*) from audit where created_at<?",Long.class,cutoff),
            "notifications",store.db.queryForObject("select count(*) from notifications where created_at<?",Long.class,cutoff),
            "resolvedAlerts",store.db.queryForObject("select count(*) from alerts where lifecycle_status='RESOLVED' and resolved_at<?",Long.class,cutoff),
            "closedTickets",store.db.queryForObject("""
                select count(*) from tickets k where status='CLOSED' and
                (select max(created_at) from timeline l where l.ticket_id=k.id and l.action='CLOSE')<?
                """,Long.class,cutoff),
            "finishedTasks",store.db.queryForObject("select count(*) from tasks where finished_at<? and status in ('SUCCEEDED','FAILED')",Long.class,cutoff));
    }

    @PostMapping("/tickets/{id}/export") @Transactional(isolation=Isolation.REPEATABLE_READ)
    public ResponseEntity<?> export(@PathVariable long id,@RequestParam(defaultValue="json")String format,HttpServletRequest req){
        var actor=Access.actor(req);
        access.ticketMutation(actor,id,access.ticketTeam(id));
        var row=store.one("select "+Tickets.COLUMNS+" from tickets where id=?",id);
        requireOwner(actor,row);
        Problem.choice(format,Set.of("json","markdown"),"format");
        // Export only operator-entered records and task metadata; raw evidence can contain credentials.
        var events=store.rows("select id,action,actor,note,created_at,outcome from timeline where ticket_id=? order by id limit 1001",id);
        var items=store.rows("select title,owner_id,due_date,status from improvement_items where ticket_id=? order by id limit 1001",id);
        var tasks=store.rows("select id,status,mode,created_at,finished_at from tasks where ticket_id=? order by id limit 1001",id);
        if(events.size()>1000 || items.size()>1000 || tasks.size()>1000) throw new Problem(413,"导出超过每类 1000 条的上限，请使用分页查询");
        var export=new LinkedHashMap<String,Object>();
        export.put("ticket",row);export.put("timeline",events);export.put("improvements",items);export.put("investigations",tasks);
        store.audit(actor.username(),"EXPORT_TICKET",id);
        boolean markdown=format.equals("markdown");
        var response=ResponseEntity.ok().header("Content-Disposition","attachment; filename=ticket-"+id+(markdown?".md":".json"))
            .contentType(markdown?MediaType.parseMediaType("text/markdown;charset=UTF-8"):MediaType.APPLICATION_JSON);
        if(!markdown)return response.body(export);
        var text=new StringBuilder("# Causelume 工单 #").append(id).append("\n\n");
        for(var section:export.entrySet()){
            text.append("## ").append(section.getKey()).append("\n\n");
            if(section.getValue() instanceof java.util.List<?> list){
                for(Object entry:list)appendFields(text,(Map<?,?>)entry);
            }else appendFields(text,(Map<?,?>)section.getValue());
        }
        return response.body(text.toString());
    }
    private static void appendFields(StringBuilder text,Map<?,?> values){
        // Indented Markdown code blocks keep user-authored HTML, links and fence text inert.
        for(var field:values.entrySet())if(field.getValue()!=null)
            text.append("    ").append(field.getKey()).append(" = ").append(field.getValue().toString().replace("\r","").replace("\n","\n    ")).append("\n");
        text.append("\n");
    }
}
