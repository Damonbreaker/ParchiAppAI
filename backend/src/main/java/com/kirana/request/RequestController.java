package com.kirana.request;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.common.PageDto;
import com.kirana.config.AuthUser;
import com.kirana.request.dto.AcceptRequest;
import com.kirana.request.dto.CreateRequestRequest;
import com.kirana.request.dto.RequestDetailDto;
import com.kirana.request.dto.RequestSummaryDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Customer endpoints. CUSTOMER role is enforced for /api/requests/** in SecurityConfig. */
@RestController
@RequestMapping("/api/requests")
@RequiredArgsConstructor
public class RequestController {

    private final RequestService requestService;
    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestDetailDto create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreateRequestRequest req) {
        return requestService.create(me.id(), req);
    }

    /** ?page=0&size=20 (size 1..50) -> {items, page, hasMore}, newest first. */
    @GetMapping("/mine")
    public PageDto<RequestSummaryDto> mine(@AuthenticationPrincipal AuthUser me,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must be 0 or more") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "must be between 1 and 50")
            @Max(value = PageDto.MAX_SIZE, message = "must be between 1 and 50") int size) {
        return requestService.listMine(me.id(), page, size);
    }

    @GetMapping("/{id:\\d+}")
    public RequestDetailDto get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return requestService.getMine(me.id(), id);
    }

    @PostMapping("/{id:\\d+}/cancel")
    public RequestDetailDto cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return requestService.cancel(me.id(), id);
    }

    /** Accept one store's quote. Body: {expectedTotal} = the total shown to the customer. */
    @PostMapping("/{id:\\d+}/accept/{quoteId:\\d+}")
    public RequestDetailDto accept(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                   @PathVariable Long quoteId, @Valid @RequestBody AcceptRequest body) {
        return orderService.accept(me.id(), id, quoteId, body);
    }
}
