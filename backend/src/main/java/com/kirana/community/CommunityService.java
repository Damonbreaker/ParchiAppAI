package com.kirana.community;

import java.security.SecureRandom;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.common.ApiException;
import com.kirana.community.dto.CommunityDto;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CommunityService {

    private static final String ALIAS_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CommunityRepository communities;
    private final CommunityMemberRepository members;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public List<CommunityDto> listFor(Long userId) {
        return communities.findByActiveTrueOrderBySocietyNameAscTowerNameAsc().stream()
                .map(c -> toDto(c, userId))
                .toList();
    }

    @Transactional
    public CommunityDto join(Long userId, Long communityId) {
        Community community = communities.findById(communityId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMMUNITY_NOT_FOUND", "Society/tower not found"));
        if (!community.isActive()) {
            throw new ApiException(HttpStatus.GONE, "COMMUNITY_INACTIVE", "This community is not accepting members");
        }
        return members.findByCommunityIdAndUserId(communityId, userId)
                .map(m -> toDto(community, userId))
                .orElseGet(() -> {
                    User user = users.findById(userId)
                            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please log in"));
                    CommunityMember m = new CommunityMember();
                    m.setCommunity(community);
                    m.setUser(user);
                    m.setAlias(newAlias(communityId));
                    members.save(m);
                    return toDto(community, userId);
                });
    }

    @Transactional(readOnly = true)
    public CommunityMember requireMembership(Long communityId, Long userId) {
        return members.findByCommunityIdAndUserId(communityId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER",
                        "Only residents of this society/tower can join this pool"));
    }

    public boolean isMember(Long communityId, Long userId) {
        return members.existsByCommunityIdAndUserId(communityId, userId);
    }

    private CommunityDto toDto(Community c, Long userId) {
        var membership = userId == null ? null : members.findByCommunityIdAndUserId(c.getId(), userId).orElse(null);
        return new CommunityDto(
                c.getId(),
                c.getCode(),
                c.getSocietyName(),
                c.getTowerName(),
                c.getLabel(),
                membership != null,
                membership != null ? membership.getAlias() : null);
    }

    private String newAlias(Long communityId) {
        for (int i = 0; i < 40; i++) {
            String alias = "Neighbor-"
                    + ALIAS_ALPHABET.charAt(RANDOM.nextInt(ALIAS_ALPHABET.length()))
                    + (10 + RANDOM.nextInt(90));
            if (!members.existsByCommunityIdAndAliasIgnoreCase(communityId, alias)) {
                return alias;
            }
        }
        return "Neighbor-" + (1000 + RANDOM.nextInt(9000));
    }
}
