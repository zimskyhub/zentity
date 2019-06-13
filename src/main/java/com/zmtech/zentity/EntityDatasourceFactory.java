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

    /** Return the JDBC DataSource, if applicable. Return null if no JDBC DataSource exists for this Entity Datasource. */
    DataSource getDataSource();
}
