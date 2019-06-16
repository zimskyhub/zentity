package com.zmtech.zframework.transaction;

import com.zmtech.zframework.exception.TransactionException;
import groovy.lang.Closure;

import javax.transaction.Synchronization;
import javax.transaction.xa.XAResource;

/** Use this interface to do transaction demarcation and related operations.
 * This should be used instead of using the JTA UserTransaction and TransactionManager interfaces.
 *
 * When you do transaction demarcation yourself use something like:
 *
 * <pre>
 * boolean beganTransaction = transactionFacade.begin(timeout);
 * try {
 *     ...
 * } catch (Throwable t) {
 *     transactionFacade.rollback(beganTransaction, "...", t);
 *     throw t;
 * } finally {
 *     if (transactionFacade.isTransactionInPlace()) transactionFacade.commit(beganTransaction);
 * }
 * </pre>
 *
 * This code will use a transaction if one is already in place (including setRollbackOnly instead of rollbackon
 * error), or begin a new one if not.
 *
 * When you want to suspend the current transaction and create a new one use something like: 
 *
 * <pre>
 * boolean suspendedTransaction = false;
 * try {
 *     if (transactionFacade.isTransactionInPlace()) suspendedTransaction = transactionFacade.suspend();
 *
 *     boolean beganTransaction = transactionFacade.begin(timeout);
 *     try {
 *         ...
 *     } catch (Throwable t) {
 *         transactionFacade.rollback(beganTransaction, "...", t);
 *         throw t;
 *     } finally {
 *         if (transactionFacade.isTransactionInPlace()) transactionFacade.commit(beganTransaction);
 *     }
 * } catch (TransactionException e) {
 *     ...
 * } finally {
 *     if (suspendedTransaction) transactionFacade.resume();
 * }
 * </pre>
 */
@SuppressWarnings("unused")
public interface TransactionFacade {

    /** Run in current transaction if one is in place, begin and commit/rollback if none is. */
    Object runUseOrBegin(Integer timeout, String rollbackMessage, Closure closure);
    /** Run in a separate transaction, even if one is in place. */
    Object runRequireNew(Integer timeout, String rollbackMessage, Closure closure);

    javax.transaction.TransactionManager getTransactionManager();
    javax.transaction.UserTransaction getUserTransaction();

    /** Get the status of the current transaction */
    int getStatus() throws TransactionException;

    String getStatusString() throws TransactionException;

    boolean isTransactionInPlace() throws TransactionException;

    /** Begins a transaction in the current thread. Only tries if the current transaction status is not ACTIVE, if
     * ACTIVE it returns false since no transaction was begun.
     *
     * @param timeout Optional Integer for the timeout. If null the default configured will be used.
     * @return True if a transaction was begun, otherwise false.
     * @throws TransactionException
     */
    boolean begin(Integer timeout) throws TransactionException;

    /** Commits the transaction in the current thread if beganTransaction is true */
    void commit(boolean beganTransaction) throws TransactionException;

    /** Commits the transaction in the current thread */
    void commit() throws TransactionException;

    /** Rollback current transaction if beganTransaction is true, otherwise setRollbackOnly is called to mark current
     * transaction as rollback only.
     */
    void rollback(boolean beganTransaction, String causeMessage, Throwable causeThrowable) throws TransactionException;

    /** Rollback current transaction */
    void rollback(String causeMessage, Throwable causeThrowable) throws TransactionException;

    /** Mark current transaction as rollback-only (transaction can only be rolled back) */
    void setRollbackOnly(String causeMessage, Throwable causeThrowable) throws TransactionException;

    boolean suspend() throws TransactionException;

    void resume() throws TransactionException;

    java.sql.Connection enlistConnection(javax.sql.XAConnection con) throws TransactionException;

    void enlistResource(XAResource resource) throws TransactionException;
    XAResource getActiveXaResource(String resourceName);
    void putAndEnlistActiveXaResource(String resourceName, XAResource xar);

    void registerSynchronization(Synchronization sync) throws TransactionException;
    Synchronization getActiveSynchronization(String syncName);
    void putAndEnlistActiveSynchronization(String syncName, Synchronization sync);

    void initTransactionCache();
    boolean isTransactionCacheActive();
    void flushAndDisableTransactionCache();
}
