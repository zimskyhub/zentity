package com.zmtech.zkit.transaction.impl;

import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.context.impl.ExecutionContextImpl;
import com.zmtech.zkit.exception.BaseException;
import com.zmtech.zkit.exception.EntityException;
import com.zmtech.zkit.exception.TransactionException;
import com.zmtech.zkit.transaction.TransactionFacade;
import com.zmtech.zkit.transaction.TransactionInternal;
import com.zmtech.zkit.util.ContextJavaUtil.*;
import com.zmtech.zkit.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.XAConnection;
import javax.transaction.*;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;


public class TransactionFacadeImpl implements TransactionFacade {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionFacadeImpl.class);
    protected final static boolean isTraceEnabled = logger.isTraceEnabled();

    protected final ExecutionContextFactoryImpl ecfi;

    protected TransactionInternal transactionInternal = null;

    protected UserTransaction ut;
    protected TransactionManager tm;

    protected boolean useTransactionCache = true;
    protected boolean useConnectionStash = true;

    private ThreadLocal<TxStackInfo> txStackInfoCurThread = new ThreadLocal<>();
    private ThreadLocal<LinkedList<TxStackInfo>> txStackInfoListThread = new ThreadLocal<>();

    public TransactionFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi;

        MNode transactionFacadeNode = ecfi.getConfXmlRoot().first("transaction-facade");
        if (transactionFacadeNode.hasChild("transaction-jndi")) {
            this.populateTransactionObjectsJndi();
        } else if (transactionFacadeNode.hasChild("transaction-internal")) {
            // initialize internal
            MNode transactionInternalNode = transactionFacadeNode.first("transaction-internal");
            String tiClassName = transactionInternalNode.attribute("class");
            try {
                transactionInternal = (TransactionInternal) Thread.currentThread().getContextClassLoader()
                        .loadClass(tiClassName).newInstance();
            } catch (Exception e) {
                throw new IllegalArgumentException("事务操作错误: 无法加载内部事务类 ["+tiClassName+"]!");
            }
            transactionInternal.init(ecfi);

            ut = transactionInternal.getUserTransaction();
            tm = transactionInternal.getTransactionManager();
        } else {
            throw new IllegalArgumentException("事务操作错误: 配置中没有 jndi 事务或者 内部事务!");
        }

        if (transactionFacadeNode.attribute("use-transaction-cache").equals("false")) useTransactionCache = false;
        if (transactionFacadeNode.attribute("use-connection-stash").equals( "false")) useConnectionStash = false;
    }

    public void destroy() {
        // 首先设置为null以避免其他操作
        this.tm = null;
        this.ut = null;

        // 如果适用，销毁内部; JNDI不需要
        if (transactionInternal != null) transactionInternal.destroy();

        txStackInfoCurThread.remove();
        txStackInfoListThread.remove();
    }

    /**
     * 调用此方法以确保线程中所有事务关闭。
     * 提交任何活动事务，清除线程的内部数据等。
     */
    public void destroyAllInThread() {
        if (isTransactionInPlace()) {
            logger.warn("事务操作警告: 线程内含有未提交事务, 试图提交。");
            commit();
        }

        LinkedList<TxStackInfo> txStackInfoList = txStackInfoListThread.get();
        if (txStackInfoList != null && txStackInfoList.size() > 0) {
            int numSuspended = 0;
            for (TxStackInfo txStackInfo : txStackInfoList) {
                Transaction tx = txStackInfo.suspendedTx;
                if (tx != null) {
                    resume();
                    commit();
                    numSuspended++;
                }
            }
            if (numSuspended > 0) logger.warn("事务操作警告: 清空 [" + numSuspended + "] 个挂起事务.");
        }

        txStackInfoCurThread.remove();
        txStackInfoListThread.remove();
    }

    @Override
    public TransactionInternal getTransactionInternal() { return transactionInternal; }

    public TransactionManager getTransactionManager() { return tm; }

    public UserTransaction getUserTransaction() { return ut; }
    public Long getCurrentTransactionStartTime() {
        TxStackInfo txStackInfo = getTxStackInfo();
        Long time = txStackInfo != null ? txStackInfo.transactionBeginStartTime : (Long) null;
        if (time == null && isTraceEnabled) logger.trace("事务操作跟踪: 没有事务开始时间, 事务是否就位? ["+this.isTransactionInPlace()+"]", new EntityException("空事务起始堆栈列表"));
        return time;
    }

    protected LinkedList<TxStackInfo> getTxStackInfoList() {
        LinkedList<TxStackInfo> list = txStackInfoListThread.get();
        if (list == null) {
            list = new LinkedList<>();
            txStackInfoListThread.set(list);
            TxStackInfo txStackInfo = new TxStackInfo();
            list.add(txStackInfo);
            txStackInfoCurThread.set(txStackInfo);
        }
        return list;
    }
    protected TxStackInfo getTxStackInfo() {
        TxStackInfo txStackInfo = txStackInfoCurThread.get();
        if (txStackInfo == null) {
            LinkedList<TxStackInfo> list = getTxStackInfoList();
            txStackInfo = list.getFirst();
        }
        return txStackInfo;
    }
    protected void pushTxStackInfo(Transaction tx, Exception txLocation) {
        TxStackInfo txStackInfo = new TxStackInfo();
        txStackInfo.suspendedTx = tx;
        txStackInfo.suspendedTxLocation = txLocation;
        getTxStackInfoList().addFirst(txStackInfo);
        txStackInfoCurThread.set(txStackInfo);
    }
    protected void popTxStackInfo() {
        LinkedList<TxStackInfo> list = getTxStackInfoList();
        list.removeFirst();
        txStackInfoCurThread.set(list.getFirst());
    }


    @Override
    public Object runUseOrBegin(Integer timeout, String rollbackMessage, Function<Boolean,Boolean> function) {
        if (rollbackMessage == null) rollbackMessage = "";
        boolean beganTransaction = begin(timeout);
        try {
            return function.apply(true);
        } catch (Throwable t) {
            rollback(beganTransaction, rollbackMessage, t);
            throw t;
        } finally {
            commit(beganTransaction);
        }
    }
    @Override
    public Object runRequireNew(Integer timeout, String rollbackMessage, Function<Boolean,Boolean> function) {
        return runRequireNew(timeout, rollbackMessage, true, true, function);
    }
    protected final static boolean requireNewThread = true;
    public Object runRequireNew(Integer timeout, String rollbackMessage, boolean beginTx, boolean threadReuseEci, Function<Boolean,Boolean> function) {
        AtomicReference<Object> result = new AtomicReference<>();
        if (requireNewThread) {
            // if there is a timeout for this thread wait 10x the timeout (so multiple seconds by 10k instead of 1k)
            long threadWait = timeout != null ? timeout * 10000 : 60000;

            Thread txThread = null;
            ExecutionContextImpl eci = ecfi.getEci();
            AtomicReference<Throwable> threadThrown = new AtomicReference<>();

            try {
                txThread = new Thread(()->{
                    if (threadReuseEci) ecfi.useExecutionContextInThread(eci);
                    try {
                        if (beginTx) {
                            result.set(runUseOrBegin(timeout, rollbackMessage, function));
                        } else {
                            result.set(function.apply(true));
                        }
                    } catch (Throwable t) {

                        threadThrown.set(t);
                    }
                },"RequireNewTx");

                txThread.start();

            } finally {
                if (txThread != null) {
                    try {
                        txThread.join(threadWait);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (txThread.getState() != Thread.State.TERMINATED) {
                        // TODO: do more than this?
                        logger.warn("事务操作警告: 新的事务线程没有终止, 线程状态 ["+txThread.getState()+"]");
                    }
                }
            }
            if (threadThrown.get() != null) try {
                throw threadThrown.get();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        } else {
            boolean suspendedTransaction = false;
            try {
                if (isTransactionInPlace()) suspendedTransaction = suspend();
                if (beginTx) {
                    result.set(runUseOrBegin(timeout, rollbackMessage, function));
                } else {
                    result.set(function.apply(true));
                }
            } finally {
                if (suspendedTransaction) resume();
            }
        }
        return result.get();
    }

    @Override
    public XAResource getActiveXaResource(String resourceName) {
        return getTxStackInfo().getActiveXaResourceMap().get(resourceName);
    }
    @Override
    public void putAndEnlistActiveXaResource(String resourceName, XAResource xar) {
        enlistResource(xar);
        getTxStackInfo().getActiveXaResourceMap().put(resourceName, xar);
    }

    @Override
    public Synchronization getActiveSynchronization(String syncName) {
        return getTxStackInfo().getActiveSynchronizationMap().get(syncName);
    }
    @Override
    public void putAndEnlistActiveSynchronization(String syncName, Synchronization sync) {
        registerSynchronization(sync);
        getTxStackInfo().getActiveSynchronizationMap().put(syncName, sync);
    }


    @Override
    public int getStatus() {
        if (ut == null) return Status.STATUS_NO_TRANSACTION;
        try {
            return ut.getStatus();
        } catch (SystemException e) {
            throw new TransactionException("事务操作错误: 系统错误, 无法获得事务状态", e);
        }
    }

    @Override
    public String getStatusString() {
        int statusInt = getStatus();
        /*
         * javax.transaction.Status
         * STATUS_ACTIVE           0
         * STATUS_MARKED_ROLLBACK  1
         * STATUS_PREPARED         2
         * STATUS_COMMITTED        3
         * STATUS_ROLLEDBACK       4
         * STATUS_UNKNOWN          5
         * STATUS_NO_TRANSACTION   6
         * STATUS_PREPARING        7
         * STATUS_COMMITTING       8
         * STATUS_ROLLING_BACK     9
         */
        switch (statusInt) {
            case Status.STATUS_ACTIVE:
                return "活动中 ("+statusInt+")";
            case Status.STATUS_COMMITTED:
                return "已提交 ("+statusInt+")";
            case Status.STATUS_COMMITTING:
                return "提交中 ("+statusInt+")";
            case Status.STATUS_MARKED_ROLLBACK:
                return "标记回滚 ("+statusInt+")";
            case Status.STATUS_NO_TRANSACTION:
                return "没有事务 ("+statusInt+")";
            case Status.STATUS_PREPARED:
                return "已准备 ("+statusInt+")";
            case Status.STATUS_PREPARING:
                return "准备中 ("+statusInt+")";
            case Status.STATUS_ROLLEDBACK:
                return "已回滚 ("+statusInt+")";
            case Status.STATUS_ROLLING_BACK:
                return "回滚中 ("+statusInt+")";
            case Status.STATUS_UNKNOWN:
                return "未知状态 ("+statusInt+")";
            default:
                return "状态码无效 ("+statusInt+")";
        }
    }

    @Override
    public boolean isTransactionInPlace() { return getStatus() != Status.STATUS_NO_TRANSACTION; }

    public boolean isTransactionActive() { return getStatus() == Status.STATUS_ACTIVE; }
    public boolean isTransactionOperable() {
        int curStatus = getStatus();
        return curStatus == Status.STATUS_ACTIVE || curStatus == Status.STATUS_NO_TRANSACTION;
    }

    @Override
    public boolean begin(Integer timeout) throws TransactionException {
        int currentStatus = 0;
        try {
            currentStatus = ut.getStatus();
        } catch (SystemException e) {
            logger.error("事务操作错误: 无法获取事务状态!");
        }
        // logger.warn("================ begin TX, currentStatus=${currentStatus}", new BaseException("beginning transaction at"))

        if (currentStatus == Status.STATUS_ACTIVE) {
            // 不开始事务，并返回false，让调用者知道我们没有开始事务
            return false;
        } else if (currentStatus == Status.STATUS_MARKED_ROLLBACK) {
            TxStackInfo txStackInfo = getTxStackInfo();
            if (txStackInfo.transactionBegin != null) {
                logger.warn("事务操作警告: 当前事务已经标记为回滚,没有事务启动. 堆栈跟踪显示事务开始的位置: ", txStackInfo.transactionBegin);
            } else {
                logger.warn("事务操作警告: 当前事务已经标记为回滚,没有事务启动 （注意：没有堆栈跟踪来显示事务开始的位置）.");
            }
            if (txStackInfo.rollbackOnlyInfo != null) {
                logger.warn("事务操作警告: 当前事务已经标记为回滚,没有新事务启动. 事务回滚未知: ", txStackInfo.rollbackOnlyInfo.rollbackLocation);
                throw new TransactionException( "事务操作错误: 当前事务已经标记为回滚, 没有事务启动. 事务回滚原因: " + txStackInfo.rollbackOnlyInfo.causeMessage, txStackInfo.rollbackOnlyInfo.causeThrowable);
            } else {
                return false;
            }
        }

        try {
            // 注意：由于JTA 1.1 setTransactionTimeout（）是本地线程，因此不需要同步。
            if (timeout != null) ut.setTransactionTimeout(timeout);
            ut.begin();

            TxStackInfo txStackInfo = getTxStackInfo();
            txStackInfo.transactionBegin = new Exception("事务操作错误: 事务开始占位符");
            txStackInfo.transactionBeginStartTime = System.currentTimeMillis();
            // logger.warn("================ begin TX, getActiveSynchronizationStack()=${getActiveSynchronizationStack()}")

            if (txStackInfo.txCache != null) logger.warn("事务操作浸膏: 想要开始事务, 事务缓存不能为 Null!");
            /* 未来：这是一个有趣的可能性，总是在只读模式下使用tx缓存，但目前会导致问题（需要更多工作缓存清除等）
            if (useTransactionCache) {
                txStackInfo.txCache = new TransactionCache(ecfi, true)
                registerSynchronization(txStackInfo.txCache)
            }
            */

            return true;
        } catch (NotSupportedException e) {
            throw new TransactionException("事务操作错误: 无法开启事务 (可能是事务嵌套问题):", e);
        } catch (SystemException e) {
            throw new TransactionException("事务操作错误: C无法开启事务:", e);
        } finally {
            // 确保超时始终重置为默认值
            if (timeout != null) {
                try {
                    ut.setTransactionTimeout(0);
                } catch (SystemException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void commit(boolean beganTransaction) { if (beganTransaction) this.commit(); }

    @Override
    public void commit() {
        TxStackInfo txStackInfo = getTxStackInfo();
        try {
            int status = ut.getStatus();
            // logger.warn("================ commit TX, currentStatus=${status}")

            txStackInfo.closeTxConnections();
            if (status == Status.STATUS_MARKED_ROLLBACK) {
                if (txStackInfo.rollbackOnlyInfo != null) {
                    logger.warn("事务操作警告: 不能提交事务, 当前事务被标记为仅回滚状态, 执行回滚操作, 事务仅回滚设置位置:", txStackInfo.rollbackOnlyInfo.rollbackLocation);
                } else {
                    logger.warn("事务操作警告: 不能提交事务, 当前被标记为仅回滚状态, 执行回滚操作,没有仅回滚信息, 当前位置:", new BaseException("事务操作错误: 回滚替代提交位置"));
                }
                ut.rollback();
            } else if (status != Status.STATUS_NO_TRANSACTION && status != Status.STATUS_COMMITTING &&
                    status != Status.STATUS_COMMITTED && status != Status.STATUS_ROLLING_BACK &&
                    status != Status.STATUS_ROLLEDBACK) {
                try {
                    ut.commit();
                } catch (HeuristicMixedException | HeuristicRollbackException e) {
                    throw new TransactionException("事务操作错误: 无法提交事务:", e);
                }
            } else {
                if (status != Status.STATUS_NO_TRANSACTION)
                    logger.warn("事务操作警告: 不能提交事务,因为当前事务状态为 [" + getStatusString()+"]", new Exception("事务操作错误: 事务状态错误!"));
            }
        } catch (RollbackException e) {
            if (txStackInfo.rollbackOnlyInfo != null) {
                logger.warn("事务操作警告: 不能提交事务, 当前被标记为仅回滚状态. 事务仅回滚设置位置: ", txStackInfo.rollbackOnlyInfo.rollbackLocation);
                throw new TransactionException("事务操作错误: 不能提交事务, 当前被标记为仅回滚状态. 引起回滚原因: " + txStackInfo.rollbackOnlyInfo.causeMessage, txStackInfo.rollbackOnlyInfo.causeThrowable);
            } else {
                throw new TransactionException("事务操作错误: 不能提交事务, 事务替换成回滚 (不知道导致回滚的原因):", e);
            }
        } catch (SystemException e) {
            throw new TransactionException("事务操作错误: 无法获取事务状态!", e);
        } finally {
            // there shouldn't be a TX around now, but if there is the commit may have failed so rollback to clean things up
            int status = 0;
            try {
                status = ut.getStatus();
            } catch (SystemException e) {
                e.printStackTrace();
            }
            if (status != Status.STATUS_NO_TRANSACTION && status != Status.STATUS_COMMITTING &&
                    status != Status.STATUS_COMMITTED && status != Status.STATUS_ROLLING_BACK &&
                    status != Status.STATUS_ROLLEDBACK) {
                rollback("事务提交失败, 正在回滚...", null);
            }

            txStackInfo.clearCurrent();
        }
    }

    @Override
    public void rollback(boolean beganTransaction, String causeMessage, Throwable causeThrowable) {
        if (beganTransaction) {
            this.rollback(causeMessage, causeThrowable);
        } else {
            this.setRollbackOnly(causeMessage, causeThrowable);
        }
    }

    @Override
    public void rollback(String causeMessage, Throwable causeThrowable) {
        TxStackInfo txStackInfo = getTxStackInfo();
        try {
            txStackInfo.closeTxConnections();

            // logger.warn("================ rollback TX, currentStatus=${getStatus()}")
            if (getStatus() == Status.STATUS_NO_TRANSACTION) {
                logger.warn("事务操作警告: 事务未回滚, 当前状态为: STATUS_NO_TRANSACTION");
                return;
            }

            if (causeThrowable != null) {
                String causeString = causeThrowable.toString();
                if (causeString.contains("org.eclipse.jetty.io.EofException")) {
                    logger.warn("事务操作警告: 事务已回滚. 回滚原因为: ["+causeMessage+"] \n ["+causeString+"]");
                } else {
                    logger.warn("事务操作警告: 事务已回滚. 回滚原因为: ["+causeMessage+"]", causeThrowable);
                    logger.warn("事务操作警告: 事务回滚原因 ["+causeMessage+"]. 当前位置为: ", new EntityException("实体事务错误: 回滚位置"));
                }
            } else {
                logger.warn("事务操作警告: 事务回滚原因 ["+causeMessage+"]. 当前位置为: ", new EntityException("实体事务错误: 回滚位置"));
            }

            ut.rollback();
        } catch (Throwable t) {
            throw new TransactionException("事务操作错误: 无法回滚事务:", t);
        } finally {
            // 注意：这真的应该在最后吗？
            // 也许我们只想成功回滚，以避免删除应该仍然存在的东西，
            // 或者最后它可能会匹配添加并更好地删除
            txStackInfo.clearCurrent();
        }
    }

    @Override
    public void setRollbackOnly(String causeMessage, Throwable causeThrowable) {
        try {
            int status = getStatus();
            if (status != Status.STATUS_NO_TRANSACTION) {
                if (status != Status.STATUS_MARKED_ROLLBACK) {
                    Exception rbLocation = new EntityException("实体事务错误: 设置仅回滚位置");

                    if (causeThrowable != null) {
                        String causeString = causeThrowable.toString();
                        if (causeString.contains("org.eclipse.jetty.io.EofException")) {
                            logger.warn("事务操作警告: 事务已回滚. 回滚原因为: ["+causeMessage+"]\n["+causeString+"]");
                        } else {
                            logger.warn("事务操作警告: 事务已设置为仅回滚, 原因为: ["+causeMessage+"]", causeThrowable);
                            logger.warn("事务操作警告: 事务已设置为仅回滚, 原因为: ["+causeMessage+"]. 当前位置: ", rbLocation);
                        }
                    } else {
                        logger.warn("事务操作警告: 事务回滚原因 ["+causeMessage+"]. 当前位置: ", rbLocation);
                    }

                    ut.setRollbackOnly();
                    // do this after setRollbackOnly so it only tracks it if rollback-only was actually set
                    getTxStackInfo().rollbackOnlyInfo = new RollbackInfo(causeMessage, causeThrowable, rbLocation);
                }
            } else {
                logger.warn("事务操作警告: 当前事务未设置成仅回滚, 当前状态为 STATUS_NO_TRANSACTION");
            }
        } catch (IllegalStateException | SystemException e) {
            throw new TransactionException("事务操作错误: 无法设置事务状态为仅回滚:", e);
        }
    }

    @Override
    public boolean suspend() {
        try {
            if (getStatus() == Status.STATUS_NO_TRANSACTION) {
                logger.warn("事务操作警告: 没有事务,无法挂起");
                return false;
            }

            // close connections before suspend, let the pool reuse them
            TxStackInfo txStackInfo = getTxStackInfo();
            txStackInfo.closeTxConnections();

            Transaction tx = tm.suspend();
            // only do these after successful suspend
            pushTxStackInfo(tx, new Exception("事务操作错误: 事务挂起位置:"));

            return true;
        } catch (SystemException e) {
            throw new TransactionException("事务操作错误: 无法挂起事务:", e);
        }
    }

    @Override
    public void resume() {
        if (isTransactionInPlace()) {
            logger.warn("事务操作警告: 恢复事务完毕，尝试提交");
            commit();
        }

        try {
            TxStackInfo txStackInfo = getTxStackInfo();
            if (txStackInfo.suspendedTx != null) {
                tm.resume(txStackInfo.suspendedTx);
                // only do this after successful resume
                popTxStackInfo();
            } else {
                logger.warn("事务操作警告: 没有事务被挂起, 无法恢复");
            }
        } catch (InvalidTransactionException | SystemException e) {
            throw new TransactionException("事务操作错误: 无法恢复事务:", e);
        }
    }

    @Override
    public Connection enlistConnection(XAConnection con) {
        if (con == null) return null;
        try {
            XAResource resource = con.getXAResource();
            this.enlistResource(resource);
            return con.getConnection();
        } catch (SQLException e) {
            throw new TransactionException("事务操作错误: 无法登记事务连接", e);
        }
    }

    @Override
    public void enlistResource(XAResource resource) {
        if (resource == null) return;
        if (getStatus() != Status.STATUS_ACTIVE) {
            logger.warn("事务操作警告: 无法登记 XAResource : 事务状态不是 ACTIVE", new Exception("警告位置:"));
            return;
        }
        try {
            Transaction tx = tm.getTransaction();
            if (tx != null) {
                tx.enlistResource(resource);
            } else {
                logger.warn("事务操作警告: 无法登记 XAResource: 事务为空", new Exception("警告位置:"));
            }
        } catch (RollbackException | SystemException e) {
            // This is deprecated, hopefully errors are adequate without, but leaving here for future reference
            // if (e instanceof ExtendedSystemException) {
            //     for (Throwable se in e.errors) logger.error("Extended Atomikos error: ${se.toString()}", se)
            // }
            throw new TransactionException("事务操作错误: 无法登记事务 XAResource:", e);
        }

    }

    @Override
    public void registerSynchronization(Synchronization sync) {
        if (sync == null) return;
        if (getStatus() != Status.STATUS_ACTIVE) {
            logger.warn("事务操作警告: 无法注册同步: 事务状态不是 ACTIVE", new Exception("警告位置:"));
            return;
        }
        try {
            Transaction tx = tm.getTransaction();
            if (tx != null) {
                tx.registerSynchronization(sync);
            } else {
                logger.warn("事务操作警告: 无法注册同步: 事务为空", new Exception("警告位置"));
            }
        } catch (RollbackException | SystemException e) {
            throw new TransactionException("事务操作错误: 事务无法注册同步:", e);
        }
    }

    @Override
    public void initTransactionCache() {
        if (!useTransactionCache) return;

        TxStackInfo txStackInfo = getTxStackInfo();
        if (txStackInfo.txCache == null) {
            if (isTraceEnabled) {
                StringBuilder infoString = new StringBuilder();
                infoString.append("初始化事务缓存:");
//                for (infoAei : ecfi.getEci().artifactExecutionFacade.getStack()) infoString.append(infoAei.getName());
                logger.trace(infoString.toString());
                // } else if (logger.isInfoEnabled()) {
                //     logger.info("Initializing TX cache in ${ecfi.getEci().getArtifactExecutionImpl().peek()?.getName()}")
            }

            try {
                if (tm == null || tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active");
            } catch (SystemException e) {
                logger.error("事务操作错误: 无法获取事务状态");
            } catch (XAException e) {
                logger.error("事务操作错误: 无法登记,没有 active(活动的) 事务或者事务管理器!");
                e.printStackTrace();
            }

            TransactionCache txCache = new TransactionCache(this.ecfi, false);
            txStackInfo.txCache = txCache;
            registerSynchronization(txCache);
        } else if (txStackInfo.txCache.isReadOnly()) {
//            if (isTraceEnabled) logger.trace("事务操作跟踪: 标记事务缓存可写,来自 ["+ecfi.getEci().artifactExecutionFacade.peek()!=null?ecfi.getEci().artifactExecutionFacade.peek().getName():null+"]");
            txStackInfo.txCache.makeWriteThrough();
            // doing on read only init: registerSynchronization(txStackInfo.txCache)
        }
    }
    @Override
    public boolean isTransactionCacheActive() {
        TxStackInfo txStackInfo = getTxStackInfo();
        return txStackInfo.txCache != null && !txStackInfo.txCache.isReadOnly();
    }
    @Override
    public TransactionCache getTransactionCache() { return getTxStackInfo().txCache; }
    @Override
    public void flushAndDisableTransactionCache() {
        TxStackInfo txStackInfo = getTxStackInfo();
        if (txStackInfo.txCache != null) {
            txStackInfo.txCache.makeReadOnly();
            // would be safer to flush and remove it completely, but trying just switching to read only mode
            // txStackInfo.txCache.flushCache(true)
            // txStackInfo.txCache = null
        }
    }

    public Connection getTxConnection(String groupName) {
        if (!useConnectionStash) return null;

        TxStackInfo txStackInfo = getTxStackInfo();
        ConnectionWrapper con = txStackInfo.txConByGroup.get(groupName);
        if (con == null) return null;

        try {
            if (con.isClosed()) {
                txStackInfo.txConByGroup.remove(groupName);
                logger.info("事务操作信息: 隐藏连接已关闭 组 ["+groupName+"] 连接 ["+con.toString()+"]");
                return null;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (!isTransactionActive()) {
            try {
                con.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            txStackInfo.txConByGroup.remove(groupName);
            logger.info("事务操作信息: 发现隐藏连接,但是事务是未活动状态,事务状态 ["+getStatusString()+"] 组 ["+groupName+"] 连接 ["+con.toString()+"]");
            return null;
        }
        return con;
    }
    public Connection stashTxConnection(String groupName, Connection con) {
        if (!useConnectionStash || !isTransactionActive()) return con;

        TxStackInfo txStackInfo = getTxStackInfo();
        // if transactionBeginStartTime is null we didn't begin the transaction, so can't count on commit/rollback through this
        if (txStackInfo.transactionBeginStartTime == null) return con;

        ConnectionWrapper existing = txStackInfo.txConByGroup.get(groupName);
        try {
            if (existing != null && !existing.isClosed()) existing.closeInternal();
        } catch (Throwable t) {
            logger.error("事务操作错误: 无法关闭之前隐藏连接 组 ["+groupName+"] 连接 ["+existing.toString()+"] :", t);
        }
        ConnectionWrapper newCw = new ConnectionWrapper(con, this, groupName);
        txStackInfo.txConByGroup.put(groupName, newCw);
        return newCw;
    }


    // ========== Initialize/Populate Methods ==========

    public void populateTransactionObjectsJndi() {
        MNode transactionJndiNode = this.ecfi.getConfXmlRoot().first("transaction-facade").first("transaction-jndi");
        String userTxJndiName = transactionJndiNode.attribute("user-transaction-jndi-name");
        String txMgrJndiName = transactionJndiNode.attribute("transaction-manager-jndi-name");

        MNode serverJndi = this.ecfi.getConfXmlRoot().first("transaction-facade").first("server-jndi");

        try {
            InitialContext ic;
            if (serverJndi != null) {
                Hashtable<String, Object> h = new Hashtable<>();
                h.put(Context.INITIAL_CONTEXT_FACTORY, serverJndi.attribute("initial-context-factory"));
                h.put(Context.PROVIDER_URL, serverJndi.attribute("context-provider-url"));
                if (serverJndi.attribute("url-pkg-prefixes") != null) h.put(Context.URL_PKG_PREFIXES, serverJndi.attribute("url-pkg-prefixes"));
                if (serverJndi.attribute("security-principal") != null) h.put(Context.SECURITY_PRINCIPAL, serverJndi.attribute("security-principal"));
                if (serverJndi.attribute("security-credentials") != null) h.put(Context.SECURITY_CREDENTIALS, serverJndi.attribute("security-credentials"));
                ic = new InitialContext(h);
            } else {
                ic = new InitialContext();
            }

            this.ut = (UserTransaction) ic.lookup(userTxJndiName);
            this.tm = (TransactionManager) ic.lookup(txMgrJndiName);
        } catch (NamingException ne) {
            logger.error("Error while finding JNDI Transaction objects ["+userTxJndiName+"] and ["+txMgrJndiName+"] from server ["+(serverJndi!=null ? serverJndi.attribute("context-provider-url") : "default")+"].", ne);
        }

        if (this.ut == null) logger.error("Could not find UserTransaction with name ["+userTxJndiName+"] in JNDI server ["+(serverJndi!=null ? serverJndi.attribute("context-provider-url") : "default")+"].");
        if (this.tm == null) logger.error("Could not find TransactionManager with name ["+txMgrJndiName+"] in JNDI server ["+(serverJndi!=null ? serverJndi.attribute("context-provider-url") : "default")+"].");
    }
}
