package com.decorix.finance.core.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic safeguards for item-level receipt advice. Amounts never come from model suggestions. */
public final class ReceiptBasketPolicy {
    public static final String ALGORITHM_VERSION = "receipt-basket.v1";
    private static final Set<String> VERDICTS = Set.of("useful", "neutral", "harmful", "unnecessary");
    private static final List<String> JUNK = List.of(
            "алкоголь", "пиво", "пив", "жигул", "балтик", "водк", "вино", "винн", "шампан",
            "коньяк", "виски", "ром ", "джин", "настойк", "вермут", "сидр", "энергетик", "энергет",
            "red bull", "burn", "adrenaline", "tornado", "чипс", "chips", "lays", "лейс", "сухарик",
            "сух.", "грен", "кириешк", "снек", "попкорн", "шоколад", "батончик", "мармелад", "жвачк",
            "конфет", "морожен", "печень", "пряник", "вафл", "кекс", "торт", "пирожн", "cola", "pepsi",
            "пепси", "фанта", "спрайт", "лимонад", "торнад", "газиров");
    private static final List<String> FOOD = List.of(
            "макарон", "мак.", "изд.мак", "перья", "спагет", "вермишел", "лапш", "рожк", "круп", "рис",
            "греч", "овсян", "пшен", "булгур", "кускус", "горох", "фасол", "чечевиц", "мук", "сахар",
            "соль", "специ", "консерв", "тушен", "масло", "сыр", "молок", "кефир", "творог", "сметан",
            "яйц", "мяс", "кури", "рыб", "овощ", "фрукт", "ягод", "картоф", "лук", "морков", "капуст",
            "хлеб", "батон", "чай", "кофе", "вода", "сок", "йогурт", "ряженк", "сливк", "томат", "огур",
            "зелен", "петрушк", "гриб", "паштет", "колбас");
    private static final List<String> PRODUCT_PRIORITY = List.of(
            "колбас", "сервелат", "серв кар", "серв.кар", "ветчин", "сосиск", "бекон", "мясо", "слойк",
            "слоен", "пирог", "булоч", "хлеб", "молок", "сметан", "сыр", "творог", "йогурт", "кефир",
            "пельмен", "макарон", "корм", "проклад", "вода");
    private static final List<String> ALWAYS_NEUTRAL_PRODUCT = List.of(
            "колбас", "сервелат", "серв кар", "серв.кар", "ветчин", "сосиск", "слойк", "слоен", "пирог", "булоч");
    private static final List<String> TECH = List.of(
            "бп ", "блок питан", "корпус", "монитор", "ноутбук", "клавиатур", "мышь", "мышк", "наушник",
            "телевизор", "смартфон", "телефон", "планшет", "принтер", "роутер", "ssd", "hdd", "видеокарт",
            "процессор", "материнск", "флешк", "зарядк", "кабел", "переходник", "колонк", "гарнитур",
            "powerbank", "павербанк");
    private static final List<String> PACKAGING = List.of("пакет", "пакет-майка", "пакет майка", "мешок для", "мешок-майка");
    private static final List<String> ACCESSORY = List.of("закол", "игрушк", "сувенир", "брелок", "наклейк", "погремушк");
    private static final List<String> CARE = List.of(
            "гель", "шампун", "зубн", "паст", "мыло", "прокладк", "салфетк", "стиральн", "порошок", "бумаг",
            "губк", "бритв", "дезодор", "sensitive", "lac.", "лосьон", "крем");
    private static final Pattern GRAMS = Pattern.compile("(\\d+)\\s*(?:г|гр)");
    private static final List<String> PACKAGING_WORDS = List.of("упаковк", "пакет", "мешок", "одноразов", "тара");

    private ReceiptBasketPolicy() {}

    public static List<Review> apply(List<Proposal> proposals) {
        if (proposals == null || proposals.size() > 200) {
            throw new IllegalArgumentException("receipt review proposals are invalid");
        }
        List<Candidate> candidates = new ArrayList<>(proposals.size());
        Set<UUID> itemIds = new HashSet<>();
        for (Proposal proposal : proposals) {
            if (proposal == null || proposal.itemId() == null || !itemIds.add(proposal.itemId())
                    || proposal.name() == null || proposal.name().isBlank() || proposal.name().length() > 200) {
                throw new IllegalArgumentException("receipt item review identity is invalid");
            }
            String verdict = proposal.modelVerdict() == null ? "" : proposal.modelVerdict().trim().toLowerCase(Locale.ROOT);
            boolean validModel = VERDICTS.contains(verdict);
            candidates.add(new Candidate(proposal.itemId(), proposal.name().trim(), normalize(proposal.name()),
                    validModel ? verdict : "neutral", validModel ? clean(proposal.reason()) : "",
                    validModel ? clean(proposal.action()) : "",
                    validModel ? "model" : "default"));
        }

        for (Candidate item : candidates) applyRules(item);
        removeRepeatedModelAdvice(candidates, true);
        removeRepeatedModelAdvice(candidates, false);
        return candidates.stream().map(item -> new Review(item.itemId, item.verdict, item.reason, item.action, item.source)).toList();
    }

