package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

public class LogTest {

    private static void gone(final String message, final String secret) {
        final String out = Log.clean(message);
        Assert.assertFalse(out, out.contains(secret));
    }

    @Test
    public void accountsAndDevicesGo() {
        gone("alice@example.org: send ping (action=ping)", "alice");
        gone("AxolotlService (alice@example.org/phone): Received PEP device list [81, 7764]", "7764");
        Assert.assertTrue(Log.clean("alice@example.org: send ping (action=ping)").contains("send ping"));
    }

    @Test
    public void fileLinkWithItsKeyGoes() {
        final String key = "0123456789abcdef0123456789abcdef0123456789abcdef";
        gone("knownFileSize: 123, body=aesgcm://example.org:5443/upload/abc/a.pdf#" + key, key);
        gone("download aesgcm://example.org:5443/upload/abc/a.pdf#" + key + " done", key);
    }

    @Test
    public void addressesPathsAndStanzasGo() {
        gone("connecting to 10.20.30.40 port 5222", "10.20.30.40");
        gone("via abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuv.onion:5222", ".onion");
        gone("server chat.example.com says hi", "example.com");
        gone("unable to read /storage/emulated/0/Documents/Velo/secret.pdf", "secret.pdf");
        gone("<message to='alice@example.org'><body>ciao</body></message>", "ciao");
    }

    @Test
    public void exceptionKeepsClassAndLosesMessage() {
        final String out = Log.clean(new IllegalStateException("alice@example.org failed"));
        Assert.assertTrue(out, out.contains("IllegalStateException"));
        Assert.assertFalse(out, out.contains("alice"));
    }

    @Test
    public void ordinaryMessagesStayReadable() {
        Assert.assertEquals("VeloTor: pronto", Log.clean("VeloTor: pronto"));
        Assert.assertEquals("attempt 3 after 90 s", Log.clean("attempt 3 after 90 s"));
    }
}
