package photography.photography.api;

import java.util.List;

/** Small input-validation helpers shared by the services. All failures are 400 VALIDATION with a field name the form can show. */
public final class Check {
    private Check() {
    }

    public static String text(String v, String field, int min, int max) {
        String t = v == null ? "" : v.trim();
        if (t.length() < min || t.length() > max) {
            throw ApiException.validation(min > 0 ? field + " must be " + min + "-" + max + " characters." : field + " must be at most " + max + " characters.", field, min > 0 ? min + "-" + max + " characters" : "Max " + max + " characters");
        }
        return t;
    }

    public static String optional(String v, String field, int max) {
        return v == null ? null : text(v, field, 0, max);
    }

    public static void range(Number v, String field, double min, double max) {
        if (v == null || v.doubleValue() < min || v.doubleValue() > max || Double.isNaN(v.doubleValue())) {
            throw ApiException.validation(field + " must be between " + min + " and " + max + ".", field, "Between " + (long) min + " and " + (long) max);
        }
    }

    /** Only absolute http(s) URLs are accepted for images: no javascript:, data: or file: URLs get stored. Empty is allowed. */
    public static String url(String v, String field) {
        if (v == null || v.isBlank()) {
            return "";
        }
        String t = v.trim();
        if (t.length() > 500 || !(t.startsWith("https://") || t.startsWith("http://"))) {
            throw ApiException.validation("Use an uploaded image or a full http(s) address.", field, "Invalid image address");
        }
        return t;
    }

    public static List<String> strings(List<String> v, String field, int maxItems, int maxLen) {
        if (v == null) {
            return List.of();
        }
        if (v.size() > maxItems) {
            throw ApiException.validation("Too many entries in " + field + " (max " + maxItems + ").", field, "Max " + maxItems + " entries");
        }
        return v.stream().map(s -> text(s, field, 1, maxLen)).toList();
    }

    public static void oneOf(String v, String field, String... allowed) {
        for (String a : allowed) {
            if (a.equals(v)) {
                return;
            }
        }
        throw ApiException.validation("Invalid " + field + ".", field, "Not allowed");
    }
}
