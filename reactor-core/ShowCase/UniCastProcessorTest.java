package reactor.core.processors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.UnicastProcessor;
import reactor.core.subscriber.AssertSubscriber;
import reactor.test.StepVerifier;
import reactor.util.concurrent.Queues;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;

/**
 *  UnicastProcessor： <br>
 *  1. 内置缓存队列 <br>
 *  2. 只能有一个订阅者 <br>
 * @author lianghong
 * @date 2020/6/12
 */
public class UniCastProcessorTest {

    @Test
    @DisplayName("通过发出error拒绝多订阅者")
    public void secondSubscriberRejectedProperly() {
        UnicastProcessor<Integer> uniCastProcessor = UnicastProcessor.create();

        AssertSubscriber<Integer> ts_1 = AssertSubscriber.create();
        AssertSubscriber<Integer> ts_2 = AssertSubscriber.create();
        uniCastProcessor.subscribe(ts_1);
        uniCastProcessor.subscribe(ts_2);

        uniCastProcessor.onNext(1);
        uniCastProcessor.onComplete();

        ts_1.assertValues(1)
                .assertComplete()
                .assertNoError();

        // 第二个订阅者会接收到一个错误信号
        // UnicastProcessor 只允许最多1个订阅者
        ts_2.assertError(IllegalStateException.class);
        Assertions.assertTrue(uniCastProcessor.hasCompleted(), "no completed?");
    }

    @Test
    @DisplayName("队列溢出自定义处理策略")
    public void overflowQueueTerminate() {
        Consumer<? super Integer> onOverflow = item -> {
            System.out.println("缓存队列已满，无法继续产生数据！");
        };
        LinkedBlockingDeque<Integer> queue = new LinkedBlockingDeque<>(1);
        UnicastProcessor<Integer> unicastProcessor = UnicastProcessor.create(queue, onOverflow, () -> {});

        StepVerifier
                .create(unicastProcessor, 0L)
                .then(() -> {
                    FluxSink<Integer> sink = unicastProcessor.sink();
                    for (int i = 0; i < 20; i++) {
                        sink.next(i);
                    }
                    sink.complete();
                })
                .thenRequest(1)
                .expectNext(0)
                .expectError(IllegalStateException.class)
                .verify();
    }
}
