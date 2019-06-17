package com.zmtech.zframework.entity.impl.condition.impl;

import com.zmtech.zframework.entity.EntityCondition;
import com.zmtech.zframework.entity.impl.EntityConditionFactoryImpl;
import com.zmtech.zframework.entity.impl.EntityDefinition;
import com.zmtech.zframework.entity.impl.EntityQueryBuilder;
import com.zmtech.zframework.entity.impl.FieldInfo;
import com.zmtech.zframework.entity.impl.condition.EntityConditionImplBase;
import com.zmtech.zframework.exception.EntityException;
import com.zmtech.zframework.util.EntityJavaUtil.*;
import com.zmtech.zframework.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.*;

public class FieldValueCondition implements EntityConditionImplBase, Externalizable {
    protected final static Logger logger = LoggerFactory.getLogger(FieldValueCondition.class);
    private static final Class thisClass = FieldValueCondition.class;

    protected ConditionField field;
    protected ComparisonOperator operator;
    protected Object value;
    protected boolean ignoreCase = false;
    private int curHashCode;

    public FieldValueCondition() {
    }

    public FieldValueCondition(ConditionField field, ComparisonOperator operator, Object value) {
        this.field = field;
        this.value = value;

        // 默认为EQUALS
        ComparisonOperator tempOp = operator != null ? operator : EQUALS;
        // 如果 EQUALS 并且有一个Collection，则隐藏IN运算符，NOT_EQUAL 一样
        if (value instanceof Collection) {
            if (tempOp == EQUALS) tempOp = IN;
            else if (tempOp == NOT_EQUAL) tempOp = NOT_IN;
        }
        this.operator = tempOp;

        curHashCode = createHashCode();
    }

    public ComparisonOperator getOperator() {
        return operator;
    }

    public String getFieldName() {
        return field.fieldName;
    }

    public Object getValue() {
        return value;
    }

    public boolean getIgnoreCase() {
        return ignoreCase;
    }

