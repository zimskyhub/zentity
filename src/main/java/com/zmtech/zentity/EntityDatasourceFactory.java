package com.zmtech.zentity;

import com.zmtech.zentity.util.MNode;
import javax.sql.DataSource;

@SuppressWarnings("unused")
public interface EntityDatasourceFactory {
    EntityDatasourceFactory init(EntityFacade ef, MNode datasourceNode);
    void destroy();
    boolean checkTableExists(String entityName);
    void checkAndAddTable(String entityName);
    EntityValue makeEntityValue(String entityName);
    EntityFind makeEntityFind(String entityName);

    /** 返回适用的 JDBC 数据源. 如果没有返回null. */
    DataSource getDataSource();
}
