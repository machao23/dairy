import java.util.Objects;
import java.util.function.Consumer;

public abstract class Mono<T> implements Publisher<T> {
    public final reactor.core.publisher.Mono<T> doOnSuccess(Consumer<? super T> onSuccess) {
        // 调用doOnSuccess会创建一个 MonoPeekTerminal 实例
        return onAssembly(new MonoPeekTerminal<>(this, onSuccess, null, null));
    }
}

// doOnSuccess时候会创建这个实例
final class MonoPeekTerminal<T> extends MonoOperator<T, T> implements Fuseable {

    // 内部订阅类
    static final class MonoTerminalPeekSubscriber<T>
            implements Fuseable.ConditionalSubscriber<T>, InnerOperator<T, T>,
            Fuseable.QueueSubscription<T> {

        // parent就是外部类的实例
        final MonoPeekTerminal<T> parent;

        @Override
        public void onNext(T t) {
            if (parent.onSuccessCall != null) {
                // 如果流里声明了doOnSuccess，就会回调
                parent.onSuccessCall.accept(t);
            }
        }
    }
}
