package com.kirana.store;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.config.AuthUser;
import com.kirana.store.dto.CreateStoreRequest;
import com.kirana.store.dto.MyStoreDto;
import com.kirana.store.dto.PublicStoreDto;
import com.kirana.store.dto.TopUpParchiRequest;
import com.kirana.store.dto.UpdateStoreRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.RequiredArgsConstructor;

/** /api/stores mixes roles, so access is set per method rather than by URL. */
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private static final String LAT_MSG = "must be between -90 and 90";
    private static final String LNG_MSG = "must be between -180 and 180";

    private final StoreService storeService;

    // ------------------------------------------------------------ store owner

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('STORE_OWNER')")
    public MyStoreDto create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreateStoreRequest req) {
        return storeService.createMine(me.id(), req);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('STORE_OWNER')")
    public MyStoreDto mine(@AuthenticationPrincipal AuthUser me) {
        return storeService.getMine(me.id());
    }

    @PatchMapping("/mine")
    @PreAuthorize("hasRole('STORE_OWNER')")
    public MyStoreDto updateMine(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody UpdateStoreRequest req) {
        return storeService.updateMine(me.id(), req);
    }

    /** Top up the shopkeeper Parchi wallet (demo packs welcome). */
    @PostMapping("/mine/parchi/top-up")
    @PreAuthorize("hasRole('STORE_OWNER')")
    public MyStoreDto topUpParchi(@AuthenticationPrincipal AuthUser me,
                                  @Valid @RequestBody TopUpParchiRequest req) {
        return storeService.topUpParchi(me.id(), req.amount());
    }

    // ------------------------------------------------------------ customer

    @GetMapping("/nearby")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<PublicStoreDto> nearby(
            @RequestParam @DecimalMin(value = "-90.0", message = LAT_MSG) @DecimalMax(value = "90.0", message = LAT_MSG) Double lat,
            @RequestParam @DecimalMin(value = "-180.0", message = LNG_MSG) @DecimalMax(value = "180.0", message = LNG_MSG) Double lng) {
        return storeService.nearby(lat, lng);
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<PublicStoreDto> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DecimalMin(value = "-90.0", message = LAT_MSG) @DecimalMax(value = "90.0", message = LAT_MSG) Double lat,
            @RequestParam(required = false) @DecimalMin(value = "-180.0", message = LNG_MSG) @DecimalMax(value = "180.0", message = LNG_MSG) Double lng) {
        return storeService.search(q, lat, lng);
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PublicStoreDto storePage(
            @PathVariable Long id,
            @RequestParam(required = false) @DecimalMin(value = "-90.0", message = LAT_MSG) @DecimalMax(value = "90.0", message = LAT_MSG) Double lat,
            @RequestParam(required = false) @DecimalMin(value = "-180.0", message = LNG_MSG) @DecimalMax(value = "180.0", message = LNG_MSG) Double lng) {
        return storeService.getForCustomer(id, lat, lng);
    }
}
