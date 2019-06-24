package com.zmtech.zkit.entity.impl;

import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.context.impl.ExecutionContextImpl;
import com.zmtech.zkit.entity.*;
import com.zmtech.zkit.entity.impl.condition.EntityConditionImplBase;
import com.zmtech.zkit.entity.impl.condition.impl.FieldValueCondition;
import com.zmtech.zkit.entity.impl.condition.impl.ListCondition;
import com.zmtech.zkit.etl.SimpleEtl;
import com.zmtech.zkit.exception.EntityException;
import com.zmtech.zkit.exception.EntityNotFoundException;
import com.zmtech.zkit.references.ResourceReference;
import com.zmtech.zkit.transaction.impl.TransactionFacadeImpl;
import com.zmtech.zkit.util.*;
import com.zmtech.zkit.util.EntityJavaUtil.*;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.typehandling.GroovyCastException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import javax.cache.Cache;
import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class EntityFacadeImpl implements EntityFacade {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFacadeImpl.class);
    protected final static boolean isTraceEnabled = logger.isTraceEnabled();

    public final ExecutionContextFactoryImpl ecfi;
    public final EntityConditionFactoryImpl entityConditionFactory;

    protected final ConcurrentHashMap<String, EntityDatasourceFactory> datasourceFactoryByGroupMap = new ConcurrentHashMap<>();

    /** 以实体名称为密钥，以EntityDefinition为值进行缓存; 清除此缓存以重新加载实体定义 */
    public final Cache<String, EntityDefinition> entityDefinitionCache;
    /** 具有单个条目的缓存可以过期/清除，实体名称的映射作为键并且文件位置列表作为值  */
    public final Cache<String, Map<String, List<String>>> entityLocationSingleCache;
    public static final String entityLocSingleEntryName = "ALL_ENTITIES";
    /** 映射框架实体定义，避免缓存开销和超时问题 */
    final HashMap<String, EntityDefinition> frameworkEntityDefinitions = new HashMap<>();

    /** 序列名称（通常是实体名称）是键，值是 2 的 Long 的数组，第一个是下一个可用值，第二个是bank中保留/缓存的最高值。*/
    public final Cache<String, long[]> entitySequenceBankCache;

    protected final ConcurrentHashMap<String, Lock> dbSequenceLocks = new ConcurrentHashMap<String, Lock>();
    protected final ReentrantLock locationLoadLock = new ReentrantLock();

    protected HashMap<String, ArrayList<EntityEcaRule>> eecaRulesByEntityName = new HashMap<>();
    protected final HashMap<String, String> entityGroupNameMap = new HashMap<>();
    protected final HashMap<String, MNode> databaseNodeByGroupName = new HashMap<>();
    protected final HashMap<String, MNode> datasourceNodeByGroupName = new HashMap<>();
    protected final String defaultGroupName;
    protected final TimeZone databaseTimeZone;
    protected final Locale databaseLocale;
    protected final ThreadLocal<Calendar> databaseTzLcCalendar = new ThreadLocal<>();
    protected final String sequencedIdPrefix;

    private boolean queryStats = false;

    protected EntityDbMeta dbMeta = null;
    protected final EntityCache entityCache;
    protected final EntityDataFeed entityDataFeed;
    protected final EntityDataDocument entityDataDocument;

    protected final EntityListImpl emptyList;

    public EntityFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi;
        entityConditionFactory = new EntityConditionFactoryImpl(this);

        MNode entityFacadeNode = getEntityFacadeNode();
        entityFacadeNode.setSystemExpandAttributes(true);
        defaultGroupName = entityFacadeNode.attribute("default-group-name");
        sequencedIdPrefix = entityFacadeNode.attribute("sequenced-id-prefix");
        queryStats = entityFacadeNode.attribute("query-stats").equals("true");

        TimeZone theTimeZone = null;
        if (entityFacadeNode.attribute("database-time-zone") != null) {
            try {
                theTimeZone = TimeZone.getTimeZone(entityFacadeNode.attribute("database-time-zone"));
            } catch (Exception e) { logger.warn("实体操作错误: 无法解析数据库时区(database-time-zone):" + e.toString()); }
        }
        databaseTimeZone = theTimeZone != null ? theTimeZone : TimeZone.getDefault();
        logger.info("实体操作信息: 数据库时区(time-zone)为" + databaseTimeZone);
        Locale theLocale = null;
        if (entityFacadeNode.attribute("database-locale") != null) {
            try {
                String localeStr = entityFacadeNode.attribute("database-locale");
                if (localeStr != null ) theLocale = localeStr.contains("_") ?
                        new Locale(localeStr.substring(0, localeStr.indexOf("_")), localeStr.substring(localeStr.indexOf("_")+1).toUpperCase()) :
                        new Locale(localeStr);
            } catch (Exception e) { logger.warn("实体操作错误: 无法解析数据库本地化语言(database-locale): "+e.toString());}
        }
        databaseLocale = theLocale != null ?theLocale: Locale.getDefault();
        logger.info("实体操作信息: 数据库本地化语言(database-locale)为" + databaseLocale);
        // 初始化实体元数据
        entityDefinitionCache = ecfi.getCache().getCache("entity.definition");
        entityLocationSingleCache = ecfi.getCache().getCache("entity.location");
        // 注意：在构造函数完成之前，不要尝试加载实体位置;this.loadAllEntityLocations（）
        entitySequenceBankCache = ecfi.getCache().getCache("entity.sequence.bank");

        // 初始化每个组的连接池（DataSource）
        try {
            initAllDatasources();
        } catch (Exception e) {
            e.printStackTrace();
        }

        entityCache = new EntityCache(this);
        entityDataFeed = new EntityDataFeed(this);
        entityDataDocument = new EntityDataDocument(this);

        emptyList = new EntityListImpl(this);
        emptyList.setFromCache();
    }

    public void postFacadeInit() {
        // ========== 提前加载一些东西，因此第一页命中生产速度更快（在开发模式下，当缓存超时时，无论如何都会重新加载）
        // 加载实体定义
        logger.info("实体操作信息: 开始加载实体定义!");
        long entityStartTime = System.currentTimeMillis();
        loadAllEntityLocations();
        int entityCount = loadAllEntityDefinitions();
        // 不要总是加载/加热框架实体，在生产环境中并且不需要加载在开发环境不需要 dev：entityFacade.loadFrameworkEntities（）
        logger.info("实体操作信息: 加载了 ["+entityCount+"] 条实体定义 用时 "+ (System.currentTimeMillis() - entityStartTime) +" 毫秒!");

        // 现在一切都已启动，如果配置了检查所有实体表
        checkInitDatasourceTables();

        // 加载 EECA 规则
        loadEecaRulesAll();
    }

    public EntityCache getEntityCache() { return entityCache; }
    public EntityDataFeed getEntityDataFeed() { return entityDataFeed; }
    public EntityDataDocument getEntityDataDocument() { return entityDataDocument; }
    public String getDefaultGroupName() { return defaultGroupName; }

    // 注意：用于脚本等情况
    TimeZone getDatabaseTimeZone() { return databaseTimeZone; }
    Locale getDatabaseLocale() { return databaseLocale; }

    EntityListImpl getEmptyList() { return emptyList; }

    @Override
    public Calendar getCalendarForTzLc() {
        // OLD approach 使用用户的TimeZone / Locale 这并不合理，因为用户可能会更改相同的记录，获得不同的值等
        // 使用 efi.getEcfi().getExecutionContext().getUser().getCalendarForTzLcOnly()

        // 最安全的方法，但从分析测试来看，这非常慢:
        // 使用 Calendar.getInstance(databaseTimeZone, databaseLocale)
        // 注意：这种方法更快但似乎导致Derby错误（ERROR 22007：日期/时间值的字符串表示超出范围）
        // 使用 databaseTzLcCalendar
        // 注意，此字段是Calendar对象，现在是ThreadLocal <Calendar>

        // 避免为每次使用创建Calendar对象的最新方法，使用ThreadLocal字段
        Calendar dbCal = databaseTzLcCalendar.get();
        if (dbCal == null) {
            dbCal = Calendar.getInstance(databaseTimeZone, databaseLocale);
            dbCal.clear();
            databaseTzLcCalendar.set(dbCal);
        } else {
            dbCal.clear();
        }
        return dbCal;
    }

    public MNode getEntityFacadeNode() { return ecfi.getConfXmlRoot().first("entity-facade"); }
    public void checkInitDatasourceTables() {
        // if startup-add-missing=true check tables now
        long currentTime = System.currentTimeMillis();

        Set<String> startupAddMissingGroups = new TreeSet<>();
        Set<String> allConfiguredGroups = new TreeSet<>();
        for (MNode datasourceNode : getEntityFacadeNode().children("datasource")) {
            String groupName = datasourceNode.attribute("group-name");
            MNode databaseNode = getDatabaseNode(groupName);
            String startupAddMissing = datasourceNode.attribute("startup-add-missing");
            if ((startupAddMissing == null && "true".equals(databaseNode.attribute("default-startup-add-missing"))) || "true".equals(startupAddMissing)) {
                startupAddMissingGroups.add(groupName);
            }
            allConfiguredGroups.add(groupName);
        }

        boolean defaultStartAddMissing = startupAddMissingGroups.contains(getEntityFacadeNode().attribute("default-group-name"));
        if (startupAddMissingGroups.size() > 0) {
            logger.info("实体操作信息:检查组 ["+startupAddMissingGroups+"] 中的实体表!");
            boolean createdTables = false;
            for (String entityName : getAllEntityNames()) {
                String groupName = getEntityGroupName(entityName) != null ? getEntityGroupName(entityName) : defaultGroupName;
                if (startupAddMissingGroups.contains(groupName) ||
                        (!allConfiguredGroups.contains(groupName) && defaultStartAddMissing)) {
                    EntityDatasourceFactory edf = getDatasourceFactory(groupName);
                    if (edf.checkAndAddTable(entityName)) createdTables = true;
                }
            }
            // 做第二遍以确保创建所有FK
            if (createdTables) {
                logger.info("实体操作信息: 数据库建表完成, 检查组 ["+startupAddMissingGroups+"] 中所有实体的外键! 竟然没有错,牛逼! ");
                for (String entityName : getAllEntityNames()) {
                    String groupName = getEntityGroupName(entityName) != null ? getEntityGroupName(entityName): defaultGroupName;
                    if (startupAddMissingGroups.contains(groupName) ||
                            (!allConfiguredGroups.contains(groupName) && defaultStartAddMissing)) {
                        EntityDatasourceFactory edf = getDatasourceFactory(groupName);
                        if (edf instanceof EntityDatasourceFactoryImpl) {
                            EntityDefinition ed = getEntityDefinition(entityName);
                            if (ed.isViewEntity) continue;
                            getEntityDbMeta().createForeignKeys(ed, true);

                        }
                    }
                }
            }
            logger.info("实体操作信息: 已完成检查所有实体的表, 用时 "+((System.currentTimeMillis() - currentTime)/1000)+" 秒!");
        }
    }

    protected void initAllDatasources() throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        for (MNode datasourceNode : getEntityFacadeNode().children("datasource")) {
            datasourceNode.setSystemExpandAttributes(true);
            String groupName = datasourceNode.attribute("group-name");
            String objectFactoryClass = datasourceNode.attribute("object-factory") != null? datasourceNode.attribute("object-factory"): "com.zmtech.zkit.entity.impl.EntityDatasourceFactoryImpl";
            EntityDatasourceFactory edf = (EntityDatasourceFactory) Thread.currentThread().getContextClassLoader().loadClass(objectFactoryClass).newInstance();
            datasourceFactoryByGroupMap.put(groupName, edf.init(this, datasourceNode));
        }
    }

    public static class DatasourceInfo {
        public EntityFacadeImpl efi;
        public MNode datasourceNode;
        public String uniqueName;
        public Map<String, String> dsDetails = new LinkedHashMap<>();

        public String jndiName;
        public MNode serverJndi;
        public String jdbcDriver = null, jdbcUri = null, jdbcUsername = null, jdbcPassword = null;
        public String xaDsClass = null;
        public Properties xaProps = null;

        public MNode inlineJdbc = null;
        public MNode database = null;

        public DatasourceInfo(EntityFacadeImpl efi, MNode datasourceNode) {
            this.efi = efi;
            this.datasourceNode = datasourceNode;

            String groupName = datasourceNode.attribute("group-name");
            uniqueName =  groupName + "_DS";

            MNode jndiJdbcNode = datasourceNode.first("jndi-jdbc");
            inlineJdbc = datasourceNode.first("inline-jdbc");
            if (jndiJdbcNode == null && inlineJdbc == null) {
                MNode dbNode = efi.getDatabaseNode(groupName);
                inlineJdbc = dbNode.first("inline-jdbc");
            }
            MNode xaProperties = inlineJdbc != null?inlineJdbc.first("xa-properties"):null;
            database = efi.getDatabaseNode(groupName);

            if (jndiJdbcNode != null) {
                serverJndi = efi.getEntityFacadeNode().first("server-jndi");
                if (serverJndi != null) serverJndi.setSystemExpandAttributes(true);
                jndiName = jndiJdbcNode.attribute("jndi-name");
            } else if (xaProperties != null) {
                xaDsClass = inlineJdbc.attribute("xa-ds-class") != null ? inlineJdbc.attribute("xa-ds-class") : database.attribute("default-xa-ds-class");

                xaProps = new Properties();
                xaProperties.setSystemExpandAttributes(true);
                for (String key : xaProperties.attributes().keySet()) {
                    if (xaProps.containsKey(key)) continue;
                    // 各种H2，Derby等属性都有$ {zkit.runtime}，这是一个系统属性，其他数据库也可以拥有它
                    String propValue = xaProperties.attribute(key);
                    if (propValue != null) xaProps.setProperty(key, propValue);
                }

                for (String propName : xaProps.stringPropertyNames()) {
                    if (propName.toLowerCase().contains("password")) continue;
                    dsDetails.put(propName, xaProps.getProperty(propName));
                }
            } else if (inlineJdbc != null) {
                inlineJdbc.setSystemExpandAttributes(true);
                jdbcDriver = inlineJdbc.attribute("jdbc-driver") != null? inlineJdbc.attribute("jdbc-driver") : database.attribute("default-jdbc-driver");
                jdbcUri = inlineJdbc.attribute("jdbc-uri");
                if (jdbcUri.contains("${")) jdbcUri = SystemBinding.expand(jdbcUri);
                jdbcUsername = inlineJdbc.attribute("jdbc-username");
                jdbcPassword = inlineJdbc.attribute("jdbc-password");

                dsDetails.put("uri", jdbcUri);
                dsDetails.put("user", jdbcUsername);
            } else {
                throw new EntityException("实体操作错误: 组 ["+groupName+"] 的数据源没有 inline-jdbc 或 jndi-jdbc 设置!");
            }
        }
    }

    public void loadFrameworkEntities() {
        // 加载框架实体 (zkit.*)
        long startTime = System.currentTimeMillis();
        Set<String> entityNames = getAllEntityNames();
        int entityCount = 0;
        for (String entityName : entityNames) {
            if (entityName.startsWith("zkit.")) {
                entityCount++;
                try {
                    EntityDefinition ed = getEntityDefinition(entityName);
                    ed.getRelationshipInfoMap();
                    // 必须使用EntityDatasourceFactory.checkTableExists，不可以使用 entityDbMeta.tableExists
                    ed.entityInfo.datasourceFactory.checkTableExists(ed.getFullEntityName());
                } catch (Throwable t) { logger.warn("实体操作错误: 无法加载框架实体 ["+entityName+"]: "+t.toString(), t) ;}
            }
        }
        logger.info("实体操作信息: 加载了 ["+entityCount+"] 条框架实体, 用时 ["+(System.currentTimeMillis() - startTime)+"] 毫秒!");
    }

    public final static Set<String> cachedCountEntities = new HashSet<>(Collections.singletonList("zkit.basic.EnumerationType"));
    public final static Set<String> cachedListEntities = new HashSet<>(Arrays.asList("zkit.entity.document.DataDocument",
            "zkit.entity.document.DataDocumentCondition", "zkit.entity.document.DataDocumentField",
            "zkit.entity.feed.DataFeedAndDocument", "zkit.entity.view.DbViewEntity", "zkit.entity.view.DbViewEntityAlias",
            "zkit.entity.view.DbViewEntityKeyMap", "zkit.entity.view.DbViewEntityMember",

            "zkit.screen.ScreenThemeResource", "zkit.screen.SubscreensItem", "zkit.screen.form.DbFormField",
            "zkit.screen.form.DbFormFieldAttribute", "zkit.screen.form.DbFormFieldEntOpts", "zkit.screen.form.DbFormFieldEntOptsCond",
            "zkit.screen.form.DbFormFieldEntOptsOrder", "zkit.screen.form.DbFormFieldOption", "zkit.screen.form.DbFormLookup",

            "zkit.security.ArtifactAuthzCheckView", "zkit.security.ArtifactTarpitCheckView", "zkit.security.ArtifactTarpitLock",
            "zkit.security.UserGroupMember", "zkit.security.UserGroupPreference"));
    public final static Set<String> cachedOneEntities = new HashSet<>(Arrays.asList("zkit.basic.Enumeration", "zkit.basic.LocalizedMessage",
            "zkit.entity.document.DataDocument", "zkit.entity.view.DbViewEntity", "zkit.screen.form.DbForm",
            "zkit.security.UserAccount", "zkit.security.UserPreference", "zkit.security.UserScreenTheme", "zkit.server.Visit"));
    public void warmCache()  {
        logger.info("实体操作信息: 开始加载实体定义缓存!");
        long startTime = System.currentTimeMillis();
        Set<String> entityNames = getAllEntityNames();
        for (String entityName : entityNames) {
            try {
                EntityDefinition ed = getEntityDefinition(entityName);
                ed.getRelationshipInfoMap();
                // 必须使用EntityDatasourceFactory.checkTableExists，不能使用 entityDbMeta.tableExists
                ed.entityInfo.datasourceFactory.checkTableExists(ed.getFullEntityName());

                if (cachedCountEntities.contains(entityName)) ed.getCacheCount(entityCache);
                if (cachedListEntities.contains(entityName)) {
                    ed.getCacheList(entityCache);
                    ed.getCacheListRa(entityCache);
                    ed.getCacheListViewRa(entityCache);
                }
                if (cachedOneEntities.contains(entityName)) {
                    ed.getCacheOne(entityCache);
                    ed.getCacheOneRa(entityCache);
                    ed.getCacheOneViewRa(entityCache);
                }
            } catch (Throwable t) { logger.warn("实体操作错误: 无法加载实体缓存 : "+t.toString()); }
        }

        logger.info("实体操作信息: 完成加载实体缓存, 加载了 ["+entityNames.size()+"] 条实体, 用时 ["+(System.currentTimeMillis() - startTime)+"] 毫秒!");
    }

    public Set<String> getDatasourceGroupNames() {
        Set<String> groupNames = new TreeSet<>();
        for (MNode datasourceNode : getEntityFacadeNode().children("datasource")) {
            groupNames.add((String) datasourceNode.attribute("group-name"));
        }
        return groupNames;
    }

    public static int getTxIsolationFromString(String isolationLevel) {
        if (isolationLevel == null) return -1;
        if ("Serializable".equals(isolationLevel)) {
            return Connection.TRANSACTION_SERIALIZABLE;
        } else if ("RepeatableRead".equals(isolationLevel)) {
            return Connection.TRANSACTION_REPEATABLE_READ;
        } else if ("ReadUncommitted".equals(isolationLevel)) {
            return Connection.TRANSACTION_READ_UNCOMMITTED;
        } else if ("ReadCommitted".equals(isolationLevel)) {
            return Connection.TRANSACTION_READ_COMMITTED;
        } else if ("None".equals(isolationLevel)) {
            return Connection.TRANSACTION_NONE;
        } else {
            return -1;
        }
    }

    public List<ResourceReference> getAllEntityFileLocations() {
        List<ResourceReference> entityRrList = new LinkedList<>();
        entityRrList.addAll(getConfEntityFileLocations());
//        entityRrList.addAll(getComponentEntityFileLocations(null));
        return entityRrList;
    }
    public List<ResourceReference> getConfEntityFileLocations() {
        List<ResourceReference> entityRrList = new LinkedList<>();

        // loop through all of the entity-facade.load-entity nodes, check each for "<entities>" root element
        for (MNode loadEntity : getEntityFacadeNode().children("load-entity")) {
            entityRrList.add(this.ecfi.getResource().getLocationReference((String) loadEntity.attribute("location")));
        }

        return entityRrList;
    }
