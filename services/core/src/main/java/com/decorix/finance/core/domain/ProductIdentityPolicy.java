package com.decorix.finance.core.domain;

import java.util.Locale;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable product decision keys, matching the legacy purchase-history signature. */
public final class ProductIdentityPolicy {
    private static final Pattern TOKEN = Pattern.compile("[a-zа-я0-9]{3,}");
    private static final Pattern CANONICAL_KEY = Pattern.compile("[a-zа-я0-9]{1,256}");
    private static final Set<String> STOP_WORDS = Set.of(
            "пятерочка", "смaк", "тема", "папа", "мож", "катти", "pur", "felix",
            "корм", "пакет", "майка", "покупка", "товар", "шт", "руб", "р");

    private ProductIdentityPolicy() {}

    public static String productKey(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).replace('ё', 'е');
        Matcher matcher = TOKEN.matcher(normalized);
        Set<String> tokens = new TreeSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_WORDS.contains(token) && !token.chars().allMatch(Character::isDigit)) tokens.add(token);
        }
        return String.join("", tokens);
    }

    public static String requireProductKey(String key) {
        if (key == null || !CANONICAL_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Product key is invalid");
        }
        return key;
    }

    public static boolean sameProduct(String current, String previous) {
        Set<String> currentTokens = tokens(current);
        Set<String> previousTokens = tokens(previous);
        if (currentTokens.isEmpty() || previousTokens.isEmpty()) return false;
        Set<String> overlap = new HashSet<>(currentTokens);
        overlap.retainAll(previousTokens);
        double tokenOverlap = (double) overlap.size() / Math.max(currentTokens.size(), previousTokens.size());
        String currentSignature = String.join("", new TreeSet<>(currentTokens));
        String previousSignature = String.join("", new TreeSet<>(previousTokens));
        return tokenOverlap >= 0.75 || tokenOverlap >= 0.5
                && sequenceRatio(currentSignature, previousSignature) >= 0.82;
    }

    private static Set<String> tokens(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).replace('ё', 'е');
        Matcher matcher = TOKEN.matcher(normalized);
        Set<String> tokens = new HashSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_WORDS.contains(token) && !token.chars().allMatch(Character::isDigit)) tokens.add(token);
        }
        return tokens;
    }

    /** Ratcliff/Obershelp matching blocks used by Python SequenceMatcher.ratio(). */
    private static double sequenceRatio(String first, String second) {
        if (first.isEmpty() && second.isEmpty()) return 1.0;
        Deque<Range> pending = new ArrayDeque<>();
        pending.push(new Range(0, first.length(), 0, second.length()));
        int matched = 0;
        while (!pending.isEmpty()) {
            Range range = pending.pop();
            Match match = longestMatch(first, second, range);
            if (match.length() == 0) continue;
            matched += match.length();
            if (range.firstStart() < match.firstStart() && range.secondStart() < match.secondStart()) {
                pending.push(new Range(range.firstStart(), match.firstStart(), range.secondStart(), match.secondStart()));
            }
            int firstAfter = match.firstStart() + match.length();
            int secondAfter = match.secondStart() + match.length();
            if (firstAfter < range.firstEnd() && secondAfter < range.secondEnd()) {
                pending.push(new Range(firstAfter, range.firstEnd(), secondAfter, range.secondEnd()));
            }
        }
        return 2.0 * matched / (first.length() + second.length());
    }

    private static Match longestMatch(String first, String second, Range range) {
        Map<Integer, Integer> previous = Map.of();
        int bestStart = range.firstStart();
        int bestSecondStart = range.secondStart();
        int bestLength = 0;
        for (int firstIndex = range.firstStart(); firstIndex < range.firstEnd(); firstIndex++) {
            Map<Integer, Integer> current = new HashMap<>();
            for (int secondIndex = range.secondStart(); secondIndex < range.secondEnd(); secondIndex++) {
                if (first.charAt(firstIndex) == second.charAt(secondIndex)) {
                    int length = previous.getOrDefault(secondIndex - 1, 0) + 1;
                    current.put(secondIndex, length);
                    if (length > bestLength) {
                        bestStart = firstIndex - length + 1;
                        bestSecondStart = secondIndex - length + 1;
                        bestLength = length;
                    }
                }
            }
            previous = current;
        }
        return new Match(bestStart, bestSecondStart, bestLength);
    }

    private record Range(int firstStart, int firstEnd, int secondStart, int secondEnd) {}
    private record Match(int firstStart, int secondStart, int length) {}
}
