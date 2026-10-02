package com.moneymate;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class Api {
  final Records records;
  final Trips trips;
  final Auth auth;

  Api(Records records, Trips trips, Auth auth) {
    this.records = records;
    this.trips = trips;
    this.auth = auth;
  }

  @GetMapping("/sync")
  Map<String, Object> snapshot(Authentication a) {
    return records.snapshot(Auth.id(a));
  }

  @PostMapping("/sync")
  Records.Entry write(Authentication a, @RequestBody Records.Operation operation) {
    return records.write(Auth.id(a), operation);
  }

  @GetMapping("/trips/{trip}/suggestions")
  List<Money.Transfer> suggestions(Authentication a, @PathVariable UUID trip) {
    return records.suggestions(trip, Auth.id(a));
  }

  record Invite(UUID participantId) {}

  record Join(String token) {}

  @PostMapping("/trips/{trip}/invitations")
  Map<String, Object> invite(Authentication a, @PathVariable UUID trip, @RequestBody Invite input) {
    return trips.invite(Auth.id(a), trip, input.participantId);
  }

  @PostMapping("/invitations/join")
  Map<String, UUID> join(Authentication a, @RequestBody Join input) {
    auth.throttle("join:" + a.getName(), 10);
    return trips.join(Auth.id(a), input.token);
  }

  @DeleteMapping("/trips/{trip}/members/{member}")
  Map<String, Boolean> remove(
      Authentication a, @PathVariable UUID trip, @PathVariable UUID member) {
    trips.remove(Auth.id(a), trip, member);
    return Map.of("ok", true);
  }
}
