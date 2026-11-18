package com.kirana.parchi;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.kirana.config.AuthUser;
import com.kirana.parchi.dto.ScanResultDto;

import lombok.RequiredArgsConstructor;

/** CUSTOMER role is enforced for /api/parchi/** in SecurityConfig. */
@RestController
@RequestMapping("/api/parchi")
@RequiredArgsConstructor
public class ParchiController {

    private final ParchiService parchiService;

    /** multipart/form-data with the photo in field "image". */
    @PostMapping("/scan")
    public ScanResultDto scan(@AuthenticationPrincipal AuthUser me,
                              @RequestParam(value = "image", required = false) MultipartFile image) {
        return parchiService.scan(me.id(), image);
    }
}
