package com.rinbo.reactor.backpressure;

import java.util.ArrayList;
import java.util.List;

public class EventSource {
    private List<EventListener> listeners;

    public EventSource() {
        listeners = new ArrayList<>();
    }

    public void onEvent(Event event) {
        for (EventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    public void onStop() {
        for (EventListener listener : listeners) {
            listener.onStop();
        }
    }

    public void addListener(EventListener listener) {
        this.listeners.add(listener);
    }
}
