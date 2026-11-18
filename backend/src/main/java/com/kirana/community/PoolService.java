package com.kirana.community;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.common.ApiException;
import com.kirana.community.dto.CreatePoolRequest;
import com.kirana.community.dto.PoolBagDto;
import com.kirana.community.dto.PoolDto;
import com.kirana.community.dto.SetPoolListRequest;
import com.kirana.community.dto.ShopBagDto;
import com.kirana.request.OrderRequest;
import com.kirana.request.OrderRequestRepository;
import com.kirana.request.RequestMode;
import com.kirana.request.RequestService;
import com.kirana.request.dto.CreateRequestRequest;
import com.kirana.request.dto.ItemInput;
import com.kirana.request.dto.RequestDetailDto;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PoolService {

    private static final int DEFAULT_WINDOW = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderPoolRepository pools;
    private final CommunityRepository communities;
    private final CommunityService communityService;
    private final UserRepository users;
    private final RequestService requestService;
    private final OrderRequestRepository orderRequests;

    @Transactional
    public PoolDto create(Long hostUserId, CreatePoolRequest req) {
        Community community = communities.findById(req.communityId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMMUNITY_NOT_FOUND", "Society/tower not found"));
        CommunityMember membership = communityService.requireMembership(community.getId(), hostUserId);
        User host = users.findById(hostUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please log in"));

        int window = req.windowMinutes() != null ? req.windowMinutes() : DEFAULT_WINDOW;
        Instant now = Instant.now();
        OrderPool pool = new OrderPool();
        pool.setToken(newToken());
        pool.setCommunity(community);
        pool.setHost(host);
        pool.setTitle(req.title() != null && !req.title().isBlank()
                ? req.title().trim()
                : community.getLabel() + " · delivery pool");
        pool.setStatus(PoolStatus.OPEN);
        pool.setWindowMinutes(window);
        pool.setOpensAt(now);
        pool.setClosesAt(now.plus(Duration.ofMinutes(window)));

        PoolMember hostMember = new PoolMember();
        hostMember.setUser(host);
        hostMember.setAlias(membership.getAlias());
        hostMember.setStatus(PoolMemberStatus.JOINED);
        pool.addMember(hostMember);
        pools.save(pool);
        return toDto(pool, hostUserId);
    }

    @Transactional
    public PoolDto get(String token, Long userId) {
        OrderPool pool = load(token);
        refreshExpiry(pool);
        assertCanView(pool, userId);
        return toDto(pool, userId);
    }

    @Transactional
    public PoolDto join(String token, Long userId) {
        OrderPool pool = load(token);
        refreshExpiry(pool);
        if (pool.getStatus() != PoolStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "POOL_CLOSED", "This pool is no longer accepting neighbours");
        }
        CommunityMember membership = communityService.requireMembership(pool.getCommunity().getId(), userId);
        boolean already = pool.getMembers().stream().anyMatch(m -> m.getUser().getId().equals(userId));
        if (!already) {
            User user = users.findById(userId)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please log in"));
            PoolMember member = new PoolMember();
            member.setUser(user);
            member.setAlias(membership.getAlias());
            member.setStatus(PoolMemberStatus.JOINED);
            pool.addMember(member);
            pools.save(pool);
        }
        return toDto(pool, userId);
    }

    @Transactional
    public PoolDto setMyList(String token, Long userId, SetPoolListRequest req) {
        OrderPool pool = load(token);
        refreshExpiry(pool);
        if (pool.getStatus() != PoolStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "POOL_CLOSED", "Window closed — ask the host to send what's ready");
        }
        communityService.requireMembership(pool.getCommunity().getId(), userId);
        PoolMember member = pool.getMembers().stream()
                .filter(m -> m.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_IN_POOL", "Join this pool first"));

        member.clearItems();
        int pos = 0;
        for (ItemInput in : req.items()) {
            PoolMemberItem item = new PoolMemberItem();
            item.setName(in.name().trim());
            item.setQuantity(in.quantity());
            item.setUnit(in.unit());
            item.setPosition(pos++);
            member.addItem(item);
        }
        member.setFromPhoto(Boolean.TRUE.equals(req.fromPhoto()));
        member.setStatus(PoolMemberStatus.LIST_READY);
        pools.save(pool);
        return toDto(pool, userId);
    }

    @Transactional
    public PoolDto closeWindow(String token, Long hostUserId) {
        OrderPool pool = loadOwned(token, hostUserId);
        refreshExpiry(pool);
        if (pool.getStatus() != PoolStatus.OPEN && pool.getStatus() != PoolStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "POOL_NOT_OPEN", "Pool already moved on");
        }
        pool.setStatus(PoolStatus.CLOSED);
        pool.setClosesAt(Instant.now());
        pools.save(pool);
        return toDto(pool, hostUserId);
    }

    /** Host sends the cluster to nearby shops as one order request; bags stay organised for packing. */
    @Transactional
    public PoolDto sendToShops(String token, Long hostUserId) {
        OrderPool pool = loadOwned(token, hostUserId);
        refreshExpiry(pool);
        if (pool.getStatus() != PoolStatus.OPEN && pool.getStatus() != PoolStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "POOL_NOT_READY", "Pool cannot be sent in status " + pool.getStatus());
        }
        List<PoolMember> ready = pool.getMembers().stream()
                .filter(m -> m.getStatus() == PoolMemberStatus.LIST_READY && !m.getItems().isEmpty())
                .toList();
        if (ready.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_LISTS", "Need at least one neighbour list before sending");
        }
        pool.setStatus(PoolStatus.CLOSED);

        List<ItemInput> flat = new ArrayList<>();
        boolean anyPhoto = false;
        for (PoolMember m : ready) {
            anyPhoto |= m.isFromPhoto();
            for (PoolMemberItem item : m.getItems()) {
                // Prefix keeps bags separable if a shop only sees the flat quote list.
                String labeled = trimName("[" + m.getAlias() + "] " + item.getName());
                flat.add(new ItemInput(labeled, item.getQuantity(), item.getUnit()));
            }
        }
        Community community = pool.getCommunity();
        RequestDetailDto created = requestService.create(hostUserId, new CreateRequestRequest(
                flat,
                community.getLat(),
                community.getLng(),
                RequestMode.NEAREST,
                null,
                anyPhoto));
        OrderRequest order = orderRequests.findById(created.id())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REQUEST_MISSING",
                        "Cluster order was created but not found"));
        pool.setOrderRequest(order);
        pool.setStatus(PoolStatus.QUOTING);
        pools.save(pool);
        return toDto(pool, hostUserId);
    }

    @Transactional
    public void onRequestAccepted(Long orderRequestId) {
        pools.findByOrderRequestId(orderRequestId).ifPresent(pool -> {
            for (PoolMember m : pool.getMembers()) {
                if (m.getStatus() == PoolMemberStatus.LIST_READY && !m.getItems().isEmpty()) {
                    m.setPickupCode(newPickupCode());
                    // OTP hidden until customer approves their bag.
                }
            }
            pool.setStatus(PoolStatus.ACCEPTED);
            pools.save(pool);
        });
    }

    @Transactional
    public void onRequestReady(Long orderRequestId) {
        pools.findByOrderRequestId(orderRequestId).ifPresent(pool -> {
            pool.setStatus(PoolStatus.READY);
            pools.save(pool);
        });
    }

    /** Customer reviews their packed list; only then is OTP revealed. */
    @Transactional
    public PoolDto approveMyBag(String token, Long userId) {
        OrderPool pool = load(token);
        assertCanView(pool, userId);
        if (pool.getStatus() != PoolStatus.ACCEPTED && pool.getStatus() != PoolStatus.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_YET", "Wait until a shop is chosen and packing starts");
        }
        PoolMember member = requireMember(pool, userId);
        if (member.getItems().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_ITEMS", "You have no items in this pool");
        }
        if (member.getPickupCode() == null) {
            member.setPickupCode(newPickupCode());
        }
        member.setApprovedAt(Instant.now());
        member.setStatus(PoolMemberStatus.APPROVED);
        pools.save(pool);
        return toDto(pool, userId);
    }

    @Transactional
    public PoolDto deliverBag(Long orderRequestId, Long storeOwnerId, Long memberId, String pickupCode) {
        OrderPool pool = pools.findByOrderRequestId(orderRequestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_A_POOL", "Not a tower pool order"));
        // Store ownership validated by caller via OrderService/RequestService.
        PoolMember member = pool.getMembers().stream()
                .filter(m -> m.getId().equals(memberId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BAG_NOT_FOUND", "Bag not found"));
        if (member.getApprovedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "BAG_NOT_APPROVED",
                    "Neighbour has not approved their list / shared OTP yet");
        }
        if (member.getPickupCode() == null || !member.getPickupCode().equals(pickupCode == null ? "" : pickupCode.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_CODE", "Pickup code does not match");
        }
        member.setDeliveredAt(Instant.now());
        member.setStatus(PoolMemberStatus.DELIVERED);
        boolean allDone = pool.getMembers().stream()
                .filter(m -> m.getStatus() == PoolMemberStatus.LIST_READY
                        || m.getStatus() == PoolMemberStatus.APPROVED
                        || m.getStatus() == PoolMemberStatus.DELIVERED)
                .filter(m -> !m.getItems().isEmpty())
                .allMatch(m -> m.getDeliveredAt() != null);
        if (allDone) {
            pool.setStatus(PoolStatus.DONE);
        }
        pools.save(pool);
        return toDto(pool, null);
    }

    @Transactional(readOnly = true)
    public List<ShopBagDto> shopBags(Long orderRequestId) {
        return pools.findByOrderRequestId(orderRequestId)
                .map(pool -> pool.getMembers().stream()
                        .filter(m -> !m.getItems().isEmpty())
                        .map(ShopBagDto::from)
                        .toList())
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public boolean isPoolRequest(Long orderRequestId) {
        return pools.findByOrderRequestId(orderRequestId).isPresent();
    }

    private OrderPool load(String token) {
        return pools.findByToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "POOL_NOT_FOUND", "Delivery pool not found"));
    }

    private OrderPool loadOwned(String token, Long hostUserId) {
        OrderPool pool = load(token);
        if (!Objects.equals(pool.getHost().getId(), hostUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the pool host can do that");
        }
        return pool;
    }

    private void assertCanView(OrderPool pool, Long userId) {
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please log in");
        }
        if (!communityService.isMember(pool.getCommunity().getId(), userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER",
                    "Only residents of " + pool.getCommunity().getLabel() + " can open this pool link");
        }
    }

    private PoolMember requireMember(OrderPool pool, Long userId) {
        return pool.getMembers().stream()
                .filter(m -> m.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_IN_POOL", "Join this pool first"));
    }

    private void refreshExpiry(OrderPool pool) {
        if (pool.getStatus() == PoolStatus.OPEN && Instant.now().isAfter(pool.getClosesAt())) {
            pool.setStatus(PoolStatus.CLOSED);
            pools.save(pool);
        }
    }

    private PoolDto toDto(OrderPool pool, Long viewerUserId) {
        boolean host = viewerUserId != null && Objects.equals(pool.getHost().getId(), viewerUserId);
        boolean member = viewerUserId != null && pool.getMembers().stream()
                .anyMatch(m -> m.getUser().getId().equals(viewerUserId));
        String myAlias = pool.getMembers().stream()
                .filter(m -> viewerUserId != null && m.getUser().getId().equals(viewerUserId))
                .map(PoolMember::getAlias)
                .findFirst()
                .orElse(null);
        long secondsLeft = Math.max(0, Duration.between(Instant.now(), pool.getClosesAt()).getSeconds());
        if (pool.getStatus() != PoolStatus.OPEN) {
            secondsLeft = 0;
        }
        int listsReady = (int) pool.getMembers().stream()
                .filter(m -> m.getStatus() == PoolMemberStatus.LIST_READY
                        || m.getStatus() == PoolMemberStatus.APPROVED
                        || m.getStatus() == PoolMemberStatus.DELIVERED)
                .filter(m -> !m.getItems().isEmpty())
                .count();
        List<PoolBagDto> bags = pool.getMembers().stream()
                .filter(m -> !m.getItems().isEmpty() || (viewerUserId != null && m.getUser().getId().equals(viewerUserId)))
                .map(m -> PoolBagDto.from(m, viewerUserId, pool.getHost().getId(), true))
                .toList();
        Long requestId = pool.getOrderRequest() != null ? pool.getOrderRequest().getId() : null;
        return new PoolDto(
                pool.getToken(),
                pool.getTitle(),
                pool.getStatus(),
                pool.getCommunity().getLabel(),
                pool.getCommunity().getCode(),
                pool.getWindowMinutes(),
                pool.getOpensAt(),
                pool.getClosesAt(),
                secondsLeft,
                host,
                member,
                myAlias,
                pool.getMembers().size(),
                listsReady,
                requestId,
                bags);
    }

    private static String newToken() {
        byte[] buf = new byte[12];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String newPickupCode() {
        return String.format("%04d", RANDOM.nextInt(10000));
    }

    private static String trimName(String name) {
        return name.length() <= 60 ? name : name.substring(0, 60);
    }
}
