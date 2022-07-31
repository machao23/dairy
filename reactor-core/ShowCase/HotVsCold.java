package com.rinbo.reactor;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.UnicastProcessor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HotVsCold {

    @Test
    public void testHotSequence() {
        //热发布者
        UnicastProcessor<String> hostSource = UnicastProcessor.create();
        Flux<String> hotFlux = hostSource.publish().autoConnect().map(String::toUpperCase);
        // 虽然B41和B42是在第一个subscribe之前触发的，但它们会保留，直到subscribe1消费
        hostSource.onNext("b41");
        hostSource.onNext("b42");

        hotFlux.subscribe(d -> log.info("Subscribe 1:" + d));
        //使用onNext方法手动发出元素
        hostSource.onNext("aa");
        hostSource.onNext("bb");
        // hot生产者的情况，aa和bb是在subscribe2前触发的，所以不会在subscribe2里消费
        // cold生产者不会有这个问题，每次有新的subscribe会重新触发publish
        hotFlux.subscribe(d -> log.info("Subscriber 2 to Hot Source: " + d));

        hostSource.onNext("orange");
        hostSource.onNext("purple");
        hostSource.onComplete();
    }


    @Test
    public void testConnectableFlux() throws InterruptedException {
        Flux<Integer> source = Flux.range(1, 3).doOnSubscribe(s -> log.info("上游收到订阅"));

        ConnectableFlux<Integer> co = source.publish();
        co.subscribe(System.out::println, e -> {
        }, () -> {
        });
        co.subscribe(System.out::println, e -> {
        }, () -> {
        });
        log.info("订阅者完成订阅操作");
        Thread.sleep(500);
        log.info("还没有连接上");
        //当connect的时候，上游才真正收到订阅请求
        co.connect();
    }

    @Test
    public void testConnectableFluxAutoConnect() throws InterruptedException {
        Flux<Integer> source = Flux.range(1, 3)
                .doOnSubscribe(s -> log.info("上游收到订阅"));
        Flux<Integer> autoCo = source.publish().autoConnect(2);
        autoCo.subscribe(System.out::println, e -> {
        }, () -> {
        });
        log.info("第一个订阅者完成订阅操作");
        Thread.sleep(500);
        log.info("第二个订阅者完成订阅操作");
        //只有两个订阅者都完成订阅之后，上游才收到订阅请求，并开始发出数据
        autoCo.subscribe(System.out::println, e -> {
        }, () -> {
        });
    }

    @Test
    public void testConnectableFluxRefConnect() throws InterruptedException {
        // 间隔500ms生产一个计数
        Flux<Long> refCounted = Flux.interval(Duration.ofMillis(500))
                .doOnSubscribe(s -> log.info("上游收到订阅"))
                .doOnCancel(() -> log.info("上游发布者断开连接"))
                //当所有订阅者都取消时，如果不能在两秒内接入新的订阅者，则上游会断开连接
                // refCount的执行
                .publish().refCount(2, Duration.ofSeconds(2));
        log.info("第一个订阅者订阅");
        // 因为 refCounted需要2个订阅方起开始发布，所以此时sub1还没有消费
        Disposable sub1 = refCounted.subscribe(l -> log.info("sub1: " + l));
        log.info("第二个订阅者订阅");
        Disposable sub2 = refCounted.subscribe(l -> log.info("sub2: " + l));
        // sub1和sub2都已经连接，开始发布，收到第一个请求0
        TimeUnit.SECONDS.sleep(2    );
        log.info("第一个订阅者取消订阅");
        sub1.dispose();

        // 为什么第一个订阅者取消后，第二个订阅者还能消费？
        // 是因为refCount里的minSubscriber只和触发订阅有关系？和保持生产没关系？
        TimeUnit.SECONDS.sleep(2);
        log.info("第二个订阅者取消订阅");
        sub2.dispose();

        TimeUnit.SECONDS.sleep(1);
        log.info("第三个订阅者订阅");
        Disposable sub3 = refCounted.subscribe(l -> log.info("sub3: " + l));

        TimeUnit.SECONDS.sleep(1);
        log.info("第三个订阅者取消订阅");
        sub3.dispose();

        //第四个订阅者没能在2秒内开始订阅，所以上游发布者断开连接
        TimeUnit.SECONDS.sleep(3);
        log.info("第四个订阅者订阅");
        Disposable sub4 = refCounted.subscribe(l -> log.info("sub4: " + l));
        TimeUnit.SECONDS.sleep(1);
        log.info("第五个订阅者订阅");
        Disposable sub5 = refCounted.subscribe(l -> log.info("sub5: " + l));
        TimeUnit.SECONDS.sleep(2);
    }

    @Test
    public void testConnectableFluxRepay() throws InterruptedException {
        CountDownLatch cd = new CountDownLatch(1);
        Flux<Long> source = Flux.interval(Duration.ofSeconds(1))
                .doOnSubscribe(s -> log.info("上游收到订阅"));
        //replay缓存最新数据
        Flux<Long> replay = source.replay(4).autoConnect();
        replay.subscribe(l -> log.info("sub1: " + l), System.err::println, cd::countDown);
        TimeUnit.SECONDS.sleep(10);
        replay.subscribe(l -> log.info("sub2: " + l), System.err::println, cd::countDown);
        cd.await();
    }
}
