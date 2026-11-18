package com.kirana.parchi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.kirana.common.Unit;

/**
 * Turns raw OCR lines of a handwritten parchi into {name, quantity, unit, confident}.
 *
 * Handles: quantity before or after the name ("aata 5kg", "½ kg pyaz"); decimals, ½ ¼ ¾, "1/2", "1 1/2";
 * Devanagari digits (०-९); units in English, Hindi and common spellings, with or without a space ("500gm");
 * serial numbers at the start of a line ("1.", "2)", "-", "•"). Units are mapped, never converted
 * ("250 gram" stays 250 G).
 *
 * confident = false when the line needs a human look: no quantity (defaults to 1 PCS), several numbers,
 * a quantity out of range, or a name that had to be cut. A number without a unit ("tata namak 1") is
 * that many PCS and still confident. Empty and punctuation-only lines are dropped.
 *
 * Item names are kept as written (Hindi or English); only the quantity and unit are taken out.
 */
@Component
public class ParchiLineParser {

    public record ParsedItem(String name, BigDecimal quantity, Unit unit, boolean confident) {
    }

    /** Same limits as ItemInput, so a parsed item is always a valid request item. */
    static final int MAX_NAME = 60;
    private static final BigDecimal MAX_QTY = new BigDecimal("9999999.999");

    private static final Map<String, Unit> UNIT_WORDS = unitWords();

    /** A number, optionally followed by a unit word; neither may be glued to other letters. */
    private static final Pattern QTY;

    static {
        String units = UNIT_WORDS.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed()) // "ltr" before "l", "लीटर" before "ली"
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        QTY = Pattern.compile("(?<![\\p{L}\\p{M}\\d.])(\\d+(?:\\.\\d+)?)"
                        + "(?:\\s*(" + units + ")(?![\\p{L}\\p{M}]))?"
                        + "(?![\\p{L}\\p{M}\\d])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** "1.", "12)", "(3)", "-", "•", "*" at the very start of a line. "1.5 kg" is not a serial number. */
    private static final Pattern SERIAL = Pattern.compile("^\\s*(?:\\(?\\d{1,2}\\s*[.)]\\s+|[-–—•*·>]+\\s*)");
    /** "1 1/2" (not part of a date like 11/11/2026). */
    private static final Pattern MIXED = Pattern.compile("(?<![\\d./])(\\d+)\\s+(\\d+)\\s*/\\s*(\\d+)(?![\\d./])");
    /** "1/2". */
    private static final Pattern SLASH = Pattern.compile("(?<![\\d./])(\\d+)\\s*/\\s*(\\d+)(?![\\d./])");
    /** "1½", "2 ¼". */
    private static final Pattern WHOLE_AND_VULGAR = Pattern.compile("(\\d+)\\s*([½¼¾])");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[\\p{Punct}\\p{Pd}•·।\\s]+|[\\p{Punct}\\p{Pd}•·।\\s]+$");
    private static final Pattern HAS_LETTER = Pattern.compile("[\\p{L}]");

    public List<ParsedItem> parse(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream().filter(Objects::nonNull).map(this::parseLine)
                .flatMap(Optional::stream).toList();
    }

    /** Empty for blank or punctuation-only lines (and lines with no letters at all, e.g. a date). */
    public Optional<ParsedItem> parseLine(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String line = normalize(raw);
        if (!HAS_LETTER.matcher(line).find()) {
            return Optional.empty();
        }

        List<MatchResult> found = QTY.matcher(line).results().toList();
        if (found.isEmpty()) {
            return Optional.of(item(clean(line), BigDecimal.ONE, Unit.PCS, false));
        }
        // Several numbers: prefer the one with a unit ("maggi 2 x 4 pkt"), and flag the line.
        MatchResult m = found.size() == 1 ? found.get(0)
                : found.stream().filter(r -> r.group(2) != null).findFirst().orElse(found.get(0));
        boolean confident = found.size() == 1;

        Unit unit = m.group(2) == null ? Unit.PCS : UNIT_WORDS.get(m.group(2).toLowerCase(Locale.ROOT));
        BigDecimal qty = new BigDecimal(m.group(1));
        if (qty.scale() > 3) {
            qty = qty.setScale(3, RoundingMode.HALF_UP);
            confident = false;
        }
        if (qty.signum() <= 0 || qty.compareTo(MAX_QTY) > 0) {
            qty = BigDecimal.ONE;
            confident = false;
        }

        String name = clean(line.substring(0, m.start()) + " " + line.substring(m.end()));
        if (name.isEmpty()) {
            // Only a quantity on this line ("5 kg"): keep the text so the customer can fix it on review.
            return Optional.of(item(clean(line), BigDecimal.ONE, Unit.PCS, false));
        }
        return Optional.of(item(name, qty, unit, confident));
    }

