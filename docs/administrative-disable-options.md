# Preserve administrative account disable

An administratively disabled native account has `disabled=Y` and `disabledDateTime=null`.
A timed login lockout has `disabled=Y` and a timestamp. The native login realm counts failed
attempts in both cases. Above `max-failures`, the previous failed-login service unconditionally
added a timestamp, converting administrative disable into an expiring lockout.

## Options for Deepak

| | A: native service correction (recommended; implemented here) | B: component ECA hooks (alternative proposal) |
| --- | --- | --- |
| Owning change | One conditional in framework `UserServices.increment#UserAccountFailedLogins` | Pre/post-service rules in `maarg-util` for that exact native service |
| Behavior | Preserve `Y`/null; start or refresh a timestamp for enabled accounts or existing timed lockouts | Capture administrative-disable state, allow native counting, then restore the null timestamp when applicable |
| Coverage | All callers of the corrected native service | Instances with the component hooks installed |
| Release | Native framework service-resource release through the normal application packaging flow | Component release/restart; unchanged framework |
| Added moving parts | None | Two hooks, invocation-local state and account locking |
| Validation | Native login rejection and timed-lockout refresh passed locally | Not implemented or validated |

Option A uses this condition before creating or refreshing a lockout timestamp:

```groovy
successiveFailedLogins > maxFailures && (disabled != 'Y' || disabledDateTime != null)
```

It preserves the existing failure counter, threshold and temporary-lockout behavior. It does not
change service authentication, remote exposure or the account model. It does not change Java code.

If option B is selected, use `pre-service` to lock/read the account and privately capture whether it
was already `Y`/null. Let the native service increment normally. In `post-service`, restore a null
timestamp only when the captured state was administrative disable and the account remains disabled.
Keep these actions in the native service transaction. Do not trust a caller-supplied state marker or
use shared/global state. Validate it independently against the unchanged framework, including
concurrent failed attempts and administrative updates. The tests of option A do not validate B.

## Validation of option A

`framework/src/test/groovy/AdministrativeDisableRegression.groovy` is an explicitly executed
real-backend regression, not an automatically discovered shared-database test. Run it with
`groovy.ui.GroovyMain`, an isolated dev runtime, `MCP_NATIVE_REGRESSION_DB` matching the runtime's
`entity_ds_database`, and a dedicated `mcp-regression-*` account. Stop any server sharing its
transaction journal first.

The original permanent-disable case was reproduced before the correction. On 5 November 2026,
the disable-only change passed against local database `mcp_user_mdm_20261105`, using stock framework
classes and only the changed native service XML. The successful run printed
`ADMIN_DISABLE_AND_TIMED_LOCKOUT_OK`: login remained rejected with a null administrative timestamp
above the failure threshold, and an existing timed lockout continued to refresh.

Credential-retention changes are excluded from this patch and remain a separate decision.
No production or shared-UAT mutation, merge or deployment was performed.
