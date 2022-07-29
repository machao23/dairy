package com.rinbo.reactor.backpressure;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Event {
    private String name;
    private int id;
}