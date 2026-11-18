package com.kirana.user.dto;

import com.kirana.user.Lang;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Both fields optional; only the ones sent are changed. */
public record UpdateMeRequest(
        @Size(max = 60, message = "must be at most 60 characters")
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String name,

        Lang preferredLang) {
}
