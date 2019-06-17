package com.zmtech.zkit.util;

public interface SimpleTopic<E> {
    void publish(E message);
}
