package com.databundleHum.OnetBundleHub.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Aligns the live wallet_transactions check constraint with the code.
 *
 * Root cause (25 Oct 2026, Railway log): admin Add money CREDITs GH₵85 / GH₵1
 * for user 506a37bd-5a62-46ab-896e-f5f96d9190bb, the balance math succeeded
 * (balance_after = amount, from 0), but Postgres rejected the ledger insert:
 *   ERROR: new row for relation "wallet_transactions" violates check
 *   constraint "wallet_transactions_type_check" (SQLState 23514)
 *   failing type: ADMIN_CREDIT
 *
 * The WalletTransaction entity carries no @Check, so this constraint is a
 * leftover in the live database from before ADMIN_CREDIT / ADMIN_DEBIT /
 * AFFILIATE_* were added to the TransactionType enum. Hibernate
 * ddl-auto=update never alters an existing check constraint, so a code-only
 * change can never fix this — the constraint itself has to be replaced.
 *
 * This runner does exactly that, idempotently, on every boot (after
 * Hibernate has run, since ApplicationRunner runs after context setup):
 *   DROP CONSTRAINT IF EXISTS wallet_transactions_type_check;
 *   ADD CONSTRAINT wallet_transactions_type_check CHECK (type IN (…all 9…));
 *
 * Existing rows cannot violate the new list: every type the old constraint
 * allowed is a subset of the new list. If the DDL itself fails, the failure
 * is logged loudly but startup is NOT aborted — the app stays up and the
 * next wallet write will surface the real error, as before.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletTransactionConstraintFix implements ApplicationRunner {

    private final DataSource dataSource;

    private static final String DROP_CONSTRAINT =
            "ALTER TABLE public.wallet_transactions " +
            "DROP CONSTRAINT IF EXISTS wallet_transactions_type_check";

    private static final String ADD_CONSTRAINT =
            "ALTER TABLE public.wallet_transactions " +
            "ADD CONSTRAINT wallet_transactions_type_check CHECK (type IN (" +
            "'TOPUP', 'PURCHASE', 'RESELLER_FEE', 'PAYOUT', 'REFUND', " +
            "'ADMIN_CREDIT', 'ADMIN_DEBIT', " +
            "'AFFILIATE_COMMISSION', 'AFFILIATE_COMMISSION_REVERSAL'))";

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(DROP_CONSTRAINT);
            stmt.execute(ADD_CONSTRAINT);
            log.info("[WALLET-SCHEMA] wallet_transactions_type_check aligned " +
                    "to the full TransactionType list (ADMIN_CREDIT/ADMIN_DEBIT included)");
        } catch (Exception e) {
            log.error("[WALLET-SCHEMA] FAILED to align wallet_transactions_type_check — " +
                    "admin Add/Deduct money will keep 500ing until this runs", e);
        }
    }
}
