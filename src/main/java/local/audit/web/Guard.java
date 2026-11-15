package local.audit.web;

import java.io.IOException;
import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 이 컴퓨터의 브라우저가 이 프로그램 화면에서 보낸 요청만 받는다.
 * 로그인이 없으므로, 다른 웹사이트가 몰래 이 주소로 요청을 보내는 일을 여기서 막는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Guard extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String host = req.getHeader("Host");
        String name = host == null ? "" : host.replaceFirst(":\\d+$", "").toLowerCase();
        if (!(name.equals("127.0.0.1") || name.equals("localhost") || name.equals("[::1]"))) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        boolean write = !("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod()));
        if (write) {
            String site = req.getHeader("Sec-Fetch-Site");
            String origin = req.getHeader("Origin");
            boolean ok = site == null || site.equals("same-origin") || site.equals("none");
            if (ok && origin != null) {
                try {
                    URI u = URI.create(origin);
                    String authority = u.getPort() < 0 ? u.getHost() : u.getHost() + ":" + u.getPort();
                    ok = authority != null && authority.equalsIgnoreCase(host);
                } catch (RuntimeException e) {
                    ok = false;
                }
            }
            if (!ok) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("X-Frame-Options", "SAMEORIGIN");
        res.setHeader("Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'");
        chain.doFilter(req, res);
    }
}
