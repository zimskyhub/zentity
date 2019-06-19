package com.zmtech.zkit.entity.impl.condition.impl;

import com.zmtech.zkit.entity.EntityCondition;
import com.zmtech.zkit.entity.impl.EntityDefinition;
import com.zmtech.zkit.entity.impl.EntityQueryBuilder;
import com.zmtech.zkit.entity.impl.condition.EntityConditionImplBase;
import com.zmtech.zkit.exception.EntityException;
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
        // 注意：除非我们最终实现某种SQL解析，否则总是返回false，因为缓存/等始终认为不匹配
        logger.warn("实体条件警告: Where Condition 不支持 mapMatches, 相关sql: ["+this.sqlWhereClause+"]");
        return false;
    }

    @Override
    public boolean mapMatchesAny(Map<String, Object> map) {
        // 注意：除非我们最终实现某种SQL解析，否则总是返回true，因为缓存/等总是考虑匹配，因此清除缓存值
        logger.warn("实体条件警告: Where Condition 不支持 mapMatches, 相关sql: ["+this.sqlWhereClause+"]");
        return true;
    }

    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) {
        // 始终考虑匹配，以便清除缓存值
        logger.warn("实体条件警告: Where Condition 不支持 mapMatches, 相关sql: ["+this.sqlWhereClause+"]");
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
        throw new EntityException("实体条件错误: ListCondition 不支持 Ignore Case!");
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
        return sqlWhereClause.equals(that.sqlWhereClause);
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
