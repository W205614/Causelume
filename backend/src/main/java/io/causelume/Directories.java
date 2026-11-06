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
    ResponseEntity<?> users(@RequestParam(required=false) Long afterId, @RequestParam(defaultValue="100") int limit) {
        return page("/api/users","select id,username,role,active from accounts",false,afterId,limit);
    }

    @GetMapping("/services")
    ResponseEntity<?> services(@RequestParam(required=false) Long afterId, @RequestParam(defaultValue="100") int limit) {
        return page("/api/services","select * from services",false,afterId,limit);
    }

    @GetMapping("/audit")
    ResponseEntity<?> audit(@RequestParam(required=false) Long afterId, @RequestParam(defaultValue="100") int limit,HttpServletRequest req) {
        Access.actor(req).admin();
        return page("/api/audit","select * from audit",true,afterId,limit);
    }

    private ResponseEntity<List<Map<String,Object>>> page(String path,String query,boolean descending,Long cursor,int limit) {
        if (limit < 1 || limit > 100 || (cursor != null && cursor < 1))
            throw new Problem(400,"limit 应为 1–100，afterId 必须是正整数");
        String sort = descending ? " order by id desc limit ?" : " order by id limit ?";
        var rows = cursor == null ? store.rows(query+sort,limit+1)
                : store.rows(query+(descending ? " where id<?" : " where id>?")+sort,cursor,limit+1);
        if (rows.size() <= limit) return ResponseEntity.ok(rows);
        String next = rows.get(limit-1).get("id").toString();
        return ResponseEntity.ok().header("X-Next-Cursor",next)
                .header("Link","<"+path+"?afterId="+next+"&limit="+limit+">; rel=\"next\"")
                .body(rows.subList(0,limit));
    }
}
