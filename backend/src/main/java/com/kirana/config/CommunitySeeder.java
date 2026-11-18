package com.kirana.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.community.Community;
import com.kirana.community.CommunityMember;
import com.kirana.community.CommunityMemberRepository;
import com.kirana.community.CommunityRepository;
import com.kirana.user.Role;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Seeds recognised society/tower communities and enrolls demo customers. */
@Slf4j
@Component
@Order(200)
@Profile("dev & !prod")
@RequiredArgsConstructor
public class CommunitySeeder implements ApplicationRunner {

    private final CommunityRepository communities;
    private final CommunityMemberRepository members;
    private final UserRepository users;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (communities.count() > 0) {
            log.info("Communities already present, skipping seed");
            return;
        }
        Community a = save("PALM-A", "Palm Grove Society", "Tower A",
                "Palm Grove · Tower A", DevDataSeeder.TEST_LAT, DevDataSeeder.TEST_LNG);
        Community b = save("PALM-B", "Palm Grove Society", "Tower B",
                "Palm Grove · Tower B", DevDataSeeder.TEST_LAT + 0.0004, DevDataSeeder.TEST_LNG + 0.0003);
        Community c = save("GREEN-C", "Green Park Residency", "Tower C",
                "Green Park · Tower C", DevDataSeeder.TEST_LAT - 0.0005, DevDataSeeder.TEST_LNG + 0.0002);

        enrollCustomers(a, "Neighbor-A11", "Neighbor-A22", "Neighbor-A33");
        enrollCustomers(b, "Neighbor-B11", "Neighbor-B22", "Neighbor-B33");
        // Leave Green Park empty so join flow can be demoed.
        log.info("Seeded communities {} / {} / {}", a.getCode(), b.getCode(), c.getCode());
    }

    private Community save(String code, String society, String tower, String label, double lat, double lng) {
        Community c = new Community();
        c.setCode(code);
        c.setSocietyName(society);
        c.setTowerName(tower);
        c.setLabel(label);
        c.setLat(lat);
        c.setLng(lng);
        c.setActive(true);
        return communities.save(c);
    }

    private void enrollCustomers(Community community, String... aliases) {
        var customers = users.findAll().stream().filter(u -> u.getRole() == Role.CUSTOMER).sorted((x, y) ->
                Long.compare(x.getId(), y.getId())).toList();
        for (int i = 0; i < Math.min(customers.size(), aliases.length); i++) {
            User u = customers.get(i);
            if (members.existsByCommunityIdAndUserId(community.getId(), u.getId())) {
                continue;
            }
            CommunityMember m = new CommunityMember();
            m.setCommunity(community);
            m.setUser(u);
            m.setAlias(aliases[i]);
            members.save(m);
        }
    }
}
