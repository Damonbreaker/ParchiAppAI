package com.kirana.community;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityRepository extends JpaRepository<Community, Long> {

    List<Community> findByActiveTrueOrderBySocietyNameAscTowerNameAsc();

    Optional<Community> findByCodeIgnoreCase(String code);
}
