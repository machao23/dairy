package com.greek.reactorstart;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author lanruqi
 * @date 2020/6/8
 */
@DisplayName("创建数据流")
public class CreateDemo {

    int a = 5;
    @Test
    @DisplayName("比较defer和just的区别")
    public void deferVSJust() {
        Mono<Integer> monoJust = Mono.just(a);
        Mono<Integer> monoDefer = Mono.defer(() -> Mono.just(a));
        monoJust.subscribe(System.out::print);
        monoDefer.subscribe(System.out::print);
        a = 7;
        // just的值是跟着初始化不变了，但是defer会跟着变量变化
        monoJust.subscribe(System.out::print);
        monoDefer.subscribe(System.out::print);
    }

    @Test
    @DisplayName("defer延迟创建一个数据流")
    public void defer() {
        AtomicInteger c = new AtomicInteger();
        Mono<String> source = Mono.defer(() -> c.getAndIncrement() < 3 ? Mono.empty() : Mono.just("test-data"));
        List<Long> iterations = new ArrayList<>();
        source.repeatWhenEmpty(o -> o.doOnNext(iterations::add)).subscribe(System.out::println);

        Assertions.assertEquals(4, c.get());
        Assertions.assertEquals(3,iterations.size());
    }

    @Test
    @DisplayName("从一个可回收的资源中创建数据源")
    public void using() {
        AtomicInteger cleanup = new AtomicInteger();
        /*
          Flux.using(resourceSupplier,sourceSupplier,resourceCleanup,eager)
          resourceSupplier 在订阅时被调用以生成资源
          sourceSupplier 提供一个从提供的创造资源的工厂
          resourceCleanup 完成时调用的资源清理回调
          eager  是否在终止下游订户之前进行清理
         */
        Flux<Integer> using = Flux.using(() -> 1, r -> Flux.range(r, 10), cleanup::set, false);

        StepVerifier.create(using)
                .expectNext(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                .verifyComplete();
        Assertions.assertEquals(1, cleanup.get());
    }

    @Test
    @DisplayName("IGNORE策略--与DROP策略对比")
    void fluxCreateIgnoreBackPressured1() {
        // ignore会跳过积压，直接输出1,2,3
        Flux.<String>create(s -> {
            assertThat(s.requestedFromDownstream()).isEqualTo(1);
            s.next("test1");
            s.next("test2");
            s.next("test3");
            s.complete();
        }, FluxSink.OverflowStrategy.IGNORE)
                .subscribe(System.out::println, null, null, subscription -> subscription.request(1));


        Flux.<String>create(s -> {
            assertThat(s.requestedFromDownstream()).isEqualTo(1);
            s.next("test5");
            s.next("test6");
            s.next("test7");
            s.complete();
        }, FluxSink.OverflowStrategy.DROP)
                .subscribe(System.out::println, null, null, subscription -> subscription.request(1));
    }
}
