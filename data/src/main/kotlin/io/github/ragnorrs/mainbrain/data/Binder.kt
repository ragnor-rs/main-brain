package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.entity.EntityId
import io.github.ragnorrs.mainbrain.domain.record.ExternalId
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Binds a statement's parameters in order, counting for you.
 *
 * JDBC numbers its placeholders from one, and the alternative to this is every call site
 * hand-counting them. That is a mistake waiting to happen rather than a style preference: an
 * external identifier occupies *two* placeholders, so inserting one column at the front of a
 * statement shifts every index after it, and the result is not a compile error — it is a value
 * written into the wrong column, or a silent type coercion. Binding positionally in
 * declaration order removes the whole class of it.
 */
internal class Binder(private val statement: PreparedStatement) {

    private var index = 0

    fun string(value: String?) = statement.setString(++index, value)

    fun long(value: Long) = statement.setLong(++index, value)

    fun int(value: Int) = statement.setInt(++index, value)

    fun boolean(value: Boolean) = statement.setBoolean(++index, value)

    /**
     * Binds a flag that may be *unknown*, where the column's null is a third state.
     *
     * Separate from [boolean] rather than widening it. A column where false is an answer and one
     * where null means nobody has established the value are different things, and a single binder
     * taking either would let a value forgotten at a call site reach the second kind as *nothing is
     * known* without anything objecting.
     */
    fun booleanOrUnknown(value: Boolean?) {
        if (value == null) statement.setNull(++index, Types.BOOLEAN) else statement.setBoolean(++index, value)
    }

    fun double(value: Double) = statement.setDouble(++index, value)

    /**
     * Binds a list of strings as `text[]`.
     *
     * The array is built by the connection rather than handed over as a Kotlin list, because
     * JDBC has no mapping from one to the other -- `setObject` with a list reaches the driver as
     * an object it refuses. Taken from the statement's own connection so that this needs no
     * second parameter, and so the array cannot be built against a connection other than the one
     * about to bind it.
     */
    fun strings(values: List<String>) =
        statement.setArray(++index, statement.connection.createArrayOf("text", values.toTypedArray()))

    /**
     * Binds an instant as `timestamptz`.
     *
     * Through [OffsetDateTime] at UTC rather than `setTimestamp`, which converts using the
     * virtual machine's default zone. The stored value would be the same instant either way;
     * this one does not depend on the container's clock settings to be readable.
     */
    fun instant(value: Instant?) {
        if (value == null) {
            statement.setNull(++index, Types.TIMESTAMP_WITH_TIMEZONE)
        } else {
            statement.setObject(++index, value.atOffset(ZoneOffset.UTC))
        }
    }

    /**
     * Two placeholders: an external identifier is a pair of columns.
     *
     * Nullable, because an absent reference — a conversation with no parent, a message with no
     * author — is stored as the pair of nulls a `CHECK` constraint keeps whole. One function
     * rather than an overload per nullability: on the virtual machine the two would erase to
     * the same signature.
     */
    fun externalId(value: ExternalId?) {
        string(value?.source?.name)
        string(value?.nativeId)
    }

    fun table(value: RecordTable) = string(value.table)

    /**
     * Binds an entity identifier that may be absent, as the text its column casts from.
     *
     * Through text rather than [uuid] because there is no nullable form of `setObject` worth
     * reaching for here: a null uuid is bound the same way every other absent value in this class
     * is, and the statement carries the `?::uuid` cast. The one obligation that comes with it is
     * that the cast is actually written — without it the comparison is text against uuid, which
     * Postgres refuses rather than coerces.
     */
    fun entityId(value: EntityId?) = string(value?.value?.toString())

    /**
     * Binds a UUID as `uuid`.
     *
     * Through the driver rather than as text with a cast, which the vector binders below do: the
     * driver has a mapping for this one, so there is nothing to be gained by writing the cast
     * into every statement that holds an entity identifier.
     */
    fun uuid(value: UUID) = statement.setObject(++index, value)

    /**
     * [value] as a count of whole seconds, for a statement that casts it to an interval.
     *
     * **Seconds and not the duration's own text.** A `Duration` renders as an ISO-8601 period and
     * Postgres does accept that spelling, so binding the string appears to work — until a duration
     * of days renders as one and the two spellings of a month disagree about how long it is. A
     * count of seconds has one meaning, and the statement says what to make of it.
     */
    fun seconds(value: Duration) = long(value.seconds)

    /**
     * Binds a dense vector, as the text form the column casts from.
     *
     * Bound as a string with the cast written into the statement — `?::vector` — rather than
     * through a driver type. The alternative is a second Postgres dependency for a value that
     * is four characters of punctuation and a list of floats.
     */
    fun dense(value: List<Float>) = string(value.joinToString(",", "[", "]"))

    /**
     * Binds term weights as a `sparsevec`, **shifting every index up by one**.
     *
     * This is not an implementation flourish and removing it silently corrupts retrieval. A
     * model's vocabulary is numbered from zero, and `sparsevec` is numbered from one — asking
     * it for index `0` is rejected outright, so an unshifted vocabulary would fail on any text
     * containing the first token and, worse, every other term would be compared against the
     * neighbouring one. The declared width of the column is the vocabulary size, which is
     * exactly what the shifted range needs: positions `0..n-1` become `1..n`.
     *
     * The entries are written in ascending index order because `sparsevec` requires it.
     */
    fun sparse(value: Map<Int, Float>, dimensions: Int) = string(
        value.entries
            .sortedBy { it.key }
            .joinToString(",", "{", "}/$dimensions") { (index, weight) -> "${index + 1}:$weight" },
    )
}
