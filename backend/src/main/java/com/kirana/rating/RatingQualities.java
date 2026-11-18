package com.kirana.rating;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Allowed multi-select quality tags for each rating direction. */
public final class RatingQualities {

    private RatingQualities() {
    }

    public static final List<String> STORE = List.of(
            "delivery",
            "friendly",
            "fast_packing",
            "large_inventory",
            "consistent_stock",
            "low_price",
            "easy_return",
            "clean_store",
            "fair_weights",
            "good_packaging");

    public static final List<String> CUSTOMER = List.of(
            "on_time",
            "no_return_issues",
            "cooperative",
            "clear_list",
            "polite",
            "pays_promptly",
            "patient",
            "accurate_items");

    public static List<String> allowed(RatingTargetType type) {
        return type == RatingTargetType.STORE ? STORE : CUSTOMER;
    }

    public static List<String> sanitize(RatingTargetType type, List<String> raw) {
        Set<String> allowed = new LinkedHashSet<>(allowed(type));
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        for (String q : raw) {
            if (q != null && allowed.contains(q)) {
                out.add(q);
            }
        }
        return List.copyOf(out);
    }
}
