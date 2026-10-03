package com.moneymate;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class Security {
  @Bean
  SecurityFilterChain chain(
      HttpSecurity http, Auth auth, @Value("${moneymate.cors-origins}") String origins)
      throws Exception {
    var cors = new CorsConfiguration();
    cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::strip).toList());
    cors.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "ngrok-skip-browser-warning"));
    cors.setAllowCredentials(false);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", cors);
    return http.cors(c -> c.configurationSource(source))
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/auth/login", "/api/auth/register", "/api/health", "/api/auth/device/challenge", "/api/auth/device/verify", "/api/auth/passkey/options", "/api/auth/passkey/verify")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, error) -> {
                      res.setStatus(401);
                      res.setContentType("application/json");
                      res.getWriter()
                          .write(
                              "{\"message\":\"Sign in to synchronize. Your offline workspace is"
                                  + " still available.\"}");
                    }))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws IOException, ServletException {
                response.setHeader("Cache-Control", "no-store");
                HttpServletRequest bounded = request;
                if (request.getMethod().equals("POST")) {
                  byte[] body = request.getInputStream().readNBytes(65537);
                  if (body.length > 65536) {
                    response.sendError(413);
                    return;
                  }
                  bounded =
                      new HttpServletRequestWrapper(request) {
                        @Override
                        public ServletInputStream getInputStream() {
                          var input = new ByteArrayInputStream(body);
                          return new ServletInputStream() {
                            public int read() {
                              return input.read();
                            }

                            public boolean isFinished() {
                              return input.available() == 0;
                            }

                            public boolean isReady() {
                              return true;
                            }

                            public void setReadListener(ReadListener listener) {}
                          };
                        }
                      };
                }
                String header = request.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer ") && header.length() < 200) {
                  UUID user = auth.authenticate(header.substring(7));
                  if (user != null)
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                user.toString(), null, List.of()));
                }
                chain.doFilter(bounded, response);
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
