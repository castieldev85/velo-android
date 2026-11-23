package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;

public class RemoteWipeTest {

    private static final Jid ACCOUNT = Jid.of("mario@example.org/velo");

    @Test
    public void onlyTheOwnServersAdminMayWipe() {
        Assert.assertTrue(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("admin@example.org")));
        Assert.assertTrue(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("admin@example.org/Velo.x1")));
    }

    @Test
    public void othersMayNotWipe() {
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("luigi@example.org")));
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("admin@other.net")));
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, Jid.of("admin@example.org.evil.net")));
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, Jid.ofDomain("example.org")));
        Assert.assertFalse(RemoteWipe.isFromAdmin(ACCOUNT, null));
    }

    @Test
    public void commandsAreRecognised() {
        final Element wipe = RemoteWipe.createWipe(1234L, RemoteWipe.MODE_DELETE);
        Assert.assertTrue(RemoteWipe.is(wipe));
        Assert.assertEquals("1234", wipe.getAttribute("issued"));
        Assert.assertTrue(RemoteWipe.is(RemoteWipe.createWiped(1234L)));
        Assert.assertFalse(RemoteWipe.is(new Element("wipe", "urn:other")));
        Assert.assertFalse(RemoteWipe.is(HistoryPurge.create(1234L)));
    }

    // --- Velo 0.49.1 (W1): il wipe e' onorato solo se la chiave combacia con l'impronta admin
    // inchiodata nell'app. ---

    private static final String ADMIN_FP =
            "0512ab34cd56ef7890123456789abcdef0123456789abcdef0123456789abcdef01";

    @Test
    public void wipeHonouredOnlyForThePinnedAdminKey() {
        // La stessa impronta, anche se l'utente l'ha scritta con spazi/maiuscole, combacia.
        Assert.assertTrue(RemoteWipe.matchesAdminKey(ADMIN_FP, ADMIN_FP));
        Assert.assertTrue(RemoteWipe.matchesAdminKey(
                "0512AB34 CD56EF78 90123456 789ABCDE F0123456 789ABCDE F0123456 789ABCDE F01",
                ADMIN_FP));
    }

    @Test
    public void aForgedAdminKeyDoesNotWipe() {
        // Un solo bit diverso -> non combacia (il server non puo' falsificare la chiave).
        final String forged = ADMIN_FP.substring(0, 63) + "ff";
        Assert.assertFalse(RemoteWipe.matchesAdminKey(ADMIN_FP, forged));
        Assert.assertFalse(RemoteWipe.matchesAdminKey(ADMIN_FP, null));
    }

    @Test
    public void noPinnedAdminKeyMeansNoRemoteWipe() {
        // Build pubblica / nessuna impronta inchiodata -> il wipe a distanza e' disattivato.
        Assert.assertFalse(RemoteWipe.matchesAdminKey(null, ADMIN_FP));
        Assert.assertFalse(RemoteWipe.matchesAdminKey("", ADMIN_FP));
        Assert.assertFalse(RemoteWipe.matchesAdminKey("   ", ADMIN_FP));
        // E la via che legge davvero Config (nel test non c'e' pin) deve rifiutare.
        Assert.assertFalse(RemoteWipe.isFromPinnedAdminKey(ADMIN_FP));
    }

    @Test
    public void severalAdminDevicesMayBePinned() {
        final String other =
                "05aa11bb22cc33dd44ee55ff66778899aabbccddeeff00112233445566778899aa";
        final String list = ADMIN_FP + "," + other;
        Assert.assertTrue(RemoteWipe.matchesAdminKey(list, ADMIN_FP));
        Assert.assertTrue(RemoteWipe.matchesAdminKey(list, other));
        Assert.assertEquals(2, RemoteWipe.parseAdminKeys(list).size());
    }

    @Test
    public void fingerprintNormalisationIsForgiving() {
        Assert.assertEquals("05ab", RemoteWipe.normalizeFingerprint("05:AB"));
        Assert.assertEquals("05ab", RemoteWipe.normalizeFingerprint(" 05 ab "));
        Assert.assertNull(RemoteWipe.normalizeFingerprint("ghij")); // nessuna cifra esadecimale
        Assert.assertNull(RemoteWipe.normalizeFingerprint(null));
    }
}
