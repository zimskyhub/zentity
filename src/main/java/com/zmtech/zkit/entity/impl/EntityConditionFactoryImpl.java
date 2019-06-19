
package com.zmtech.zkit.entity.impl;

import com.google.common.collect.ImmutableMap;
import com.zmtech.zkit.entity.EntityCondition;
import com.zmtech.zkit.entity.EntityConditionFactory;
import com.zmtech.zkit.entity.impl.condition.impl.*;
import com.zmtech.zkit.exception.BaseException;
import com.zmtech.zkit.exception.EntityException;
import com.zmtech.zkit.entity.impl.condition.*;
import com.zmtech.zkit.entity.EntityCondition.*;
import com.zmtech.zkit.util.CollectionUtil.*;
import com.zmtech.zkit.util.MNode;
import com.zmtech.zkit.util.ObjectUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.*;

import static com.zmtech.zkit.entity.EntityCondition.ComparisonOperator.*;


public class EntityConditionFactoryImpl implements EntityConditionFactory {

    private final static Logger logger = LoggerFactory.getLogger(EntityConditionFactoryImpl.class);

    private final EntityFacadeImpl efi;
    private final TrueCondition trueCondition;

    public EntityConditionFactoryImpl(EntityFacadeImpl efi) {
        this.efi = efi;
        trueCondition = new TrueCondition();
    }

    public EntityFacadeImpl getEfi() { return efi; }

    @Override
    public EntityCondition getTrueCondition() { return trueCondition; }

    @Override
    public EntityCondition makeCondition(EntityCondition lhs, JoinOperator operator, EntityCondition rhs) {
        return makeConditionImpl((EntityConditionImplBase) lhs, operator, (EntityConditionImplBase) rhs);
    }

    private static EntityConditionImplBase makeConditionImpl(EntityConditionImplBase lhs, JoinOperator operator, EntityConditionImplBase rhs) {
        if (lhs != null) {
            if (rhs != null) {
                // we have both lhs and rhs
                if (lhs instanceof ListCondition) {
                    ListCondition lhsLc = (ListCondition) lhs;
                    if (lhsLc.getOperator() == operator) {
                        if (rhs instanceof ListCondition) {
                            ListCondition rhsLc = (ListCondition) rhs;
                            if (rhsLc.getOperator() == operator) {
                                lhsLc.addConditions(rhsLc);
                                return lhsLc;
                            } else {
                                lhsLc.addCondition(rhsLc);
                                return lhsLc;
                            }
                        } else {
                            lhsLc.addCondition(rhs);
                            return lhsLc;
                        }
                    }
                }
                // no special handling, create a BasicJoinCondition
                return new BasicJoinCondition(lhs, operator, rhs);
            } else {
                return lhs;
            }
        } else {
            return rhs;
        }
    }

    @Override
    public EntityCondition makeCondition(String field, ComparisonOperator operator, Object value) {
        return new FieldValueCondition(new ConditionField(field), operator, value);
    }

    @Override
    public EntityCondition makeCondition(String field, ComparisonOperator operator, Object value, boolean orNull) {
        EntityConditionImplBase cond = new FieldValueCondition(new ConditionField(field), operator, value);
        return orNull ? makeCondition(cond, JoinOperator.OR, makeCondition(field, ComparisonOperator.EQUALS, null)) : cond;
    }

    @Override
    public EntityCondition makeConditionToField(String fieldName, ComparisonOperator operator, String toFieldName) {
        return new FieldToFieldCondition(new ConditionField(fieldName), operator, new ConditionField(toFieldName));
    }

