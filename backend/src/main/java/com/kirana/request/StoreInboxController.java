package com.kirana.request;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.common.PageDto;
import com.kirana.config.AuthUser;
import com.kirana.request.dto.StoreInboxItemDto;
import com.kirana.request.dto.StoreRequestDetailDto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * Store owner's incoming requests (read side). STORE_OWNER role is enforced for /api/store/** in
 * SecurityConfig. The quote endpoint (PUT /{id}/quote) is in quote.StoreQuoteController.
 */
@RestController
@RequestMapping("/api/store/requests")
@RequiredArgsConstructor
public class StoreInboxController {

    private final RequestService requestService;

    /** ?status=OPEN|ORDERS|CLOSED&page=0&size=20 (size 1..50) -> {items, page, hasMore}, newest first. */
    @GetMapping
    public PageDto<StoreInboxItemDto> inbox(@AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) InboxFilter status,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must be 0 or more") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "must be between 1 and 50")
            @Max(value = PageDto.MAX_SIZE, message = "must be between 1 and 50") int size) {
        return requestService.storeInbox(me.id(), status, page, size);
    }

    @GetMapping("/{id:\\d+}")
    public StoreRequestDetailDto open(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return requestService.storeOpen(me.id(), id);
    }
}
