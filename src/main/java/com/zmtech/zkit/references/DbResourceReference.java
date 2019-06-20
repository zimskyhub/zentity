
package com.zmtech.zkit.references;

import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.context.impl.ExecutionContextImpl;
import com.zmtech.zkit.entity.EntityList;
import com.zmtech.zkit.entity.EntityValue;
import com.zmtech.zkit.util.ObjectUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.rowset.serial.SerialBlob;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;

public class DbResourceReference extends BaseResourceReference {
    protected final static Logger logger = LoggerFactory.getLogger(DbResourceReference.class);
    public final static String locationPrefix = "dbresource://";

    String location;
    String resourceId = null;

    DbResourceReference() { }

    @Override
    public ResourceReference init(String location, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf
        this.location = location
        return this
    }

    public ResourceReference init(String location, EntityValue dbResource, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf
        this.location = location
        resourceId = dbResource.resourceId
        return this
    }

    @Override public ResourceReference createNew(String location) {
        DbResourceReference resRef = new DbResourceReference()
        resRef.init(location, ecf)
        return resRef
    }
    @Override public String getLocation() { location }

    public String getPath() {
        if (!location) return ""
        // should have a prefix of "dbresource://"
        return location.substring(locationPrefix.length())
    }

    @Override
    public InputStream openStream() {
        EntityValue dbrf = getDbResourceFile()
        if (dbrf == null) return null
        return dbrf.getSerialBlob("fileData")?.getBinaryStream()
    }

    @Override
    public OutputStream getOutputStream() {
        throw new UnsupportedOperationException("The getOutputStream method is not supported for DB resources, use putStream() instead")
    }

    @Override public public String getText() { return ObjectUtil.getStreamText(openStream()) }

    @Override public boolean supportsAll() { true }

    @Override public boolean supportsUrl() { false }
    @Override
    public URL getUrl() { return null }

    @Override boolean public supportsDirectory() { true }
    @Override boolean public isFile() { return "Y".equals(getDbResource(true)?.isFile) }
    @Override boolean public isDirectory() {
        if (!getPath()) return true // consider root a directory
        EntityValue dbr = getDbResource(true)
        return dbr != null && !"Y".equals(dbr.isFile)
    }
    @Override
    public List<ResourceReference> getDirectoryEntries() {
        List<ResourceReference> dirEntries = new LinkedList()
        EntityValue dbr = getDbResource(true)
        if (getPath() && dbr == null) return dirEntries

        // allow parentResourceId to be null for the root
        EntityList childList = ecf.entity.find("moqui.resource.DbResource").condition([parentResourceId:dbr?.resourceId])
                .useCache(true).list()
        for (EntityValue child in childList) {
            String childLoc = getPath() ? "${location}/${child.filename}" : "${location}${child.filename}"
            dirEntries.add(new DbResourceReference().init(childLoc, child, ecf))
        }
        return dirEntries
    }

    @Override public boolean supportsExists() { true }
    @Override public boolean getExists() { return getDbResource(true) != null }

    @Override public boolean supportsLastModified() { true }
    @Override public long getLastModified() {
        EntityValue dbr = getDbResource(true)
        if (dbr == null) return 0
        if ("Y".equals(dbr.isFile)) {
            EntityValue dbrf = ecf.entity.find("moqui.resource.DbResourceFile").condition("resourceId", resourceId)
                    .selectField("lastUpdatedStamp").useCache(false).one()
            if (dbrf != null) return dbrf.getTimestamp("lastUpdatedStamp").getTime()
        }
        return dbr.getTimestamp("lastUpdatedStamp").getTime()
    }

    @Override public boolean supportsSize() { true }
    @Override public long getSize() {
        EntityValue dbrf = getDbResourceFile()
        if (dbrf == null) return 0
        return dbrf.getSerialBlob("fileData")?.length() ?: 0
    }

    @Override public boolean supportsWrite() { true }
    @Override public void putText(String text) {
        // TODO: use diff from last version for text
        SerialBlob sblob = text ? new SerialBlob(text.getBytes(StandardCharsets.UTF_8)) : null
        this.putObject(sblob)
    }
    @Override public void putStream(InputStream stream) {
        if (stream == null) return
                ByteArrayOutputStream baos = new ByteArrayOutputStream()
        ObjectUtil.copyStream(stream, baos)
        SerialBlob sblob = new SerialBlob(baos.toByteArray())
        this.putObject(sblob)
    }

