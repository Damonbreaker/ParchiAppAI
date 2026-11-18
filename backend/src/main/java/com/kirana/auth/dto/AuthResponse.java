package com.kirana.auth.dto;

import com.kirana.user.dto.UserDto;

public record AuthResponse(String token, UserDto user) {
}
