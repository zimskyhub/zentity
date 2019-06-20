package com.zmtech.zkit.transaction.impl;

import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.entity.EntityCondition;
import com.zmtech.zkit.entity.EntityValue;
import com.zmtech.zkit.entity.impl.*;
import com.zmtech.zkit.util.EntityJavaUtil.*;
import com.zmtech.zkit.exception.EntityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Synchronization;
import javax.transaction.xa.XAException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这是事务高速缓存，基本功能是伪装成数据库的事务范围。
 * 使用它时测试代码很好，因为它不支持所有功能。
 * 请参阅JavaDoc for ServiceCallSync.useTransactionCache（）中的限制说明
 */
public class TransactionCache implements Synchronization {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionCache.class);

    protected ExecutionContextFactoryImpl ecfi;
    private boolean readOnly;

    private Map<Map, EntityValueBase> readOneCache = new HashMap<>();
    private Set<Map> knownLocked = new HashSet<>();
    private Map<String, Map<EntityCondition, EntityListImpl>> readListCache = new ConcurrentHashMap<>();

    private Map<Map, EntityWriteInfo> firstWriteInfoMap = new HashMap<>();
    private Map<Map, EntityWriteInfo> lastWriteInfoMap = new HashMap<>();
    private ArrayList<EntityWriteInfo> writeInfoList = new ArrayList<>(50);
    private LinkedHashMap<String, LinkedHashMap<Map, EntityValueBase>> createByEntityRef = new LinkedHashMap<>();

    public TransactionCache(ExecutionContextFactoryImpl ecfi, boolean readOnly) {
        this.ecfi = ecfi;
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() { return readOnly; }
    public void makeReadOnly() {
        if (readOnly) return;
        try {
            flushCache(false);
        } catch (XAException e) {
            e.printStackTrace();
        }
        readOnly = true;
    }
    public void makeWriteThrough() { readOnly = false;}

    public LinkedHashMap<Map, EntityValueBase> getCreateByEntityMap(String entityName) {
        return createByEntityRef.computeIfAbsent(entityName, k -> new LinkedHashMap<>());
    }

    public static Map<String, Object> makeKey(EntityValueBase evb) {
        if (evb == null) return null;
        Map<String, Object> key = evb.getPrimaryKeys();
        if (key == null) return null;
        key.put("_entityName", evb.getEntityName());
        return key;
    }
    public static Map<String,Object> makeKeyFind(EntityFindBase efb) {
        // 注意：这应该永远不会为null（EntityFindBase.one（）=> oneGet（）=>这只是调用路径）
        if (efb == null) return null;
        Map<String,Object> key = efb.getSimpleMapPrimaryKeys();
        if (key == null) return null;
        key.put("_entityName", efb.getEntityDef().getFullEntityName());
        return key;
    }
    public void addWriteInfo(Map<String, Object> key, EntityWriteInfo newEwi) {
        writeInfoList.add(newEwi);
        if (!firstWriteInfoMap.containsKey(key)) firstWriteInfoMap.put(key, newEwi);
        lastWriteInfoMap.put(key, newEwi);
    }

    /** 如果创建处理器则返回true，否则返回false; 如果false调用者应该处理该操作 */
    public boolean create(EntityValueBase evb) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;

        if (!readOnly) {
            EntityWriteInfo currentEwi = lastWriteInfoMap.get(key);
            if (readOneCache.get(key) != null)
                throw new EntityException("事务缓存错误: 不能创建数据库已经存在的值, 实体 ["+evb.getEntityName()+"], 主键 ["+evb.getPrimaryKeys()+"]");
            if (currentEwi != null && currentEwi.writeMode != WriteMode.DELETE)
                throw new EntityException("事务缓存错误: 不能创建缓存中已经存在的值 实体 ["+evb.getEntityName()+"] 主键 ["+evb.getPrimaryKeys()+"]");

            EntityWriteInfo newEwi = new EntityWriteInfo(evb, WriteMode.CREATE);
            addWriteInfo(key, newEwi);
            if (currentEwi == null || currentEwi.writeMode != WriteMode.DELETE) {
                getCreateByEntityMap(evb.getEntityName()).put(evb.getPrimaryKeys(), evb);
            }
        }

        // 添加到readCache后我们也认为它不存在
        readOneCache.put(key, evb);
        // 添加到任何匹配列表缓存入口
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(evb.getEntityName());
        if (entityListCache != null) {
            for (Map.Entry<EntityCondition, EntityListImpl> entry : entityListCache.entrySet()) {
                if (entry.getKey().mapMatches(evb)) entry.getValue().add(evb);
            }
        }

        // 考虑锁定创建的记录以避免forUpdate查询
        knownLocked.add(key);

        return !readOnly;
    }
    public boolean update(EntityValueBase evb) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;

        if (!readOnly) {
            // 使用writeInfoList作为普通列表方法无需查找现有的创建或更新，只需添加到列表中即可
            if (!evb.getIsFromDb()) {
                EntityValueBase cacheEvb = readOneCache.get(key);
                if (cacheEvb != null) {
                    cacheEvb.setFields(evb, true, null, false);
                    evb = cacheEvb;
                } else {
                    EntityValueBase dbEvb = (EntityValueBase) evb.cloneValue();
                    dbEvb.refresh();
                    dbEvb.setFields(evb, true, null, false);
                    logger.warn("事务缓存警告: ====== 没有事务缓存更新: db\nevb: "+evb+"\ndbEvb: "+dbEvb);
                    evb = dbEvb;
                }
            }

            EntityWriteInfo newEwi = new EntityWriteInfo(evb, WriteMode.UPDATE);
            addWriteInfo(key, newEwi);
        }

        // 添加到readCache
        if (evb.getIsFromDb()) {
            readOneCache.put(key, evb);
        } else {
            // 可能有部分值不是来自DB，所以找到现有的并从valueMap中放入所有值
            EntityValueBase existingEv = readOneCache.get(key);
            if (existingEv != null) {
                existingEv.putAll(evb);
            } else {
                // 注意：如果不是只读，应该放一个不是DB值？ 如果只阅读肯定没有
                if (!readOnly) readOneCache.put(key, evb);
            }
        }

        // 注意：如果是局部evb，没有从DB /缓存中填满，并且没有匹配的字段值，则在此处发出问题; 获得全部价值来解决更高的问题？ 更新任何匹配列表缓存条目，如果不存在则添加到列表缓存（尽管通常应该是，具体取决于条件）
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(evb.getEntityName());
        if (entityListCache != null) {
            for (Map.Entry<EntityCondition, EntityListImpl> entry : entityListCache.entrySet()) {
                if (entry.getKey().mapMatches(evb)) {
                    // 找到现有入口并进行更新
                    boolean foundEntry = false;
                    EntityListImpl eli = entry.getValue();

                    for (EntityValue entityValue : eli) {
                        EntityValueBase existingEv = (EntityValueBase) entityValue;
                        if (evb.primaryKeyMatches(existingEv)) {
                            existingEv.putAll(evb);
                            foundEntry = true;
                        }
                    }
                    // 如果没有现有条目添加此项
                    if (!foundEntry) entry.getValue().add(evb);
                }
            }
        }

        return !readOnly;
    }

    public boolean delete(EntityValueBase evb) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;
        // logger.warn("txc delete ${key}")

        if (!readOnly) {
            EntityWriteInfo currentEwi = firstWriteInfoMap.get(key);
            if (currentEwi != null && currentEwi.writeMode == WriteMode.CREATE) {
                // 如果是在TX缓存中创建但从未写入DB只清除所有更改
                firstWriteInfoMap.remove(key);
                lastWriteInfoMap.remove(key);
                for (int i = 0; i < writeInfoList.size(); ) {
                    EntityWriteInfo ewi = writeInfoList.get(i);
                    if (key.equals(makeKey(ewi.evb))) { writeInfoList.remove(i); }
                    else { i++; }
                }
                getCreateByEntityMap(evb.getEntityName()).remove(evb.getPrimaryKeys());
            } else {
                EntityWriteInfo newEwi = new EntityWriteInfo(evb, WriteMode.DELETE);
                addWriteInfo(key, newEwi);
            }
        }

        // 如果需要，从readCache中删除
        readOneCache.remove(key);
        // 删除所有匹配列表缓存条目
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(evb.getEntityName());
        if (entityListCache != null) {
            for (Map.Entry<EntityCondition, EntityListImpl> entry : entityListCache.entrySet()) {
                if (entry.getKey().mapMatches(evb)) {
                    Iterator existingEvIter = entry.getValue().iterator();
                    while (existingEvIter.hasNext()) {
                        EntityValue existingEv = (EntityValue) existingEvIter.next();
                        if (evb.getPrimaryKeys() == existingEv.getPrimaryKeys()) existingEvIter.remove();
                    }
                }
            }
        }

        return !readOnly;
    }
    public boolean refresh(EntityValueBase evb) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;
        EntityValueBase curEvb = readOneCache.get(key);
        if (curEvb != null) {
            ArrayList<String> nonPkFieldList = evb.getEntityDefinition().getNonPkFieldNames();
            for (String fieldName : nonPkFieldList) {
                evb.getValueMap().put(fieldName, curEvb.getValueMap().get(fieldName));
            }
            evb.setSyncedWithDb();
            return true;
        } else {
            return false;
        }
    }

    public boolean isTxCreate(EntityValueBase evb) {
        if (readOnly || writeInfoList.size() == 0) return false;
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;
        return isTxCreate(key);
    }
    protected boolean isTxCreate(Map key) {
        if (readOnly || writeInfoList.size() == 0) return false;
        EntityWriteInfo currentEwi = firstWriteInfoMap.get(key);
        if (currentEwi == null) return false;
        return currentEwi.writeMode == WriteMode.CREATE;
    }

    public boolean isKnownLocked(EntityValueBase evb) {
        if (readOnly || knownLocked.size() == 0) return false;
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;
        return knownLocked.contains(key);
    }
    public EntityValueBase oneGet(EntityFindBase efb) {
        // 注意：此处不执行forUpdate，由调用者处理
        Map<String, Object> key = makeKeyFind(efb);
        if (key == null) return null;

        if (!readOnly) {
            // 如果已删除它，则返回DeletedEntityValue实例，以便调用者知道它已被删除，并且不会在数据库中查找另一条记录
            EntityWriteInfo currentEwi = (EntityWriteInfo) lastWriteInfoMap.get(key);
            if (currentEwi != null && currentEwi.writeMode == WriteMode.DELETE)
                return new EntityValueBase.DeletedEntityValue(efb.getEntityDef(), (EntityFacadeImpl) ecfi.getEntity());
        }

        // cloneValue（），以便在更新完成之前更新不在读取缓存中
        return readOneCache.get(key) != null ? (EntityValueBase) readOneCache.get(key).cloneValue():null;
    }
    public void onePut(EntityValueBase evb, boolean forUpdate) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return;
        EntityWriteInfo currentEwi = lastWriteInfoMap.get(key);
        // 如果这已被删除，我们不想添加它，但一般情况下，如果我们有一个ewi，
        // 那么它已经在缓存中，我们不想从此更新（通常来自数据库，可能比已经存在的值更旧）
        // 在将值放入缓存之前克隆该值，以便调用者以后不能使用更新调用更改它
        if (currentEwi == null || currentEwi.writeMode != WriteMode.DELETE) readOneCache.put(key, (EntityValueBase) evb.cloneValue());

        // if (evb.getEntityDefinition().getEntityName() == "Asset") logger.warn("=========== onePut of Asset ${evb.get('assetId')}", new Exception("Location"))

        if (forUpdate) knownLocked.add(key);
    }

    public EntityListImpl listGet(EntityDefinition ed, EntityCondition whereCondition, List<String> orderByExpanded) {
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(ed.getFullEntityName());
        // 总是克隆，以便调用者的过滤器/排序/等不会改变它
        EntityListImpl cacheList = entityListCache != null ? entityListCache.get(whereCondition) !=null?entityListCache.get(whereCondition).deepCloneList():null : null;

        // 如果我们通过相关实体上的PK字段搜索正在搜索的字段，它只能存在于读缓存中，所以在这里找到并且不要打扰数据库查询
        if (cacheList == null) {
            // 如果条件依赖于在此tx缓存中创建的记录，则从此处构建列表而不是让它放到数据库中，如果找不到任何内容，txCache扩展
            Map<String, Object> condMap = new ConcurrentHashMap<>();
            if (whereCondition != null && whereCondition.populateMap(condMap)) {
                boolean foundCreatedDependent = false;

                for (RelationshipInfo relInfo : ed.getRelationshipsInfo(false)) {
                    if (relInfo.type.equals("one")) continue;
                    // 跳过也行，但是相关实体名称可能不是完整的实体名称
                    EntityDefinition relEd = relInfo.relatedEd;
                    String relEntityName = relEd.getFullEntityName();
                    // 首先看看是否有一个创建Map，然后进行更昂贵的操作，获取扩展键Map和相关实体的PK Map
                    Map relCreateMap = getCreateByEntityMap(relEntityName);
                    if (relCreateMap != null && relCreateMap.size() > 0) {
                        Map<String, String> relKeyMap = relInfo.keyMap;
                        Map<String, String> relPk = new ConcurrentHashMap<>();
                        boolean foundAllPks = true;
                        for (Map.Entry<String, String> entry : relKeyMap.entrySet()) {
                            Object relValue = condMap.get(entry.getKey());
                            if (relValue!=null) relPk.put(entry.getValue(), (String)relValue);
                            else foundAllPks = false;
                        }
                        // if (ed.getFullEntityName().contains("OrderItem")) logger.warn("==== listGet ${relEntityName} foundAllPks=${foundAllPks} relPk=${relPk} relCreateMap=${relCreateMap}")
                        if (!foundAllPks) continue;
                        if (relCreateMap.containsKey(relPk)) {
                            foundCreatedDependent = true;
                            break;
                        }
                    }
                }
                if (foundCreatedDependent) {
                    EntityListImpl createdValueList = new EntityListImpl((EntityFacadeImpl) ecfi.getEntity());
                    Map createMap = createByEntityRef.get(ed.getFullEntityName());
                    if (createMap != null) {
                        for (Object createEvbObj : createMap.values()) {
                            if (createEvbObj instanceof EntityValueBase) {
                                EntityValueBase createEvb = (EntityValueBase) createEvbObj;
                                if (whereCondition.mapMatches(createEvb)) createdValueList.add(createEvb);
                            }
                        }
                    }

                    listPut(ed, whereCondition, createdValueList);
                    cacheList = createdValueList.deepCloneList();
                }
            }
        }

        if (cacheList!=null && cacheList.size() >0 && orderByExpanded != null && orderByExpanded.size() >0 ) cacheList.orderByFields(orderByExpanded);
        return cacheList;
    }
    public Map<EntityCondition, EntityListImpl> getEntityListCache(String entityName) {
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(entityName);
        if (entityListCache == null) {
            entityListCache = new ConcurrentHashMap<>();
            readListCache.put(entityName, entityListCache);
        }
        return entityListCache;
    }
    public void listPut(EntityDefinition ed, EntityCondition whereCondition, EntityListImpl eli) {
        if (eli.isFromCache()) return;
        Map<EntityCondition, EntityListImpl> entityListCache = getEntityListCache(ed.getFullEntityName());
        // 这里不需要做太多其他事情; list中已经有事务缓存的创建/更新/删除的值了
        entityListCache.put(whereCondition, (EntityListImpl) eli.cloneList());
    }

    // 注意：不需要过滤EntityList或EntityListIterator，它们通过调用此方法在内部执行
    public WriteMode checkUpdateValue(EntityValueBase evb, FindAugmentInfo fai) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return null;
        EntityWriteInfo firstEwi = firstWriteInfoMap.get(key);
        EntityWriteInfo currentEwi = lastWriteInfoMap.get(key);
        if (currentEwi == null) {
            // 添加到readCache以供其他引用使用
            onePut(evb, false);
            return null;
        }
        if (WriteMode.CREATE.equals(firstEwi.writeMode)) {
            throw new EntityException("事务缓存错误: 从数据库中找到与在直写事务高速缓存中创建的值匹配的值,抛出异常避免事务提交失败!");
        }
        if (WriteMode.UPDATE.equals(currentEwi.writeMode)) {
            if (fai != null && ((fai.econd != null && !fai.econd.mapMatches(currentEwi.evb)) || fai.foundUpdated.contains(currentEwi.evb.getPrimaryKeys()))) {
                // 当前值不再匹配，告诉ELII跳过它（与DELETE相同
                return WriteMode.DELETE;
            }
            evb.setFields(currentEwi.evb, true, null, false);
            // 添加到readCache
            onePut(evb, false);
        }
        return currentEwi.writeMode;
    }
    public FindAugmentInfo getFindAugmentInfo(String entityName, EntityCondition econd) {
        ArrayList<EntityValueBase> valueList = new ArrayList<>();

        // 还获取已更新的值，以便它们现在应包含在列表中
        Set<Map<String, Object>> foundUpdated = new HashSet<>();
        if (econd != null) {
            int writeInfoListSize = writeInfoList.size();
            // 通过倒退来获得最新的
            for (int i = (writeInfoListSize - 1); i >= 0 ; i--) {
                EntityWriteInfo ewi = writeInfoList.get(i);
                if (WriteMode.UPDATE.equals(ewi.writeMode) && entityName.equals(ewi.evb.getEntityName()) && econd.mapMatches(ewi.evb)) {
                    Map<String, Object> pkMap = ewi.evb.getPrimaryKeys();
                    if (!foundUpdated.contains(pkMap)) {
                        foundUpdated.add(pkMap);
                        valueList.add(ewi.evb);
                    }
                }
            }
        }

        Map<Map, EntityValueBase> createMap = getCreateByEntityMap(entityName);
        if (createMap != null && createMap.size() > 0 && econd != null) for (EntityValueBase evb : createMap.values()) {
            if (econd.mapMatches(evb) && (foundUpdated.size() == 0 || !foundUpdated.contains(evb.getPrimaryKeys())))
                valueList.add(evb);
        }
        // if (entityName.contains("OrderPart")) logger.warn("OP tx cache list: ${valueList}")
        return new FindAugmentInfo(valueList, foundUpdated, econd);
    }

    public void flushCache(boolean clearRead) throws XAException  {
        Map<String, Connection> connectionByGroup = new HashMap<>();
        try {
            int writeInfoListSize = writeInfoList.size();
            if (writeInfoListSize > 0) {
                // logger.error("Tx cache flush at", new BaseException("txc flush"))
                EntityFacadeImpl efi = (EntityFacadeImpl) ecfi.getEntity();

                long startTime = System.currentTimeMillis();
                int createCount = 0;
                int updateCount = 0;
                int deleteCount = 0;
                // for (EntityWriteInfo ewi in writeInfoList) logger.warn("===== TX Cache value to ${ewi.writeMode} ${ewi.evb.getEntityName()}: \n${ewi.evb}")
                if (readOnly) logger.warn("事务缓存警告: 只读书屋缓存中有 ["+writeInfoListSize+"] 条记录需要写入!");
                for (EntityWriteInfo entityWriteInfo : writeInfoList) {
                    String groupName = (entityWriteInfo).evb.getEntityDefinition().getEntityGroupName();
                    Connection con = connectionByGroup.get(groupName);
                    if (con == null) {
                        con = efi.getConnection(groupName);
                        connectionByGroup.put(groupName, con);
                    }

                    if ((entityWriteInfo).writeMode.equals(WriteMode.CREATE)) {
                        (entityWriteInfo).evb.basicCreate(con);
                        createCount++;
                    } else if ((entityWriteInfo).writeMode.equals(WriteMode.DELETE)) {
                        (entityWriteInfo).evb.deleteExtended(con);
                        deleteCount++;
                    } else {
                        (entityWriteInfo).evb.basicUpdate(con);
                        updateCount++;
                    }
                }
                if (logger.isDebugEnabled()) logger.debug("事务缓存调试: 已刷新事务缓存, 用时 ["+(System.currentTimeMillis() - startTime)+"] 毫秒,已创建 ["+createCount+"] ,已更新 ["+updateCount+"],已删除["+deleteCount+"],可读 ["+readOneCache.size()+"] ,实体数量 ["+readListCache.size()+"]");
            }

            writeInfoList.clear();
            firstWriteInfoMap.clear();
            lastWriteInfoMap.clear();
            createByEntityRef.clear();
            if (clearRead) {
                readOneCache.clear();
                readListCache.clear();
                // set to readOnly to avoid any other write through
                readOnly = true;
            }
        } catch (Throwable t) {
            logger.error("事务缓存错误: 无法从事务缓存中写值: "+t.toString(), t);
            throw new XAException("事务缓存错误: 无法从事务缓存中写值:"+t.toString());
        } finally {
            // now close connections
            for (Connection con : connectionByGroup.values()){
                try {
                    con.close();
                } catch (SQLException e) {
                    throw new XAException("事务缓存错误: 无法关闭事务缓存连接:"+e.toString());
                }
            }
        }
    }

    @Override
    public void beforeCompletion() {
        try {
            this.flushCache(true);
        } catch (XAException e) {
            e.printStackTrace();
        }
    }
    @Override
    public void afterCompletion(int i) { }
}
