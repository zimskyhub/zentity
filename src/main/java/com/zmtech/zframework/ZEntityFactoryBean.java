package com.zmtech.zframework;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

public class ZEntityFactoryBean implements FactoryBean<ZEntityFacade>, InitializingBean, ApplicationListener<ApplicationEvent> {

    public ZEntityFacade getObject() throws Exception {
        ZEntityFacade zf= new ZEntityFacade();

        return zf;
    }

    public Class<?> getObjectType() {
        return ZEntityFacade.class;
    }

    public boolean isSingleton() {
        return true;
    }

    public void afterPropertiesSet() throws Exception {

    }

    public void onApplicationEvent(ApplicationEvent applicationEvent) {

    }
}
