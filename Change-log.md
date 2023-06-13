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
1. Added tenantId in methods getTxConnection() and stashTxConnection()

#### 16. TransactionInternalBitronix.groovy 
1. Added tenantId parameter getDataSource method.

#### 17.  UserFacadeImpl.groovy
1. Modified initFromHttpRequest(), initFromHandshakeRequest(),  initFromHttpSession() -
   Passed tenantId in method  pushUser()
   Passed tenantId null in pushUserSubject() 
   Get authTenantId from secure parameters and pass it into loginUser method.
   Get tenantId from header, trim it and pass it into loginUserKey method.
2. Modified loginUser() and internalloginUser()
   Added tenantId parameter methods
   Added code to change the tenant in execution context if another tenant has logged in
3. Added tenantId param in internalLoginToken()
4. Modified method logoutLocal()
   Added code to remove tenantId and visitId from session
5. Added tenantId in loginUserKey()
   Check for null tenantId and call changeTenant
   Passed tenantId in internalLoginUser()
6. Modified pushUser() and pushUserSubject()
   Passed tenantId in method params, new UserInfo and setInfo.
7. Added methods pushTenant() and popTenant() to manage tenants in the execution context.
   
#### 18.   EntityCache.groovy
1. Converted strings from static final to final and appended tenantId to all strings
2. Modified EntityCacheInvalidate(), writeExternal(), readExternal(), clearCacheForValue()  to use tenantId. 

#### 19.EntityDataDocument.groovy
Modified mergeValueToDocMap() to use getEci() and getTenantId() 
    
#### 20.   EntityDataFeed.groovy
Modified constructor EntityDataFeed and logs to display tenant Id 

#### 21.   EntityDataSourceFactoryImpl.groovy
Added tenantId param in init() method 

#### 22.   EntityDbMeta.groovy
Modified runsqlUpdate to pass "null" in shared connection by default.

#### 23. EntityDefinition.groovy
Modified getCacheOne(), getCacheOneRa(), getCacheOneViewRa(), getCacheList(), getCacheListRa(), getCacheListViewRa(), getCacheCount() to take tenantId parameter.
   
#### 24.   EntityFacadeImpl 
1. Modified constructor to take tenantId param, set to "DEFAULT" if not given.	
2. Modified postFacadeInit() to use defaultEntityFacade
3. Modified static class DatasourceInfo to check for tenants in Db and connect to tenant specific data source. 
4. Modified cachedOneEntities(), warmCache(), clearEntityDefinitionFromCache() 
5. Overloaded getAllEntitiesInfo() with tenantCommon default to true
6. Added boolean excludeTenantCommon to getAllEntitiesInfo()
7. Added getTenantId()

#### 25.   EntityFindBase.groovy
Modified oneInternal(), listInternal(), iteratorInternal(), countInternal(), updateAllInternal(), deleteAllInternal() - to check tenant and restrict operations on common entities.

#### 26.   EntityJavaUtil.java
Modified EntityInfo() to check if transactional group name is tenantcommon.

#### 27.   EntityListImpl.java
Modified constructors of EntityListImpl(), writeExternal(), readExternal() - to include tenantId.

#### 27. EntityValueBase.java 
1. Modified constructor of EntityValueBase, writeExternal(), readExternal() - to read and write tenantId
2. Modified create(),  update(), delete() - to check tenant and restrict operations on common entities.

#### 28. EntityDatasourceFactory.java
Added tenantId in  init(). 

#### 29.   ScreenDefinition.groovy
1. Added hashset tenantsAllowed, getTenantsAllowed() to check which tenants are allowed to access this screen.
2. Modified ScreenDefinition(), static class SubscreensItems and it's constructors, isValidInCurrentContext() - to manage access of tenants on screens.

### 30. ScreenRenderImpl.groovy
Modified recursiveRunActions(), doActualRender(), renderSubscreen() - to check if the rendered screen is available to current tenant.

#### 31. ScreenTestImpl.groovy
Modified ScreenTestRender render(), run() - to use tenantId

#### 32. ScreenUrlInfo.groovy
1. Modified isPermitted() - to check if specific tenant is permitted
2. Modified initUrl() - to add tenantId when making condition for checking SubscreensDefault records

#### 33. RestApi.groovy
Added tenant_id header in getSwaggerMap
   
#### 34. ScheduledJobRunner.groovy
1. Replaced cronByExpression hash map with executionTimeByExpression.
2. Modified run() to get allEntityFacades and execute job for each tenant.

#### 35. ServiceCallAsyncImpl.groovy
Modified AsyncServiceInfo(), writeExternal(), readExternal(), AsyncServiceInfo > runInternal() - to use threadTenantId in for managing tenants. 

