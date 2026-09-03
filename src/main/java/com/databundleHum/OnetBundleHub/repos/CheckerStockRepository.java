package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.entity.CheckerStock;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CheckerStockRepository extends JpaRepository<CheckerStock, Long> {

    /**
     * Claim the oldest unused code(s) for an exam type with a SELECT FOR
     * UPDATE row lock, so two concurrent purchases can never be handed the
     * same code — mirrors UserRepository.findByIdForUpdate's locking
     * pattern. Must be called inside an active @Transactional boundary.
     *
     * Takes a Pageable purely to limit the result count (Spring Data has no
     * portable "LIMIT" in @Query JPQL otherwise) — callers claiming a
     * single code should pass PageRequest.of(0, 1) and read index 0.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CheckerStock s " +
            "WHERE s.examType = :examType AND s.used = false " +
            "ORDER BY s.id ASC")
    List<CheckerStock> findAvailableForUpdate(@Param("examType") CheckerPricing.ExamType examType, Pageable pageable);

    long countByExamTypeAndUsedFalse(CheckerPricing.ExamType examType);

    long countByExamTypeAndUsedTrue(CheckerPricing.ExamType examType);

    Page<CheckerStock> findByExamTypeOrderByCreatedAtDesc(CheckerPricing.ExamType examType, Pageable pageable);

    Page<CheckerStock> findByExamTypeAndUsedOrderByCreatedAtDesc(
            CheckerPricing.ExamType examType, boolean used, Pageable pageable);
}
