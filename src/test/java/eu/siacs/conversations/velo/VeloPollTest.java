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

    // Velo 0.51: poll with buttons

    @Test
    public void readsBackWhatComposeWrites() {
        final VeloPoll.Poll poll = VeloPoll.parse(
                VeloPoll.compose("Dove ceniamo?", Arrays.asList("Pizza", "Sushi", "Kebab")));
        assertEquals("Dove ceniamo?", poll.question);
        assertEquals(Arrays.asList("Pizza", "Sushi", "Kebab"), poll.options);
    }

    @Test
    public void isNoPollWithoutTwoChoicesInOrderOrWithTextAfter() {
        assertNull(VeloPoll.parse("ciao"));
        assertNull(VeloPoll.parse(null));
        assertNull(VeloPoll.parse("📊 Domanda\n\n1️⃣ Solo una"));
        assertNull(VeloPoll.parse("📊 Domanda\n\n2️⃣ A\n1️⃣ B"));
        assertNull(VeloPoll.parse("📊 Domanda\n\n1️⃣ A\n2️⃣ B\naltro testo"));
    }

    @Test
    public void numbersWithOrWithoutVariationSelectorVote() {
        assertEquals(0, VeloPoll.choiceOf("1️⃣"));
        assertEquals(1, VeloPoll.choiceOf("2⃣")); // another app, without U+FE0F
        assertEquals(-1, VeloPoll.choiceOf("❤️"));
        assertEquals(-1, VeloPoll.choiceOf(null));
    }

    @Test
    public void oneVoteOnlyAndTappingAgainTakesItBack() {
        final java.util.List<String> ours = Arrays.asList("❤️", "1️⃣");
        // a new choice replaces the old one, the heart stays
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("❤️", "2️⃣")), VeloPoll.vote(ours, 1));
        // the same choice again: vote taken back
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("❤️")), VeloPoll.vote(ours, 0));
        assertEquals(0, VeloPoll.ourVote(ours));
        assertEquals(-1, VeloPoll.ourVote(Arrays.asList("❤️")));
    }

    @Test
    public void percentOfAllVotes() {
        assertEquals(0, VeloPoll.percent(0, 0));
        assertEquals(33, VeloPoll.percent(1, 3));
        assertEquals(67, VeloPoll.percent(2, 3));
    }
}
