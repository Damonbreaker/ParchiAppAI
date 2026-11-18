package com.kirana.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.kirana.user.dto.UpdateMeRequest;
import com.kirana.user.dto.UserDto;

class UserServiceTest {

    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final UserService service = new UserService(users);

    @Test
    void updateMeChangesOnlySentFields() {
        User u = new User();
        u.setId(1L);
        u.setName("Ramesh");
        u.setPreferredLang(Lang.EN);
        when(users.findById(1L)).thenReturn(Optional.of(u));

        UserDto res = service.updateMe(1L, new UpdateMeRequest(null, Lang.HI));

        assertThat(res.preferredLang()).isEqualTo(Lang.HI);
        assertThat(res.name()).isEqualTo("Ramesh");
        assertThat(u.getPreferredLang()).isEqualTo(Lang.HI);
    }
}
