## Major changes  

### 1. Added TenantDefaultData.xml
Contains seed data of "DEFAULT" tenant.

### 2. EntityEntities.xml
Added field targetTenantId 

### 3. ScreenEntities.xml
1. Added field tenantsAllowed in SubscreensItem Entity , to control which tenants are allowed to access this screen.
2. Added field tenantId in SubscreensDefault Entity
3. Added one-nofk relationship to moqui.tenant.Tenant entity.

### 4. Added tenantId in ServiceEntities.xml

### 5. Added TenantEntities.xml
Contains entities to store tenant specific data - Tenant, TenantDataSource, TenantDataSourceXaProp, TenantHostDefault 

### 6.  Modified field in EntitySyncServices
Added authTenantId in remoteInMap field in "internalRun" service

### 7. Modified field in SystemMessageRemote
Added authTenantId in field inMap in SystemMessageJsonRpc#send service

### 8. Added TenantServices.xml
Services to operate on tenant - disable, enable, create, provision, setup, create admin,  provision databases.

### 9. Modified UserServices.xml
Added field tenantId and called changeTenant method when changes to user are made.

### 10. CacheFacadeImpl.groovy
Added shared cache map for tenants
Added and modified methods to use cache with tenants - getFullName, isTenantsShare, getCacheInternal, getAllCachesInfo, initCache, createCache

### 11. ContextJavaUtil.java
Added tenant information to ArtifactHit and Connection Wrapper

### 12. ExecutionContextFactoryImpl.groovy
1. Added maps entityFacadeByTenant, deferredHitInfoQueueByTenant, 
2. Add and Initialize defaultEntityFacade
3. Added code to load all tenants on startup 
4. Added methods getCacheFacade, initEntityFacade, getDeferredHitInfoQueue
5. Modified getEntity, flushqueue
6. Added tenant entities to entitiesToSkipHitCount
7. Running deferredHitInfoQueueByTenant for all tenants 

#### 13.   ExecutionContextImpl.java
1. Added activeTenantId, tenantIdStack 
2. Replaced entityFacade with getEntityFacade for active tenant.
3. Modified initCaches(), initWebFacade(), toString(), run() to include tenant information.
4. Added getTenantId(), getTenant(), changeTenant(), getThreadTenantId(), setThreadTenantId, ThreadPoolRunnable Constructor

#### 14. NotificationMessageImpl.groovy
1. Added string tenantId 
2. Modified base constructor, getNotificationTopic(), readExternal() to include tenant info.
3. Replaced entityFacade and entity with getEntity(tenantId)
4.  Replaced entityFacade with getEntityFacade(tenantId).

#### 15. TransactionFacadeImpl.groovy
1. Modified getTxConnection(), stashTxConnection()

#### 16. TransactionInternalBitronix.groovy 
1. Modified get DataSource

#### 17.  UserFacadeImpl.groovy
1. Modified initFromHttpRequest(), initFromHandshakeRequest(),  initFromHttpSession(), loginUser(), internalloginUser(), internalLoginToken(), logoutLocal(),  loginUserKey(), pushUserSubject(), pushUser(), pushTenant(), popTenant(), setInfo() to include and work with tenantId.

2. Addded String tenantId 
   
#### 18.   EntityCache.groovy
1. Converted strings from static final to final, values setting by tenant
2. Modified EntityCacheInvalidate(), writeExternal(), readExternal(), clearCacheForValue()  to use tenantId. 

#### 19.EntityDataDocument.groovy
1. Modified mergeValueToDocMap() to use getEci() and getTenantId() 
    
#### 20.   EntityDataFeed.groovy
1. Modified constructor EntityDataFeed and logs to display tenant Id 

#### 21.   EntityDataSourceFactoryImpl.groovy
1. Added tenantId param in init() method 

#### 22.   EntityDbMeta.groovy
Modified runsqlUpdate to pass shared connection null by default.

#### 23. EntityDefinition.groovy
1. Modified getCacheOne(), getCacheOneRa(), getCacheOneViewRa(), getCacheList(), getCacheListRa(), getCacheListViewRa(), getCacheCount() to take tenantId parameter.
   
#### 24.   EntityFacadeImpl 
1. Modified constructor, postFacadeInit(), static class DatasourceInfo, cachedOneEntities(), warmCache(), clearEntityDefinitionFromCache() 
2. Added getTenantId()

#### 25.   EntityFindBase.groovy
1. Modified oneInternal(), listInternal(), iteratorInternal(), countInternal(), updateAllInternal(), deleteAllInternal() - to check for common tenant condition.

#### 26.   EntityUtil.java
1. Modified EntityInfo() to check if transactional group name is tenantcommon.
