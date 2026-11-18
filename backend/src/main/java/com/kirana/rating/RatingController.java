package com.kirana.rating;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.config.AuthUser;
import com.kirana.rating.dto.RatingDto;
import com.kirana.rating.dto.RatingInput;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class RatingController {

    private final RatingService ratings;

    /** Customer rates the winning store after pickup. */
    @PostMapping("/requests/{id}/rating")
    @ResponseStatus(HttpStatus.CREATED)
    public RatingDto rateStore(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                               @Valid @RequestBody RatingInput body) {
        return ratings.rateStore(me.id(), id, body);
    }

    /** Shopkeeper rates the customer after pickup. */
    @PostMapping("/store/requests/{id}/rating")
    @ResponseStatus(HttpStatus.CREATED)
    public RatingDto rateCustomer(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                  @Valid @RequestBody RatingInput body) {
        return ratings.rateCustomer(me.id(), id, body);
    }

    @GetMapping("/requests/{id}/ratings")
    public Map<String, RatingDto> requestRatingsCustomer(@AuthenticationPrincipal AuthUser me,
                                                         @PathVariable Long id) {
        return ratings.ratingsForRequest(id, me.id());
    }

    @GetMapping("/store/requests/{id}/ratings")
    public Map<String, RatingDto> requestRatingsStore(@AuthenticationPrincipal AuthUser me,
                                                      @PathVariable Long id) {
        return ratings.ratingsForRequest(id, me.id());
    }
}
