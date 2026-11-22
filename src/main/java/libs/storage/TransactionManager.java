package libs.storage;

import java.util.function.Supplier;

public interface TransactionManager {
    <T> T inTransaction(Supplier<T> operation);
}
