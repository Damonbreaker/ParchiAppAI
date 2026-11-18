package com.kirana.community.dto;

import java.util.List;

import com.kirana.request.dto.ItemInput;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SetPoolListRequest(
        @NotNull
        @Size(min = 1, max = 50)
        List<@Valid ItemInput> items,

        Boolean fromPhoto) {
}
