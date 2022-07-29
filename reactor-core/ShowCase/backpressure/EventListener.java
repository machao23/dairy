package com.rinbo.reactor.backpressure;

public interface EventListener {
    void onEvent(Event event);

    void onStop();
}
