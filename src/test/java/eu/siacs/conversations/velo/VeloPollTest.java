package eu.siacs.conversations.velo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.junit.Test;

public class VeloPollTest {

    private static final String[] USUAL = {"❤️", "👍"};

    @Test
    public void composesQuestionAndNumberedChoices() {
        assertEquals("📊 Dove ceniamo?\n\n1️⃣ Pizza\n2️⃣ Sushi",
                VeloPoll.compose(" Dove ceniamo? ", Arrays.asList("Pizza", "", "  Sushi ", null)));
    }

    @Test
    public void needsQuestionAndTwoChoices() {
        assertNull(VeloPoll.compose("", Arrays.asList("A", "B")));
        assertNull(VeloPoll.compose("Domanda", Arrays.asList("A", " ")));
    }

    @Test
    public void reactionsOfAPollAreItsNumbers() {
        final String poll = VeloPoll.compose("Quando?", Arrays.asList("Lunedì", "Martedì", "Mercoledì"));
        assertArrayEquals(new String[] {"1️⃣", "2️⃣", "3️⃣"}, VeloPoll.reactionsFor(poll, USUAL));
    }

    @Test
    public void otherMessagesKeepTheUsualReactions() {
        assertArrayEquals(USUAL, VeloPoll.reactionsFor("ciao", USUAL));
        assertArrayEquals(USUAL, VeloPoll.reactionsFor(null, USUAL));
        assertArrayEquals(USUAL, VeloPoll.reactionsFor("📊 solo testo", USUAL));
    }
}
