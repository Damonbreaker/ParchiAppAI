package com.kirana.community.dto;

import java.util.List;

import com.kirana.community.PoolMember;
import com.kirana.community.PoolMemberStatus;

public record PoolBagDto(
        Long memberId,
        String alias,
        PoolMemberStatus status,
        boolean mine,
        boolean host,
        boolean fromPhoto,
        int itemCount,
        List<PoolItemDto> items,
        /** Only for the bag owner after they approve; never for other neighbours. */
        String pickupCode) {

    public static PoolBagDto from(PoolMember m, Long viewerUserId, Long hostUserId, boolean revealOwnOtp) {
        boolean mine = viewerUserId != null && viewerUserId.equals(m.getUser().getId());
        String otp = null;
        if (mine && revealOwnOtp && m.getApprovedAt() != null && m.getPickupCode() != null
                && m.getDeliveredAt() == null) {
            otp = m.getPickupCode();
        }
        return new PoolBagDto(
                m.getId(),
                m.getAlias(),
                m.getStatus(),
                mine,
                hostUserId != null && hostUserId.equals(m.getUser().getId()),
                m.isFromPhoto(),
                m.getItems().size(),
                m.getItems().stream().map(PoolItemDto::from).toList(),
                otp);
    }
}
