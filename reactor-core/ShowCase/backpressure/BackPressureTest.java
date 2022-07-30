package com.rinbo.reactor.backpressure;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BackPressureTest {

    CountDownLatch countDownLatch;
    EventSource eventSource = new EventSource();
    Subscriber<Event> slowSubscriber;

    //慢订阅者类
    class SlowSubscriber extends BaseSubscriber<Event> {

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // 订阅时请求1个数据
            request(1);
        }

        @Override
        protected void hookOnNext(Event event) {
            System.out.println("         consumer <<< " + event.getName() + "  " + event.getId());
            try {
                // 每次停顿30ms后要求上游发起请求
                //订阅者处理每个元素的时间，单位毫秒
                TimeUnit.MILLISECONDS.sleep(30);
            } catch (InterruptedException ignored) {
            }
            //每处理完1个数据，就再请求1个
            request(Integer.MAX_VALUE);
//            request(1);
        }

        @Override
        protected void hookOnError(Throwable throwable) {
        }

        @Override
        protected void hookOnComplete() {
            countDownLatch.countDown();
        }
    }

    public Flux<Event> createFlux(FluxSink.OverflowStrategy strategy) {
        return Flux.create(fluxSink -> eventSource.addListener(new EventListener() {
            @Override
            public void onEvent(Event event) {
                System.out.println("publish >>> " + event.getName());
                fluxSink.next(event);
            }

            @Override
            public void onStop() {
                fluxSink.complete();
            }
        }), strategy);
    }

    // 循环生成MyEvent，每个MyEvent间隔millis毫秒
    private void generateEvent() {
        //生成20个事件, 每个事件间隔10ms
        for (int i = 0; i < 20; i++) {
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException ignored) {
            }
            eventSource.onEvent(new Event(Thread.currentThread().getName(), i));
        }
        eventSource.onStop();
    }

    @Test
    public void testCreateBackPressureStrategy() throws InterruptedException {
        countDownLatch = new CountDownLatch(1);
        slowSubscriber = new SlowSubscriber();
        eventSource = new EventSource();
        // 1. subscriber.request(1)，下游向上游拉取1个请求
        // 2. 触发 doOnRequest，打印request:n 这里的n就是一次拉取request的数量，即prefetch值
        Flux<Event> fastPublisher = createFlux(FluxSink.OverflowStrategy.DROP)
                .doOnRequest(n -> log.info("         ===  request: " + n + " ==="))
                // publishOn 让生产者在main线程执行，消费者在newSingle线程里执行，不会相互阻塞
                // 这样生产者可以一直生产，不需要依赖消费者的消费速度
                // 生产者是10ms生产，消费者是30ms消费，除了第一个消息是立即消费，其他都是3个消息积压
                // prefetch 是指一次拉去上游N个请求并保存的数量， 生产超过部分会被丢弃
                .publishOn(Schedulers.newSingle("newSingle"), 1);
        fastPublisher.subscribe(slowSubscriber);
        generateEvent();
        countDownLatch.await(1, TimeUnit.MINUTES);
    }

    // 验证publishOn是用来切换下游的执行线程
    @Test
    public void publishOnTest() {
        Flux.range(1,2)
                .map(i -> {
                    // 在主线程执行
                    log.info("Map 1, the value map to: {}", i*i);
                    return i*i;
                })
                .publishOn(Schedulers.single())
                .map(i -> {
                    // 在single线程执行
                    log.info("Map 2, the value map to: {}", -i);
                    return -i;
                })
                .publishOn(Schedulers.newParallel("parallel", 4))
                .map(i -> {
                    // 在parallel线程执行
                    log.info("Map 3, the value map to: {}", i+2);
                    return (i+2) + "";
                })
                .subscribe();
    }

	@Test
    public void testCompose() {
        AtomicInteger ai = new AtomicInteger();
        Function<Flux<String>, Flux<String>> filterAndMap = f -> {
            System.out.println("ai:" + ai.get());
            if (ai.incrementAndGet() == 1) {
                return f.filter(color -> !color.equals("orange"))
                        .map(String::toUpperCase);
            }
            return f.filter(color -> !color.equals("purple"))
                    .map(String::toUpperCase);
        };
        Flux<String> composedFlux =
                Flux.fromIterable(Arrays.asList("blue", "green", "orange", "purple"))
                        .doOnNext(System.out::println)
                        //为每个subscriber 生成一个新的original sequence
                        //original sequence的内容为compose()前面部分
                        // transform是初始化时候执行的，compose是在每次subscribe时候重新执行一遍
                        // compose后续版本又叫DeferTransform，即在subscribe阶段执行
                        .compose(filterAndMap);

        composedFlux.subscribe(d -> System.out.println("Subscriber 1 to Composed MapAndFilter :" + d));
        composedFlux.subscribe(d -> System.out.println("Subscriber 2 to Composed MapAndFilter: " + d));
    }
}
