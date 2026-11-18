package com.kirana.request.dto;

import com.kirana.user.User;

/** Customer's name and phone, shown only to the store whose quote was accepted. */
public record CustomerContactDto(String name, String phone) {

    public static CustomerContactDto from(User u) {
        return new CustomerContactDto(u.getName(), u.getPhone());
    }
}
