package io.github.ragnorrs.mainbrain.domain.record

/**
 * Where a record came from, and what that source calls it.
 *
 * Source-qualified and deliberately **not** account-qualified. One message fetched by five
 * sessions has one native identifier; putting the account into the key would multiply the
 * corpus and over-count every history. Which session observed a record is provenance, and
 * there may be several.
 *
 * The native identifier has to be unique **within its source**, which is a requirement on
 * whichever adapter mints it rather than something this type can check. It matters most where
 * a platform numbers per container: a chat platform's message numbering restarts in every
 * chat, so an adapter handing over the bare per-chat number would fuse unrelated messages the
 * first time two chats reused a number. Qualifying it is the adapter's job.
 */
data class ExternalId(val source: Source, val nativeId: String) {
    init {
        require(nativeId.isNotBlank()) { "A native identifier cannot be blank: it is half of the record's key." }
    }
}

/**
 * The sources this build reads.
 *
 * One value, because one source is in scope. Later workstreams add a constant each and
 * nothing else — the point of listing them rather than carrying a string is that a
 * misspelling cannot silently fork the corpus into two sources that look like one.
 */
enum class Source {
    TELEGRAM,
}