    protected void putObject(Object fileObj) {
        EntityValue dbrf = getDbResourceFile()
        if (dbrf != null) {
            makeNextVersion(dbrf, fileObj)
        } else {
            // first make sure the directory exists that this is in
            List<String> filenameList = new ArrayList<>(Arrays.asList(getPath().split("/")))
            int filenameListSize = filenameList.size()
            if (filenameListSize == 0) throw new BaseArtifactException("Cannot put file at empty location ${getPath()}")
            String filename = filenameList.get(filenameList.size()-1)
            // remove the current filename from the list, and find ID of parent directory for path
            filenameList.remove(filenameList.size()-1)
            String parentResourceId = findDirectoryId(filenameList, true)

            if (parentResourceId == null) throw new BaseArtifactException("Could not find directory to put new file in at ${filenameList}")

            // lock the parentResourceId
            ecf.entity.find("moqui.resource.DbResource").condition("resourceId", parentResourceId)
                    .selectField("lastUpdatedStamp").forUpdate(true).one()
            // do a query by name to see if it exists
            EntityValue existingValue = ecf.entity.find("moqui.resource.DbResource")
                    .condition("parentResourceId", parentResourceId).condition("filename", filename)
                    .useCache(false).list().getFirst()
            if (existingValue != null) {
                resourceId = existingValue.resourceId
                dbrf = getDbResourceFile()
                makeNextVersion(dbrf, fileObj)
            } else {
                // now write the DbResource and DbResourceFile records
                Map createDbrResult = ecf.service.sync().name("create", "moqui.resource.DbResource")
                        .parameters([parentResourceId:parentResourceId, filename:filename, isFile:"Y"]).call()
                resourceId = createDbrResult.resourceId
                String versionName = "01"
                ecf.service.sync().name("create", "moqui.resource.DbResourceFile")
                        .parameters([resourceId:resourceId, mimeType:getContentType(), versionName:versionName,
                        rootVersionName:versionName, fileData:fileObj]).call()
                ExecutionContextImpl eci = ecf.getEci()
                ecf.service.sync().name("create", "moqui.resource.DbResourceFileHistory")
                        .parameters([resourceId:resourceId, versionDate:eci.userFacade.nowTimestamp, userId:eci.userFacade.userId,
                        isDiff:"N"]).call() // NOTE: no fileData, for non-diff only past versions
            }
        }
    }
    protected void makeNextVersion(EntityValue dbrf, Object newFileObj) {
        String currentVersionName = dbrf.versionName
        if (currentVersionName != null && !currentVersionName.isEmpty()) {
            EntityValue currentDbrfHistory = ecf.entityFacade.find("moqui.resource.DbResourceFileHistory").condition("resourceId", resourceId)
                    .condition("versionName", currentVersionName).useCache(false).one()
            currentDbrfHistory.set("fileData", dbrf.fileData)
            currentDbrfHistory.update()
        }
        ExecutionContextImpl eci = ecf.getEci()
        Map createOut = ecf.service.sync().name("create", "moqui.resource.DbResourceFileHistory")
                .parameters([resourceId:resourceId, previousVersionName:currentVersionName,
                versionDate:eci.userFacade.nowTimestamp, userId:eci.userFacade.userId,
                isDiff:"N"]).call()  // NOTE: no fileData, for non-diff only past versions
        String newVersionName = createOut.versionName
        if (!dbrf.rootVersionName) dbrf.rootVersionName = currentVersionName ?: newVersionName
        dbrf.versionName = newVersionName
        dbrf.fileData = newFileObj
        dbrf.update()
    }
    private String findDirectoryId(List<String> pathList, boolean create) {
        String finalParentResourceId = null
        if (pathList) {
            String parentResourceId = null
            boolean found = true
            for (String filename in pathList) {
                if (filename == null || filename.length() == 0) continue

                EntityValue directoryValue = ecf.entity.find("moqui.resource.DbResource")
                        .condition("parentResourceId", parentResourceId).condition("filename", filename)
                        .useCache(true).list().getFirst()
                if (directoryValue == null) {
                    if (create) {
                        // trying a create so lock the parent, then query again to make sure it doesn't exist
                        ecf.entity.find("moqui.resource.DbResource").condition("resourceId", parentResourceId)
                                .selectField("lastUpdatedStamp").forUpdate(true).one()
                        directoryValue = ecf.entity.find("moqui.resource.DbResource")
                                .condition("parentResourceId", parentResourceId).condition("filename", filename)
                                .useCache(false).list().getFirst()
                        if (directoryValue == null) {
                            Map createResult = ecf.service.sync().name("create", "moqui.resource.DbResource")
                                    .parameters([parentResourceId:parentResourceId, filename:filename, isFile:"N"]).call()
                            parentResourceId = createResult.resourceId
                            // logger.warn("=============== put text to ${location}, created dir ${filename}")
                        }
                        // else fall through, handle below
                    } else {
                        found = false
                        break
                    }
                }
                if (directoryValue != null) {
                    if (directoryValue.isFile == "Y") {
                        throw new BaseArtifactException("Tried to find a directory in a path but found file instead at ${filename} under DbResource ${parentResourceId}")
                    } else {
                        parentResourceId = directoryValue.resourceId
                        // logger.warn("=============== put text to ${location}, found existing dir ${filename}")
                    }
                }
            }
            if (found) finalParentResourceId = parentResourceId
        }
        return finalParentResourceId
    }

