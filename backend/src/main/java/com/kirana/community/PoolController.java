package com.kirana.community;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.community.dto.CreatePoolRequest;
import com.kirana.community.dto.PoolDto;
import com.kirana.community.dto.SetPoolListRequest;
import com.kirana.config.AuthUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/pools")
@RequiredArgsConstructor
public class PoolController {

    private final PoolService service;

    @PostMapping
    public PoolDto create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreatePoolRequest req) {
        return service.create(me.id(), req);
    }

    @GetMapping("/{token}")
    public PoolDto get(@AuthenticationPrincipal AuthUser me, @PathVariable String token) {
        return service.get(token, me.id());
    }

    @PostMapping("/{token}/join")
    public PoolDto join(@AuthenticationPrincipal AuthUser me, @PathVariable String token) {
        return service.join(token, me.id());
    }

    @PutMapping("/{token}/my-list")
    public PoolDto setMyList(
            @AuthenticationPrincipal AuthUser me,
            @PathVariable String token,
            @Valid @RequestBody SetPoolListRequest req) {
        return service.setMyList(token, me.id(), req);
    }

    @PostMapping("/{token}/close")
    public PoolDto close(@AuthenticationPrincipal AuthUser me, @PathVariable String token) {
        return service.closeWindow(token, me.id());
    }

    @PostMapping("/{token}/send")
    public PoolDto send(@AuthenticationPrincipal AuthUser me, @PathVariable String token) {
        return service.sendToShops(token, me.id());
    }

    @PostMapping("/{token}/approve")
    public PoolDto approve(@AuthenticationPrincipal AuthUser me, @PathVariable String token) {
        return service.approveMyBag(token, me.id());
    }
}
