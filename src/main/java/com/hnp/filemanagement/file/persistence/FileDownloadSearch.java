package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.file.domain.FileDownload;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * A page of download records, filtered (2.7.0) - built for the filters given, as
 * {@link FileHistorySearch} is: the statement names only the conditions asked for, newest first,
 * and reads one row past the page to know whether there is another. Never a count: the table only
 * grows. Every filter is an equality or a range an index of {@code V3.6} serves.
 */
@Repository
public class FileDownloadSearch {

    private final EntityManager entityManager;

    public FileDownloadSearch(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Slice<FileDownload> find(FileDownloadQuery query, Pageable pageable) {
        if (query.readScope() != null && query.readScope().nothing()) {
            return new SliceImpl<>(List.of(), pageable, false);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<FileDownload> criteria = cb.createQuery(FileDownload.class);
        Root<FileDownload> download = criteria.from(FileDownload.class);

        List<Predicate> where = new ArrayList<>();
        if (query.fileInfoId() != null) {
            where.add(cb.equal(download.get("fileInfoId"), query.fileInfoId()));
        }
        if (query.userId() != null) {
            where.add(cb.equal(download.get("userId"), query.userId()));
        }
        if (query.apiKeyId() != null) {
            where.add(cb.equal(download.get("apiKeyId"), query.apiKeyId()));
        }
        if (query.clientIp() != null) {
            where.add(cb.equal(download.get("clientIp"), query.clientIp()));
        }
        if (query.channel() != null) {
            where.add(cb.equal(download.get("channel"), query.channel()));
        }
        if (query.from() != null) {
            where.add(cb.greaterThanOrEqualTo(download.get("occurredAt"), query.from()));
        }
        if (query.until() != null) {
            where.add(cb.lessThan(download.get("occurredAt"), query.until()));
        }
        if (query.readScope() != null && !query.readScope().unrestricted()) {
            where.add(ReadableFolders.contains(cb, criteria, download.get("folderId"), query.readScope()));
        }

        criteria.select(download)
                .where(where.toArray(Predicate[]::new))
                .orderBy(cb.desc(download.get("occurredAt")), cb.desc(download.get("id")));

        List<FileDownload> rows = entityManager.createQuery(criteria)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize() + 1)
                .getResultList();
        boolean more = rows.size() > pageable.getPageSize();
        return new SliceImpl<>(more ? rows.subList(0, pageable.getPageSize()) : rows, pageable, more);
    }
}
