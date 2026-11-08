package com.dotran.oms.inventory.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionTimeoutPersistenceAdapter")
class TransactionTimeoutPersistenceAdapterTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private Query query;

    @InjectMocks
    private TransactionTimeoutPersistenceAdapter adapter;

    @Test
    @DisplayName("sets lock_timeout and statement_timeout for the transaction only, in milliseconds")
    void setsLocalTimeouts() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(entityManager.createNativeQuery(sql.capture())).thenReturn(query);
        when(query.setParameter(anyString(), anyString())).thenReturn(query);

        adapter.applyLocal(Duration.ofSeconds(3), Duration.ofMillis(5500));

        assertThat(sql.getValue()).contains("set_config('lock_timeout', :lockTimeout, true)")
                .contains("set_config('statement_timeout', :statementTimeout, true)");
        verify(query).setParameter("lockTimeout", "3000ms");
        verify(query).setParameter("statementTimeout", "5500ms");
        verify(query).getSingleResult();
    }
}
