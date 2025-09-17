package com.safebuy.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "recall_products")
@Data
public class RecallProduct {
    @Id
    @Column(name = "recall_sn")
    private String recallSn;            // 리콜번호 (PK)

    @Column(name = "product_nm", columnDefinition = "TEXT")
    private String productNm;           // 제품명

    @Column(name = "bsnm_nm", length = 500)
    private String bsnmNm;              // 사업자명

    @Column(name = "makr", length = 500)
    private String makr;                // 제조사

    @Column(name = "modl_nm_info", columnDefinition = "TEXT")
    private String modlNmInfo;          // 모델명

    @Column(name = "recall_publict_bgnde", length = 50)
    private String recallPublictBgnde;  // 리콜 공표시작일

    @Column(name = "shrtcom_cn", columnDefinition = "LONGTEXT")
    private String shrtcomCn;           // 결함내용
}
