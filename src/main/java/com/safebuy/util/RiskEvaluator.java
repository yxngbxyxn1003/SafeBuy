package com.safebuy.util;

import com.safebuy.entity.RecallProduct;

import java.util.Map;

public class RiskEvaluator {
    /* 제품과 검색어를 비교해서 위험 점수를 계산하는 메서드 */
    // 파라미터 query: 사용자가 입력하거나 AI가 확장한 검색어
    // 파라미터 product: DB에서 찾은 RecallProduct 엔티티
    // 리턴값: 위험 점수 (0~100)
    public static int calculateRiskScore(
            String productNameQuery,
            String manufacturerQuery,
            String modelNameQuery,
            RecallProduct product
    ) {
        if (product == null) return 0; // 검색 결과 없으면 점수 0

        int score = 0;

        // 모델명과 검색어 비교
        if (matchesNormalized(product.getModlNmInfo(), modelNameQuery, /*manufacturerMode*/ false)) {
            score += 40;
        }

        // 제품명과 검색어 비교
        if (matchesNormalized(product.getProductNm(), productNameQuery, /*manufacturerMode*/ false)) {
            score += 30;
        }

        // 제조사와 검색어 비교
        if (matchesNormalized(product.getMakr(), manufacturerQuery, /*manufacturerMode*/ true)) {
            score += 20;
        }

        // 결함내용 존재 여부
        if (product.getShrtcomCn() != null && !product.getShrtcomCn().isBlank()) {
            score += 10;
        }


        // 점수 상한 제한
        return Math.min(score, 100);
    }

    /* 점수를 기반으로 위험 단계를 문장열로 반환하는 메서드 */
    // 파라미터 score: 위험 점수 (0~100)
    // 리턴값: 위험 단계 (고위험/중위험/저위험/없음)
    public static String riskLevelFromScore(int score) {
        if (score >= 70) return "고위험";
        if (score >= 50) return "중위험";
        if (score >= 1) return "저위험";
        return "없음"; // 정확히 0점일 때만 해당
    }

    /* 내부 메서드 */
    // 제품 정보값과 query(사용자 입력값/AI 확장값)를 정규화 후 토큰 단위 부분 매칭
    private static boolean matchesNormalized(String target, String query, boolean manufacturerMode) {
        if (target == null || query == null || query.isBlank()) return false;

        // 필드 성격에 맞는 정규화
        final String normTarget = manufacturerMode
                ? TextNormalizer.normalizeManufacturer(target)
                : TextNormalizer.normalizeText(target);

        final String normQuery = manufacturerMode
                ? TextNormalizer.normalizeManufacturer(query)
                : TextNormalizer.normalizeText(query);

        if (normTarget == null || normTarget.isBlank() || normQuery == null || normQuery.isBlank()) return false;

        // 완전 포함 우선
        if (normTarget.contains(normQuery)) return true;

        // 토큰 단위 부분 매칭 허용 (길이 2 이상 토큰만)
        for (String token : normQuery.split("\\s+")) {
            if (token.length() >= 2 && normTarget.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