//    public List<ResourceReference> getComponentEntityFileLocations(List<String> componentNameList) {
//        List<ResourceReference> entityRrList = new LinkedList();
//
//        List<String> componentBaseLocations;
//        if (componentNameList != null && !componentNameList.isEmpty()) {
//            componentBaseLocations = new ArrayList<>();
//            for (String cn : componentNameList)
//            componentBaseLocations.add(ecfi.getComponentBaseLocations().get(cn));
//        } else {
//            componentBaseLocations = new ArrayList(ecfi.getComponentBaseLocations().values());
//        }
//
//        // loop through components look for XML files in the entity directory, check each for "<entities>" root element
//        for (String location in componentBaseLocations) {
//            ResourceReference entityDirRr = ecfi.resourceFacade.getLocationReference(location + "/entity")
//            if (entityDirRr.supportsAll()) {
//                // if directory doesn't exist skip it, component doesn't have an entity directory
//                if (!entityDirRr.exists || !entityDirRr.isDirectory()) continue
//                        // get all files in the directory
//                TreeMap<String, ResourceReference> entityDirEntries = new TreeMap<String, ResourceReference>()
//                for (ResourceReference entityRr in entityDirRr.directoryEntries) {
//                    if (!entityRr.isFile() || !entityRr.location.endsWith(".xml")) continue
//                    entityDirEntries.put(entityRr.getFileName(), entityRr)
//                }
//                for (Map.Entry<String, ResourceReference> entityDirEntry in entityDirEntries) {
//                    entityRrList.add(entityDirEntry.getValue())
//                }
//            } else {
//                // just warn here, no exception because any non-file component location would blow everything up
//                logger.warn("Cannot load entity directory in component location [${location}] because protocol [${entityDirRr.uri.scheme}] is not supported.")
//            }
//        }
//
//        return entityRrList
//    }
//
    public Map<String, List<String>> loadAllEntityLocations() {
        // 锁定或等待锁定，这里使用此锁定检查实体定义
        locationLoadLock.lock();

        try {
            // 基于ResourceReference加载所有实体文件
            long startTime = System.currentTimeMillis();

            Map<String, List<String>> entityLocationCache = entityLocationSingleCache.get(entityLocSingleEntryName);
            // 当加载所有实体位置时，如果不需要加载，我们希望它为null
            if (entityLocationCache != null) return entityLocationCache;
            entityLocationCache = new HashMap<>();

            List<ResourceReference> allEntityFileLocations = getAllEntityFileLocations();
            for (ResourceReference entityRr : allEntityFileLocations) this.loadEntityFileLocations(entityRr, entityLocationCache);
            if (logger.isInfoEnabled()) logger.info("实体操作信息: 找到 ["+allEntityFileLocations.size()+"] 个文件,用时 ["+(System.currentTimeMillis() - startTime)+"] 毫秒!");

            // 放入缓存以供其他代码使用; 在DbViewEntity加载之前需要，以便DB查询工作
            entityLocationSingleCache.put(entityLocSingleEntryName, entityLocationCache);

            // 查看数据库中 view-entity (zkit.entity.view.DbViewEntity) 定义
            if (entityLocationCache.get("zkit.entity.view.DbViewEntity") != null) {
                int numDbViewEntities = 0;
                for (EntityValue dbViewEntity : find("zkit.entity.view.DbViewEntity").list()) {
                    if (dbViewEntity.get("packageName") != null) {
                        List<String> pkgList = entityLocationCache.get(dbViewEntity.getString("packageName") + "." + dbViewEntity.getString("dbViewEntityName"));
                        if (pkgList == null) {
                            pkgList = new LinkedList<>();
                            entityLocationCache.put(dbViewEntity.getString("dbViewEntityName") + "." + dbViewEntity.getString("dbViewEntityName"), pkgList);
                        }
                        if (!pkgList.contains("_DB_VIEW_ENTITY_")) pkgList.add("_DB_VIEW_ENTITY_");
                    }

                    List<String> nameList = entityLocationCache.get(dbViewEntity.getString("dbViewEntityName"));
                    if (nameList == null) {
                        nameList = new LinkedList<>();
                        // put in cache under both plain entityName and fullEntityName
                        entityLocationCache.put( dbViewEntity.getString("dbViewEntityName"), nameList);
                    }
                    if (!nameList.contains("_DB_VIEW_ENTITY_")) nameList.add("_DB_VIEW_ENTITY_");

                    numDbViewEntities++;
                }
                if (logger.isInfoEnabled()) logger.info("实体操作信息: 在数据库中找到 ["+numDbViewEntities+"] 个视图实体定义!");
            } else {
                logger.warn("实体警告信息: 数据库中不存在 [zkit.entity.view.DbViewEntity] 的视图实体定义!");
            }

            /* a little code to show all entities and their locations
            Set<String> enSet = new TreeSet(entityLocationCache.keySet())
            for (String en in enSet) {
                List lst = entityLocationCache.get(en)
                entityLocationCache.put(en, Collections.unmodifiableList(lst))
                logger.warn("TOREMOVE entity ${en}: ${lst}")
            }
            */

            return entityLocationCache;
        } finally {
            locationLoadLock.unlock();
        }
    }

    //注意：仅由loadAllEntityLocations（）调用，它是同步/锁定的，因此不需要用
    protected void loadEntityFileLocations(ResourceReference entityRr, Map<String, List<String>> entityLocationCache) {
        MNode entityRoot = getEntityFileRoot(entityRr);
        if (entityRoot.getName() == "entities") {
            // 循环遍历所有实体，视图实体和扩展实体，并把已命名的实体添加文件位置到List
            int numEntities = 0;
            for (MNode entity : entityRoot.getChildren()) {
                String entityName = entity.attribute("entity-name");
                String packageName = entity.attribute("package");
                if (packageName == null || packageName.isEmpty()) packageName = entity.attribute("package-name");
                String shortAlias = entity.attribute("short-alias");

                if (entityName == null || entityName.length() == 0) {
                    logger.warn("实体操作警告: 跳过实体定义XML文件 ["+entityRr.getLocation()+"] , 因为没有@entity-name ["+entity+"]!");
                    continue;
                }

                List<String> locList = entityLocationCache.get(entityName);
                if (locList == null) {
                    locList = new LinkedList<>();
                    locList.add(entityRr.getLocation());
                    entityLocationCache.put(entityName, locList);
                } else if (!locList.contains(entityRr.getLocation())) {
                    locList.add(entityRr.getLocation());
                }

                if (packageName != null && !packageName.isEmpty()) {
                    String fullEntityName = packageName.concat(".").concat(entityName);
                    if (!entityLocationCache.containsKey(fullEntityName)) entityLocationCache.put(fullEntityName, locList);
                }
                if (shortAlias != null && !shortAlias.isEmpty()) {
                    if (!entityLocationCache.containsKey(shortAlias)) entityLocationCache.put(shortAlias, locList);
                }

                numEntities++;
            }
            if (isTraceEnabled) logger.trace("实体操作跟踪: 在文件 ["+entityRr.getLocation()+"] 中找到了 ["+numEntities+"] 条实体定义!");
        }
    }

    protected static MNode getEntityFileRoot(ResourceReference entityRr) { return MNode.parse(entityRr) ;}

    public int loadAllEntityDefinitions() {
        int entityCount = 0;
        for (String en : getAllEntityNames()) {
            try {
                getEntityDefinition(en);
            } catch (EntityException e) {
                logger.warn("实体操作警告: 实体定义查询错误!", e);
                continue;
            }
            entityCount++;
        };
        return entityCount;
    }


    protected EntityDefinition loadEntityDefinition(String entityName) {
        if (entityName.contains("#")) {
            // 这是一个关系名称，不是实体名称所以只返回null; 发生这种情况是因为我们在各个地方检查名称是否是实体名称，包括检查关系的位置
            return null;
        }

        EntityDefinition ed = entityDefinitionCache.get(entityName);
        if (ed != null) return ed;

        Map<String, List<String>> entityLocationCache = entityLocationSingleCache.get(entityLocSingleEntryName);
        if (entityLocationCache == null) entityLocationCache = loadAllEntityLocations();

        List<String> entityLocationList = entityLocationCache.get(entityName);
        if (entityLocationList == null) {
            if (logger.isWarnEnabled()) logger.warn("实体操作警告: 没有找到实体 ["+entityName+"] 的缓存位置,重新加载实体文件和数据文件中...");
            if (isTraceEnabled) logger.trace("实体操作跟踪: 未知的实体 ["+entityName+"] 路径!", new EntityException("未知的实体路径!"));

            // 删除单个缓存数据
            entityLocationSingleCache.remove(entityLocSingleEntryName);
            // 重新加载所有位置
            entityLocationCache = this.loadAllEntityLocations();
            entityLocationList = entityLocationCache.get(entityName);
            // 找不到此实体的位置，实体可能不存在
            if (entityLocationList == null || entityLocationList.size() == 0) {
                // TODO: 虽然这很有用，但如果找到另一个未知的不存在的实体，这将丢失
                entityLocationCache.put(entityName, new LinkedList<>());
                if (logger.isWarnEnabled()) logger.warn("实体操作警告: 没有找到实体 ["+entityName+"] 的实体定义!");
                throw new EntityNotFoundException("实体操作错误:没有找到实体 ["+entityName+"] 的实体定义!");
            }
        }

        if (entityLocationList.size() == 0) {
            if (isTraceEnabled) logger.trace("实体操作跟踪: 实体 ["+entityName+"] 未定义 ,返回空的实体定义!");
            return null;
        }

        String packageName = null;
        if (entityName.contains(".")) {
            packageName = entityName.substring(0, entityName.lastIndexOf("."));
            entityName = entityName.substring(entityName.lastIndexOf(".")+1);
        }

        // if (!packageName) logger.warn("TOREMOVE finding entity def for [${entityName}] with no packageName, entityLocationList=${entityLocationList}")

        // 如果这是一个zkit.entity.view.DbViewEntity，请以特殊方式处理（从DB记录生成节点）
        if (entityLocationList.contains("_DB_VIEW_ENTITY_")) {
            EntityValue dbViewEntity = find("zkit.entity.view.DbViewEntity").condition("dbViewEntityName", entityName).one();
            if (dbViewEntity == null) {
                logger.warn("实体操作警告: 实体 ["+entityName+"] 的 DbViewEntity 不存在!");
                return null;
            }
            String finalEntityName = entityName;
            MNode dbViewNode = new MNode("view-entity",new ConcurrentHashMap<String,String>(){{
                put("entity-name", finalEntityName);
                put("package",dbViewEntity.getEntityName());
            }} );
            if ("Y".equals(dbViewEntity.get("cache"))) dbViewNode.attributes().put("cache", "true");
            else if ("N".equals(dbViewEntity.get("cache"))) dbViewNode.attributes().put("cache", "false");

            EntityList memberList = find("zkit.entity.view.DbViewEntityMember").condition("dbViewEntityName", entityName).list();
            for (EntityValue dbViewEntityMember : memberList) {
                MNode memberEntity = dbViewNode.append("member-entity",new ConcurrentHashMap<String,String>(){{
                    put("entity-alias",dbViewEntityMember.getString("entityAlias"));
                    put("entity-name",dbViewEntityMember.getString("entityName"));
                }});
                if (dbViewEntityMember.get("joinFromAlias") != null) {
                    memberEntity.attributes().put("join-from-alias", (String) dbViewEntityMember.get("joinFromAlias"));
                    if (dbViewEntityMember.get("joinOptional") == "Y") memberEntity.attributes().put("join-optional", "true");
                }

                String finalEntityName1 = entityName;
                EntityList dbViewEntityKeyMapList = find("zkit.entity.view.DbViewEntityKeyMap")
                        .condition(new ConcurrentHashMap<String,Object>(){{
                            put("dbViewEntityName", finalEntityName1);
                            put("joinFromAlias", dbViewEntityMember.get("joinFromAlias"));
                            put("entityAlias", dbViewEntityMember.getString("entityAlias"));
                        }})
                        .list();
                for (EntityValue dbViewEntityKeyMap : dbViewEntityKeyMapList) {
                    MNode keyMapNode = memberEntity.append("key-map", new ConcurrentHashMap<String,String>(){{
                        put("field-name",(String)dbViewEntityKeyMap.get("fieldName"));
                    }});
                    if (dbViewEntityKeyMap.get("relatedFieldName")!=null)
                        keyMapNode.attributes().put("related", (String) dbViewEntityKeyMap.get("relatedFieldName"));
                }
            }
            for (EntityValue dbViewEntityAlias : find("zkit.entity.view.DbViewEntityAlias").condition("dbViewEntityName", entityName).list()) {
                MNode aliasNode = dbViewNode.append("alias",new ConcurrentHashMap<String,String>(){{
                    put("name",(String) dbViewEntityAlias.get("fieldAlias"));
                    put("entity-alias",(String) dbViewEntityAlias.get("entityAlias"));
                }});
                if (dbViewEntityAlias.get("fieldName") != null) aliasNode.attributes().put("field", (String) dbViewEntityAlias.get("fieldName"));
                if (dbViewEntityAlias.get("functionName") != null) aliasNode.attributes().put("function", (String) dbViewEntityAlias.get("functionName"));
            }

            // 创建新的实体定义
            ed = new EntityDefinition(this, dbViewNode);

            // 将它缓存在entityName，fullEntityName和short-alias下
            String fullEntityName = ed.fullEntityName;
            if (fullEntityName.startsWith("zkit.")) {
                frameworkEntityDefinitions.put(ed.entityInfo.internalEntityName, ed);
                frameworkEntityDefinitions.put(fullEntityName, ed);
                if (ed.entityInfo.shortAlias != null ) frameworkEntityDefinitions.put(ed.entityInfo.shortAlias, ed);
            } else {
                entityDefinitionCache.put(ed.entityInfo.internalEntityName, ed);
                entityDefinitionCache.put(fullEntityName, ed);
                if (ed.entityInfo.shortAlias != null ) entityDefinitionCache.put(ed.entityInfo.shortAlias, ed);
            }
            // 送它上路
            return ed;
        }

        // 获取实体，视图实体和扩展实体节点
        MNode entityNode = null;
        List<MNode> extendEntityNodes = new ArrayList<>();
        for (String location : entityLocationList) {
            MNode entityRoot = getEntityFileRoot(this.ecfi.getResource().getLocationReference(location));
            // 如果指定过滤包，其他情况随便取
            String finalEntityName = entityName;
            String finalPackageName = packageName;
            List<MNode> packageChildren = entityRoot.getChildren().stream().filter((MNode it)-> (it.attribute("entity-name").equals(finalEntityName) || it.attribute("short-alias").equals(finalEntityName)) &&
                    (finalPackageName == null || (it.attribute("package").equals(finalPackageName) || it.attribute("package-name").equals(finalPackageName)))).collect(Collectors.toList());
            for (MNode childNode : packageChildren) {
                if (childNode.getName().equals("extend-entity")) {
                    extendEntityNodes.add(childNode);
                } else {
                    if (entityNode != null) logger.warn("实体操作警告: 找到路径为 ["+location+"] 实体 ["+entityName+"] 的重复定义, 覆盖之前版本的实体定义!");
                    entityNode = childNode.deepCopy(null);
                }
            }
        }
        if (entityNode == null) throw new EntityNotFoundException("实体操作错误: "+(packageName != null && !packageName.isEmpty()? "包 ["+packageName+"] 中" : "") +" 没有找到实体 ["+entityName+"] ");

        // 如果entityName是一个短别名，那么扩展实体元素将不匹配它，所以现在我们有了主实体节点再次找到它们
        if (entityName.equals(entityNode.attribute("short-alias"))) {
            entityName = entityNode.attribute("entity-name");
            packageName = entityNode.attribute("package") != null ? entityNode.attribute("package"): entityNode.attribute("package-name");
            for (String location : entityLocationList) {
                MNode entityRoot = getEntityFileRoot(this.ecfi.getResource().getLocationReference(location));
                String finalEntityName = entityName;
                String finalPackageName = packageName;
                List<MNode> packageChildren = entityRoot.getChildren().stream().filter((MNode it)-> it.attribute("entity-name").equals(finalEntityName) &&
                        (finalPackageName == null || finalPackageName.isEmpty() || (it.attribute("package").equals(finalPackageName) || it.attribute("package-name").equals(finalPackageName))) ).collect(Collectors.toList());
                for (MNode childNode : packageChildren) {
                    if (childNode.getName().equals("extend-entity")) {
                        extendEntityNodes.add(childNode);
                    }
                }
            }
        }
        // if (entityName.endsWith("xample")) logger.warn("======== Creating Example ED entityNode=${entityNode}\nextendEntityNodes: ${extendEntityNodes}")

        // 合并扩展实体节点
        for (MNode extendEntity : extendEntityNodes) {
            // 如果包属性不匹配，请跳过
            String entityPackage = entityNode.attribute("package") != null ? entityNode.attribute("package"): entityNode.attribute("package-name");
            String extendPackage = extendEntity.attribute("package") != null ? extendEntity.attribute("package") : extendEntity.attribute("package-name");
            if (!entityPackage.equals(extendPackage) ) continue;
            // 合并属性
            entityNode.attributes().putAll(extendEntity.attributes());
            // 合并字段节点
            for (MNode childOverrideNode : extendEntity.children("field")) {
                String keyValue = childOverrideNode.attribute("name");
                MNode childBaseNode = entityNode.first((MNode it)-> it.getName().equals("field") && it.attribute("name").equals(keyValue));
                if (childBaseNode != null) childBaseNode.attributes().putAll(childOverrideNode.attributes());
                else entityNode.append(childOverrideNode);
            }
            // 添加关系，键映射（复制，也将获得子节点）
            ArrayList<MNode> relNodeList = extendEntity.children("relationship");
            for (int i = 0; i < relNodeList.size(); i++) {
                MNode copyNode = relNodeList.get(i);
                Optional<MNode> optionalFilterNode = entityNode.getChildren().stream().filter((MNode it) ->{
                    String itRelated = it.attribute("related") != null?it.attribute("related"):it.attribute("related-entity-name");
                    String copyRelated = copyNode.attribute("related") != null ? copyNode.attribute("related"): copyNode.attribute("related-entity-name");

                    return it.getName().equals("relationship") && itRelated.equals(copyRelated) &&
                            it.attribute("title").equals(copyNode.attribute("title"));
                }).findFirst();
                int curNodeIndex = 0;
                if(optionalFilterNode.isPresent()){
                    curNodeIndex = entityNode.getChildren().indexOf(optionalFilterNode.get());
                }
                if (curNodeIndex >= 0) {
                    entityNode.getChildren().set(curNodeIndex, copyNode);
                } else {
                    entityNode.append(copyNode);
                }
            }
            // 添加索引，索引字段
            for (MNode copyNode : extendEntity.children("index")) {
                Optional<MNode> optionalFilterNode = entityNode.getChildren().stream().filter((MNode it)-> it.getName().equals("index") && it.attribute("name").equals(copyNode.attribute("name"))).findFirst();
                int curNodeIndex = 0;
                if(optionalFilterNode.isPresent()){
                    curNodeIndex = entityNode.getChildren().indexOf(optionalFilterNode.get());
                }
                if (curNodeIndex >= 0) {
                    entityNode.getChildren().set(curNodeIndex, copyNode);
                } else {
                    entityNode.append(copyNode);
                }
            }
            // 复制主节点（将在解析时合并）
            // TODO: 在将其附加到entityNode之前检查主/明细存在
            for (MNode copyNode : extendEntity.children("master")) entityNode.append(copyNode);
        }

        // 创建新的实体定义
        ed = new EntityDefinition(this, entityNode);
        // 将它缓存在 entityName，fullEntityName和short-alias下
        String fullEntityName = ed.fullEntityName;
        if (fullEntityName.startsWith("zkit.")) {
            frameworkEntityDefinitions.put(ed.entityInfo.internalEntityName, ed);
            frameworkEntityDefinitions.put(fullEntityName, ed);
            if (ed.entityInfo.shortAlias != null && !ed.entityInfo.shortAlias.isEmpty()) frameworkEntityDefinitions.put(ed.entityInfo.shortAlias, ed);
        } else {
            entityDefinitionCache.put(ed.entityInfo.internalEntityName, ed);
            entityDefinitionCache.put(fullEntityName, ed);
            if (ed.entityInfo.shortAlias != null && !ed.entityInfo.shortAlias.isEmpty()) entityDefinitionCache.put(ed.entityInfo.shortAlias, ed);
        }
        // 送它上路
        return ed;
    }

    public synchronized void createAllAutoReverseManyRelationships() {
        int relationshipsCreated = 0;
        Set<String> entityNameSet = getAllEntityNames();
        for (String entityName : entityNameSet) {
            EntityDefinition ed;
            // 对于自动反向关系，只需忽略 getEntityDefinition 方法上的 EntityException 异常
            try { ed = getEntityDefinition(entityName); } catch (EntityException e) { if (isTraceEnabled) logger.trace("实体操作跟踪:实体不存在!", e); continue; }
            // 如果所有实体名称都包含DB视图实体或其他实际不存在的实体，则可能会发生
            if (ed == null) continue;
            String edEntityName = ed.entityInfo.internalEntityName;
            String edFullEntityName = ed.fullEntityName;
            List<String> pkSet = ed.getPkFieldNames();
            ArrayList<MNode> relationshipList = ed.getEntityNode().children("relationship");
            int relationshipListSize = relationshipList.size();
            for (int rlIndex = 0; rlIndex < relationshipListSize; rlIndex++) {
                MNode relNode = relationshipList.get(rlIndex);
                // 不要为自动引用关系创建反向
                if ("true".equals(relNode.attribute("is-auto-reverse"))) continue;
                String relatedEntityName = relNode.attribute("related");
                if (relatedEntityName == null || relatedEntityName.length() == 0) relatedEntityName = relNode.attribute("related-entity-name");
                // 不要创建回到同一实体的反向关系，因为它将具有相同的标题，它将创建具有相同名称的多个关系
                if (entityName.equals(relatedEntityName)) continue;

                EntityDefinition reverseEd;
                try {
                    reverseEd = getEntityDefinition(relatedEntityName);
                } catch (EntityException e) {
                    logger.error("实体操作错误: 无法获取实体 ["+entityName+"] 的引用实体 ["+relatedEntityName+"] 的实体定义! : "+e.toString());
                    continue;
                }
                if (reverseEd == null) {
                    logger.warn("实体操作警告: 无法获取实体 ["+entityName+"] 的引用实体 ["+relatedEntityName+"] 的实体定义!");
                    continue;
                }

                List<String> reversePkSet = reverseEd.getPkFieldNames();
                String relType = reversePkSet.equals(pkSet) ? "one-nofk" : "many";
                String title = relNode.attribute("title");
                boolean hasTitle = title != null && title.length() > 0;

                // 回归的关系是否已经存在？
                boolean foundReverse = false;
                ArrayList<MNode> reverseRelList = reverseEd.getEntityNode().children("relationship");
                for (MNode reverseRelNode : reverseRelList) {
                    String related = reverseRelNode.attribute("related");
                    if (related == null || related.length() == 0)
                        related = reverseRelNode.attribute("related-entity-name");
                    if (!edEntityName.equals(related) && !edFullEntityName.equals(related)) continue;
                    // TODO: 替换检查标题检查反向扩展键映射
                    String reverseTitle = reverseRelNode.attribute("title");
                    if (hasTitle) {
                        if (!title.equals(reverseTitle)) continue;
                    } else {
                        if (reverseTitle != null && reverseTitle.length() > 0) continue;
                    }
                    foundReverse = true;
                }
                // NOTE: removed "it."@type" == relType && ", if there is already any relationship coming back don't create the reverse
                if (foundReverse) {
                    // NOTE DEJ 20150314 Just track auto-reverse, not one-reverse
                    // make sure has is-one-reverse="true"
                    // reverseRelNode.attributes().put("is-one-reverse", "true")
                    continue;
                }

                // 跟踪相关实体有其他人指向它的实例，除非原始关系类型很多（不符合条件）
                if (!ed.isViewEntity && !"many".equals(relNode.attribute("type"))) reverseEd.getEntityNode().attributes().put("has-dependents", "true");

                // 创造新的反向关系
                Map<String, String> keyMap = EntityDefinition.getRelationshipExpandedKeyMapInternal(relNode, reverseEd);

                MNode newRelNode = reverseEd.getEntityNode().append("relationship",new ConcurrentHashMap<String,String>(){{
                    put("related",edFullEntityName);
                    put("is-auto-reverse","true");
                    put("imutable","true");
                }});
                if (hasTitle) newRelNode.attributes().put("title", title);
                for (Map.Entry<String, String> keyEntry : keyMap.entrySet()) {
                    // 使用反向字段添加键映射
                    newRelNode.append("key-map", new ConcurrentHashMap<String,String>(){{
                        put("field-name",keyEntry.getValue());
                        put("related",keyEntry.getKey());
                    }});
                }
                relationshipsCreated++;
            }
        }
        // 所有EntityDefinition对象现在都有相反的关系，请记住，这样只会调用新的，而不是缓存
        for (String entityName : entityNameSet) {
            EntityDefinition ed;
            try { ed = getEntityDefinition(entityName); } catch (EntityException e) { if (isTraceEnabled) logger.trace("实体操作跟踪: 实体不存在!", e); continue; }
            if (ed == null) continue;
            ed.setHasReverseRelationships();
        }

        if (logger.isInfoEnabled() && relationshipsCreated > 0) logger.info("实体操作信息: 完成创建 ["+relationshipsCreated+"] 的自动反向关联!");
    }

    // 用于屏幕工具
    public int getEecaRuleCount() {
        int count = 0;
        for (List ruleList : eecaRulesByEntityName.values()) count += ruleList.size();
        return count;
    }

    public void loadEecaRulesAll() {
        int numLoaded = 0;
        int numFiles = 0;
        HashMap<String, EntityEcaRule> ruleByIdMap = new HashMap<>();
        LinkedList<EntityEcaRule> ruleNoIdList = new LinkedList<>();
        // 删除组件化
//        for (String location : this.ecfi.getComponentBaseLocations().values()) {
//            ResourceReference entityDirRr = this.ecfi.resourceFacade.getLocationReference(location + "/entity");
//            if (entityDirRr.supportsAll()) {
//                // if for some weird reason this isn't a directory, skip it
//                if (!entityDirRr.isDirectory()) continue;
//                for (ResourceReference rr : entityDirRr.directoryEntries) {
//                    if (!rr.fileName.endsWith(".eecas.xml")) continue;
//                    numLoaded += loadEecaRulesFile(rr, ruleByIdMap, ruleNoIdList);
//                    numFiles++;
//
//                }
//            } else {
//                logger.warn("Can't load EECA rules from component at ["+entityDirRr.location+"] because it doesn't support exists/directory/etc");
//            }
//        }
        if (logger.isInfoEnabled()) logger.info("实体操作信息: 完成 从 ["+numFiles+"] 个eecas.xml文件中加载 ["+numLoaded+"] 条实体 ECA 规则, ["+ruleNoIdList.size()+"] 个规则没有ID, ["+(ruleNoIdList.size() + ruleByIdMap.size())+"] 个规则启用!");

        HashMap<String, ArrayList<EntityEcaRule>> ruleMap = new HashMap<>();
        ruleNoIdList.addAll(ruleByIdMap.values());
        for (EntityEcaRule ecaRule : ruleNoIdList) {
            EntityDefinition ed = getEntityDefinition(ecaRule.getEntityName());
            String entityName = ed.getFullEntityName();

            ArrayList<EntityEcaRule> lst = ruleMap.computeIfAbsent(entityName, k -> new ArrayList<>());
            lst.add(ecaRule);
        }

        // 在一次操作中替换整个EECA规则Map
        eecaRulesByEntityName = ruleMap;
    }
    public int loadEecaRulesFile(ResourceReference rr, HashMap<String, EntityEcaRule> ruleByIdMap, LinkedList<EntityEcaRule> ruleNoIdList) {
        MNode eecasRoot = MNode.parse(rr);
        int numLoaded = 0;
        if(eecasRoot != null)return numLoaded;
        for (MNode eecaNode : eecasRoot.children("eeca")) {
            String entityName = eecaNode.attribute("entity");
            if (!isEntityDefined(entityName)) {
                logger.warn("实体操作警告: EECA文件 ["+rr.getLocation()+"] 中的实体 ["+entityName+"] 的实体名称错误,跳过操作!");
                continue;
            }
            EntityEcaRule ecaRule = new EntityEcaRule(ecfi, eecaNode, rr.getLocation());
            String ruleId = eecaNode.attribute("id");
            if (ruleId != null && !ruleId.isEmpty()) ruleByIdMap.put(ruleId, ecaRule);
            else ruleNoIdList.add(ecaRule);
            numLoaded++;
        }
        if (logger.isTraceEnabled()) logger.trace("实体操作跟最: 完成从文件 ["+rr.getLocation()+"] 加载 ["+numLoaded+"] 条 ECA 规则!");
        return numLoaded;
    }

    public boolean hasEecaRules(String entityName) { return eecaRulesByEntityName.get(entityName) != null; }
    public void runEecaRules(String entityName, Map fieldValues, String operation, boolean before) {
        ArrayList<EntityEcaRule> lst = eecaRulesByEntityName.get(entityName);
        if (lst != null && lst.size() > 0) {
            // if Entity ECA rules disabled in ArtifactExecutionFacade, just return immediately
            // do this only if there are EECA rules to run, small cost in getEci, etc
//            if (ecfi.getEci().artifactExecutionFacade.entityEcaDisabled()) return

            for (EntityEcaRule entityEcaRule : lst) {
                EntityEcaRule eer = entityEcaRule;
                eer.runIfMatches(entityName, fieldValues, operation, before, ecfi.getEci());
            }
        }
    }

    public void destroy() {
        Set<String> groupNames = this.datasourceFactoryByGroupMap.keySet();
        for (String groupName : groupNames) {
            EntityDatasourceFactory edf = this.datasourceFactoryByGroupMap.get(groupName);
            this.datasourceFactoryByGroupMap.put(groupName, null);
            edf.destroy();
        }
    }

    // 在工具页面中使用
    public void checkAllEntityTables(String groupName) {
        // TODO: 首先加载框架实体，然后加载组件/地幔/等实体，以便在第一次传递时获得更好的FK
        EntityDatasourceFactory edf = getDatasourceFactory(groupName);
        for (String entityName : getAllEntityNamesInGroup(groupName)) edf.checkAndAddTable(entityName);
    }

    public Set<String> getAllEntityNames() { return getAllEntityNames(null); }
    public Set<String> getAllEntityNames(String filterRegexp) {
        Map<String, List<String>> entityLocationCache = entityLocationSingleCache.get(entityLocSingleEntryName);
        if (entityLocationCache == null) entityLocationCache = loadAllEntityLocations();

        TreeSet<String> allNames = new TreeSet<>();
        // 只添加完整的实体名称（包含其中的包，总是至少有一个点）
        // 仅包含缓存中具有非空位置列表的实体（否则为无效实体）
        for (Map.Entry<String, List<String>> entry : entityLocationCache.entrySet()) {
            String en = entry.getKey();
            List<String> locList = entry.getValue();
            if (en.contains(".") && locList != null && locList.size() > 0) {
                // Added (?i) to ignore the case and '*' in the starting and at ending to match if searched string is sub-part of entity name
                if (filterRegexp != null && !en.matches("(?i).*" + filterRegexp + ".*")) continue;
                allNames.add(en);
            }
        }
        return allNames;
    }

    public Set<String> getAllNonViewEntityNames() {
        Set<String> allNames = getAllEntityNames();
        Set<String> nonViewNames = new TreeSet<>();
        for (String name : allNames) {
            EntityDefinition ed = getEntityDefinition(name);
            if (ed != null && !ed.isViewEntity) nonViewNames.add(name);
        }
        return nonViewNames;
    }
    public Set<String> getAllEntityNamesWithMaster() {
        Set<String> allNames = getAllEntityNames();
        Set<String> masterNames = new TreeSet<>();
        for (String name : allNames) {
            EntityDefinition ed;
            try { ed = getEntityDefinition(name); } catch (EntityException e) { if (isTraceEnabled) logger.trace("Entity not found", e); continue; }
            if (ed != null && !ed.isViewEntity && ed.getMasterDefinitionMap() != null && !ed.getMasterDefinitionMap().isEmpty()) masterNames.add(name);
        }
        return masterNames;
    }

    // used in tools screens
    public List<Map> getAllEntityInfo(int levels, boolean excludeViewEntities) {
        Map<String, Map> entityInfoMap = new ConcurrentHashMap<>();
        for (String entityName : getAllEntityNames()) {
            EntityDefinition ed = getEntityDefinition(entityName);
            boolean isView = ed.isViewEntity;
            if (excludeViewEntities && isView) continue;
            int lastDotIndex = 0;
            for (int i = 0; i < levels; i++) lastDotIndex = entityName.indexOf(".", lastDotIndex+1);
            String name = lastDotIndex == -1 ? entityName : entityName.substring(0, lastDotIndex);
            Map curInfo = entityInfoMap.get(name);
            if (curInfo != null && !curInfo.isEmpty()) {
                if (isView) CollectionUtil.addToBigDecimalInMap("viewEntities", BigDecimal.valueOf(1.0), curInfo);
                else CollectionUtil.addToBigDecimalInMap("entities", BigDecimal.valueOf(1.0), curInfo);
            } else {
                entityInfoMap.put(name, new HashMap<String,Object>(){{
                    put("name",name);
                    put("entities",isView ? 0 : 1);
                    put("viewEntities",isView ? 1 : 0);
                }});
            }
        }
        TreeSet<String> nameSet = new TreeSet<>(entityInfoMap.keySet());
        List<Map> entityInfoList = new ArrayList<>();
        for (String name : nameSet) entityInfoList.add(entityInfoMap.get(name));
        return entityInfoList;
    }

    /**
     * 这主要由服务引擎使用，以快速确定名词是否是实体。
     * 调用所有ServiceDefinition init以查看名词是否为实体名称。
     * 如果没有路径和动词是实体自动支持的动词之一，则由实体自动检查调用。
     */
    public boolean isEntityDefined(String entityName) {
        if (entityName == null) return false;

        // 框架实体的特殊处理，map查找（比Cache get快）
        if (frameworkEntityDefinitions.containsKey(entityName)) return true;

        Map<String, List<String>> entityLocationCache = entityLocationSingleCache.get(entityLocSingleEntryName);
        if (entityLocationCache == null) entityLocationCache = loadAllEntityLocations();

        List<String> locList = entityLocationCache.get(entityName);
        return locList != null && locList.size() > 0;
    }

    @Override
    public EntityDefinition getEntityDefinition(String entityName) {
        if (entityName == null) return null;
        EntityDefinition ed = frameworkEntityDefinitions.get(entityName);
        if (ed != null) return ed;
        ed = entityDefinitionCache.get(entityName);
        if (ed != null) return ed;
        if (entityName.isEmpty()) return null;
        if (entityName.startsWith("DataDocument.")) {
            return entityDataDocument.makeEntityDefinition(entityName.substring(entityName.indexOf(".") + 1));
        } else {
            return loadEntityDefinition(entityName);
        }
    }

    // used in tools screens
    public void clearEntityDefinitionFromCache(String entityName) {
        EntityDefinition ed = this.entityDefinitionCache.get(entityName);
        if (ed != null) {
            this.entityDefinitionCache.remove(ed.entityInfo.internalEntityName);
            this.entityDefinitionCache.remove(ed.fullEntityName);
            if (ed.entityInfo.shortAlias != null && !ed.entityInfo.shortAlias.isEmpty()) this.entityDefinitionCache.remove(ed.entityInfo.shortAlias);
        }
    }

    // used in tools screens
    public ArrayList<Map<String, Object>> getAllEntitiesInfo(String orderByField, String filterRegexp, boolean masterEntitiesOnly,
                                                      boolean excludeViewEntities) {
        if (masterEntitiesOnly) createAllAutoReverseManyRelationships();

        ArrayList<Map<String, Object>> eil = new ArrayList<>();
        for (String en : getAllEntityNames(filterRegexp)) {
            EntityDefinition ed = null;
            try { ed = getEntityDefinition(en); } catch (EntityException e) { logger.warn("实体操作警告: 无法找到实体定义!", e); }
            if (ed == null) continue;
            if (excludeViewEntities && ed.isViewEntity) continue;

            if (masterEntitiesOnly) {
                if (!(ed.getEntityNode().attribute("has-dependents").equals("true")) || en.endsWith("Type") ||
                        "zkit.basic.Enumeration".equals(en) || "zkit.basic.StatusItem".equals(en)) continue;
                if (ed.getPkFieldNames().size() > 1) continue;
            }

            EntityDefinition finalEd = ed;
            eil.add(new HashMap<String,Object>(){{
                put("entityName", finalEd.entityInfo.internalEntityName);
                put("package", finalEd.getEntityNode().attribute("package"));
                put("isView", finalEd.isViewEntity ? "true" : "false");
                put("fullEntityName", finalEd.fullEntityName);
            }});
        }

        if (orderByField != null && !orderByField.isEmpty()) CollectionUtil.orderMapList(eil, Collections.singletonList(orderByField));
        return eil;
    }

    // 用于工具屏幕（EntityDbView）
    public ArrayList<Map<String, Object>> getAllEntityRelatedFields(String en, String orderByField, String dbViewEntityName) {
        // 确保存在反向对多关系
        createAllAutoReverseManyRelationships();

        EntityValue dbViewEntity = dbViewEntityName != null && !dbViewEntityName.isEmpty() ? find("zkit.entity.view.DbViewEntity").condition("dbViewEntityName", dbViewEntityName).one() : null;

        ArrayList<Map<String, Object>> efl = new ArrayList<>();
        EntityDefinition ed = null;
        try { ed = getEntityDefinition(en); } catch (EntityException e) { logger.warn("Problem finding entity definition", e); }
        if (ed == null) return efl;

        // 首先获取主要实体的字段
        for (String fn : ed.getAllFieldNames()) {
            MNode fieldNode = ed.getFieldNode(fn);

            boolean inDbView = false;
            String functionName = null;
            EntityValue aliasVal = find("zkit.entity.view.DbViewEntityAlias")
                    .condition(new HashMap<String,Object>(){{
                        put("dbViewEntityName",dbViewEntityName);
                        put("entityAlias","MASTER");
                        put("fieldName","fn");
                    }}).one();
            if (aliasVal != null) {
                inDbView = true;
                functionName = aliasVal.getString("functionName");
            }

            boolean finalInDbView = inDbView;
            String finalFunctionName = functionName;
            efl.add(new HashMap<String,Object>(){{
                put("entityName",en);
                put("fieldName",fn);
                put("type",fieldNode.attribute("type"));
                put("cardinality","one");
                put("inDbView", finalInDbView);
                put("functionName", finalFunctionName);

            }});
        }

        // 循环遍历所有相关实体并获取其字段
        for (RelationshipInfo relInfo : ed.getRelationshipsInfo(false)) {
            //[type:relNode."@type", title:(relNode."@title"?:""), relatedEntityName:relNode."@related-entity-name",
            //        keyMap:keyMap, targetParameterMap:targetParameterMap, prettyName:prettyName]
            EntityDefinition red = null;
            try { red = getEntityDefinition((String) relInfo.relatedEntityName); } catch (EntityException e) { logger.warn("Problem finding entity definition", e); }
            if (red == null) continue;

            EntityValue dbViewEntityMember = null;
            if (dbViewEntity != null) {
                EntityDefinition finalRed = red;
                dbViewEntityMember = find("zkit.entity.view.DbViewEntityMember")
                        .condition(new HashMap<String,Object>(){{
                            put("dbViewEntityName",dbViewEntityName);
                            put("entityName", finalRed.getFullEntityName());
                        }}).one();
            }

            for (String fn : red.getAllFieldNames()) {
                MNode fieldNode = red.getFieldNode(fn);
                boolean inDbView = false;
                String functionName = null;
                if (dbViewEntityMember != null) {
                    EntityValue finalDbViewEntityMember = dbViewEntityMember;
                    EntityValue aliasVal = find("zkit.entity.view.DbViewEntityAlias")
                            .condition(new HashMap<String,Object>(){{
                                put("dbViewEntityName",dbViewEntityName);
                                put("fieldName",fn);
                                put("entityAlias", finalDbViewEntityMember.get("entityAlias"));
                            }}).one();
                    if (aliasVal != null) {
                        inDbView = true;
                        functionName = aliasVal.getString("functionName");
                    }
                }
                boolean finalInDbView = inDbView;
                String finalFunctionName = functionName;
                efl.add(new HashMap<String,Object>(){{
                    put("entityName",relInfo.relatedEntityName);
                    put("fieldName",fn);
                    put("type",fieldNode.attribute("type"));
                    put("cardinality",relInfo.type);
                    put("title",relInfo.title);
                    put("inDbView", finalInDbView);
                    put("functionName", finalFunctionName);
                }});
            }
        }

        if (orderByField != null ) CollectionUtil.orderMapList(efl,Collections.singletonList(orderByField));
        return efl;
    }

    public MNode getDatabaseNode(String groupName) {
        MNode node = databaseNodeByGroupName.get(groupName);
        if (node != null) return node;
        return findDatabaseNode(groupName);
    }
    protected MNode findDatabaseNode(String groupName) {
        MNode datasourceNode = getDatasourceNode(groupName);
        String databaseConfName = datasourceNode.attribute("database-conf-name");
        MNode node = ecfi.getConfXmlRoot().first("database-list")
                .first((MNode it) -> it.getName().equals("database") && it.attribute("name").equals(databaseConfName));
        databaseNodeByGroupName.put(groupName, node);
        return node;
    }
    protected MNode getDatabaseNodeByConf(String confName) {
        return ecfi.getConfXmlRoot().first("database-list")
                .first((MNode it) -> it.getName().equals("database") && it.attribute("name").equals(confName));
    }
    public String getDatabaseConfName(String entityName) {
        MNode dsNode = getDatasourceNode(getEntityGroupName(entityName));
        if (dsNode == null) return null;
        return dsNode.attribute("database-conf-name");
    }

    public MNode getDatasourceNode(String groupName) {
        MNode node = datasourceNodeByGroupName.get(groupName);
        if (node != null) return node;
        return findDatasourceNode(groupName);
    }
    protected MNode findDatasourceNode(String groupName) {
        MNode dsNode = getEntityFacadeNode().first((MNode it) ->it.getName().equals("datasource" ) && it.attribute("group-name").equals(groupName));
        if (dsNode == null) dsNode = getEntityFacadeNode()
                .first((MNode it) ->it.getName().equals("datasource") && it.attribute("group-name").equals(defaultGroupName));
        dsNode.setSystemExpandAttributes(true);
        datasourceNodeByGroupName.put(groupName, dsNode);
        return dsNode;
    }

    public EntityDbMeta getEntityDbMeta() { return dbMeta != null ? dbMeta : (dbMeta = new EntityDbMeta(this));}

    /** Get a JDBC Connection based on xa-properties configuration. The Conf Map should contain the default entity_ds properties
     * including entity_ds_db_conf, entity_ds_host, entity_ds_port, entity_ds_database, entity_ds_user, entity_ds_password */
    public XAConnection getConfConnection(Map<String, String> confMap) {
        String confName = confMap.get("entity_ds_db_conf");
        MNode databaseNode = getDatabaseNodeByConf(confName);
        MNode xaPropsNode = databaseNode.first("inline-jdbc").first("xa-properties");
        if (xaPropsNode == null) throw new IllegalArgumentException("Could not find database.inline-jdbc.xa-properties element for conf name ${confName}");

        String xaDsClassName = databaseNode.attribute("default-xa-ds-class");
        if (xaDsClassName == null) throw new IllegalArgumentException("Could database conf ${confName} has no default-xa-ds-class attribute");
        XADataSource xaDs = null;
        // 按照配置获取链接
        try {
            xaDs = (XADataSource) ecfi.getClassLoader().loadClass(xaDsClassName).newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("Could database conf ${confName} has no default-xa-ds-class attribute");
        }

        for (Map.Entry<String, String> attrEntry : xaPropsNode.attributes().entrySet()) {
            String propValue = ecfi.getResource().expand(attrEntry.getValue(), "", confMap);
            try {
                DefaultGroovyMethods.putAt(xaDs,attrEntry.getKey(),propValue);
            } catch (GroovyCastException e) {
                if (isTraceEnabled) logger.trace("Cast failed, trying int", e);
                DefaultGroovyMethods.putAt(xaDs,attrEntry.getKey(),Integer.valueOf(propValue));
            }
        }

        try {
            return xaDs.getXAConnection(confMap.get("entity_ds_user"), confMap.get("entity_ds_password"));
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }
    // used in services
    public int runSqlUpdateConf(CharSequence sql, Map<String, String> confMap) {
        // only do one DB meta data operation at a time; may lock above before checking for existence of something to make sure it doesn't get created twice
        AtomicInteger records = new AtomicInteger();
        ecfi.getTransaction().runRequireNew(30, "Error in DB meta data change",  ( Boolean it)-> {
            XAConnection xacon = null;
            Connection con = null;
            Statement stmt = null;
            try {
                xacon = getConfConnection(confMap);
                con = xacon.getConnection();
                stmt = con.createStatement();
                records.set(stmt.executeUpdate(sql.toString()));
            } catch (SQLException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (stmt != null) { stmt.close(); }
                    if (con != null) con.close();
                    if (xacon != null) xacon.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            return true;
        });
        return records.get();
    }
    /* this needs more work, can't pass back ResultSet with Connection closed so need to somehow return Connection and ResultSet so both can be closed...
    ResultSet runSqlQueryConf(CharSequence sql, Map<String, String> confMap) {
        Connection con = null
        Statement stmt = null
        ResultSet rs = null
        try {
            con = getConfConnection(confMap)
            stmt = con.createStatement()
            rs = stmt.executeQuery(sql.toString())
        } finally {
            if (stmt != null) stmt.close()
            if (con != null) con.close()
        }
        return rs
    }
    */
    // used in services
    public long runSqlCountConf(CharSequence from, CharSequence where, Map<String, String> confMap) {
        StringBuilder sqlSb = new StringBuilder("SELECT COUNT(*) FROM ").append(from).append(" WHERE ").append(where);
        XAConnection xacon = null;
        Connection con = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            xacon = getConfConnection(confMap);
            try {
                con = xacon.getConnection();
                stmt = con.createStatement();
                rs = stmt.executeQuery(sqlSb.toString());
                if (rs.next()) return rs.getLong(1);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return 0;
        } finally {
            try {
                if (stmt != null) { stmt.close();}
                if (rs != null) rs.close();
                if (con != null) con.close();
                if (xacon != null) xacon.close();

            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /* ========================= */
    /* Interface Implementations */
    /* ========================= */

    @Override
    public EntityDatasourceFactory getDatasourceFactory(String groupName) {
        EntityDatasourceFactory edf = datasourceFactoryByGroupMap.get(groupName);
        if (edf == null) edf = datasourceFactoryByGroupMap.get(defaultGroupName);
        if (edf == null) throw new EntityException("Could not find EntityDatasourceFactory for entity group ${groupName}");
        return edf;
    }
    public List<Map<String, Object>> getDataSourcesInfo() {
        List<Map<String, Object>> dsiList = new LinkedList<>();
        for (String groupName : datasourceFactoryByGroupMap.keySet()) {
            EntityDatasourceFactory edf = datasourceFactoryByGroupMap.get(groupName);
            if (edf instanceof EntityDatasourceFactoryImpl) {
                EntityDatasourceFactoryImpl edfi = (EntityDatasourceFactoryImpl) edf;
                DatasourceInfo dsi = edfi.dsi;
                dsiList.add(new HashMap<String,Object>(){{
                    put("group",groupName);
                    put("uniqueName",dsi.uniqueName);
                    put("database",dsi.database.attribute("name"));
                    put("detail",dsi.dsDetails);
                }});
            } else {
                dsiList.add(Collections.singletonMap("group",groupName));
            }
        }
        return dsiList;
    }

    @Override
    public EntityConditionFactory getConditionFactory() { return this.entityConditionFactory; }
    public EntityConditionFactoryImpl getConditionFactoryImpl() { return this.entityConditionFactory; }

    @Override
    public EntityValue makeValue(String entityName) {
        // don't check entityName empty, getEntityDefinition() does it
        EntityDefinition ed = getEntityDefinition(entityName);
        if (ed == null) throw new EntityException("No entity found with name ${entityName}");
        return ed.makeEntityValue();
    }

    @Override
    public EntityFind find(String entityName) {
        // don't check entityName empty, getEntityDefinition() does it
        EntityDefinition ed = getEntityDefinition(entityName);
        if (ed == null) throw new EntityException("No entity found with name ${entityName}");
        if (ed.isDynamicView && entityName.startsWith("DataDocument.")) {
            // see if it happens to be a DataDocument and if so make a special find that has its conditions too
            // TODO: consider addition condition methods to EntityDynamicView and handling this lower level instead of here
            return entityDataDocument.makeDataDocumentFind(entityName.substring(entityName.indexOf(".") + 1));
        }
        return ed.makeEntityFind();
    }
    @Override
    public EntityFind find(MNode node) {
        String entityName = node.attribute("entity-name");
        if (entityName != null && entityName.contains("${")) entityName = ecfi.getResource().expand(entityName, null);
        // don't check entityName empty, getEntityDefinition() does it
        EntityDefinition ed = getEntityDefinition(entityName);
        if (ed == null) throw new EntityException("No entity found with name ${entityName}");
        EntityFind ef;
        if (ed.isDynamicView && entityName.startsWith("DataDocument.")) {
            // see if it happens to be a DataDocument and if so make a special find that has its conditions too
            // TODO: consider addition condition methods to EntityDynamicView and handling this lower level instead of here
            ef = entityDataDocument.makeDataDocumentFind(entityName.substring(entityName.indexOf(".") + 1));
        } else {
            ef = ed.makeEntityFind();
        }

        String cache = node.attribute("cache");
        if (cache != null && !cache.isEmpty()) { ef.useCache("true".equals(cache)) }
        String forUpdate = node.attribute("for-update");
        if (forUpdate != null && !forUpdate.isEmpty()) ef.forUpdate("true".equals(forUpdate));
        String distinct = node.attribute("distinct");
        if (distinct != null && !distinct.isEmpty()) ef.distinct("true".equals(distinct));
        String offset = node.attribute("offset");
        if (offset != null && !offset.isEmpty()) ef.offset(Integer.valueOf(offset));
        String limit = node.attribute("limit");
        if (limit != null && !limit.isEmpty()) ef.limit(Integer.valueOf(limit));
        for (MNode sf : node.children("select-field")) ef.selectField(sf.attribute("field-name"));
        for (MNode ob : node.children("order-by")) ef.orderBy(ob.attribute("field-name"));

        if (node.hasChild("search-form-inputs")) {
            MNode sfiNode = node.first("search-form-inputs");
            boolean paginate = !"false".equals(sfiNode.attribute("paginate"));
            MNode defaultParametersNode = sfiNode.first("default-parameters");
            String inputFieldsMapName = sfiNode.attribute("input-fields-map");
            Map<String, Object> inf = inputFieldsMapName != null? (Map<String, Object>) ecfi.getResource().expression(inputFieldsMapName, "") : ecfi.getEci().getContext();
            ef.searchFormMap(inf, defaultParametersNode != null ? defaultParametersNode.attributes(), sfiNode.attribute("skip-fields"), sfiNode.attribute("default-order-by"), paginate);
        }

        // logger.warn("=== shouldCache ${this.entityName} ${shouldCache()}, limit=${this.limit}, offset=${this.offset}, useCache=${this.useCache}, getEntityDef().getUseCache()=${this.getEntityDef().getUseCache()}")
        EntityCondition mainCond = getConditionFactoryImpl().makeActionConditions(node, ef.shouldCache());
        if (mainCond != null) ef.condition(mainCond);

        if (node.hasChild("having-econditions")) {
            for (MNode havingCond : node.children("having-econditions"))
            ef.havingCondition(getConditionFactoryImpl().makeActionConditions(havingCond, ef.shouldCache()));
        }

        return ef;
    }

    /** Simple, fast find by primary key; doesn't filter find based on authz; doesn't use TransactionCache
     * For cached queries this is about 50% faster (6M/s vs 4M/s) for non-cached queries only about 10% faster (500K vs 450K) */
    public EntityValue fastFindOne(String entityName, Boolean useCache, boolean disableAuthz, Object... values) {
        ExecutionContextImpl ec = ecfi.getEci();
//        ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade
//        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDefinition(entityName);
            if (ed == null) throw new EntityException("Entity not found with name ${entityName}");
            EntityInfo entityInfo = ed.entityInfo;
            FieldInfo[] pkFieldInfoArray = entityInfo.pkFieldInfoArray;

            if (ed.isViewEntity || !entityInfo.isEntityDatasourceFactoryImpl) {
                if (logger.isInfoEnabled()) logger.info("fastFindOne used with entity ${entityName} which is view entity (${ed.isViewEntity}) or not from EntityDatasourceFactoryImpl (${entityInfo.isEntityDatasourceFactoryImpl})");
                EntityFind ef = find(entityName);
                if (useCache) ef.useCache(true);
                if (disableAuthz) ef.disableAuthz();
                for (int i = 0; i < pkFieldInfoArray.length; i++) {
                    FieldInfo fi = pkFieldInfoArray[i];
                    Object fieldValue = values[i];
                    ef.condition(fi.name, fieldValue);
                }
                return ef.one();
            }

//            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(),
//                    ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_VIEW, "one")
//            // really worth the overhead? if so change to handle singleCondField: .setParameters(simpleAndMap)
//            aefi.pushInternal(aei, !ed.entityInfo.authorizeSkipView, false)

            try {
                boolean doCache = useCache != null ? (useCache.booleanValue() ? !entityInfo.neverCache : false) : "true".equals(entityInfo.useCache);

                boolean hasEmptyPk = false;
                int pkSize = pkFieldInfoArray.length;
                if (values.length != pkSize) throw new EntityException("Cannot do fastFindOne for entity ${entityName} with ${pkSize} primary key fields and ${values.length} values");
                EntityConditionImplBase whereCondition = (EntityConditionImplBase) null;
                if (pkSize == 1) {
                    Object fieldValue = values[0];
                    if (ObjectUtil.isEmpty(fieldValue)) {
                        hasEmptyPk = true;
                    } else if (doCache) {
                        FieldInfo fi = (FieldInfo) pkFieldInfoArray[0];
                        whereCondition = new FieldValueCondition(fi.conditionField, EntityCondition.EQUALS, fieldValue);
                    }
                } else {
                    ListCondition listCond = doCache ? new ListCondition(null, EntityCondition.AND) : (ListCondition) null;
                    for (int i = 0; i < pkSize; i++) {
                        Object fieldValue = values[i];
                        if (ObjectUtil.isEmpty(fieldValue)) {
                            hasEmptyPk = true;
                            break;
                        }
                        if (doCache) {
                            FieldInfo fi = (FieldInfo) pkFieldInfoArray[i];
                            listCond.addCondition(new FieldValueCondition(fi.conditionField, EntityCondition.EQUALS, fieldValue));
                        }
                    }
                    if (doCache) whereCondition = listCond;
                }
                // if any PK fields are null, for whatever reason in calling code, the result is null so no need to send to DB or cache or anything
                if (hasEmptyPk) return (EntityValue) null;

                Cache<EntityCondition, EntityValueBase> entityOneCache = doCache ?
                        ed.getCacheOne(entityCache) : (Cache<EntityCondition, EntityValueBase>) null;
                EntityValueBase cacheHit = doCache ? (EntityValueBase) entityOneCache.get(whereCondition) : (EntityValueBase) null;

                EntityValueBase newEntityValue;
                if (cacheHit != null) {
                    if (cacheHit instanceof EntityCache.EmptyRecord) newEntityValue = (EntityValueBase) null;
                    else newEntityValue = cacheHit;
                } else {
                    newEntityValue = fastFindOneExtended(ed, values);
                    // put it in whether null or not (already know cacheHit is null)
                    if (doCache) entityCache.putInOneCache(ed, whereCondition, newEntityValue, entityOneCache);
                }

                return newEntityValue;
            } finally {
                // pop the ArtifactExecutionInfo
//                aefi.pop(aei)
            }
        } finally {
//            if (enableAuthz) aefi.enableAuthz()
        }
    }
    public EntityValueBase fastFindOneExtended(EntityDefinition ed, Object... values) throws EntityException {
        // table doesn't exist, just return null
        if (!ed.tableExistsDbMetaOnly()) return null;

        FieldInfo[] fieldInfoArray = ed.entityInfo.allFieldInfoArray;
        FieldInfo[] pkFieldInfoArray = ed.entityInfo.pkFieldInfoArray;
        int pkSize = pkFieldInfoArray.length;

        final StringBuilder sqlTopLevel = new StringBuilder(500);
        sqlTopLevel.append("SELECT ").append(ed.entityInfo.allFieldsSqlSelect);

        // FROM Clause
        sqlTopLevel.append(" FROM ");
        sqlTopLevel.append(ed.getFullTableName());

        // WHERE clause; whereCondition will always be FieldValueCondition or ListCondition with FieldValueCondition
        sqlTopLevel.append(" WHERE ");
        for (int i = 0; i < pkSize; i++) {
            FieldInfo fi = (FieldInfo) pkFieldInfoArray[i];
            // Object fieldValue = values[i]
            if (i > 0) sqlTopLevel.append(" AND ");
            sqlTopLevel.append(fi.getFullColumnName()).append(" = ?");
        }

        String finalSql = sqlTopLevel.toString();

        // run the SQL now that it is built
        EntityValueBase newEntityValue = null;
        Connection connection = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            connection = getConnection(ed.getEntityGroupName());
            ps = connection.prepareStatement(finalSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            for (int i = 0; i < pkSize; i++) {
                FieldInfo fi = pkFieldInfoArray[i];
                Object fieldValue = values[i];
                fi.setPreparedStatementValue(ps, i + 1, fieldValue, ed, this);
            }

            long beforeQuery = this.queryStats ? System.nanoTime() : 0;
            rs = ps.executeQuery();
            if (this.queryStats) saveQueryStats(ed, finalSql, System.nanoTime() - beforeQuery, false);

            if (rs.next()) {
                newEntityValue = new EntityValueImpl(ed, this);
                HashMap<String, Object> valueMap = newEntityValue.getValueMap();
                int size = fieldInfoArray.length;
                for (int i = 0; i < size; i++) {
                    FieldInfo fi = fieldInfoArray[i];
                    if (fi == null) break;
                    fi.getResultSetValue(rs, i + 1, valueMap, this);
                }
            }
        } catch (SQLException e) {
            throw new EntityException("Error finding value", e);
        } finally {
            try {
                if (ps != null) ps.close();
                if (rs != null) rs.close();
                if (connection != null) connection.close();
            } catch (SQLException sqle) { throw new EntityException("Error finding value", sqle); }
        }

        return newEntityValue;
    }

    public final static Map<String, String> operationByMethod = new ConcurrentHashMap<String,String>(){{

    }}[get:'find', post:'create', put:'store', patch:'update', delete:'delete']
    @Override
    public Object rest(String operation, List<String> entityPath, Map parameters, boolean masterNameInPath) {
        if (operation == null || operation.length() == 0) throw new EntityException("Operation (method) must be specified");
        operation = operationByMethod.get(operation.toLowerCase()) != null ? operationByMethod.get(operation.toLowerCase()): operation;
        if (!(operation : ['find', 'create', 'store', 'update', 'delete']))
        throw new EntityException("Operation [${operation}] not supported, must be one of: get, post, put, patch, or delete for HTTP request methods or find, create, store, update, or delete for direct entity operations");

        if (entityPath == null || entityPath.size() == 0) throw new EntityException("No entity name or alias specified in path");

        boolean dependents = (parameters.dependents == 'true' || parameters.dependents == 'Y')
        int dependentLevels = (parameters.dependentLevels ?: (dependents ? '2' : '0')) as int
        String masterName = parameters.master

        List<String> localPath = new ArrayList<String>(entityPath)

        String firstEntityName = localPath.remove(0)
        EntityDefinition firstEd = getEntityDefinition(firstEntityName)
        // this exception will be thrown at lower levels, but just in case check it again here
        if (firstEd == null) throw new EntityNotFoundException("No entity found with name or alias [${firstEntityName}]")

        // look for a master definition name as the next path element
        if (masterNameInPath) {
            if (masterName == null || masterName.length() == 0) {
                if (localPath.size() > 0 && firstEd.getMasterDefinition(localPath.get(0)) != null) {
                    masterName = localPath.remove(0)
                } else {
                    masterName = "default"
                }
            }
            if (firstEd.getMasterDefinition(masterName) == null)
                throw new EntityException("Master definition not found for entity [${firstEd.getFullEntityName()}], tried master name [${masterName}]")
        }

        // if there are more path elements use one for each PK field of the entity
        if (localPath.size() > 0) {
            for (String pkFieldName in firstEd.getPkFieldNames()) {
                String pkValue = localPath.remove(0)
                if (!ObjectUtilities.isEmpty(pkValue)) parameters.put(pkFieldName, pkValue)
                if (localPath.size() == 0) break
            }
        }

        EntityDefinition lastEd = firstEd

        // if there is still more in the path the next should be a relationship name or alias
        while (localPath) {
            String relationshipName = localPath.remove(0)
            RelationshipInfo relInfo = lastEd.getRelationshipInfoMap().get(relationshipName)
            if (relInfo == null) throw new EntityNotFoundException("No relationship found with name or alias [${relationshipName}] on entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}]")

            String relEntityName = relInfo.relatedEntityName
            EntityDefinition relEd = relInfo.relatedEd
            if (relEd == null) throw new EntityNotFoundException("No entity found with name [${relEntityName}], related to entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}] by relationship [${relationshipName}]")

            // TODO: How to handle more exotic relationships where they are not a dependent record, ie join on a field
            // TODO:     other than a PK field? Should we lookup interim records to get field values to lookup the final
            // TODO:     one? This would assume that all records exist along the path... need any variation for different
            // TODO:     operations?

            // if there are more path elements use one for each PK field of the entity
            if (localPath.size() > 0) {
                for (String pkFieldName in relEd.getPkFieldNames()) {
                    // do we already have a value for this PK field? if so skip it...
                    if (parameters.containsKey(pkFieldName)) continue

                    String pkValue = localPath.remove(0)
                    if (!ObjectUtilities.isEmpty(pkValue)) parameters.put(pkFieldName, pkValue)
                    if (localPath.size() == 0) break
                }
            }

            lastEd = relEd
        }

        // at this point we should have the entity we actually want to operate on, and all PK field values from the path
        if (operation == 'find') {
            if (lastEd.containsPrimaryKey(parameters)) {
                // if we have a full PK lookup by PK and return the single value
                Map pkValues = [:]
                lastEd.entityInfo.setFields(parameters, pkValues, false, null, true)

                if (masterName != null && masterName.length() > 0) {
                    Map resultMap = find(lastEd.getFullEntityName()).condition(pkValues).oneMaster(masterName)
                    if (resultMap == null) throw new EntityValueNotFoundException("No value found for entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}] with key ${pkValues}")
                    return resultMap
                } else {
                    EntityValueBase evb = (EntityValueBase) find(lastEd.getFullEntityName()).condition(pkValues).one()
                    if (evb == null) throw new EntityValueNotFoundException("No value found for entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}] with key ${pkValues}")
                    Map resultMap = evb.getPlainValueMap(dependentLevels)
                    return resultMap
                }
            } else {
                // otherwise do a list find
                EntityFind ef = find(lastEd.fullEntityName).searchFormMap(parameters, null, null, null, false)
                // we don't want to go overboard with these requests, never do an unlimited find, if no limit use 100
                if (!ef.getLimit()) ef.limit(100)

                // support pagination, at least "X-Total-Count" header if find is paginated
                long count = ef.count()
                long pageIndex = ef.getPageIndex()
                long pageSize = ef.getPageSize()
                long pageMaxIndex = ((count - 1) as BigDecimal).divide(pageSize as java.math.BigDecimal, 0, java.math.BigDecimal.ROUND_DOWN).longValue()
                long pageRangeLow = pageIndex * pageSize + 1
                long pageRangeHigh = (pageIndex * pageSize) + pageSize
                if (pageRangeHigh > count) pageRangeHigh = count

                parameters.put('xTotalCount', count)
                parameters.put('xPageIndex', pageIndex)
                parameters.put('xPageSize', pageSize)
                parameters.put('xPageMaxIndex', pageMaxIndex)
                parameters.put('xPageRangeLow', pageRangeLow)
                parameters.put('xPageRangeHigh', pageRangeHigh)

                if (masterName != null && masterName.length() > 0) {
                    List resultList = ef.listMaster(masterName)
                    return resultList
                } else {
                    EntityList el = ef.list()
                    List resultList = el.getPlainValueList(dependentLevels)
                    return resultList
                }
            }
        } else {
            // use the entity auto service runner for other operations (create, store, update, delete)
            Map result = ecfi.serviceFacade.sync().name(operation, lastEd.fullEntityName).parameters(parameters).call()
            return result
        }
    }

    public EntityList getValueListFromPlainMap(Map value, String entityName) {
        if (entityName == null || entityName.length() == 0) entityName = value."_entity"
        if (entityName == null || entityName.length() == 0) throw new EntityException("No entityName passed and no _entity field in value Map")

        EntityDefinition ed = getEntityDefinition(entityName)
        if (ed == null) throw new EntityNotFoundException("Not entity found with name ${entityName}")

        EntityList valueList = new EntityListImpl(this)
        addValuesFromPlainMapRecursive(ed, value, valueList, null)
        return valueList
    }
    public void addValuesFromPlainMapRecursive(EntityDefinition ed, Map value, EntityList valueList, Map<String, Object> parentPks) {
        // add in all of the main entity's primary key fields, this is necessary for auto-generated, and to
        //     allow them to be left out of related records
        if (parentPks != null) {
            for (Map.Entry<String, Object> entry in parentPks.entrySet())
            if (!value.containsKey(entry.key)) value.put(entry.key, entry.value)
        }

        EntityValue newEntityValue = makeValue(ed.getFullEntityName())
        newEntityValue.setFields(value, true, null, null)
        valueList.add(newEntityValue)

        Map<String, Object> sharedPkMap = newEntityValue.getPrimaryKeys()
        if (parentPks != null) {
            for (Map.Entry<String, Object> entry in parentPks.entrySet())
            if (!sharedPkMap.containsKey(entry.key)) sharedPkMap.put(entry.key, entry.value)
        }

        // check parameters Map for relationships and other entities
        Map nonFieldEntries = ed.entityInfo.cloneMapRemoveFields(value, null)
        for (Map.Entry entry in nonFieldEntries.entrySet()) {
            Object relParmObj = entry.getValue()
            if (relParmObj == null) continue
            // if the entry is not a Map or List ignore it, we're only looking for those
            if (!(relParmObj instanceof Map) && !(relParmObj instanceof List)) continue

            String entryName = (String) entry.getKey()
            if (parentPks != null && parentPks.containsKey(entryName)) continue
            if (EntityAutoServiceRunner.otherFieldsToSkip.contains(entryName)) continue

            EntityDefinition subEd = null
            Map<String, Object> pkMap = null
            RelationshipInfo relInfo = ed.getRelationshipInfo(entryName)
            if (relInfo != null) {
                if (!relInfo.mutable) continue
                subEd = relInfo.relatedEd
                        // this is a relationship so add mapped key fields to the parentPks if any field names are different
                        pkMap = new HashMap<>(sharedPkMap)
                pkMap.putAll(relInfo.getTargetParameterMap(sharedPkMap))
            } else if (isEntityDefined(entryName)) {
                subEd = getEntityDefinition(entryName)
                pkMap = sharedPkMap
            }
            if (subEd == null) continue

            boolean isEntityValue = relParmObj instanceof EntityValue
            if (relParmObj instanceof Map && !isEntityValue) {
                addValuesFromPlainMapRecursive(subEd, (Map) relParmObj, valueList, pkMap)
            } else if (relParmObj instanceof List) {
                for (Object relParmEntry in relParmObj) {
                    if (relParmEntry instanceof Map) {
                        addValuesFromPlainMapRecursive(subEd, (Map) relParmEntry, valueList, pkMap)
                    } else {
                        logger.warn("In entity values from plain map for entity ${ed.getFullEntityName()} found list for sub-object ${entryName} with a non-Map entry: ${relParmEntry}")
                    }
                }
            } else {
                if (isEntityValue) {
                    if (logger.isTraceEnabled()) logger.trace("In entity values from plain map for entity ${ed.getFullEntityName()} found sub-object ${entryName} which is not a Map or List: ${relParmObj}")
                } else {
                    logger.warn("In entity values from plain map for entity ${ed.getFullEntityName()} found sub-object ${entryName} which is not a Map or List: ${relParmObj}")
                }
            }
        }
    }


    @Override
    public EntityListIterator sqlFind(String sql, List<Object> sqlParameterList, String entityName, List<String> fieldList) {
        if (sqlParameterList == null || fieldList == null || sqlParameterList.size() != fieldList.size())
            throw new BaseArtifactException("For sqlFind sqlParameterList and fieldList must not be null and must be the same size")
        EntityDefinition ed = this.getEntityDefinition(entityName)
        this.entityDbMeta.checkTableRuntime(ed)

        Connection con = getConnection(getEntityGroupName(entityName))
        PreparedStatement ps
        try {
            FieldInfo[] fiArray = new FieldInfo[fieldList.size()]
            int fiArrayIndex = 0
            for (String fieldName in fieldList) {
                FieldInfo fi = ed.getFieldInfo(fieldName)
                if (fi == null) throw new BaseArtifactException("Field ${fieldName} not found for entity ${entityName}")
                fiArray[fiArrayIndex] = fi
                fiArrayIndex++
            }

            // create the PreparedStatement
            ps = con.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            // set the parameter values
            int paramIndex = 1
            for (Object parameterValue in sqlParameterList) {
                FieldInfo fi = (FieldInfo) fiArray[paramIndex - 1]
                fi.setPreparedStatementValue(ps, paramIndex, parameterValue, ed, this)
                paramIndex++
            }
            // do the actual query
            long timeBefore = System.currentTimeMillis()
            ResultSet rs = ps.executeQuery()
            if (logger.traceEnabled) logger.trace("Executed query with SQL [${sql}] and parameters [${sqlParameterList}] in [${(System.currentTimeMillis()-timeBefore)/1000}] seconds")
            // make and return the eli
            EntityListIterator eli = new EntityListIteratorImpl(con, rs, ed, fiArray, this, null, null, null)
            return eli
        } catch (SQLException e) {
            throw new EntityException("SQL Exception with statement:" + sql + "; " + e.toString(), e)
        }
    }

    @Override
    public ArrayList<Map> getDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp,
                                    Timestamp thruUpdatedStamp) {
        return entityDataDocument.getDataDocuments(dataDocumentId, condition, fromUpdateStamp, thruUpdatedStamp)
    }

    @Override
    public ArrayList<Map> getDataFeedDocuments(String dataFeedId, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp) {
        return entityDataFeed.getFeedDocuments(dataFeedId, fromUpdateStamp, thruUpdatedStamp)
    }

    public void tempSetSequencedIdPrimary(String seqName, long nextSeqNum, long bankSize) {
        long[] bank = new long[2]
        bank[0] = nextSeqNum
        bank[1] = nextSeqNum + bankSize
        entitySequenceBankCache.put(seqName, bank)
    }
    public void tempResetSequencedIdPrimary(String seqName) {
        entitySequenceBankCache.put(seqName, null)
    }

    @Override
    public String sequencedIdPrimary(String seqName, Long staggerMax, Long bankSize) {
        try {
            // is the seqName an entityName?
            if (isEntityDefined(seqName)) {
                EntityDefinition ed = getEntityDefinition(seqName)
                if (ed.entityInfo.sequencePrimaryUseUuid) return UUID.randomUUID().toString()
            }
        } catch (EntityException e) {
            // do nothing, just means seqName is not an entity name
            if (isTraceEnabled) logger.trace("Ignoring exception for entity not found: ${e.toString()}")
        }
        // fall through to default to the db sequenced ID
        long staggerMaxPrim = staggerMax != null ? staggerMax.longValue() : 0L
        long bankSizePrim = (bankSize != null && bankSize.longValue() > 0) ? bankSize.longValue() : defaultBankSize
        return dbSequencedIdPrimary(seqName, staggerMaxPrim, bankSizePrim)
    }

    public String sequencedIdPrimaryEd(EntityDefinition ed) {
        EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo
        try {
            // is the seqName an entityName?
            if (entityInfo.sequencePrimaryUseUuid) return UUID.randomUUID().toString()
        } catch (EntityException e) {
            // do nothing, just means seqName is not an entity name
            if (isTraceEnabled) logger.trace("Ignoring exception for entity not found: ${e.toString()}")
        }
        // fall through to default to the db sequenced ID
        return dbSequencedIdPrimary(ed.getFullEntityName(), entityInfo.sequencePrimaryStagger, entityInfo.sequenceBankSize)
    }

    public final static long defaultBankSize = 50L;
    protected Lock getDbSequenceLock(String seqName) {
        Lock oldLock, dbSequenceLock = dbSequenceLocks.get(seqName);
        if (dbSequenceLock == null) {
            dbSequenceLock = new ReentrantLock();
            oldLock = dbSequenceLocks.putIfAbsent(seqName, dbSequenceLock);
            if (oldLock != null) return oldLock;
        }
        return dbSequenceLock;
    }
    protected String dbSequencedIdPrimary(String seqName, long staggerMax, long bankSize) {

        // TODO: find some way to get this running non-synchronized for performance reasons (right now if not
        // TODO:     synchronized the forUpdate won't help if the record doesn't exist yet, causing errors in high
        // TODO:     traffic creates; is it creates only?)

        Lock dbSequenceLock = getDbSequenceLock(seqName)
        dbSequenceLock.lock()

        // NOTE: simple approach with forUpdate, not using the update/select "ethernet" approach used in OFBiz; consider
        // that in the future if there are issues with this approach

        try {
            // first get a bank if we don't have one already
            long[] bank = (long[]) entitySequenceBankCache.get(seqName)
            if (bank == null || bank[0] > bank[1]) {
                if (bank == null) {
                    bank = new long[2]
                    bank[0] = 0
                    bank[1] = -1
                    entitySequenceBankCache.put(seqName, bank)
                }

                ecfi.transactionFacade.runRequireNew(null, "Error getting primary sequenced ID", true, true, {
                        ArtifactExecutionFacadeImpl aefi = ecfi.getEci().artifactExecutionFacade
                boolean enableAuthz = !aefi.disableAuthz()
                try {
                    EntityValue svi = find("zkit.entity.SequenceValueItem").condition("seqName", seqName)
                            .useCache(false).forUpdate(true).one()
                    if (svi == null) {
                        svi = makeValue("zkit.entity.SequenceValueItem")
                        svi.set("seqName", seqName)
                        // a new tradition: start sequenced values at one hundred thousand instead of ten thousand
                        bank[0] = 100000L
                        bank[1] = bank[0] + bankSize
                        svi.set("seqNum", bank[1])
                        svi.create()
                    } else {
                        Long lastSeqNum = svi.getLong("seqNum")
                        bank[0] = (lastSeqNum > bank[0] ? lastSeqNum + 1L : bank[0])
                        bank[1] = bank[0] + bankSize
                        svi.set("seqNum", bank[1])
                        svi.update()
                    }
                } finally {
                    if (enableAuthz) aefi.enableAuthz()
                }
                })
            }

            long seqNum = bank[0]
            if (staggerMax > 1L) {
                long stagger = Math.round(Math.random() * staggerMax)
                bank[0] = seqNum + stagger
                // NOTE: if bank[0] > bank[1] because of this just leave it and the next time we try to get a sequence
                //     value we'll get one from a new bank
            } else {
                bank[0] = seqNum + 1L
            }

            return sequencedIdPrefix != null ? sequencedIdPrefix + seqNum : seqNum
        } finally {
            dbSequenceLock.unlock()
        }
    }

    public Set<String> getAllEntityNamesInGroup(String groupName) {
        Set<String> groupEntityNames = new TreeSet<String>()
        for (String entityName in getAllEntityNames()) {
            // use the entity/group cache handled by getEntityGroupName()
            if (getEntityGroupName(entityName) == groupName) groupEntityNames.add(entityName)
        }
        return groupEntityNames
    }

    @Override
    public String getEntityGroupName(String entityName) {
        String entityGroupName = (String) entityGroupNameMap.get(entityName)
        if (entityGroupName != null) return entityGroupName
        EntityDefinition ed
        // for entity group name just ignore EntityException on getEntityDefinition
        try { ed = getEntityDefinition(entityName) } catch (EntityException e) { return null }
        // may happen if all entity names includes a DB view entity or other that doesn't really exist
        if (ed == null) return null
        entityGroupName = ed.getEntityGroupName()
        entityGroupNameMap.put(entityName, entityGroupName)
        return entityGroupName
    }

    @Override
    public Connection getConnection(String groupName) {
        TransactionFacadeImpl tfi = ecfi.transactionFacade
        if (!tfi.isTransactionOperable()) throw new EntityException("Cannot get connection, transaction not in operable status (${tfi.getStatusString()})")
        Connection stashed = tfi.getTxConnection(groupName)
        if (stashed != null) return stashed

        EntityDatasourceFactory edf = getDatasourceFactory(groupName)
        DataSource ds = edf.getDataSource()
        if (ds == null) throw new EntityException("Cannot get JDBC Connection for group-name [${groupName}] because it has no DataSource")
        Connection newCon
        if (ds instanceof XADataSource) {
            newCon = tfi.enlistConnection(((XADataSource) ds).getXAConnection())
        } else {
            newCon = ds.getConnection()
        }
        if (newCon != null) newCon = tfi.stashTxConnection(groupName, newCon)
        return newCon
    }

    @Override
    public EntityDataLoader makeDataLoader() { return new EntityDataLoaderImpl(this) }
    @Override
    public EntityDataWriter makeDataWriter() { return new EntityDataWriterImpl(this) }

    @Override
    public SimpleEtl.Loader makeEtlLoader() { return new EtlLoader(this) }
    public static class EtlLoader implements SimpleEtl.Loader {
        private boolean beganTransaction = false
        private EntityFacadeImpl efi
        private boolean useTryInsert = false, dummyFks = false
        EtlLoader(EntityFacadeImpl efi) { this.efi = efi }
        EtlLoader useTryInsert() { useTryInsert = true; return this }
        EtlLoader dummyFks() { dummyFks = true; return this }

        @Override
        public void init(Integer timeout) {
            if (!efi.ecfi.transactionFacade.isTransactionActive()) beganTransaction = efi.ecfi.transactionFacade.begin(timeout)
        }
        @Override
        public void load(SimpleEtl.Entry entry) throws Exception {
            String entityName = entry.getEtlType()
            if (!efi.isEntityDefined(entityName)) {
                logger.info("Tried to load ETL entry with invalid entity name " + entityName)
                return
            }
            EntityDefinition ed = efi.getEntityDefinition(entityName)
            if (ed == null) throw new BaseArtifactException("Could not find entity ${entityName}")
            // NOTE: the following uses the same pattern as EntityDataLoaderImpl.LoadValueHandler
            if (dummyFks || useTryInsert) {
                EntityValue curValue = ed.makeEntityValue()
                curValue.setAll(entry.getEtlValues())
                if (useTryInsert) {
                    try {
                        curValue.create()
                    } catch (EntityException ce) {
                        if (logger.isTraceEnabled()) logger.trace("Insert failed, trying update (${ce.toString()})")
                        boolean noFksMissing = true
                        if (dummyFks) noFksMissing = curValue.checkFks(true)
                        // retry, then if this fails we have a real error so let the exception fall through
                        // if there were no FKs missing then just do an update, if there were that may have been the error so createOrUpdate
                        if (noFksMissing) {
                            try {
                                curValue.update()
                            } catch (EntityException ue) {
                                logger.error("Error in update after attempt to create (tryInsert), here is the create error: ", ce)
                                throw ue
                            }
                        } else {
                            curValue.createOrUpdate()
                        }
                    }
                } else {
                    if (dummyFks) curValue.checkFks(true)
                    curValue.createOrUpdate()
                }
            } else {
                Map<String, Object> results = new HashMap()
                EntityAutoServiceRunner.storeEntity(efi.ecfi.getEci(), ed, entry.getEtlValues(), results, null)
                if (results.size() > 0) entry.getEtlValues().putAll(results)
            }
        }
        @Override
        public void complete(SimpleEtl etl) {
            if (etl.hasError()) {
                efi.ecfi.transactionFacade.rollback(beganTransaction, "Error in ETL load", etl.getSingleErrorCause())
            } else if (beganTransaction) {
                efi.ecfi.transactionFacade.commit()
            }
        }
    }

    @Override
    public EntityValue makeValue(Element element) {
        if (!element) return null

        String entityName = element.getTagName()
        if (entityName.indexOf('-') > 0) entityName = entityName.substring(entityName.indexOf('-') + 1)
        if (entityName.indexOf(':') > 0) entityName = entityName.substring(entityName.indexOf(':') + 1)

        EntityValueImpl newValue = (EntityValueImpl) makeValue(entityName)
        EntityDefinition ed = newValue.getEntityDefinition()

        for (String fieldName in ed.getAllFieldNames()) {
            String attrValue = element.getAttribute(fieldName)
            if (attrValue) {
                newValue.setString(fieldName, attrValue)
            } else {
                org.w3c.dom.NodeList seList = element.getElementsByTagName(fieldName)
                Element subElement = seList.getLength() > 0 ? (Element) seList.item(0) : null
                if (subElement) newValue.setString(fieldName, StringUtilities.elementValue(subElement))
            }
        }

        return newValue
    }

    /* =============== */
    /* Utility Methods */
    /* =============== */

    protected Map<String, Map<String, String>> javaTypeByGroup = [:]
    public String getFieldJavaType(String fieldType, EntityDefinition ed) {
        String groupName = ed.getEntityGroupName()
        Map<String, String> javaTypeMap = javaTypeByGroup.get(groupName)
        if (javaTypeMap != null) {
            String ft = javaTypeMap.get(fieldType)
            if (ft != null) return ft
        }
        return getFieldJavaTypeFromDbNode(groupName, fieldType, ed)
    }
    protected getFieldJavaTypeFromDbNode(String groupName, String fieldType, EntityDefinition ed) {
        Map<String, String> javaTypeMap = javaTypeByGroup.get(groupName)
        if (javaTypeMap == null) {
            javaTypeMap = new HashMap()
            javaTypeByGroup.put(groupName, javaTypeMap)
        }

        MNode databaseNode = this.getDatabaseNode(groupName)
        MNode databaseTypeNode = databaseNode ?
                databaseNode.first({ MNode it -> it.name == "database-type" && it.attribute('type') == fieldType }) : null
        String javaType = databaseTypeNode?.attribute("java-type")
        if (!javaType) {
            MNode databaseListNode = ecfi.confXmlRoot.first("database-list")
            MNode dictionaryTypeNode = databaseListNode.first({ MNode it -> it.name == "dictionary-type" && it.attribute('type') == fieldType })
            javaType = dictionaryTypeNode?.attribute("java-type")
            if (!javaType) throw new EntityException("Could not find Java type for field type [${fieldType}] on entity [${ed.getFullEntityName()}]")
        }
        javaTypeMap.put(fieldType, javaType)
        return javaType
    }

    protected Map<String, Map<String, String>> sqlTypeByGroup = [:]
    protected String getFieldSqlType(String fieldType, EntityDefinition ed) {
        String groupName = ed.getEntityGroupName()
        Map<String, String> sqlTypeMap = (Map<String, String>) sqlTypeByGroup.get(groupName)
        if (sqlTypeMap != null) {
            String st = (String) sqlTypeMap.get(fieldType)
            if (st != null) return st
        }
        return getFieldSqlTypeFromDbNode(groupName, fieldType, ed)
    }
    protected getFieldSqlTypeFromDbNode(String groupName, String fieldType, EntityDefinition ed) {
        Map<String, String> sqlTypeMap = sqlTypeByGroup.get(groupName)
        if (sqlTypeMap == null) {
            sqlTypeMap = new HashMap()
            sqlTypeByGroup.put(groupName, sqlTypeMap)
        }

        MNode databaseNode = this.getDatabaseNode(groupName)
        MNode databaseTypeNode = databaseNode ?
                databaseNode.first({ MNode it -> it.name == "database-type" && it.attribute('type') == fieldType }) : null
        String sqlType = databaseTypeNode?.attribute("sql-type")
        if (!sqlType) {
            MNode databaseListNode = ecfi.confXmlRoot.first("database-list")
            MNode dictionaryTypeNode = databaseListNode
                    .first({ MNode it -> it.name == "dictionary-type" && it.attribute('type') == fieldType })
            sqlType = dictionaryTypeNode?.attribute("default-sql-type")
            if (!sqlType) throw new EntityException("Could not find SQL type for field type [${fieldType}] on entity [${ed.getFullEntityName()}]")
        }
        sqlTypeMap.put(fieldType, sqlType)
        return sqlType

    }

    /** For pretty-print of field values based on field type */
    public String formatFieldString(String entityName, String fieldName, String value) {
        if (value == null || value.isEmpty()) return "";
        EntityDefinition ed = getEntityDefinition(entityName);
        if (ed == null) return value;
        FieldInfo fi = ed.getFieldInfo(fieldName);
        if (fi == null) return value;
        String outVal = value;
        if (fi.typeValue == 2) {
            if (value.matches("\\d*")) {
                // date-time with only digits, ms since epoch value
                outVal = ecfi.getL10n().format(new Timestamp(Long.parseLong(value)), null);
            }
        } else if (fi.type.startsWith("currency-")) {
            outVal = ecfi.getL10n().format(new BigDecimal(value), "#,##0.00#");
        }
        // logger.warn("formatFieldString ${entityName}:${fieldName} value ${value} outVal ${outVal}")
        return outVal;
    }

    protected static final Map<String, Integer> fieldTypeIntMap = [
            "id":1, "id-long":1, "text-indicator":1, "text-short":1, "text-medium":1, "text-long":1, "text-very-long":1,
            "date-time":2, "time":3, "date":4,
            "number-integer":6, "number-float":8,
            "number-decimal":9, "currency-amount":9, "currency-precise":9,
            "binary-very-long":12 ]
    protected static final Map<String, String> fieldTypeJavaMap = [
            "id":"java.lang.String", "id-long":"java.lang.String",
            "text-indicator":"java.lang.String", "text-short":"java.lang.String", "text-medium":"java.lang.String",
            "text-long":"java.lang.String", "text-very-long":"java.lang.String",
            "date-time":"java.sql.Timestamp", "time":"java.sql.Time", "date":"java.sql.Date",
            "number-integer":"java.lang.Long", "number-float":"java.lang.Double",
            "number-decimal":"java.math.BigDecimal", "currency-amount":"java.math.BigDecimal", "currency-precise":"java.math.BigDecimal",
            "binary-very-long":"java.sql.Blob" ]
    protected static final Map<String, Integer> javaIntTypeMap = [
            "java.lang.String":1, "String":1, "org.codehaus.groovy.runtime.GStringImpl":1, "char[]":1,
            "java.sql.Timestamp":2, "Timestamp":2,
            "java.sql.Time":3, "Time":3,
            "java.sql.Date":4, "Date":4,
            "java.lang.Integer":5, "Integer":5,
            "java.lang.Long":6,"Long":6,
            "java.lang.Float":7, "Float":7,
            "java.lang.Double":8, "Double":8,
            "java.math.BigDecimal":9, "BigDecimal":9,
            "java.lang.Boolean":10, "Boolean":10,
            "java.lang.Object":11, "Object":11,
            "java.sql.Blob":12, "Blob":12, "byte[]":12, "java.nio.ByteBuffer":12, "java.nio.HeapByteBuffer":12,
            "java.sql.Clob":13, "Clob":13,
            "java.util.Date":14,
            "java.util.ArrayList":15, "java.util.HashSet":15, "java.util.LinkedHashSet":15, "java.util.LinkedList":15]
    public static int getJavaTypeInt(String javaType) {
        Integer typeInt = (Integer) javaIntTypeMap.get(javaType);
        if (typeInt == null) throw new EntityException("Java type " + javaType + " not supported for entity fields");
        return typeInt;
    }

    public final Map<String, EntityJavaUtil.QueryStatsInfo> queryStatsInfoMap = new HashMap<>();
    public void saveQueryStats(EntityDefinition ed, String sql, long queryTime, boolean isError) {
        EntityJavaUtil.QueryStatsInfo qsi = queryStatsInfoMap.get(sql);
        if (qsi == null) {
            qsi = new EntityJavaUtil.QueryStatsInfo(ed.getFullEntityName(), sql);
            queryStatsInfoMap.put(sql, qsi);
        }
        qsi.countHit(this, queryTime, isError);
    }
    public boolean isQueryStats() {
        return queryStats;
    }
    public ArrayList<Map<String, Object>> getQueryStatsList(String orderByField, String entityFilter, String sqlFilter) {
        ArrayList<Map<String, Object>> qsl = new ArrayList<>(queryStatsInfoMap.size());
        boolean hasEntityFilter = entityFilter != null && entityFilter.length() > 0;
        boolean hasSqlFilter = sqlFilter != null && sqlFilter.length() > 0;
        for (EntityJavaUtil.QueryStatsInfo qsi : queryStatsInfoMap.values()) {
            if (hasEntityFilter && !qsi.getEntityName().matches("(?i).*" + entityFilter + ".*")) continue;
            if (hasSqlFilter && !qsi.getSql().matches("(?i).*" + sqlFilter + ".*")) continue;
            qsl.add(qsi.makeDisplayMap());
        }
        if (orderByField != null) CollectionUtil.orderMapList(qsl, [orderByField]);
        return qsl;
    }
    public void clearQueryStats() { queryStatsInfoMap.clear(); }
}
