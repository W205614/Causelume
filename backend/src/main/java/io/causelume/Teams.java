package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teams")
class Teams {
    private final Store store;
    private final TeamAccess access;

    Teams(Store store, TeamAccess access) { this.store=store; this.access=access; }

    record Team(String name,String slug) {}
    record Member(String username,String role) {}
    record Removal(Long successorId) {}

    @GetMapping Object list(HttpServletRequest req) {
        var actor=Access.actor(req);
        var scope=TeamAccess.scope(actor,"t.id",null);
        return store.rows("""
            select t.id,t.slug,t.name,m.team_role from teams t
            left join team_members m on m.team_id=t.id and m.account_id=? where
            """+scope.sql()+" order by t.id",scopeWithActor(actor,scope));
    }

    private Object[] scopeWithActor(Actor actor,TeamAccess.Scope scope) {
        var args=new java.util.ArrayList<>();
        args.add(actor.id());
        args.addAll(scope.args());
        return args.toArray();
    }

    @PostMapping @Transactional
    public Object create(@RequestBody Team input,HttpServletRequest req) {
        var actor=Access.actor(req);
        actor.admin();
        access.writer(actor,TeamAccess.DEFAULT_TEAM);
        String slug=Problem.text(input.slug(),64,"团队标识");
        if(!slug.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new Problem(400,"团队标识仅支持小写 ASCII 字母、数字、连字符和下划线");
        if(!store.rows("select id from teams where slug=?",slug).isEmpty()) throw new Problem(409,"团队标识已存在");
        long id=store.insert("insert into teams(slug,name) values (?,?)",slug,Problem.text(input.name(),100,"团队名称"));
        audit(actor,"CREATE_TEAM",id,id);
        return store.one("select * from teams where id=?",id);
    }

    @GetMapping("/{id}/members") Object members(@PathVariable long id,HttpServletRequest req) {
        access.requireManager(Access.actor(req),id);
        return store.rows("""
            select a.id,a.username,a.active,m.team_role from team_members m join accounts a on a.id=m.account_id
            where m.team_id=? order by a.id
            """,id);
    }

    @PostMapping("/{id}/members") @Transactional
    public Object member(@PathVariable long id,@RequestBody Member input,HttpServletRequest req) {
        var actor=Access.actor(req);
        access.writer(actor,id);
        access.requireManager(actor,id);
        String role=Problem.choice(input.role(),Set.of("MANAGER","MEMBER"),"团队角色");
        long account=(long)store.one("select id from accounts where username=? and active=true for update",Problem.text(input.username(),64,"账号")).get("id");
        var current=store.rows("select team_role from team_members where team_id=? and account_id=?",id,account);
        if(!TeamAccess.platform(actor) && (role.equals("MANAGER") || !current.isEmpty() && current.getFirst().get("teamRole").equals("MANAGER")))
            throw new Problem(403,"仅平台管理员可以变更团队管理员");
        if(current.isEmpty()) store.db.update("insert into team_members(team_id,account_id,team_role) values (?,?,?)",id,account,role);
        else if(current.getFirst().get("teamRole").equals(role)) return Map.of("ok",true);
        else store.db.update("update team_members set team_role=? where team_id=? and account_id=?",role,id,account);
        audit(actor,role.equals("MANAGER")?"SET_TEAM_MANAGER":"SET_TEAM_MEMBER",account,id);
        return Map.of("ok",true);
    }

    @PostMapping("/{id}/members/{account}/remove") @Transactional
    public Object remove(@PathVariable long id,@PathVariable long account,@RequestBody Removal input,HttpServletRequest req) {
        var actor=Access.actor(req);
        access.writer(actor,id);
        access.requireManager(actor,id);
        store.one("select id from accounts where id=? for update",account);
        var member=store.one("select team_role from team_members where team_id=? and account_id=?",id,account);
        if(member.get("teamRole").equals("MANAGER") && !TeamAccess.platform(actor))
            throw new Problem(403,"仅平台管理员可以移除团队管理员");
        if(input.successorId()!=null) {
            if(input.successorId().equals(account)) throw new Problem(400,"请选择另一位接手人");
            access.assignee(id,input.successorId());
        }
        transfer(actor,id,account,input.successorId());
        store.db.update("delete from team_members where team_id=? and account_id=?",id,account);
        audit(actor,"REMOVE_TEAM_MEMBER",account,id);
        if(store.rows("select team_id from team_members where account_id=?",account).isEmpty()) revokeIncoming(account);
        return Map.of("ok",true);
    }

    void accountDisabled(Actor actor,long account) {
        for(var row:store.rows("select team_id from team_members where account_id=? order by team_id",account)) {
            long team=(long)row.get("teamId");
            transfer(actor,team,account,null);
        }
        revokeIncoming(account);
    }

    private void revokeIncoming(long account) {
        store.db.update("update ticket_invites set revoked_at=current_timestamp where account_id=? and revoked_at is null",account);
        store.db.update("""
            update notifications set mail_status='CANCELLED',last_error='ACCESS_REVOKED'
            where recipient_id=? and invite_id is not null and mail_status in ('PENDING','RETRY','FAILED','UNKNOWN')
            """,account);
    }

    private void transfer(Actor actor,long team,long account,Long successor) {
        store.db.update("update services set owner_id=?,version=version+1 where team_id=? and owner_id=?",successor,team,account);
        var serviceIds="select id from services where team_id=?";
        var tickets=store.rows("select id from tickets where status<>'CLOSED' and assignee_id=? and service_id in ("+serviceIds+")",account,team);
        for(var ticket:tickets) {
            long id=(long)ticket.get("id");
            store.db.update("update tickets set assignee_id=?,version=version+1 where id=?",successor,id);
            store.db.update("insert into timeline(ticket_id,action,actor,note) values (?,'MEMBER_HANDOVER',?,?)",
                id,actor.username(),successor==null?"原负责人离队或停用，待分派":"负责人已交接给账号 #"+successor);
            audit(actor,"TEAM_HANDOVER",id,team);
        }
        store.db.update("""
            update improvement_items set owner_id=? where owner_id=? and status='OPEN' and ticket_id in
              (select id from tickets where service_id in (select id from services where team_id=?))
            """,successor,account,team);
        audit(actor,"TEAM_OWNERSHIP_CHANGE",account,team);
    }

    void audit(Actor actor,String action,long target,long team) {
        store.db.update("insert into audit(actor,action,target_id,team_id) values (?,?,?,?)",actor.username(),action,target,team);
    }
}
