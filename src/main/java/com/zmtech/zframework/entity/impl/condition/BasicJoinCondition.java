
package com.zmtech.zframework.entity.impl.condition;

import com.zmtech.zframework.entity.EntityCondition;
import com.zmtech.zframework.entity.impl.EntityConditionFactoryImpl;
import com.zmtech.zframework.entity.impl.EntityDefinition;
import com.zmtech.zframework.entity.impl.EntityQueryBuilder;
import com.zmtech.zframework.exception.EntityException;


import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Map;
import java.util.Set;

public class BasicJoinCondition implements EntityConditionImplBase {
    private static final Class thisClass = BasicJoinCondition.class;
    private EntityConditionImplBase lhsInternal;
    protected JoinOperator operator;
    private EntityConditionImplBase rhsInternal;
    private int curHashCode;

    public BasicJoinCondition(EntityConditionImplBase lhs, JoinOperator operator, EntityConditionImplBase rhs) {
        this.lhsInternal = lhs;
        this.operator = operator != null ? operator : AND;
        this.rhsInternal = rhs;
        curHashCode = createHashCode();
    }

    public BasicJoinCondition() {
    }

    public JoinOperator getOperator() {
        return operator;
    }

    public EntityConditionImplBase getLhs() {
        return lhsInternal;
    }

    public EntityConditionImplBase getRhs() {
        return rhsInternal;
    }

    @Override
    @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
    public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) {
        StringBuilder sql = eqb.sqlTopLevel;
        sql.append('(');
        lhsInternal.makeSqlWhere(eqb, subMemberEd);
        sql.append(' ').append(EntityConditionFactoryImpl.getJoinOperatorString(this.operator)).append(' ');
        rhsInternal.makeSqlWhere(eqb, subMemberEd);
        sql.append(')');
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        boolean lhsMatches = lhsInternal.mapMatches(map);

        // 处理我们不需要评估rhs的情况
        if (lhsMatches && operator == OR) return true;
        if (!lhsMatches && operator == AND) return false;

        // 处理相反的情况因为我们知道上面的情况不正确（即如果OR然后lhs = false，如果AND然后lhs = true
        // 如果匹配rhs则结果为真，无论是AND还是OR
        // 如果不匹配rhs则结果为假，无论是AND还是OR
        return rhsInternal.mapMatches(map);
    }

    @Override
    public boolean mapMatchesAny(Map<String, Object> map) {
        return lhsInternal.mapMatchesAny(map) || rhsInternal.mapMatchesAny(map);
    }

    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) {
        return lhsInternal.mapKeysNotContained(map) && rhsInternal.mapKeysNotContained(map);
    }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        return operator == AND && lhsInternal.populateMap(map) && rhsInternal.populateMap(map);
    }

    @Override
    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        lhsInternal.getAllAliases(entityAliasSet, fieldAliasSet);
        rhsInternal.getAllAliases(entityAliasSet, fieldAliasSet);
    }

    @Override
    public EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) {
        EntityConditionImplBase filterLhs = lhsInternal.filter(entityAlias, mainEd);
        EntityConditionImplBase filterRhs = rhsInternal.filter(entityAlias, mainEd);
        if (filterLhs != null) {
            if (filterRhs != null) return this;
            else return filterLhs;
        } else {
            return filterRhs;
        }
    }

    @Override
    public EntityCondition ignoreCase() {
        throw new EntityException("Basic Join Condition 不能设置为 ignore (忽略)!");
    }

    @Override
    public String toString() {
        // general SQL where clause style text with values included
        return "(" + lhsInternal.toString() + ") " + EntityConditionFactoryImpl.getJoinOperatorString(this.operator) + " (" + rhsInternal.toString() + ")";
    }

    @Override
    public int hashCode() {
        return curHashCode;
    }

    private int createHashCode() {
        return (lhsInternal != null ? lhsInternal.hashCode() : 0) + operator.hashCode() + (rhsInternal != null ? rhsInternal.hashCode() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        BasicJoinCondition that = (BasicJoinCondition) o;
        if (!this.lhsInternal.equals(that.lhsInternal)) return false;
        // 注意：对于Java 枚举情况，！= 比.equals 更快
        return this.operator == that.operator && this.rhsInternal.equals(that.rhsInternal);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(lhsInternal);
        out.writeUTF(operator.name());
        out.writeObject(rhsInternal);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        lhsInternal = (EntityConditionImplBase) in.readObject();
        operator = JoinOperator.valueOf(in.readUTF());
        rhsInternal = (EntityConditionImplBase) in.readObject();
        curHashCode = createHashCode();
    }
}
