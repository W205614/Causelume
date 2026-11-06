package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class Accounts {
    final Store store;
    final Environment env;
    final BCryptPasswordEncoder encoder;
    Accounts(Store store, Environment env, BCryptPasswordEncoder encoder) {
        this.store=store; this.env=env; this.encoder=encoder;
    }

    @Transactional
    public void bootstrap() {
        if (store.db.queryForObject("select count(*) from accounts", Long.class)>0) return;
        create("admin",env.getRequiredProperty("APP_ADMIN_PASSWORD"),"ADMIN");
        create("operator",env.getRequiredProperty("APP_OPERATOR_PASSWORD"),"OPERATOR");
    }

    private long create(String username,String password,String role) {
        if (password==null || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new Problem(400,"密码至少 12 个字符，最多 72 个 UTF-8 字节");
        return store.insert("insert into accounts(username,password_hash,role) values (?,?,?)",
            Problem.text(username,64,"username"),encoder.encode(password),Problem.choice(role,Set.of("ADMIN","OPERATOR"),"role"));
    }

    record Login(String username,String password) {}
    record Account(String username,String password,String role) {}

    @GetMapping("/health") Object health() {
        store.db.queryForObject("select 1", Integer.class);
        return Map.of("status","UP");
    }
    @PostMapping("/login") Actor login(@RequestBody Login login,HttpServletRequest req) {
        var rows=store.rows("select * from accounts where username=?", Problem.text(login.username,64,"username"));
        if (login.password==null || login.password.getBytes(StandardCharsets.UTF_8).length>72 || rows.isEmpty()
            || !encoder.matches(login.password,(String)rows.getFirst().get("passwordHash")))
            throw new Problem(401,"用户名或密码错误");
        var row=rows.getFirst();
        var actor=new Actor((long)row.get("id"),(String)row.get("username"),(String)row.get("role"));
        if (req.getSession(false)!=null) req.getSession(false).invalidate();
        req.getSession(true).setAttribute("actor",actor);
        return actor;
    }

    @GetMapping("/me") Actor me(HttpServletRequest req) { return Access.actor(req); }
    @PostMapping("/logout") Object logout(HttpServletRequest req) {
        req.getSession().invalidate();
        return Map.of("ok",true);
    }
    @GetMapping("/users") Object users() { return store.rows("select id,username,role from accounts order by id"); }
    @GetMapping("/audit") Object audit(HttpServletRequest req) {
        Access.actor(req).admin();
        return store.rows("select * from audit order by id desc");
    }
    @PostMapping("/users") @Transactional
    public Object user(@RequestBody Account input,HttpServletRequest req) {
        Actor actor=Access.actor(req);
        actor.admin();
        long id=create(input.username,input.password,input.role);
        store.audit(actor.username(),"CREATE_USER",id);
        return store.one("select id,username,role from accounts where id=?",id);
    }
}
