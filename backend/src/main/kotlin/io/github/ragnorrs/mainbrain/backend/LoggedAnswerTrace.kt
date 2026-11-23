package io.github.ragnorrs.mainbrain.backend

import io.github.ragnorrs.mainbrain.domain.answer.AnswerTrace
import io.github.ragnorrs.mainbrain.domain.answer.Attempted
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

/**
 * Writes each attempt to the log, as one line the evaluation set reads back.
 *
 * **The log is the observation point, and that is a deliberate choice with a cost.** The
 * alternative was the retrieved set on the response, and the line is drawn at what an interface
 * could ever use. A citation is something a reader can be taken to, so it travels on the wire;
 * what a request retrieved and did not use, and which attempt produced it, are facts about how
 * the machinery ran, and a wire format carrying those would be answering a question nobody on the
 * wire asked. The cost is that this format is load-bearing for a gate while looking like a log
 * line, so [MARKER] and the six keys in [line] are a contract with `eval/` rather than a message
 * for a person to read. [LoggedAnswerTraceTest] pins them for that reason.
 *
 * **JSON, and built rather than serialised.** The suite has to parse it, and a format assembled
 * from separators would break on the first native identifier that contained one — a Telegram
 * identifier is two numbers and a separator already. Built through [buildJsonObject] because a
 * `@Serializable` class declared here would be a wire type outside the wire format, which is the
 * rule this module's dependencies are chosen to keep.
 *
 * **Citations only, never the answer.** [Attempted] carries no composed text, so there is nothing
 * here that could put a drafted reply in a log file. What it does carry names material in the
 * corpus — or, where a citation named none, the number or the text the model wrote in its place,
 * bounded per citation by the runner that builds it — which is the minimum a grounding verdict can
 * be checked from. A citation written as words is the evidence a claim could not be checked, not a
 * piece of the reply: the unmarked-line rule keeps a drafted sentence carrying the marker out of
 * here by leaving it as prose.
 *
 * At `info` rather than `debug`: the default level is what a deployment runs at, and a gate whose
 * input has to be switched on is one that measures nothing on the run nobody remembered to
 * configure.
 */
internal class LoggedAnswerTrace : AnswerTrace {

    private val log = LoggerFactory.getLogger(LoggedAnswerTrace::class.java)

    // Through a placeholder rather than as the format string itself: the payload is a JSON
    // object, and a message handed to the facade verbatim would have its own braces read as
    // substitutions.
    override fun attempted(attempted: Attempted) = log.info("{}", line(attempted))

    internal companion object {

        /**
         * What `eval/` finds the line by.
         *
         * Hyphenated and lower-case so that it cannot collide with a logger name or a sentence in
         * another message. Changing it breaks the evaluation set, which is why it is named once.
         */
        const val MARKER = "answer-trace"

        /**
         * [attempted] as the one line that is written.
         *
         * Separate from writing it so that the format is assertable without capturing a logger's
         * output. What the gate depends on is this string; where it goes is slf4j's business.
         */
        fun line(attempted: Attempted): String {
            val payload = buildJsonObject {
                put("shape", JsonPrimitive(attempted.shape.value))
                put("attempt", JsonPrimitive(attempted.ordinal))
                put("retrieved", attempted.retrieved.asJson())
                put("cited", attempted.cited.asJson())
                put("ungrounded", attempted.ungrounded.asJson())
                put("survived", attempted.survived.asJson())
            }
            return "$MARKER $payload"
        }

        private fun List<String>.asJson() = JsonArray(map { JsonPrimitive(it) })
    }
}
