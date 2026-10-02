package com.moneymate;

import java.math.BigInteger;
import java.util.*;

public final class Money {
  public static final long MAX = 9_000_000_000_000L;

  public record Part(UUID participantId, long value) {}

  public record Allocation(UUID participantId, long amount) {}

  public record Transfer(UUID from, UUID to, long amount) {}

  public static List<Allocation> split(long amount, String method, List<Part> parts) {
    ApiError.require(amount > 0 && amount <= MAX, "Amount is outside the supported range.");
    ApiError.require(!parts.isEmpty() && parts.size() <= 100, "Select 1 to 100 participants.");
    ApiError.require(
        parts.stream().map(Part::participantId).distinct().count() == parts.size(),
        "Duplicate split participants.");
    var sorted =
        parts.stream().sorted(Comparator.comparing(p -> p.participantId.toString())).toList();
    if (method.equals("EXACT")) {
      ApiError.require(
          sorted.stream().allMatch(p -> p.value >= 0 && p.value <= MAX), "Invalid exact amount.");
      ApiError.require(
          sorted.stream().mapToLong(Part::value).sum() == amount,
          "Exact splits must equal the expense amount.");
      return sorted.stream().map(p -> new Allocation(p.participantId, p.value)).toList();
    }
    ApiError.require(
        Set.of("EQUAL", "SELECTED", "PERCENT", "SHARES").contains(method), "Unknown split method.");
    boolean equal = method.equals("EQUAL") || method.equals("SELECTED");
    ApiError.require(
        equal || sorted.stream().allMatch(p -> p.value >= 0 && p.value <= 1_000_000),
        "Invalid split weights.");
    long total = equal ? sorted.size() : sorted.stream().mapToLong(Part::value).sum();
    ApiError.require(
        total > 0 && (!method.equals("PERCENT") || total == 10000),
        "Percentages must total 100%; shares must be positive in total.");
    long[] amounts = new long[sorted.size()];
    BigInteger[] remainders = new BigInteger[sorted.size()];
    long allocated = 0;
    for (int i = 0; i < sorted.size(); i++) {
      var division =
          BigInteger.valueOf(amount)
              .multiply(BigInteger.valueOf(equal ? 1 : sorted.get(i).value))
              .divideAndRemainder(BigInteger.valueOf(total));
      amounts[i] = division[0].longValueExact();
      remainders[i] = division[1];
      allocated += amounts[i];
    }
    var order = new ArrayList<Integer>();
    for (int i = 0; i < sorted.size(); i++) order.add(i);
    order.sort(
        (a, b) -> {
          int result = remainders[b].compareTo(remainders[a]);
          return result == 0
              ? sorted
                  .get(a)
                  .participantId
                  .toString()
                  .compareTo(sorted.get(b).participantId.toString())
              : result;
        });
    for (int i = 0; i < amount - allocated; i++) amounts[order.get(i)]++;
    var result = new ArrayList<Allocation>();
    for (int i = 0; i < sorted.size(); i++)
      result.add(new Allocation(sorted.get(i).participantId, amounts[i]));
    return result;
  }

  public static List<Transfer> settlements(Map<UUID, Long> net) {
    ApiError.require(
        net.values().stream().mapToLong(Long::longValue).sum() == 0, "Unbalanced trip.");
    var balances = new TreeMap<UUID, Long>(Comparator.comparing(UUID::toString));
    balances.putAll(net);
    var result = new ArrayList<Transfer>();
    while (true) {
      var debt =
          balances.entrySet().stream()
              .filter(e -> e.getValue() < 0)
              .min(
                  Comparator.<Map.Entry<UUID, Long>>comparingLong(Map.Entry::getValue)
                      .thenComparing(e -> e.getKey().toString()));
      var credit =
          balances.entrySet().stream()
              .filter(e -> e.getValue() > 0)
              .min(
                  Comparator.<Map.Entry<UUID, Long>>comparingLong(e -> -e.getValue())
                      .thenComparing(e -> e.getKey().toString()));
      if (debt.isEmpty() || credit.isEmpty()) break;
      long amount = Math.min(-debt.get().getValue(), credit.get().getValue());
      UUID from = debt.get().getKey(), to = credit.get().getKey();
      result.add(new Transfer(from, to, amount));
      balances.put(from, balances.get(from) + amount);
      balances.put(to, balances.get(to) - amount);
    }
    return result;
  }
}
