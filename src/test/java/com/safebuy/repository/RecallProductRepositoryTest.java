package com.safebuy.repository;

import com.safebuy.entity.RecallProduct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RecallProductRepositoryTest {

    @Autowired
    private RecallProductRepository repository;

    @Test
    void testFindAll() {
        List<RecallProduct> products = repository.findAll();
        assertFalse(products.isEmpty(), "DB에 저장된 제품이 없습니다.");

        products.forEach(p -> System.out.println(
                p.getProductNm() + " | " + p.getMakr() + " | " + p.getModlNmInfo() + " | " + p.getCategory()
        ));
    }

    @Test
    void testFindByProductName() {
        List<RecallProduct> products = repository.findByProductNmContainingIgnoreCase("샘플제품");
        assertFalse(products.isEmpty(), "검색 결과가 없습니다.");

        products.forEach(p -> System.out.println(
                p.getProductNm() + " | " + p.getMakr() + " | " + p.getModlNmInfo() + " | " + p.getCategory()
        ));
    }
}