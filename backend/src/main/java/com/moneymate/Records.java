package com.moneymate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Records {
  final JdbcTemplate db;
  final ObjectMapper json;

  public record Entry(
      UUID id,
      String kind,
      UUID ownerId,
      UUID tripId,
      long version,
      boolean deleted,
      JsonNode body,
      String updatedAt) {}

  public record Operation(
      UUID operationId,
      UUID id,
      String kind,
      UUID tripId,
      long baseVersion,
      boolean deleted,
      ObjectNode body) {}

  public record Member(UUID tripId, UUID userId, UUID participantId, String role, String name) {}

  public Records(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public JdbcTemplate database() {
    return db;
  }

  JsonNode parse(String text) {
    try {
      return json.readTree(text);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String stringify(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  Entry map(ResultSet r, int n) throws SQLException {
    return new Entry(
        r.getObject("id", UUID.class),
        r.getString("kind"),
        r.getObject("owner_id", UUID.class),
        r.getObject("trip_id", UUID.class),
        r.getLong("version"),
        r.getBoolean("deleted"),
        parse(r.getString("body")),
        r.getTimestamp("updated_at").toInstant().toString());
  }

  Entry find(UUID id) {
    var rows = db.query("select * from record where id=?", this::map, id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  Member member(UUID trip, UUID user) {
    var rows =
        db.query(
            "select m.*,u.display_name from membership m join app_user u on u.id=m.user_id where"
                + " trip_id=? and user_id=?",
            (r, n) ->
                new Member(
                    trip,
                    user,
                    r.getObject("participant_id", UUID.class),
                    r.getString("role"),
                    r.getString("display_name")),
            trip,
            user);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  Member requireMember(UUID trip, UUID user) {
    var m = member(trip, user);
    if (m == null) throw new ApiError(403, "Trip access was revoked or is not available.");
    return m;
  }

  void owner(UUID trip, UUID user) {
    if (!requireMember(trip, user).role.equals("OWNER"))
      throw new ApiError(403, "Only the trip owner can do this.");
  }

  void lockUser(UUID user) {
    db.queryForObject("select id from app_user where id=? for update", UUID.class, user);
  }

  void lockTrip(UUID trip) {
    if (db.query(
            "select id from record where id=? and kind='trip' and deleted=false for update",
            (r, n) -> r.getObject(1, UUID.class),
            trip)
        .isEmpty()) throw new ApiError(403, "Trip is unavailable.");
  }

  void activity(UUID trip, UUID user, String action, UUID id) {
    db.update(
        "insert into activity(trip_id,actor_id,action,record_id) values (?,?,?,?)",
        trip,
        user,
        action,
        id);
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String, Object> snapshot(UUID user) {
    var entries =
        db.query(
            "select r.* from record r where (r.trip_id is null and r.kind<>'trip' and r.owner_id=?)"
                + " or exists(select 1 from membership m join record t on t.id=m.trip_id where"
                + " m.user_id=? and t.deleted=false and m.trip_id=case when r.kind='trip' then r.id"
                + " else r.trip_id end)",
            this::map,
            user,
            user);
    var members =
        db.query(
            "select m.*,u.display_name from membership m join app_user u on u.id=m.user_id join"
                + " record t on t.id=m.trip_id where t.deleted=false and m.trip_id in(select"
                + " trip_id from membership where user_id=?)",
            (r, n) ->
                new Member(
                    r.getObject("trip_id", UUID.class),
                    r.getObject("user_id", UUID.class),
                    r.getObject("participant_id", UUID.class),
                    r.getString("role"),
                    r.getString("display_name")),
            user);
    var activities =
        db.queryForList(
            "select a.id,a.trip_id,a.action,a.record_id,a.created_at,u.display_name as actor from"
                + " activity a join app_user u on u.id=a.actor_id join record t on t.id=a.trip_id"
                + " where t.deleted=false and a.trip_id in(select trip_id from membership where"
                + " user_id=?) order by a.id desc limit 300",
            user);
    return Map.of(
        "records",
        entries,
        "members",
        members,
        "activity",
        activities,
        "serverTime",
        Instant.now().toString());
  }

  @Transactional
  public Entry write(UUID user, Operation op) {
    ApiError.require(
        op.operationId != null
            && op.id != null
            && op.body != null
            && op.kind != null
            && op.baseVersion >= 0,
        "Incomplete operation.");
    ApiError.require(
        Set.of(
                "account",
                "category",
                "transaction",
                "budget",
                "settings",
                "trip",
                "participant",
                "expense",
                "settlement")
            .contains(op.kind),
        "Unknown record type.");
    boolean shared = Set.of("participant", "expense", "settlement").contains(op.kind);
    ApiError.require(shared == (op.tripId != null), "Invalid record scope.");
    lockUser(user);
    Entry old = find(op.id);
    if (old != null && (!old.kind.equals(op.kind) || !Objects.equals(old.tripId, op.tripId)))
      throw new ApiError(403, "Record scope cannot change.");
    UUID trip = op.kind.equals("trip") ? op.id : op.tripId;
    if (trip != null && !(op.kind.equals("trip") && old == null)) {
      if (op.kind.equals("trip") && old.deleted && op.deleted)
        db.queryForObject("select id from record where id=? for update", UUID.class, trip);
      else lockTrip(trip);
      requireMember(trip, user);
      // Another member may have committed while we waited for the trip lock.
      old = find(op.id);
      if (old != null && (!old.kind.equals(op.kind) || !Objects.equals(old.tripId, op.tripId)))
        throw new ApiError(403, "Record scope cannot change.");
    }
    if (trip == null && old != null && !old.ownerId.equals(user))
      throw new ApiError(403, "This record is private.");
    // Check access before returning a previously accepted operation.
    String fingerprint = Auth.hash(stringify(op));
    var previous =
        db.queryForList(
            "select fingerprint,result::text from sync_operation where user_id=? and"
                + " operation_id=?",
            user,
            op.operationId);
    if (!previous.isEmpty()) {
      if (!previous.getFirst().get("fingerprint").equals(fingerprint))
        throw new ApiError(409, "Operation ID was already used with different content.");
      try {
        return json.treeToValue(parse((String) previous.getFirst().get("result")), Entry.class);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    if ((old == null ? 0 : old.version) != op.baseVersion
        || (old != null && old.deleted && !op.deleted))
      throw new ApiError(
          409,
          "Another change exists. Review it before applying yours.",
          old == null ? Map.of() : old);
    if (op.kind.equals("trip") && old != null) owner(trip, user);
    if (op.kind.equals("participant")) owner(trip, user);
    if (op.deleted && old == null) throw new ApiError(400, "Cannot delete a missing record.");
    if (op.deleted && op.kind.equals("settlement"))
      throw new ApiError(400, "Cancel a settlement to keep its history.");
    if (op.deleted && op.kind.equals("participant")) {
      if (db.queryForObject(
              "select count(*) from membership where participant_id=?", Integer.class, op.id)
          > 0) throw new ApiError(400, "Remove linked membership first.");
      if (db.queryForObject(
              "select count(*) from record where trip_id=? and kind in ('expense','settlement') and"
                  + " deleted=false and body::text like ?",
              Integer.class,
              trip,
              "%" + op.id + "%")
          > 0) throw new ApiError(400, "Participant has financial history and must be retained.");
    }
    ObjectNode body = op.body.deepCopy();
    if (!op.deleted) validate(user, op, old, body);
    if (op.deleted) body = (ObjectNode) old.body;
    long version = op.baseVersion + 1;
    if (old == null)
      db.update(
          "insert into record(id,kind,owner_id,trip_id,version,deleted,body) values"
              + " (?,?,?,?,?,?,?::jsonb)",
          op.id,
          op.kind,
          user,
          op.tripId,
          version,
          op.deleted,
          stringify(body));
    else
      db.update(
          "update record set version=?,deleted=?,body=?::jsonb,updated_at=now() where id=?",
          version,
          op.deleted,
          stringify(body),
          op.id);
    if (op.kind.equals("trip") && old == null) {
      UUID participant = uuid(body, "ownerParticipantId");
      var participantBody = json.createObjectNode().put("name", text(body, "ownerName", 80));
      db.update(
          "insert into record(id,kind,owner_id,trip_id,version,body) values"
              + " (?,'participant',?,?,1,?::jsonb)",
          participant,
          user,
          op.id,
          stringify(participantBody));
      db.update(
          "insert into membership(trip_id,user_id,participant_id,role) values (?,?,?,'OWNER')",
          op.id,
          user,
          participant);
    }
    if (trip != null)
      activity(
          trip,
          user,
          (op.deleted ? "Deleted " : old == null ? "Created " : "Updated ") + op.kind,
          op.id);
    Entry result = find(op.id);
    db.update(
        "insert into sync_operation(user_id,operation_id,fingerprint,result) values"
            + " (?,?,?,?::jsonb)",
        user,
        op.operationId,
        fingerprint,
        stringify(result));
    return result;
  }

  static String text(JsonNode b, String key, int max) {
    ApiError.require(b.path(key).isTextual(), key + " must be text.");
    String v = b.path(key).asText("").strip();
    ApiError.require(!v.isEmpty() && v.length() <= max, "Enter a valid " + key + ".");
    return v;
  }

  static UUID uuid(JsonNode b, String key) {
    return UUID.fromString(text(b, key, 36));
  }

  static long number(JsonNode b, String key, long min, long max) {
    JsonNode v = b.path(key);
    ApiError.require(v.isIntegralNumber() && v.canConvertToLong(), key + " must be an integer.");
    long n = v.longValue();
    ApiError.require(n >= min && n <= max, key + " is out of range.");
    return n;
  }

  static void currency(JsonNode b) {
    String value = text(b, "currency", 3);
    ApiError.require(value.matches("[A-Z]{3}"), "Use a three-letter currency code.");
    try {
      java.util.Currency.getInstance(value);
    } catch (Exception e) {
      throw new ApiError(400, "Unsupported currency.");
    }
  }

  static void date(JsonNode b, String key) {
    LocalDate.parse(text(b, key, 10));
  }

  Entry reference(UUID id, String kind, UUID user, UUID trip) {
    Entry e = find(id);
    ApiError.require(
        e != null
            && e.kind.equals(kind)
            && (trip != null
                ? Objects.equals(e.tripId, trip)
                : e.ownerId.equals(user) && e.tripId == null),
        "Invalid " + kind + " reference.");
    return e;
  }

  void optionalReference(JsonNode body, String key, String kind, UUID user) {
    if (body.hasNonNull(key) && !body.path(key).asText().isBlank())
      reference(uuid(body, key), kind, user, null);
  }

  void validate(UUID user, Operation op, Entry old, ObjectNode b) {
    if (b.hasNonNull("notes"))
      ApiError.require(
          b.path("notes").isTextual() && b.path("notes").asText().length() <= 2000,
          "Notes must be text up to 2000 characters.");
    switch (op.kind) {
      case "account" -> {
        text(b, "name", 80);
        currency(b);
        number(b, "openingBalance", -Money.MAX, Money.MAX);
        if (old != null)
          ApiError.require(
              old.body.path("currency").equals(b.path("currency")),
              "Account currency cannot change.");
      }
      case "category" -> {
        text(b, "name", 80);
        optionalReference(b, "parentId", "category", user);
        if (b.hasNonNull("parentId") && !b.path("parentId").asText().isBlank()) {
          Entry parent = reference(uuid(b, "parentId"), "category", user, null);
          ApiError.require(
              !parent.id.equals(op.id) && parent.body.path("parentId").asText("").isBlank(),
              "Subcategories have one level.");
        }
      }
      case "settings" -> {
        ApiError.require(
            Set.of("light", "dark", "system").contains(text(b, "theme", 10)), "Invalid theme.");
        currency(b);
      }
      case "transaction" -> {
        number(b, "amount", 1, Money.MAX);
        currency(b);
        date(b, "date");
        text(b, "description", 160);
        String type = text(b, "type", 10);
        ApiError.require(
            Set.of("EXPENSE", "INCOME", "TRANSFER").contains(type), "Invalid transaction type.");
        optionalReference(b, "categoryId", "category", user);
        optionalReference(b, "accountId", "account", user);
        optionalReference(b, "toAccountId", "account", user);
        if (type.equals("TRANSFER"))
          ApiError.require(
              b.hasNonNull("accountId")
                  && !b.path("accountId").asText().isBlank()
                  && b.hasNonNull("toAccountId")
                  && !b.path("toAccountId").asText().isBlank()
                  && !b.path("accountId").equals(b.path("toAccountId")),
              "Select two different accounts.");
        for (String key : List.of("accountId", "toAccountId"))
          if (b.hasNonNull(key) && !b.path(key).asText().isBlank())
            ApiError.require(
                reference(uuid(b, key), "account", user, null)
                    .body
                    .path("currency")
                    .equals(b.path("currency")),
                "Account currency does not match.");
      }
      case "budget" -> {
        text(b, "name", 80);
        number(b, "amount", 1, Money.MAX);
        currency(b);
        date(b, "startDate");
        ApiError.require(
            Set.of("MONTH", "WEEK", "DAY", "TRIP").contains(text(b, "period", 10)),
            "Invalid budget period.");
        optionalReference(b, "categoryId", "category", user);
        if (b.path("period").asText().equals("TRIP")) {
          UUID id = uuid(b, "budgetTripId");
          requireMember(id, user);
          ApiError.require(
              find(id).body.path("currency").equals(b.path("currency")),
              "Budget currency must match trip.");
        }
      }
      case "trip" -> {
        text(b, "name", 100);
        currency(b);
        text(b, "groupType", 20);
        if (old == null) {
          uuid(b, "ownerParticipantId");
          text(b, "ownerName", 80);
        } else {
          ApiError.require(
              old.body.path("currency").equals(b.path("currency")), "Trip currency cannot change.");
          b.set("ownerParticipantId", old.body.path("ownerParticipantId"));
          b.set("ownerName", old.body.path("ownerName"));
        }
      }
      case "participant" -> {
        text(b, "name", 80);
        if (old == null)
          ApiError.require(
              db.queryForObject(
                      "select count(*) from record where kind='participant' and trip_id=? and"
                          + " deleted=false",
                      Integer.class,
                      op.tripId)
                  < 100,
              "Trip supports up to 100 participants.");
      }
      case "expense" -> {
        text(b, "description", 160);
        date(b, "date");
        currency(b);
        long amount = number(b, "amount", 1, Money.MAX);
        Entry trip = find(op.tripId);
        ApiError.require(
            trip.body.path("currency").equals(b.path("currency")), "Currency must match the trip.");
        Entry payer = reference(uuid(b, "payerId"), "participant", user, op.tripId);
        ApiError.require(!payer.deleted, "Payer is inactive.");
        ApiError.require(b.path("parts").isArray(), "Choose split participants.");
        var parts = new ArrayList<Money.Part>();
        for (JsonNode part : b.path("parts")) {
          UUID id = uuid(part, "participantId");
          ApiError.require(
              !reference(id, "participant", user, op.tripId).deleted,
              "Split participant is inactive.");
          parts.add(new Money.Part(id, number(part, "value", 0, Money.MAX)));
        }
        b.set("allocations", json.valueToTree(Money.split(amount, text(b, "method", 12), parts)));
        b.set("payers", json.valueToTree(List.of(new Money.Allocation(payer.id, amount))));
      }
      case "settlement" -> validateSettlement(user, op, old, b);
    }
  }

  void validateSettlement(UUID user, Operation op, Entry old, ObjectNode b) {
    UUID from = uuid(b, "from"), to = uuid(b, "to");
    reference(from, "participant", user, op.tripId);
    reference(to, "participant", user, op.tripId);
    ApiError.require(!from.equals(to), "Choose different participants.");
    long amount = number(b, "amount", 1, Money.MAX);
    currency(b);
    ApiError.require(
        find(op.tripId).body.path("currency").equals(b.path("currency")), "Currency mismatch.");
    String status = text(b, "status", 12);
    ApiError.require(
        Set.of("Pending", "Paid", "Confirmed", "Cancelled").contains(status),
        "Invalid settlement status.");
    var member = requireMember(op.tripId, user);
    boolean owner = member.role.equals("OWNER");
    if (old == null) ApiError.require(status.equals("Pending"), "New settlements start Pending.");
    else {
      for (String key : List.of("from", "to", "amount", "currency"))
        ApiError.require(
            old.body.path(key).equals(b.path(key)),
            "Settlement amount and parties are immutable. Cancel and create another.");
      String previous = old.body.path("status").asText();
      boolean transition =
          (previous.equals("Pending") && status.equals("Paid"))
              || (previous.equals("Paid") && status.equals("Confirmed"))
              || (!previous.equals("Cancelled") && status.equals("Cancelled"));
      ApiError.require(transition, "Invalid settlement transition.");
      if (status.equals("Paid") && !owner && !member.participantId.equals(from))
        throw new ApiError(403, "Only the payer or owner can mark Paid.");
      if (status.equals("Confirmed") && !owner && !member.participantId.equals(to))
        throw new ApiError(403, "Only the recipient or owner can confirm.");
      if (status.equals("Cancelled")
          && !owner
          && !(previous.equals("Pending") && member.participantId.equals(from)))
        throw new ApiError(403, "Only the owner can cancel this settlement.");
    }
    if (old == null || status.equals("Confirmed")) {
      var net = net(op.tripId);
      ApiError.require(
          net.getOrDefault(from, 0L) <= -amount && net.getOrDefault(to, 0L) >= amount,
          "Settlement exceeds the current outstanding balance.");
    }
  }

  Map<UUID, Long> net(UUID trip) {
    Map<UUID, Long> result = new HashMap<>();
    for (Entry e :
        db.query(
            "select * from record where trip_id=? and deleted=false and kind in"
                + " ('expense','settlement')",
            this::map,
            trip)) {
      JsonNode b = e.body;
      if (e.kind.equals("expense")) {
        result.merge(uuid(b, "payerId"), b.path("amount").asLong(), Long::sum);
        for (JsonNode p : b.path("allocations"))
          result.merge(uuid(p, "participantId"), -p.path("amount").asLong(), Long::sum);
      } else if (b.path("status").asText().equals("Confirmed")) {
        result.merge(uuid(b, "from"), b.path("amount").asLong(), Long::sum);
        result.merge(uuid(b, "to"), -b.path("amount").asLong(), Long::sum);
      }
    }
    return result;
  }

  public List<Money.Transfer> suggestions(UUID trip, UUID user) {
    requireMember(trip, user);
    return Money.settlements(net(trip));
  }
}
