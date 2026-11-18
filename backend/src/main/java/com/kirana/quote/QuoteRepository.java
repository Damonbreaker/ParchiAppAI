package com.kirana.quote;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuoteRepository extends JpaRepository<Quote, Long> {

    Optional<Quote> findByRequestIdAndStoreId(Long requestId, Long storeId);

    List<Quote> findByRequestId(Long requestId);

    /** This store's quotes for the given requests (inbox myQuoteTotal). */
    List<Quote> findByStoreIdAndRequestIdIn(Long storeId, Collection<Long> requestIds);

    /** Quote count and lowest total per request, any quote status (customer's request list). */
    @Query("""
            select q.request.id as requestId, count(q) as quoteCount, min(q.totalPrice) as lowestTotal
              from Quote q
             where q.request.id in :requestIds
             group by q.request.id""")
    List<QuoteStats> statsForRequests(@Param("requestIds") Collection<Long> requestIds);

    interface QuoteStats {
        Long getRequestId();

        long getQuoteCount();

        BigDecimal getLowestTotal();
    }
}
