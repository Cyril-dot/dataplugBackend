package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.entity.CheckerResellerPricing;
import com.databundleHum.OnetBundleHub.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CheckerResellerPricingRepository extends JpaRepository<CheckerResellerPricing, Long> {

    Optional<CheckerResellerPricing> findByResellerAndExamType(User reseller, CheckerPricing.ExamType examType);

    List<CheckerResellerPricing> findByReseller(User reseller);

    /**
     * Only checker prices for exam types that are also currently active on
     * the platform-wide CheckerPricing table — mirrors
     * ResellerPricingRepository.findByResellerWithActivePlatformSettings.
     */
    @Query("""
        SELECT crp FROM CheckerResellerPricing crp
        WHERE crp.reseller = :reseller
          AND EXISTS (
              SELECT 1 FROM CheckerPricing cp
              WHERE cp.examType = crp.examType AND cp.active = true
          )
        """)
    List<CheckerResellerPricing> findByResellerWithActivePricing(User reseller);
}
