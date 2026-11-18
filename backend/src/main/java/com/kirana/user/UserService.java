package com.kirana.user;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.common.ApiException;
import com.kirana.user.dto.UpdateMeRequest;
import com.kirana.user.dto.UserDto;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;

    @Transactional(readOnly = true)
    public UserDto getMe(Long userId) {
        return UserDto.from(load(userId));
    }

    @Transactional
    public UserDto updateMe(Long userId, UpdateMeRequest req) {
        User user = load(userId);
        if (req.name() != null) {
            user.setName(req.name().trim());
        }
        if (req.preferredLang() != null) {
            user.setPreferredLang(req.preferredLang());
        }
        return UserDto.from(user);
    }

    private User load(Long userId) {
        // Token is valid but the account is gone: treat as logged out.
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User no longer exists"));
    }
}
