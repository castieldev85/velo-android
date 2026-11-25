package eu.siacs.conversations.velo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import eu.siacs.conversations.xmpp.Jid;

public class VeloGuestTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Jid USER = Jid.of("ospite@example.org");

    private static VeloGuest.Guest guest(final long until, final boolean told, final long wipeSent) {
        return new VeloGuest.Guest("admin-uuid", USER, until, told, wipeSent);
    }

    @Test
    public void beforeTheEndTellsOnceThenWaits() {
        assertEquals(VeloGuest.Step.TELL, VeloGuest.next(guest(NOW + 1000, false, 0), NOW, false));
        assertEquals(VeloGuest.Step.NOTHING, VeloGuest.next(guest(NOW + 1000, true, 0), NOW, false));
    }

    @Test
    public void atTheEndAlwaysTriesTheWipeFirst() {
        // 0.50.1: also a guest who never got the date (tickAdmin sends it, already past)
        assertEquals(VeloGuest.Step.WIPE, VeloGuest.next(guest(NOW, true, 0), NOW, false));
        assertEquals(VeloGuest.Step.WIPE, VeloGuest.next(guest(NOW, false, 0), NOW, false));
    }

    @Test
    public void afterTheWipeWaitsADayForTheAnswer() {
        final long sent = NOW - 1000;
        assertEquals(VeloGuest.Step.NOTHING, VeloGuest.next(guest(NOW - 5000, true, sent), NOW, true));
        assertEquals(VeloGuest.Step.DELETE,
                VeloGuest.next(guest(NOW - 5000, true, NOW - VeloGuest.ANSWER_WAIT_MS), NOW, true));
    }

    @Test
    public void withTheWaitGoneTheAccountIsDeletedNeverForgotten() {
        // 0.50.1: "Smetti di aspettare", or the answer came but the delete failed: the guest is
        // still recorded, so its account is deleted on the server (before: forgotten, kept for good)
        assertEquals(VeloGuest.Step.DELETE,
                VeloGuest.next(guest(NOW - 5000, true, NOW - 1000), NOW, false));
    }

    @Test
    public void theWholeAppGoesOnlyWithNoOtherAccount() {
        assertTrue(VeloGuest.wholeApp(java.util.List.of("guest"), "guest"));
        assertTrue(VeloGuest.wholeApp(java.util.List.of(), "guest"));
        // an account added after the date came stays (0.50.1)
        assertFalse(VeloGuest.wholeApp(java.util.List.of("guest", "mine"), "guest"));
        assertFalse(VeloGuest.wholeApp(java.util.List.of("mine"), "guest"));
    }

    @Test
    public void guestRecordSurvivesStorage() {
        final VeloGuest.Guest stored = VeloGuest.Guest.decode(guest(NOW, true, 42).encode());
        assertEquals(USER, stored.user);
        assertEquals(NOW, stored.until);
        assertTrue(stored.told);
        assertEquals(42, stored.wipeSent);
        assertNull(VeloGuest.Guest.decode("rotto"));
    }

    @Test
    public void ownTimeEndsByDateOrByCountedTime() {
        assertFalse(VeloGuest.over(NOW, NOW + 1, 1));
        assertTrue(VeloGuest.over(NOW + 1, NOW + 1, 5000)); // the date came
        assertTrue(VeloGuest.over(NOW, NOW + 999_999, 0));  // the date was moved back, time is up
    }
}
