package dev.aegis.bootstrap.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Infrastructure decorator that runs every call of an application use-case interface inside one database
 * transaction. Keeps the application services free of Spring; verified by integration tests, not assumed.
 */
public final class TransactionalProxy {
    private TransactionalProxy() {
    }

    /**
     * Exposes exactly one use-case interface of a service object without transaction handling, so that a
     * service implementing several interfaces can be registered once per interface without ambiguity.
     */
    public static <T> T expose(Class<T> useCase, T target) {
        Object proxy = Proxy.newProxyInstance(useCase.getClassLoader(), new Class<?>[] {useCase},
                (self, method, args) -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
        return useCase.cast(proxy);
    }

    public static <T> T wrap(Class<T> useCase, T target, PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Object proxy = Proxy.newProxyInstance(useCase.getClassLoader(), new Class<?>[] {useCase},
                (self, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(target, args);
                    }
                    try {
                        return template.execute(status -> {
                            try {
                                return method.invoke(target, args);
                            } catch (InvocationTargetException e) {
                                throw new Rethrow(e.getTargetException());
                            } catch (IllegalAccessException e) {
                                throw new IllegalStateException(e);
                            }
                        });
                    } catch (Rethrow rethrow) {
                        throw rethrow.getCause();
                    }
                });
        return useCase.cast(proxy);
    }

    /** Carries the original exception through the template (which rolls back on any RuntimeException). */
    private static final class Rethrow extends RuntimeException {
        private Rethrow(Throwable cause) {
            super(cause);
        }
    }
}
