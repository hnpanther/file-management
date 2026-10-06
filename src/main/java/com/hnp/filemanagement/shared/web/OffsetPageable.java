package com.hnp.filemanagement.shared.web;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A slice of rows from any offset, where a {@code PageRequest} starts only on a page boundary - for
 * a list made of two queries' rows one after the other (the tree page's level, roadmap 12.4: the
 * folders, then the files), whose second part begins wherever the first ended.
 */
public final class OffsetPageable implements Pageable {

    private final long offset;
    private final int limit;
    private final Sort sort;

    public OffsetPageable(long offset, int limit, Sort sort) {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset " + offset + " and limit " + limit);
        }
        this.offset = offset;
        this.limit = limit;
        this.sort = sort == null ? Sort.unsorted() : sort;
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageable(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return new OffsetPageable(Math.max(0, offset - limit), limit, sort);
    }

    @Override
    public Pageable first() {
        return new OffsetPageable(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageable((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
