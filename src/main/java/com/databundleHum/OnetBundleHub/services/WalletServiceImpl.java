package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.entity.*;
import com.databundleHum.OnetBundleHub.entity.WalletTransaction.TransactionType;
import com.databundleHum.OnetBundleHub.repos.*;
import com.databundleHum.OnetBundleHub.security.InsufficientBalanceException;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * All wallet mutations go through this service.
 *
 * Concurrency safety:
 *   debit() and credit() both use a SELECT FOR UPDATE via userRepository.findByIdForUpdate()
 *   inside a @Transactional boundary.  This prevents two simultaneous purchases from
 *   over-spending the same wallet balance.
 *
 * Audit trail:
 *   Every mutation inserts a wallet_transactions row AND updates users.wallet_balance atomically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final UserRepository userRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    // ── Debit ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void debit(UUID userId, BigDecimal amount, TransactionType type,
                      String description, String reference) {

        // Row-level lock — prevents concurrent over-spend
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        BigDecimal currentBalance = currentBalance(user);
        if (currentBalance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(
                    "Wallet balance GHS " + currentBalance
                            + " is insufficient. Required: GHS " + amount);
        }

        BigDecimal newBalance = currentBalance.subtract(amount);
        user.setWalletBalance(newBalance);
        userRepository.save(user);

        recordTransaction(user, type, amount, newBalance, reference, description);

        log.info("Wallet DEBIT: userId={} type={} amount={} balanceAfter={} ref={}",
                userId, type, amount, newBalance, reference);
    }

    // ── Credit ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void credit(UUID userId, BigDecimal amount, TransactionType type,
                       String description, String reference) {

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        BigDecimal newBalance = currentBalance(user).add(amount);
        user.setWalletBalance(newBalance);
        userRepository.save(user);

        recordTransaction(user, type, amount, newBalance, reference, description);

        log.info("Wallet CREDIT: userId={} type={} amount={} balanceAfter={} ref={}",
                userId, type, amount, newBalance, reference);
    }

    // ── Balance ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getBalance(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        return currentBalance(user);
    }

    /**
     * Some user rows predate the wallet_balance backfill and still carry NULL.
     * Calling .add()/.compareTo() on that NULL threw a NullPointerException,
     * which surfaced as a bare 500 — this is what broke admin Add money for
     * affected users. NULL is treated as GHS 0, and the first successful
     * mutation writes a real balance back, so the row self-heals.
     */
    private BigDecimal currentBalance(User user) {
        if (user.getWalletBalance() == null) {
            log.warn("[WALLET] Null wallet_balance for userId={} — treating as 0", user.getId());
            return BigDecimal.ZERO;
        }
        return user.getWalletBalance();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void recordTransaction(User user, TransactionType type, BigDecimal amount,
                                   BigDecimal balanceAfter, String reference, String description) {
        WalletTransaction tx = WalletTransaction.builder()
                .user(user)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .reference(reference)
                .description(description)
                .build();
        walletTransactionRepository.save(tx);
    }
}