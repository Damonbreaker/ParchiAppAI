package com.kirana.community.dto;

public record CommunityDto(
        Long id,
        String code,
        String societyName,
        String towerName,
        String label,
        boolean member,
        String myAlias) {
}
