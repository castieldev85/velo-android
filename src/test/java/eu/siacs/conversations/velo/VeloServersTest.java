package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

public class VeloServersTest {

    private static final String ONION = "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion";
    private static final String PIN1 = "L9l4GB0GWJY/FDfwf8ooFq4Ckp9QziZ4IP5fYY2x/5k=";
    private static final String PIN2 = "LKkegaGL8IKoRa6Ko98RZdAnqb4ujZ1bTMJhZ4944xk=";

    private static String invite(final String domain, final String onion, final String pins, final String name) {
        return new VeloServers.Server(
                        domain, onion, 5222, VeloServers.parsePins(pins), name, false)
                .toInvite();
    }

    @Test
    public void roundTrip() {
        final String text = invite("chat.example.org", ONION, PIN1 + "," + PIN2, "Studio Rossi & C.");
        Assert.assertTrue(text.startsWith("velo:invite?v=1&"));
        Assert.assertFalse("a password never travels", text.contains("password"));
        final VeloServers.Server server = VeloServers.parseInvite(text);
        Assert.assertEquals("chat.example.org", server.domain);
        Assert.assertEquals(ONION, server.onion);
        Assert.assertEquals(5222, server.port);
        Assert.assertEquals(2, server.pins.size());
        Assert.assertTrue(server.pins.contains(PIN1));
        Assert.assertTrue(server.pins.contains(PIN2));
        Assert.assertEquals("Studio Rossi & C.", server.name);
        Assert.assertFalse(server.builtIn);
    }

    @Test
    public void unencodedPlusInPinStillWorks() {
        final String text = "velo:invite?v=1&d=chat.example.org&o=" + ONION + "&p=" + PIN1 + "&n=Gruppo";
        Assert.assertTrue(VeloServers.parseInvite(text).pins.contains(PIN1));
    }

    @Test
    public void domainAndOnionAreLowercased() {
        final String text = "velo:invite?v=1&d=Chat.Example.ORG&o=" + ONION.toUpperCase().replace(".ONION", ".onion") + "&p=" + PIN1;
        final VeloServers.Server server = VeloServers.parseInvite(text);
        Assert.assertEquals("chat.example.org", server.domain);
        Assert.assertEquals(ONION, server.onion);
        Assert.assertEquals("Velo", server.name);
    }

