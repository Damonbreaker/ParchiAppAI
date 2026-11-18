package com.kirana.community;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.community.dto.CommunityDto;
import com.kirana.config.AuthUser;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/communities")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService service;

    @GetMapping
    public List<CommunityDto> list(@AuthenticationPrincipal AuthUser me) {
        return service.listFor(me.id());
    }

    @PostMapping("/{id}/join")
    public CommunityDto join(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.join(me.id(), id);
    }
}
