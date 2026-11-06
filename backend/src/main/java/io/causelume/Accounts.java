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
    final LoginAttempts attempts;
    final String dummyHash;
    Accounts(Store store, Environment env, BCryptPasswordEncoder encoder,LoginAttempts attempts) {
        this.store=store; this.env=env; this.encoder=encoder;
        this.attempts=attempts;
        this.dummyHash=encoder.encode(java.util.UUID.randomUUID().toString());
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
        String peer=req.getRemoteAddr();
        var decision=attempts.check(peer);
        if (decision!=LoginAttempts.Decision.ALLOW) {
            if(decision==LoginAttempts.Decision.FIRST_LIMIT) store.audit(peer,"LOGIN_LIMITED",0);
            throw new Problem(429,"登录请求过多，请在一分钟后重试");
        }
        String username=login.username==null ? "" : login.username.strip();
        var rows=username.length()>64 ? java.util.List.<Map<String,Object>>of()
                : store.rows("select * from accounts where username=?",username);
        String hash = rows.isEmpty() ? dummyHash : (String)rows.getFirst().get("passwordHash");
        if (login.password==null || login.password.getBytes(StandardCharsets.UTF_8).length>72
            || !encoder.matches(login.password,hash) || rows.isEmpty()) {
            store.audit(peer,"LOGIN_DENIED",0);
            throw new Problem(401,"用户名或密码错误");
        }
        var row=rows.getFirst();
        var actor=new Actor((long)row.get("id"),(String)row.get("username"),(String)row.get("role"));
        store.audit(actor.username(),"LOGIN",actor.id());
        if (req.getSession(false)!=null) req.getSession(false).invalidate();
        req.getSession(true).setAttribute("actor",actor);
        return actor;
    }

    @GetMapping("/me") Actor me(HttpServletRequest req) { return Access.actor(req); }
    @PostMapping("/logout") Object logout(HttpServletRequest req) {
        var actor=Access.actor(req);
        store.audit(actor.username(),"LOGOUT",actor.id());
        req.getSession().invalidate();
        return Map.of("ok",true);
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