    @Override
    public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) {
        @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
        StringBuilder sql = eqb.sqlTopLevel;
        boolean valueDone = false;
        EntityDefinition curEd = subMemberEd != null ? subMemberEd : eqb.getMainEd();
        FieldInfo fi = field.getFieldInfo(curEd);
        if (fi == null)
            throw new EntityException("无法找到字段 字段名: [" + field.fieldName + "] 实体名: [" + curEd.getFullEntityName()+"]!");

        if (value instanceof Collection && ((Collection) value).isEmpty()) {
            if (operator == IN) {
                sql.append(" 1 = 2 ");
                valueDone = true;
            } else if (operator == NOT_IN) {
                sql.append(" 1 = 1 ");
                valueDone = true;
            }
        } else {
            if (ignoreCase && fi.typeValue == 1) sql.append("UPPER(");
            sql.append(field.getColumnName(curEd));
            if (ignoreCase && fi.typeValue == 1) sql.append(')');
            sql.append(' ');

            if (value == null) {
                if (operator == EQUALS || operator == LIKE || operator == IN || operator == BETWEEN) {
                    sql.append(" IS NULL");
                    valueDone = true;
                } else if (operator == NOT_EQUAL || operator == NOT_LIKE || operator == NOT_IN || operator == NOT_BETWEEN) {
                    sql.append(" IS NOT NULL");
                    valueDone = true;
                }
            }
        }
        if (operator == IS_NULL || operator == IS_NOT_NULL) {
            sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(operator));
            valueDone = true;
        }
        if (!valueDone) {
            sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(operator));
            if (operator == IN || operator == NOT_IN) {
                if (value instanceof CharSequence) {
                    String valueStr = value.toString();
                    if (valueStr.contains(",")) value = Arrays.asList(valueStr.split(","));
                }
                if (value instanceof Collection) {
                    sql.append(" (");
                    boolean isFirst = true;
                    for (Object curValue : (Collection) value) {
                        if (isFirst) isFirst = false;
                        else sql.append(", ");
                        sql.append("?");
                        if (ignoreCase && (curValue instanceof CharSequence))
                            curValue = curValue.toString().toUpperCase();
                        eqb.parameters.add(new EntityConditionParameter(fi, curValue, eqb));
                    }
                    sql.append(')');
                } else {
                    if (ignoreCase && (value instanceof CharSequence)) value = value.toString().toUpperCase();
                    sql.append(" (?)");
                    eqb.parameters.add(new EntityConditionParameter(fi, value, eqb));
                }
            } else if ((operator == BETWEEN || operator == NOT_BETWEEN) && value instanceof Collection &&
                    ((Collection) value).size() == 2) {
                sql.append(" ? AND ?");
                Iterator iterator = ((Collection) value).iterator();
                Object value1 = iterator.next();
                if (ignoreCase && (value1 instanceof CharSequence)) value1 = value1.toString().toUpperCase();
                Object value2 = iterator.next();
                if (ignoreCase && (value2 instanceof CharSequence)) value2 = value2.toString().toUpperCase();
                eqb.parameters.add(new EntityConditionParameter(fi, value1, eqb));
                eqb.parameters.add(new EntityConditionParameter(fi, value2, eqb));
            } else {
                if (ignoreCase && (value instanceof CharSequence)) value = value.toString().toUpperCase();
                sql.append(" ?");
                eqb.parameters.add(new EntityConditionParameter(fi, value, eqb));
            }
        }
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        return EntityConditionFactoryImpl.compareByOperator(map.get(field.fieldName), operator, value);
    }

    @Override
    public boolean mapMatchesAny(Map<String, Object> map) {
        return mapMatches(map);
    }

    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) {
        return !map.containsKey(field.fieldName);
    }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        if (operator != EQUALS || ignoreCase || field instanceof ConditionAlias) return false;
        map.put(field.fieldName, value);
        return true;
    }

    @Override
    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        // 只能在 view entity 中使用,可以使用实体别名或字段别名
        if (field instanceof ConditionAlias) {
            entityAliasSet.add(((ConditionAlias) field).getEntityAlias());
        } else {
            fieldAliasSet.add(field.fieldName);
        }
    }

    @Override
    public EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) {
        // 只能在 view entity 中使用
        FieldInfo fi = field.getFieldInfo(mainEd);
        MNode fieldMe = fi.directMemberEntityNode;
        if (entityAlias == null) {
            if (fieldMe != null && "true".equals(fieldMe.attribute("sub-select"))) return null;
            return this;
        } else {
            if (fieldMe != null && entityAlias.equals(fieldMe.attribute("entity-alias"))) {
                if (fi.aliasFieldName != null && !fi.aliasFieldName.equals(field.fieldName)) {
                    FieldValueCondition newCond = new FieldValueCondition(new ConditionField(fi.aliasFieldName), operator, value);
                    if (ignoreCase) newCond.ignoreCase();
                    return newCond;
                }
                return this;
            }
            return null;
        }
    }

    @Override
    public EntityCondition ignoreCase() {
        this.ignoreCase = true;
        curHashCode++;
        return this;
    }

    @Override
    public String toString() {
        return field.toString() + " " + EntityConditionFactoryImpl.getComparisonOperatorString(this.operator) + " " +
                (value != null ? value.toString() + " (" + value.getClass().getName() + ")" : "null");
    }

    @Override
    public int hashCode() {
        return curHashCode;
    }

    private int createHashCode() {
        return (field != null ? field.hashCode() : 0) + operator.hashCode() + (value != null ? value.hashCode() : 0) + (ignoreCase ? 1 : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        FieldValueCondition that = (FieldValueCondition) o;
        if (!field.equals(that.field)) return false;
        if (value != null) {
            if (!value.equals(that.value)) return false;
        } else {
            if (that.value != null) return false;
        }
        return operator == that.operator && ignoreCase == that.ignoreCase;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        field.writeExternal(out);
        // 注意：发现 Hazelcast 中的序列化器使用writeUTF（）非常慢，需要在for循环中使用String.chatAt（），妈的
        out.writeObject(operator.name().toCharArray());
        out.writeObject(value);
        out.writeBoolean(ignoreCase);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        field = new ConditionField();
        field.readExternal(in);
        operator = ComparisonOperator.valueOf(new String((char[]) in.readObject()));
        value = in.readObject();
        ignoreCase = in.readBoolean();
        curHashCode = createHashCode();
    }
}
