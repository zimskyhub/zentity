package com.zmtech.zframework.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zframework")
public class ZEntityProperties {
    private String test;

    public String getTest() {
        return test;
    }

    public void setTest(String test) {
        this.test = test;
    }
}
