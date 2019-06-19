package com.zmtech.zkit.entity.impl;

import com.zmtech.zkit.cache.impl.CacheFacadeImpl;
import com.zmtech.zkit.entity.EntityCondition;
import com.zmtech.zkit.entity.EntityList;
import com.zmtech.zkit.entity.EntityValue;
import com.zmtech.zkit.util.MNode;
import com.zmtech.zkit.util.SimpleTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class EntityCache {

    protected final static Logger logger = LoggerFactory.getLogger(EntityCache.class);

    protected final EntityFacadeImpl efi;
    final CacheFacadeImpl cfi;

    static final String oneKeyBase = "entity.record.one.";
    static final String oneRaKeyBase = "entity.record.one_ra.";
    static final String oneViewRaKeyBase = "entity.record.one_view_ra.";
    static final String listKeyBase = "entity.record.list.";
    static final String listRaKeyBase = "entity.record.list_ra.";
    static final String listViewRaKeyBase = "entity.record.list_view_ra.";
    static final String countKeyBase = "entity.record.count.";

    private Cache<String, Set<EntityCondition>> oneBfCache;
    private final Map<String, List<String>> cachedListViewEntitiesByMember = new HashMap<>();

    private final boolean distributedCacheInvalidate;
    /** 实体缓存无效 Topic */
    private SimpleTopic<EntityCacheInvalidate> entityCacheInvalidateTopic = null;

    EntityCache(EntityFacadeImpl efi) {
        this.efi = efi;
        this.cfi = (CacheFacadeImpl)efi.ecfi.getCache();

        oneBfCache = cfi.getCache("entity.record.one_bf");

        MNode entityFacadeNode = efi.getEntityFacadeNode();
        distributedCacheInvalidate = entityFacadeNode.attribute("distributed-cache-invalidate").equals("true") && entityFacadeNode.attribute("dci-topic-factory") != null;
        logger.info("实体缓存信息: 实体缓存已经初始化完成, 分布式缓存未启用: "+distributedCacheInvalidate);

        if (distributedCacheInvalidate) {
            try {
                String dciTopicFactory = entityFacadeNode.attribute("dci-topic-factory");
                entityCacheInvalidateTopic = (SimpleTopic<EntityCacheInvalidate>) efi.ecfi.getTool(dciTopicFactory, SimpleTopic.class);
            } catch (Exception e) {
                logger.error("实体缓存错误: 实体分布式缓存已启用但初始化失败!", e);
            }
        }
    }

    public static class EntityCacheInvalidate implements Externalizable {
        boolean isCreate;
        EntityValueBase evb;

        public EntityCacheInvalidate() { }

        EntityCacheInvalidate(EntityValueBase evb, boolean isCreate) {
            this.isCreate = isCreate;
            this.evb = evb;
        }

        @Override
        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeBoolean(isCreate);
            // 本来可以更快,但不知道使用了哪个抽象类的impl：evb.writeExternal（out）,所以不行
            out.writeObject(evb);
        }

        @Override
        public void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
            isCreate = objectInput.readBoolean();
            evb = (EntityValueBase) objectInput.readObject();
        }
    }

    public static class EmptyRecord extends EntityValueImpl {
        public EmptyRecord() { }
        EmptyRecord(EntityDefinition ed, EntityFacadeImpl efip) { super(ed, efip); }
    }

    void putInOneCache(EntityDefinition ed, EntityCondition whereCondition, EntityValueBase newEntityValue,
                       Cache<EntityCondition, EntityValueBase> entityOneCache) {
        if (entityOneCache == null) entityOneCache = ed.getCacheOne(this);

        if (newEntityValue != null) newEntityValue.setFromCache();
        entityOneCache.put(whereCondition, newEntityValue != null ? newEntityValue : new EmptyRecord(ed, efi));
        // 需要注册RA，以防条件不是主键
        registerCacheOneRa(ed.getFullEntityName(), whereCondition, newEntityValue);
    }

    EntityListImpl getFromListCache(EntityDefinition ed, EntityCondition whereCondition, List<String> orderByList,
                                    Cache<EntityCondition, EntityListImpl> entityListCache) {
        if (whereCondition == null) return null;
        if (entityListCache == null) entityListCache = ed.getCacheList(this);

        EntityListImpl cacheHit = entityListCache.get(whereCondition);
        if (cacheHit != null && orderByList != null && orderByList.size() > 0) cacheHit.orderByFields(orderByList);
        return cacheHit;
    }
    void putInListCache(EntityDefinition ed, EntityListImpl el, EntityCondition whereCondition,
                        Cache<EntityCondition, EntityListImpl> entityListCache) {
        if (whereCondition == null) return;
        if (entityListCache == null) entityListCache = ed.getCacheList(this);

        // EntityList elToCache = el != null ? el : EntityListImpl.EMPTY
        EntityListImpl elToCache = el != null ? el : efi.getEmptyList();
        elToCache.setFromCache();
        entityListCache.put(whereCondition, elToCache);
        registerCacheListRa(ed.getFullEntityName(), whereCondition, elToCache);
    }
    /*
    Long getFromCountCache(EntityDefinition ed, EntityCondition whereCondition, Cache<EntityCondition, Long> entityCountCache) {
        if (entityCountCache == null) entityCountCache = getCacheCount(ed.getFullEntityName())
        return (Long) entityCountCache.get(whereCondition)
    }
    */

    /** 从EntityValueBase调用 */
    void clearCacheForValue(EntityValueBase evb, boolean isCreate) {
        if (evb == null) return;
        EntityDefinition ed = evb.getEntityDefinition();
        if (ed.entityInfo.neverCache) return;

        // String entityName = evb.getEntityName()
        // if (!entityName.startsWith("moqui.")) logger.info("========== ========== ========== clearCacheForValue ${entityName}")
        if (distributedCacheInvalidate && entityCacheInvalidateTopic != null) {
            // 注意:对几乎所有实体的CrUD操作，这需要完成很多并且需要一些时间来运行
            // 注意: 将许多实体设置为永不缓存
            // 注意：当缓存不存在且未在view-entity时，无法避免消息，因为它可能位于另一台服务器上
            EntityCacheInvalidate eci = new EntityCacheInvalidate(evb, isCreate);
            entityCacheInvalidateTopic.publish(eci);
        } else {
            clearCacheForValueActual(evb, isCreate);
        }
    }
    /** 实际缓存是否清除，或通过 topic 直接调用或者分布调用*/
    private void clearCacheForValueActual(EntityValueBase evb, boolean isCreate) {
        // logger.info("====== clearCacheForValueActual isCreate=${isCreate}, evb: ${evb}")
        try {
            EntityDefinition ed = evb.getEntityDefinition();
            // 使用getValueMap而不用getMap，更快，我们不想缓存本地化的值/等
            Map<String,Object> evbMap = evb.getValueMap();
            // 在clearCacheForValue（）中检查：if（'never'.equals（ed.getUseCache（）））返回
            String fullEntityName = ed.entityInfo.fullEntityName;
            // 将此初始化为null，如果需要就设置一下（常见情况设置为null，会表现更好）
            EntityCondition pkCondition = null;

            // 注意：用于检查缓存是否仅存在，不要用于实际获取缓存
            ConcurrentMap<String, Cache> localCacheMap = cfi.localCacheMap;

            // 清除一个缓存
            String oneKey = oneKeyBase.concat(fullEntityName);
            if (localCacheMap.containsKey(oneKey)) {
                pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys());

                Cache<EntityCondition, EntityValueBase> entityOneCache = ed.getCacheOne(this);
                // 通过PK清除，最常见的情况
                entityOneCache.remove(pkCondition);

                // 注意：由于非pk更新等原因，这两个必须完成，无论它是否为创建
                // 看看是否有任何一个RA数据
                Cache<EntityCondition, Set<EntityCondition>> oneRaCache = ed.getCacheOneRa(this);
                Set<EntityCondition> raKeyList = oneRaCache.get(pkCondition);
                if (raKeyList != null) {
                    for (EntityCondition ec : raKeyList) {
                        entityOneCache.remove(ec);
                    }
                    // 我们已经清除了所指的所有条目，所以也要清理它
                    oneRaCache.remove(pkCondition);
                }
                // 使用bf（强力）匹配查看是否存在任何没有结果的缓存条目
                Set<EntityCondition> bfKeySet = oneBfCache.get(fullEntityName);
                if (bfKeySet != null && bfKeySet.size() > 0) {
                    ArrayList<EntityCondition> keysToRemove = new ArrayList<>();
                    for (EntityCondition bfKey : bfKeySet) {
                        if (bfKey.mapMatches(evbMap)) keysToRemove.add(bfKey);
                    }
                    for (EntityCondition key : keysToRemove) {
                        entityOneCache.remove(key);
                        bfKeySet.remove(key);
                    }
                }
            }

            // 使用BF（强力）匹配查看是否存在任何没有结果的缓存条目
            String oneViewRaKey = oneViewRaKeyBase.concat(fullEntityName);
            if (localCacheMap.containsKey(oneViewRaKey)) {
                if (pkCondition == null) pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys());

                Cache<EntityCondition, Set<ViewRaKey>> oneViewRaCache = ed.getCacheOneViewRa(this);
                Set<ViewRaKey> oneViewRaKeyList = oneViewRaCache.get(pkCondition);
                // if (fullEntityName.contains("FOO")) logger.warn("======= clearCacheForValue ${fullEntityName}, PK ${pkCondition}, oneViewRaKeyList: ${oneViewRaKeyList}")
                if (oneViewRaKeyList != null) {
                    for (ViewRaKey raKey : oneViewRaKeyList) {
                        EntityDefinition raEd = efi.getEntityDefinition(raKey.entityName);
                        Cache<EntityCondition, EntityValueBase> viewEntityOneCache = raEd.getCacheOne(this);
                        // 可能已经被清除，需要浪费一些时间检查一下是否确定被清除
                        viewEntityOneCache.remove(raKey.ec);
                    }
                    // 我们已经清除了所指的所有条目，所以也要清理它
                    oneViewRaCache.remove(pkCondition);
                }
            }

            // 清除列表缓存，使用反向关联映射（也是缓存）
            String listKey = listKeyBase.concat(fullEntityName);
            if (localCacheMap.containsKey(listKey)) {
                if (pkCondition == null) pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys());

                Cache<EntityCondition, EntityListImpl> entityListCache = ed.getCacheList(this);

                // 如果是创建RA缓存是不行的，所以请通过EACH条目查看它是否与创建的值匹配RA缓存不适用于更新字段不匹配的存在记录.
                // 在最初完成缓存列表查找时查找条件，但随后更新以使字段匹配
                for (Cache.Entry<EntityCondition, EntityListImpl> entry : entityListCache) {
                    EntityCondition ec = entry.getKey();
                    // 有效清除这些RA缓存的方法有哪些？ 现在只是放着，最后最终处理
                    if (ec.mapMatches(evbMap)) entityListCache.remove(ec);
                }

                // 如果更新，还要检查反向关联（RA），因为上面的条件检查可能与新值或部分更新的记录不匹配
                if (!isCreate) {
                    // 首先只列出RA缓存
                    Cache<EntityCondition, Set<EntityCondition>> listRaCache = ed.getCacheListRa(this);
                    // logger.warn("============= clearing list for entity ${fullEntityName}, for pkCondition [${pkCondition}] listRaCache=${listRaCache}")
                    Set<EntityCondition> raKeyList = listRaCache.get(pkCondition);
                    if (raKeyList != null) {
                        // logger.warn("============= for entity ${fullEntityName}, for pkCondition [${pkCondition}], raKeyList for clear=${raKeyList}")
                        for (EntityCondition raKey : raKeyList) {
                            // logger.warn("============= for entity ${fullEntityName}, removing raKey=${raKey} from ${entityListCache.getName()}")
                            // 这可能已经被清除，需要用些时间检查确定已经被清除
                            entityListCache.remove(raKey);
                        }
                        // 我们已经清除了所指的所有条目，所以也要清理它
                        listRaCache.remove(pkCondition);
                    }

                    // 现在列表视图RA缓存同样处理
                    Cache<EntityCondition, Set<ViewRaKey>> listViewRaCache = ed.getCacheListViewRa(this);
                    // logger.warn("============= clearing view list for entity ${fullEntityName}, for pkCondition [${pkCondition}] listViewRaCache=${listViewRaCache}")
                    Set<ViewRaKey> listViewRaKeyList = listViewRaCache.get(pkCondition);
                    if (listViewRaKeyList != null) {
                        // logger.warn("============= for entity ${fullEntityName}, for pkCondition [${pkCondition}], listViewRaKeyList for clear=${listViewRaKeyList}")
                        for (ViewRaKey raKey : listViewRaKeyList) {
                            // logger.warn("============= for entity ${fullEntityName}, removing raKey=${raKey} from ${entityListCache.getName()}")
                            EntityDefinition raEd = efi.getEntityDefinition(raKey.entityName);
                            Cache<EntityCondition, EntityListImpl> viewEntityListCache = raEd.getCacheList(this);
                            // 可能已经被清除，需要浪费一些时间检查一下是否确定被清除
                            viewEntityListCache.remove(raKey.ec);
                        }
                        // 我们已经清除了所指的所有条目，所以也要清理缓存
                        listViewRaCache.remove(pkCondition);
                    }
                }
            }

            // 查看此实体是否是缓存视图实体的成员
            List<String> cachedViewEntityNames = cachedListViewEntitiesByMember.get(fullEntityName);
            if (cachedViewEntityNames != null) {
                synchronized (cachedViewEntityNames) {
                    for (String viewEntityName : cachedViewEntityNames) {
                        // logger.warn("Found ${cachedViewEntityName} as a cached view-entity for member ${fullEntityName}")

                        EntityDefinition viewEd = efi.getEntityDefinition(viewEntityName);

                        // 通常匹配成员实体句柄情况下的字段的视图实体别名，其中当前记录（evbMap）具有来自view-entity的一些键但不是全部（如UserPermissionCheck）
                        Map<String, Object> viewMatchMap = new HashMap<>();
                        Map<String, ArrayList<MNode>> memberFieldAliases = viewEd.getMemberFieldAliases(fullEntityName);
                        for (Map.Entry<String, ArrayList<MNode>> mfAliasEntry : memberFieldAliases.entrySet()) {
                            String fieldName = mfAliasEntry.getKey();
                            if (!evbMap.containsKey(fieldName)) continue;
                            Object fieldValue = evbMap.get(fieldName);
                            ArrayList<MNode> aliasNodeList = mfAliasEntry.getValue();
                            for (MNode aliasNode : aliasNodeList) {
                                viewMatchMap.put(aliasNode.attribute("name"), fieldValue);
                            }
                        }
                        // logger.warn("========= viewMatchMap: ${viewMatchMap}")

                        Cache<EntityCondition, EntityListImpl> entityListCache = viewEd.getCacheList(this);

                        Iterator<Cache.Entry<EntityCondition, EntityListImpl>> elcIterator = entityListCache.iterator();
                        while (elcIterator.hasNext()) {
                            Cache.Entry<EntityCondition, EntityListImpl> entry = elcIterator.next();
                            // 在javax.cache.Cache中，next（）可能会为过期的etc条目并且返回null
                            if (entry == null) continue;
                            EntityCondition econd = entry.getKey();
                            // logger.warn("======= entity ${fullEntityName} view-entity ${cachedViewEntityName} matches any? ${econd.mapMatchesAny(viewMatchMap)} keys not contained? ${econd.mapKeysNotContained(viewMatchMap)} econd: ${econd}")
                            // 将来可能：有效清除这些RA缓存的方法是什么？
                            // 现在只是离开并且他们被处理最终不需要完全匹配，
                            // 如果匹配条件,清除时注意：mapKeysNotContained（）调用将处理没有负匹配的情况，
                            // 但是过度包容并将清除缓存 可能不需要清除的条目;
                            // 可能有更好的方法; 特别需要主要成员实体上的字段查询列表但更新另一个成员实体的情况
                            if (econd.mapMatchesAny(viewMatchMap) || econd.mapKeysNotContained(viewMatchMap))
                                elcIterator.remove();
                        }
                    }
                }
            }

            // 清除计数缓存（没有RA因为我们只有一个计数可以使用，只是按条件匹配）
            String countKey = countKeyBase.concat(fullEntityName);
            if (localCacheMap.containsKey(countKey)) {
                Cache<EntityCondition, Long> entityCountCache = ed.getCacheCount(this);
                // 由于关于计数缓存结果的信息很少，
                // 我们不能做RA并且检查条件无法在值不再匹配的情况下清除，
                // 将处理新匹配的清除计数增加但不再不匹配计数减少的情况除了选择清除整个缓存
                entityCountCache.clear();
                /*
                Iterator<Cache.Entry<EntityCondition, Long>> eccIterator = entityCountCache.iterator()
                while (eccIterator.hasNext()) {
                    Cache.Entry<EntityCondition, Long> entry = (Cache.Entry<EntityCondition, Long>) eccIterator.next()
                    EntityCondition ec = (EntityCondition) entry.getKey()
                    logger.warn("checking count condition: ${ec.toString()} matches? ${ec.mapMatchesAny(evbMap) || ec.mapKeysNotContained(evbMap)}")
                    if (ec.mapMatchesAny(evbMap) || ec.mapKeysNotContained(evbMap)) eccIterator.remove()
                }
                */
            }
        } catch (Throwable t) {
            logger.error("实体缓存错误: 实体"+evb.getEntityName()+"缓存清除中的抑制错误: "+(isCreate ? "create" : "non-create"), t);
        }
    }
    private void registerCacheOneRa(String entityName, EntityCondition ec, EntityValueBase evb) {
        // 不要跳过它的空值，因为我们也缓存它们：if（evb == null）return
        if (evb == null) {
            // 不能使用RA缓存，因为我们不知道PK，所以使用暴力缓存但保持分开以更好地执行
            Set<EntityCondition> bfKeySet = oneBfCache.get(entityName);
            if (bfKeySet == null) {
                bfKeySet = ConcurrentHashMap.newKeySet();
                oneBfCache.put(entityName, bfKeySet);
            }
            bfKeySet.add(ec);
        } else {
            EntityDefinition ed = evb.getEntityDefinition();
            Cache<EntityCondition, Set<EntityCondition>> oneRaCache = ed.getCacheOneRa(this);
            EntityCondition pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys());
            // 如果条件与主键匹配，则不需要RA条目
            if (pkCondition != ec) {
                Set<EntityCondition> raKeyList = oneRaCache.get(pkCondition);
                if (raKeyList == null) {
                    raKeyList = ConcurrentHashMap.newKeySet();
                    oneRaCache.put(pkCondition, raKeyList);
                }
                raKeyList.add(ec);
            }

            // 如果这是一个视图实体，我们需要查看每个成员实体的RA条目（我们有一个PK）
            if (ed.isViewEntity) {
                // 每个成员实体走一遍
                ArrayList<MNode> memberEntityList = ed.getEntityNode().children("member-entity");
                for (MNode memberEntityNode : memberEntityList) {
                    Map<String, String> mePkFieldToAliasNameMap = ed.getMePkFieldToAliasNameMap(memberEntityNode.attribute("entity-alias"));

                    if (mePkFieldToAliasNameMap.isEmpty()) {
                        logger.warn("实体缓存警告: 视图实体 ["+entityName+"] 成员实体 ["+memberEntityNode.attribute("@entity-name")+"] 的主键别名为空!");
                        continue;
                    }
                    // 创建具有pk字段的EntityCondition，其中主要ec具有视图实体名称，在RA缓存中用于成员实体名称的视图实体，其缓存键为member-entity PK EntityCondition obj
                    EntityDefinition memberEd = efi.getEntityDefinition(memberEntityNode.attribute("entity-name"));
                    // String memberEntityName = memberEd.getFullEntityName()

                    Map<String, Object> pkCondMap = new HashMap<>();
                    for (Map.Entry<String, String> mePkEntry : mePkFieldToAliasNameMap.entrySet())
                        pkCondMap.put(mePkEntry.getKey(), evb.getNoCheckSimple(mePkEntry.getValue()));
                    // 没有PK字段？ view-entity肯定没有，跳过它
                    if (pkCondMap.size() == 0) continue;

                    // logger.warn("====== for view-entity ${entityName}, member-entity ${memberEd.fullEntityName}, got PK field to alias map: ${mePkFieldToAliasNameMap}\npkCondMap: ${pkCondMap}")

                    Cache<EntityCondition, Set<ViewRaKey>> oneViewRaCache = memberEd.getCacheOneViewRa(this);
                    EntityCondition memberPkCondition = efi.getConditionFactory().makeCondition(pkCondMap);
                    Set<ViewRaKey> raKeyList = oneViewRaCache.get(memberPkCondition);
                    ViewRaKey newRaKey = new ViewRaKey(entityName, ec);
                    // logger.warn("===== added ViewRaKey for ${memberEntityName}, PK ${memberPkCondition}, raKeyList: ${raKeyList}")
                    if (raKeyList == null) {
                        raKeyList = ConcurrentHashMap.newKeySet();
                        oneViewRaCache.put(memberPkCondition, raKeyList);
                        raKeyList.add(newRaKey);
                        // logger.warn("===== added ViewRaKey for ${memberEntityName}, PK ${memberPkCondition}, raKeyList: ${raKeyList}")
                    } else raKeyList.add(newRaKey);
                }
            }
        }
    }

    private void registerCacheListRa(String entityName, EntityCondition ec, EntityList eli) {
        EntityDefinition ed = efi.getEntityDefinition(entityName);
        if (ed.isViewEntity) {
            // 所有成员实体走一遍
            ArrayList<MNode> memberEntityList = ed.getEntityNode().children("member-entity");
            for (MNode mNode : memberEntityList) {
                Map<String, String> mePkFieldToAliasNameMap = ed.getMePkFieldToAliasNameMap(mNode.attribute("entity-alias"));

                if (mePkFieldToAliasNameMap.isEmpty()) {
                    logger.warn("实体缓存警告: 视图实体 ["+entityName+"] 成员实体 ["+mNode.attribute("@entity-name")+"] 的主键别名为空!");
                    continue;
                }
                // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, got PK field to alias map: ${mePkFieldToAliasNameMap}")

                // 使用主键字段创建实体条件
                // 将主要ec与视图实体名称一起存储在RA缓存中，用于成员实体名称的视图实体
                // 使用成员实体PK EntityCondition obj的缓存键
                EntityDefinition memberEd = efi.getEntityDefinition(mNode.attribute("entity-name"));
                String memberEntityName = memberEd.getFullEntityName();

                // 请记住，此成员实体已在缓存的视图实体中使用
                List<String> cachedViewEntityNames = cachedListViewEntitiesByMember.get(memberEntityName);
                if (cachedViewEntityNames == null) {
                    cachedViewEntityNames = Collections.synchronizedList(new ArrayList<>());
                    cachedListViewEntitiesByMember.put(memberEntityName, cachedViewEntityNames);
                    cachedViewEntityNames.add(entityName);
                    // logger.info("Added ${entityName} as a cached view-entity for member ${memberEntityName}")
                } else if (!cachedViewEntityNames.contains(entityName)) {
                    cachedViewEntityNames.add(entityName);
                    // logger.info("Added ${entityName} as a cached view-entity for member ${memberEntityName}")
                }

                Cache<EntityCondition, Set<ViewRaKey>> listViewRaCache = memberEd.getCacheListViewRa(this);
                for (EntityValue ev : eli) {
                    Map<String, Object> pkCondMap = new HashMap<>();
                    for (Map.Entry<String, String> mePkEntry : mePkFieldToAliasNameMap.entrySet())
                        pkCondMap.put(mePkEntry.getKey(), ev.getNoCheckSimple(mePkEntry.getValue()));

                    EntityCondition pkCondition = efi.getConditionFactory().makeCondition(pkCondMap);
                    Set<ViewRaKey> raKeyList = listViewRaCache.get(pkCondition);
                    ViewRaKey newRaKey = new ViewRaKey(entityName, ec);
                    if (raKeyList == null) {
                        raKeyList = ConcurrentHashMap.newKeySet();
                        listViewRaCache.put(pkCondition, raKeyList);
                        raKeyList.add(newRaKey);
                    } else raKeyList.add(newRaKey);
                    // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, for pkCondition [${pkCondition}], raKeyList after add=${raKeyList}")
                }
            }
        } else {
            Cache<EntityCondition, Set<EntityCondition>> listRaCache = ed.getCacheListRa(this);

            for (EntityValue entityValue : eli) {
                EntityCondition pkCondition = efi.getConditionFactory().makeCondition((entityValue).getPrimaryKeys());
                // 注意：这里是内存泄漏，使用List它随着时间的推移变得非常大，有重复的查找列表条件，请改用Set
                Set<EntityCondition> raKeyList = listRaCache.get(pkCondition);
                if (raKeyList == null) {
                    raKeyList = ConcurrentHashMap.newKeySet();
                    listRaCache.put(pkCondition, raKeyList);
                }
                raKeyList.add(ec);
            }
        }
    }

    static class ViewRaKey implements Serializable {
        final String entityName;
        final EntityCondition ec;
        final int hashCodeVal;
        ViewRaKey(String entityName, EntityCondition ec) {
            this.entityName = entityName; this.ec = ec;
            hashCodeVal = entityName.hashCode() + ec.hashCode();
        }

        @Override
        public int hashCode() { return hashCodeVal; }
        @Override
        public boolean equals(Object obj) {
            if (obj.getClass() != ViewRaKey.class) return false;
            ViewRaKey that = (ViewRaKey) obj;
            if (!entityName.equals(that.entityName)) return false;
            return ec.equals(that.ec);
        }
        @Override
        public String toString() { return entityName + '(' + ec.toString() + ')'; }
    }
}