    private static void applyRules(Candidate item) {
        String name = item.name.toLowerCase(Locale.ROOT);
        String modelVerdict = item.verdict;
        if (containsAny(name, PRODUCT_PRIORITY)) {
            if (containsAny(name, ALWAYS_NEUTRAL_PRODUCT) || containsAny(name, List.of("корм", "проклад"))) {
                item.verdict = "neutral";
                item.reason = "";
                item.action = "";
                rule(item);
            } else if ("unnecessary".equals(modelVerdict)) {
                item.verdict = "neutral";
                item.reason = "";
                item.action = "";
                rule(item);
            }
        } else if (containsAny(name, JUNK)) {
            if (!"unnecessary".equals(modelVerdict)) item.verdict = "harmful";
            item.reason = "";
            item.action = "";
            Advice advice = junkAdvice(name);
            item.reason = advice.reason();
            item.action = advice.action();
            rule(item);
        } else if (containsAny(name, TECH)) {
            item.verdict = "neutral";
            item.reason = "";
            item.action = "";
            rule(item);
        } else if (containsAny(name, PACKAGING)) {
            item.verdict = "unnecessary";
            item.reason = "одноразовая упаковка";
            item.action = "пакет-сумка из дома";
            rule(item);
        } else if (containsAny(name, ACCESSORY)) {
            item.verdict = "unnecessary";
            item.reason = "не еда, покупка по желанию";
            item.action = "брать только осознанно";
            rule(item);
        } else if (containsAny(name, CARE) && ("useful".equals(modelVerdict) || "unnecessary".equals(modelVerdict))) {
            item.verdict = "neutral";
            item.reason = "";
            item.action = "";
            rule(item);
        } else if ("unnecessary".equals(modelVerdict) && containsAny(name, FOOD)) {
            item.verdict = "neutral";
            item.reason = "";
            item.action = "";
            rule(item);
        }

        String advice = (item.reason + " " + item.action).toLowerCase(Locale.ROOT);
        if (containsAny(advice, PACKAGING_WORDS) && !containsAny(name, PACKAGING)) {
            item.reason = "";
            item.action = "";
        }
    }

    private static Advice junkAdvice(String name) {
        if (containsAny(name, List.of("чипс", "chips", "lays", "лейс", "сухарик", "сух.", "грен", "снек"))) {
            Matcher matcher = GRAMS.matcher(name);
            int grams = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
            String action = grams >= 140 ? "взять маленькую пачку до 80 г"
                    : grams > 0 && grams < 100 ? "не докупать вторую пачку к этой"
                    : "сравнить цену за 100 г и взять одну пачку";
            return new Advice("снек, много калорий", action);
        }
        if (containsAny(name, List.of("пиво", "пив", "жигул", "балтик", "сидр"))) {
            return new Advice("пиво, много калорий", "хватит одной бутылки");
        }
        if (containsAny(name, List.of("вино", "винн", "шампан", "вермут"))) {
            return new Advice("вино, алкоголь", "бокал вместо бутылки");
        }
        if (containsAny(name, List.of("водк", "коньяк", "виски", "ром ", "джин", "настойк", "алкоголь"))) {
            return new Advice("крепкий алкоголь", "только по праздникам");
        }
        if (containsAny(name, List.of("энергет", "red bull", "burn", "adrenaline"))) {
            return new Advice("энергетик, много кофеина", "заменить кофе");
        }
        if (containsAny(name, List.of("шоколад", "батончик", "мармелад", "жвачк", "конфет", "морожен", "печень", "пряник", "вафл", "кекс", "торт", "пирожн"))) {
            return new Advice("сладкое, много сахара", "оставить одну сладость, не несколько");
        }
        return new Advice("сладкий напиток или снек", "брать реже");
    }

    private static void removeRepeatedModelAdvice(List<Candidate> candidates, boolean reason) {
        Map<String, Set<String>> namesByAdvice = new HashMap<>();
        for (Candidate item : candidates) {
            if (!"model".equals(item.source)) continue;
            String value = reason ? item.reason : item.action;
            String key = normalizeAdvice(value);
            if (!key.isEmpty()) namesByAdvice.computeIfAbsent(key, ignored -> new HashSet<>()).add(item.normalizedName);
        }
        Set<String> duplicates = new HashSet<>();
        namesByAdvice.forEach((advice, names) -> { if (names.size() > 1) duplicates.add(advice); });
        for (Candidate item : candidates) {
            if (!"model".equals(item.source)) continue;
            String value = reason ? item.reason : item.action;
            if (duplicates.contains(normalizeAdvice(value))) {
                if (reason) item.reason = "";
                else item.action = "";
            }
        }
    }

    private static boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(marker -> value.contains(marker));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String normalizeAdvice(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} ]", "").trim().replaceAll("\\s+", " ");
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    private static void rule(Candidate item) {
        item.source = "rule";
    }

    public record Proposal(UUID itemId, String name, String modelVerdict, String reason, String action) {}
    public record Review(UUID itemId, String verdict, String reason, String action, String source) {}
    private record Advice(String reason, String action) {}

    private static final class Candidate {
        private final UUID itemId;
        private final String name;
        private final String normalizedName;
        private String verdict;
        private String reason;
        private String action;
        private String source;

        private Candidate(UUID itemId, String name, String normalizedName, String verdict,
                          String reason, String action, String source) {
            this.itemId = itemId;
            this.name = name;
            this.normalizedName = normalizedName;
            this.verdict = verdict;
            this.reason = reason;
            this.action = action;
            this.source = source;
        }
    }
}
