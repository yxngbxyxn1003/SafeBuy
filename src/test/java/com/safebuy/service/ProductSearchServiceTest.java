package com.safebuy.service;

import com.safebuy.dto.AlternativeProductDto;
import com.safebuy.dto.ProductSearchRequest;
import com.safebuy.dto.ProductSearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ProductSearchServiceTest {

    @Autowired
    private ProductSearchService productSearchService;

    @Test
    void testSearchProductWithAlternatives() {
        ProductSearchRequest request = new ProductSearchRequest();
        request.setProductName("유아용침대");
        request.setManufacturer("Shuyang Sunnybury Baby Products Co., Ltd");
        request.setModelName("MC676");

        ProductSearchResponse response = productSearchService.searchProduct(request);

        assertNotNull(response);

        if (response.isFound()) {
            System.out.println("검색된 제품: " + response.getProductName());
            List<AlternativeProductDto> alternatives = response.getAlternatives();
            assertNotNull(alternatives);
            if (alternatives.isEmpty()) {
                System.out.println("대체상품 없음");
            } else {
                System.out.println("대체상품 목록:");
                alternatives.forEach(a -> System.out.println(
                        a.getTitle() + " | " + a.getMaker() + " | " + a.getPrice()
                ));
            }
        } else {
            System.out.println("검색된 제품 없음: " + response.getMessage());
        }
    }
}
