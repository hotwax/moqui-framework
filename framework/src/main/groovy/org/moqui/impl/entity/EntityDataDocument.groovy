/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.entity

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.condition.ConditionAlias
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.util.CollectionUtilities
import org.moqui.util.LiteStringMap
import org.moqui.util.MNode
import org.moqui.util.ObjectUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@CompileStatic
class EntityDataDocument {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDataDocument.class)

    protected final EntityFacadeImpl efi

    EntityDataDocument(EntityFacadeImpl efi) {
        this.efi = efi
    }

    int writeDocumentsToFile(String filename, List<String> dataDocumentIds, EntityCondition condition,
                             Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp, boolean prettyPrint) {
        File outFile = new File(filename)
        if (!outFile.createNewFile()) {
            efi.ecfi.getEci().message.addError(efi.ecfi.resource.expand('File ${filename} already exists.','',[filename:filename]))
            return 0
        }
        int valuesWritten
        try (PrintWriter pw = new PrintWriter(outFile)) {
        pw.write("[\n")
        valuesWritten = writeDocumentsToWriter(pw, dataDocumentIds, condition, fromUpdateStamp, thruUpdatedStamp, prettyPrint)
        pw.write("{}\n]\n")
        }
        efi.ecfi.getEci().message.addMessage(efi.ecfi.resource.expand('Wrote ${valuesWritten} documents to file ${filename}','',[valuesWritten:valuesWritten,filename:filename]))
        return valuesWritten
    }
    int writeDocumentsToDirectory(String dirname, List<String> dataDocumentIds, EntityCondition condition,
                                  Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp, boolean prettyPrint) {
        File outDir = new File(dirname)
        if (!outDir.exists()) outDir.mkdir()
        if (!outDir.isDirectory()) {
            efi.ecfi.getEci().message.addError(efi.ecfi.resource.expand('Path ${dirname} is not a directory.','',[dirname:dirname]))
            return 0
        }

        int valuesWritten = 0

        for (String dataDocumentId in dataDocumentIds) {
            String filename = "${dirname}/${dataDocumentId}.json"
            File outFile = new File(filename)
            if (outFile.exists()) {
                efi.ecfi.getEci().message.addError(efi.ecfi.resource.expand('File ${filename} already exists, skipping document ${dataDocumentId}.','',[filename:filename,dataDocumentId:dataDocumentId]))
                continue
            }
            outFile.createNewFile()

            try (PrintWriter pw = new PrintWriter(outFile)) {
            pw.write("[\n")
            valuesWritten += writeDocumentsToWriter(pw, [dataDocumentId], condition, fromUpdateStamp, thruUpdatedStamp, prettyPrint)
            pw.write("{}\n]\n")
            }
            efi.ecfi.getEci().message.addMessage(efi.ecfi.resource.expand('Wrote ${valuesWritten} records to file ${filename}','',[valuesWritten:valuesWritten, filename:filename]))
        }

        return valuesWritten
    }
    int writeDocumentsToWriter(Writer pw, List<String> dataDocumentIds, EntityCondition condition,
                               Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp, boolean prettyPrint) {
        if (dataDocumentIds == null || dataDocumentIds.size() == 0) return 0
        int valuesWritten = 0
        for (String dataDocumentId in dataDocumentIds) {
            ArrayList<Map> documentList = getDataDocuments(dataDocumentId, condition, fromUpdateStamp, thruUpdatedStamp)
            int docListSize = documentList.size()
            for (int i = 0; i < docListSize; i++) {
                if (valuesWritten > 0) pw.write(",\n")
                Map document = (Map) documentList.get(i)
                String json = JsonOutput.toJson(document)
                if (prettyPrint) {
                    pw.write(JsonOutput.prettyPrint(json))
                } else {
                    pw.write(json)
                }
                valuesWritten++
            }
        }
        if (valuesWritten > 0) pw.write("\n")

        return valuesWritten
    }

    static class DataDocumentInfo {
        String dataDocumentId
        EntityValue dataDocument
        EntityList dataDocumentFieldList
        EntityList dataDocumentRelAliasList
        EntityList dataDocumentConditionList
        String primaryEntityName
        EntityDefinition primaryEd
        ArrayList<String> primaryPkFieldNames
        int primaryPkFieldNamesSize
        Map<String, Object> fieldTree = [:]
        Map<String, String> fieldAliasPathMap = [:]
        Map<String, String> relationshipAliasMap = [:]
        boolean hasExpressionField = false
        boolean hasAllPrimaryPks = true
        EntityDefinition entityDef

        DataDocumentInfo(String dataDocumentId, EntityFacadeImpl efi) {
            this.dataDocumentId = dataDocumentId

            dataDocument = efi.fastFindOne("moqui.entity.document.DataDocument", true, false, dataDocumentId)
            if (dataDocument == null) throw new EntityException("No DataDocument found with ID ${dataDocumentId}")
            dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, ['sequenceNum', 'fieldPath'], true, false)
            dataDocumentRelAliasList = dataDocument.findRelated("moqui.entity.document.DataDocumentRelAlias", null, null, true, false)
            dataDocumentConditionList = dataDocument.findRelated("moqui.entity.document.DataDocumentCondition", null, null, true, false)

            for (int rai = 0; rai < dataDocumentRelAliasList.size(); rai++) {
                EntityValue dataDocumentRelAlias = (EntityValue) dataDocumentRelAliasList.get(rai)
                relationshipAliasMap.put((String) dataDocumentRelAlias.getNoCheckSimple("relationshipName"),
                        (String) dataDocumentRelAlias.getNoCheckSimple("documentAlias"))
            }

            primaryEntityName = (String) dataDocument.getNoCheckSimple("primaryEntityName")
            primaryEd = efi.getEntityDefinition(primaryEntityName)
            primaryPkFieldNames = primaryEd.getPkFieldNames()
            primaryPkFieldNamesSize = primaryPkFieldNames.size()

            AtomicBoolean hasExprMut = new AtomicBoolean(false)
            populateFieldTreeAndAliasPathMap(dataDocumentFieldList, primaryPkFieldNames, fieldTree, fieldAliasPathMap, hasExprMut, false)
            hasExpressionField = hasExprMut.get()

            for (int pki = 0; pki < primaryPkFieldNames.size(); pki++) {
                String pkFieldName = (String) primaryPkFieldNames.get(pki)
                if (!fieldAliasPathMap.containsKey(pkFieldName)) {
                    hasAllPrimaryPks = false
                    break
                }
            }

            EntityDynamicViewImpl dynamicView = new EntityDynamicViewImpl(efi)
            dynamicView.entityNode.attributes.put("package", "DataDocument")
            dynamicView.entityNode.attributes.put("entity-name", dataDocumentId)

            // add member entities and field aliases to dynamic view
            dynamicView.addMemberEntity("PRIM", primaryEntityName, null, null, null)
            AtomicInteger incrementer = new AtomicInteger()
            fieldTree.put("_ALIAS", "PRIM")
            addDataDocRelatedEntity(dynamicView, "PRIM", fieldTree, incrementer, makeDdfByAlias(dataDocumentFieldList))
            // logger.warn("=========== ${dataDocumentId} fieldTree=${fieldTree}")
            // logger.warn("=========== ${dataDocumentId} fieldAliasPathMap=${fieldAliasPathMap}")

            entityDef = dynamicView.makeEntityDefinition()
        }
        String makeDocId(EntityValue ev) {
            if (primaryPkFieldNamesSize == 1) {
                // optimization for common simple case
                String pkFieldName = (String) primaryPkFieldNames.get(0)
                Object pkFieldValue = ev.getNoCheckSimple(pkFieldName)
                return ObjectUtilities.toPlainString(pkFieldValue)
            } else {
                StringBuilder pkCombinedSb = new StringBuilder()
                for (int pki = 0; pki < primaryPkFieldNamesSize; pki++) {
                    String pkFieldName = (String) primaryPkFieldNames.get(pki)
                    // don't do this, always use full PK even if not all aliased in doc, probably a bad DataDocument definition: if (!fieldAliasPathMap.containsKey(pkFieldName)) continue
                    if (pkCombinedSb.length() > 0) pkCombinedSb.append("::")
                    Object pkFieldValue = ev.getNoCheckSimple(pkFieldName)
                    pkCombinedSb.append(ObjectUtilities.toPlainString(pkFieldValue))
                }
                return pkCombinedSb.toString()
            }
        }
    }

    EntityDefinition makeEntityDefinition(String dataDocumentId) {
        DataDocumentInfo ddi = new DataDocumentInfo(dataDocumentId, efi)
        return ddi.entityDef
    }

    EntityFind makeDataDocumentFind(String dataDocumentId) {
        DataDocumentInfo ddi = new DataDocumentInfo(dataDocumentId, efi)
        return makeDataDocumentFind(ddi, null, null)
    }

    EntityFind makeDataDocumentFind(DataDocumentInfo ddi, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp) {
        // build the query condition for the primary entity and all related entities
        EntityDefinition ed = ddi.entityDef
        EntityFind mainFind = ed.makeEntityFind()

        // add conditions
        if (ddi.dataDocumentConditionList != null && ddi.dataDocumentConditionList.size() > 0) {
            ExecutionContextImpl eci = efi.ecfi.getEci()
            int dataDocumentConditionListSize = ddi.dataDocumentConditionList.size()
            for (int ddci = 0; ddci < dataDocumentConditionListSize; ddci++) {
                EntityValue dataDocumentCondition = (EntityValue) ddi.dataDocumentConditionList.get(ddci)
                String fieldAlias = (String) dataDocumentCondition.getNoCheckSimple("fieldNameAlias")
                FieldInfo fi = ed.getFieldInfo(fieldAlias)
                if (fi == null) throw new EntityException("Found DataDocument Condition with alias [${fieldAlias}] that is not aliased in DataDocument ${ddi.dataDocumentId}")
                if (dataDocumentCondition.getNoCheckSimple("postQuery") != "Y") {
                    String operator = ((String) dataDocumentCondition.getNoCheckSimple("operator")) ?: 'equals'
                    String toFieldAlias = (String) dataDocumentCondition.getNoCheckSimple("toFieldNameAlias")
                    if (toFieldAlias != null && !toFieldAlias.isEmpty()) {
                        mainFind.conditionToField(fieldAlias, EntityConditionFactoryImpl.stringComparisonOperatorMap.get(operator), toFieldAlias)
                    } else {
                        String stringVal = (String) dataDocumentCondition.getNoCheckSimple("fieldValue")
                        Object objVal = fi.convertFromString(stringVal, eci.l10nFacade)
                        mainFind.condition(fieldAlias, operator, objVal)
                    }
                }
            }
        }

        // create a condition with an OR list of date range comparisons to check that at least one member-entity has lastUpdatedStamp in range
        if ((Object) fromUpdateStamp != null || (Object) thruUpdatedStamp != null) {
            List<EntityCondition> dateRangeOrCondList = []
            for (MNode memberEntityNode in ed.entityNode.children("member-entity")) {
                ConditionField ludCf = new ConditionAlias(memberEntityNode.attribute("entity-alias"),
                        "lastUpdatedStamp", efi.getEntityDefinition(memberEntityNode.attribute("entity-name")))
                List<EntityCondition> dateRangeFieldCondList = []
                if ((Object) fromUpdateStamp != null) {
                    dateRangeFieldCondList.add(efi.getConditionFactory().makeCondition(
                            new FieldValueCondition(ludCf, EntityCondition.EQUALS, null),
                            EntityCondition.OR,
                            new FieldValueCondition(ludCf, EntityCondition.GREATER_THAN_EQUAL_TO, fromUpdateStamp)))
                }
                if ((Object) thruUpdatedStamp != null) {
                    dateRangeFieldCondList.add(efi.getConditionFactory().makeCondition(
                            new FieldValueCondition(ludCf, EntityCondition.EQUALS, null),
                            EntityCondition.OR,
                            new FieldValueCondition(ludCf, EntityCondition.LESS_THAN, thruUpdatedStamp)))
                }
                dateRangeOrCondList.add(efi.getConditionFactory().makeCondition(dateRangeFieldCondList, EntityCondition.AND))
            }
            mainFind.condition(efi.getConditionFactory().makeCondition(dateRangeOrCondList, EntityCondition.OR))
        }

        // use a read only clone if available, this always runs async or for reporting anyway
        mainFind.useClone(true)

        // logger.warn("=========== DataDocument query condition for ${dataDocumentId} mainFind.condition=${((EntityFindImpl) mainFind).getWhereEntityCondition()}\n${mainFind.toString()}")
        return mainFind
    }

    /** Build data document Maps from DB data, feed in batches to specified service. This is called from the SearchServices.index#DataFeedDocuments service */
    int feedDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp,
            String feedReceiveServiceName, Integer batchSizeOvd) {
        if (feedReceiveServiceName == null || feedReceiveServiceName.isEmpty()) {
            logger.warn("In feedDataDocuments no feed receive service name specified, not searching and feeding ${dataDocumentId} documents")
            return 0
        }
        int batchSize = batchSizeOvd != null ? batchSizeOvd.intValue() : 1000
        logger.info("Feeding data documents for dataDocumentId ${dataDocumentId} in batches of ${batchSize} to service ${feedReceiveServiceName}")

        DataDocumentInfo ddi = new DataDocumentInfo(dataDocumentId, efi)

        long startTimeMillis = System.currentTimeMillis()
        Timestamp docTimestamp = thruUpdatedStamp != (Timestamp) null ? thruUpdatedStamp : new Timestamp(startTimeMillis)
        String docTsString = docTimestamp.toInstant().atZone(ZoneOffset.UTC.normalized()).format(DateTimeFormatter.ISO_INSTANT)

        boolean hasAllPrimaryPks = ddi.hasAllPrimaryPks
        if (!hasAllPrimaryPks) logger.warn("DataDocument ${dataDocumentId} does not have all primary keys for feed to service ${feedReceiveServiceName}")
        Map<String, Map> documentMapMap = hasAllPrimaryPks ? new LinkedHashMap<String, Map>(batchSize + 10) : null
        ArrayList<Map> documentMapList = hasAllPrimaryPks ? null : new ArrayList<Map>(batchSize + 10)

        EntityFind mainFind = makeDataDocumentFind(ddi, fromUpdateStamp, thruUpdatedStamp)
        if (condition != null) mainFind.condition(condition)

        // for this to work sort by primary key fields (of primary entity) so all records for a given document are together
        mainFind.orderBy(ddi.primaryPkFieldNames)

        // do the one big query
        String lastDocId = null
        int docCount = 0
        try (EntityListIterator mainEli = mainFind.iterator()) {
            logger.info("Feed dataDocumentId ${dataDocumentId} query complete (cursor opened) in ${System.currentTimeMillis() - startTimeMillis}ms")
            EntityValue ev
            while ((ev = (EntityValue) mainEli.next()) != null) {
                String curDocId = ddi.makeDocId(ev)
                if (!curDocId.equals(lastDocId)) {
                    docCount++

                    // index the batch if time to, with sort by PK fields when we get a new combined doc ID
                    //     we are in results between documents (single document often has multiple rows)
                    int docsSoFar = hasAllPrimaryPks ? documentMapMap.size() : documentMapList.size()
                    if (docsSoFar >= batchSize) {
                        // logger.warn("curDocId ${curDocId} lastDocId ${lastDocId}")

                        if (hasAllPrimaryPks) {
                            documentMapList = new ArrayList<>(documentMapMap.values())
                        }
                        postProcessDocMapList(documentMapList, ddi)

                        // call the feed receive service
                        efi.ecfi.serviceFacade.sync().name(feedReceiveServiceName).parameter("documentList", documentMapList)
                                .noRememberParameters().call()
                        // stop if there was an error
                        if (efi.ecfi.getEci().messageFacade.hasError()) break

                        documentMapMap = hasAllPrimaryPks ? new LinkedHashMap<String, Map>(batchSize + 10) : null
                        documentMapList = hasAllPrimaryPks ? null : new ArrayList<Map>(batchSize + 10)
                    }
                }

                // continue current doc or ready to move on to next doc, merge the current result
                lastDocId = mergeValueToDocMap(ev, ddi, documentMapMap, documentMapList, docTsString)
            }
            // feed remaining documents
            if (documentMapMap != null && documentMapMap.size() > 0) {
                documentMapList = new ArrayList<>(documentMapMap.values())
            }
            if (documentMapList != null && documentMapList.size() > 0) {
                postProcessDocMapList(documentMapList, ddi)
                // call the feed receive service
                efi.ecfi.serviceFacade.sync().name(feedReceiveServiceName).parameter("documentList", documentMapList).call()
            }
        } finally {
            logger.info("Feed dataDocumentId ${dataDocumentId} feed complete and cursor closed in ${System.currentTimeMillis() - startTimeMillis}ms")
        }

        logger.info("Fed ${docCount} data documents for dataDocumentId ${dataDocumentId} to service ${feedReceiveServiceName}")
        return docCount
    }

    ArrayList<Map> getDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp) {
        DataDocumentInfo ddi = new DataDocumentInfo(dataDocumentId, efi)

        EntityFind mainFind = makeDataDocumentFind(ddi, fromUpdateStamp, thruUpdatedStamp)
        if (condition != null) mainFind.condition(condition)

        Timestamp docTimestamp = thruUpdatedStamp != (Timestamp) null ? thruUpdatedStamp : new Timestamp(System.currentTimeMillis())
        String docTsString = docTimestamp.toInstant().atZone(ZoneOffset.UTC.normalized()).format(DateTimeFormatter.ISO_INSTANT)

        Map<String, Map> documentMapMap = ddi.hasAllPrimaryPks ? new LinkedHashMap<String, Map>() : null
        ArrayList<Map> documentMapList = ddi.hasAllPrimaryPks ? null : new ArrayList<Map>()

        // do the one big query

        mainFind.iterator().withCloseable ({mainEli->
            EntityValue ev
            while ((ev = (EntityValue) mainEli.next()) != null) {
                // logger.warn("=========== DataDocument query result for ${dataDocumentId}: ${ev}")
                mergeValueToDocMap(ev, ddi, documentMapMap, documentMapList, docTsString)
            }
        })

        // make the actual list and return it
        if (ddi.hasAllPrimaryPks) {
            documentMapList = new ArrayList<>(documentMapMap.size())
            documentMapList.addAll(documentMapMap.values())
        }

        postProcessDocMapList(documentMapList, ddi)

        return documentMapList
    }

    String mergeValueToDocMap(EntityValue ev, DataDocumentInfo ddi, Map<String, Map> documentMapMap,
            ArrayList<Map> documentMapList, String docTsString) {
        /*
          - _index = DataDocument.indexName
          - _type = dataDocumentId
          - _id = pk field values from primary entity, double colon separated
          - _timestamp = document created time
          - Map for primary entity with primaryEntityName as key
          - nested List of Maps for each related entity with aliased fields with relationship name as key
         */
        String docId = ddi.makeDocId(ev)
        // logger.warn("DataDoc record PKs string: " + docId)
        Map<String, Object> docMap = ddi.hasAllPrimaryPks ? ((Map<String, Object>) documentMapMap.get(docId)) : (Map<String, Object>) null
        if (docMap == null) {
            // add special entries
            docMap = new LiteStringMap<Object>()
            docMap.put("_type", ddi.dataDocumentId)
            if (docId != null && !docId.isEmpty()) docMap.put("_id", docId)
            docMap.put('_timestamp', docTsString)
            String _index = ddi.dataDocument.indexName
            if (_index != null && !_index.isEmpty()) docMap.put('_index', _index.toLowerCase())
            docMap.put('_entity', ddi.primaryEd.getShortOrFullEntityName())

            // add Map for primary entity
            for (Map.Entry<String, Object> fieldTreeEntry in ddi.fieldTree.entrySet()) {
                Object entryValue = fieldTreeEntry.getValue()
                // if ("_ALIAS".equals(fieldTreeEntry.getKey())) continue
                if (entryValue instanceof ArrayList) {
                    String fieldEntryKey = fieldTreeEntry.getKey()
                    if (fieldEntryKey.startsWith("(")) continue
                    ArrayList<String> fieldAliasList = (ArrayList<String>) entryValue
                    for (int i = 0; i < fieldAliasList.size(); i++) {
                        String fieldAlias = (String) fieldAliasList.get(i)
                        Object curVal = ev.get(fieldAlias)
                        if (curVal != null) docMap.put(fieldAlias, curVal)
                    }
                }
            }

            if (ddi.hasAllPrimaryPks) documentMapMap.put(docId, docMap)
            else documentMapList.add(docMap)
        }

        // recursively add Map or List of Maps for each related entity
        populateDataDocRelatedMap(ev, docMap, ddi.primaryEd, ddi.fieldTree, ddi.relationshipAliasMap, false)

        return docId
    }
    void postProcessDocMapList(ArrayList<Map> documentMapList, DataDocumentInfo ddi) {
        String manualDataServiceName = (String) ddi.dataDocument.getNoCheckSimple("manualDataServiceName")
        // NOTE: have to get size() each time in case records are removed
        for (int i = 0; i < documentMapList.size(); ) {
            Map<String, Object> docMap = (Map<String, Object>) documentMapList.get(i)
            // call the manualDataServiceName service for each document
            if (manualDataServiceName != null && !manualDataServiceName.isEmpty()) {
                // logger.warn("Calling ${manualDataServiceName} with doc: ${docMap}")
                Map result = efi.ecfi.serviceFacade.sync().name(manualDataServiceName)
                        .parameter("dataDocumentId", ddi.dataDocumentId).parameter("document", docMap).call()
                if (result == null || efi.ecfi.getEci().messageFacade.hasError()) {
                    logger.error("Error calling manual data service for ${ddi.dataDocumentId}, document may be missing data: ${efi.ecfi.getEci().messageFacade.getErrorsString()}")
                    efi.ecfi.getEci().messageFacade.clearErrors()
                } else {
                    Map outDoc = (Map<String, Object>) result.get("document")
                    if (outDoc != null && outDoc.size() > 0) {
                        docMap = outDoc
                        documentMapList.set(i, docMap)
                    }
                }
            }

            // evaluate expression fields
            if (ddi.hasExpressionField) {
                runDocExpressions(docMap, null, ddi.primaryEd, ddi.fieldTree, ddi.relationshipAliasMap)
            }

            // check postQuery conditions
            boolean allPassed = true
            int dataDocumentConditionListSize = ddi.dataDocumentConditionList.size()
            for (int ddci = 0; ddci < dataDocumentConditionListSize; ddci++) {
                EntityValue dataDocumentCondition = (EntityValue) ddi.dataDocumentConditionList.get(ddci)
                if ("Y".equals(dataDocumentCondition.postQuery)) {
                    Set<Object> valueSet = new HashSet<Object>()
                    CollectionUtilities.findAllFieldsNestedMap((String) dataDocumentCondition.getNoCheckSimple("fieldNameAlias"), docMap, valueSet)
                    if (valueSet.size() == 0) {
                        if (!dataDocumentCondition.getNoCheckSimple("fieldValue")) { continue }
                        else { allPassed = false; break }
                    }
                    if (!dataDocumentCondition.getNoCheckSimple("fieldValue")) { allPassed = false; break }
                    Object fieldValueObj = dataDocumentCondition.getNoCheckSimple("fieldValue").asType(valueSet.first().class)
                    if (!(fieldValueObj in valueSet)) { allPassed = false; break }
                }
            }

            if (allPassed) { i++ } else { documentMapList.remove(i) }
        }
    }

    static ArrayList<String> fieldPathToList(String fieldPath) {
        int openParenIdx = fieldPath.indexOf("(")
        ArrayList<String> fieldPathElementList = new ArrayList<>()
        if (openParenIdx == -1) {
            Collections.addAll(fieldPathElementList, fieldPath.split(":"))
        } else {
            if (openParenIdx > 0) {
                // should end with a colon so subtract 1
                String preParen = fieldPath.substring(0, openParenIdx - 1)
                Collections.addAll(fieldPathElementList, preParen.split(":"))
                fieldPathElementList.add(fieldPath.substring(openParenIdx))
            } else {
                fieldPathElementList.add(fieldPath)
            }
        }
        return fieldPathElementList
    }
    static void populateFieldTreeAndAliasPathMap(EntityList dataDocumentFieldList, List<String> primaryPkFieldNames,
                                          Map<String, Object> fieldTree, Map<String, String> fieldAliasPathMap, AtomicBoolean hasExprMut, boolean allPks) {
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = (String) dataDocumentField.getNoCheckSimple("fieldPath")
            ArrayList<String> fieldPathElementList = fieldPathToList(fieldPath)
            Map currentTree = fieldTree
            int fieldPathElementListSize = fieldPathElementList.size()
            for (int i = 0; i < fieldPathElementListSize; i++) {
                String fieldPathElement = (String) fieldPathElementList.get(i)
                if (i < (fieldPathElementListSize - 1)) {
                    Map subTree = (Map) currentTree.get(fieldPathElement)
                    if (subTree == null) { subTree = [:]; currentTree.put(fieldPathElement, subTree) }
                    currentTree = subTree
                } else {
                    String fieldAlias = ((String) dataDocumentField.getNoCheckSimple("fieldNameAlias")) ?: fieldPathElement
                    CollectionUtilities.addToListInMap(fieldPathElement, fieldAlias, currentTree)
                    fieldAliasPathMap.put(fieldAlias, fieldPath)
                    if (fieldPathElement.startsWith("(")) hasExprMut.set(true)
                }
            }
        }
        // make sure all PK fields of the primary entity are aliased
        if (allPks) {
            for (String pkFieldName in primaryPkFieldNames) if (!fieldAliasPathMap.containsKey(pkFieldName)) {
                fieldTree.put(pkFieldName, pkFieldName)
                fieldAliasPathMap.put(pkFieldName, pkFieldName)
            }
        }
    }

    protected void runDocExpressions(Map<String, Object> curDocMap, Map<String, Object> parentsMap, EntityDefinition parentEd,
                                     Map<String, Object> fieldTreeCurrent, Map relationshipAliasMap) {
        for (Map.Entry<String, Object> fieldTreeEntry in fieldTreeCurrent.entrySet()) {
            String fieldEntryKey = fieldTreeEntry.getKey()
            Object fieldEntryValue = fieldTreeEntry.getValue()
            if (fieldEntryValue instanceof Map) {
                String relationshipName = fieldEntryKey
                Map<String, Object> fieldTreeChild = (Map<String, Object>) fieldEntryValue

                EntityJavaUtil.RelationshipInfo relationshipInfo = parentEd.getRelationshipInfo(relationshipName)
                String relDocumentAlias = relationshipAliasMap.get(relationshipName) ?: relationshipInfo.shortAlias ?: relationshipName
                EntityDefinition relatedEd = relationshipInfo.relatedEd
                boolean isOneRelationship = relationshipInfo.isTypeOne

                if (isOneRelationship) {
                    runDocExpressions(curDocMap, parentsMap, relatedEd, fieldTreeChild, relationshipAliasMap)
                } else {
                    List<Map> relatedEntityDocList = (List<Map>) curDocMap.get(relDocumentAlias)
                    if (relatedEntityDocList != null) for (Map childMap in relatedEntityDocList) {
                        Map<String, Object> newParentsMap
                        if (parentsMap != null) {
                            newParentsMap = new HashMap<String, Object>(parentsMap)
                            newParentsMap.putAll(curDocMap)
                        } else {
                            newParentsMap = curDocMap
                        }
                        runDocExpressions(childMap, newParentsMap, relatedEd, fieldTreeChild, relationshipAliasMap)
                    }
                }
            } else if (fieldEntryValue instanceof ArrayList) {
                if (fieldEntryKey.startsWith("(")) {
                    // run expression to get value, set for all aliases (though will always be one)
                    Map<String, Object> evalMap
                    if (parentsMap != null) {
                        evalMap = new HashMap<String, Object>(parentsMap)
                        evalMap.putAll(curDocMap)
                    } else {
                        evalMap = curDocMap
                    }
                    try {
                        Object curVal = efi.ecfi.resourceFacade.expression(fieldEntryKey, null, evalMap)
                        if (curVal != null) {
                            ArrayList<String> fieldAliasList = (ArrayList<String>) fieldEntryValue
                            for (int i = 0; i < fieldAliasList.size(); i++) {
                                String fieldAlias = (String) fieldAliasList.get(i)
                                if (curVal != null) curDocMap.put(fieldAlias, curVal)
                            }
                        }
                    } catch (Throwable t) {
                        logger.error("Error evaluating DataDocumentField expression: ${fieldEntryKey}", t)
                    }
                }
            }
        }
    }

    protected void populateDataDocRelatedMap(EntityValue ev, Map<String, Object> parentDocMap, EntityDefinition parentEd,
                                             Map<String, Object> fieldTreeCurrent, Map relationshipAliasMap, boolean setFields) {
        for (Map.Entry<String, Object> fieldTreeEntry in fieldTreeCurrent.entrySet()) {
            String fieldEntryKey = fieldTreeEntry.getKey()
            Object fieldEntryValue = fieldTreeEntry.getValue()
            // if ("_ALIAS".equals(fieldEntryKey)) continue
            if (fieldEntryValue instanceof Map) {
                String relationshipName = fieldEntryKey
                Map<String, Object> fieldTreeChild = (Map<String, Object>) fieldEntryValue

                EntityJavaUtil.RelationshipInfo relationshipInfo = parentEd.getRelationshipInfo(relationshipName)
                String relDocumentAlias = relationshipAliasMap.get(relationshipName) ?: relationshipInfo.shortAlias ?: relationshipName
                EntityDefinition relatedEd = relationshipInfo.relatedEd
                boolean isOneRelationship = relationshipInfo.isTypeOne

                if (isOneRelationship) {
                    // we only need a single Map
                    populateDataDocRelatedMap(ev, parentDocMap, relatedEd, fieldTreeChild, relationshipAliasMap, true)
                } else {
                    // we need a List of Maps
                    Map relatedEntityDocMap = (Map) null

                    // see if there is a Map in the List in the matching entry
                    List<Map> relatedEntityDocList = (List<Map>) parentDocMap.get(relDocumentAlias)
                    if (relatedEntityDocList != null) {
                        for (Map candidateMap in relatedEntityDocList) {
                            boolean allMatch = true
                            for (Map.Entry<String, Object> fieldTreeChildEntry in fieldTreeChild.entrySet()) {
                                Object entryValue = fieldTreeChildEntry.getValue()
                                if (entryValue instanceof ArrayList && !fieldTreeChildEntry.getKey().startsWith("(")) {
                                    ArrayList<String> fieldAliasList = (ArrayList<String>) entryValue
                                    for (int i = 0; i < fieldAliasList.size(); i++) {
                                        String fieldAlias = (String) fieldAliasList.get(i)
                                        if (candidateMap.get(fieldAlias) != ev.get(fieldAlias)) {
                                            allMatch = false
                                            break
                                        }
                                    }
                                }
                            }
                            if (allMatch) {
                                relatedEntityDocMap = candidateMap
                                break
                            }
                        }
                    }

                    if (relatedEntityDocMap == null) {
                        // no matching Map? create a new one... and it will get populated in the recursive call
                        relatedEntityDocMap = new LiteStringMap<Object>()
                        // now time to recurse
                        populateDataDocRelatedMap(ev, relatedEntityDocMap, relatedEd, fieldTreeChild, relationshipAliasMap, true)
                        if (relatedEntityDocMap.size() > 0) {
                            if (relatedEntityDocList == null) {
                                // use ArrayList internally, avoid new object per entry with LinkedList
                                relatedEntityDocList = new ArrayList<>()
                                parentDocMap.put(relDocumentAlias, relatedEntityDocList)
                            }
                            relatedEntityDocList.add(relatedEntityDocMap)
                        }
                    } else {
                        // now time to recurse
                        populateDataDocRelatedMap(ev, relatedEntityDocMap, relatedEd, fieldTreeChild, relationshipAliasMap, false)
                    }
                }
            } else if (fieldEntryValue instanceof ArrayList) {
                if (setFields && !fieldEntryKey.startsWith("(")) {
                    // set the field(s)
                    ArrayList<String> fieldAliasList = (ArrayList<String>) fieldEntryValue
                    for (int i = 0; i < fieldAliasList.size(); i++) {
                        String fieldAlias = (String) fieldAliasList.get(i)
                        Object curVal = ev.get(fieldAlias)
                        if (curVal != null) parentDocMap.put(fieldAlias, curVal)
                    }
                }
            }
        }
    }

    private static Map<String, EntityValue> makeDdfByAlias(EntityList dataDocumentFieldList) {
        Map<String, EntityValue> ddfByAlias = new HashMap<>()
        int ddfSize = dataDocumentFieldList.size()
        for (int i = 0; i < ddfSize; i++) {
            EntityValue ddf = (EntityValue) dataDocumentFieldList.get(i)
            String alias = (String) ddf.getNoCheckSimple("fieldNameAlias")
            if (alias == null || alias.isEmpty()) {
                String fieldPath = (String) ddf.getNoCheckSimple("fieldPath")
                ArrayList<String> fieldPathElementList = fieldPathToList(fieldPath)
                alias = (String) fieldPathElementList.get(fieldPathElementList.size() - 1)
            }
            ddfByAlias.put(alias, ddf)
        }
        return ddfByAlias
    }
    private static void addDataDocRelatedEntity(EntityDynamicViewImpl dynamicView, String parentEntityAlias,
            Map<String, Object> fieldTreeCurrent, AtomicInteger incrementer, Map<String, EntityValue> ddfByAlias) {
        for (Map.Entry fieldTreeEntry in fieldTreeCurrent.entrySet()) {
            String fieldEntryKey = (String) fieldTreeEntry.getKey()
            if ("_ALIAS".equals(fieldEntryKey)) continue

            Object entryValue = fieldTreeEntry.getValue()
            if (entryValue instanceof Map) {
                Map fieldTreeChild = (Map) entryValue
                // add member entity, and entity alias in "_ALIAS" entry
                String entityAlias = "MBR" + incrementer.getAndIncrement()
                dynamicView.addRelationshipMember(entityAlias, parentEntityAlias, fieldEntryKey, true)
                fieldTreeChild.put("_ALIAS", entityAlias)
                // now time to recurse
                addDataDocRelatedEntity(dynamicView, entityAlias, fieldTreeChild, incrementer, ddfByAlias)
            } else if (entryValue instanceof ArrayList) {
                // add alias for field
                String entityAlias = fieldTreeCurrent.get("_ALIAS")
                ArrayList<String> fieldAliasList = (ArrayList<String>) entryValue
                for (int i = 0; i < fieldAliasList.size(); i++) {
                    String fieldAlias = (String) fieldAliasList.get(i)
                    EntityValue ddf = ddfByAlias.get(fieldAlias)
                    if (ddf == null) throw new EntityException("Could not find DataDocumentField for field alias ${fieldEntryKey}")
                    String defaultDisplay = ddf.getNoCheckSimple("defaultDisplay")

                    if (fieldEntryKey.startsWith("(")) {
                        // handle expressions differently, expressions have to be meant for this but nice for various cases
                        // TODO: somehow specify type, yet another new field on DataDocumentField entity? for now defaulting to 'text-long'
                        dynamicView.addPqExprAlias(fieldAlias, fieldEntryKey, "text-long",
                                "N".equals(defaultDisplay) ? "false" : ("Y".equals(defaultDisplay) ? "true" : null))
                    } else {
                        dynamicView.addAlias(entityAlias, fieldAlias, fieldEntryKey, (String) ddf.getNoCheckSimple("functionName"),
                                "N".equals(defaultDisplay) ? "false" : ("Y".equals(defaultDisplay) ? "true" : null))
                    }
                }
            }
        }
    }
}
