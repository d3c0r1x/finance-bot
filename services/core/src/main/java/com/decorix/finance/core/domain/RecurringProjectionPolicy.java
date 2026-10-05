package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative history-only recurrence projection shared by cash planning and later recurrence views. */
public final class RecurringProjectionPolicy {
    private static final Pattern WORD = Pattern.compile("[a-zа-я0-9]+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> STOP_WORDS = Set.of(
            "пятерочка", "пятёрочка", "смак", "тема", "папа", "мож", "катти", "pur", "felix",
            "корм", "пакет", "майка", "покупка", "товар", "шт", "руб", "р",
            "январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август",
            "сентябрь", "октябрь", "ноябрь", "декабрь", "january", "february", "march",
            "april", "june", "july", "august", "september", "october", "november", "december");

    private RecurringProjectionPolicy() {}

    public static List<Projection> detect(List<HistoryItem> history, LocalDate today) {
        Map<String, List<HistoryItem>> groups = new HashMap<>();
        for (HistoryItem item : history) {
            if (item == null || !("income".equals(item.type()) || "expense".equals(item.type()))
                    || item.occurredOn() == null || item.amount() == null || item.amount().signum() <= 0) continue;
            String key = key(item);
            if (!key.isBlank()) groups.computeIfAbsent(item.type() + ":" + key, ignored -> new ArrayList<>()).add(item);
        }
        List<Projection> projections = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<HistoryItem> series = entry.getValue().stream().sorted(Comparator.comparing(HistoryItem::occurredOn)).toList();
            if (series.size() < 3) continue;
            List<BigDecimal> amounts = series.stream().map(HistoryItem::amount).toList();
            BigDecimal average = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_EVEN);
            if (average.signum() <= 0 || amounts.stream().max(BigDecimal::compareTo).orElseThrow()
                    .subtract(amounts.stream().min(BigDecimal::compareTo).orElseThrow())
                    .compareTo(average.multiply(new BigDecimal("0.25"))) > 0) continue;
            List<Long> deltas = new ArrayList<>();
            for (int i = 1; i < series.size(); i++) {
                long days = ChronoUnit.DAYS.between(series.get(i - 1).occurredOn(), series.get(i).occurredOn());
                if (days > 0) deltas.add(days);
            }
            if (deltas.size() < 2) continue;
            deltas.sort(Long::compareTo);
            int periodDays = Math.toIntExact(deltas.get(deltas.size() / 2));
            if (!(periodDays >= 6 && periodDays <= 8 || periodDays >= 25 && periodDays <= 35)) continue;
            long regularCount = deltas.stream().filter(days -> Math.abs(days - periodDays) <= periodDays * 0.25).count();
            if (regularCount < deltas.size() * 0.6) continue;
            HistoryItem latest = series.get(series.size() - 1);
            String key = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            projections.add(new Projection(key, latest.description(), latest.type(), average,
                    latest.occurredOn().plusDays(periodDays), periodDays));
        }
        return projections.stream().sorted(Comparator.comparing(Projection::type).thenComparing(Projection::key)).toList();
    }

    private static String key(HistoryItem item) {
        String source = ((item.description() == null ? "" : item.description()) + " "
                + (item.category() == null ? "" : item.category())).toLowerCase(Locale.ROOT).replace('ё', 'е');
        Set<String> words = new HashSet<>();
        var matcher = WORD.matcher(source);
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() >= 3 && !word.chars().allMatch(Character::isDigit) && !STOP_WORDS.contains(word)) words.add(word);
        }
        return words.stream().sorted().reduce((left, right) -> left + " " + right).orElse("");
    }

    public record HistoryItem(String type, BigDecimal amount, String category, String description, LocalDate occurredOn) {}
    public record Projection(String key, String name, String type, BigDecimal amount, LocalDate nextDueDate, int periodDays) {}
}
