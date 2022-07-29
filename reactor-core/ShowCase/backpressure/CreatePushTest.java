package com.rinbo.reactor.backpressure;

import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

public class CreatePushTest {

    @Test
    public void testCreate() {
        EventSource eventSource = new EventSource();
        Flux.create(fluxSink -> {
            eventSource.addListener(new EventListener() {
                @Override
                public void onEvent(Event event) {
                    fluxSink.next(event);
                }

                @Override
                public void onStop() {
                    fluxSink.complete();
                }
            });
        },FluxSink.OverflowStrategy.DROP).subscribe(System.out::println);

        for (int i = 0; i < 10; i++) {
            eventSource.onEvent(new Event(Thread.currentThread().getName(), i));
        }
    }

    @Test
    public void testPush() {
        EventSource eventSource = new EventSource();
        Flux.push(fluxSink -> {
            eventSource.addListener(new EventListener() {
                @Override
                public void onEvent(Event event) {
                    fluxSink.next(event);
                }

                @Override
                public void onStop() {
                    fluxSink.complete();
                }
            });
        }).subscribe(System.out::println);

        for (int i = 0; i < 10; i++) {
            eventSource.onEvent(new Event(Thread.currentThread().getName(), i));
        }
    }


}
