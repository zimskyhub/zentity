package com.zmtech.zkit.entity.impl;

import com.zmtech.zkit.entity.EntityDatasourceFactory;
import com.zmtech.zkit.entity.EntityFacade;
import com.zmtech.zkit.entity.EntityFind;
import com.zmtech.zkit.entity.EntityValue;
import com.zmtech.zkit.exception.EntityException;
import com.zmtech.zkit.transaction.TransactionInternal;
import com.zmtech.zkit.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.util.Hashtable;

import static java.lang.Thread.sleep;

public class EntityDatasourceFactoryImpl implements EntityDatasourceFactory {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDatasourceFactoryImpl.class);
    protected final static int DS_RETRY_COUNT = 5;
    protected final static long DS_RETRY_SLEEP = 5000;

    protected EntityFacadeImpl efi = null;
    protected MNode datasourceNode = null;

    protected DataSource dataSource = null;
    public EntityFacadeImpl.DatasourceInfo dsi = null;


    EntityDatasourceFactoryImpl() {
    }

    @Override
    public EntityDatasourceFactory init(EntityFacade ef, MNode datasourceNode) {
        // 本地字段信息
        this.efi = (EntityFacadeImpl) ef;
        this.datasourceNode = datasourceNode;

        // 初始化数据源
        dsi = new EntityFacadeImpl.DatasourceInfo(efi, datasourceNode);
        if (dsi.jndiName != null && !dsi.jndiName.isEmpty()) {
            try {
                InitialContext ic;
                if (dsi.serverJndi != null) {
                    Hashtable<String, Object> h = new Hashtable<>();
                    h.put(Context.INITIAL_CONTEXT_FACTORY, dsi.serverJndi.attribute("initial-context-factory"));
                    h.put(Context.PROVIDER_URL, dsi.serverJndi.attribute("context-provider-url"));
                    if (dsi.serverJndi.attribute("url-pkg-prefixes") != null)
                        h.put(Context.URL_PKG_PREFIXES, dsi.serverJndi.attribute("url-pkg-prefixes"));
                    if (dsi.serverJndi.attribute("security-principal") != null)
                        h.put(Context.SECURITY_PRINCIPAL, dsi.serverJndi.attribute("security-principal"));
                    if (dsi.serverJndi.attribute("security-credentials") != null)
                        h.put(Context.SECURITY_CREDENTIALS, dsi.serverJndi.attribute("security-credentials"));
                    ic = new InitialContext(h);
                } else {
                    ic = new InitialContext();
                }

                this.dataSource = (DataSource) ic.lookup(dsi.jndiName);
                if (this.dataSource == null) {
                    logger.error("数据源错误: 无法找到名称为 [" + dsi.jndiName + "] 的数据源, 所在 JNDI server 为[" + (dsi.serverJndi != null ? dsi.serverJndi.attribute("context-provider-url") : "default") +
                            "] 数据源所属组名称为 [" + datasourceNode.attribute("group-name") + ".");
                }
            } catch (NamingException ne) {
                logger.error("数据源错误: 无法找到名称为 [" + dsi.jndiName + "] 的数据源,所在 JNDI server 为 [" + (dsi.serverJndi != null ? dsi.serverJndi.attribute("context-provider-url") : "default") +
                        "] 数据源所属组名称为 [${" + datasourceNode.attribute("group-name") + "}].", ne);
            }
        } else if (dsi.inlineJdbc != null) {
            // 嵌入式deby的特性，只需设置一个系统属性;
            if (datasourceNode.attribute("database-conf-name").equals("derby") && System.getProperty("derby.system.home") == null) {
                System.setProperty("derby.system.home", efi.ecfi.getRuntimePath() + "/db/derby");
                logger.info("数据源设置: 设置属性 derby.system.home 的值为 [${" + System.getProperty("derby.system.home") + "}]");
            }

            TransactionInternal ti = efi.ecfi.getTransaction().getTransactionInternal();
            // 初始化DataSource，如果失败会重试几次
            for (int retry = 1; retry <= DS_RETRY_COUNT; retry++) {
                try {
                    this.dataSource = ti.getDataSource(efi, datasourceNode);
                    break;
                } catch (Throwable t) {
                    if (retry < DS_RETRY_COUNT) {
                        Throwable cause = t;
                        while (cause.getCause() != null) cause = cause.getCause();
                        logger.error("数据源错误: 无法连接数据源 ${" + datasourceNode.attribute("group-name") + "} (" + datasourceNode.attribute("database-conf-name") +
                                "), 正在重试,第 ${" + retry + "} / " + DS_RETRY_COUNT + " 次 进程: " + cause + "...");
                        try {
                            sleep(DS_RETRY_SLEEP);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else {
                        throw t;
                    }
                }
            }
        } else {
            throw new EntityException("数据源错误: 在jdbc sub-element 里没有找到组名为: (" + datasourceNode.attribute("group-name") + ") 的数据源");
        }

        return this;
    }

    @Override
    public void destroy() {
        // 注意：销毁TransactionFacade时将销毁TransactionInternal DataSource;
    }

    @Override
    public boolean checkTableExists(String entityName) {
        EntityDefinition ed;
        // 忽略getEntityDefinition上的EntityException
        try {
            ed = efi.getEntityDefinition(entityName);
        } catch (EntityException e) {
            return false;
        }
        // 如果所有实体名称里包含DB视图实体或其他不存在的实体，则可能会发生
        if (ed == null) return false;
        return ed.tableExistsDbMetaOnly();
    }

    @Override
    public boolean checkAndAddTable(String entityName) {
        EntityDefinition ed;
        // 忽略getEntityDefinition上的EntityException
        try {
            ed = efi.getEntityDefinition(entityName);
        } catch (EntityException e) {
            return false;
        }
        // 如果所有实体名称里包含DB视图实体或其他不存在的实体，则可能会发生
        if (ed == null) return false;
        return efi.getEntityDbMeta().checkTableStartup(ed);
    }

    @Override
    public EntityValue makeEntityValue(String entityName) {
        EntityDefinition entityDefinition = efi.getEntityDefinition(entityName);
        if (entityDefinition == null) throw new EntityException("实体名称错误: 没有找到名称为 [" + entityName + "] 的实体");
        return new EntityValueImpl(entityDefinition, efi);
    }

    @Override
    public EntityFind makeEntityFind(String entityName) {
        return new EntityFindImpl(efi, entityName);
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }
}
