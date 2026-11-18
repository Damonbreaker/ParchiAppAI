package com.kirana.shared;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SharedListRepository extends JpaRepository<SharedList, Long> {

    @EntityGraph(attributePaths = { "items", "owner" })
    Optional<SharedList> findByToken(String token);
}
