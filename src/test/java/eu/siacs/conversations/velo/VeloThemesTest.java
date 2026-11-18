package eu.siacs.conversations.velo;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import eu.siacs.conversations.R;

public class VeloThemesTest {

    @Test
    public void contrastFollowsWcag() {
        Assert.assertEquals(21.0, VeloThemes.contrast(0xFF000000, 0xFFFFFFFF), 0.01);
        Assert.assertEquals(1.0, VeloThemes.contrast(0xFF7C8CFF, 0xFF7C8CFF), 0.0001);
        Assert.assertEquals(4.48, VeloThemes.contrast(0xFF777777, 0xFFFFFFFF), 0.02);
    }

    @Test
    public void textOnColourIsTheMoreReadable() {
        Assert.assertEquals(VeloThemes.LIGHT_TEXT, VeloThemes.onColor(0xFF1F6FD6));
        Assert.assertEquals(VeloThemes.DARK_TEXT, VeloThemes.onColor(0xFFF2B544));
        // a light green: the old brightness threshold gave white here
        Assert.assertEquals(VeloThemes.DARK_TEXT, VeloThemes.onColor(0xFF4CC38A));
        Assert.assertEquals(VeloThemes.DARK_TEXT, VeloThemes.onColor(0xFFFFFFFF));
        Assert.assertEquals(VeloThemes.LIGHT_TEXT, VeloThemes.onColor(0xFF000000));
    }

    /** Every theme, now and later: words, bubbles and buttons must read. */
    @Test
    public void everyThemeReads() {
        for (final VeloThemes.Preset p : VeloThemes.PRESETS) {
            final int out = p.bubbleOut(null);
            Assert.assertTrue(p.key + " main colour on the page",
                    VeloThemes.contrast(p.primary, p.background) >= VeloThemes.ACCENT_CONTRAST);
            Assert.assertTrue(p.key + " text on the main colour",
                    VeloThemes.contrast(VeloThemes.onColor(p.primary), p.primary) >= VeloThemes.TEXT_CONTRAST);
            Assert.assertTrue(p.key + " text in sent bubbles",
                    VeloThemes.contrast(VeloThemes.onColor(out), out) >= VeloThemes.TEXT_CONTRAST);
            Assert.assertTrue(p.key + " text in received bubbles",
                    VeloThemes.contrast(VeloThemes.onColor(p.bubbleIn), p.bubbleIn) >= VeloThemes.TEXT_CONTRAST);
            Assert.assertTrue(p.key + " text on the page",
                    VeloThemes.contrast(VeloThemes.onColor(p.background), p.background) >= VeloThemes.TEXT_CONTRAST);
            Assert.assertEquals(p.key + " night or day follows the page", p.dark,
                    VeloThemes.onColor(p.background) == VeloThemes.LIGHT_TEXT);
            Assert.assertTrue(p.key + " sent bubbles stand out from the chat",
                    VeloThemes.contrast(out, p.chatBg) >= 1.2);
            Assert.assertTrue(p.key + " received bubbles stand out from the chat",
                    VeloThemes.contrast(p.bubbleIn, p.chatBg) >= 1.2);
            final int chip = VeloThemes.dateChip(p.chatBg);
            Assert.assertTrue(p.key + " date in the chat",
                    VeloThemes.contrast(VeloThemes.LIGHT_TEXT, chip) >= VeloThemes.TEXT_CONTRAST);
            Assert.assertTrue(p.key + " date chip stands out from the chat",
                    VeloThemes.contrast(chip, p.chatBg) >= 1.2);
        }
    }

    @Test
    public void themeKeysAreUnique() {
        final java.util.Set<String> keys = new java.util.HashSet<>();
        for (final VeloThemes.Preset p : VeloThemes.PRESETS) {
            Assert.assertTrue(p.key, keys.add(p.key));
        }
    }

