package com.kirana.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.common.Unit;
import com.kirana.quote.QuoteItemStatus;
import com.kirana.quote.QuoteRepository;
import com.kirana.quote.QuoteService;
import com.kirana.quote.dto.QuoteDto;
import com.kirana.quote.dto.QuoteInput;
import com.kirana.quote.dto.QuoteItemInput;
import com.kirana.request.OrderRequest;
import com.kirana.request.OrderRequestRepository;
import com.kirana.request.RequestItem;
import com.kirana.request.RequestMode;
import com.kirana.request.RequestService;
import com.kirana.request.RequestStatus;
import com.kirana.request.RequestTarget;
import com.kirana.request.dto.CreateRequestRequest;
import com.kirana.request.dto.ItemInput;
import com.kirana.request.dto.RequestDetailDto;
import com.kirana.store.Store;
import com.kirana.store.StoreRepository;
import com.kirana.user.Lang;
import com.kirana.user.Role;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Local dummy data around the Noida test point (28.5050, 77.3800).
 * Runs only with the "dev" profile (the local default). Users/stores are skipped if the first
 * seed customer exists; seed requests are skipped if any request exists; seed quotes are skipped if any
 * quote exists.
 * Area names are labels only; positions come from the distance/bearing offsets below.
 */
@Slf4j
@Component
@Profile("dev & !prod")   // never in prod, even if both profiles are active
@RequiredArgsConstructor
public class DevDataSeeder implements ApplicationRunner {

    public static final double TEST_LAT = 28.5050;
    public static final double TEST_LNG = 77.3800;
    public static final String PASSWORD = "test123";

    private static final String FIRST_SEED_PHONE = "9000000001";
    private static final double KM_PER_DEG_LAT = 111.32;
    private static final String SEP = "\\|";

    private final UserRepository users;
    private final StoreRepository stores;
    private final OrderRequestRepository requests;
    private final RequestService requestService;
    private final QuoteRepository quotes;
    private final QuoteService quoteService;
    private final PasswordEncoder encoder;

    record SeedStore(String storeName, String area, String ownerName, double distanceKm, double bearingDeg,
                     boolean open) {
    }

    record SeedCustomer(String name, Lang lang) {
    }

    // 10 inside the 3 km radius, 5 outside; 3 closed (2 inside, 1 outside).
    static final List<SeedStore> STORES = List.of(
            new SeedStore("Sharma General Store", "Sector 93", "Ramesh Sharma", 0.2, 30, true),
            new SeedStore("Gupta Kirana", "Sector 93", "Sunil Gupta", 0.5, 120, true),
            new SeedStore("Shree Balaji Provision", "Sector 128", "Mahesh Agarwal", 0.8, 210, true),
            new SeedStore("Agarwal Kirana Store", "Sector 135", "Pankaj Agarwal", 1.1, 300, false),
            new SeedStore("Jain Departmental Store", "Sector 137", "Ashok Jain", 1.4, 60, true),
            new SeedStore("Krishna Provision Store", "Sector 104", "Gopal Yadav", 1.7, 150, true),
            new SeedStore("Bansal Grocery", "Sector 128", "Vikas Bansal", 2.0, 240, true),
            new SeedStore("Maa Durga Kirana", "Sector 135", "Rajesh Mishra", 2.3, 330, false),
            new SeedStore("Verma Ration Store", "Sector 137", "Anil Verma", 2.6, 90, true),
            new SeedStore("Ganesh General Store", "Sector 104", "Ganesh Prasad", 2.9, 180, true),
            new SeedStore("Shiv Shakti Kirana", "Sector 93", "Shivam Tiwari", 3.4, 270, true),
            new SeedStore("Om Sai Provision", "Sector 128", "Sai Kumar", 3.8, 0, true),
            new SeedStore("Yadav Kirana Store", "Sector 135", "Manoj Yadav", 4.2, 45, false),
            new SeedStore("Laxmi Super Mart", "Sector 137", "Deepak Goel", 4.6, 135, true),
            new SeedStore("हरि ओम किराना", "Sector 104", "Hari Om Singh", 5.0, 225, true));

    static final List<SeedCustomer> CUSTOMERS = List.of(
            new SeedCustomer("Anita Singh", Lang.EN),
            new SeedCustomer("राहुल वर्मा", Lang.HI),
            new SeedCustomer("Priya Mehta", Lang.EN));

    /** A seed request: which customer (1-3), small offset from the test point, items as "name|qty|UNIT". */
    record SeedRequest(int customer, double dLat, double dLng, List<String> items) {
    }

