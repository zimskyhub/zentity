
package com.zmtech.zkit.entity.impl;

import com.zmtech.zkit.entity.EntityCondition;
import com.zmtech.zkit.entity.EntityList;
import com.zmtech.zkit.entity.EntityListIterator;
import com.zmtech.zkit.entity.EntityValue;
import com.zmtech.zkit.exception.EntityException;
import com.zmtech.zkit.transaction.impl.TransactionCache;
import com.zmtech.zkit.util.CollectionUtil;
import com.zmtech.zkit.util.EntityJavaUtil.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

class EntityListIteratorWrapper implements EntityListIterator {
    protected static final Logger logger = LoggerFactory.getLogger(EntityListIteratorWrapper.class);
    protected EntityFacadeImpl efi;
    private List<EntityValue> valueList;
    private int internalIndex = -1;
    private EntityDefinition entityDefinition;
    /** This is needed to determine if the ResultSet is empty as cheaply as possible. */
    private boolean haveMadeValue = false;
    protected boolean closed = false;

    EntityListIteratorWrapper(List<EntityValue> valList, EntityDefinition entityDefinition, EntityFacadeImpl efi,
                              EntityCondition queryCondition, ArrayList<String> obf) {
        valueList = new ArrayList<>(valList);
        this.efi = efi;
        this.entityDefinition = entityDefinition;
        TransactionCache txCache = efi.ecfi.getTransaction().getTransactionCache();
        if (txCache != null && queryCondition != null) {
            // add all created values (updated and deleted values will be handled by the next() method
            FindAugmentInfo tempFai = txCache.getFindAugmentInfo(entityDefinition.getFullEntityName(), queryCondition);
            if (tempFai.valueListSize > 0) {
                // remove update values already in list
                if (tempFai.foundUpdated.size() > 0) {
                    for (int i = 0; i < valueList.size(); ) {
                        EntityValue ev = valueList.get(i);
                        if (tempFai.foundUpdated.contains(ev.getPrimaryKeys())) {
                            valueList.remove(i);
                        } else {
                            i++;
                        }
                    }
                }
                valueList.addAll(tempFai.valueList);
                // update the order if we know the order by field list
                if (obf != null && obf.size() > 0) valueList.sort(new CollectionUtil.MapOrderByComparator(obf));
            }
        }
    }

    @Override public void close() {
        if (this.closed) {
            logger.warn("EntityListIterator for entity " + entityDefinition.fullEntityName + " is already closed, not closing again");
        } else {
            this.closed = true;
        }
    }

    @Override public void afterLast() { this.internalIndex = valueList.size(); }
    @Override public void beforeFirst() { internalIndex = -1; }
    @Override public boolean last() { internalIndex = (valueList.size() - 1); return true; }
    @Override public boolean first() { internalIndex = 0; return true; }

    @Override public EntityValue currentEntityValue() {
        this.haveMadeValue = true;
        return valueList.get(internalIndex);
    }
    @Override public int currentIndex() { return internalIndex; }

    @Override public boolean absolute(int rowNum) {
        internalIndex = rowNum;
        return !(internalIndex < 0 || internalIndex >= valueList.size());
    }
    @Override public boolean relative(int rows) {
        internalIndex += rows;
        return !(internalIndex < 0 || internalIndex >= valueList.size());
    }

    @Override public boolean hasNext() { return internalIndex < (valueList.size() - 1); }
    @Override public boolean hasPrevious() { return internalIndex > 0; }

    @Override public EntityValue next() {
        if (internalIndex >= valueList.size()) return null;
        internalIndex++;
        if (internalIndex >= valueList.size()) return null;
        return currentEntityValue();
    }
    @Override public int nextIndex() { return internalIndex + 1; }

    @Override public EntityValue previous() {
        if (internalIndex < 0) return null;
        internalIndex--;
        if (internalIndex < 0) return null;
        return currentEntityValue();
    }
    @Override public int previousIndex() { return internalIndex - 1; }

    @Override public void setFetchSize(int rows) {/* do nothing, just ignore */}

    @Override public EntityList getCompleteList(boolean closeAfter) {
        try {
            EntityList list = new EntityListImpl(efi);
            EntityValue value;
            while ((value = this.next()) != null) list.add(value);
            return list;
        } finally {
            if (closeAfter) close();
        }
    }

    @Override public EntityList getPartialList(int offset, int limit, boolean closeAfter) {
        try {
            EntityList list = new EntityListImpl(this.efi);
            if (limit == 0) return list;

            // jump to start index, or just get the first result
            if (!this.absolute(offset)) {
                // not that many results, get empty list
                return list;
            }

            // get the first as the current one
            list.add(this.currentEntityValue());

            int numberSoFar = 1;
            EntityValue nextValue;
            while (limit > numberSoFar && (nextValue = this.next()) != null) {
                list.add(nextValue);
                numberSoFar++;
            }

            return list;
        } finally {
            if (closeAfter) close();
        }
    }

    @Override public int writeXmlText(Writer writer, String prefix, int dependentLevels) {
        int recordsWritten = 0;
        if (haveMadeValue && internalIndex != -1) internalIndex = -1;
        EntityValue value;
        while ((value = this.next()) != null) recordsWritten += value.writeXmlText(writer, prefix, dependentLevels);
        return recordsWritten;
    }

    @Override public int writeXmlTextMaster(Writer writer, String prefix, String masterName) {
        int recordsWritten = 0;
        if (haveMadeValue && internalIndex != -1) internalIndex = -1;
        EntityValue value;
        while ((value = this.next()) != null) recordsWritten += value.writeXmlTextMaster(writer, prefix, masterName);
        return recordsWritten;
    }

    @Override public void remove() {
        throw new EntityException("EntityListIteratorWrapper.remove() not currently supported");
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override public void set(EntityValue e) {
        throw new EntityException("EntityListIteratorWrapper.set() not currently supported");
        // TODO implement this
        // TODO: call EECAs
        // TODO: notify cache clear
    }

    @Override public void add(EntityValue e) {
        throw new EntityException("EntityListIteratorWrapper.add() not currently supported");
        // TODO implement this
    }

    @Override protected void finalize() throws Throwable {
        if (!closed) {
            this.close();
            logger.error("EntityListIteratorWrapper not closed for entity " + entityDefinition.fullEntityName + ", caught in finalize()");
        }
        super.finalize();
    }
}
