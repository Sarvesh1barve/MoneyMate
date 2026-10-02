package com.moneymate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
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