    private static void refused(final String text) {
        try {
            VeloServers.parseInvite(text);
            Assert.fail("accepted: " + text);
        } catch (final IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void badInvitesAreRefused() {
        refused(null);
        refused("");
        refused("xmpp:anna@example.org");
        refused("https://example.org/velo:invite?v=1");
        refused("velo:invite?v=2&d=chat.example.org&o=" + ONION + "&p=" + PIN1);
        refused("velo:invite?v=1&o=" + ONION + "&p=" + PIN1);
        refused("velo:invite?v=1&d=localhost&o=" + ONION + "&p=" + PIN1);
        refused("velo:invite?v=1&d=chat.example.org&p=" + PIN1);
        refused("velo:invite?v=1&d=chat.example.org&o=abc.onion&p=" + PIN1);
        refused("velo:invite?v=1&d=chat.example.org&o=" + ONION);
        refused("velo:invite?v=1&d=chat.example.org&o=" + ONION + "&p=nonunaimpronta");
        refused("velo:invite?v=1&d=chat.example.org&o=" + ONION + "&p=" + PIN1 + "," + PIN2 + ",6kUZzgbT/G4qROkAzEd/Oxn+x50POboRxftMcRXr9Fg=,h5VsW7RJg6gNz56PH85inJO2B3Npq5Qg/Xe5l/Ph8QU=,YdA9iHtqFVPlmBcxotPd31utXadwenYbJUkg091f6kY=");
        refused("velo:invite?v=1&d=chat.example.org/../x&o=" + ONION + "&p=" + PIN1);
    }

    @Test
    public void nameIsCleaned() {
        Assert.assertEquals("Velo", VeloServers.cleanName(null));
        Assert.assertEquals("Velo", VeloServers.cleanName("   "));
        Assert.assertEquals("A B", VeloServers.cleanName("A\u0000\n‮ B"));
        Assert.assertEquals(VeloServers.MAX_NAME, VeloServers.cleanName(new String(new char[100]).replace((char) 0, (char) 120)).length());
    }

    @Test
    public void shortCodeIsStableAndOrderFree() {
        final VeloServers.Server a = VeloServers.parseInvite(invite("chat.example.org", ONION, PIN1 + "," + PIN2, "G"));
        final VeloServers.Server b = VeloServers.parseInvite(invite("chat.example.org", ONION, PIN2 + "," + PIN1, "Altro nome"));
        Assert.assertEquals(a.shortCode(), b.shortCode());
        Assert.assertTrue(a.shortCode().matches("^[0-9A-F]{4}-[0-9A-F]{4}$"));
        final VeloServers.Server c = VeloServers.parseInvite(invite("other.example.org", ONION, PIN1 + "," + PIN2, "G"));
        Assert.assertNotEquals(a.shortCode(), c.shortCode());
    }

    @Test
    public void knownServerIsNeverReplaced() {
        final VeloServers.Server good = VeloServers.parseInvite(invite("gruppo.example.net", ONION, PIN1 + "," + PIN2, "G"));
        Assert.assertTrue(VeloServers.register(good));
        Assert.assertFalse(VeloServers.conflicts(good));
        final VeloServers.Server forged = VeloServers.parseInvite(invite("gruppo.example.net", ONION, PIN1, "G"));
        Assert.assertTrue(VeloServers.conflicts(forged));
        Assert.assertFalse(VeloServers.register(forged));
        Assert.assertEquals(2, VeloServers.get("gruppo.example.net").pins.size());
        final String otherOnion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion".substring(0, 56) + ".onion";
        final VeloServers.Server moved = VeloServers.parseInvite(invite("GRUPPO.example.net", otherOnion.substring(0, 56) + ".onion", PIN1 + "," + PIN2, "G"));
        Assert.assertTrue(VeloServers.conflicts(moved));
        Assert.assertEquals(ONION, VeloServers.get("gruppo.example.net").onion);
    }

    @Test
    public void serverWithoutAccountIsForgotten() {
        // Velo 0.39.1: after the account is deleted, the reinstalled server's new invite is accepted
        final VeloServers.Server old = VeloServers.parseInvite(invite("rifatto.example.net", ONION, PIN1 + "," + PIN2, "R"));
        Assert.assertTrue(VeloServers.register(old));
        final VeloServers.Server rebuilt = VeloServers.parseInvite(invite("rifatto.example.net", ONION, PIN2, "R"));
        Assert.assertTrue(VeloServers.conflicts(rebuilt));
        VeloServers.forgetUnused(java.util.Collections.emptyList());
        Assert.assertNull(VeloServers.get("rifatto.example.net"));
        Assert.assertFalse(VeloServers.conflicts(rebuilt));
        Assert.assertTrue(VeloServers.register(rebuilt));
    }

    @Test
    public void inviteIsFoundInsidePastedText() {
        final String link = invite("incolla.example.net", ONION, PIN1, "I");
        Assert.assertEquals(link, VeloServers.findInvite("Gruppo di prova\nInvito: " + link + "\nScade il 26/11"));
        Assert.assertEquals(link, VeloServers.findInvite("  " + link + "  "));
        Assert.assertEquals("ciao", VeloServers.findInvite(" ciao "));
        Assert.assertNull(VeloServers.findInvite(null));
        Assert.assertEquals("incolla.example.net", VeloServers.parseInvite(VeloServers.findInvite("x " + link)).domain);
    }

    @Test
    public void remainingAccountKeepsItsOwnServer() {
        // Velo 0.39.3: the deleted account's keys do not stay in use for the account that remains
        final VeloServers.Server old = VeloServers.parseInvite(invite("doppio.example.net", ONION, PIN1, "D"));
        final VeloServers.Server current = VeloServers.parseInvite(invite("doppio.example.net", ONION, PIN2, "D"));
        Assert.assertTrue(VeloServers.register(old));
        final java.util.Map<String, java.util.List<VeloServers.Server>> used = new java.util.HashMap<>();
        used.put("doppio.example.net", java.util.Collections.singletonList(current));
        VeloServers.retain(used);
        Assert.assertTrue(VeloServers.sameServer(current, VeloServers.get("doppio.example.net")));
        // an account without its own record leaves the known server as it is
        used.put("doppio.example.net", java.util.Collections.emptyList());
        VeloServers.retain(used);
        Assert.assertTrue(VeloServers.sameServer(current, VeloServers.get("doppio.example.net")));
        VeloServers.retain(java.util.Collections.emptyMap());
        Assert.assertNull(VeloServers.get("doppio.example.net"));
    }

    @Test
    public void accountSelectorFallsBackToBuiltInDomain() {
        Assert.assertNull(VeloServers.accountJid("", null));
        Assert.assertEquals("anna@chat.example.org", VeloServers.accountJid("anna@chat.example.org", null).toString());
    }
}
