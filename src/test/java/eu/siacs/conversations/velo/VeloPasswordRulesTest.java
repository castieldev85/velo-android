package eu.siacs.conversations.velo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VeloPasswordRulesTest {

    @Test
    public void wrongPinsWaitLongerUpToALimit() {
        assertEquals(0, VeloPinGuard.waitAfter(4));
        assertEquals(30_000, VeloPinGuard.waitAfter(5));
        assertEquals(60_000, VeloPinGuard.waitAfter(6));
        assertEquals(15 * 60_000, VeloPinGuard.waitAfter(1000));
    }

    @Test
    public void strengthMatchesTheServerMeasure() {
        assertTrue(VeloPasswordActivity.strength("Password1") < VeloPasswordActivity.MIN_STRENGTH);
        assertTrue(VeloPasswordActivity.strength("abcdefghijklmn") >= VeloPasswordActivity.MIN_STRENGTH);
        assertEquals(0, VeloPasswordActivity.strength(""), 0);
    }

    @Test
    public void proposedPasswordsAreStrong() {
        final String password = VeloPasswordActivity.generate();
        assertEquals(20, password.length());
        assertTrue(VeloPasswordActivity.strength(password) >= VeloPasswordActivity.MIN_STRENGTH);
    }

    @Test
    public void modelFilesCannotLeaveTheirFolder() {
        assertFalse(VeloKnownModels.isServerFileName("../../shared_prefs/x.xml"));
        assertFalse(VeloKnownModels.isServerFileName(".hidden"));
        assertTrue(VeloKnownModels.isServerFileName("vocab.enit.spm.gz"));
        assertFalse(
                VeloKnownModels.isTranslationFile(
                        "../../shared_prefs/x.xml",
                        "3ef0211d4ae6db21440892f180f2019fe2bfc110a330ffa9d2eca9665e4f2bc5"));
        assertFalse(VeloKnownModels.isTranslationFile("vocab.spm", "00"));
        assertTrue(
                VeloKnownModels.isTranslationFile(
                        "vocab.spm",
                        "3ef0211d4ae6db21440892f180f2019fe2bfc110a330ffa9d2eca9665e4f2bc5"));
    }
}
