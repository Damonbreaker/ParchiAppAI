package com.kirana.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.kirana.common.ApiException;
import com.kirana.rating.RatingService;
import com.kirana.store.dto.CreateStoreRequest;
import com.kirana.store.dto.MyStoreDto;
import com.kirana.store.dto.UpdateStoreRequest;
import com.kirana.user.UserRepository;

class StoreServiceTest {

    private final StoreRepository stores = mock(StoreRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RatingService ratingService = mock(RatingService.class);
    private final StoreService service = new StoreService(stores, users, new StoreProperties(3, 20), ratingService);

    @Test
    void secondStoreForSameOwnerIs409() {
        when(stores.existsByOwnerId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.createMine(1L,
                new CreateStoreRequest("Gupta Kirana", "Main Market", "Sector 93", null, 28.5, 77.38)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("STORE_EXISTS");
                });
        verify(stores, never()).saveAndFlush(any());
    }

    @Test
    void updateMineTogglesOpenAndKeepsOtherFields() {
        Store s = new Store();
        s.setName("Gupta Kirana");
        s.setOpen(true);
        when(stores.findByOwnerId(1L)).thenReturn(Optional.of(s));

        MyStoreDto res = service.updateMine(1L,
                new UpdateStoreRequest(null, null, null, null, null, null, false));

        assertThat(res.isOpen()).isFalse();
        assertThat(s.isOpen()).isFalse();
        assertThat(res.name()).isEqualTo("Gupta Kirana");
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(StoreService.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }
}
