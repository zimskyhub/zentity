package com.zmtech.zframework.entity.impl.condition;

import com.zmtech.zframework.entity.EntityCondition;
import com.zmtech.zframework.entity.impl.EntityDefinition;
import com.zmtech.zframework.entity.impl.EntityQueryBuilder;
import com.zmtech.zframework.exception.EntityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Map;
import java.util.Set;


public class WhereCondition implements EntityConditionImplBase {
    protected final static Logger logger = LoggerFactory.getLogger(WhereCondition.class);
    protected String sqlWhereClause;

    public WhereCondition(String sqlWhereClause) {
        this.sqlWhereClause = sqlWhereClause != null ? sqlWhereClause : "";
    }

    @Override
    public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) {
        eqb.sqlTopLevel.append(this.sqlWhereClause);
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        // NOTE: always return false unless we eventually implement some sort of SQL parsing, for caching/etc
        // always consider not matching
        logger.warn("The mapMatches for the SQL Where Condition is not supported, text is [${this.sqlWhereClause}]");
        return false;
    }

    @Override
    public boolean mapMatchesAny(Map<String, Object> map) {
        // NOTE: always return true unless we eventually implement some sort of SQL parsing, for caching/etc
        // always consider matching so cache values are cleared
        logger.warn("The mapMatchesAny for the SQL Where Condition is not supported, text is [${this.sqlWhereClause}]");
        return true;
    }

    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) {
        // always consider matching so cache values are cleared
        logger.warn("The mapMatchesAny for the SQL Where Condition is not supported, text is [${this.sqlWhereClause}]");
        return true;
    }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        return false;
    }

    @Override
    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
    }

    @Override
    public EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) {
        return entityAlias == null ? this : null;
    }

    @Override
    public EntityCondition ignoreCase() {
        throw new EntityException("Ignore case not supported for this type of condition.");
    }

    @Override
    public String toString() {
        return sqlWhereClause;
    }

    @Override
    public int hashCode() {
        return (sqlWhereClause != null ? sqlWhereClause.hashCode() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != getClass()) return false;
        WhereCondition that = (WhereCondition) o;
        if (!sqlWhereClause.equals(that.sqlWhereClause)) return false;
        return true;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeUTF(sqlWhereClause);
    }

    @Override
    public void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        sqlWhereClause = objectInput.readUTF();
    }
}
