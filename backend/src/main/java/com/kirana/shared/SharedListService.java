package com.kirana.shared;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.common.ApiException;
import com.kirana.common.RateLimiter;
import com.kirana.common.Unit;
import com.kirana.config.AuthUser;
import com.kirana.request.dto.ItemInput;
import com.kirana.shared.dto.AddSharedItemRequest;
import com.kirana.shared.dto.CreateSharedListRequest;
import com.kirana.shared.dto.SharedListDto;
import com.kirana.shared.dto.SharedListItemDto;
import com.kirana.shared.dto.UpdateSharedItemRequest;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SharedListService {

    private static final Duration TTL = Duration.ofHours(2);
    private static final int MAX_ITEMS = 50;
    private static final Duration MUTATE_WINDOW = Duration.ofMinutes(1);
    private static final int MUTATE_LIMIT = 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SharedListRepository lists;
    private final SharedListItemRepository items;
    private final UserRepository users;
    private final RateLimiter rateLimiter;

    @Transactional
    public SharedListDto create(Long ownerId, CreateSharedListRequest req) {
        User owner = users.findById(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Please log in"));
        SharedList list = new SharedList();
        list.setToken(newToken());
        list.setOwner(owner);
        list.setTitle(cleanTitle(req != null ? req.title() : null, owner.getName()));
        list.setStatus(SharedListStatus.OPEN);
        list.setExpiresAt(Instant.now().plus(TTL));

        if (req != null && req.items() != null) {
            int pos = 0;
            for (ItemInput in : req.items()) {
                if (pos >= MAX_ITEMS) {
                    break;
                }
                SharedListItem item = newItem(in.name(), in.quantity(), in.unit(), pos++, firstName(owner.getName()), null, owner);
                list.addItem(item);
            }
        }
        lists.save(list);
        return toDto(list, ownerId, null);
    }

    @Transactional
    public SharedListDto get(String token, AuthUser me, String guestKey) {
        SharedList list = load(token);
        expireIfNeeded(list);
        return toDto(list, me != null ? me.id() : null, guestKey);
    }

    @Transactional
    public SharedListDto addItem(String token, AuthUser me, AddSharedItemRequest req) {
        SharedList list = loadOpen(token);
        rateLimit(token, me, req.guestKey());
        if (list.getItems().size() >= MAX_ITEMS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LIST_FULL", "This list already has 50 items");
        }
        String guestKey = cleanGuestKey(req.guestKey());
        User user = me != null ? users.findById(me.id()).orElse(null) : null;
        String label = resolveLabel(user, req.guestName());
        int pos = list.getItems().isEmpty() ? 0
                : list.getItems().stream().mapToInt(SharedListItem::getPosition).max().orElse(-1) + 1;
        SharedListItem item = newItem(req.name(), req.quantity(), req.unit(), pos, label, guestKey, user);
        list.addItem(item);
        list.setExpiresAt(Instant.now().plus(TTL));
        lists.save(list);
        return toDto(list, me != null ? me.id() : null, guestKey);
    }

    @Transactional
    public SharedListDto updateItem(String token, Long itemId, AuthUser me, UpdateSharedItemRequest req) {
        SharedList list = loadOpen(token);
        rateLimit(token, me, req.guestKey());
        SharedListItem item = findItem(list, itemId);
        assertCanEdit(list, item, me, req.guestKey());
        item.setName(req.name().trim());
        item.setQuantity(req.quantity());
        item.setUnit(req.unit());
        list.setExpiresAt(Instant.now().plus(TTL));
        lists.save(list);
        return toDto(list, me != null ? me.id() : null, cleanGuestKey(req.guestKey()));
    }

    @Transactional
    public SharedListDto deleteItem(String token, Long itemId, AuthUser me, String guestKey) {
        SharedList list = loadOpen(token);
        rateLimit(token, me, guestKey);
        SharedListItem item = findItem(list, itemId);
        assertCanEdit(list, item, me, guestKey);
        list.getItems().remove(item);
        items.delete(item);
        list.setExpiresAt(Instant.now().plus(TTL));
        lists.save(list);
        return toDto(list, me != null ? me.id() : null, cleanGuestKey(guestKey));
    }

    @Transactional
    public SharedListDto lock(String token, Long ownerId) {
        SharedList list = loadOwned(token, ownerId);
        expireIfNeeded(list);
        if (list.getStatus() == SharedListStatus.PLACED) {
            throw new ApiException(HttpStatus.CONFLICT, "LIST_PLACED", "This list was already sent to shops");
        }
        if (list.getStatus() == SharedListStatus.EXPIRED) {
            throw new ApiException(HttpStatus.GONE, "LIST_EXPIRED", "This group list has expired");
        }
        list.setStatus(SharedListStatus.LOCKED);
        lists.save(list);
        return toDto(list, ownerId, null);
    }

    @Transactional
    public SharedListDto unlock(String token, Long ownerId) {
        SharedList list = loadOwned(token, ownerId);
        if (list.getStatus() == SharedListStatus.PLACED) {
            throw new ApiException(HttpStatus.CONFLICT, "LIST_PLACED", "This list was already sent to shops");
        }
        expireIfNeeded(list);
        if (list.getStatus() == SharedListStatus.EXPIRED) {
            throw new ApiException(HttpStatus.GONE, "LIST_EXPIRED", "This group list has expired");
        }
        list.setStatus(SharedListStatus.OPEN);
        list.setExpiresAt(Instant.now().plus(TTL));
        lists.save(list);
        return toDto(list, ownerId, null);
    }

    /** Owner locks (if needed) and marks ready to place; front-end copies items into draft. */
    @Transactional
    public SharedListDto preparePlace(String token, Long ownerId) {
        SharedList list = loadOwned(token, ownerId);
        expireIfNeeded(list);
        if (list.getStatus() == SharedListStatus.EXPIRED) {
            throw new ApiException(HttpStatus.GONE, "LIST_EXPIRED", "This group list has expired");
        }
        if (list.getStatus() == SharedListStatus.PLACED) {
            throw new ApiException(HttpStatus.CONFLICT, "LIST_PLACED", "This list was already sent to shops");
        }
        if (list.getItems().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_ITEMS", "Add at least one item before sending");
        }
        list.setStatus(SharedListStatus.LOCKED);
        lists.save(list);
        return toDto(list, ownerId, null);
    }

    @Transactional
    public SharedListDto markPlaced(String token, Long ownerId) {
        SharedList list = loadOwned(token, ownerId);
        list.setStatus(SharedListStatus.PLACED);
        lists.save(list);
        return toDto(list, ownerId, null);
    }

    private SharedList load(String token) {
        return lists.findByToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LIST_NOT_FOUND", "Group list not found"));
    }

    private SharedList loadOpen(String token) {
        SharedList list = load(token);
        expireIfNeeded(list);
        if (list.getStatus() == SharedListStatus.EXPIRED) {
            throw new ApiException(HttpStatus.GONE, "LIST_EXPIRED", "This group list has expired");
        }
        if (list.getStatus() == SharedListStatus.LOCKED || list.getStatus() == SharedListStatus.PLACED) {
            throw new ApiException(HttpStatus.CONFLICT, "LIST_LOCKED", "Host locked this list — wait or ask them to reopen");
        }
        return list;
    }

    private SharedList loadOwned(String token, Long ownerId) {
        SharedList list = load(token);
        if (!Objects.equals(list.getOwner().getId(), ownerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the host can do that");
        }
        return list;
    }

    private void expireIfNeeded(SharedList list) {
        if (list.getStatus() == SharedListStatus.OPEN && Instant.now().isAfter(list.getExpiresAt())) {
            list.setStatus(SharedListStatus.EXPIRED);
            lists.save(list);
        }
    }

    private void rateLimit(String token, AuthUser me, String guestKey) {
        String who = me != null ? "u" + me.id() : "g" + (cleanGuestKey(guestKey) != null ? cleanGuestKey(guestKey) : "anon");
        String key = "shared:" + token + ":" + who;
        rateLimiter.check(key, MUTATE_LIMIT, MUTATE_WINDOW, "Too many edits — wait a moment");
        rateLimiter.record(key, MUTATE_WINDOW);
    }

    private SharedListItem findItem(SharedList list, Long itemId) {
        return list.getItems().stream()
                .filter(i -> Objects.equals(i.getId(), itemId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Item not found"));
    }

    private void assertCanEdit(SharedList list, SharedListItem item, AuthUser me, String guestKey) {
        if (me != null && Objects.equals(list.getOwner().getId(), me.id())) {
            return;
        }
        if (me != null && item.getAddedByUser() != null && Objects.equals(item.getAddedByUser().getId(), me.id())) {
            return;
        }
        String key = cleanGuestKey(guestKey);
        if (key != null && key.equals(item.getGuestKey())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You can only edit items you added");
    }

    private SharedListItem newItem(String name, BigDecimal qty, Unit unit, int pos, String label, String guestKey,
            User user) {
        SharedListItem item = new SharedListItem();
        item.setName(name.trim());
        item.setQuantity(qty);
        item.setUnit(unit);
        item.setPosition(pos);
        item.setAddedByLabel(label);
        item.setGuestKey(guestKey);
        item.setAddedByUser(user);
        return item;
    }

    private SharedListDto toDto(SharedList list, Long viewerUserId, String guestKey) {
        boolean owner = viewerUserId != null && Objects.equals(list.getOwner().getId(), viewerUserId);
        String gKey = cleanGuestKey(guestKey);
        List<SharedListItemDto> itemDtos = list.getItems().stream()
                .map(i -> new SharedListItemDto(
                        i.getId(),
                        i.getName(),
                        i.getQuantity(),
                        i.getUnit(),
                        i.getPosition(),
                        i.getAddedByLabel(),
                        isMine(i, viewerUserId, gKey, owner)))
                .toList();
        return new SharedListDto(
                list.getToken(),
                list.getTitle(),
                list.getStatus(),
                firstName(list.getOwner().getName()),
                owner,
                list.getExpiresAt(),
                list.getUpdatedAt(),
                list.getVersion() == null ? 0L : list.getVersion(),
                itemDtos.size(),
                itemDtos);
    }

    private static boolean isMine(SharedListItem item, Long viewerUserId, String guestKey, boolean owner) {
        if (owner) {
            return true;
        }
        if (viewerUserId != null && item.getAddedByUser() != null
                && Objects.equals(item.getAddedByUser().getId(), viewerUserId)) {
            return true;
        }
        return guestKey != null && guestKey.equals(item.getGuestKey());
    }

    private static String resolveLabel(User user, String guestName) {
        if (user != null && user.getName() != null && !user.getName().isBlank()) {
            return firstName(user.getName());
        }
        if (guestName != null && !guestName.isBlank()) {
            return trimTo(guestName.trim(), 40);
        }
        return "Friend";
    }

    private static String firstName(String name) {
        String n = name == null ? "Host" : name.trim();
        int sp = n.indexOf(' ');
        return trimTo(sp > 0 ? n.substring(0, sp) : n, 40);
    }

    private static String cleanTitle(String title, String ownerName) {
        if (title != null && !title.isBlank()) {
            return trimTo(title.trim(), 80);
        }
        return firstName(ownerName) + "'s group list";
    }

    private static String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String cleanGuestKey(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim();
        if (k.isEmpty() || k.length() > 64) {
            return null;
        }
        return k;
    }

    private static String newToken() {
        byte[] buf = new byte[12];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
