package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "plan")
public class Plan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    @Column(name = "quota_amount")
    private Integer quotaAmount;
    @Column(name = "price_cents")
    private Integer priceCents;
    private String description;
}
