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


import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.service.ServiceCallback
import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import java.sql.Timestamp

class ServiceFacadeTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    void cleanupServiceJobRunTestData() {
        boolean enableAuthz = !ec.artifactExecution.disableAuthz()
        boolean beganTransaction = ec.transaction.begin(null)
        try {
            ec.entity.find("moqui.test.TestServiceJobRunRef").condition("testRefId", "TEST_SJR_REF").deleteAll()
            ec.entity.find("moqui.service.job.ServiceJobRun").condition("jobRunId", "TEST_SJR_CLEAN_OLD").deleteAll()
            ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", "TEST_CLEAN_SJR").deleteAll()
            ec.transaction.commit(beganTransaction)
        } catch (Throwable t) {
            ec.transaction.rollback(beganTransaction, "Error cleaning ServiceJobRun test data", t)
            throw t
        } finally {
            if (enableAuthz) ec.artifactExecution.enableAuthz()
        }
    }

    def "register callback concurrently"() {
        def sfi = (ServiceFacadeImpl)ec.service
        ServiceCallback scb = Mock(ServiceCallback)

        when:
        ConcurrentExecution.executeConcurrently(10, { sfi.registerCallback("foo", scb) })
        sfi.callRegisteredCallbacks("foo", null, null)

        then:
        10 * scb.receiveEvent(null, null)
    }

    def "clean ServiceJobRun clears dependent references before deleting old runs"() {
        given:
        cleanupServiceJobRunTestData()
        boolean enableAuthz = !ec.artifactExecution.disableAuthz()
        boolean beganTransaction = ec.transaction.begin(null)
        try {
            ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName:"TEST_CLEAN_SJR",
                    serviceName:"org.moqui.impl.ServiceServices.clean#ServiceJobRun", paused:"Y"]).create()
            ec.entity.makeValue("moqui.service.job.ServiceJobRun").setAll([jobRunId:"TEST_SJR_CLEAN_OLD",
                    jobName:"TEST_CLEAN_SJR", startTime:new Timestamp(System.currentTimeMillis() - (120L * 24L * 60L * 60L * 1000L))]).create()
            ec.entity.makeValue("moqui.test.TestServiceJobRunRef").setAll([testRefId:"TEST_SJR_REF",
                    jobRunId:"TEST_SJR_CLEAN_OLD", testMedium:"Retained child record"]).create()
            ec.transaction.commit(beganTransaction)
        } catch (Throwable t) {
            ec.transaction.rollback(beganTransaction, "Error preparing ServiceJobRun cleanup test data", t)
            throw t
        } finally {
            if (enableAuthz) ec.artifactExecution.enableAuthz()
        }

        when:
        Map result = ec.service.sync().name("org.moqui.impl.ServiceServices.clean#ServiceJobRun")
                .parameters([daysToKeep:90]).call()

        then:
        result.recordsRemoved == 1
        ec.entity.find("moqui.service.job.ServiceJobRun").condition("jobRunId", "TEST_SJR_CLEAN_OLD").disableAuthz().one() == null
        def retainedRef = ec.entity.find("moqui.test.TestServiceJobRunRef").condition("testRefId", "TEST_SJR_REF").disableAuthz().one()
        retainedRef != null
        retainedRef.jobRunId == null

        cleanup:
        cleanupServiceJobRunTestData()
    }
}
