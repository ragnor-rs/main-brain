package io.github.ragnorrs.mainbrain.data

import io.github.ragnorrs.mainbrain.domain.record.RecordKind

/**
 * The record tables, which double as the discriminator the shared identifier table carries.
 *
 * One value rather than a table name and a separate string, so the two cannot drift apart —
 * the migration's `CHECK` constraint on `record_kind` lists exactly these names. This is also
 * the only thing interpolated into any SQL string in this module; every other value is a bound
 * parameter.
 */
internal enum class RecordTable(val table: String) {
    CONVERSATION("conversation"),
    MESSAGE("message"),
    PERSON("person"),
    DOCUMENT("document"),
    ;

    /**
     * Whether a claim arriving for this kind of record is a change to what the record *is*.
     *
     * True for a person, whose identity is scored from its claims, so one arriving may change the
     * answer and the record is owed its derivation again. False everywhere else, and each `false`
     * is a decision rather than a default:
     *
     * - A **message** is derived over by indexing and by inference, and inference is paid for in
     *   model tokens. A claim arriving changes nothing about what the message says, so re-deriving
     *   it would spend the one budget this build cannot absorb for no new reading.
     * - A **conversation**'s identity is read from its name, never from its claims, so a claim
     *   arriving cannot change whether it is a body of work.
     * - A **document** is matched on neither, as nothing derives identity from one yet.
     *
     * Named here rather than tested at the call site because it is a property of the record kind,
     * and the one place that writes claims should not be the place that knows which kinds are
     * matched on.
     */
    val claimsAreIdentity: Boolean get() = this == PERSON

    internal companion object {
        /**
         * The table a record kind lives in.
         *
         * Matched by name rather than by a `when`, so that a kind added to the domain without
         * a table here fails on the first call instead of compiling into a branch nobody
         * notices is missing.
         */
        fun of(kind: RecordKind): RecordTable = valueOf(kind.name)
    }
}
