package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResellerProfileRepository extends JpaRepository<ResellerProfile, UUID> {

    boolean existsByUser(User user);

    Optional<ResellerProfile> findByUser(User user);

    Optional<ResellerProfile> findByUser_Id(UUID userId);

    Page<ResellerProfile> findByStatus(ResellerProfile.ResellerStatus status, Pageable pageable);

    long countByStatus(ResellerProfile.ResellerStatus status);

    List<ResellerProfile> findByStatusAndStoreSlugIsNull(ResellerProfile.ResellerStatus status);

    // ── NEW: storefront slug lookup ───────────────────────────────────────────

    /** Look up a store by its slug (used by the public storefront endpoint). */
    Optional<ResellerProfile> findByStoreSlug(String storeSlug);

    /** Check slug uniqueness during generation / admin override validation. */
    boolean existsByStoreSlug(String storeSlug);
}