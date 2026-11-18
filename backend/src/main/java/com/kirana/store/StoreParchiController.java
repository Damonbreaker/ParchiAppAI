package com.kirana.store;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.kirana.config.AuthUser;
import com.kirana.parchi.ParchiService;
import com.kirana.parchi.dto.ScanResultDto;

import lombok.RequiredArgsConstructor;

/**
 * Shopkeeper scan: item / qty / price list photo → base catalogue names + prices.
 */
@RestController
@RequestMapping("/api/store/parchi")
@RequiredArgsConstructor
public class StoreParchiController {

    private final ParchiService parchiService;

    @PostMapping("/scan")
    public ScanResultDto scan(@AuthenticationPrincipal AuthUser me,
                              @RequestParam(value = "image", required = false) MultipartFile image) {
        return parchiService.scan(me.id(), image, "shop");
    }
}
