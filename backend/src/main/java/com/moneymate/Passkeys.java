package com.moneymate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
public class Passkeys {
  final DeviceSignIn devices;
  final ObjectMapper json;
  final JdbcTemplate db;
  final Auth auth;
  Passkeys(DeviceSignIn devices, ObjectMapper json, JdbcTemplate db, Auth auth) { this.devices = devices; this.json = json; this.db = db; this.auth = auth; }

  static ByteArray bytes(String encoded) { return new ByteArray(Base64.getUrlDecoder().decode(encoded)); }
  static ByteArray handle(UUID user) { return new ByteArray(user.toString().getBytes(StandardCharsets.UTF_8)); }
  static UUID user(ByteArray handle) { return UUID.fromString(new String(handle.getBytes(), StandardCharsets.UTF_8)); }

  RelyingParty rp(String origin) {
    devices.origin(origin);
    String rpId = URI.create(origin).getHost();
    CredentialRepository repository = new CredentialRepository() {
      public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        var credentials = db.query("select credential_id from passkey where user_id=? and rp_id=?", (r,n) -> PublicKeyCredentialDescriptor.builder().id(bytes(r.getString(1))).build(), UUID.fromString(username), rpId);
        return new HashSet<>(credentials);
      }
      public Optional<ByteArray> getUserHandleForUsername(String username) {
        return db.query("select id from app_user where id=?", (r,n) -> handle(r.getObject(1, UUID.class)), UUID.fromString(username)).stream().findFirst();
      }
      public Optional<String> getUsernameForUserHandle(ByteArray id) {
        try { return db.query("select id from app_user where id=?", (r,n) -> r.getObject(1,UUID.class).toString(), user(id)).stream().findFirst(); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
      }
      public Optional<RegisteredCredential> lookup(ByteArray id, ByteArray userHandle) {
        return lookupAll(id).stream().filter(c -> c.getUserHandle().equals(userHandle)).findFirst();
      }
      public Set<RegisteredCredential> lookupAll(ByteArray id) {
        return new HashSet<>(db.query("select * from passkey where credential_id=? and rp_id=?", (r,n) -> RegisteredCredential.builder()
          .credentialId(id).userHandle(handle(r.getObject("user_id",UUID.class))).publicKeyCose(bytes(r.getString("public_key_cose")))
          .signatureCount(r.getLong("signature_count")).backupEligible(r.getBoolean("backup_eligible")).backupState(r.getBoolean("backed_up")).build(), id.getBase64Url(), rpId));
      }
    };
    return RelyingParty.builder().identity(RelyingPartyIdentity.builder().id(rpId).name("MoneyMate").build())
      .credentialRepository(repository).origins(Set.of(origin)).allowOriginPort(false).allowOriginSubdomain(false)
      .preferredPubkeyParams(List.of(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.RS256)).build();
  }

  public record Finish(@NotNull UUID challengeId, @NotNull JsonNode credential, @Size(max=80) String name) {}

