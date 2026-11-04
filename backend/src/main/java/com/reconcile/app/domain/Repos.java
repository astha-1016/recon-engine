package com.reconcile.app.domain;

import com.reconcile.app.domain.Entities.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class Repos {
    private Repos() {}

    public interface CustomerRepo extends JpaRepository<CustomerE, String> {}

    public interface InvoiceRepo extends JpaRepository<InvoiceE, String> {
        List<InvoiceE> findByCustomerId(String customerId);
        boolean existsByRowHash(String rowHash);
        List<InvoiceE> findByIdIn(Collection<String> ids);
    }

    public interface TxnRepo extends JpaRepository<TxnE, String> {
        boolean existsByRowHash(String rowHash);
        List<TxnE> findByIdIn(Collection<String> ids);
    }

    public interface AliasRepo extends JpaRepository<AliasE, Long> {
        Optional<AliasE> findByPayerKeyAndCustomerId(String payerKey, String customerId);
    }

    public interface AllocationRepo extends JpaRepository<AllocationE, Long> {
        List<AllocationE> findByTxnId(String txnId);
        List<AllocationE> findByInvoiceId(String invoiceId);

        /** Bulk deletes: harmless if the background run already removed the rows (a load-then-delete would throw then). */
        @Modifying @Query("delete from AllocationE a where a.txnId = :txnId")
        int deleteAllForTxn(@Param("txnId") String txnId);

        @Modifying @Query("delete from AllocationE a where a.invoiceId = :invoiceId")
        int deleteAllForInvoice(@Param("invoiceId") String invoiceId);
    }

    public interface AdjustmentRepo extends JpaRepository<AdjustmentE, Long> {}

    public interface CreditRepo extends JpaRepository<CreditE, Long> {
        Optional<CreditE> findFirstByTxnId(String txnId);

        @Modifying @Query("delete from CreditE c where c.txnId = :txnId")
        int deleteAllForTxn(@Param("txnId") String txnId);
    }

    public interface OutcomeRepo extends JpaRepository<OutcomeE, String> {
        List<OutcomeE> findByDecisionIn(Collection<String> decisions);
        long countByDecision(String decision);

        @Modifying @Query("delete from OutcomeE o where o.txnId = :txnId")
        int deleteForTxn(@Param("txnId") String txnId);
    }

    public interface ManualRepo extends JpaRepository<ManualE, Long> {
        List<ManualE> findByTxnId(String txnId);
        void deleteByTxnId(String txnId);
        boolean existsByInvoiceId(String invoiceId);
        boolean existsByTxnId(String txnId);
    }

    public interface CreditApplicationRepo extends JpaRepository<CreditApplicationE, Long> {
        List<CreditApplicationE> findByTxnId(String txnId);
        boolean existsByInvoiceId(String invoiceId);
        boolean existsByTxnId(String txnId);
    }

    public interface FeedbackRepo extends JpaRepository<FeedbackE, Long> {
        List<FeedbackE> findByTxnId(String txnId);
    }

    public interface UserRepo extends JpaRepository<UserE, Long> {
        Optional<UserE> findByUsername(String username);
    }

    public interface AuditRepo extends JpaRepository<AuditE, Long> {}
}
