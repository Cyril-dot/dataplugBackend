package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CheckerPricingRepository extends JpaRepository<CheckerPricing, Long> {

    Optional<CheckerPricing> findByExamTypeAndActiveTrue(CheckerPricing.ExamType examType);

    /**
     * ✅ Added for AdminCheckerPricingController's upsert logic — exam_type
     * has a UNIQUE constraint at the database level regardless of active
     * status, but the upsert previously only checked for an ACTIVE existing
     * row before deciding to insert a new one. If a row for that exam type
     * existed but was inactive (e.g. soft-deleted earlier), the check
     * missed it, a duplicate insert was attempted, and the database's
     * unique constraint threw a DataIntegrityViolationException — a 500 on
     * every attempt to re-add pricing for an exam type that had ever been
     * deactivated before. This finds the row regardless of active status
     * so the upsert can correctly update/reactivate it instead.
     */
    Optional<CheckerPricing> findByExamType(CheckerPricing.ExamType examType);

    List<CheckerPricing> findByActiveTrue();
}
