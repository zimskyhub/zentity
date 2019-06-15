package com.zmtech.zentity.entity.impl;

import com.zmtech.zentity.entity.EntityDynamicView;
import com.zmtech.zentity.exception.EntityException;
import com.zmtech.zentity.util.MNode;


import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class EntityDynamicViewImpl implements EntityDynamicView {

    protected EntityFacadeImpl efi;
    protected String entityName = "DynamicView";
    protected MNode entityNode = new MNode("view-entity",new ConcurrentHashMap<String, String>() {{
            put("package", "dynamic");
            put("entity-name", "DynamicView");
            put("is-dynamic-view", "true");
        }});

    public EntityDynamicViewImpl(EntityFindImpl entityFind) { this.efi = entityFind.efi; }

    public EntityDynamicViewImpl(EntityFacadeImpl efi) { this.efi = efi; }

    public EntityDefinition makeEntityDefinition() { return new EntityDefinition(efi, entityNode); }

    @Override
    public EntityDynamicView setEntityName(String entityName) {
        entityNode.getAttributes().put("entity-name", entityName);
        return this;
    }

    @Override
    public EntityDynamicView addMemberEntity(String entityAlias, String entityName, String joinFromAlias, Boolean joinOptional,
                                      Map<String, String> entityKeyMaps) {
        MNode memberEntity = entityNode.append("member-entity", new ConcurrentHashMap<String, String>() {{
            put("entity-alias", entityAlias);
            put("entity-name", entityName);
        }});
        if (joinFromAlias != null) {
            memberEntity.getAttributes().put("join-from-alias", joinFromAlias);
            memberEntity.getAttributes().put("join-optional", (joinOptional ? "true" : "false"));
        }
        if (entityKeyMaps!= null && !entityKeyMaps.isEmpty()) for (Map.Entry<String, String> keyMapEntry : entityKeyMaps.entrySet()) {
            memberEntity.append("key-map", new ConcurrentHashMap<String, String>() {{
                put("field-name", keyMapEntry.getKey());
                put("related", keyMapEntry.getValue());
            }});
        }
        return this;
    }

    @Override
    public EntityDynamicView addRelationshipMember(String entityAlias, String joinFromAlias, String relationshipName,
                                            Boolean joinOptional) {
        MNode joinFromMemberEntityNode = entityNode.first({ MNode it -> it.name == "member-entity" && it.attribute("entity-alias") == joinFromAlias });
        String entityName = joinFromMemberEntityNode.attribute("entity-name");
        EntityDefinition joinFromEd = efi.getEntityDefinition(entityName);
        EntityJavaUtil.RelationshipInfo relInfo = joinFromEd.getRelationshipInfo(relationshipName);
        if (relInfo == null) throw new EntityException("Relationship not found with name [${relationshipName}] on entity [${entityName}]");

        Map<String, String> relationshipKeyMap = relInfo.keyMap;
        MNode memberEntity = entityNode.append("member-entity",new ConcurrentHashMap<String,String>(){{
            put("entity-alias",entityAlias);
            put("entity-name",relInfo.relatedEntityName);
        }});
        memberEntity.getAttributes().put("join-from-alias", joinFromAlias);
        memberEntity.getAttributes().put("join-optional", (joinOptional ? "true" : "false"));
        for (Map.Entry<String, String> keyMapEntry : relationshipKeyMap.entrySet()) {
            memberEntity.append("key-map",new ConcurrentHashMap<String,String>(){{
              put("field-name",keyMapEntry.getKey());
              put("related",keyMapEntry.getValue());
            }});
        }

        if (relInfo.keyValueMap != null && relInfo.keyValueMap.size() > 0) {
            Map<String, String> keyValueMap = relInfo.keyValueMap;
            MNode entityCondition = memberEntity.append("entity-condition", null);
            for (Map.Entry<String, String> keyValueEntry: keyValueMap.entrySet()) {
                entityCondition.append("econdition",new ConcurrentHashMap<String,String>(){{
                    put("entity-alias",entityAlias);
                    put("'value'",keyValueEntry.getValue());
                }});
            }
        }
        return this;
    }

    public MNode getViewEntityNode() { return entityNode; }

    @Override
    public List<MNode> getMemberEntityNodes() { return entityNode.children("member-entity"); }

    @Override
    public EntityDynamicView addAliasAll(String entityAlias, String prefix) {
        entityNode.append("alias-all", new ConcurrentHashMap<String,String>(){{
            put("entity-alias",entityAlias);
            put("prefix",prefix);
        }});
        return this;
    }

    @Override
    public EntityDynamicView addAlias(String entityAlias, String name) {
        entityNode.append("alias", new ConcurrentHashMap<String,String>(){{
            put("entity-alias",entityAlias);
            put("name",name);
        }});
        return this;
    }
    @Override
    public EntityDynamicView addAlias(String entityAlias, String name, String field, String function) {
        return addAlias(entityAlias, name, field, function, null);
    }
    public EntityDynamicView addAlias(String entityAlias, String name, String field, String function, String defaultDisplay) {
        MNode aNode = entityNode.append("alias", new ConcurrentHashMap<String,String>(){{
            put("entity-alias",entityAlias);
            put("name",name);
        }});
        if (field != null && !field.isEmpty()) aNode.getAttributes().put("field", field);
        if (function != null && !function.isEmpty()) aNode.getAttributes().put("function", function);
        if (defaultDisplay != null && !defaultDisplay.isEmpty()) aNode.getAttributes().put("default-display", defaultDisplay);
        return this;
    }
    public MNode getAlias(String name) { return entityNode.first("alias", "name", name); }

    @Override
    public EntityDynamicView addRelationship(String type, String title, String relatedEntityName, Map<String, String> entityKeyMaps) {
        MNode viewLink = entityNode.append("relationship",new ConcurrentHashMap<String,String>(){{
            put("type",type);
            put("title",title);
            put("related",relatedEntityName);
        }});

        for (Map.Entry<String, String> keyMapEntry : entityKeyMaps.entrySet()) {
            viewLink.append("key-map", new ConcurrentHashMap<String,String>(){{
                put("field-name",keyMapEntry.getKey());
                put("related",keyMapEntry.getValue());
            }});
        }
        return this;
    }
}
