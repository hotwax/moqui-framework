import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import java.sql.Timestamp

class TypedEmptyFilterTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { ec.destroy(); Moqui.destroyActiveExecutionContextFactory() }
    def setup() { ec.artifactExecution.disableAuthz(); ec.transaction.begin(null) }
    def cleanup() { ec.transaction.rollback("Rollback isolated test data", null); ec.artifactExecution.enableAuthz() }

    @Unroll
    def "empty filter for #fieldName with value #value and negation #negated returns #expected"() {
        given:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"JM_EMPTY", (fieldName):value]).create()
        Map params = [testId:"JM_EMPTY", (fieldName + "_op"):"empty"]
        if (negated) params[fieldName + "_not"] = "Y"
        when:
        def find = ec.entity.find("moqui.test.TestEntity").searchFormMap(params, null, null, null, false)
        then:
        find.count() == expected
        find.list().size() == expected
        where:
        fieldName | value | negated | expected
        "testDateTime" | null | false | 1
        "testDateTime" | null | true | 0
        "testDateTime" | new Timestamp(1700000000000L) | false | 0
        "testDateTime" | new Timestamp(1700000000000L) | true | 1
        "testDate" | null | false | 1
        "testDate" | null | true | 0
        "testDate" | java.sql.Date.valueOf("2026-10-24") | false | 0
        "testDate" | java.sql.Date.valueOf("2026-10-24") | true | 1
        "testTime" | null | false | 1
        "testTime" | null | true | 0
        "testTime" | java.sql.Time.valueOf("12:00:00") | false | 0
        "testTime" | java.sql.Time.valueOf("12:00:00") | true | 1
        "testNumberInteger" | null | false | 1
        "testNumberInteger" | null | true | 0
        "testNumberInteger" | 0 | false | 0
        "testNumberInteger" | 0 | true | 1
        "testLong" | null | false | 1
        "testLong" | null | true | 0
        "testLong" | "" | false | 1
        "testLong" | "" | true | 0
        "testLong" | "populated" | false | 0
        "testLong" | "populated" | true | 1
    }
    @Unroll
    def "ServiceJobRun #status filters count and page the same records"() {
        given:
        ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName:"JM_EMPTY_FILTER", serviceName:"org.moqui.impl.UserServices.get#UserAccount", paused:"Y"]).create()
        def start = new Timestamp(1700000000000L)
        [
            [jobRunId:"JM_A", hasError:"N", startTime:start, endTime:start],
            [jobRunId:"JM_B", hasError:null, startTime:start, endTime:start],
            [jobRunId:"JM_C", hasError:null, startTime:start],
            [jobRunId:"JM_D", hasError:"N"],
            [jobRunId:"JM_E", hasError:"Y", startTime:start, endTime:start],
            [jobRunId:"JM_F", hasError:"Y"],
            [jobRunId:"JM_G", hasError:null, endTime:start]
        ].each { row ->
            ec.entity.makeValue("moqui.service.job.ServiceJobRun")
                    .setAll(row + [jobName:"JM_EMPTY_FILTER"]).create()
        }
        Map params = [jobName:"JM_EMPTY_FILTER", pageIndex:0, pageSize:1, orderByField:"jobRunId"] + filters
        when:
        def find = ec.entity.find("moqui.service.job.ServiceJobRun").searchFormMap(params, null, null, null, false)
        then:
        find.count() == expectedIds.size()
        find.list()*.jobRunId == expectedIds.take(1)
        ec.entity.find("moqui.service.job.ServiceJobRun")
                .searchFormMap(params + [pageIndex:1], null, null, null, false).list()*.jobRunId == expectedIds.drop(1).take(1)
        where:
        status | filters | expectedIds
        "SUCCESSFUL" | [hasError:"Y", hasError_not:"Y", startTime_op:"empty", startTime_not:"Y", endTime_op:"empty", endTime_not:"Y"] | ["JM_A", "JM_B"]
        "RUNNING" | [hasError:"Y", hasError_not:"Y", startTime_op:"empty", startTime_not:"Y", endTime_op:"empty"] | ["JM_C"]
        "TERMINATED" | [hasError:"Y", hasError_not:"Y", startTime_op:"empty"] | ["JM_D", "JM_G"]
        "FAILED" | [hasError:"Y"] | ["JM_E", "JM_F"]
    }

}
