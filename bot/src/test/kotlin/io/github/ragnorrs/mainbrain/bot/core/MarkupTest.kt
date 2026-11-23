package io.github.ragnorrs.mainbrain.bot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a reply body may carry as markup, and what it must spell out instead.
 *
 * Two halves. The escaping, for text somebody else wrote: the three reserved characters are made
 * literal, because the platform rejects a message whose markup it cannot parse and an unescaped
 * character therefore means no reply at all — and only for the questions whose material happens to
 * contain one. And the vocabulary with its validator, for the one text that is *not* escaped: a
 * locale bundle carries this interface's emphasis, so what stands in for escaping it is a check.
 *
 * Small, and worth its own file because both halves fail the same way, which is silently and only
 * for some inputs.
 */
class MarkupTest {

    @Test
    fun `the three reserved characters are made literal`() {
        assertEquals("&amp;", escaped("&"))
        assertEquals("&lt;", escaped("<"))
        assertEquals("&gt;", escaped(">"))
    }

    @Test
    fun `an ampersand is not escaped twice`() {
        // The order matters and is the one way this can be written wrongly: replacing the angle
        // brackets first introduces ampersands, and an ampersand pass after them would escape
        // those — so a reader would see `&lt;` where a `<` had been.
        assertEquals("&lt;", escaped("<"))
        assertEquals("&amp;lt;", escaped("&lt;"))
    }

    @Test
    fun `markdown emphasis the model volunteered is removed rather than shown`() {
        // Measured on a real reply: the model set its section headings in Markdown bold. This
        // interface renders HTML, so the asterisks are not markup to it — they are characters,
        // and the reader was shown ten runs of them. Removed rather than translated into tags,
        // because the interface decides its own emphasis and honouring the model's would make
        // what a draft looks like depend on what the model felt like writing.
        assertEquals("1. Priorities and scope", plain("**1. Priorities and scope**"))
        assertEquals("1. Priorities and scope", plain("__1. Priorities and scope__"))
        assertEquals("Agreed: Friday. Open: the rest.", plain("**Agreed:** Friday. **Open:** the rest."))
    }

    @Test
    fun `a lone marker is left alone, because prose is full of them`() {
        // Only the paired, doubled form is removed. A single asterisk or underscore is ordinary
        // punctuation in the material this build answers from — a file name, a footnote, a
        // multiplication — and stripping those would edit the answer rather than clean it.
        assertEquals("option_1 and *urgent*", plain("option_1 and *urgent*"))
        assertEquals("2 ** 8 is 256", plain("2 ** 8 is 256"))
        assertEquals("an unclosed **run", plain("an unclosed **run"))
    }

    @Test
    fun `removal happens before escaping, so the two cannot interfere`() {
        // The order is the one way this can be written wrongly: escaping first would leave the
        // markers sitting beside entities, and a remover written against the escaped text would
        // have to know what `&amp;` is.
        assertEquals("a &amp; b", escaped(plain("**a & b**")))
    }

    @Test
    fun `text with nothing reserved comes back as it was`() {
        // Including the characters the platform's Markdown dialect reserves, which is the reason
        // this build does not use it: every one of these is ordinary in a drafted reply.
        val prose = "Shall we start at 10:00 — option_1, *urgent*, [see attachment] (100%)"
        assertEquals(prose, escaped(prose))
    }

    @Test
    fun `a realistic hostile answer survives escaping`() {
        // What this actually guards: prose about a comparison, a company with an ampersand in its
        // name, and a quoted fragment of markup.
        assertEquals(
            "Acme &amp; Co. offered &lt; 5% and wrote &lt;b&gt;urgent&lt;/b&gt;",
            escaped("Acme & Co. offered < 5% and wrote <b>urgent</b>"),
        )
    }

    @Test
    fun `emphasis wraps the text it is given`() {
        assertEquals("<b>Option 1</b>", bold("Option 1"))
        assertEquals(BOLD_OPEN + "x" + BOLD_CLOSE, bold("x"))
    }

    @Test
    fun `an empty string is not a special case`() {
        assertEquals("", escaped(""))
    }

    @Test
    fun `text a bundle may hold is found sound`() {
        // The shapes the shipped bundle actually uses: emphasis around a command, emphasis around
        // a parameter, and prose with none. A check calling any of these a fault would stop the
        // process at startup over text that is correct.
        //
        // Latin here, where `CatalogueTest` is what holds the Russian bundle itself to loading. No
        // Kotlin file in this repository carries a corpus script, test source included, and these
        // cases are about the structure of the markup rather than about any language.
        assertEquals(emptyList<String>(), markupFaults("Summary — <b>/status</b> with no name."))
        assertEquals(emptyList<String>(), markupFaults("{0}. <b>{1}</b> ({2})"))
        assertEquals(emptyList<String>(), markupFaults("Nothing suitable was found."))
        assertEquals(emptyList<String>(), markupFaults(""))
    }

