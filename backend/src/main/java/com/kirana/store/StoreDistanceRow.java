package com.kirana.store;

/** Row from the native distance queries in StoreRepository. distanceKm is unrounded (null if no location given). */
public interface StoreDistanceRow {
    Long getId();

    String getName();

    String getAddress();

    String getArea();

    Double getLat();

    Double getLng();

    Boolean getIsOpen();

    Double getDistanceKm();
}
