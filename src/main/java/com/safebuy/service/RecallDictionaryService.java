package com.safebuy.service;

import com.safebuy.entity.RecallProduct;
import com.safebuy.repository.RecallProductRepository;
import com.safebuy.util.TextNormalizer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecallDictionaryService {

    private final RecallProductRepository repository;

    // 한 번 만들어지면 변경되지 않는 불변 딕셔너리를 담는 래퍼 클래스
    // manufacturers/products/models: 모두 정규화된 문자열의 집합
    @Getter
    public static final class Dictionary {
        private final Set<String> manufacturers;
        private final Set<String> products;
        private final Set<String> models;

        private Dictionary(Set<String> manufacturers, Set<String> products, Set<String> models) {
            this.manufacturers = manufacturers;
            this.products = products;
            this.models = models;
        }

        public static Dictionary empty() {
            return new Dictionary(
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptySet()
            );
        }
    }
    // 현재 사용 중인 딕셔너리의 "참조"를 담는 필드
    // - volatile: 다른 스레드에서 최신 참조를 즉시 볼 수 있도록 보장함
    // - 항상 불변 딕셔너리 객체만 참조하도록 하므로 읽기 경합이 전혀 없다.
    private volatile Dictionary dictRef = Dictionary.empty();

    // 공개 API
    // 서버 기동 완료시 자동으로 1회 실행
    // ApplicationReadyEvent는 Spring 컨텍스트가 모두 올라온 뒤 발생함
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        try {
            log.info("[RecallDictionaryService] 애플리케이션 기동 - 사전 캐시 초기화 시작");
            refresh(); // 최초 1회
            log.info("[RecallDictionaryService] 사전 캐시 초기화 완료: manu={}, prod={}, model={}",
                    dictRef.getManufacturers().size(),
                    dictRef.getProducts().size(),
                    dictRef.getManufacturers().size());
        } catch (Exception e) {
            log.error("[RecallDictionaryService] 초기화 실패 - 런타임에는 문제 없이 동작하지만 추천/필터 품질이 떨어질 수 있습니다.", e);
        }
    }

    // DB를 전체 스캔하여 새 딕셔너리를 만든 뒤 참조를 원자적으로 교체함
    // - RecallService.updateAllData() 끝에서 호출 필요!
    @Async
    public void refresh() {
        Instant start = Instant.now(); // 시작 시간 기록
        log.info("[RecallDictionaryService] refresh 시작");

        // DB 전체 조회
        List<RecallProduct> all = repository.findAll();
        log.info("[RecallDictionaryService] DB row 수: {}", all.size());

        // 임시 Set 만들기 (중복 제거 목적 -> HashSet 자료구조 사용)
        Set<String> manu = new HashSet<>(all.size());
        Set<String> prod = new HashSet<>(all.size());
        Set<String> model = new HashSet<>(all.size());

        // 정규화해서 담기
        for (RecallProduct p : all) {
            // 제조사 정규화
            String m = TextNormalizer.normalizeText(p.getMakr());
            if (StringUtils.hasText(m) && !TextNormalizer.isWeakQuery(m)) {
                manu.add(m);
            }

            // 제품명 정규화
            String pn = TextNormalizer.normalizeText(p.getProductNm());
            if (StringUtils.hasText(pn) && !TextNormalizer.isWeakQuery(pn)) {
                prod.add(pn);

            }

            // 모델명 정규화
            String md = TextNormalizer.normalizeText(p.getModlNmInfo());
            if (StringUtils.hasText(md) && !TextNormalizer.isWeakQuery(md)) {
                model.add(md);
            }
        }

        // 불변 Set로 래핑 (읽기 시 동기화 불필요)
        Dictionary newDict = new Dictionary(
                Collections.unmodifiableSet(manu),
                Collections.unmodifiableSet(prod),
                Collections.unmodifiableSet(model)
        );

        // 참조 원자 교체 (volatile로 선언한 dictRef로 교체하므로 다른 스레드는 즉시 최신 딕셔너리를 볼 수 있다.)
        dictRef = newDict;

        Duration took = Duration.between(start, Instant.now()); // 소요시간 계산
        log.info("[RecallDictionaryService] refresh 완료 - manu={}, prod={}, model={}, took={}ms",
                dictRef.getManufacturers().size(),
                dictRef.getProducts().size(),
                dictRef.getModels().size(),
                took.toMillis());
    }

    // 검색어 후보 검증 API
    public enum Field {
        MANUFACTURER, PRODUCT, MODEL
    }

    // 주어진 후보들 중 DB에 존재할 법한 것만 남기는 메서드
    // 1순위: 정규화 후 정확히 일치하는 것
    // 2순위: 정확 일치가 하나도 없으면 부분 포함(contains)으로 느슨하게 허용
    public List<String> filterCandidates(List<String> candidates, Field field, int limit) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        // 정규화, 유효성 체크
        List<String> normalized = candidates.stream()
                .filter(StringUtils::hasText)
                .map(s -> normalizedByField(s, field))
                .filter(StringUtils::hasText)
                .filter(s -> !TextNormalizer.isWeakQuery(s))
                .distinct()
                .collect(Collectors.toList());

        if (normalized.isEmpty()) return List.of();

        // 딕셔너리 스냅샷
        Dictionary dict = dictRef;

        // 1순위 - 정확 일치 우선 판단
        Set<String> exact = normalized.stream()
                .filter(s -> containsExact(dict, field, s))
                .limit(limit)
                .collect(Collectors.toCollection(LinkedHashSet::new)); // LinkedHashSet 사용으로 삽입 순서 유지

        if (!exact.isEmpty()) return new ArrayList<>(exact);

        // 2순위 - 정확 일치가 없으면 부분 포함(contains)으로 fallback
        Set<String> fuzzy = normalized.stream()
                .filter(s -> containsFuzzy(dict, field, s))
                .limit(limit)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new ArrayList<>(fuzzy);
    }

    // 단일 용어가 DB에 존재할 법한지 체크
    // 정확 일치 -> 없으면 부분 일치 순으로 확인
    public boolean mightExist(String term, Field field) {
        if (!StringUtils.hasText(term)) return false;
        String n = normalizedByField(term, field);
        if (!StringUtils.hasText(n) || TextNormalizer.isWeakQuery(n)) return false;

        Dictionary dict = dictRef;
        return containsExact(dict, field, n) || containsFuzzy(dict, field, n);
    }

    // 내부 메서드
    private String normalizedByField(String s, Field field) {
        if (s == null) return null;
        return switch (field) {
            case MANUFACTURER -> TextNormalizer.normalizeManufacturer(s);
            case PRODUCT, MODEL -> TextNormalizer.normalizeText(s);
        };
    }

    private Set<String> getBaseSet(Dictionary d, Field f) {
        return switch (f) {
            case MANUFACTURER -> d.getManufacturers();
            case PRODUCT -> d.getProducts();
            case MODEL -> d.getModels();
        };
    }

    private boolean containsExact(Dictionary d, Field f, String v) {
        return getBaseSet(d, f).contains(v);
    }

    private boolean containsFuzzy(Dictionary d, Field f, String v) {
        return getBaseSet(d, f).stream().anyMatch(x -> x.contains(v));
    }
}
