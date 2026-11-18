package com.kirana.request;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.config.AuthUser;
import com.kirana.request.dto.BagPickupRequest;
import com.kirana.request.dto.PickupRequest;
import com.kirana.request.dto.StoreRequestDetailDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Winning store moves the order on. STORE_OWNER role is enforced for /api/store/** in SecurityConfig. */
@RestController
@RequestMapping("/api/store/requests")
@RequiredArgsConstructor
public class StoreOrderController {

    private final OrderService orderService;

    @PostMapping("/{id:\\d+}/ready")
    public StoreRequestDetailDto ready(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return orderService.markReady(me.id(), id);
    }

    @PostMapping("/{id:\\d+}/picked-up")
    public StoreRequestDetailDto pickedUp(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                          @Valid @RequestBody PickupRequest body) {
        return orderService.markPickedUp(me.id(), id, body.pickupCode());
    }

    @PostMapping("/{id:\\d+}/bags/deliver")
    public StoreRequestDetailDto deliverBag(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                             @Valid @RequestBody BagPickupRequest body) {
        return orderService.markBagDelivered(me.id(), id, body.memberId(), body.pickupCode());
    }
}
