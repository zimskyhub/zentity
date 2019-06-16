package com.zmtech.zframework.util;

public interface SimpleTopic<E> {
    void publish(E message);
}
