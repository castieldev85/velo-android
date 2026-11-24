package eu.siacs.conversations.velo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VeloAckTest {

    @Test
    public void composeMarksTheTextOnce() {
        assertEquals("📋 Riunione alle 9", VeloAck.compose("  Riunione alle 9 "));
        assertEquals("📋 già segnato", VeloAck.compose("📋 già segnato"));
        assertNull(VeloAck.compose("   "));
        assertNull(VeloAck.compose(null));
    }

    @Test
    public void onlyMarkedMessagesAskForConfirmation() {
        assertTrue(VeloAck.isRequestBody(VeloAck.compose("Leggi il regolamento")));
        assertFalse(VeloAck.isRequestBody("Leggi il regolamento"));
        assertFalse(VeloAck.isRequestBody("📋 "));
        assertFalse(VeloAck.isRequestBody(null));
    }

    @Test
    public void confirmationStartsWithTheCheck() {
        assertTrue(VeloAck.isConfirmationBody("✅ Presa visione"));
        assertTrue(VeloAck.isConfirmationBody("  ✅ Read and acknowledged"));
        assertFalse(VeloAck.isConfirmationBody("ok letto"));
        assertFalse(VeloAck.isConfirmationBody(null));
    }
}
