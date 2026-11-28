package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

public class VeloIntroTest {

    @Test
    public void dotsMarkTheCurrentCard() {
        Assert.assertEquals("● ○ ○", VeloIntro.dots(0));
        Assert.assertEquals("○ ● ○", VeloIntro.dots(1));
        Assert.assertEquals("○ ○ ●", VeloIntro.dots(2));
    }
}
