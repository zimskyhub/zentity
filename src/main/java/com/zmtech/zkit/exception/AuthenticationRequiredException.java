
package com.zmtech.zkit.exception;

/** 当组件或操作需要身份验证且没有用户登录时抛出。 */
public class AuthenticationRequiredException extends BaseArtifactException {
    public AuthenticationRequiredException(String str) { super(str); }
    public AuthenticationRequiredException(String str, Throwable nested) { super(str, nested); }
}