    static final List<SeedRequest> REQUESTS = List.of(
            new SeedRequest(1, 0.0010, 0.0010, List.of(
                    "Aashirvaad atta|5|KG", "चीनी|2|KG", "Amul butter|1|PACKET",
                    "Eggs|1|DOZEN", "Tata salt|1|KG", "Doodh|2|L")),
            new SeedRequest(2, -0.0010, -0.0008, List.of(
                    "अरहर दाल|1|KG", "बासमती चावल|5|KG", "सरसों का तेल|1|L",
                    "हल्दी पाउडर|200|G", "Maggi|4|PACKET")),
            new SeedRequest(3, 0.0015, -0.0020, List.of(
                    "Bread|1|PACKET", "Dahi|500|G", "Kele|1|DOZEN", "Pyaz|2|KG",
                    "टमाटर|1|KG", "Parle-G|3|PACKET")),
            new SeedRequest(1, -0.0020, 0.0015, List.of(
                    "Surf Excel|1|KG", "Vim bar|2|PCS", "Colgate|1|PCS",
                    "मूंग दाल|0.5|KG", "Paneer|250|G", "Fortune oil|1|L")));

    /**
     * Quotes for one seed request (index into REQUESTS) from its nearest targets, in distance order.
     * Each store gives a packing time and one line per item, in item order:
     * "A|qty|UNIT|price" (available), "P|qty|UNIT|price" (partial) or "U" (unavailable). Prices are line totals.
     */
    record SeedQuote(int requestIndex, List<SeedStoreQuote> stores) {
    }

    record SeedStoreQuote(int packingTimeMins, List<String> lines) {
    }

