package com.kirana.quote;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kirana.config.AuthUser;
import com.kirana.quote.dto.QuoteDto;
import com.kirana.quote.dto.QuoteInput;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Store owner sends or edits their quote. STORE_OWNER role is enforced for /api/store/** in SecurityConfig. */
@RestController
@RequestMapping("/api/store/requests")
@RequiredArgsConstructor
public class StoreQuoteController {

    private final QuoteService quoteService;

    @PutMapping("/{id:\\d+}/quote")
    public QuoteDto upsert(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                           @Valid @RequestBody QuoteInput input) {
        return quoteService.upsert(me.id(), id, input);
    }
}
