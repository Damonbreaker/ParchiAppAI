package com.kirana.request.dto;

import com.kirana.store.Store;

/** Accepted store's contact details, shown to the customer only after they accept its quote. */
public record StoreContactDto(String name, String address, String area, String phone, double lat, double lng) {

    public static StoreContactDto from(Store s) {
        return new StoreContactDto(s.getName(), s.getAddress(), s.getArea(), s.getPhone(), s.getLat(), s.getLng());
    }
}
