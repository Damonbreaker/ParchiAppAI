package com.kirana.store.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.kirana.store.Store;

/** The owner's own store, including its phone and Parchi wallet. Never returned to customers. */
public record MyStoreDto(Long id, String name, String address, String area, String phone,
                         double lat, double lng, Boolean isOpen, Instant createdAt,
                         BigDecimal parchiBalance, boolean lowParchi) {

    public static final BigDecimal LOW_THRESHOLD = new BigDecimal("5");

    public static MyStoreDto from(Store s) {
        BigDecimal bal = s.getParchiBalance() == null ? BigDecimal.ZERO : s.getParchiBalance();
        return new MyStoreDto(s.getId(), s.getName(), s.getAddress(), s.getArea(), s.getPhone(),
                s.getLat(), s.getLng(), s.isOpen(), s.getCreatedAt(),
                bal, bal.compareTo(LOW_THRESHOLD) <= 0);
    }
}
