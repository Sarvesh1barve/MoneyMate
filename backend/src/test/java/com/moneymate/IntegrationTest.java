package com.moneymate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.upokecenter.cbor.CBORObject;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc(
    print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTest {
  static final EmbeddedPostgres postgres;

  static {
    try {
      postgres = EmbeddedPostgres.builder().setPort(0).start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired Auth auth;
  @Autowired Records records;
  Auth.Session a, b, c;

  @BeforeAll
  void users() {
    a = auth.register(new Auth.Credentials("alice@test.invalid", "A-long-test-password!", "Alice"));
    b = auth.register(new Auth.Credentials("bob@test.invalid", "A-long-test-password!", "Bob"));
    c =
        auth.register(
            new Auth.Credentials("charlie@test.invalid", "A-long-test-password!", "Charlie"));
  }

  ObjectNode body(Object... args) {
    ObjectNode n = json.createObjectNode();
    for (int i = 0; i < args.length; i += 2)
      n.set(args[i].toString(), json.valueToTree(args[i + 1]));
    return n;
  }

  Records.Operation op(String kind, UUID id, UUID trip, long version, ObjectNode body) {
    return new Records.Operation(UUID.randomUUID(), id, kind, trip, version, false, body);
  }

  JsonNode postJson(String path, Object body, Auth.Session session, int status) throws Exception {
    var req =
        post("/api" + path).contentType("application/json").content(json.writeValueAsBytes(body));
    if (session != null) req.header("Authorization", "Bearer " + session.token());
    MvcResult result = mvc.perform(req).andExpect(status().is(status)).andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  JsonNode postOrigin(String path, Object body, Auth.Session session, int expected) throws Exception {
    var request = post("/api" + path).contentType("application/json").header("Origin", "https://sarvesh1barve.github.io")
        .content(json.writeValueAsBytes(body));
    if (session != null) request.header("Authorization", "Bearer " + session.token());
    var result = mvc.perform(request).andReturn();
    if (result.getResponse().getStatus() != expected)
      throw new AssertionError(path + " returned " + result.getResponse().getStatus() + ": " + result.getResponse().getContentAsString() + " / " + result.getResolvedException());
    return json.readTree(result.getResponse().getContentAsString());
  }

  static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  static byte[] sha(byte[] bytes) { try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (GeneralSecurityException e) { throw new IllegalStateException(e); } }

  @Test
  void generalInvitationCreatesParticipantAndGrantsOnlyThatTrip() throws Exception {
    UUID[] first = trip(), second = trip();
    int prior = records.database().queryForObject("select count(*) from record where trip_id=? and kind='participant'", Integer.class, first[0]);
    String token = postJson("/trips/" + first[0] + "/invitations", body("participantId", null), a, 200).path("token").asText();
    postJson("/invitations/join", body("token", token), c, 200);
    assertEquals(prior + 1, records.database().queryForObject("select count(*) from record where trip_id=? and kind='participant'", Integer.class, first[0]));
    assertTrue(sync(c).toString().contains(first[0].toString()));
    assertFalse(sync(c).toString().contains(second[0].toString()));
    postJson("/invitations/join", body("token", token), c, 200);
    postJson("/invitations/join", body("token", token), b, 400);
    mvc.perform(delete("/api/trips/" + first[0] + "/members/" + c.user().id()).header("Authorization", "Bearer " + a.token()))
       .andExpect(status().isOk());
    assertFalse(sync(c).toString().contains(first[0].toString()));
    postJson("/invitations/join", body("token", token), c, 400);
  }

  @Test
  void rememberedDeviceUsesSignedSingleUseChallengesAndCanBeRevoked() throws Exception {
    Auth.Session fresh = auth.login(new Auth.Credentials("alice@test.invalid", "A-long-test-password!", "Alice"));
    var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
    var key = generator.generateKeyPair(); UUID id = UUID.randomUUID();
    postOrigin("/auth/devices", body("id", id, "publicKey", b64(key.getPublic().getEncoded()), "name", "Test phone"), fresh, 200);
    var challenge = postOrigin("/auth/device/challenge", body("deviceId", id), null, 200);
    var signer = Signature.getInstance("SHA256withECDSAinP1363Format"); signer.initSign(key.getPrivate());
    signer.update(challenge.path("challenge").asText().getBytes(StandardCharsets.UTF_8));
    var proof = body("deviceId", id, "challengeId", challenge.path("challengeId").asText(), "signature", b64(signer.sign()));
    var session = postOrigin("/auth/device/verify", proof, null, 200);
    assertEquals(a.user().id().toString(), session.path("user").path("id").asText());
    postOrigin("/auth/device/verify", proof, null, 401);
    postOrigin("/auth/passkeys/options", body(), new Auth.Session(session.path("token").asText(), a.user(), java.time.Instant.now()), 403);
    mvc.perform(delete("/api/auth/devices/" + id).header("Authorization", "Bearer " + fresh.token()))
      .andExpect(status().isOk());
    postOrigin("/auth/device/challenge", body("deviceId", id), null, 401);
    mvc.perform(get("/api/me").header("Authorization", "Bearer " + session.path("token").asText()))
      .andExpect(status().isUnauthorized());
  }

  @Test
  void passkeyCeremoniesRequireVerifiedDeviceAndRejectReplay() throws Exception {
    Auth.Session fresh = auth.login(new Auth.Credentials("alice@test.invalid", "A-long-test-password!", "Alice"));
    var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
    var key = generator.generateKeyPair();
    var registration = postOrigin("/auth/passkeys/options", body(), fresh, 200);
    String id = b64(sha(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
    var ec = (ECPublicKey) key.getPublic();
    byte[] x = Arrays.copyOfRange(ec.getW().getAffineX().toByteArray(), Math.max(0, ec.getW().getAffineX().toByteArray().length-32), ec.getW().getAffineX().toByteArray().length);
    byte[] y = Arrays.copyOfRange(ec.getW().getAffineY().toByteArray(), Math.max(0, ec.getW().getAffineY().toByteArray().length-32), ec.getW().getAffineY().toByteArray().length);
    CBORObject cose = CBORObject.NewMap().Add(1,2).Add(3,-7).Add(-1,1).Add(-2,x).Add(-3,y);
    byte[] rp = sha("sarvesh1barve.github.io".getBytes(StandardCharsets.UTF_8));
    var authData = ByteBuffer.allocate(32+1+4+16+2+32+cose.EncodeToBytes().length);
    authData.put(rp).put((byte)0x45).putInt(0).put(new byte[16]).putShort((short)32).put(Base64.getUrlDecoder().decode(id)).put(cose.EncodeToBytes());
    byte[] client = json.writeValueAsBytes(body("type", "webauthn.create", "challenge", registration.path("options").path("publicKey").path("challenge").asText(), "origin", "https://sarvesh1barve.github.io", "crossOrigin", false));
    byte[] attestation = CBORObject.NewMap().Add("fmt", "none").Add("attStmt", CBORObject.NewMap()).Add("authData", authData.array()).EncodeToBytes();
    var credential = body("id", id, "rawId", id, "type", "public-key", "response", body("clientDataJSON", b64(client), "attestationObject", b64(attestation), "transports", List.of("internal")), "clientExtensionResults", body());
    postOrigin("/auth/passkeys", body("challengeId", registration.path("challengeId").asText(), "credential", credential, "name", "Test biometric"), fresh, 200);
    var login = postOrigin("/auth/passkey/options", body(), null, 200);
    byte[] data = ByteBuffer.allocate(37).put(rp).put((byte)0x05).putInt(1).array();
    byte[] loginClient = json.writeValueAsBytes(body("type", "webauthn.get", "challenge", login.path("options").path("publicKey").path("challenge").asText(), "origin", "https://sarvesh1barve.github.io", "crossOrigin", false));
    var signer = Signature.getInstance("SHA256withECDSA"); signer.initSign(key.getPrivate());
    signer.update(data); signer.update(sha(loginClient));
    var assertion = body("id", id, "rawId", id, "type", "public-key", "response", body("clientDataJSON", b64(loginClient), "authenticatorData", b64(data), "signature", b64(signer.sign()), "userHandle", b64(a.user().id().toString().getBytes(StandardCharsets.UTF_8))), "clientExtensionResults", body());
    var proof = body("challengeId", login.path("challengeId").asText(), "credential", assertion);
    assertEquals(a.user().id().toString(), postOrigin("/auth/passkey/verify", proof, null, 200).path("user").path("id").asText());
    postOrigin("/auth/passkey/verify", proof, null, 401);
    mvc.perform(delete("/api/auth/passkeys/"+id).header("Authorization", "Bearer "+fresh.token())).andExpect(status().isOk());
    var another = postOrigin("/auth/passkey/options", body(), null, 200);
    postOrigin("/auth/passkey/verify", body("challengeId", another.path("challengeId").asText(), "credential", assertion), null, 401);
  }

  JsonNode sync(Auth.Session session) throws Exception {
    return json.readTree(
        mvc.perform(get("/api/sync").header("Authorization", "Bearer " + session.token()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  UUID[] trip() throws Exception {
    UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), guest = UUID.randomUUID();
    postJson(
        "/sync",
        op(
            "trip",
            id,
            null,
            0,
            body(
                "name",
                "Weekend",
                "currency",
                "INR",
                "groupType",
                "Trip",
                "ownerParticipantId",
                owner,
                "ownerName",
                "Alice")),
        a,
        200);
    postJson("/sync", op("participant", guest, id, 0, body("name", "Bob")), a, 200);
    return new UUID[] {id, owner, guest};
  }

  String invite(UUID[] trip) throws Exception {
    return postJson("/trips/" + trip[0] + "/invitations", body("participantId", trip[2]), a, 200)
        .path("token")
        .asText();
  }

  Records.Operation expense(UUID[] trip, UUID payer, long amount) {
    return op(
        "expense",
        UUID.randomUUID(),
        trip[0],
        0,
        body(
            "description",
            "Dinner",
            "date",
            "2026-10-01",
            "currency",
            "INR",
            "amount",
            amount,
            "payerId",
            payer,
            "method",
            "EQUAL",
            "parts",
            List.of(
                body("participantId", trip[1], "value", 1),
                body("participantId", trip[2], "value", 1))));
  }

  @Test
  void expenseCreationTimeSurvivesEditsAndIsReturnedInSnapshots() throws Exception {
    UUID[] t = trip();
    var initial = expense(t, t[1], 10000);
    var created = postJson("/sync", initial, a, 200);
    String createdAt = created.path("createdAt").asText();
    assertFalse(createdAt.isBlank());
    var changed = initial.body().deepCopy().put("description", "Dinner corrected");
    var edited = postJson("/sync", op("expense", initial.id(), t[0], 1, changed), a, 200);
    assertEquals(createdAt, edited.path("createdAt").asText());
    assertEquals(createdAt, records.find(initial.id()).createdAt());
    boolean found = false;
    for (JsonNode record : sync(a).path("records"))
      if (record.path("id").asText().equals(initial.id().toString())) {
        assertEquals(createdAt, record.path("createdAt").asText());
        found = true;
      }
    assertTrue(found);
  }

  @Test
  void authenticationAndCors() throws Exception {
    mvc.perform(get("/api/sync")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/me").header("Authorization", "Bearer " + a.token()))
        .andExpect(jsonPath("$.id").value(a.user().id().toString()));
    postJson(
        "/auth/login",
        body("email", "alice@test.invalid", "password", "Wrong-long-password"),
        null,
        401);
    mvc.perform(
            options("/api/sync")
                .header("Origin", "https://evil.invalid")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
    mvc.perform(
            options("/api/sync")
                .header("Origin", "https://sarvesh1barve.github.io")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Access-Control-Allow-Origin", "https://sarvesh1barve.github.io"));
    var login =
        postJson(
            "/auth/login",
            body("email", "bob@test.invalid", "password", "A-long-test-password!"),
            null,
            200);
    var token = login.path("token").asText();
    mvc.perform(
            post("/api/auth/logout")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void personalRecordsArePrivateAndPersistent() throws Exception {
    UUID account = UUID.randomUUID();
    var create =
        op(
            "account",
            account,
            null,
            0,
            body("name", "Private bank", "currency", "INR", "openingBalance", 10000));
    postJson("/sync", create, a, 200);
    assertTrue(sync(a).toString().contains(account.toString()));
    assertFalse(sync(b).toString().contains(account.toString()));
    postJson(
        "/sync",
        op(
            "account",
            account,
            null,
            1,
            body("name", "Stolen", "currency", "INR", "openingBalance", 0)),
        b,
        403);
    assertEquals("Private bank", records.find(account).body().path("name").asText());
    var tx =
        op(
            "transaction",
            UUID.randomUUID(),
            null,
            0,
            body(
                "description",
                "Unauthorized account",
                "amount",
                10,
                "type",
                "EXPENSE",
                "date",
                "2026-10-01",
                "currency",
                "INR",
                "accountId",
                account));
    postJson("/sync", tx, b, 400);
  }

  @Test
  void invitesAreSingleUseAndMembershipControlsAllTripWrites() throws Exception {
    UUID[] t = trip();
    var expense = expense(t, t[1], 10001);
    postJson("/sync", expense, b, 403);
    assertFalse(sync(b).toString().contains(t[0].toString()));
    String token = invite(t);
    postJson("/invitations/join", body("token", token), b, 200);
    postJson("/invitations/join", body("token", token), c, 400);
    JsonNode written = postJson("/sync", expense, a, 200);
    assertEquals(
        10001,
        written.path("body").path("allocations").findValues("amount").stream()
            .mapToLong(JsonNode::asLong)
            .sum());
    postJson("/sync", expense(t, t[2], 3000), b, 200);
    assertTrue(sync(b).toString().contains(expense.id().toString()));
    assertTrue(sync(a).toString().contains(t[2].toString()));
    postJson(
        "/sync",
        op("participant", UUID.randomUUID(), t[0], 0, body("name", "Unauthorized invite target")),
        b,
        403);
    mvc.perform(
            delete("/api/trips/" + t[0] + "/members/" + b.user().id())
                .header("Authorization", "Bearer " + a.token()))
        .andExpect(status().isOk());
    assertFalse(sync(b).toString().contains(t[0].toString()));
    postJson("/sync", expense, b, 403);
    postJson("/sync", expense(t, t[2], 1), b, 403);
    assertFalse(sync(c).toString().contains(t[0].toString()));
  }

  @Test
  void retriesVersionsAndTombstones() throws Exception {
    UUID id = UUID.randomUUID();
    var first =
        op(
            "account",
            id,
            null,
            0,
            body("name", "Idempotent", "currency", "INR", "openingBalance", 0));
    JsonNode one = postJson("/sync", first, a, 200);
    JsonNode two = postJson("/sync", first, a, 200);
    assertEquals(one, two);
    assertEquals(1, records.find(id).version());
    postJson(
        "/sync",
        new Records.Operation(
            first.operationId(),
            id,
            "account",
            null,
            0,
            false,
            body("name", "Changed retry", "currency", "INR", "openingBalance", 0)),
        a,
        409);
    postJson("/sync", op("account", id, null, 0, first.body()), a, 409);
    postJson(
        "/sync",
        new Records.Operation(UUID.randomUUID(), id, "account", null, 1, true, first.body()),
        a,
        200);
    postJson("/sync", op("account", id, null, 2, first.body()), a, 409);
    assertTrue(records.find(id).deleted());
  }

  @Test
  void invalidSplitsCurrencyAndSettlementPermissions() throws Exception {
    UUID[] t = trip();
    postJson("/invitations/join", body("token", invite(t)), b, 200);
    var expense = expense(t, t[1], 10000);
    expense.body().put("currency", "USD");
    postJson("/sync", expense, a, 400);
    expense.body().put("currency", "INR");
    expense.body().put("method", "EXACT");
    postJson("/sync", expense, a, 400);
    expense.body().put("method", "EQUAL");
    postJson("/sync", expense, a, 200);
    var settlement =
        op(
            "settlement",
            UUID.randomUUID(),
            t[0],
            0,
            body("from", t[2], "to", t[1], "amount", 5000, "currency", "INR", "status", "Pending"));
    postJson("/sync", settlement, b, 200);
    var paid = settlement.body().deepCopy().put("status", "Paid");
    postJson("/sync", op("settlement", settlement.id(), t[0], 1, paid), b, 200);
    var confirmed = paid.deepCopy().put("status", "Confirmed");
    postJson("/sync", op("settlement", settlement.id(), t[0], 2, confirmed), b, 403);
    postJson("/sync", op("settlement", settlement.id(), t[0], 2, confirmed), a, 200);
    assertTrue(records.suggestions(t[0], a.user().id()).isEmpty());
  }

  @Test
  void invitationExpiryAndOversizedInput() throws Exception {
    UUID[] t = trip();
    String token = invite(t);
    records
        .database()
        .update(
            "update invitation set expires_at=now()-interval '1 second' where token_hash=?",
            Auth.hash(token));
    postJson("/invitations/join", body("token", token), b, 400);
    mvc.perform(
            post("/api/sync")
                .header("Authorization", "Bearer " + a.token())
                .contentType("application/json")
                .content("x".repeat(65537)))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void concurrentEditsAcceptExactlyOneVersion() throws Exception {
    UUID id = UUID.randomUUID();
    records.write(
        a.user().id(),
        op(
            "account",
            id,
            null,
            0,
            body("name", "Concurrent", "currency", "INR", "openingBalance", 0)));
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var tasks = List.of(pool.submit(() -> attempt(id)), pool.submit(() -> attempt(id)));
      int accepted = 0;
      for (var task : tasks) accepted += task.get();
      assertEquals(1, accepted);
      assertEquals(2, records.find(id).version());
    }
  }

  int attempt(UUID id) {
    try {
      records.write(
          a.user().id(),
          op(
              "account",
              id,
              null,
              1,
              body("name", "Updated", "currency", "INR", "openingBalance", 1)));
      return 1;
    } catch (ApiError e) {
      assertEquals(409, e.status);
      return 0;
    }
  }

  @Test
  void differentMembersCannotOverwriteSameExpenseVersion() throws Exception {
    UUID[] t = trip();
    postJson("/invitations/join", body("token", invite(t)), b, 200);
    var first = expense(t, t[1], 10000);
    records.write(a.user().id(), first);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var results = new ArrayList<java.util.concurrent.Future<Integer>>();
      for (var session : List.of(a, b))
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    records.write(
                        session.user().id(), op("expense", first.id(), t[0], 1, first.body()));
                    return 1;
                  } catch (ApiError e) {
                    assertEquals(409, e.status);
                    return 0;
                  }
                }));
      start.countDown();
      int accepted = 0;
      for (var result : results) accepted += result.get();
      assertEquals(1, accepted);
      assertEquals(2, records.find(first.id()).version());
    }
  }

  @Test
  void deletedTripRetryIsIdempotent() throws Exception {
    UUID[] t = trip();
    var existing = records.find(t[0]);
    var deletion =
        new Records.Operation(
            UUID.randomUUID(), t[0], "trip", null, 1, true, (ObjectNode) existing.body());
    assertEquals(postJson("/sync", deletion, a, 200), postJson("/sync", deletion, a, 200));
    assertFalse(sync(a).path("records").toString().contains(t[0].toString()));
  }

  @Test
  void loginRateLimitAndSessionExpiry() throws Exception {
    for (int i = 0; i < 8; i++)
      postJson(
          "/auth/login",
          body("email", "absent@test.invalid", "password", "Wrong-password-123"),
          null,
          401);
    postJson(
        "/auth/login",
        body("email", "absent@test.invalid", "password", "Wrong-password-123"),
        null,
        429);
    var session =
        auth.login(new Auth.Credentials("charlie@test.invalid", "A-long-test-password!", null));
    records
        .database()
        .update(
            "update auth_session set expires_at=now()-interval '1 second' where token_hash=?",
            Auth.hash(session.token()));
    mvc.perform(get("/api/me").header("Authorization", "Bearer " + session.token()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validatesPersonalAmountsTransfersAndBudgetReferences() throws Exception {
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    records.write(
        a.user().id(),
        op("account", first, null, 0, body("name", "One", "currency", "INR", "openingBalance", 0)));
    records.write(
        a.user().id(),
        op(
            "account",
            second,
            null,
            0,
            body("name", "Two", "currency", "INR", "openingBalance", 0)));
    ObjectNode tx =
        body(
            "description",
            "Transfer",
            "type",
            "TRANSFER",
            "date",
            "2026-10-01",
            "currency",
            "INR",
            "amount",
            100,
            "accountId",
            first,
            "toAccountId",
            first);
    postJson("/sync", op("transaction", UUID.randomUUID(), null, 0, tx), a, 400);
    tx.put("toAccountId", second.toString());
    postJson("/sync", op("transaction", UUID.randomUUID(), null, 0, tx), a, 200);
    tx.put("amount", -1);
    postJson("/sync", op("transaction", UUID.randomUUID(), null, 0, tx), a, 400);
    UUID[] t = trip();
    postJson(
        "/sync",
        op(
            "budget",
            UUID.randomUUID(),
            null,
            0,
            body(
                "name",
                "Not my trip",
                "amount",
                1000,
                "currency",
                "INR",
                "startDate",
                "2026-10-01",
                "period",
                "TRIP",
                "budgetTripId",
                t[0])),
        b,
        403);
    assertEquals(
        9,
        records
            .database()
            .queryForObject(
                "select count(*) from record where owner_id=? and kind='category'",
                Integer.class,
                a.user().id()));
  }
}
