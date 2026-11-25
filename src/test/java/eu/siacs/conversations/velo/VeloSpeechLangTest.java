package eu.siacs.conversations.velo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VeloSpeechLangTest {

    @Test
    public void chosenLanguageWins() {
        assertEquals("fr", VeloSpeechLang.resolve("fr", "it"));
    }

    @Test
    public void autoFollowsTheApp() {
        assertEquals("es", VeloSpeechLang.resolve(VeloSpeechLang.AUTO, "es"));
        assertEquals("it", VeloSpeechLang.resolve(null, "it"));
    }

    @Test
    public void unknownAppLanguageFallsBackToEnglish() {
        assertEquals("en", VeloSpeechLang.resolve(VeloSpeechLang.AUTO, "de"));
        assertEquals("en", VeloSpeechLang.resolve("xx", ""));
    }

    @Test
    public void everyLanguageHasAKnownModel() {
        for (final String lang : VeloSpeechLang.LANGS) {
            final String dir = VeloKnownModels.speechDir(lang);
            assertNotNull(lang, dir);
            assertTrue(lang, VeloKnownModels.isSpeechDir(dir));
            assertEquals(lang, 64, VeloKnownModels.speechSha256(lang).length());
            assertTrue(lang, VeloSpeechLang.sizeMb(lang) > 0);
        }
        assertEquals(VeloSpeechLang.LANGS.length, VeloSpeechLang.names().length);
    }

    @Test
    public void noModelForOtherLanguages() {
        assertNull(VeloKnownModels.speechDir("de"));
        assertNull(VeloKnownModels.speechSha256("de"));
        assertFalse(VeloKnownModels.isSpeechDir(".unpacking"));
        assertFalse(VeloKnownModels.isSpeechDir("vosk-model-small-it-0.21"));
    }
}
