package com.zmtech.zentity.util;

public interface SimpleTopic<E> {
    void publish(E message);
}
