package com.zmtech.zkit.exception;

/**
 * ServiceFacade Exception
 */
public class ServiceException extends BaseException {

    public ServiceException(String str) {
        super(str);
    }

    public ServiceException(String str, Throwable nested) {
        super(str, nested);
    }
}
