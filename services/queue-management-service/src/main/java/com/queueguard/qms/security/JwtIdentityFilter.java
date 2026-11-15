package com.queueguard.qms.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueguard.common.dto.ApiResponse;
import com.queueguard.common.security.JwtUtil;
import jakarta.servlet.*; import jakarta.servlet.http.*;
import org.springframework.core.Ordered; import org.springframework.core.annotation.Order; import org.springframework.stereotype.Component; import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
/** Independently validates bearer JWTs; forwarded identity headers are never trusted. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class JwtIdentityFilter extends OncePerRequestFilter {
 public static final String USER_ID = "qms.userId"; private final JwtUtil jwt; private final ObjectMapper mapper = new ObjectMapper();
 public JwtIdentityFilter(JwtUtil jwt) { this.jwt=jwt; }
 @Override protected boolean shouldNotFilter(HttpServletRequest r) { String p=r.getRequestURI(); return p.equals("/health")||p.startsWith("/swagger-ui")||p.startsWith("/v3/api-docs")||p.startsWith("/actuator"); }
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c) throws ServletException,IOException {
  String auth=r.getHeader("Authorization"); if(auth==null||!auth.startsWith("Bearer ")){deny(s,"Missing bearer token");return;}
  try { long id=Long.parseLong(jwt.validate(auth.substring(7)).raw().path("uid").asText()); if(id<=0) throw new IllegalArgumentException(); r.setAttribute(USER_ID,id); c.doFilter(r,s); } catch(IllegalArgumentException e){deny(s,"Invalid token");}
 }
 private void deny(HttpServletResponse s,String message)throws IOException{s.setStatus(401);s.setContentType("application/json");s.getWriter().write(mapper.writeValueAsString(ApiResponse.fail(message)));}
}
