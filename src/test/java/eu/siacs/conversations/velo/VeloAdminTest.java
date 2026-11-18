package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.forms.Data;
import im.conversations.android.xmpp.model.stanza.Iq;

public class VeloAdminTest {

    private static final Jid SERVER = Jid.ofDomain("example.org");
    private static final Jid MARIO = Jid.of("mario@example.org");

    @Test
    public void namesAreLowerCaseAndSimple() {
        Assert.assertEquals("mario", VeloAdmin.cleanName(" Mario "));
        Assert.assertEquals("anna.b-1_x", VeloAdmin.cleanName("anna.b-1_x"));
        Assert.assertNull(VeloAdmin.cleanName(""));
        Assert.assertNull(VeloAdmin.cleanName("mario rossi"));
        Assert.assertNull(VeloAdmin.cleanName("mario@example.org"));
        Assert.assertNull(VeloAdmin.cleanName(".mario"));
        Assert.assertNull(VeloAdmin.cleanName(null));
    }

    @Test
    public void eachCommandGetsTheFieldsEjabberdAsks() {
        final Map<String, List<String>> add = VeloAdmin.forUser(VeloAdmin.ADD, MARIO, "pw");
        Assert.assertEquals(List.of("mario@example.org"), add.get("accountjid"));
        Assert.assertEquals(List.of("pw"), add.get("password"));
        Assert.assertEquals(List.of("pw"), add.get("password-verify"));
        Assert.assertEquals(List.of("pw"), VeloAdmin.forUser(VeloAdmin.PASSWORD, MARIO, "pw").get("password"));
        Assert.assertEquals(List.of("mario@example.org"), VeloAdmin.forUser(VeloAdmin.KICK, MARIO, null).get("accountjid"));
        for (final String command : List.of(VeloAdmin.DISABLE, VeloAdmin.ENABLE, VeloAdmin.DELETE)) {
            final Map<String, List<String>> fields = VeloAdmin.forUser(command, Jid.of("mario@example.org/phone"), null);
            Assert.assertEquals(List.of("mario@example.org"), fields.get("accountjids"));
            Assert.assertEquals(1, fields.size());
        }
    }

    @Test
    public void completeCarriesSessionAndForm() {
        final Iq iq = VeloAdmin.complete(SERVER, VeloAdmin.DELETE, "s1", VeloAdmin.forUser(VeloAdmin.DELETE, MARIO, null));
        final Element command = iq.findChild("command", Namespace.COMMANDS);
        Assert.assertEquals(VeloAdmin.PREFIX + "delete-user", command.getAttribute("node"));
        Assert.assertEquals("complete", command.getAttribute("action"));
        Assert.assertEquals("s1", command.getAttribute("sessionid"));
        final Data data = Data.parse(command.findChild("x", Namespace.DATA));
        Assert.assertEquals("submit", data.getAttribute("type"));
        Assert.assertEquals("http://jabber.org/protocol/admin", data.getFormType());
        Assert.assertEquals(List.of("mario@example.org"), data.getFieldByName("accountjids").getValues());
        Assert.assertEquals("execute", VeloAdmin.execute(SERVER, VeloAdmin.LIST_USERS)
                .findChild("command", Namespace.COMMANDS).getAttribute("action"));
    }

    @Test
    public void listAnswerGivesSortedBareJids() {
        final Iq iq = completedAnswer();
        final Element x = iq.findChild("command", Namespace.COMMANDS).addChild("x", Namespace.DATA);
        final Element field = x.addChild("field");
        field.setAttribute("var", "registereduserjids");
        field.addChild("value").setContent("zoe@example.org");
        field.addChild("value").setContent("prova2@example.org");
        field.addChild("value").setContent("prova@example.org");
        field.addChild("value").setContent("not a jid@@");
        Assert.assertTrue(VeloAdmin.completed(iq));
        Assert.assertEquals(List.of(Jid.of("prova@example.org"), Jid.of("prova2@example.org"), Jid.of("zoe@example.org")),
                VeloAdmin.jids(iq, "registereduserjids"));
        Assert.assertTrue(VeloAdmin.jids(iq, "onlineuserjids").isEmpty());
        Assert.assertTrue(VeloAdmin.jids(new Iq(Iq.Type.ERROR), "registereduserjids").isEmpty());
    }

    @Test
    public void adminIsRecognisedFromTheCommandList() {
        final Iq items = new Iq(Iq.Type.RESULT);
        final Element query = items.addChild("query", Namespace.DISCO_ITEMS);
        query.addChild("item").setAttribute("node", "ping");
        Assert.assertFalse(VeloAdmin.offersAdmin(items));
        query.addChild("item").setAttribute("node", VeloAdmin.PREFIX + "add-user");
        Assert.assertTrue(VeloAdmin.offersAdmin(items));
        Assert.assertFalse(VeloAdmin.offersAdmin(new Iq(Iq.Type.ERROR)));
        Assert.assertFalse(VeloAdmin.offersAdmin(null));
    }

    @Test
    public void onlyACompletedResultCounts() {
        Assert.assertTrue(VeloAdmin.completed(completedAnswer()));
        final Iq executing = new Iq(Iq.Type.RESULT);
        executing.addChild("command", Namespace.COMMANDS).setAttribute("status", "executing");
        Assert.assertFalse(VeloAdmin.completed(executing));
        Assert.assertFalse(VeloAdmin.completed(new Iq(Iq.Type.ERROR)));
        Assert.assertFalse(VeloAdmin.completed(null));
    }

    private static Iq completedAnswer() {
        final Iq iq = new Iq(Iq.Type.RESULT);
        iq.addChild("command", Namespace.COMMANDS).setAttribute("status", "completed");
        return iq;
    }
}