    // ------------------------------------------------------------ helpers

    private static ParsedItem item(String name, BigDecimal qty, Unit unit, boolean confident) {
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME).trim();
            confident = false;
        }
        return new ParsedItem(name, plain(qty), unit, confident);
    }

    /** Devanagari digits to ASCII, serial number removed, fractions to decimals. */
    static String normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c >= '०' && c <= '९') {
                sb.append((char) ('0' + (c - '०')));
            } else if (Character.isSpaceChar(c) || c == '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String s = SERIAL.matcher(sb.toString()).replaceFirst("");
        s = MIXED.matcher(s).replaceAll(r -> fraction(r.group(1), r.group(2), r.group(3), r.group()));
        s = SLASH.matcher(s).replaceAll(r -> fraction("0", r.group(1), r.group(2), r.group()));
        s = WHOLE_AND_VULGAR.matcher(s).replaceAll(r ->
                plain(new BigDecimal(r.group(1)).add(vulgar(r.group(2).charAt(0)))).toPlainString());
        s = s.replace("½", " 0.5 ").replace("¼", " 0.25 ").replace("¾", " 0.75 ");
        return s.trim();
    }

    private static String fraction(String whole, String num, String den, String original) {
        BigDecimal d = new BigDecimal(den);
        if (d.signum() == 0) {
            return Matcher.quoteReplacement(original);
        }
        BigDecimal v = new BigDecimal(whole).add(new BigDecimal(num).divide(d, 3, RoundingMode.HALF_UP));
        return plain(v).toPlainString();
    }

    private static BigDecimal vulgar(char c) {
        return switch (c) {
            case '½' -> new BigDecimal("0.5");
            case '¼' -> new BigDecimal("0.25");
            default -> new BigDecimal("0.75");
        };
    }

    /** 2.000 -> 2, 0.500 -> 0.5, never 1E+1. */
    private static BigDecimal plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    /** Drops tokens with no letter or digit (stray "-", "(", ")") and trims punctuation at the ends. */
    static String clean(String s) {
        String joined = Arrays.stream(s.trim().split("\\s+"))
                .filter(t -> t.codePoints().anyMatch(Character::isLetterOrDigit))
                .collect(Collectors.joining(" "));
        return EDGE_PUNCT.matcher(joined).replaceAll("");
    }

    private static Map<String, Unit> unitWords() {
        Map<String, Unit> m = new LinkedHashMap<>();
        put(m, Unit.KG, "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "किलो", "किग्रा", "किलोग्राम", "केजी");
        put(m, Unit.G, "g", "gm", "gms", "gram", "grams", "gramme", "grm", "gr", "ग्राम", "ग्रा");
        put(m, Unit.L, "l", "lt", "ltr", "ltrs", "litre", "litres", "liter", "liters", "लीटर", "लिटर", "ली");
        put(m, Unit.ML, "ml", "mls", "millilitre", "milliliter", "मिली", "एमएल");
        put(m, Unit.PACKET, "pkt", "pkts", "packet", "packets", "pack", "packs", "पैकेट", "पैकिट", "पाकेट");
        put(m, Unit.DOZEN, "dozen", "dozens", "doz", "dz", "darjan", "darzan", "दर्जन");
        put(m, Unit.PCS, "pc", "pcs", "piece", "pieces", "nos", "पीस", "नग");
        return Map.copyOf(m);
    }

    private static void put(Map<String, Unit> m, Unit unit, String... words) {
        for (String w : words) {
            m.put(w, unit);
        }
    }
}