    @Override public void move(String newLocation) {
        EntityValue dbr = getDbResource(false)
        // if the current resource doesn't exist, nothing to move
        if (!dbr) {
            logger.warn("Could not find dbresource at [${getPath()}]")
            return
        }
        if (!newLocation) throw new BaseArtifactException("No location specified, not moving resource at ${getLocation()}")
        // ResourceReference newRr = ecf.resource.getLocationReference(newLocation)
        if (!newLocation.startsWith(locationPrefix))
            throw new BaseArtifactException("Location [${newLocation}] is not a dbresource location, not moving resource at ${getLocation()}")

        List<String> filenameList = new ArrayList<>(Arrays.asList(newLocation.substring(locationPrefix.length()).split("/")))
        if (filenameList) {
            String newFilename = filenameList.get(filenameList.size()-1)
            filenameList.remove(filenameList.size()-1)
            String parentResourceId = findDirectoryId(filenameList, true)

            dbr.parentResourceId = parentResourceId
            dbr.filename = newFilename
            dbr.update()
        }
    }

    @Override public ResourceReference makeDirectory(String name) {
        findDirectoryId([name], true)
        return new DbResourceReference().init("${location}/${name}", ecf)
    }
    @Override public ResourceReference makeFile(String name) {
        DbResourceReference newRef = (DbResourceReference) new DbResourceReference().init("${location}/${name}", ecf)
        newRef.putObject(null)
        return newRef
    }
    @Override public boolean delete() {
        EntityValue dbr = getDbResource(false)
        if (dbr == null) return false
        if (dbr.isFile == "Y") {
            EntityValue dbrf = getDbResourceFile()
            if (dbrf != null) {
                // first delete history records
                dbrf.deleteRelated("histories")
                // then delete the file
                dbrf.delete()
            }
        }
        dbr.delete()
        resourceId = null
        return true
    }

