package com.kirana.shared.dto;

import java.util.List;

import com.kirana.request.dto.ItemInput;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record CreateSharedListRequest(
        @Size(max = 80, message = "must be at most 80 characters")
        String title,

        @Size(max = 50, message = "must have at most 50 items")
        List<@Valid ItemInput> items) {
}
