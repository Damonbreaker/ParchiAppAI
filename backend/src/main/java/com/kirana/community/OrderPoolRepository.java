package com.kirana.community;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderPoolRepository extends JpaRepository<OrderPool, Long> {

    // Avoid MultipleBagFetchException: don't fetch members.items in the same graph.
    @EntityGraph(attributePaths = { "community", "host", "members", "members.user" })
    Optional<OrderPool> findByToken(String token);

    @EntityGraph(attributePaths = { "community", "host", "members", "members.user" })
    Optional<OrderPool> findByOrderRequestId(Long orderRequestId);
}
