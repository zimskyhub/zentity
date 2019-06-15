package com.zmtech.zentity.transaction.impl

import com.zmtech.zentity.entity.EntityCondition;
import com.zmtech.zentity.entity.EntityValue;
import com.zmtech.zentity.entity.impl.*;
import com.zmtech.zentity.entity.impl.EntityJavaUtil.*;
import com.zmtech.zentity.exception.EntityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Synchronization;
import javax.transaction.xa.XAException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** This is a per-transaction cache that basically pretends to be the database for the scope of the transaction.
 * Test your code well when using this as it doesn't support everything.
 *
 * See notes on limitations in the JavaDoc for ServiceCallSync.useTransactionCache()
 *
 */
public class TransactionCache implements Synchronization {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionCache.class);

    protected EntityContextFactoryImpl ecfi;
    private boolean readOnly;

    private Map<Map, EntityValueBase> readOneCache = new HashMap<>();
    private Set<Map> knownLocked = new HashSet<>();
    private Map<String, Map<EntityCondition, EntityListImpl>> readListCache = new ConcurrentHashMap<>();

    private Map<Map, EntityWriteInfo> firstWriteInfoMap = new HashMap<Map, EntityWriteInfo>();
    private Map<Map, EntityWriteInfo> lastWriteInfoMap = new HashMap<Map, EntityWriteInfo>();
    private ArrayList<EntityWriteInfo> writeInfoList = new ArrayList<EntityWriteInfo>(50);
    private LinkedHashMap<String, LinkedHashMap<Map, EntityValueBase>> createByEntityRef = new LinkedHashMap<>();

    public TransactionCache(EntityContextFactoryImpl ecfi, boolean readOnly) {
        this.ecfi = ecfi;
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() { return readOnly; }
    public void makeReadOnly() {
        if (readOnly) return;
        flushCache(false);
        readOnly = true;
    }
    public void makeWriteThrough() { readOnly = false;}

    public LinkedHashMap<Map, EntityValueBase> getCreateByEntityMap(String entityName) {
        LinkedHashMap<Map, EntityValueBase> createMap = createByEntityRef.get(entityName);
        if (createMap == null) {
            createMap = new LinkedHashMap<>();
            createByEntityRef.put(entityName, createMap);
        }
        return createMap;
    }

    public static Map<String, Object> makeKey(EntityValueBase evb) {
        if (evb == null) return null;
        Map<String, Object> key = evb.getPrimaryKeys();
        if (key == null) return null;
        key.put("_entityName", evb.getEntityName());
        return key;
    }
    public static Map makeKeyFind(EntityFindBase efb) {
        // NOTE: this should never come in null (EntityFindBase.one() => oneGet() => this is only call path)
        if (efb == null) return null;
        Map key = efb.getSimpleMapPrimaryKeys();
        if (key == null) return null;
        key.put("_entityName", efb.getEntityDef().getFullEntityName());
        return key;
    }
    public void addWriteInfo(Map<String, Object> key, EntityWriteInfo newEwi) {
        writeInfoList.add(newEwi);
        if (!firstWriteInfoMap.containsKey(key)) firstWriteInfoMap.put(key, newEwi);
        lastWriteInfoMap.put(key, newEwi);
    }

    /** Returns true if create handled, false if not; if false caller should handle the operation */
    public boolean create(EntityValueBase evb) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;

        if (!readOnly) {
            // if create info already exists blow up
            EntityWriteInfo currentEwi = lastWriteInfoMap.get(key);
            if (readOneCache.get(key) != null)
                throw new EntityException("Tried to create a value that already exists in database, entity ${evb.getEntityName()}, PK ${evb.getPrimaryKeys()}");
            if (currentEwi != null && currentEwi.writeMode != WriteMode.DELETE)
                throw new EntityException("Tried to create a value that already exists in write cache, entity ${evb.getEntityName()}, PK ${evb.getPrimaryKeys()}");

            EntityWriteInfo newEwi = new EntityWriteInfo(evb, WriteMode.CREATE);
            addWriteInfo(key, newEwi);
            if (currentEwi == null || currentEwi.writeMode != WriteMode.DELETE) {
                getCreateByEntityMap(evb.getEntityName()).put(evb.getPrimaryKeys(), evb);
            }
        }

        // add to readCache after so we don't think it already exists
        readOneCache.put(key, evb);
        // add to any matching list cache entries
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(evb.getEntityName());
        if (entityListCache != null) {
            for (Map.Entry<EntityCondition, EntityListImpl> entry : entityListCache.entrySet()) {
                if (entry.getKey().mapMatches(evb)) entry.getValue().add(evb);
            }
        }

        // consider created records locked to avoid forUpdate queries
        knownLocked.add(key);

        return !readOnly;
    }
    public boolean update(EntityValueBase evb) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return false;

        if (!readOnly) {
            // with writeInfoList as plain list approach no need to look for existing create or update, just add to the list
            if (!evb.getIsFromDb()) {
                EntityValueBase cacheEvb = readOneCache.get(key);
                if (cacheEvb != null) {
                    cacheEvb.setFields(evb, true, null, false);
                    evb = cacheEvb;
                } else {
                    EntityValueBase dbEvb = (EntityValueBase) evb.cloneValue();
                    dbEvb.refresh();
                    dbEvb.setFields(evb, true, null, false);
                    logger.warn("====== tx cache update not from db\nevb: "+evb+"\ndbEvb: "+dbEvb);
                    evb = dbEvb;
                }
            }

            EntityWriteInfo newEwi = new EntityWriteInfo(evb, WriteMode.UPDATE);
            addWriteInfo(key, newEwi);
        }

        // add to readCache
        if (evb.getIsFromDb()) {
            readOneCache.put(key, evb);
        } else {
            // not from DB, may have partial values so find existing and put all from valueMap
            EntityValueBase existingEv = readOneCache.get(key);
            if (existingEv != null) {
                existingEv.putAll(evb);
            } else {
                // NOTE: should put a not from DB value if not read only? if read only definitely no
                if (!readOnly) readOneCache.put(key, evb);
            }
        }

        // NOTE: issue here if the evb is partial, not full from DB/cache, and doesn't have field value that would match; solve higher up by getting full value?
        // update any matching list cache entries, add to list cache if not there (though generally should be, depending on the condition)
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(evb.getEntityName());
        if (entityListCache != null) {
            for (Map.Entry<EntityCondition, EntityListImpl> entry : entityListCache.entrySet()) {
                if (entry.getKey().mapMatches(evb)) {
                    // find an existing entry and update it
                    boolean foundEntry = false;
                    EntityListImpl eli = entry.getValue();
                    int eliSize = eli.size();
                    for (int i = 0; i < eliSize; i++) {
                        EntityValueBase existingEv = (EntityValueBase) eli.get(i);
                        if (evb.primaryKeyMatches(existingEv)) {
                            existingEv.putAll(evb);
                            foundEntry = true;
                        }
                    }
                    // if no existing entry found add this
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
                // if was created in TX cache but never written to DB just clear all changes
                firstWriteInfoMap.remove(key);
                lastWriteInfoMap.remove(key);
                for (int i = 0; i < writeInfoList.size(); ) {
                    EntityWriteInfo ewi = (EntityWriteInfo) writeInfoList.get(i);
                    if (key.equals(makeKey(ewi.evb))) { writeInfoList.remove(i); }
                    else { i++; }
                }
                getCreateByEntityMap(evb.getEntityName()).remove(evb.getPrimaryKeys());
            } else {
                EntityWriteInfo newEwi = new EntityWriteInfo(evb, WriteMode.DELETE);
                addWriteInfo(key, newEwi);
            }
        }

        // remove from readCache if needed
        readOneCache.remove(key);
        // remove any matching list cache entries
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
            int nonPkSize = nonPkFieldList.size();
            for (int j = 0; j < nonPkSize; j++) {
                String fieldName = nonPkFieldList.get(j);
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
        // NOTE: do nothing here on forUpdate, handled by caller
        Map<String, Object> key = makeKeyFind(efb);
        if (key == null) return null;

        if (!readOnly) {
            // if this has been deleted return a DeletedEntityValue instance so caller knows it was deleted and doesn't look in the DB for another record
            EntityWriteInfo currentEwi = (EntityWriteInfo) lastWriteInfoMap.get(key);
            if (currentEwi != null && currentEwi.writeMode == WriteMode.DELETE)
                return new EntityValueBase.DeletedEntityValue(efb.getEntityDef(), ecfi.entityFacade);
        }

        // cloneValue() so that updates aren't in the read cache until an update is done
        EntityValueBase evb = readOneCache.get(key) != null ? (EntityValueBase) readOneCache.get(key).cloneValue():null;
        return evb;
    }
    public void onePut(EntityValueBase evb, boolean forUpdate) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return;
        EntityWriteInfo currentEwi = lastWriteInfoMap.get(key);
        // if this has been deleted we don't want to add it, but in general if we have a ewi then it's already in the
        //     cache and we don't want to update from this (generally from DB and may be older than value already there)
        // clone the value before putting it into the cache so that the caller can't change it later with an update call
        if (currentEwi == null || currentEwi.writeMode != WriteMode.DELETE) readOneCache.put(key, (EntityValueBase) evb.cloneValue());

        // if (evb.getEntityDefinition().getEntityName() == "Asset") logger.warn("=========== onePut of Asset ${evb.get('assetId')}", new Exception("Location"))

        if (forUpdate) knownLocked.add(key);
    }

    public EntityListImpl listGet(EntityDefinition ed, EntityCondition whereCondition, List<String> orderByExpanded) {
        Map<EntityCondition, EntityListImpl> entityListCache = readListCache.get(ed.getFullEntityName());
        // always clone this so that filters/sorts/etc by callers won't change this
        EntityListImpl cacheList = entityListCache != null ? entityListCache.get(whereCondition) !=null?entityListCache.get(whereCondition).deepCloneList():null : null;

        // if we are searching by a field that is a PK on a related entity to the one being searched it can only exist
        //     in the read cache so find here and don't bother with a DB query
        if (cacheList == null) {
            // if the condition depends on a record that was created in this tx cache, then build the list from here
            //     instead of letting it drop to the DB, finding nothing, then being expanded from the txCache
            Map<String, Object> condMap = new ConcurrentHashMap<>();
            if (whereCondition != null && whereCondition.populateMap(condMap)) {
                boolean foundCreatedDependent = false;

                for (RelationshipInfo relInfo : ed.getRelationshipsInfo(false)) {
                    if (relInfo.type != "one") continue;
                            // would be nice to skip this, but related-entity-name may not be full entity name
                    EntityDefinition relEd = relInfo.relatedEd;
                    String relEntityName = relEd.getFullEntityName();
                    // first see if there is a create Map for this, then do the more expensive operation of getting the
                    //     expanded key Map and the related entity's PK Map
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
                    EntityListImpl createdValueList = new EntityListImpl(ecfi.entityFacade);
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
        // don't need to do much else here; list will already have values created/updated/deleted in this TX Cache
        entityListCache.put(whereCondition, (EntityListImpl) eli.cloneList());
    }

    // NOTE: no need to filter EntityList or EntityListIterator, they do it internally by calling this method
    public WriteMode checkUpdateValue(EntityValueBase evb, FindAugmentInfo fai) {
        Map<String, Object> key = makeKey(evb);
        if (key == null) return null;
        EntityWriteInfo firstEwi = firstWriteInfoMap.get(key);
        EntityWriteInfo currentEwi = lastWriteInfoMap.get(key);
        if (currentEwi == null) {
            // add to readCache for future reference
            onePut(evb, false);
            return null;
        }
        if (WriteMode.CREATE.equals(firstEwi.writeMode)) {
            throw new EntityException("Found value from database that matches a value created in the write-through transaction cache, throwing error now instead of waiting to fail on commit");
        }
        if (WriteMode.UPDATE.equals(currentEwi.writeMode)) {
            if (fai != null && ((fai.econd != null && !fai.econd.mapMatches(currentEwi.evb)) || fai.foundUpdated.contains(currentEwi.evb.getPrimaryKeys()))) {
                // current value no longer matches, tell ELII to skip it (same as DELETE)
                return WriteMode.DELETE;
            }
            evb.setFields(currentEwi.evb, true, null, false);
            // add to readCache
            onePut(evb, false);
        }
        return currentEwi.writeMode;
    }
    public FindAugmentInfo getFindAugmentInfo(String entityName, EntityCondition econd) {
        ArrayList<EntityValueBase> valueList = new ArrayList<>();

        // also get values that have been updated so that they should now be included in the list
        Set<Map<String, Object>> foundUpdated = new HashSet<>();
        if (econd != null) {
            int writeInfoListSize = writeInfoList.size();
            // go through backwards to get the most recent only
            for (int i = (writeInfoListSize - 1); i >= 0 ; i--) {
                EntityWriteInfo ewi = (EntityWriteInfo) writeInfoList.get(i);
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
        if (createMap.size() > 0) for (EntityValueBase evb : createMap.values()) {
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
                EntityFacadeImpl efi = ecfi.entityFacade;

                long startTime = System.currentTimeMillis();
                int createCount = 0;
                int updateCount = 0;
                int deleteCount = 0;
                // for (EntityWriteInfo ewi in writeInfoList) logger.warn("===== TX Cache value to ${ewi.writeMode} ${ewi.evb.getEntityName()}: \n${ewi.evb}")
                if (readOnly && writeInfoListSize > 0) logger.warn("Read only TX cache has "+writeInfoListSize+" values to write");
                for (int i = 0; i < writeInfoListSize; i++) {
                    EntityWriteInfo ewi = (EntityWriteInfo) writeInfoList.get(i);
                    String groupName = ewi.evb.getEntityDefinition().getEntityGroupName();
                    Connection con = connectionByGroup.get(groupName);
                    if (con == null) {
                        con = efi.getConnection(groupName);
                        connectionByGroup.put(groupName, con);
                    }

                    if (ewi.writeMode.equals(WriteMode.CREATE)) {
                        ewi.evb.basicCreate(con);
                        createCount++;
                    } else if (ewi.writeMode.equals(WriteMode.DELETE)) {
                        ewi.evb.deleteExtended(con);
                        deleteCount++;
                    } else {
                        ewi.evb.basicUpdate(con);
                        updateCount++;
                    }
                }
                if (logger.isDebugEnabled()) logger.debug("Flushed TransactionCache in "+(System.currentTimeMillis() - startTime)+"ms: "+createCount+" creates, "+updateCount+" updates, "+deleteCount+" deletes, "+readOneCache.size()+" read entries, "+readListCache.size()+" entities with list cache");
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
            logger.error("Error writing values from TransactionCache: "+t.toString(), t);
            throw new XAException("Error writing values from TransactionCache:"+t.toString());
        } finally {
            // now close connections
            for (Connection con : connectionByGroup.values()){
                try {
                    con.close();
                } catch (SQLException e) {
                    throw new XAException("Error close Connection from TransactionCache:");
                }
            }
        }
    }

    @Override
    public void beforeCompletion() { this.flushCache(true); }
    @Override
    public void afterCompletion(int i) { }
}
