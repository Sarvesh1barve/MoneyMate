package com.moneymate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
public class Auth {
  private final JdbcTemplate db;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final String dummy = encoder.encode("not-a-real-account-password");
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Window> attempts = new ConcurrentHashMap<>();

  private record Window(long start, int count) {}

  public record User(UUID id, String email, String name) {}

  public record Session(String token, User user, Instant expiresAt) {}

  public record Credentials(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 64) String password,
      @Size(max = 80) String name) {}

  public Auth(JdbcTemplate db) {
    this.db = db;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public String secret() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  synchronized void throttle(String key, int max) {
    long now = System.currentTimeMillis();
    attempts.entrySet().removeIf(e -> now - e.getValue().start > 60_000);
    Window old = attempts.getOrDefault(key, new Window(now, 0));
    if (old.count >= max || attempts.size() > 10000)
      throw new ApiError(429, "Too many attempts. Try again in a minute.");
    attempts.put(key, new Window(old.start, old.count + 1));
  }

  public void limit(String email, String address) {
    throttle("email:" + email.strip().toLowerCase(Locale.ROOT), 8);
    throttle("ip:" + address, 80);
  }

  @Transactional
  public Session register(Credentials input) {
    ApiError.require(input.name() != null && !input.name().isBlank(), "Enter a display name.");
    ApiError.require(
        input.password().getBytes(StandardCharsets.UTF_8).length <= 72,
        "Password must fit in 72 UTF-8 bytes.");
    UUID id = UUID.randomUUID();
    String email = input.email().strip().toLowerCase(Locale.ROOT);
    if (db.queryForObject("select count(*) from app_user where email=?", Integer.class, email) > 0)
      throw new ApiError(409, "Registration unavailable for these details.");
    db.update(
        "insert into app_user(id,email,display_name,password_hash) values (?,?,?,?)",
        id,
        email,
        input.name().strip(),
        encoder.encode(input.password()));
    for (String name :
        List.of(
            "Food & drinks",
            "Transport",
            "Shopping",
            "Home",
            "Health",
            "Travel",
            "Entertainment",
            "Salary",
            "Other"))
      db.update(
          "insert into record(id,kind,owner_id,version,body) values"
              + " (?,'category',?,1,jsonb_build_object('name',cast(? as text)))",
          UUID.randomUUID(),
          id,
          name);
    return issue(id);
  }

  public Session login(Credentials input) {
    var rows =
        db.queryForList(
            "select id,password_hash from app_user where email=?",
            input.email().strip().toLowerCase(Locale.ROOT));
    String passwordHash = rows.isEmpty() ? dummy : (String) rows.getFirst().get("password_hash");
    boolean match = encoder.matches(input.password(), passwordHash);
    if (!match || rows.isEmpty()) throw new ApiError(401, "Email or password is incorrect.");
    return issue((UUID) rows.getFirst().get("id"));
  }

  Session issue(UUID id) {
    db.update("delete from auth_session where expires_at < now()");
    String token = secret();
    Instant expires = Instant.now().plusSeconds(1800);
    db.update(
        "insert into auth_session(token_hash,user_id,expires_at) values (?,?,?)",
        hash(token),
        id,
        Timestamp.from(expires));
    return new Session(token, user(id), expires);
  }

  void recent(String token, UUID user) {
    if (db.queryForObject("select count(*) from auth_session where token_hash=? and user_id=? and strong_auth and created_at>now()-interval '5 minutes' and expires_at>now()", Integer.class, hash(token), user) != 1)
      throw new ApiError(403, "Sign in again with your password or passkey before changing sign-in methods.");
  }

  Session issueDevice(UUID id, UUID device) {
    Session session = issue(id);
    db.update("update auth_session set device_id=?,strong_auth=false where token_hash=?", device, hash(session.token()));
    return session;
  }

  public User user(UUID id) {
    return db.queryForObject(
        "select id,email,display_name from app_user where id=?",
        (r, n) -> new User(r.getObject(1, UUID.class), r.getString(2), r.getString(3)),
        id);
  }

  public UUID authenticate(String token) {
    var ids =
        db.query(
            "select user_id from auth_session where token_hash=? and expires_at>now()",
            (r, n) -> r.getObject(1, UUID.class),
            hash(token));
    return ids.isEmpty() ? null : ids.getFirst();
  }

  @Transactional
  public void logout(String token) {
    db.update("delete from remembered_device where id in (select device_id from auth_session where token_hash=?)", hash(token));
    db.update("delete from auth_session where token_hash=?", hash(token));
  }

  static UUID id(Authentication auth) {
    return UUID.fromString(auth.getName());
  }
}

@RestController
@RequestMapping("/api")
class AuthController {
  private final Auth auth;

  AuthController(Auth auth) {
    this.auth = auth;
  }

  @PostMapping("/auth/register")
  Auth.Session register(@Valid @RequestBody Auth.Credentials input, HttpServletRequest request) {
    auth.limit(input.email(), request.getRemoteAddr());
    return auth.register(input);
  }

  @PostMapping("/auth/login")
  Auth.Session login(@Valid @RequestBody Auth.Credentials input, HttpServletRequest request) {
    auth.limit(input.email(), request.getRemoteAddr());
    return auth.login(input);
  }

  @GetMapping("/me")
  Auth.User me(Authentication principal) {
    return auth.user(Auth.id(principal));
  }

  @PostMapping("/auth/logout")
  Map<String, Boolean> logout(@RequestHeader("Authorization") String header) {
    auth.logout(header.substring(7));
    return Map.of("ok", true);
  }

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "up");
  }
}
