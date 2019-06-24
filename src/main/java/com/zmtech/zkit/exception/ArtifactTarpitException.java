package com.zmtech.zkit.exception;


/** 当组件tarpit被击中时抛出，对组件的使用太多。 */
public class ArtifactTarpitException extends BaseArtifactException {

    private Integer retryAfterSeconds = null;

    public ArtifactTarpitException(String str) { super(str); }
    public ArtifactTarpitException(String str, Throwable nested) { super(str, nested); }
    public ArtifactTarpitException(String str, Integer retryAfterSeconds) {
        super(str);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
}
