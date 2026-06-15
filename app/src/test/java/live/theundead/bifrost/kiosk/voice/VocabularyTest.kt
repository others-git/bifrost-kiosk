package live.theundead.bifrost.kiosk.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the vocabulary biasing: parsing the server response
 * ([VocabularyClient.parseWords]) and building the Vosk grammar
 * ([VoskSpeechEngine.buildGrammar]). The HTTP transport itself is a thin
 * HttpURLConnection wrapper exercised on-device.
 */
class VocabularyTest {

    @Test
    fun parse_extracts_lowercased_deduped_words() {
        val body = """{"words":["Turn","OFF","the","off","Lights"]}"""
        val words = VocabularyClient.parseWords(body)
        assertEquals(listOf("turn", "off", "the", "lights"), words)
    }

    @Test
    fun parse_drops_blank_tokens() {
        val body = """{"words":["turn","  ","","lights"]}"""
        assertEquals(listOf("turn", "lights"), VocabularyClient.parseWords(body))
    }

    @Test
    fun parse_returns_null_when_no_words_key() {
        assertNull(VocabularyClient.parseWords("""{"other":[1,2]}"""))
    }

    @Test
    fun parse_returns_null_on_empty_array() {
        assertNull(VocabularyClient.parseWords("""{"words":[]}"""))
    }

    @Test
    fun parse_returns_null_on_garbage() {
        assertNull(VocabularyClient.parseWords("not json"))
    }

    @Test
    fun grammar_is_json_array_of_words_plus_unk() {
        // A custom wake word with no homophone aliases injects only its own token,
        // so the rest of the array is exactly the supplied words.
        val grammar = VoskSpeechEngine.buildGrammar(listOf("turn", "off", "lights"), "jarvis")
        val arr = JSONArray(grammar)
        val parsed = (0 until arr.length()).map { arr.getString(it) }
        assertEquals(listOf("turn", "off", "lights", "jarvis", VoskSpeechEngine.UNK_TOKEN), parsed)
    }

    @Test
    fun grammar_always_includes_unk_token() {
        val grammar = VoskSpeechEngine.buildGrammar(emptyList(), "")
        val arr = JSONArray(grammar)
        assertEquals(1, arr.length())
        assertEquals(VoskSpeechEngine.UNK_TOKEN, arr.getString(0))
    }

    @Test
    fun grammar_dedupes_words() {
        val grammar = VoskSpeechEngine.buildGrammar(listOf("lights", "lights", "off"), "")
        val arr = JSONArray(grammar)
        val parsed = (0 until arr.length()).map { arr.getString(it) }
        assertEquals(listOf("lights", "off", VoskSpeechEngine.UNK_TOKEN), parsed)
    }

    @Test
    fun grammar_injects_wake_word_homophone_tokens() {
        // The small model has no "bifrost" entry and hears it as "by frost"; the
        // grammar must contain the homophone tokens so the wake word survives the
        // constraint. The literal "bifrost" token is included too (harmless if OOV).
        val grammar = VoskSpeechEngine.buildGrammar(listOf("turn", "off", "lights"), "bifrost")
        val arr = JSONArray(grammar)
        val parsed = (0 until arr.length()).map { arr.getString(it) }
        for (token in listOf("by", "frost", "buy", "bi", "be", "bifrost")) {
            assertTrue("grammar missing wake token '$token'", parsed.contains(token))
        }
    }

    @Test
    fun grammar_does_not_duplicate_wake_tokens_already_present() {
        // If the server vocabulary already supplies a wake token, injection dedupes it.
        val grammar = VoskSpeechEngine.buildGrammar(listOf("frost", "lights"), "bifrost")
        val arr = JSONArray(grammar)
        val parsed = (0 until arr.length()).map { arr.getString(it) }
        assertEquals(1, parsed.count { it == "frost" })
    }

    @Test
    fun grammar_roundtrips_through_real_server_shape() {
        // The shape the endpoint actually returns: command keywords + names.
        val body = JSONObject().put(
            "words",
            JSONArray(listOf("bifrost", "turn", "off", "office", "lights", "bedroom", "lamp")),
        ).toString()
        val words = VocabularyClient.parseWords(body)!!
        assertTrue(words.contains("bifrost"))
        val grammar = VoskSpeechEngine.buildGrammar(words, "bifrost")
        assertTrue(grammar.contains("\"office\""))
        assertTrue(grammar.contains(VoskSpeechEngine.UNK_TOKEN))
    }
}