    static final List<SeedQuote> QUOTES = List.of(
            // Aashirvaad atta 5 KG, चीनी 2 KG, Amul butter 1 PACKET, Eggs 1 DOZEN, Tata salt 1 KG, Doodh 2 L
            new SeedQuote(0, List.of(
                    new SeedStoreQuote(15, List.of("A|5|KG|285", "A|2|KG|90", "A|1|PACKET|60",
                            "A|1|DOZEN|84", "A|1|KG|28", "A|2|L|132")),
                    new SeedStoreQuote(25, List.of("A|5|KG|280", "A|2|KG|88", "A|1|PACKET|58",
                            "P|6|PCS|42", "A|1|KG|28", "U")),
                    new SeedStoreQuote(10, List.of("A|5|KG|290", "A|2|KG|92", "A|1|PACKET|62",
                            "A|1|DOZEN|90", "A|1|KG|30", "A|2|L|128")))),
            // अरहर दाल 1 KG, बासमती चावल 5 KG, सरसों का तेल 1 L, हल्दी पाउडर 200 G, Maggi 4 PACKET
            new SeedQuote(1, List.of(
                    new SeedStoreQuote(20, List.of("A|1|KG|160", "A|5|KG|550", "A|1|L|185",
                            "A|200|G|62", "A|4|PACKET|56")),
                    new SeedStoreQuote(30, List.of("P|500|G|82", "A|5|KG|520", "A|1|L|180",
                            "A|2|PACKET|64", "P|2|PACKET|28")))));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByPhone(FIRST_SEED_PHONE)) {
            log.info("Dev seed users and stores already present, skipping");
        } else {
            seedUsersAndStores();
        }
        if (requests.count() > 0) {
            log.info("Requests already exist, skipping seed requests");
        } else {
            seedRequests();
        }
        if (quotes.count() > 0) {
            log.info("Quotes already exist, skipping seed quotes");
        } else {
            seedQuotes();
        }
    }

    private void seedQuotes() {
        List<OrderRequest> all = requests.findAll(Sort.by("id"));
        StringBuilder out = new StringBuilder("\n\n===== DEV SEED QUOTES =====\n");
        for (SeedQuote sq : QUOTES) {
            List<String> names = REQUESTS.get(sq.requestIndex()).items().stream()
                    .map(s -> s.split(SEP)[0]).toList();
            OrderRequest r = all.stream()
                    .filter(x -> x.getStatus() == RequestStatus.OPEN)
                    .filter(x -> x.getItems().stream().map(RequestItem::getName).toList().equals(names))
                    .findFirst().orElse(null);
            if (r == null) {
                log.warn("Open seed request {} not found, skipping its quotes", sq.requestIndex() + 1);
                continue;
            }
            List<RequestTarget> targets = r.getTargets(); // nearest first
            for (int s = 0; s < sq.stores().size() && s < targets.size(); s++) {
                SeedStoreQuote ssq = sq.stores().get(s);
                List<QuoteItemInput> lines = new ArrayList<>();
                for (int i = 0; i < r.getItems().size(); i++) {
                    String[] p = ssq.lines().get(i).split(SEP);
                    Long itemId = r.getItems().get(i).getId();
                    lines.add(p[0].equals("U")
                            ? new QuoteItemInput(itemId, null, null, null, QuoteItemStatus.UNAVAILABLE)
                            : new QuoteItemInput(itemId, new BigDecimal(p[1]), Unit.valueOf(p[2]),
                                    new BigDecimal(p[3]),
                                    p[0].equals("P") ? QuoteItemStatus.PARTIAL : QuoteItemStatus.AVAILABLE));
                }
                RequestTarget t = targets.get(s);
                QuoteDto q = quoteService.upsert(t.getStore().getOwner().getId(), r.getId(),
                        new QuoteInput(ssq.packingTimeMins(), lines));
                out.append(String.format("  request #%d  %-24s owner %s  Rs %s  %d min%n", r.getId(),
                        t.getStore().getName(), t.getStore().getOwner().getPhone(), q.totalPrice(),
                        q.packingTimeMins()));
            }
        }
        out.append("===========================\n");
        log.info(out.toString());
    }

    private void seedRequests() {
        StringBuilder out = new StringBuilder("\n\n===== DEV SEED REQUESTS (NEAREST, OPEN) =====\n");
        for (SeedRequest sr : REQUESTS) {
            User customer = users.findByPhone(phone(sr.customer())).orElse(null);
            if (customer == null) {
                log.warn("Seed customer {} missing, skipping seed requests", phone(sr.customer()));
                return;
            }
            List<ItemInput> items = sr.items().stream().map(s -> {
                String[] p = s.split(SEP);
                return new ItemInput(p[0], new BigDecimal(p[1]), Unit.valueOf(p[2]));
            }).toList();
            RequestDetailDto r = requestService.create(customer.getId(), new CreateRequestRequest(
                    items, TEST_LAT + sr.dLat(), TEST_LNG + sr.dLng(), RequestMode.NEAREST, null, false));
            out.append(String.format("  #%d by %s -> %d stores: %s%n", r.id(), customer.getPhone(),
                    r.targets().size(), r.targets().stream().map(t -> t.storeName()).toList()));
        }
        out.append("=============================================\n");
        log.info(out.toString());
    }

    private void seedUsersAndStores() {
        String hash = encoder.encode(PASSWORD); // same hash for every seed user is fine for dev

        StringBuilder out = new StringBuilder("\n\n===== DEV SEED DATA (password for all: " + PASSWORD + ") =====\n");
        out.append("Test location: ").append(TEST_LAT).append(", ").append(TEST_LNG).append(" (Noida)\n\n");
        out.append("CUSTOMERS\n");
        for (int i = 0; i < CUSTOMERS.size(); i++) {
            SeedCustomer c = CUSTOMERS.get(i);
            String phone = phone(i + 1);
            User u = user(c.name(), phone, hash, Role.CUSTOMER);
            u.setPreferredLang(c.lang());
            users.save(u);
            out.append(String.format("  %s  %s%n", phone, c.name()));
        }

        out.append("\nSTORE OWNERS\n");
        for (int i = 0; i < STORES.size(); i++) {
            SeedStore s = STORES.get(i);
            String phone = phone(i + 11);
            User owner = users.save(user(s.ownerName(), phone, hash, Role.STORE_OWNER));

            double[] pos = offset(TEST_LAT, TEST_LNG, s.distanceKm(), s.bearingDeg());
            Store store = new Store();
            store.setOwner(owner);
            store.setName(s.storeName());
            store.setArea(s.area());
            store.setAddress("Shop " + (i + 1) + ", Main Market, " + s.area() + ", Noida");
            store.setPhone(phone);
            store.setLat(pos[0]);
            store.setLng(pos[1]);
            store.setOpen(s.open());
            stores.save(store);
            out.append(String.format("  %s  %-26s %-11s %.1f km%s%n", phone, s.storeName(), s.area(),
                    s.distanceKm(), s.open() ? "" : "  (closed)"));
        }
        out.append("==========================================================\n");
        log.info(out.toString());
    }

    private static String phone(int n) {
        return String.format("90000000%02d", n);
    }

    private static User user(String name, String phone, String hash, Role role) {
        User u = new User();
        u.setName(name);
        u.setPhone(phone);
        u.setPasswordHash(hash);
        u.setRole(role);
        return u;
    }

    /** Point at distanceKm along bearingDeg (0 = north). Flat approximation; fine below ~10 km. */
    static double[] offset(double lat, double lng, double distanceKm, double bearingDeg) {
        double b = Math.toRadians(bearingDeg);
        double dLat = distanceKm * Math.cos(b) / KM_PER_DEG_LAT;
        double dLng = distanceKm * Math.sin(b) / (KM_PER_DEG_LAT * Math.cos(Math.toRadians(lat)));
        return new double[] {lat + dLat, lng + dLng};
    }
}
