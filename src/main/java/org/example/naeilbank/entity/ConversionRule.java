package org.example.naeilbank.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Table(name = "conversion_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversionRule extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // Long (BIGINT)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source; // Source 엔티티 참조 (FK)

    @Column(nullable = false)
    private String category; // String (VARCHAR)

    @Column(name = "bmj_coefficient", nullable = false)
    private BigDecimal bmjCoefficient; // BigDecimal (NUMERIC / DECIMAL)

    private String description; // String (VARCHAR)
}