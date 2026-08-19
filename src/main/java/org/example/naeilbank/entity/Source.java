package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Source extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(name = "doi_url", nullable = false, length = 500)
    private String doiUrl;

    @Column(name = "summary_ko", columnDefinition = "TEXT")
    private String summaryKo;

    @Column(name = "limitations_ko", columnDefinition = "TEXT")
    private String limitationsKo;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;
}