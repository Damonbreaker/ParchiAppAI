package com.kirana.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Slice;

/** One page of a list: {items, page, hasMore}. page is 0-based; hasMore = another page exists. */
public record PageDto<T>(List<T> items, int page, boolean hasMore) {

    /** Largest page size the API accepts. */
    public static final int MAX_SIZE = 50;

    public static <T> PageDto<T> of(Slice<?> slice, List<T> items) {
        return new PageDto<>(items, slice.getNumber(), slice.hasNext());
    }

    public static <E, T> PageDto<T> map(Slice<E> slice, Function<E, T> mapper) {
        return of(slice, slice.getContent().stream().map(mapper).toList());
    }
}
