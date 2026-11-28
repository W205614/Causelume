package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class Directories {
    private final Store store;
    Directories(Store store) { this.store = store; }

    @GetMapping("/users")
    ResponseEntity<?> users(@RequestParam(required=false) Long afterId, @RequestParam(defaultValue="100") int limit,
                            @RequestParam(required=false) Long teamId,HttpServletRequest req) {
        var actor=Access.actor(req);
        var scope=TeamAccess.scope(actor,"x.team_id",teamId);
        String where=TeamAccess.platform(actor)&&teamId==null?"1=1":
            "exists(select 1 from team_members x where x.account_id=a.id and "+scope.sql()+")";
        return page("/api/users","select a.id,a.username,a.role,a.active from accounts a where "+where,
            scope,false,afterId,limit,teamId);
    }

    @GetMapping("/services")
    ResponseEntity<?> services(@RequestParam(required=false) Long afterId, @RequestParam(defaultValue="100") int limit,
                               @RequestParam(required=false) Long teamId,HttpServletRequest req) {
        var scope=TeamAccess.scope(Access.actor(req),"s.team_id",teamId);
        return page("/api/services","select s.* from services s where "+scope.sql(),scope,false,afterId,limit,teamId);
    }

    @GetMapping("/audit")
    ResponseEntity<?> audit(@RequestParam(required=false) Long afterId, @RequestParam(defaultValue="100") int limit,HttpServletRequest req) {
        Access.actor(req).admin();
        var scope=TeamAccess.scope(Access.actor(req),"team_id",null);
        return page("/api/audit","select * from audit where "+scope.sql(),scope,true,afterId,limit,null);
    }

    private ResponseEntity<List<Map<String,Object>>> page(String path,String query,TeamAccess.Scope scope,boolean descending,Long cursor,int limit,Long teamId) {
        if (limit < 1 || limit > 100 || (cursor != null && cursor < 1))
            throw new Problem(400,"limit 应为 1–100，afterId 必须是正整数");
        String sort = descending ? " order by id desc limit ?" : " order by id limit ?";
        var rows = cursor == null ? store.rows(query+sort,scope.with(limit+1))
                : store.rows(query+(descending ? " and id<?" : " and id>?")+sort,scope.with(cursor,limit+1));
        if (rows.size() <= limit) return ResponseEntity.ok(rows);
        String next = rows.get(limit-1).get("id").toString();
        return ResponseEntity.ok().header("X-Next-Cursor",next)
                .header("Link","<"+path+"?afterId="+next+"&limit="+limit+(teamId==null?"":"&teamId="+teamId)+">; rel=\"next\"")
                .body(rows.subList(0,limit));
    }
}