    @Override
    public EntityCondition makeCondition(List<EntityCondition> conditionList) {
        return this.makeCondition(conditionList, JoinOperator.AND);
    }
    @Override
    public EntityCondition makeCondition(List<EntityCondition> conditionList, JoinOperator operator) {
        if (conditionList == null || conditionList.isEmpty()) return null;
        ArrayList<EntityConditionImplBase> newList = new ArrayList<>();

        if (conditionList instanceof RandomAccess) {
            // avoid creating an iterator if possible
            for (EntityCondition curCond : conditionList) {
                if (curCond == null) continue;
                // this is all they could be, all that is supported right now
                if (curCond instanceof EntityConditionImplBase) newList.add((EntityConditionImplBase) curCond);
                else
                    throw new EntityException("EntityCondition of type [${curCond.getClass().getName()}] not supported");
            }
        } else {
            for (EntityCondition curCond : conditionList) {
                if (curCond == null) continue;
                // this is all they could be, all that is supported right now
                if (curCond instanceof EntityConditionImplBase) newList.add((EntityConditionImplBase) curCond);
                else throw new EntityException("EntityCondition of type [${curCond.getClass().getName()}] not supported");
            }
        }
        if (newList.isEmpty()) return null;
        if (newList.size() == 1) {
            return newList.get(0);
        } else {
            return new ListCondition(newList, operator);
        }
    }

    @Override
    public EntityCondition makeCondition(List<Object> conditionList, String listOperator, String mapComparisonOperator, String mapJoinOperator) {
        if (conditionList == null || conditionList.isEmpty()) return null;

        JoinOperator listJoin = listOperator != null ? getJoinOperator(listOperator) : JoinOperator.AND;
        ComparisonOperator mapComparison = mapComparisonOperator != null ? getComparisonOperator(mapComparisonOperator) : ComparisonOperator.EQUALS;
        JoinOperator mapJoin = mapJoinOperator != null ? getJoinOperator(mapJoinOperator) : JoinOperator.AND;

        List<EntityConditionImplBase> newList = new ArrayList<>();
        for (Object curObj : conditionList) {
            if (curObj == null) continue;
            if (curObj instanceof Map) {
                Map<String,Object> curMap = (Map<String,Object>) curObj;
                if (curMap.isEmpty()) continue;
                EntityCondition curCond = makeCondition(curMap, mapComparison, mapJoin);
                newList.add((EntityConditionImplBase) curCond);
                continue;
            }
            if (curObj instanceof EntityConditionImplBase) {
                EntityConditionImplBase curCond = (EntityConditionImplBase) curObj;
                newList.add(curCond);
                continue;
            }
            throw new EntityException("The conditionList parameter must contain only Map and EntityCondition objects, found entry of type [${curObj.getClass().getName()}]");
        }
        if (newList.isEmpty()) return null;
        if (newList.size() == 1) {
            return newList.get(0);
        } else {
            return new ListCondition(newList, listJoin);
        }
    }

    @Override
    public EntityCondition makeCondition(Map<String, Object> fieldMap, ComparisonOperator comparisonOperator, JoinOperator joinOperator) {
        return makeCondition(fieldMap, comparisonOperator, joinOperator, null, null, false);
    }

    private EntityConditionImplBase makeCondition(Map<String, Object> fieldMap, ComparisonOperator comparisonOperator,
                                                  JoinOperator joinOperator, EntityDefinition findEd, Map<String, ArrayList<MNode>> memberFieldAliases, boolean excludeNulls) {
        if (fieldMap == null || fieldMap.isEmpty()) return  null;

        JoinOperator joinOp = joinOperator != null ? joinOperator : JoinOperator.AND;
        ComparisonOperator compOp = comparisonOperator != null ? comparisonOperator : ComparisonOperator.EQUALS;
        ArrayList<EntityConditionImplBase> condList = new ArrayList<>();
        ArrayList<KeyValue> fieldList = new ArrayList<>();

        for (Map.Entry<String, Object> entry : fieldMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.startsWith("_")) {
                switch (key) {
                    case "_comp":
                        compOp = getComparisonOperator((String) value);
                        continue;
                    case "_join":
                        joinOp = getJoinOperator((String) value);
                        continue;
                    case "_list":
                        // if there is an _list treat each as a condition Map, ie call back into this method
                        if (value instanceof List) {
                            List valueList = (List) value;
                            for (Object listEntry : valueList) {
                                if (listEntry instanceof Map) {
                                    EntityConditionImplBase entryCond = makeCondition((Map) listEntry, ComparisonOperator.EQUALS,
                                            JoinOperator.AND, findEd, memberFieldAliases, excludeNulls);
                                    if (entryCond != null) condList.add(entryCond);
                                } else {
                                    throw new EntityException("Entry in _list is not a Map: ${listEntry}");
                                }
                            }
                        } else {
                            throw new EntityException("Value for _list entry is not a List: ${value}");
                        }
                        continue;
                }
            }

            if (excludeNulls && value == null) {
                if (logger.isTraceEnabled()) logger.trace("Tried to filter find on entity ${findEd.fullEntityName} on field ${key} but value was null, not adding condition");
                continue;
            }

            // add field key/value to a list to iterate over later for conditions once we have _comp for sure
            fieldList.add(new KeyValue(key, value));
        }

