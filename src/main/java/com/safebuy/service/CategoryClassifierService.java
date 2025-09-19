package com.safebuy.service;

import org.springframework.stereotype.Service;

@Service
public class CategoryClassifierService {

    public String classifyProduct(String productName) {
        if (productName == null || productName.trim().isEmpty()) {
            return "기타";
        }

        String product = productName.toLowerCase().trim();

        // 식품 관련 키워드
        if (containsAny(product, "음료", "음식", "식품", "과자", "빵", "케이크", "아이스크림", "우유", "요거트", 
                       "치즈", "버터", "식용유", "소스", "조미료", "향신료", "차", "커피", "주스", "탄산음료",
                       "베이비푸드", "유아식", "이유식", "분유", "영양제", "비타민", "건강식품", "기능성식품")) {
            return "식품";
        }

        // 가구 관련 키워드
        if (containsAny(product, "침대", "소파", "의자", "책상", "책장", "옷장", "화장대", "식탁", "의자",
                       "테이블", "캐비넷", "수납장", "선반", "거울", "조명", "가구", "인테리어", "홈데코",
                       "매트리스", "베개", "이불", "커튼", "블라인드")) {
            return "가구";
        }

        // 스포츠 관련 키워드
        if (containsAny(product, "운동", "스포츠", "헬스", "피트니스", "요가", "필라테스", "홈트레이닝",
                       "덤벨", "바벨", "러닝머신", "자전거", "헬스기구", "운동기구", "구기", "축구", "농구",
                       "테니스", "배드민턴", "골프", "수영", "등산", "캠핑", "아웃도어", "스키", "스노보드")) {
            return "스포츠";
        }

        // 육아 관련 키워드
        if (containsAny(product, "유모차", "아기띠", "카시트", "아기용품", "육아용품", "기저귀", "분유",
                       "이유식", "베이비푸드", "아기옷", "유아복", "아기장난감", "교육용품", "학습용품",
                       "아기침대", "아기의자", "아기식탁", "아기욕조", "아기용품", "육아", "키즈", "어린이")) {
            return "육아";
        }

        // 가전 관련 키워드
        if (containsAny(product, "냉장고", "세탁기", "건조기", "에어컨", "히터", "전자레인지", "오븐",
                       "인덕션", "가스레인지", "전기밥솥", "믹서", "블렌더", "커피머신", "청소기", "로봇청소기",
                       "공기청정기", "가습기", "제습기", "다리미", "드라이어", "면도기", "전기면도기",
                       "가전", "전자제품", "가전제품")) {
            return "가전";
        }

        // 생활/건강 관련 키워드
        if (containsAny(product, "화장품", "스킨케어", "메이크업", "향수", "샴푸", "린스", "바디워시",
                       "비누", "치약", "칫솔", "세정제", "세제", "섬유유연제", "화장지", "휴지", "생활용품",
                       "건강", "의료기기", "혈압계", "체중계", "온도계", "마스크", "손소독제", "의료용품",
                       "생활", "위생", "청결", "소독")) {
            return "생활/건강";
        }

        // 디지털 관련 키워드
        if (containsAny(product, "스마트폰", "핸드폰", "휴대폰", "태블릿", "노트북", "컴퓨터", "pc",
                       "모니터", "키보드", "마우스", "헤드폰", "이어폰", "스피커", "카메라", "캠코더",
                       "게임기", "ps", "xbox", "nintendo", "스위치", "게임", "콘솔", "충전기", "케이블",
                       "디지털", "전자", "it", "가젯", "기술", "스마트", "ai", "iot")) {
            return "디지털";
        }

        return "기타";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
