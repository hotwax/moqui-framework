/* Focused real-backend regression. Run explicitly with groovy.ui.GroovyMain, an isolated dev
 * runtime and MCP_NATIVE_REGRESSION_DB matching its database. No shared database is permitted.
 * Verifies permanent disable and refresh of a normal timed lockout using native services.
 */
import org.moqui.Moqui
import org.moqui.impl.context.ExecutionContextFactoryImpl

Moqui.dynamicInit(new ExecutionContextFactoryImpl())
def ec = Moqui.getExecutionContext()
int exitCode = 0
try {
    assert System.getProperty('instance_purpose') == 'dev'
    assert System.getenv('MCP_NATIVE_REGRESSION_DB')
    assert System.getProperty('entity_ds_database') == System.getenv('MCP_NATIVE_REGRESSION_DB')
    ec.artifactExecution.disableAuthz()
    def target = ec.entity.find('moqui.security.UserAccount').condition('username','like','mcp-regression-%').orderBy('-userId').one()
    assert target
    String id = target.userId
    def loginNode = ec.factory.confXmlRoot.first('user-facade').first('login')
    int maxFailures = (loginNode.attribute('max-failures') ?: '3') as int
    ec.service.sync().name('update#moqui.security.UserAccount').parameters([
        userId:id,disabled:'Y',disabledDateTime:null,successiveFailedLogins:maxFailures+1]).call()
    ec.message.clearAll()
    assert !ec.user.loginUser(target.username, 'not-a-real-credential')
    ec.message.clearAll()
    def disabled = ec.entity.find('moqui.security.UserAccount').condition('userId',id).useCache(false).one()
    assert disabled.disabled == 'Y' && disabled.disabledDateTime == null: 'Permanent disable became a timed lockout'

    // Timed lockouts must still refresh their window when attempts continue.
    def oldTime = new java.sql.Timestamp(System.currentTimeMillis() - 1000)
    ec.service.sync().name('update#moqui.security.UserAccount').parameters([
        userId:id,disabled:'Y',disabledDateTime:oldTime,successiveFailedLogins:maxFailures+1]).call()
    ec.service.sync().name('org.moqui.impl.UserServices.increment#UserAccountFailedLogins').parameter('userId',id).call()
    def timed = ec.entity.find('moqui.security.UserAccount').condition('userId',id).useCache(false).one()
    assert timed.disabledDateTime.after(oldTime): 'Timed lockout stopped refreshing'
    ec.service.sync().name('update#moqui.security.UserAccount').parameters([userId:id,disabled:'Y',disabledDateTime:null]).call()
    println 'ADMIN_DISABLE_AND_TIMED_LOCKOUT_OK'
} catch (Throwable failure) {
    System.err.println('ADMIN_DISABLE_REGRESSION_FAILED: ' + failure.message)
    exitCode = 1
} finally {
    ec.destroy()
    Moqui.destroyActiveExecutionContextFactory()
}
System.exit(exitCode)
