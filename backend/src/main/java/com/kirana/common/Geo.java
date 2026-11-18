package com.kirana.common;

/** Distance helpers. The SQL Haversine in StoreRepository uses the same earth radius. */
public final class Geo {

    public static final double EARTH_RADIUS_KM = 6371.0;

    private Geo() {
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return EARTH_RADIUS_KM * 2 * Math.asin(Math.sqrt(a));
    }

    public static Double round1(Double km) {
        return km == null ? null : Math.round(km * 10) / 10.0;
    }
}
