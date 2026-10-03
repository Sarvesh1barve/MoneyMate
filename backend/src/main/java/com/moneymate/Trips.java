package com.moneymate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Trips {
  final Records records;
  final Auth auth;

  Trips(Records records, Auth auth) {
    this.records = records;
    this.auth = auth;
  }

  @Transactional
  public Map<String, Object> invite(UUID user, UUID trip, UUID participant) {
    records.lockUser(user);
    records.lockTrip(trip);
    records.owner(trip, user);
    if (participant != null) {
    var p = records.reference(participant, "participant", user, trip);
    ApiError.require(!p.deleted(), "Participant is inactive.");
    ApiError.require(
        records
                .database()
                .queryForObject(
                    "select count(*) from membership where trip_id=? and participant_id=?",
                    Integer.class,
                    trip,
                    participant)
            == 0,
        "Participant is already linked.");
    records
        .database()
        .update(
            "update invitation set used_at=now() where trip_id=? and participant_id=? and used_at"
                + " is null",
            trip,
            participant);
    }
    String token = auth.secret();
    Instant expiry = Instant.now().plusSeconds(86400);
    records
        .database()
        .update(
            "insert into invitation(token_hash,trip_id,participant_id,created_by,expires_at) values"
                + " (?,?,?,?,?)",
            Auth.hash(token),
            trip,
            participant,
            user,
            Timestamp.from(expiry));
    records.activity(trip, user, "Created invitation", participant);
    return Map.of("token", token, "expiresAt", expiry);
  }

  @Transactional
  public Map<String, UUID> join(UUID user, String token) {
    ApiError.require(token != null && token.length() == 43, "Invalid or expired invitation.");
    records.lockUser(user);
    var invite =
        records
            .database()
            .queryForList(
                "select trip_id,participant_id,accepted_by,used_at from invitation where token_hash=? and expires_at>now()",
                Auth.hash(token));
    if (invite.isEmpty()) throw new ApiError(400, "Invalid or expired invitation.");
    UUID trip = (UUID) invite.getFirst().get("trip_id"),
        participant = (UUID) invite.getFirst().get("participant_id");
    records.lockTrip(trip);
    // Re-read under the trip lock: two users must never consume the same invitation.
    var current = records.database().queryForMap("select * from invitation where token_hash=?", Auth.hash(token));
    if (current.get("used_at") != null) {
      if (user.equals(current.get("accepted_by")) && records.member(trip, user) != null)
        return Map.of("tripId", trip);
      throw new ApiError(400, "Invalid or expired invitation.");
    }
    // Existing members can open a fresh link without spending somebody else's invitation.
    if (records.member(trip, user) != null) return Map.of("tripId", trip);
    if (participant != null) ApiError.require(
        !records.reference(participant, "participant", user, trip).deleted(),
        "Participant is inactive.");
    int used =
        records
            .database()
            .update(
                "update invitation set used_at=now(),accepted_by=? where token_hash=? and used_at is null and"
                    + " expires_at>now()",
                user, Auth.hash(token));
    ApiError.require(used == 1, "Invalid or expired invitation.");
    if (participant == null) {
      participant = UUID.randomUUID();
      records.database().update("insert into record(id,kind,owner_id,trip_id,version,body) values (?,'participant',?,?,1,jsonb_build_object('name',cast(? as text)))",
          participant, records.find(trip).ownerId(), trip, auth.user(user).name());
      records.database().update("update invitation set participant_id=? where token_hash=?", participant, Auth.hash(token));
    }
    records
        .database()
        .update(
            "insert into membership(trip_id,user_id,participant_id,role) values (?,?,?,'MEMBER')",
            trip,
            user,
            participant);
    records.activity(trip, user, "Joined trip", participant);
    return Map.of("tripId", trip);
  }

  @Transactional
  public void remove(UUID user, UUID trip, UUID member) {
    records.lockUser(user);
    records.lockTrip(trip);
    records.owner(trip, user);
    var target = records.requireMember(trip, member);
    ApiError.require(!target.role().equals("OWNER"), "The owner cannot be removed.");
    records.database().update("delete from membership where trip_id=? and user_id=?", trip, member);
    records
        .database()
        .update(
            "update invitation set used_at=now() where trip_id=? and participant_id=? and used_at"
                + " is null",
            trip,
            target.participantId());
    records.activity(trip, user, "Revoked member access", target.participantId());
  }
}
