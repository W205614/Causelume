package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.net.URI;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Configuration;

record Actor(long id,String username,String role) implements Serializable {
    void admin() {
        if (!role.equals("ADMIN")) throw new Problem(403,"需要管理员权限");
    }
}

@Configuration
class Access implements WebMvcConfigurer, HandlerInterceptor {
    static Actor actor(HttpServletRequest req) {
        var session=req.getSession(false);
        if (session == null || !(session.getAttribute("actor") instanceof Actor actor)) throw new Problem(401,"请先登录");
        return actor;
    }

    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/**"); }

    public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
        res.setHeader("Cache-Control","no-store");
        if (!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD")) {
            String origin=req.getHeader("Origin");
            URI local=URI.create(req.getRequestURL().toString());
            if (origin != null && !origin.equals(local.getScheme()+"://"+local.getRawAuthority()))
                throw new Problem(403,"跨站写入被拒绝");
            String media=req.getContentType();
            if (media==null || !media.split(";",2)[0].trim().equalsIgnoreCase("application/json"))
                throw new Problem(415,"写入请求必须使用 application/json");
        }
        if (!req.getRequestURI().equals("/api/health") && !req.getRequestURI().equals("/api/login")) actor(req);
        return true;
    }
}
