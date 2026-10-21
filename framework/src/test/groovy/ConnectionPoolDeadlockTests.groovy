import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@IgnoreIf({ !Moqui.getExecutionContext().entity.getDatabaseNode('transactional').attribute('name').startsWith('mysql') })
class ConnectionPoolDeadlockTests extends Specification {
    private static final String TYPE_ID = 'TEST_POOL_DEADLOCK'
    private static final List<String> ROW_IDS = ['TEST_POOL_DEADLOCK_A', 'TEST_POOL_DEADLOCK_B']
    private static final int LATER_TRANSACTIONS = 100

    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        deleteRows()
        ec.transaction.runRequireNew(null, 'Could not create deadlock test rows') {
            ec.entity.makeValue('moqui.basic.EnumerationType').setAll([enumTypeId: TYPE_ID, description: 'Connection pool deadlock test']).create()
            ROW_IDS.each { ec.entity.makeValue('moqui.basic.Enumeration').setAll([enumId: it, enumTypeId: TYPE_ID, description: 'start']).create() }
        }
    }

    def cleanupSpec() {
        deleteRows()
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }

    def "a connection broken by a deadlock is replaced instead of failing every later transaction"() {
        when:
        int deadlockCount = lockRowsInOppositeOrder()
        Map<String, Integer> failuresByConnection = runLaterTransactions()

        then:
        deadlockCount == 1
        failuresByConnection.values().every { it <= 2 }
    }

    private int lockRowsInOppositeOrder() {
        List<Throwable> failures = Collections.synchronizedList([])
        CountDownLatch bothFirstRowsLocked = new CountDownLatch(2)
        List<Thread> threads = [ROW_IDS, ROW_IDS.reverse()].collect { List<String> lockOrder ->
            new Thread({
                ExecutionContext threadEc = Moqui.getExecutionContext()
                threadEc.artifactExecution.disableAuthz()
                try {
                    threadEc.transaction.runRequireNew(30, 'Deadlock test side failed') {
                        updateLocked(threadEc, lockOrder[0])
                        bothFirstRowsLocked.countDown()
                        bothFirstRowsLocked.await(10, TimeUnit.SECONDS)
                        updateLocked(threadEc, lockOrder[1])
                    }
                } catch (Throwable t) {
                    failures.add(t)
                } finally {
                    threadEc.destroy()
                }
            })
        }
        threads*.start()
        threads*.join(60000)
        return failures.size()
    }

    private static void updateLocked(ExecutionContext threadEc, String enumId) {
        def enumeration = threadEc.entity.find('moqui.basic.Enumeration').condition('enumId', enumId).forUpdate(true).useCache(false).one()
        enumeration.description = Thread.currentThread().name
        enumeration.update()
    }

    private Map<String, Integer> runLaterTransactions() {
        Map<String, Integer> failuresByConnection = [:]
        LATER_TRANSACTIONS.times {
            Thread worker = new Thread({
                ExecutionContext threadEc = Moqui.getExecutionContext()
                threadEc.artifactExecution.disableAuthz()
                try {
                    threadEc.transaction.runRequireNew(30, 'Read after deadlock failed') {
                        threadEc.entity.find('moqui.basic.Enumeration').condition('enumTypeId', TYPE_ID).useCache(false).list()
                    }
                } catch (Throwable t) {
                    String connection = (causeMessages(t) =~ /XAConnection@([0-9a-f]+)/).with { it.find() ? it.group(1) : 'unknown' }
                    synchronized (failuresByConnection) {
                        failuresByConnection[connection] = (failuresByConnection[connection] ?: 0) + 1
                    }
                } finally {
                    threadEc.destroy()
                }
            })
            worker.start()
            worker.join(60000)
        }
        return failuresByConnection
    }

    private static String causeMessages(Throwable t) {
        List<String> messages = []
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>())
        for (Throwable current = t; current != null && seen.add(current); current = current.cause) messages.add(String.valueOf(current.message))
        return messages.join(' ')
    }

    private void deleteRows() {
        ec.transaction.runRequireNew(null, 'Could not delete deadlock test rows') {
            ec.entity.find('moqui.basic.Enumeration').condition('enumTypeId', TYPE_ID).deleteAll()
            ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', TYPE_ID).deleteAll()
        }
    }
}
