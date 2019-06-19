
package com.zmtech.zkit.entity.impl;

import com.zmtech.zkit.actions.XmlAction;
import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.context.impl.ExecutionContextImpl;
import com.zmtech.zkit.entity.EntityFind;
import com.zmtech.zkit.entity.EntityValue;
import com.zmtech.zkit.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class EntityEcaRule {
    protected final static Logger logger = LoggerFactory.getLogger(EntityEcaRule.class);

    protected ExecutionContextFactoryImpl ecfi;
    protected MNode eecaNode;
    protected String location;

    protected XmlAction condition = null;
    protected XmlAction actions = null;

    public EntityEcaRule(ExecutionContextFactoryImpl ecfi, MNode eecaNode, String location) {
        this.ecfi = ecfi;
        this.eecaNode = eecaNode;
        this.location = location;

        // 准备条件
        if (eecaNode.hasChild("condition") && eecaNode.first("condition").getChildren().size() > 0) {
            // 该脚本实际上是condition元素的第一个子元素
            condition = new XmlAction(ecfi, eecaNode.first("condition").getChildren().get(0), location + ".condition");
        }
        // 准备行动
        if (eecaNode.hasChild("actions")) {
            actions = new XmlAction(ecfi, eecaNode.first("actions"), null); // 是位置+“.actions”但不是唯一的！
        }
    }

    public String getEntityName() { return eecaNode.attribute("entity"); }
    public MNode getEecaNode() { return eecaNode; }

    public void runIfMatches(String entityName, Map fieldValues, String operation, boolean before, ExecutionContextImpl ec) {
        // 看看我们是否匹配此事件并且应该运行

        // 首先检查它，因为它经常不合格者
        String attrName = "on-".concat(operation);
        if (!"true".equals(eecaNode.attribute(attrName))) return;

        if (!entityName.equals(eecaNode.attribute("entity"))) return;
//        if (ec.messageFacade.hasError() && !"true".equals(eecaNode.attribute("run-on-error"))) return;

        EntityValue curValue = null;

        boolean isDelete = "delete".equals(operation);
        boolean isUpdate = !isDelete && "update".equals(operation);

        // 在删除之前获取数据库的值，以便它们在之后可用; 这会修改 EntityValueBase 使用的字段值
        if (before && isDelete && eecaNode.attribute("get-entire-entity").equals("true")) {
            // 从DB填写任何缺失（未设置）值
            if (curValue == null) curValue = getDbValue(fieldValues);
            if (curValue != null) {
                // 仅添加fieldValues不包含的字段
                for (Map.Entry entry : curValue.entrySet())
                if (!fieldValues.containsKey(entry.getKey())) fieldValues.put(entry.getKey(), entry.getValue());
            }
        }

        // 执行此操作之前使EECA规则运行后从DB获取原始值并放入实体的dbValue
        EntityValue originalValue = null;
        if (before && (isUpdate || isDelete) && "true".equals(eecaNode.attribute("get-original-value"))) {
            if (curValue == null) curValue = getDbValue(fieldValues);
            if (curValue != null) {
                originalValue = curValue;
                // 还将DB值放在fieldValues EntityValue中，如果它不是来自DB（以供将来参考）
                if (fieldValues instanceof EntityValueBase && !((EntityValueBase) fieldValues).getIsFromDb()) {
                    // 注意：从数据库中刷新，valueMap将具有干净值，dbValueMap将为null
                    ((EntityValueBase) fieldValues).setDbValueMap(((EntityValueBase) originalValue).getValueMap());
                }
            }
        }

        if (before && !"true".equals(eecaNode.attribute("run-before"))) return;
        if (!before && "true".equals(eecaNode.attribute("run-before"))) return;

        // now if we're running after the entity operation, pull the original value from the
        if (!before && fieldValues instanceof EntityValueBase && ((EntityValueBase) fieldValues).getIsFromDb() &&
                (isUpdate || isDelete) && eecaNode.attribute("get-original-value").equals("true")) {
            originalValue = ((EntityValueBase) fieldValues).cloneDbValue(true);
        }

        if ((isUpdate || isDelete) && eecaNode.attribute("get-entire-entity").equals("true")) {
            // fill in any missing (unset) values from the DB
            if (curValue == null) curValue = getDbValue(fieldValues);
            if (curValue != null) {
                // only add fields that fieldValues does not contain
                for (Map.Entry entry : curValue.entrySet())
                if (!fieldValues.containsKey(entry.getKey())) fieldValues.put(entry.getKey(), entry.getValue());
            }
        }

        try {
            Map<String, Object> contextMap = new HashMap<>();
            ec.contextStack.push(contextMap);
            ec.contextStack.putAll(fieldValues);
            ec.contextStack.put("entityValue", fieldValues);
            ec.contextStack.put("originalValue", originalValue);
            ec.contextStack.put("eecaOperation", operation);

            // run the condition and if passes run the actions
            boolean conditionPassed = true;
            if (condition != null) conditionPassed = condition.checkCondition(ec);
            if (conditionPassed && actions != null) {
                Object result = actions.run(ec);

                // if anything was set in the context that matches a field name set it on the EntityValue
                if ("true".equals(eecaNode.attribute("set-results"))) {
                    Map resultMap;
                    if (result instanceof Map) {
                        resultMap = (Map) result;
                    } else {
                        resultMap = contextMap;
                    }

                    if (!resultMap.isEmpty()) {
                        EntityDefinition ed = ecfi.getEntity().getEntityDefinition(entityName);
                        ArrayList<String> fieldNames = ed.getNonPkFieldNames();
                        int fieldNamesSize = fieldNames.size();
                        for (int i = 0; i < fieldNamesSize; i++) {
                            String fieldName = fieldNames.get(i);
                            if (resultMap.containsKey(fieldName)) fieldValues.put(fieldName, resultMap.get(fieldName));
                        }
                    }
                }
            }
        } finally {
            ec.contextStack.pop();
        }
    }

    public EntityValue getDbValue(Map fieldValues) {
        EntityDefinition ed = ecfi.getEntity().getEntityDefinition(getEntityName());
        EntityFind ef = ecfi.getEntity().find(getEntityName());
        for (String pkFieldName : ed.getPkFieldNames()) ef.condition(pkFieldName, fieldValues.get(pkFieldName));
        return ef.one();
    }
}
