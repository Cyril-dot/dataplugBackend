package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CheckerPricingRepository extends JpaRepository<CheckerPricing, Long> {

    Optional<CheckerPricing> findByExamTypeAndActiveTrue(CheckerPricing.ExamType examType);

    List<CheckerPricing> findByActiveTrue();
}
