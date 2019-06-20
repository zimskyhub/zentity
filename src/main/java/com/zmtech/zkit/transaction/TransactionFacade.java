package com.zmtech.zkit.transaction;

import com.zmtech.zkit.exception.TransactionException;
import com.zmtech.zkit.transaction.impl.TransactionCache;

import javax.transaction.Synchronization;
import javax.transaction.xa.XAResource;
import java.util.function.Function;

/**
 * 使用此接口进行事务划分和相关操作。
 * 使用它代替使用JTA UserTransaction和TransactionManager接口。
 * 当您进行事务划分时，请这样使用:
 * boolean beganTransaction = transactionFacade.begin(timeout);
 * try {
 *     ...
 * } catch (Throwable t) {
 *     transactionFacade.rollback(beganTransaction, "...", t);
 *     throw t;
 * } finally {
 *     if (transactionFacade.isTransactionInPlace()) transactionFacade.commit(beganTransaction);
 * }
 * 如果已经存在一个事务（包括setRollbackOnly而不是rollbackon错误），则此代码将使用事务，否则将开始新事务。
 * 当你想暂停当前事务并创建一个新事务时，请这样使用:
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
 */
@SuppressWarnings("unused")
public interface TransactionFacade {

    /** 如果有的话，在当前事务中运行，如果没有，则开始并提交/回滚。 */
    Object runUseOrBegin(Integer timeout, String rollbackMessage,  Function<Boolean,Boolean> function);
    /** 运行一个隔离事务,即使当前已有事务了 */
    Object runRequireNew(Integer timeout, String rollbackMessage, Function<Boolean,Boolean> function);

    TransactionInternal getTransactionInternal();

    javax.transaction.TransactionManager getTransactionManager();
    javax.transaction.UserTransaction getUserTransaction();

    /** 获取当前事务的状态*/
    int getStatus() throws TransactionException;

    String getStatusString() throws TransactionException;

    boolean isTransactionInPlace() throws TransactionException;

    /**
     * 仅在当前事务状态不是ACTIVE时 在当前线程中开始一个事务。
     * 如果ACTIVE，则返回false，因为没有开始事务。
     *
     * @param timeout 超时的可选整数。 如果为null，则将使用默认配置。
     * @return 如果事务开始则为true，否则为false。
     * @throws TransactionException
     */
    boolean begin(Integer timeout) throws TransactionException;

    /** 如果startsTransaction为true，则在当前线程中提交事务 */
    void commit(boolean beganTransaction) throws TransactionException;

    /** 在当前线程中提交事务 */
    void commit() throws TransactionException;

    /**
     * 如果startsTransaction为true，则回滚当前事务，否则调用setRollbackOnly将当前事务标记为仅回滚。
     */
    void rollback(boolean beganTransaction, String causeMessage, Throwable causeThrowable) throws TransactionException;

    /** 回滚当前事务 */
    void rollback(String causeMessage, Throwable causeThrowable) throws TransactionException;

    /** 将当前事务标记为仅回滚（事务只能回滚） */
    void setRollbackOnly(String causeMessage, Throwable causeThrowable) throws TransactionException;

    /** 将当前事务挂起 */
    boolean suspend() throws TransactionException;

    /** 启动挂起的事务 */
    void resume() throws TransactionException;
    /** 登记连接 */
    java.sql.Connection enlistConnection(javax.sql.XAConnection con) throws TransactionException;
    /** 登记资源 */
    void enlistResource(XAResource resource) throws TransactionException;
    XAResource getActiveXaResource(String resourceName);
    void putAndEnlistActiveXaResource(String resourceName, XAResource xar);
    /** 事务同步注册 */
    void registerSynchronization(Synchronization sync) throws TransactionException;
    Synchronization getActiveSynchronization(String syncName);
    void putAndEnlistActiveSynchronization(String syncName, Synchronization sync);
    /** 初始化事务缓存 */
    void initTransactionCache();
    /** 判断事务缓存是否启动 */
    boolean isTransactionCacheActive();
    void flushAndDisableTransactionCache();
    /** 删除线程中所有事务 */
    void destroyAllInThread();
    /** 取事务缓存 */
    TransactionCache getTransactionCache();
    /** 取当前事务的开始时间 */
    Long getCurrentTransactionStartTime();
}
