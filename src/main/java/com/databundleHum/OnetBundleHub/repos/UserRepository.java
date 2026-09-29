package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.ResellerProfile;
import com.databundleHum.OnetBundleHub.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    List<User> findAllByRole(User.Role role);

    // ── Auth / identity ───────────────────────────────────────────────────────

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    Optional<User> findByEmail(String email);

    // ── Wallet (pessimistic lock) ─────────────────────────────────────────────

    /**
     * Fetch a user with a SELECT FOR UPDATE row-level lock.
     *
     * Used by WalletServiceImpl.debit() and credit() to prevent two concurrent
     * transactions from reading the same stale wallet balance and over-spending.
     *
     * Must be called inside an active @Transactional boundary — Spring will
     * release the lock automatically when the transaction commits or rolls back.
     *
     * The @Query is required because Spring Data cannot derive a JPQL query
     * from the method name alone when a @Lock annotation is present.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    // ── Admin ─────────────────────────────────────────────────────────────────

    long countByRole(User.Role role);

    @Query("SELECT SUM(u.walletBalance) FROM User u")
    BigDecimal sumWalletBalances();

    /**
     * Sum of affiliateEarningsGhc across all users — the platform's total
     * outstanding liability for unclaimed affiliate commission. Kept
     * DELIBERATELY separate from sumWalletBalances(), since these are two
     * different pots of money owed to users.
     */
    @Query("SELECT COALESCE(SUM(u.affiliateEarningsGhc), 0) FROM User u")
    BigDecimal sumAffiliateEarningsGhc();

    // ── Affiliate ─────────────────────────────────────────────────────────────

    /**
     * Resolve an affiliate referral code to the owning User.
     * Used by AffiliateService.resolveAffiliateCode() at registration.
     */
    Optional<User> findByAffiliateCode(String affiliateCode);

    /**
     * Uniqueness check during affiliate code generation.
     */
    boolean existsByAffiliateCode(String affiliateCode);

    /**
     * Count of users who signed up via a specific affiliate's referral link.
     * Includes users who signed up but haven't ordered yet.
     * Used on the affiliate dashboard "Total sign-ups" stat.
     */
    long countByReferredByAffiliateId(UUID affiliateId);

    /**
     * Paginated list of users who signed up via a specific affiliate's referral link.
     * Used for the sub-customer list on the affiliate dashboard (if added later).
     */
    Page<User> findByReferredByAffiliateId(UUID affiliateId, Pageable pageable);

    // ── Reseller referral ─────────────────────────────────────────────────────

    /**
     * Find users who signed up via a specific reseller's referral link.
     * referredByReseller is a User FK (the reseller user), so we filter by their ID.
     * Used by ResellerServiceImpl.getSubCustomers().
     */
    Page<User> findByReferredByResellerId(UUID resellerId, Pageable pageable);

    /**
     * Resolve a reseller slug to the reseller's User entity.
     *
     * Joins users → reseller_profiles on reseller_profiles.user_id = users.id,
     * filtering by store_slug and status = APPROVED.
     *
     * FIX: Hibernate 6 (Spring Boot 3.x) no longer supports fully-qualified enum
     * literals inside JPQL strings. Pass the enum value as a named parameter instead.
     *
     * Used by:
     *   - AffiliateService.resolveResellerSlug() (registration cookie attribution)
     *   - AffiliateRedirectController.resellerReferralRedirect() (redirect validity check)
     *
     * Call site example:
     *   userRepository.findByApprovedResellerSlug(slug, ResellerProfile.ResellerStatus.APPROVED)
     */
    @Query("""
            SELECT u FROM User u
            JOIN ResellerProfile rp ON rp.user = u
            WHERE rp.storeSlug = :slug
              AND rp.status = :approvedStatus
            """)
    Optional<User> findByApprovedResellerSlug(
            @Param("slug") String slug,
            @Param("approvedStatus") ResellerProfile.ResellerStatus approvedStatus
    );
}
