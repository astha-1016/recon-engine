package com.reconcile.app.domain;

import com.reconcile.app.domain.Entities.*;
import org.springframework.data.jpa.repository.JpaRepository;

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
    }

    public interface AdjustmentRepo extends JpaRepository<AdjustmentE, Long> {}

    public interface CreditRepo extends JpaRepository<CreditE, Long> {
        Optional<CreditE> findFirstByTxnId(String txnId);
    }

    public interface OutcomeRepo extends JpaRepository<OutcomeE, String> {
        List<OutcomeE> findByDecisionIn(Collection<String> decisions);
        long countByDecision(String decision);
    }

    public interface ManualRepo extends JpaRepository<ManualE, Long> {
        List<ManualE> findByTxnId(String txnId);
        void deleteByTxnId(String txnId);
    }

    public interface CreditApplicationRepo extends JpaRepository<CreditApplicationE, Long> {
        List<CreditApplicationE> findByTxnId(String txnId);
    }

    public interface FeedbackRepo extends JpaRepository<FeedbackE, Long> {
        List<FeedbackE> findByTxnId(String txnId);
    }

    public interface UserRepo extends JpaRepository<UserE, Long> {
        Optional<UserE> findByUsername(String username);
    }

    public interface AuditRepo extends JpaRepository<AuditE, Long> {}
}