        // has fields? make conditions for them
        if (!fieldList.isEmpty()) {
            for (KeyValue keyValue : fieldList) {
                String fieldName = keyValue.key;
                Object value = keyValue.value;

                if (memberFieldAliases != null && !memberFieldAliases.isEmpty()) {
                    // we have a view entity, more complex
                    ArrayList<MNode> aliases = memberFieldAliases.get(fieldName);
                    if (aliases == null || aliases.isEmpty())
                        throw new EntityException("Tried to filter on field ${fieldName} which is not included in view-entity ${findEd.fullEntityName}");

                    for (MNode alias : aliases) {
                        // could be same as field name, but not if aliased with different name
                        String aliasName = alias.attribute("name");
                        ConditionField cf = findEd != null ? findEd.getFieldInfo(aliasName).conditionField : new ConditionField(aliasName);
                        if (NOT_EQUAL.equals(compOp) || NOT_IN.equals(compOp) || NOT_LIKE.equals(compOp)) {
                            condList.add(makeConditionImpl(new FieldValueCondition(cf, compOp, value), JoinOperator.OR,
                                    new FieldValueCondition(cf, EQUALS, null)));
                        } else {
                            // in view-entities do or null for member entities that are join-optional
                            String memberAlias = alias.attribute("entity-alias");
                            MNode memberEntity = findEd.getMemberEntityNode(memberAlias);
                            if ("true".equals(memberEntity.attribute("join-optional"))) {
                                condList.add(new BasicJoinCondition(new FieldValueCondition(cf, compOp, value), JoinOperator.OR,
                                        new FieldValueCondition(cf, EQUALS, null)));
                            } else {
                                condList.add(new FieldValueCondition(cf, compOp, value));
                            }
                        }
                    }
                } else {
                    ConditionField cf = findEd != null ? findEd.getFieldInfo(fieldName).conditionField : new ConditionField(fieldName);
                    if (NOT_EQUAL.equals(compOp) || NOT_IN.equals(compOp) || NOT_LIKE.equals(compOp)) {
                        condList.add(makeConditionImpl(new FieldValueCondition(cf, compOp, value), JoinOperator.OR,
                                new FieldValueCondition(cf, EQUALS, null)));
                    } else {
                        condList.add(new FieldValueCondition(cf, compOp, value));
                    }
                }

            }
        }

        if (condList.isEmpty()) return null;

