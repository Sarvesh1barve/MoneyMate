package com.moneymate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Remember a browser using proof of possession, never a persistent bearer token. */
@Service
public class DeviceSignIn {
  final JdbcTemplate db;
  final Auth auth;
  final Set<String> origins;

  DeviceSignIn(JdbcTemplate db, Auth auth, @Value("${moneymate.cors-origins}") String origins) {
    this.db = db;
    this.auth = auth;
    this.origins = new HashSet<>(Arrays.stream(origins.split(",")).map(String::strip).toList());
  }

  String origin(String origin) {
    if (origin == null || !origins.contains(origin)) throw new ApiError(403, "Sign-in origin is not allowed.");
    URI uri = URI.create(origin);
    if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1").contains(uri.getHost())))
      throw new ApiError(403, "Sign-in requires HTTPS or localhost.");
    return origin;
  }

  UUID challenge(String kind, UUID user, String origin, String payload) {
    db.update("delete from signin_challenge where expires_at<now()");
    UUID id = UUID.randomUUID();
    db.update("insert into signin_challenge(id,kind,user_id,origin,payload,expires_at) values (?,?,?,?,?,?)",
        id, kind, user, origin, payload, Timestamp.from(Instant.now().plusSeconds(120)));
    return id;
  }

  String consume(UUID id, String kind, String origin, UUID user) {
    var rows = db.queryForList("delete from signin_challenge where id=? and kind=? and origin=? and expires_at>now() and (cast(? as uuid) is null or user_id=?) returning payload", id, kind, origin, user, user);
    if (rows.isEmpty()) throw new ApiError(401, "Sign-in request expired or was already used. Try again.");
    return (String) rows.getFirst().get("payload");
  }

  public record Enroll(@NotNull UUID id, @NotBlank @Size(max=512) String publicKey, @NotBlank @Size(max=80) String name) {}
  public record Start(@NotNull UUID deviceId) {}
  public record Proof(@NotNull UUID deviceId, @NotNull UUID challengeId, @NotBlank @Size(max=200) String signature) {}

  @Transactional
  public Map<String, Object> enroll(UUID user, String token, String origin, Enroll input) {
    origin(origin);
    db.queryForObject("select id from app_user where id=? for update", UUID.class, user);
    auth.recent(token, user);
    try {
      var key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getUrlDecoder().decode(input.publicKey())));
      var parameters = AlgorithmParameters.getInstance("EC");
      parameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
      var expected = parameters.getParameterSpec(java.security.spec.ECParameterSpec.class);
      ApiError.require(key.getParams().getOrder().equals(expected.getOrder()) && key.getParams().getCurve().equals(expected.getCurve()), "Unsupported device key.");
    } catch (GeneralSecurityException | IllegalArgumentException e) { throw new ApiError(400, "Invalid device key."); }
    ApiError.require(db.queryForObject("select count(*) from remembered_device where user_id=? and expires_at>now()", Integer.class, user) < 10, "Remove an old remembered device first (maximum 10).");
    Instant expiry = Instant.now().plusSeconds(30L * 86400);
    db.update("insert into remembered_device(id,user_id,public_key,origin,name,expires_at) values (?,?,?,?,?,?)", input.id(), user, input.publicKey(), origin, input.name().strip(), Timestamp.from(expiry));
    db.update("update auth_session set device_id=? where token_hash=?", input.id(), Auth.hash(token));
    return Map.of("id", input.id(), "expiresAt", expiry);
  }

  @Transactional
  public Map<String, Object> start(String origin, UUID device) {
    origin(origin);
    var rows = db.queryForList("select user_id from remembered_device where id=? and origin=? and expires_at>now()", device, origin);
    if (rows.isEmpty()) throw new ApiError(401, "Remembered sign-in expired or was removed. Sign in again.");
    String value = "MoneyMate device sign-in\n" + device + "\n" + origin + "\n" + auth.secret();
    return Map.of("challengeId", challenge("DEVICE", (UUID) rows.getFirst().get("user_id"), origin, device + "\n" + value), "challenge", value);
  }

  @Transactional(noRollbackFor = ApiError.class)
  public Auth.Session verify(String origin, Proof proof) {
    origin(origin);
    String value = consume(proof.challengeId(), "DEVICE", origin, null);
    if (!value.startsWith(proof.deviceId() + "\n")) throw new ApiError(401, "Device sign-in failed.");
    var rows = db.queryForList("select * from remembered_device where id=? and origin=? and expires_at>now() for update", proof.deviceId(), origin);
    if (rows.isEmpty()) throw new ApiError(401, "Remembered sign-in expired or was removed. Sign in again.");
    var device = rows.getFirst();
    try {
      var key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getUrlDecoder().decode((String) device.get("public_key"))));
      var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
      verifier.initVerify(key);
      verifier.update(value.substring(value.indexOf('\n') + 1).getBytes(StandardCharsets.UTF_8));
      if (!verifier.verify(Base64.getUrlDecoder().decode(proof.signature()))) throw new GeneralSecurityException();
    } catch (GeneralSecurityException | IllegalArgumentException e) { throw new ApiError(401, "Device sign-in failed."); }
    db.update("update remembered_device set last_used_at=now() where id=?", proof.deviceId());
    return auth.issueDevice((UUID) device.get("user_id"), proof.deviceId());
  }

  public List<Map<String, Object>> list(UUID user) {
    return db.queryForList("select id,name,created_at,expires_at,last_used_at from remembered_device where user_id=? and expires_at>now() order by created_at desc", user);
  }

  @Transactional
  public void revoke(UUID user, UUID device) {
    db.update("delete from remembered_device where id=? and user_id=?", device, user);
  }
}

@RestController
@RequestMapping("/api/auth")
class DeviceSignInController {
  final DeviceSignIn service;
  final Auth auth;
  DeviceSignInController(DeviceSignIn service, Auth auth) { this.service = service; this.auth = auth; }

  @PostMapping("/devices")
  Object enroll(Authentication a, @RequestHeader("Authorization") String token, @RequestHeader("Origin") String origin, @Valid @RequestBody DeviceSignIn.Enroll input) {
    return service.enroll(Auth.id(a), token.substring(7), origin, input);
  }
  @GetMapping("/devices")
  Object list(Authentication a) { return service.list(Auth.id(a)); }
  @DeleteMapping("/devices/{id}")
  Object revoke(Authentication a, @PathVariable UUID id) { service.revoke(Auth.id(a), id); return Map.of("ok", true); }
  @PostMapping("/device/challenge")
  Object start(@RequestHeader("Origin") String origin, @Valid @RequestBody DeviceSignIn.Start input, HttpServletRequest request) {
    auth.throttle("device:" + request.getRemoteAddr(), 80);
    return service.start(origin, input.deviceId());
  }
  @PostMapping("/device/verify")
  Object verify(@RequestHeader("Origin") String origin, @Valid @RequestBody DeviceSignIn.Proof input, HttpServletRequest request) {
    auth.throttle("device-proof:" + request.getRemoteAddr(), 80);
    return service.verify(origin, input);
  }
}
