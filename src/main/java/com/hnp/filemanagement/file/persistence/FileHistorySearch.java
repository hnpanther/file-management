package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.file.domain.FileHistory;
import com.hnp.filemanagement.shared.util.SearchTerms;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * A page of the file history, filtered (2.5.0) - built for what was asked and nothing else.
 *
 * <p>A JPQL query with every filter optional ({@code :event IS NULL OR h.event = :event}) would be
 * one statement for every combination, and PostgreSQL would plan it for none in particular: the
 * name's trigram index would go unused behind an {@code OR}. Built here, the statement names only
 * the conditions that were given, newest first, and reads one row past the page to know whether
 * there is another - never a count of the whole history, which only grows.
 *
 * <p><b>A name is planned with its value.</b> After a statement has run a few times PostgreSQL may
 * keep one plan for every value ({@code plan_cache_mode = auto}), and for a {@code LIKE} it cannot
 * see it assumes many matches: it walks the time index and filters, which for a name found in a
 * few rows reads the whole history - 0.8 s on a million events, against 6 ms planned for the name
 * at hand. So a search by name asks for a plan of its own, for this transaction only
 * ({@code SET LOCAL}); the other filters are equalities and ranges, which an index serves whatever
 * the value.
 */
@Repository
public class FileHistorySearch {

    private final EntityManager entityManager;

    public FileHistorySearch(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Slice<FileHistory> find(FileHistoryQuery query, Pageable pageable) {
        if (query.folderIds() != null && query.folderIds().isEmpty()) {
            return new SliceImpl<>(List.of(), pageable, false);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<FileHistory> criteria = cb.createQuery(FileHistory.class);
        Root<FileHistory> history = criteria.from(FileHistory.class);
        history.fetch("apiKey", JoinType.LEFT);

        List<Predicate> where = new ArrayList<>();
        if (query.nameKey() != null && !query.nameKey().isEmpty()) {
            entityManager.createNativeQuery("SET LOCAL plan_cache_mode = force_custom_plan").executeUpdate();
            where.add(cb.like(history.get("searchName"), "%" + query.nameKey() + "%", SearchTerms.LIKE_ESCAPE));
        }
        if (query.event() != null) {
            where.add(cb.equal(history.get("event"), query.event()));
        }
        if (query.from() != null) {
            where.add(cb.greaterThanOrEqualTo(history.get("occurredAt"), query.from()));
        }
        if (query.until() != null) {
            where.add(cb.lessThan(history.get("occurredAt"), query.until()));
        }
        if (query.fileExternalId() != null) {
            where.add(cb.equal(history.get("fileExternalId"), query.fileExternalId()));
        }
        if (query.apiKeyId() != null) {
            where.add(cb.equal(history.get("apiKey").get("id"), query.apiKeyId()));
        }
        if (query.folderIds() != null) {
            where.add(history.get("folderId").in(query.folderIds()));
        }

        criteria.select(history)
                .where(where.toArray(Predicate[]::new))
                .orderBy(cb.desc(history.get("occurredAt")), cb.desc(history.get("id")));

        List<FileHistory> rows = entityManager.createQuery(criteria)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize() + 1)
                .getResultList();
        boolean more = rows.size() > pageable.getPageSize();
        return new SliceImpl<>(more ? rows.subList(0, pageable.getPageSize()) : rows, pageable, more);
    }
}
