package com.safebuy.controller;

import com.safebuy.dto.AlternativeProductDto;
import com.safebuy.service.AlternativeProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alternatives")
@RequiredArgsConstructor
public class AlternativeProductController {

    private final AlternativeProductService alternativeProductService;

    // 대체 상품 미리보기 (3개)
    @GetMapping("/preview")
    public List<AlternativeProductDto> getPreviewAlternatives(
            @RequestParam String productName,
            @RequestParam String category,
            @RequestParam String maker
    ) {
        return alternativeProductService.findAlternatives(productName, category, maker)
                .stream()
                .limit(3)
                .toList();
    }

    // 대체 상품 더보기 (전체, 최대 20개)
    @GetMapping("/full")
    public List<AlternativeProductDto> getFullAlternatives(
            @RequestParam String productName,
            @RequestParam String category,
            @RequestParam String maker
    ) {
        return alternativeProductService.findAlternatives(productName, category, maker)
                .stream()
                .limit(20)
                .toList();
    }
}