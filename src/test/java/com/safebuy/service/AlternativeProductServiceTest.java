package com.safebuy.service;

import com.safebuy.dto.AlternativeProductDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AlternativeProductServiceTest {

    @Autowired
    private AlternativeProductService service;

    @Test
    void testFindAlternatives() {
        String productName = "베비라보 호빵맨 사이좋은 콘서트";
        String dbCategory = "육아";
        String dbBrand = "주식회사 반다이";

        List<AlternativeProductDto> results = service.findAlternatives(productName, dbCategory, dbBrand);

        assertNotNull(results);
        assertTrue(results.size() > 0, "추천할 대체 상품이 존재하지 않습니다.");

        results.forEach(item -> {
            System.out.println(item.getTitle() + " | " + item.getBrand() + " | " + item.getPrice()
                    + " | " + item.getImage() + " | " + item.getLink());
        });
    }
}