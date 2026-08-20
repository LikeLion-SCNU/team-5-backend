package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "logical_key", nullable = false, updatable = false)
    private UUID logicalKey;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(nullable = false)
    private String title;

    @Column(name = "authors")
    private String authors;

    @Column(name = "journal")
    private String journal;

    @Column(name = "pub_year")
    private Integer publicationYear;

    @Column(name = "doi_url", length = 500)
    private String doiUrl;

    @Column(name = "summary_ko", columnDefinition = "TEXT")
    private String summaryKo;

    @Column(name = "scope_ko", columnDefinition = "TEXT")
    private String scopeKo;

    @Column(name = "limitations_ko", columnDefinition = "TEXT")
    private String limitationsKo;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public static Source create(
            String title,
            String authors,
            String journal,
            Integer publicationYear,
            String doiUrl,
            String summaryKo,
            String scopeKo,
            String limitationsKo,
            boolean active,
            Instant now
    ) {
        return version(null, UUID.randomUUID(), 1, title, authors, journal, publicationYear,
                doiUrl, summaryKo, scopeKo, limitationsKo, active, now);
    }

    public static Source nextVersion(
            Source previous,
            int versionNumber,
            String title,
            String authors,
            String journal,
            Integer publicationYear,
            String doiUrl,
            String summaryKo,
            String scopeKo,
            String limitationsKo,
            boolean active,
            Instant now
    ) {
        return version(previous, previous.logicalKey, versionNumber, title, authors, journal,
                publicationYear, doiUrl, summaryKo, scopeKo, limitationsKo, active, now);
    }

    public void setActive(boolean active, Instant now) {
        this.active = active;
        this.updatedAt = now;
    }

    public void markVersioned(Instant now) {
        this.updatedAt = now;
    }

    public long resourceVersion() {
        return rowVersion + 1L;
    }

    private static Source version(
            Source previous,
            UUID logicalKey,
            int versionNumber,
            String title,
            String authors,
            String journal,
            Integer publicationYear,
            String doiUrl,
            String summaryKo,
            String scopeKo,
            String limitationsKo,
            boolean active,
            Instant now
    ) {
        Source source = new Source();
        source.logicalKey = logicalKey;
        source.versionNumber = versionNumber;
        source.title = title;
        source.authors = authors;
        source.journal = journal;
        source.publicationYear = publicationYear;
        source.doiUrl = doiUrl;
        source.summaryKo = summaryKo;
        source.scopeKo = scopeKo;
        source.limitationsKo = limitationsKo;
        source.active = active;
        source.createdAt = now;
        source.updatedAt = now;
        return source;
    }
}
