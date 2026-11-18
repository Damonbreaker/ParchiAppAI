package com.kirana.shared;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.config.AuthUser;
import com.kirana.shared.dto.AddSharedItemRequest;
import com.kirana.shared.dto.CreateSharedListRequest;
import com.kirana.shared.dto.SharedListDto;
import com.kirana.shared.dto.UpdateSharedItemRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/shared-lists")
@RequiredArgsConstructor
public class SharedListController {

    private final SharedListService service;

    @PostMapping
    public SharedListDto create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody(required = false) CreateSharedListRequest req) {
        return service.create(me.id(), req != null ? req : new CreateSharedListRequest(null, null));
    }

    @GetMapping("/{token}")
    public SharedListDto get(
            @PathVariable String token,
            @AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) String guestKey) {
        return service.get(token, me, guestKey);
    }

    @PostMapping("/{token}/items")
    public SharedListDto addItem(
            @PathVariable String token,
            @AuthenticationPrincipal AuthUser me,
            @Valid @RequestBody AddSharedItemRequest req) {
        return service.addItem(token, me, req);
    }

    @PutMapping("/{token}/items/{itemId}")
    public SharedListDto updateItem(
            @PathVariable String token,
            @PathVariable Long itemId,
            @AuthenticationPrincipal AuthUser me,
            @Valid @RequestBody UpdateSharedItemRequest req) {
        return service.updateItem(token, itemId, me, req);
    }

    @DeleteMapping("/{token}/items/{itemId}")
    public SharedListDto deleteItem(
            @PathVariable String token,
            @PathVariable Long itemId,
            @AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) String guestKey) {
        return service.deleteItem(token, itemId, me, guestKey);
    }

    @PostMapping("/{token}/lock")
    public SharedListDto lock(@PathVariable String token, @AuthenticationPrincipal AuthUser me) {
        return service.lock(token, me.id());
    }

    @PostMapping("/{token}/unlock")
    public SharedListDto unlock(@PathVariable String token, @AuthenticationPrincipal AuthUser me) {
        return service.unlock(token, me.id());
    }

    @PostMapping("/{token}/prepare-place")
    public SharedListDto preparePlace(@PathVariable String token, @AuthenticationPrincipal AuthUser me) {
        return service.preparePlace(token, me.id());
    }

    @PostMapping("/{token}/placed")
    public SharedListDto markPlaced(@PathVariable String token, @AuthenticationPrincipal AuthUser me) {
        return service.markPlaced(token, me.id());
    }
}
