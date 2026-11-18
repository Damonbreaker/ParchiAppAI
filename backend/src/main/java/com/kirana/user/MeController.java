package com.kirana.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.config.AuthUser;
import com.kirana.user.dto.UpdateMeRequest;
import com.kirana.user.dto.UserDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final UserService userService;

    @GetMapping
    public UserDto me(@AuthenticationPrincipal AuthUser me) {
        return userService.getMe(me.id());
    }

    @PatchMapping
    public UserDto update(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody UpdateMeRequest req) {
        return userService.updateMe(me.id(), req);
    }
}
