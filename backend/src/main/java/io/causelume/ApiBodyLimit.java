package io.causelume;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class ApiBodyLimit extends OncePerRequestFilter {
    static final int MAX_BYTES = 16 * 1024;

    @Override protected boolean shouldNotFilter(HttpServletRequest req) {
        // Encoded characters and matrix parameters must not bypass the API limit.
        return !req.getServletPath().startsWith("/api/");
    }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        long declared = req.getContentLengthLong();
        if (declared == 0) {
            chain.doFilter(req,res);
            return;
        }
        if (declared > MAX_BYTES) {
            reject(res);
            return;
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            reject(res);
            return;
        }
        var input = new ByteArrayInputStream(body);
        chain.doFilter(new HttpServletRequestWrapper(req) {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b,int off,int len) { return input.read(b,off,len); }
                    @Override public boolean isReady() { return true; }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException("blocking JSON API"); }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
            }
        },res);
    }
    private void reject(HttpServletResponse res) throws IOException {
        res.setStatus(413);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"请求体超过 16 KiB\"}");
    }
}
