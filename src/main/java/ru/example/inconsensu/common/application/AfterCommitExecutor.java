package ru.example.inconsensu.common.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Запуск фоновой работы после коммита транзакции.
 *
 * <p>Задача, поставленная изнутри транзакции, стартует в другом потоке и может не увидеть ещё не
 * зафиксированную запись — тогда она молча завершится, а пользователь останется со статусом «в очереди»
 * навсегда. Поэтому исполнение откладывается до коммита; без активной транзакции задача запускается сразу.
 */
@Component
public class AfterCommitExecutor {

    private final TaskExecutor taskExecutor;

    // Начиная со Spring Boot 3.5 планировщик тоже TaskExecutor, и внедрение по типу неоднозначно:
    // фоновая работа должна уходить в пул приложения, а не в пул расписаний.
    public AfterCommitExecutor(@Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public void execute(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskExecutor.execute(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskExecutor.execute(task);
            }
        });
    }
}