    @Test
    public void wallpapersHaveTheirOwnKeys() {
        final java.util.Set<String> keys = new java.util.HashSet<>(java.util.Arrays.asList(
                VeloThemes.PATTERN_PLAIN, VeloThemes.PATTERN_GRADIENT, VeloThemes.PATTERN_GLOW));
        for (final VeloThemes.Wallpaper wallpaper : VeloThemes.WALLPAPERS) {
            Assert.assertTrue(wallpaper.key, keys.add(wallpaper.key));
            Assert.assertSame(wallpaper, VeloThemes.wallpaper(wallpaper.key));
        }
        // the ones saved before 0.32.1 keep working
        Assert.assertNotNull(VeloThemes.wallpaper(VeloThemes.PATTERN_DRAWINGS));
        Assert.assertNotNull(VeloThemes.wallpaper(VeloThemes.PATTERN_DOTS));
        Assert.assertNotNull(VeloThemes.wallpaper(VeloThemes.PATTERN_WAVES));
        Assert.assertNull(VeloThemes.wallpaper(VeloThemes.PATTERN_PLAIN));
        Assert.assertNull(VeloThemes.wallpaper(null));
    }

    @Test
    public void unreadableMainColourIsShaded() {
        // white buttons on a white page (possible in Personalizza)
        final int fixed = VeloThemes.readableOn(0xFFFFFFFF, 0xFFFFFFFF, VeloThemes.ACCENT_CONTRAST);
        Assert.assertTrue(VeloThemes.contrast(fixed, 0xFFFFFFFF) >= VeloThemes.ACCENT_CONTRAST);
        // a dark blue on a night page
        final int night = VeloThemes.readableOn(0xFF1F2650, 0xFF141936, VeloThemes.ACCENT_CONTRAST);
        Assert.assertTrue(VeloThemes.contrast(night, 0xFF141936) >= VeloThemes.ACCENT_CONTRAST);
        // a readable colour is left alone
        Assert.assertEquals(0xFF1F6FD6, VeloThemes.readableOn(0xFF1F6FD6, 0xFFFFFFFF, VeloThemes.ACCENT_CONTRAST));
    }

    @Test
    public void fixContrastRepairsTheMaterialRoles() {
        final Map<Integer, Integer> colors = new HashMap<>();
        // Personalizza with a pale yellow for the buttons on a white page
        colors.put(R.color.md_theme_light_background, 0xFFFFFFFF);
        colors.put(R.color.md_theme_light_primary, 0xFFFFF59D);
        colors.put(R.color.md_theme_light_onPrimary, 0xFFFFFFFF);
        colors.put(R.color.md_theme_light_primaryContainer, 0xFFFFF59D);
        colors.put(R.color.md_theme_light_onPrimaryContainer, 0xFFFFFFFF);
        // a night theme whose buttons are dark navy, with Material's dark text on them
        colors.put(R.color.md_theme_dark_background, 0xFF0B1120);
        colors.put(R.color.md_theme_dark_tertiary, 0xFF1A2236);
        colors.put(R.color.md_theme_dark_onTertiary, 0xFF000000);
        VeloThemes.fixContrast(colors);
        final int primary = colors.get(R.color.md_theme_light_primary);
        Assert.assertTrue(VeloThemes.contrast(primary, 0xFFFFFFFF) >= VeloThemes.ACCENT_CONTRAST);
        Assert.assertTrue(VeloThemes.contrast(colors.get(R.color.md_theme_light_onPrimary), primary) >= VeloThemes.TEXT_CONTRAST);
        Assert.assertEquals(VeloThemes.DARK_TEXT, (int) colors.get(R.color.md_theme_light_onPrimaryContainer));
        final int tertiary = colors.get(R.color.md_theme_dark_tertiary);
        Assert.assertTrue(VeloThemes.contrast(tertiary, 0xFF0B1120) >= VeloThemes.ACCENT_CONTRAST);
        Assert.assertTrue(VeloThemes.contrast(colors.get(R.color.md_theme_dark_onTertiary), tertiary) >= VeloThemes.TEXT_CONTRAST);
        Assert.assertFalse(colors.containsKey(R.color.md_theme_dark_primary));
    }
}