        if (condList.size() == 1) {
            return condList.get(0);
        } else {
            return new ListCondition(condList, joinOp);
        }
    }

    @Override
    public EntityCondition makeCondition(Map<String, Object> fieldMap) {
        return makeCondition(fieldMap, ComparisonOperator.EQUALS, JoinOperator.AND, null, null, false);
    }

    @Override
    public EntityCondition makeConditionDate(String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        return new DateCondition(fromFieldName, thruFieldName,
                (compareStamp != null) ? compareStamp : efi.ecfi.getEci().getNowTimestamp());
    }

    private EntityCondition makeConditionDate(String fromFieldName, String thruFieldName, Timestamp compareStamp, boolean ignoreIfEmpty, String ignore) {
        if (ignoreIfEmpty && compareStamp == null) return null;
        if (efi.ecfi.getResource().condition(ignore, null)) return null;
        return new DateCondition(fromFieldName, thruFieldName,
                (compareStamp !=  null) ? compareStamp : efi.ecfi.getEci().getNowTimestamp());
    }

    @Override
    public EntityCondition makeConditionWhere(String sqlWhereClause) {
        if (sqlWhereClause == null || sqlWhereClause.equals("")) return null;
        return new WhereCondition(sqlWhereClause);
    }

    public ComparisonOperator comparisonOperatorFromEnumId(String enumId) {
        switch (enumId) {
            case "ENTCO_LESS": return EntityCondition.LESS_THAN;
            case "ENTCO_GREATER": return EntityCondition.GREATER_THAN;
            case "ENTCO_LESS_EQ": return EntityCondition.LESS_THAN_EQUAL_TO;
            case "ENTCO_GREATER_EQ": return EntityCondition.GREATER_THAN_EQUAL_TO;
            case "ENTCO_EQUALS": return EntityCondition.EQUALS;
            case "ENTCO_NOT_EQUALS": return EntityCondition.NOT_EQUAL;
            case "ENTCO_IN": return EntityCondition.IN;
            case "ENTCO_NOT_IN": return EntityCondition.NOT_IN;
            case "ENTCO_BETWEEN": return EntityCondition.BETWEEN;
            case "ENTCO_NOT_BETWEEN": return EntityCondition.NOT_BETWEEN;
            case "ENTCO_LIKE": return EntityCondition.LIKE;
            case "ENTCO_NOT_LIKE": return EntityCondition.NOT_LIKE;
            case "ENTCO_IS_NULL": return EntityCondition.IS_NULL;
            case "ENTCO_IS_NOT_NULL": return EntityCondition.IS_NOT_NULL;
            default: return null;
        }
    }

    public static EntityConditionImplBase addAndListToCondition(EntityConditionImplBase baseCond, ArrayList<EntityConditionImplBase> condList) {
        EntityConditionImplBase outCondition = baseCond;
        int condListSize = condList != null ? condList.size() : 0;
        if (condListSize > 0) {
            if (baseCond == null) {
                if (condListSize == 1) {
                    outCondition = condList.get(0);
                } else {
                    outCondition = new ListCondition(condList, EntityCondition.AND);
                }
            } else {
                ListCondition newListCond = null;
                if (baseCond instanceof ListCondition) {
                    ListCondition baseListCond = (ListCondition) baseCond;
                    if (EntityCondition.AND.equals(baseListCond.getOperator())) {
                        // modify in place
                        newListCond = baseListCond;
                    }
                }
                ArrayList<EntityConditionImplBase> basList = new ArrayList<>();
                basList.add(baseCond);
                if (newListCond == null) newListCond = new ListCondition(basList, EntityCondition.AND);
                newListCond.addConditions(condList);
                outCondition = newListCond;
            }
        }
        return outCondition;
    }

    private EntityCondition makeActionCondition(String fieldName, String operator, String fromExpr, String value, String toFieldName,
                                                boolean ignoreCase, boolean ignoreIfEmpty, boolean orNull, String ignore) {
        Object from = fromExpr != null && !fromExpr.isEmpty() ? this.efi.ecfi.getResource().expression(fromExpr, "") : null;
        return makeActionConditionDirect(fieldName, operator, from, value, toFieldName, ignoreCase, ignoreIfEmpty, orNull, ignore);
    }
    private EntityCondition makeActionConditionDirect(String fieldName, String operator, Object fromObj, String value, String toFieldName,
                                                      boolean ignoreCase, boolean ignoreIfEmpty, boolean orNull, String ignore) {
        // logger.info("TOREMOVE makeActionCondition(fieldName ${fieldName}, operator ${operator}, fromExpr ${fromExpr}, value ${value}, toFieldName ${toFieldName}, ignoreCase ${ignoreCase}, ignoreIfEmpty ${ignoreIfEmpty}, orNull ${orNull}, ignore ${ignore})")

        if (efi.ecfi.getResource().condition(ignore, null)) return null;

        if (toFieldName != null && toFieldName.length() > 0) {
            EntityCondition ec = makeConditionToField(fieldName, getComparisonOperator(operator), toFieldName);
            if (ignoreCase) ec.ignoreCase();
            return ec;
        } else {
            Object condValue;
            if (value != null && value.length() > 0) {
                // NOTE: have to convert value (if needed) later on because we don't know which entity/field this is for, or change to pass in entity?
                condValue = value;
            } else {
                condValue = fromObj;
            }
            if (ignoreIfEmpty && ObjectUtil.isEmpty(condValue)) return null;

            EntityCondition mainEc = makeCondition(fieldName, getComparisonOperator(operator), condValue);
            if (ignoreCase) mainEc.ignoreCase();

            EntityCondition ec = mainEc;
            if (orNull) ec = makeCondition(mainEc, JoinOperator.OR, makeCondition(fieldName, ComparisonOperator.EQUALS, null));
            return ec;
        }
    }

    private EntityCondition makeActionCondition(MNode node) {
        Map<String, String> attrs = node.getAttributes();
        return makeActionCondition(attrs.get("field-name"),
                attrs.get("operator") != null ? attrs.get("operator"): "equals", (attrs.get("from") != null ? attrs.get("from"): attrs.get("field-name")),
        attrs.get("value"), attrs.get("to-field-name"), (attrs.get("ignore-case") != null ? attrs.get("ignore-case"): "false").equals("true") ,
                (attrs.get("ignore-if-empty") != null ? attrs.get("ignore-if-empty"): "false").equals("true"), (attrs.get("or-null")!= null ? attrs.get("or-null"): "false").equals("true"),
                (attrs.get("ignore")!= null ?attrs.get("ignore"): "false"));
    }

    EntityCondition makeActionConditions(MNode node, boolean isCached) {
        ArrayList<EntityCondition> condList = new ArrayList<>();
        ArrayList<MNode> subCondList = node.getChildren();
        for (MNode subCond : subCondList) {
            if ("econdition".equals(subCond.getName())) {
                EntityCondition econd = makeActionCondition(subCond);
                if (econd != null) condList.add(econd);
            } else if ("econditions".equals(subCond.getName())) {
                EntityCondition econd = makeActionConditions(subCond, isCached);
                if (econd != null) condList.add(econd);
            } else if ("date-filter".equals(subCond.getName())) {
                if (!isCached) {
                    Timestamp validDate = subCond.attribute("valid-date") != null ?
                            Timestamp.valueOf((String) efi.ecfi.getResource().expression(subCond.attribute("valid-date"), null)) : null;
                    condList.add(makeConditionDate(subCond.attribute("from-field-name") != null ? subCond.attribute("from-field-name") : "fromDate",
                            subCond.attribute("thru-field-name") != null ? subCond.attribute("thru-field-name") : "thruDate", validDate,
                            "true".equals(subCond.attribute("ignore-if-empty")), subCond.attribute("ignore") != null ? subCond.attribute("ignore") : "false"));
                }
            } else if ("econdition-object".equals(subCond.getName())) {
                Object curObj = efi.ecfi.getResource().expression(subCond.attribute("field"), null);
                if (curObj == null) continue;
                if (curObj instanceof Map) {
                    Map curMap = (Map) curObj;
                    if (curMap.isEmpty()) continue;
                    EntityCondition curCond = makeCondition(curMap, EQUALS, JoinOperator.AND);
                    condList.add(curCond);
                    continue;
                }
                if (curObj instanceof EntityConditionImplBase) {
                    EntityConditionImplBase curCond = (EntityConditionImplBase) curObj;
                    condList.add(curCond);
                    continue;
                }
                throw new BaseException("The econdition-object field attribute must contain only Map and EntityCondition objects, found entry of type [${curObj.getClass().getName()}]");
            }
        }
        return makeCondition(condList, getJoinOperator(node.attribute("combine")));
    }

    private static final Map<ComparisonOperator, String> comparisonOperatorStringMap = new EnumMap<>(ComparisonOperator.class);
    static {
        comparisonOperatorStringMap.put(ComparisonOperator.EQUALS, "=");
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_EQUAL, "<>");
        comparisonOperatorStringMap.put(ComparisonOperator.LESS_THAN, "<");
        comparisonOperatorStringMap.put(ComparisonOperator.GREATER_THAN, ">");
        comparisonOperatorStringMap.put(ComparisonOperator.LESS_THAN_EQUAL_TO, "<=");
        comparisonOperatorStringMap.put(ComparisonOperator.GREATER_THAN_EQUAL_TO, ">=");
        comparisonOperatorStringMap.put(ComparisonOperator.IN, "IN");
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_IN, "NOT IN");
        comparisonOperatorStringMap.put(ComparisonOperator.BETWEEN, "BETWEEN");
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_BETWEEN, "NOT BETWEEN");
        comparisonOperatorStringMap.put(ComparisonOperator.LIKE, "LIKE");
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_LIKE, "NOT LIKE");
        comparisonOperatorStringMap.put(IS_NULL, "IS NULL");
        comparisonOperatorStringMap.put(ComparisonOperator.IS_NOT_NULL, "IS NOT NULL");
    }
    static final Map<String, ComparisonOperator> stringComparisonOperatorMap = ImmutableMap.<String, ComparisonOperator>builder()
            .put("=",ComparisonOperator.EQUALS)
            .put("equals",ComparisonOperator.EQUALS)
            .put("not-equals",ComparisonOperator.NOT_EQUAL)
            .put("not-equal",ComparisonOperator.NOT_EQUAL)
            .put("!=",ComparisonOperator.NOT_EQUAL)
            .put("<>",ComparisonOperator.NOT_EQUAL)
            .put("less-than",ComparisonOperator.LESS_THAN)
            .put("less",ComparisonOperator.LESS_THAN)
            .put("<",ComparisonOperator.LESS_THAN)
            .put("greater-than",ComparisonOperator.GREATER_THAN)
            .put("greater",ComparisonOperator.GREATER_THAN)
            .put(">",ComparisonOperator.GREATER_THAN)
            .put("less-than-equal-to",ComparisonOperator.LESS_THAN_EQUAL_TO)
            .put("less-equals",ComparisonOperator.LESS_THAN_EQUAL_TO)
            .put("<=",ComparisonOperator.LESS_THAN_EQUAL_TO)
            .put("greater-than-equal-to",ComparisonOperator.GREATER_THAN_EQUAL_TO)
            .put("greater-equals",ComparisonOperator.GREATER_THAN_EQUAL_TO)
            .put(">=",ComparisonOperator.GREATER_THAN_EQUAL_TO)
            .put("in",ComparisonOperator.IN)
            .put("IN",ComparisonOperator.IN)
            .put("not-in",ComparisonOperator.NOT_IN)
            .put("NOT IN",ComparisonOperator.NOT_IN)
            .put("between",ComparisonOperator.BETWEEN)
            .put("BETWEEN",ComparisonOperator.BETWEEN)
            .put("not-between",ComparisonOperator.NOT_BETWEEN)
            .put("NOT BETWEEN",ComparisonOperator.NOT_BETWEEN)
            .put("like",ComparisonOperator.LIKE)
            .put("LIKE",ComparisonOperator.LIKE)
            .put("not-like",ComparisonOperator.NOT_LIKE)
            .put("NOT LIKE",ComparisonOperator.NOT_LIKE)
            .put("is-null", IS_NULL)
            .put("IS NULL", IS_NULL)
            .put("is-not-null",ComparisonOperator.IS_NOT_NULL)
            .put("IS NOT NULL",ComparisonOperator.IS_NOT_NULL)
            .build();

    public static String getJoinOperatorString(JoinOperator op) { return JoinOperator.OR==op ? "OR" : "AND"; }
    private static JoinOperator getJoinOperator(String opName) { return "or".equalsIgnoreCase(opName) ? JoinOperator.OR :JoinOperator.AND; }

    public static String getComparisonOperatorString(EntityCondition.ComparisonOperator op) { return comparisonOperatorStringMap.get(op); }
    static EntityCondition.ComparisonOperator getComparisonOperator(String opName) {
        if (opName == null) return ComparisonOperator.EQUALS;
        ComparisonOperator co = stringComparisonOperatorMap.get(opName);
        return co != null ? co : ComparisonOperator.EQUALS;
    }

    public static boolean compareByOperator(Object value1, ComparisonOperator op, Object value2) {
        switch (op) {
            case EQUALS:
                return value1 == value2;
            case NOT_EQUAL:
                return value1 != value2;
            case LESS_THAN:{
                Comparable comp1 = ObjectUtil.makeComparable(value1);
                Comparable comp2 = ObjectUtil.makeComparable(value2);
                return comp1.compareTo(comp2) < 0;
            }
            case GREATER_THAN:{
                Comparable comp1 = ObjectUtil.makeComparable(value1);
                Comparable comp2 = ObjectUtil.makeComparable(value2);
                return comp1.compareTo(comp2) > 0;
            }
            case LESS_THAN_EQUAL_TO:{
                Comparable comp1 = ObjectUtil.makeComparable(value1);
                Comparable comp2 = ObjectUtil.makeComparable(value2);
                return comp1.compareTo(comp2) <= 0;
            }
            case GREATER_THAN_EQUAL_TO:{
                Comparable comp1 = ObjectUtil.makeComparable(value1);
                Comparable comp2 = ObjectUtil.makeComparable(value2);
                return comp1.compareTo(comp2) >= 0;
            }

            case IN:{
                if (value2 instanceof Collection) {
                    return ((Collection) value2).contains(value1);
                } else {
                    // not a Collection, try equals
                    return value1 == value2;
                }
            }
            case NOT_IN:{
                if (value2 instanceof Collection) {
                    return !((Collection) value2).contains(value1);
                } else {
                    // not a Collection, try not-equals
                    return value1 != value2;
                }
            }

            case BETWEEN:{
                if (value2 instanceof Collection && ((Collection) value2).size() == 2) {
                    Comparable comp1 = ObjectUtil.makeComparable(value1);
                    Iterator iterator = ((Collection) value2).iterator();
                    Comparable lowObj = ObjectUtil.makeComparable(iterator.next());
                    Comparable highObj = ObjectUtil.makeComparable(iterator.next());
                    return lowObj.compareTo(comp1) <= 0 && comp1.compareTo(highObj) < 0;
                } else {
                    return false;
                }
            }

            case NOT_BETWEEN:{
                if (value2 instanceof Collection && ((Collection) value2).size() == 2) {
                    Comparable comp1 = ObjectUtil.makeComparable(value1);
                    Iterator iterator = ((Collection) value2).iterator();
                    Comparable lowObj = ObjectUtil.makeComparable(iterator.next());
                    Comparable highObj = ObjectUtil.makeComparable(iterator.next());
                    return lowObj.compareTo(comp1) > 0  && comp1.compareTo(highObj) >= 0;
                } else {
                    return false;
                }
            }
            case LIKE:
                return ObjectUtil.compareLike(value1, value2);
            case NOT_LIKE:
                return !ObjectUtil.compareLike(value1, value2);
            case IS_NULL:
                return value1 == null;
            case IS_NOT_NULL:
                return value1 != null;
        }
        // default return false
        return false;
    }
}
