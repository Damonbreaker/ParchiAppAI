package com.kirana.request;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface OrderRequestRepository extends JpaRepository<OrderRequest, Long> {

    Slice<OrderRequest> findByCustomerIdOrderByCreatedAtDescIdDesc(Long customerId, Pageable page);

    Optional<OrderRequest> findByIdAndCustomerId(Long id, Long customerId);

    /**
     * SELECT ... FOR UPDATE: every status change and every quote save takes this lock first, so they run
     * one after another per request (quote save vs cancel now, quote save vs accept in Phase 5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OrderRequest r where r.id = :id")
    Optional<OrderRequest> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OrderRequest r where r.id = :id and r.customer.id = :customerId")
    Optional<OrderRequest> findOwnForUpdate(@Param("id") Long id, @Param("customerId") Long customerId);
}
