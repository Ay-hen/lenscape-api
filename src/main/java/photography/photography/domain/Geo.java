package photography.photography.domain;

import java.util.LinkedHashMap;
import java.util.Map;

import photography.photography.model.Values.GeoPoint;

/** Distance maths and the privacy offset for freelancers' public map position. */
public final class Geo {
    private Geo() {
    }

    /** City centres used to derive a freelancer's approximate public position (same list as the Angular gazetteer). */
    public static final Map<String, GeoPoint> CITY_COORDS = new LinkedHashMap<>();

    static {
        CITY_COORDS.put("Casablanca", new GeoPoint(33.5731, -7.5898));
        CITY_COORDS.put("Rabat", new GeoPoint(34.0209, -6.8416));
        CITY_COORDS.put("Marrakech", new GeoPoint(31.6295, -7.9811));
        CITY_COORDS.put("Tangier", new GeoPoint(35.7595, -5.834));
        CITY_COORDS.put("Fes", new GeoPoint(34.0331, -5.0003));
        CITY_COORDS.put("Agadir", new GeoPoint(30.4278, -9.5981));
        CITY_COORDS.put("Tetouan", new GeoPoint(35.5785, -5.3684));
        CITY_COORDS.put("Essaouira", new GeoPoint(31.5085, -9.7595));
        CITY_COORDS.put("Meknes", new GeoPoint(33.8935, -5.5547));
        CITY_COORDS.put("Oujda", new GeoPoint(34.6867, -1.9114));
    }

    /** Case-insensitive lookup of the canonical city name; null when unknown. */
    public static String canonicalCity(String city) {
        if (city == null) {
            return null;
        }
        String t = city.trim();
        for (String k : CITY_COORDS.keySet()) {
            if (k.equalsIgnoreCase(t)) {
                return k;
            }
        }
        return null;
    }

    /** Straight-line (great-circle) distance in km. Not a driving distance. */
    public static double haversineKm(GeoPoint a, GeoPoint b) {
        double r = 6371;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    /** Deterministic offset (about 150-550 m) so a freelancer's real address is never published. Same maths as the Angular helper. */
    public static GeoPoint approximate(GeoPoint p, String seed) {
        int h = 0;
        for (int i = 0; i < seed.length(); i++) {
            h = h * 31 + seed.charAt(i);
        }
        double a = ((h & 0xffff) / (double) 0xffff) * Math.PI * 2;
        double r = 0.0015 + (((h >>> 16) & 0xff) / (double) 0xff) * 0.002;
        return new GeoPoint(p.lat() + Math.sin(a) * r, p.lng() + Math.cos(a) * r);
    }
}
