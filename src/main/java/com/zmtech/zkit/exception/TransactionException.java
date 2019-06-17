
package com.zmtech.zkit.exception;

/**
 * TransactionException
 */
public class TransactionException extends RuntimeException {
    public TransactionException(String str) { super(str); }
    public TransactionException(String str, Throwable nested) { super(str, nested); }
}