    @Override public boolean supportsVersion() { return true }
    @Override public Version getVersion(String versionName) {
        String resourceId = getDbResourceId()
        if (resourceId == null) return null
        return makeVersion(ecf.entityFacade.find("moqui.resource.DbResourceFileHistory").condition("resourceId", resourceId)
                .condition("versionName", versionName).useCache(false).one())
    }
    @Override public Version getCurrentVersion() {
        EntityValue dbrf = getDbResourceFile()
        if (dbrf == null) return null
        return getVersion((String) dbrf.versionName)
    }
    @Override public Version getRootVersion() {
        EntityValue dbrf = getDbResourceFile()
        if (dbrf == null) return null
        return getVersion((String) dbrf.rootVersionName)
    }
    @Override public ArrayList<Version> getVersionHistory() {
        String resourceId = getDbResourceId()
        if (resourceId == null) return new ArrayList<>()
        EntityList dbrfHistoryList = ecf.entityFacade.find("moqui.resource.DbResourceFileHistory")
                .condition("resourceId", resourceId).orderBy("-versionDate").useCache(false).list()
        int dbrfHistorySize = dbrfHistoryList.size()
        ArrayList<Version> verList = new ArrayList<>(dbrfHistorySize)
        for (int i = 0; i < dbrfHistorySize; i++) {
            EntityValue dbrfHistory = dbrfHistoryList.get(i)
            verList.add(makeVersion(dbrfHistory))
        }
        return verList
    }
    @Override public ArrayList<Version> getNextVersions(String versionName) {
        String resourceId = getDbResourceId()
        if (resourceId == null) return new ArrayList<>()
        EntityList dbrfHistoryList = ecf.entityFacade.find("moqui.resource.DbResourceFileHistory")
                .condition("resourceId", resourceId).condition("previousVersionName", versionName).useCache(false).list()
        int dbrfHistorySize = dbrfHistoryList.size()
        ArrayList<Version> verList = new ArrayList<>(dbrfHistorySize)
        for (int i = 0; i < dbrfHistorySize; i++) {
            EntityValue dbrfHistory = dbrfHistoryList.get(i)
            verList.add(makeVersion(dbrfHistory))
        }
        return verList
    }
    @Override public InputStream openStream(String versionName) {
        if (versionName == null || versionName.isEmpty()) return openStream()
        EntityValue dbrfHistory = getDbResourceFileHistory(versionName)
        if (dbrfHistory == null) return null
        if ("Y".equals(dbrfHistory.isDiff)) {
            // TODO if current version get full text from dbrf otherwise reconstruct from root merging in diffs as needed up to versionName
            return null
        } else {
            SerialBlob fileData = dbrfHistory.getSerialBlob("fileData")
            if (fileData != null) {
                return fileData.getBinaryStream()
            } else {
                // may be the current version with no fileData value in dbrfHistory
                EntityValue dbrf = getDbResourceFile()
                if (dbrf == null || !versionName.equals(dbrf.versionName)) return null
                fileData = dbrf.getSerialBlob("fileData")
                if (fileData == null) return null
                return fileData.getBinaryStream()
            }
        }
    }
    @Override public String getText(String versionName) { return ObjectUtil.getStreamText(openStream(versionName)) }

    private Version makeVersion(EntityValue dbrfHistory) {
        if (dbrfHistory == null) return null
        return new Version(this, (String) dbrfHistory.versionName, (String) dbrfHistory.previousVersionName,
                (String) dbrfHistory.userId, (Timestamp) dbrfHistory.versionDate)
    }
    private String getDbResourceId() {
        if (resourceId != null) return resourceId

        List<String> filenameList = new ArrayList<>(Arrays.asList(getPath().split("/")))
        String lastResourceId = null
        for (String filename in filenameList) {
            EntityValue curDbr = ecf.entityFacade.find("moqui.resource.DbResource").condition("parentResourceId", lastResourceId)
                    .condition("filename", filename).useCache(true).one()
            if (curDbr == null) return null
            lastResourceId = curDbr.resourceId
        }

        resourceId = lastResourceId
        return resourceId
    }

    private EntityValue getDbResource(boolean useCache) {
        String resourceId = getDbResourceId()
        if (resourceId == null) return null
        return ecf.entityFacade.fastFindOne("moqui.resource.DbResource", useCache, false, resourceId)
    }
    private EntityValue getDbResourceFile() {
        String resourceId = getDbResourceId()
        if (resourceId == null) return null
        // don't cache this, can be big and will be cached below this as text if needed
        return ecf.entityFacade.fastFindOne("moqui.resource.DbResourceFile", false, false, resourceId)
    }
    private EntityValue getDbResourceFileHistory(String versionName) {
        if (versionName == null) return null
        String resourceId = getDbResourceId()
        if (resourceId == null) return null
        // don't cache this, can be big and will be cached below this as text if needed
        return ecf.entityFacade.fastFindOne("moqui.resource.DbResourceFileHistory", false, false, resourceId, versionName)
    }
}
