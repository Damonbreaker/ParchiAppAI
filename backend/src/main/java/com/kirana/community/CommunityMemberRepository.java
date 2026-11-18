package com.kirana.community;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityMemberRepository extends JpaRepository<CommunityMember, Long> {

    Optional<CommunityMember> findByCommunityIdAndUserId(Long communityId, Long userId);

    List<CommunityMember> findByUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByCommunityIdAndUserId(Long communityId, Long userId);

    long countByCommunityId(Long communityId);

    boolean existsByCommunityIdAndAliasIgnoreCase(Long communityId, String alias);
}
