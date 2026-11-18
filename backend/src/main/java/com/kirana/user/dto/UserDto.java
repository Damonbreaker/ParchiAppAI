package com.kirana.user.dto;

import com.kirana.user.Lang;
import com.kirana.user.Role;
import com.kirana.user.User;

public record UserDto(Long id, String name, String phone, Role role, Lang preferredLang) {

    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getName(), u.getPhone(), u.getRole(), u.getPreferredLang());
    }
}
