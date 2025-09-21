package com.safebuy.util;

import com.safebuy.entity.RecallProduct;

import java.util.Map;

public class RiskEvaluator {
    /* 제품과 검색어를 비교해서 위험 점수를 계산하는 메서드 */
    // 파라미터 query: 사용자가 입력하거나 AI가 확장한 검색어
    // 파라미터 product: DB에서 찾은 RecallProduct 엔티티
    // 리턴값: 위험 점수 (0~100)
    public static int calculateRiskScore(String query, RecallProduct product) {
        if (product == null) return 0; // 검색 결과 없으면 점수 0

        int score = 0;

        // 모델명과 검색어 비교
        if (product.getModlNmInfo() != null && product.getModlNmInfo().toLowerCase().contains(query.toLowerCase())) {
            score += 50;
        }

        // 제품명과 검색어 비교
        if (product.getProductNm() != null &&
                product.getProductNm().toLowerCase().contains(query.toLowerCase())) {
            score += 20;
        }

        // 제조사와 검색어 비교
        if (product.getMakr() != null && product.getMakr().toLowerCase().contains(query.toLowerCase())) {
            score += 20;
        }

        // 결함내용이 존재하면 가중치 추가
        if (product.getShrtcomCn() != null && !product.getShrtcomCn().isBlank()) {
            score += 10;
        }

        // 100점이 넘지 않도록 제한
        return Math.min(score, 100);
    }

    /* 점수를 기반으로 위험 단계를 문장열로 반환하는 메서드 */
    // 파라미터 score: 위험 점수 (0~100)
    // 리턴값: 위험 단계 (고위험/중위험/저위험/없음)
    public static String riskLevelFromScore(int score) {
        if (score >= 70) return "고위험";
        if (score >= 30) return "중위험";
        if (score >= 1) return "저위험";
        return "없음"; // 정확히 0점일 때만 해당
    }
}
