package com.moneymate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MoneyTest {
  UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001"),
      b = UUID.fromString("00000000-0000-0000-0000-000000000002"),
      c = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void equalRemainderIsStable() {
    var parts = List.of(new Money.Part(b, 0), new Money.Part(a, 0), new Money.Part(c, 0));
    var split = Money.split(100, "EQUAL", parts);
    assertEquals(List.of(34L, 33L, 33L), split.stream().map(Money.Allocation::amount).toList());
    assertEquals(split, Money.split(100, "SELECTED", parts));
  }

  @Test
  void percentSharesAndExact() {
    assertEquals(
        List.of(25L, 76L),
        Money.split(101, "PERCENT", List.of(new Money.Part(a, 2500), new Money.Part(b, 7500)))
            .stream()
            .map(Money.Allocation::amount)
            .toList());
    assertEquals(
        List.of(34L, 67L),
        Money.split(101, "SHARES", List.of(new Money.Part(a, 1), new Money.Part(b, 2))).stream()
            .map(Money.Allocation::amount)
            .toList());
    assertEquals(
        101,
        Money.split(101, "EXACT", List.of(new Money.Part(a, 1), new Money.Part(b, 100))).stream()
            .mapToLong(Money.Allocation::amount)
            .sum());
  }

  @Test
  void invalidSplitsFail() {
    assertThrows(ApiError.class, () -> Money.split(100, "EXACT", List.of(new Money.Part(a, 99))));
    assertThrows(ApiError.class, () -> Money.split(100, "PERCENT", List.of(new Money.Part(a, 99))));
    assertThrows(ApiError.class, () -> Money.split(100, "SHARES", List.of(new Money.Part(a, 0))));
    assertThrows(
        ApiError.class,
        () -> Money.split(100, "EQUAL", List.of(new Money.Part(a, 1), new Money.Part(a, 1))));
  }

  @Test
  void largeAmountsPreserveEveryUnit() {
    var result =
        Money.split(Money.MAX, "SHARES", List.of(new Money.Part(a, 999999), new Money.Part(b, 1)));
    assertEquals(Money.MAX, result.stream().mapToLong(Money.Allocation::amount).sum());
  }

  @Test
  void settlementsReduceAndBalance() {
    var result = Money.settlements(Map.of(a, 800L, b, -500L, c, -300L));
    assertEquals(List.of(new Money.Transfer(b, a, 500), new Money.Transfer(c, a, 300)), result);
    assertEquals(List.of(), Money.settlements(Map.of(a, 0L)));
  }
}
