package com.safebuy.util;

import java.util.Locale;

public class TextNormalizer {
    private TextNormalizer() {}

    // 영문 회사명 패턴 목록
    private static final String[] EN_COMPANY_SUFFIXES = {
            "inc\\.?","ltd\\.?","co\\.?","corp\\.?",
            "corporation","company","limited","llc","plc","pte","gmbh",
            "s\\.a\\.?","s\\.?r\\.?l\\.?","srl","bv","ag","kg","oy","sa","kk"
    };

    // 한국어 회사명 패턴
    private static final String[] KO_PREFIX_SUFFIX = {
            "주식회사","유한회사","유한책임회사","합자회사","합명회사",
            "(주)","㈜","(유)","(유한)","(합자)","(합명)"
    };

    // 제조사 정규화 메서드
    // 파라미터 s: 원본 문자열
    // 리턴값: 비교영 정규화 문자열
    public static String normalizeManufacturer(String s) {
        if (s == null) return null;

        // lower-case & trim
        String t = s.trim().toLowerCase(Locale.ROOT);

        // 한국어 회사형태 제거
        for (String kw: KO_PREFIX_SUFFIX) {
            t = t.replace(kw.toLowerCase(Locale.ROOT), " ");
        }

        // 영문 회사형태 제거
        for (String suf : EN_COMPANY_SUFFIXES) {
            // 단어 경계 앞뒤로 등장하는 패턴을 공백으로 치환
            t = t.replaceAll("\\b" + suf + "\\b", " ");
        }

        // 기호, 특수문자 정리
        // - 다양한 하이픈 통일 → '-' 로 바꾼 뒤
        t = t.replaceAll("[·•‐-‒–—―]", "-");
        // - 영숫자/한글/공백/&/- 만 남기고 나머지는 공백
        t = t.replaceAll("[^0-9a-z가-힣&\\-\\s]", " ");

        // 5) 다중 공백 → 1칸
        t = t.replaceAll("\\s+", " ").trim();

        return t;
    }

    // 일반 텍스트 정규화 (제품명, 모델명 비교용) 메서드
    public static String normalizeText(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);

        // 한글 자모만 단독으로 있는 문자 제거 (ㄱ-ㅎ, ㅏ-ㅣ)
        t = t.replaceAll("[ㄱ-ㅎㅏ-ㅣ]", " ");

        // 다중 공백 정리
        t = t.replaceAll("\\s+", " ").trim();
        return t.isBlank() ? null : t;
    }

    // 약한 검색어 판정 메서드
    // - null/빈문자열
    // - 한글 자모만으로 이루어진 경우
    // - 영문/숫자/완성형 한글을 모두 제외한 뒤 길이가 2 미만인 경우
    public static boolean isWeakQuery(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;

        // 자모만으로 구성된 경우
        if (t.matches("^[\\sㄱ-ㅎㅏ-ㅣ]+$")) return true;

        // 의미 있는 문자만 남기고 길이 확인 (완성형 한글/영문/숫자)
        String letters = t.replaceAll("[^0-9a-zA-Z가-힣]", "");
        return letters.length() < 2;
    }



    // 단,복수 보정용 헬퍼 메서드
    public static String[] pluralCandidates(String normalized) {
        if (normalized == null || normalized.isBlank()) return new String[]{};
        String base = normalized;
        String plural = base.endsWith("s") ? base : base + "s";
        String singular = base.endsWith("s") ? base.substring(0, base.length() - 1) : base;
        if (plural.equals(singular)) return new String[]{ base }; // 한글 등 s 변화가 무의미한 경우
        return new String[]{ base, plural, singular };
    }
}
