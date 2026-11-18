package com.kirana.config;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kirana.rating.Rating;
import com.kirana.rating.RatingRepository;
import com.kirana.rating.RatingTargetType;
import com.kirana.request.OrderRequest;
import com.kirana.request.OrderRequestRepository;
import com.kirana.store.Store;
import com.kirana.store.StoreRepository;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds a few STORE ratings so nearby cards show stars + quality pills in demos.
 * One rating per (request, rater) — uses different seed customers/requests.
 */
@Slf4j
@Component
@Order(50)
@Profile("dev & !prod")
@RequiredArgsConstructor
public class DemoRatingSeeder implements ApplicationRunner {

    private final RatingRepository ratings;
    private final StoreRepository stores;
    private final UserRepository users;
    private final OrderRequestRepository requests;
    private final ObjectMapper mapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (ratings.count() > 0) {
            log.info("Ratings already present, skipping demo rating seed");
            return;
        }
        List<OrderRequest> reqs = requests.findAll(Sort.by("id"));
        if (reqs.isEmpty()) {
            log.info("No requests for demo ratings; skipping");
            return;
        }

        record Seed(String ownerPhone, String raterPhone, int stars, List<String> qualities) {
        }
        List<Seed> seeds = List.of(
                new Seed("9000000011", "9000000001", 5,
                        List.of("friendly", "low_price", "fast_packing", "consistent_stock")),
                new Seed("9000000012", "9000000002", 4,
                        List.of("large_inventory", "delivery", "clean_store")),
                new Seed("9000000014", "9000000003", 5,
                        List.of("fair_weights", "good_packaging", "friendly")),
                new Seed("9000000015", "9000000001", 4,
                        List.of("low_price", "easy_return", "delivery")));

        int n = 0;
        for (int i = 0; i < seeds.size(); i++) {
            Seed seed = seeds.get(i);
            OrderRequest req = reqs.get(i % reqs.size());
            User rater = users.findByPhone(seed.raterPhone()).orElse(null);
            User owner = users.findByPhone(seed.ownerPhone()).orElse(null);
            if (rater == null || owner == null) {
                continue;
            }
            if (ratings.existsByRequestIdAndRaterId(req.getId(), rater.getId())) {
                continue;
            }
            Store store = stores.findByOwnerId(owner.getId()).orElse(null);
            if (store == null) {
                continue;
            }
            Rating r = new Rating();
            r.setRequest(req);
            r.setRater(rater);
            r.setTargetType(RatingTargetType.STORE);
            r.setStore(store);
            r.setStars(seed.stars());
            r.setQualities(mapper.writeValueAsString(seed.qualities()));
            ratings.save(r);
            n++;
        }
        log.info("Seeded {} demo store ratings", n);
    }
}