#### 36. ServiceCallImpl.java
Added authTenantId in validateCall().

#### 37. ServiceCallJobImpl.java
Modified ServiceJobCallable(), writeExternal(), readExternal(), ServiceJobCallable>run() - to use threadTenantId in for managing tenants. 

#### 38. ServiceCallSyncImpl.java
1. Modified call() and callSingle() - to use tenantId.

#### 39. ServiceFacadeImpl.groovy

#### 40.   EntityAutoServiceRunner.groovy
Added authTenantId in otherFieldsToSkip in EntityAutoServiceRunner.groovy

#### 41. MoquiShiroRealm.groovy
Added tenantId parameter in MoquiShiroRealm to facilitate login with tenantId

#### 42. MoquiAbstractEndpoint.groovy
Added method getTenantId 

#### 43. MoquiSessionListener.groovy
Updated method closeVisit() to resolve visit Id not found exception in multi-tenant.

#### 44. NotificationWebSocketListener.groovy  
Modified registerEndpoint(), deregisterEndpoint(), onMessage() to use tenantId with user.

#### 45. Moqui.java
Modified loadData() to set tenantId.
#### 46. CacheFacade.java
Added getCache() for tenant specific cache.

#### 47. ExecutionContext.java
Added method getTenant(), getTenantId(), changeTenant(), popTenant() - to manage tenant operations.

#### 48. ExecutionContextFactory.java
Modified method for getEntity() to use tenantId.

#### 49. NotificationMessage.java
Added method getTenantId()

#### 50. TransactionalInternal.java
Modified getDataSource() to use tenantId.

#### 51. UserFacade.java
Modified loginUser(), loginUserKey() to use tenantId.

#### 52. EntityDataSourceFactory.java
Modified init() method to use tenantId 

#### 54. MoquiDefaultConf.xml
1. Set default property "entity_add_missing_runtime" to true to enable creating tables when a new tenant is provisioned.

2. Added tenants-share="true" in caches - service.location, service.rest.api, kie.component.releaseId, kie.session.component, screen.location, screen.location.perm, screen.url, screen.info, screen.info.ref.rev, screen.template.mode, screen.template.location, widget.template.location, screen.find.path, screen.form.db.node, resource.xml-actions.location, resource.groovy.location, resource.javascript.location, resource.ftl.location, resource.gstring.location, resource.wiki.location, resource.markdown.location, resource.text.location, resource.reference.location
3. Added service file location for TenantServices.xml
4. Added default properties of transactional database.
5. Added xa-properties in configuration of transactional database in datasource group-name="transactional"
6. Added datasource group-name="tenantcommon" 
7. Added entity-location for TenantEntities.xml
8. Added data location for TenantDefaultData,xml
9. Add default-runtime-add-missing="true" in 'mysql8' configuration.
 
#### 55. ResouceFacadeTest.groovy
Replaced moquiVersion with tenantId in evaluate String and evaluate Context Field functions

#### 56. SystemScreenRenderTest.groovy
Updated cacheName from l10n to DEFAULT__l10n in cleanupSpec()

#### 57. Common tests update
Passed tenandId 'null' in loginUser() in all *test.groovy files

#### 58. entity-definition-3.xsd
Added xs:enumeration value="tenantcommon" 

#### 59. moqui-conf-3.xsd
Added attribute name="tenants-share" type="boolean" default="false" to element "cache"

#### 60. service-definition-3.xsd 
Added authTenantId description.

#### 61.xml-screen-3.xsd 
Added attribute "tenantsAllowed"

#### 62. build.gradle 
1. Modified task cleanLoadSave to delete SaveTenant.zip 
2. Added args in task load, loadSeed, loadInitial and loadProduction to check for tenantId in properties and set default value.
3. Modified task saveDb to save Derby database in SaveTenant.zip file
4. Modified taskReloadSave to load SaveTenant.zip if file exists
  
#### 63. Common Changes - 
1. Replaced entityFacade and entity with getEntity(tenantId)
2. Replaced entityFacade with getEntityFacade(tenantId).
   

### 64. MoquiSessionListener.groovy
**Issue**
Whenever moqui is accessed from a browser, a session is created and stored as visitId is created and stored in the Visitor table, even without logging in.

When the user logs in it goes to the database to update the thru date  by this Visit Id.
This causes an error. Because Visit Id is created using DEFAULT tenant and stored in Default tenant's database, After logging in from another tenant it does not find the same value in the tenant's database. Causing null pointer exception
   
 **Solution**
Added code to destroy the existing VisitId stored in session and  to check if Visit Id exists in the database. If it exists then update the through date.