  @Transactional
  public Object enrollOptions(UUID user, String token, String origin) {
    auth.recent(token, user);
    var account = auth.user(user);
    var options = rp(origin).startRegistration(StartRegistrationOptions.builder()
      .user(UserIdentity.builder().name(user.toString()).displayName(account.name() + " · " + account.email()).id(handle(user)).build())
      .authenticatorSelection(AuthenticatorSelectionCriteria.builder().residentKey(ResidentKeyRequirement.REQUIRED).userVerification(UserVerificationRequirement.REQUIRED).build())
      .timeout(120000).build());
    try {
      return Map.of("challengeId", devices.challenge("PASSKEY_REGISTER", user, origin, options.toJson()), "options", json.readTree(options.toCredentialsCreateJson()));
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  @Transactional(noRollbackFor = ApiError.class)
  public Object enroll(UUID user, String token, String origin, Finish input) {
    auth.recent(token, user);
    String saved = devices.consume(input.challengeId(), "PASSKEY_REGISTER", devices.origin(origin), user);
    db.queryForObject("select id from app_user where id=? for update", UUID.class, user);
    ApiError.require(db.queryForObject("select count(*) from passkey where user_id=?", Integer.class, user) < 10, "Remove an old passkey first (maximum 10).");
    RegistrationResult result;
    try {
      result = rp(origin).finishRegistration(FinishRegistrationOptions.builder()
        .request(PublicKeyCredentialCreationOptions.fromJson(saved))
        .response(PublicKeyCredential.parseRegistrationResponseJson(json.writeValueAsString(input.credential()))).build());
    } catch (Exception e) { throw new ApiError(400, "Passkey could not be verified. Try creating it again."); }
    ApiError.require(result.isUserVerified(), "Device verification is required.");
    String name = input.name() == null || input.name().isBlank() ? "My passkey" : input.name().strip();
    db.update("insert into passkey(credential_id,user_id,rp_id,public_key_cose,signature_count,name,backup_eligible,backed_up) values (?,?,?,?,?,?,?,?)",
      result.getKeyId().getId().getBase64Url(), user, URI.create(origin).getHost(), result.getPublicKeyCose().getBase64Url(), result.getSignatureCount(), name, result.isBackupEligible(), result.isBackedUp());
    return Map.of("ok", true);
  }

  @Transactional
  public Object options(String origin) {
    var request = rp(origin).startAssertion(StartAssertionOptions.builder().userVerification(UserVerificationRequirement.REQUIRED).timeout(120000).build());
    try { return Map.of("challengeId", devices.challenge("PASSKEY_LOGIN", null, origin, request.toJson()), "options", json.readTree(request.toCredentialsGetJson())); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  @Transactional(noRollbackFor = ApiError.class)
  public Auth.Session verify(String origin, Finish input) {
    String saved = devices.consume(input.challengeId(), "PASSKEY_LOGIN", devices.origin(origin), null);
    AssertionResult result;
    try {
      var credential = PublicKeyCredential.parseAssertionResponseJson(json.writeValueAsString(input.credential()));
      // Serialize assertions for this credential before the library reads its counter.
      db.queryForList("select credential_id from passkey where credential_id=? for update", credential.getId().getBase64Url());
      result = rp(origin).finishAssertion(FinishAssertionOptions.builder().request(AssertionRequest.fromJson(saved)).response(credential).build());
      if (!result.isSuccess() || !result.isUserVerified()) throw new IllegalArgumentException();
    } catch (Exception e) { throw new ApiError(401, "Passkey sign-in failed. Try again or use your password."); }
    db.update("update passkey set signature_count=?,last_used_at=now(),backed_up=? where credential_id=?", result.getSignatureCount(), result.isBackedUp(), result.getCredentialId().getBase64Url());
    return auth.issue(user(result.getUserHandle()));
  }

  public Object list(UUID user) {
    return db.queryForList("select credential_id as id,name,rp_id,created_at,last_used_at from passkey where user_id=? order by created_at desc", user);
  }
  public void remove(UUID user, String credential) { db.update("delete from passkey where credential_id=? and user_id=?", credential, user); }
}

@RestController
@RequestMapping("/api/auth")
class PasskeyController {
  final Passkeys passkeys;
  final Auth auth;
  PasskeyController(Passkeys passkeys, Auth auth) { this.passkeys = passkeys; this.auth = auth; }
  @PostMapping("/passkeys/options")
  Object enrollOptions(Authentication a, @RequestHeader("Authorization") String token, @RequestHeader("Origin") String origin) { return passkeys.enrollOptions(Auth.id(a),token.substring(7), origin); }
  @PostMapping("/passkeys")
  Object enroll(Authentication a, @RequestHeader("Authorization") String token, @RequestHeader("Origin") String origin, @Valid @RequestBody Passkeys.Finish input) { return passkeys.enroll(Auth.id(a), token.substring(7), origin, input); }
  @GetMapping("/passkeys")
  Object list(Authentication a) { return passkeys.list(Auth.id(a)); }
  @DeleteMapping("/passkeys/{id}")
  Object remove(Authentication a, @PathVariable String id) { passkeys.remove(Auth.id(a),id); return Map.of("ok", true); }
  @PostMapping("/passkey/options")
  Object options(@RequestHeader("Origin") String origin, HttpServletRequest request) { auth.throttle("passkey:" + request.getRemoteAddr(), 80); return passkeys.options(origin); }
  @PostMapping("/passkey/verify")
  Object verify(@RequestHeader("Origin") String origin, @Valid @RequestBody Passkeys.Finish input, HttpServletRequest request) { auth.throttle("passkey-proof:" + request.getRemoteAddr(), 80); return passkeys.verify(origin, input); }
}
