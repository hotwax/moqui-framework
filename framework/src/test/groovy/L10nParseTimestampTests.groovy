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

import spock.lang.*

import org.moqui.impl.context.L10nFacadeImpl
import java.sql.Timestamp
import java.time.Instant

/** parseTimestamp with explicit locale and time zone; does not need the framework initialized. */
class L10nParseTimestampTests extends Specification {
    @Shared
    L10nFacadeImpl l10n = new L10nFacadeImpl(null)

    Instant parse(String input, String format, String timeZone, Locale locale = Locale.US) {
        return l10n.parseTimestamp(input, format, locale, TimeZone.getTimeZone(timeZone))?.toInstant()
    }

    @Unroll
    def "decimal second fraction #input in #timeZone"() {
        expect:
        parse(input, null, timeZone) == Instant.parse(expected)
        parse(input, null, timeZone, Locale.FRANCE) == Instant.parse(expected)

        where:
        input | timeZone | expected
        "2026-10-14 00:22:38.38" | "UTC" | "2026-10-14T00:22:38.380Z"
        "2026-10-14 00:22:38.46" | "UTC" | "2026-10-14T00:22:38.460Z"
        "2026-10-14 00:22:38.4" | "UTC" | "2026-10-14T00:22:38.400Z"
        "2026-10-14 00:22:38.038" | "UTC" | "2026-10-14T00:22:38.038Z"
        "2026-10-14 00:22:38.380" | "UTC" | "2026-10-14T00:22:38.380Z"
        "2026-10-14 00:22:38.000001" | "UTC" | "2026-10-14T00:22:38.000001Z"
        "2026-10-14 00:22:38.123456789" | "UTC" | "2026-10-14T00:22:38.123456789Z"
        "2026-10-14 00:22:38.0" | "UTC" | "2026-10-14T00:22:38Z"
        "2026-10-14 00:22:38" | "UTC" | "2026-10-14T00:22:38Z"
        "2026-10-14 00:22:38.46" | "Asia/Kolkata" | "2026-10-13T18:52:38.460Z"
        "2026-10-14 00:22:38.46" | "America/New_York" | "2026-10-14T04:22:38.460Z"
        "2026-10-14T00:22:38.46" | "America/New_York" | "2026-10-14T04:22:38.460Z"
        "2026-10-14T00:22:38.46Z" | "Asia/Kolkata" | "2026-10-14T00:22:38.460Z"
        "2026-10-14T00:22:38.46+0000" | "Asia/Kolkata" | "2026-10-14T00:22:38.460Z"
        "2026-10-14T00:22:38.46+05:30" | "America/New_York" | "2026-10-13T18:52:38.460Z"
        "2026-10-14T00:22:38.46-0400" | "UTC" | "2026-10-14T04:22:38.460Z"
    }

    def "Timestamp toString output parses to the same instant"() {
        when:
        Timestamp original = Timestamp.from(Instant.parse("2026-10-14T00:22:38.38Z"))
        String stored = original.toString()

        then:
        stored.endsWith(".38")
        parse(stored, null, TimeZone.getDefault().getID()) == original.toInstant()
    }

    @Unroll
    def "daylight saving #input in America/New_York"() {
        expect:
        parse(input, null, "America/New_York") == (expected ? Instant.parse(expected) : null)

        where:
        input | expected
        "2026-03-08 01:59:59.46" | "2026-03-08T06:59:59.460Z"
        "2026-03-08 02:30:00.46" | null // skipped hour
        "2026-03-08 03:00:00.46" | "2026-03-08T07:00:00.460Z"
        "2026-11-01 01:30:00.46" | "2026-11-01T06:30:00.460Z" // repeated hour resolves to standard time
    }

    @Unroll
    def "other formats unchanged #input / #format"() {
        expect:
        parse(input, format, "UTC") == Instant.parse(expected)

        where:
        input | format | expected
        // an explicit SSS pattern keeps its SimpleDateFormat meaning, an integer count of milliseconds
        "2026-10-14 00:22:38.46" | "yyyy-MM-dd HH:mm:ss.SSS" | "2026-10-14T00:22:38.046Z"
        "14/10/2026 00:22:38" | "dd/MM/yyyy HH:mm:ss" | "2026-10-14T00:22:38Z"
        "2026-10-14 00:22:38.46" | "dd/MM/yyyy" | "2026-10-14T00:22:38.460Z"
        "2026-10-14" | null | "2026-10-14T00:00:00Z"
        "2026-10-14 00:22" | null | "2026-10-14T00:22:00Z"
        "1001" | null | "1970-01-01T00:00:01.001Z"
        "-1" | null | "1969-12-31T23:59:59.999Z"
        "2026-10-14 00:22:38.038 UTC" | null | "2026-10-14T00:22:38.038Z"
    }

    @Unroll
    def "invalid timestamp '#input'"() {
        expect:
        parse(input, null, "UTC") == null

        where:
        input << [null, "", " ", "garbage", "2026-02-30 00:22:38.46", "2026-13-14 00:22:38.46",
                  "2026-10-14 24:22:38.46", "2026-10-14 00:22:38.", "2026-10-14 00:22:38.1234567890",
                  "2026-10-14 00:22:38.46junk", "2026-10-14T00:22:38.46+05:30junk"]
    }
}
