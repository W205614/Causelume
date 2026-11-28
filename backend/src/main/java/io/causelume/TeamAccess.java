package io.causelume;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class TeamAccess {
    static final long DEFAULT_TEAM = 1;
    private final Store store;

    TeamAccess(Store store) { this.store = store; }

    record Scope(String sql, List<Object> args) {
        Object[] with(Object... extra) {
            var values = new ArrayList<>(args);
            java.util.Collections.addAll(values, extra);
            return values.toArray();
        }
    }

    static boolean platform(Actor actor) { return actor.role().equals("ADMIN"); }

    static Scope scope(Actor actor, String teamColumn, Long teamId) {
        var args = new ArrayList<>();
        String sql = "1=1";
        if (teamId != null) {
            if (teamId < 1) throw new Problem(400,"团队编号必须为正整数");
            sql = teamColumn + "=?";
            args.add(teamId);
        }
        if (!platform(actor)) {
            sql += " and exists(select 1 from team_members m where m.team_id=" + teamColumn + " and m.account_id=?)";
            args.add(actor.id());
        }
        return new Scope(sql,args);
    }

    boolean internal(Actor actor, long team) {
        return platform(actor) || !store.rows("select 1 from team_members where team_id=? and account_id=?",team,actor.id()).isEmpty();
    }

    boolean manager(Actor actor, long team) {
        return platform(actor) || !store.rows("select 1 from team_members where team_id=? and account_id=? and team_role='MANAGER'",team,actor.id()).isEmpty();
    }

    void requireTeam(Actor actor, long team) {
        if (!internal(actor,team)) throw new Problem(404,"记录不存在");
    }

    void requireManager(Actor actor, long team) {
        requireTeam(actor,team);
        if (!manager(actor,team)) throw new Problem(403,"需要团队管理员权限");
    }

    void lock(long team) {
        store.one("select id from teams where id=? for update",team);
    }

    void writer(Actor actor, long team) {
        lock(team);
        requireActive(actor);
        requireTeam(actor,team);
    }

    void requireActive(Actor actor) {
        if (store.rows("select id from accounts where id=? and active=true and auth_version=?",actor.id(),actor.authVersion()).isEmpty())
            throw new Problem(401,"账户状态已变更，请重新登录");
    }

    long serviceTeam(long service) {
        return (long)store.one("select team_id from services where id=?",service).get("teamId");
    }

    long ticketTeam(long ticket) {
        return (long)store.one("select s.team_id from tickets k join services s on s.id=k.service_id where k.id=?",ticket).get("teamId");
    }

    long taskTeam(long task) {
        return (long)store.one("""
            select s.team_id from tasks t join tickets k on k.id=t.ticket_id join services s on s.id=k.service_id where t.id=?
            """,task).get("teamId");
    }

    Map<String,Object> assignee(long team, long account) {
        return store.one("""
            select a.id,a.username from accounts a join team_members m on m.account_id=a.id
            where a.id=? and a.active=true and m.team_id=?
            """,account,team);
    }

    boolean invited(long account, long ticket) {
        return !store.rows("""
            select 1 from ticket_invites i where i.ticket_id=? and i.account_id=? and i.revoked_at is null
              and exists(select 1 from team_members m where m.account_id=i.account_id)
            """,ticket,account).isEmpty();
    }

    void ticketMutation(Actor actor, long ticket, long team) {
        lock(team);
        requireActive(actor);
        if (!internal(actor,team) && invited(actor.id(),ticket)) throw new Problem(403,"协作者只能在协作工单留言");
        requireTeam(actor,team);
    }

    static void addDefault(Store store, long account) {
        store.db.update("insert into team_members(team_id,account_id,team_role) values (?,?,'MEMBER')",DEFAULT_TEAM,account);
    }
}
