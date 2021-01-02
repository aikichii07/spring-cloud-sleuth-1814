package com.example.demo;

import org.jetbrains.annotations.NotNull;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

public class FunctionalTransactionTemplate extends TransactionTemplate {

    /**
     * Used for mocking/spying in tests.
     */
    private FunctionalTransactionTemplate() {
    }

    public FunctionalTransactionTemplate(PlatformTransactionManager transactionManager) {
        super(transactionManager);
    }

    public void execute(Runnable action) throws TransactionException {
        execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(@NotNull TransactionStatus status) {
                action.run();
            }
        });
    }

    public <R> R execute(Supplier<R> supplier) throws TransactionException {
        return execute(status -> supplier.get());
    }
}
