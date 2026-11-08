package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.inventory.application.repository.TransactionTimeoutRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/** {@code set_config(..., true)} is the function form of {@code SET LOCAL}: it ends with the transaction. */
@PersistenceAdapter
@RequiredArgsConstructor
public class TransactionTimeoutPersistenceAdapter implements TransactionTimeoutRepository {

    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyLocal(Duration lockTimeout, Duration statementTimeout) {
        entityManager.createNativeQuery("""
                        SELECT set_config('lock_timeout', :lockTimeout, true),
                               set_config('statement_timeout', :statementTimeout, true)
                        """)
                .setParameter("lockTimeout", lockTimeout.toMillis() + "ms")
                .setParameter("statementTimeout", statementTimeout.toMillis() + "ms")
                .getSingleResult();
    }
}
