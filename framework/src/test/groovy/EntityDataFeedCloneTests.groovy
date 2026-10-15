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

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityDatasourceFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.service.ServiceCallback
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A datasource clone (group "transactional#...") is usually a read replica that lags the writer. The real-time
 * DataFeed queries its documents right after the writer commits, so it must read the writer, not the clone:
 * a record the clone has not applied yet would otherwise produce no document, and the feed never retries.
 */
class EntityDataFeedCloneTests extends Specification {
    static final String CLONE_GROUP = 'transactional#feedLagClone'
    static final String CLONE_URL = 'jdbc:h2:mem:feed_lag_clone;DB_CLOSE_DELAY=-1'
    static final String DOC_ID = 'FeedCloneLagTestDoc'
    static final String FEED_ID = 'FeedCloneLagTestFeed'
    static final String RECEIVE_SERVICE = 'org.moqui.impl.EntityServices.send#DataDocumentNotifications'

    @Shared ExecutionContext ec
    @Shared ExecutionContextFactoryImpl ecfi
    @Shared EntityFacadeImpl efi
    @Shared Connection cloneHold
    @Shared ConcurrentLinkedQueue<String> receivedFooIds = new ConcurrentLinkedQueue<>()
    @Shared volatile boolean recording = false

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ecfi = (ExecutionContextFactoryImpl) ec.factory
        efi = ecfi.entityFacade

        // the clone has the table but lags the writer: it never receives the rows written in this test
        cloneHold = DriverManager.getConnection(CLONE_URL, 'sa', '')
        cloneHold.createStatement().execute('CREATE TABLE IF NOT EXISTS FOO (FOO_ID VARCHAR(40) PRIMARY KEY, ' +
                'FOO_TEXT VARCHAR(255), LAST_UPDATED_STAMP TIMESTAMP)')
        DataSource cloneDs = [getConnection: { Object[] args -> DriverManager.getConnection(CLONE_URL, 'sa', '') }] as DataSource
        efi.datasourceFactoryByGroupMap.put(CLONE_GROUP, [getDataSource: { -> cloneDs }] as EntityDatasourceFactory)

        ec.service.registerCallback(RECEIVE_SERVICE, new ServiceCallback() {
            @Override boolean isEnabled() { return recording }
            @Override void receiveEvent(Map<String, Object> context, Map<String, Object> result) {
                if (!recording) return
                for (Map document in (List<Map>) context.documentList) {
                    Map foo = (Map) document.get('Foo') ?: document
                    if (foo.fooId) receivedFooIds.add((String) foo.fooId)
                }
            }
            @Override void receiveEvent(Map<String, Object> context, Throwable t) { }
        })

        ec.artifactExecution.disableAuthz()
        boolean began = ec.transaction.begin(null)
        try {
            ec.entity.makeValue('moqui.entity.document.DataDocument').setAll([dataDocumentId:DOC_ID,
                    documentName:'Feed clone lag test', primaryEntityName:'moqui.test.Foo']).createOrUpdate()
            ec.entity.makeValue('moqui.entity.document.DataDocumentField').setAll([dataDocumentId:DOC_ID,
                    fieldSeqId:'01', fieldPath:'fooId']).createOrUpdate()
            ec.entity.makeValue('moqui.entity.document.DataDocumentField').setAll([dataDocumentId:DOC_ID,
                    fieldSeqId:'02', fieldPath:'fooText']).createOrUpdate()
            ec.entity.makeValue('moqui.entity.feed.DataFeed').setAll([dataFeedId:FEED_ID, dataFeedTypeEnumId:'DTFDTP_RT_PUSH',
                    feedName:'Feed clone lag test', feedReceiveServiceName:RECEIVE_SERVICE]).createOrUpdate()
            ec.entity.makeValue('moqui.entity.feed.DataFeedDocument').setAll([dataFeedId:FEED_ID, dataDocumentId:DOC_ID]).createOrUpdate()
            ec.transaction.commit(began)
        } catch (Throwable t) {
            ec.transaction.rollback(began, 'feed clone lag test setup', t)
            throw t
        }
        ecfi.waitWorkerPoolEmpty(50)
        ec.cache.clearAllCaches()
        efi.entityDataFeed.entitiesWithDataFeed = null
    }

    def cleanupSpec() {
        recording = false
        efi.datasourceFactoryByGroupMap.remove(CLONE_GROUP)
        boolean began = ec.transaction.begin(null)
        try {
            ec.entity.find('moqui.test.Foo').condition('fooId', 'like', 'FCLAG%').deleteAll()
            ec.entity.find('moqui.entity.feed.DataFeedDocument').condition('dataFeedId', FEED_ID).deleteAll()
            ec.entity.find('moqui.entity.feed.DataFeed').condition('dataFeedId', FEED_ID).deleteAll()
            ec.entity.find('moqui.entity.document.DataDocumentField').condition('dataDocumentId', DOC_ID).deleteAll()
            ec.entity.find('moqui.entity.document.DataDocument').condition('dataDocumentId', DOC_ID).deleteAll()
            ec.transaction.commit(began)
        } catch (Throwable t) {
            ec.transaction.rollback(began, 'feed clone lag test cleanup', t)
        }
        ec.cache.clearAllCaches()
        efi.entityDataFeed.entitiesWithDataFeed = null
        ec.artifactExecution.enableAuthz()
        cloneHold?.close()
        ec.destroy()
    }

    def "real-time DataFeed delivers a committed record the clone has not applied yet"() {
        given:
        receivedFooIds.clear()
        recording = true

        when:
        boolean began = ec.transaction.begin(null)
        try {
            ['FCLAG1', 'FCLAG2', 'FCLAG3'].each { ec.entity.makeValue('moqui.test.Foo').setAll([fooId:it, fooText:'lag ' + it]).create() }
            ec.transaction.commit(began)
        } catch (Throwable t) {
            ec.transaction.rollback(began, 'feed clone lag test write', t)
            throw t
        }
        ecfi.waitWorkerPoolEmpty(100)
        recording = false

        then:
        efi.getDatasourceCloneName('transactional') == CLONE_GROUP
        receivedFooIds.toSet() == ['FCLAG1', 'FCLAG2', 'FCLAG3'] as Set
    }

    def "DataFeed window documents include records the clone has not applied yet"() {
        given:
        Timestamp fromStamp = new Timestamp(System.currentTimeMillis() - 1000)
        boolean began = ec.transaction.begin(null)
        try {
            ec.entity.makeValue('moqui.test.Foo').setAll([fooId:'FCLAG4', fooText:'lag window']).create()
            ec.transaction.commit(began)
        } catch (Throwable t) {
            ec.transaction.rollback(began, 'feed clone lag test window write', t)
            throw t
        }

        when:
        List<Map> documents = efi.entityDataFeed.getFeedDocuments(FEED_ID, fromStamp, new Timestamp(System.currentTimeMillis() + 1000))

        then:
        documents.collect { ((Map) it.get('Foo') ?: it).fooId }.contains('FCLAG4')
    }

    def "getDataDocuments still reads the clone by default"() {
        when:
        List<Map> documents = ec.entity.getDataDocuments(DOC_ID,
                ec.entity.conditionFactory.makeCondition('fooId', org.moqui.entity.EntityCondition.EQUALS, 'FCLAG1'), null, null)

        then: "the lagging clone does not have the record yet"
        documents.isEmpty()
    }
}