    @Test
    fun `the three reserved characters are faults when a bundle spells them out`() {
        // The catalogue is the one text `escaped` never sees, so a reserved character in a bundle
        // reaches the platform as markup it cannot parse. Each is a fault on its own.
        assertEquals(1, markupFaults("Acme & Co.").size)
        assertEquals(1, markupFaults("offered > 5%").size)
        // A bare `<` is read as the start of a tag, so it is reported as the tag it is not.
        assertEquals(1, markupFaults("offered < 5%").size)
        // And the escaped forms are exactly what a bundle should carry instead.
        assertEquals(emptyList<String>(), markupFaults("Acme &amp; Co. offered &lt; 5% &gt; 2%"))
    }

    @Test
    fun `a tag outside the vocabulary is a fault, and is reported once`() {
        // Once rather than per character: the run is stepped over whole, so a single mistyped tag
        // reads as one fault and not as a fault for the bracket plus one for every letter after it.
        //
        // A tag nothing will ever render, deliberately. A tag the platform supports would stop
        // being a fault the day this interface adopted it, and the case would then pass while
        // asserting nothing — which is the shape of a test that rots silently.
        val faults = markupFaults("a <blink>contact</blink>")
        assertEquals(2, faults.size) { "Expected one fault per unknown tag, got: $faults" }
        assertTrue(faults.all { "not a tag" in it }) { faults.toString() }
    }

    @Test
    fun `a tag left open is a fault, and names what would close it`() {
        val faults = markupFaults("Option <b>{0}")
        assertEquals(1, faults.size) { faults.toString() }
        assertTrue(BOLD_CLOSE in faults.single()) {
            "A fault for an unclosed tag has to name the tag that closes it: ${faults.single()}"
        }
    }

    @Test
    fun `emphasis inside emphasis is a fault, because the splitter cannot balance it`() {
        // The platform takes this one; the cut does not. A long body reserves room for a single
        // closing tag and `pieces` asserts no piece left more than one element open, so a nested
        // entry is delivered while replies are short and lost the first time one is long enough to
        // split. Refused here because that is a fault which ships.
        val faults = markupFaults("<b>a<b>b</b></b>")
        assertTrue(faults.any { "opens inside" in it }) {
            "Nesting has to be refused by name, not left to the splitter to throw on: $faults"
        }
    }

    @Test
    fun `a closer with nothing open is a fault`() {
        val faults = markupFaults("Looked for {0}</b>")
        assertEquals(1, faults.size) { faults.toString() }
        assertTrue("closes nothing" in faults.single()) { faults.single() }
    }

    @Test
    fun `every fault in one entry is reported, not just the first`() {
        // Whoever reads a refusal is holding a bundle to fix, so the check is asked for all of it.
        val faults = markupFaults("Acme & <b>Co. offered > 5%")
        assertEquals(3, faults.size) {
            "An ampersand, a stray bracket and an unclosed tag are three faults, not one: $faults"
        }
    }

    @Test
    fun `the vocabulary is what the renderer composes and the splitter balances`() {
        // One declaration read by three things. Asserted because two literal lists would agree
        // until the day one of them was edited, and the symptom of their disagreeing is a reply
        // the platform refuses — which arrives as no reply rather than as a wrong one.
        //
        // Held as properties rather than by equality against the whole map. A tag added to this
        // interface is a legitimate change and must not fail here: what matters is that whatever
        // the vocabulary holds, every entry pairs an opener with its own closer and what `bold`
        // composes is in it. An equality assertion would make adding emphasis a test to delete.
        // Read off what each function composes rather than named, so an emphasis added beside
        // these is held to the same rule without this case being edited — which is the hole the
        // second one went through: the vocabulary is what the splitter balances and the bundle is
        // validated against, so an emphasis composed outside it is delivered until a reply is
        // long enough to cut and then not at all.
        listOf(::bold, ::italic).forEach { emphasis ->
            val composed = emphasis("x")
            val opener = composed.substringBefore("x")
            assertTrue(opener in MARKUP) { "'$opener' is composed by this interface and is not in the vocabulary" }
            assertEquals(opener + "x" + MARKUP.getValue(opener), composed) {
                "'$opener' is not closed by the closer the vocabulary pairs it with"
            }
        }
        assertEquals(BOLD_CLOSE, MARKUP[BOLD_OPEN])
        MARKUP.forEach { (opener, closer) ->
            assertTrue(opener.startsWith("<") && opener.endsWith(">")) { opener }
            assertEquals("</${opener.removePrefix("<")}", closer) {
                "'$closer' is not the closer for '$opener', so the splitter would balance a cut " +
                    "with a tag the platform does not pair with it"
            }
            assertEquals(emptyList<String>(), markupFaults(opener + "x" + closer)) {
                "the vocabulary holds a tag its own validator calls a fault: $opener"
            }
        }
    }
}
