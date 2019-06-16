package com.zmtech.zframework.resource.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClasspathResourceReference extends UrlResourceReference {
    private static final Logger logger = LoggerFactory.getLogger(ClasspathResourceReference.class);
    private String strippedLocation;

    public ClasspathResourceReference() { super(); }

    @Override
    public ResourceReference init(String location) {
        strippedLocation = ResourceReference.stripLocationPrefix(location);
        // first try the current thread's context ClassLoader
        locationUrl = Thread.currentThread().getContextClassLoader().getResource(strippedLocation);
        // next try the ClassLoader that loaded this class
        if (locationUrl == null) locationUrl = this.getClass().getClassLoader().getResource(strippedLocation);
        // no luck? try the system ClassLoader
        if (locationUrl == null) locationUrl = ClassLoader.getSystemResource(strippedLocation);
        // if the URL was found this way then it exists, so remember that
        if (locationUrl != null) {
            exists = true;
            isFileProtocol = "file".equals(locationUrl.getProtocol());
        } else {
            logger.warn("Could not find location [" + strippedLocation + "] on the classpath");
        }

        return this;
    }

    @Override
    public ResourceReference createNew(String location) {
        ClasspathResourceReference resRef = new ClasspathResourceReference();
        resRef.init(location);
        return resRef;
    }

    @Override
    public String getLocation() { return "classpath://" + strippedLocation; }
}
