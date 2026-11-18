package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

public class VeloTransferTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final byte[] KEY = new byte[32];

    private static VeloTransfer.Channel writer(final ByteArrayOutputStream out, final byte[] key) {
        return new VeloTransfer.Channel(
                new DataInputStream(new ByteArrayInputStream(new byte[0])), new DataOutputStream(out), key, true);
    }

    private static VeloTransfer.Channel reader(final byte[] wire, final byte[] key) {
        return new VeloTransfer.Channel(
                new DataInputStream(new ByteArrayInputStream(wire)),
                new DataOutputStream(new ByteArrayOutputStream()),
                key,
                false);
    }

    @Test
    public void framesArriveInOrder() throws IOException {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final VeloTransfer.Channel out = writer(wire, KEY);
        out.send("one".getBytes());
        out.send("two".getBytes());
        final VeloTransfer.Channel in = reader(wire.toByteArray(), KEY);
        Assert.assertEquals("one", new String(in.receive()));
        Assert.assertEquals("two", new String(in.receive()));
    }

    @Test(expected = IOException.class)
    public void anotherKeyIsRefused() throws IOException {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writer(wire, KEY).send("secret".getBytes());
        final byte[] other = new byte[32];
        other[0] = 1;
        reader(wire.toByteArray(), other).receive();
    }

    @Test(expected = IOException.class)
    public void aChangedByteIsRefused() throws IOException {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writer(wire, KEY).send("secret".getBytes());
        final byte[] bytes = wire.toByteArray();
        bytes[bytes.length - 1] ^= 1;
        reader(bytes, KEY).receive();
    }

    @Test(expected = IOException.class)
    public void aDroppedFrameIsRefused() throws IOException {
        final ByteArrayOutputStream first = new ByteArrayOutputStream();
        final VeloTransfer.Channel out = writer(first, KEY);
        out.send("one".getBytes());
        final int firstLength = first.size();
        out.send("two".getBytes());
        final byte[] all = first.toByteArray();
        final byte[] onlySecond = new byte[all.length - firstLength];
        System.arraycopy(all, firstLength, onlySecond, 0, onlySecond.length);
        reader(onlySecond, KEY).receive();
    }

    @Test
    public void codesMustBeLocalAndWellFormed() {
        final VeloTransfer.Code code = VeloTransfer.newCode("192.168.1.20", 4242);
        final VeloTransfer.Code back = VeloTransfer.parse(code.toString());
        Assert.assertNotNull(back);
        Assert.assertEquals("192.168.1.20", back.host);
        Assert.assertEquals(4242, back.port);
        Assert.assertArrayEquals(code.key, back.key);
        final String hexKey = code.toString().substring(code.toString().lastIndexOf(':') + 1);
        Assert.assertNull(VeloTransfer.parse("velo-transfer:1:8.8.8.8:4242:" + hexKey));
        Assert.assertNull(VeloTransfer.parse("velo-transfer:1:example.org:4242:" + hexKey));
        Assert.assertNull(VeloTransfer.parse("velo-transfer:1:192.168.1.20:99999:" + hexKey));
        Assert.assertNull(VeloTransfer.parse("xmpp:someone@example.org"));
        Assert.assertNull(VeloTransfer.parse(null));
    }

    @Test
    public void aPathCannotLeaveItsFolder() throws IOException {
        final File root = folder.newFolder("root");
        Assert.assertNotNull(VeloTransfer.safeChild(root, "pictures/a.jpg"));
        Assert.assertNull(VeloTransfer.safeChild(root, "../a.jpg"));
        Assert.assertNull(VeloTransfer.safeChild(root, "pictures/../../a.jpg"));
        Assert.assertNull(VeloTransfer.safeChild(root, "/etc/a"));
        Assert.assertNull(VeloTransfer.safeChild(root, "a\\..\\b"));
        Assert.assertNull(VeloTransfer.safeChild(root, ""));
    }

    @Test
    public void theSafetyCodeIsTheSameOnBothPhones() {
        final String one = VeloSafetyCode.compute("a@x", "05aa", "b@x", "05bb");
        final String two = VeloSafetyCode.compute("b@x", "05bb", "a@x", "05aa");
        Assert.assertEquals(one, two);
        Assert.assertNotEquals(one, VeloSafetyCode.compute("a@x", "05aa", "b@x", "05cc"));
        Assert.assertEquals(47, one.length());
    }
}
