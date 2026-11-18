package com.kirana.request;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequestTargetRepository extends JpaRepository<RequestTarget, Long> {

    Optional<RequestTarget> findByRequestIdAndStoreId(Long requestId, Long storeId);

    /** All requests sent to a store, newest first. Slice = one page plus hasNext, without a count query. */
    @Query("""
            select t from RequestTarget t join fetch t.request r
             where t.store.id = :storeId
             order by r.createdAt desc, r.id desc""")
    Slice<RequestTarget> findInbox(@Param("storeId") Long storeId, Pageable page);

    @Query("""
            select t from RequestTarget t join fetch t.request r
             where t.store.id = :storeId and r.status = :status
             order by r.createdAt desc, r.id desc""")
    Slice<RequestTarget> findInboxWithStatus(@Param("storeId") Long storeId, @Param("status") RequestStatus status,
                                            Pageable page);

    /** ORDERS: this store's quote was accepted and the order is still active (ACCEPTED or READY). */
    @Query("""
            select t from RequestTarget t join fetch t.request r
             where t.store.id = :storeId and r.status in :active
               and exists (select q.id from Quote q where q.id = r.acceptedQuoteId and q.store.id = :storeId)
             order by r.createdAt desc, r.id desc""")
    Slice<RequestTarget> findOrders(@Param("storeId") Long storeId, @Param("active") Collection<RequestStatus> active,
                                   Pageable page);

    /** CLOSED: not OPEN and not an active order of this store (won and picked up, lost, cancelled). */
    @Query("""
            select t from RequestTarget t join fetch t.request r
             where t.store.id = :storeId and r.status <> :open
               and not (r.status in :active
                        and exists (select q.id from Quote q where q.id = r.acceptedQuoteId and q.store.id = :storeId))
             order by r.createdAt desc, r.id desc""")
    Slice<RequestTarget> findClosed(@Param("storeId") Long storeId, @Param("open") RequestStatus open,
                                    @Param("active") Collection<RequestStatus> active, Pageable page);
}
