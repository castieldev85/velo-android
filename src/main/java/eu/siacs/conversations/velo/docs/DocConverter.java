package eu.siacs.conversations.velo.docs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Xml;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import eu.siacs.conversations.R;

/**
 * Velo: turns a document into plain HTML, inside the isolated process {@link DocService}. A received
 * file may be built on purpose to attack the parser, so nothing here touches the network, the chats
 * or the keys, and everything has a size limit. Every piece of text is escaped and every image is
 * decoded and encoded again, so the page contains only our own tags.
 */
final class DocConverter {

    static final int OK = 0;
    static final int UNSUPPORTED = 1;
    static final int PROTECTED = 2;
    static final int TOO_BIG = 3;
    static final int UNREADABLE = 4;
    static final int IS_PDF = 5;

    static final class Failure extends Exception {
        final int status;

        Failure(final int status) {
            this.status = status;
        }
    }

    private static final int MAX_HTML_CHARS = 32 * 1024 * 1024;
    private static final int MAX_IMAGES_CHARS = 20 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;
    private static final int MAX_ROWS = 5_000;
    private static final int MAX_COLUMNS = 200;
    private static final int MAX_IMAGE_SIDE = 1600;
    private static final Set<String> TEXT_EXTENSIONS =
            new HashSet<>(
                    Arrays.asList(
                            "txt", "text", "log", "md", "csv", "tsv", "json", "xml", "ini", "cfg",
                            "conf", "yml", "yaml", "html", "htm", "vcf", "ics", "java", "kt", "c",
                            "h", "cpp", "py", "sh", "js", "css", "sql", "srt"));

    private final Context context;
    private final StringBuilder out = new StringBuilder(64 * 1024);
    private int imageChars = 0;

    private DocConverter(final Context context) {
        this.context = context;
    }

    /** The document as HTML, or a {@link Failure} that says why not. */
    static String convert(
            final Context context, final InputStream input, final String name, final String mime)
            throws Failure {
        try {
            return new DocConverter(context).run(input, name == null ? "" : name, mime);
        } catch (final OutOfMemoryError e) {
            throw new Failure(TOO_BIG);
        } catch (final Failure e) {
            throw e;
        } catch (final org.apache.poi.EncryptedDocumentException e) {
            throw new Failure(PROTECTED);
        } catch (final Exception | LinkageError e) {
            throw new Failure(UNREADABLE);
        }
    }

    private String run(final InputStream input, final String name, final String mime)
            throws Exception {
        final byte[] head = new byte[8192];
        int headLength = 0;
        while (headLength < head.length) {
            final int read = input.read(head, headLength, head.length - headLength);
            if (read < 0) {
                break;
            }
            headLength += read;
        }
        final InputStream all =
                new java.io.SequenceInputStream(
                        new ByteArrayInputStream(head, 0, headLength), input);
        if (startsWith(head, headLength, 'P', 'K', 3, 4)) {
            zipDocument(unzip(all));
        } else if (startsWith(head, headLength, 0xD0, 0xCF, 0x11, 0xE0)) {
            oleDocument(all);
        } else if (isPdf(head, headLength)) {
            throw new Failure(IS_PDF);
        } else if (startsWith(head, headLength, '{', '\\', 'r', 't')
                || !isText(head, headLength, name, mime)) {
            throw new Failure(UNSUPPORTED);
        } else {
            text(all, name);
        }
        return out.toString();
    }

    private static boolean startsWith(final byte[] data, final int length, final int... magic) {
        if (length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; ++i) {
            if ((data[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPdf(final byte[] head, final int length) {
        return new String(head, 0, Math.min(length, 1024), StandardCharsets.ISO_8859_1)
                .contains("%PDF-");
    }

    private static boolean isText(
            final byte[] head, final int length, final String name, final String mime) {
        final int dot = name.lastIndexOf('.');
        final String extension =
                dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        final boolean declared =
                TEXT_EXTENSIONS.contains(extension) || (mime != null && mime.startsWith("text/"));
        final boolean utf16 =
                startsWith(head, length, 0xFF, 0xFE) || startsWith(head, length, 0xFE, 0xFF);
        if (utf16) {
            return declared;
        }
        for (int i = 0; i < length; ++i) {
            final int c = head[i] & 0xFF;
            if (c < 9 || (c > 13 && c < 32 && c != 27)) {
                return false;
            }
        }
        return declared || length > 0;
    }

    // --- output ---

    private void raw(final String html) throws Failure {
        if (out.length() + html.length() > MAX_HTML_CHARS) {
            throw new Failure(TOO_BIG);
        }
        out.append(html);
    }

    private void text(final String text) throws Failure {
        raw(escape(text));
    }

    private static String escape(final String text) {
        final StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); ++i) {
            final char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> {
                    if (c >= 32 || c == '\n' || c == '\t') {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /** The colour of a piece of text, only if it is really a colour: it ends up in a style. */
    private static String safeColour(final String value) {
        if (value == null) {
            return null;
        }
        final String hex = value.startsWith("#") ? value.substring(1) : value;
        return hex.matches("[0-9a-fA-F]{6}") ? "#" + hex : null;
    }

    private void note(final int stringId) throws Failure {
        raw("<p class=\"note\">");
        text(context.getString(stringId));
        raw("</p>");
    }

    /** Decodes and encodes the image again: other formats and hidden data don't reach the page. */
    private void image(final byte[] data) throws Failure {
        if (data == null || !appendImage(data)) {
            note(R.string.velo_doc_image_omitted);
        }
    }

    private boolean appendImage(final byte[] data) throws Failure {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return false;
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        final int side = Math.max(bounds.outWidth, bounds.outHeight);
        options.inSampleSize = 1;
        while (side / options.inSampleSize > MAX_IMAGE_SIDE) {
            options.inSampleSize *= 2;
        }
        final Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (final OutOfMemoryError e) {
            return false;
        }
        if (bitmap == null) {
            return false;
        }
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        final boolean alpha = bitmap.hasAlpha();
        bitmap.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 80, encoded);
        bitmap.recycle();
        final String base64 = Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP);
        if (imageChars + base64.length() > MAX_IMAGES_CHARS) {
            return false;
        }
        imageChars += base64.length();
        raw("<img alt=\"\" src=\"data:image/" + (alpha ? "png" : "jpeg") + ";base64,");
        raw(base64);
        raw("\">");
        return true;
    }

    // --- zip based formats ---

    private static Map<String, byte[]> unzip(final InputStream input) throws IOException, Failure {
        final long budget =
                Math.min(Runtime.getRuntime().maxMemory() / 2, 256L * 1024 * 1024);
        final Map<String, byte[]> files = new HashMap<>();
        long total = 0;
        final byte[] buffer = new byte[64 * 1024];
        try (final ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (files.size() >= MAX_ENTRIES) {
                    throw new Failure(TOO_BIG);
                }
                final ByteArrayOutputStream content = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    total += read;
                    if (total > budget) {
                        throw new Failure(TOO_BIG);
                    }
                    content.write(buffer, 0, read);
                }
                files.put(entry.getName(), content.toByteArray());
            }
        }
        return files;
    }

    private void zipDocument(final Map<String, byte[]> zip) throws Exception {
        if (zip.containsKey("word/document.xml")) {
            new Word(zip).run();
        } else if (zip.containsKey("xl/workbook.xml")) {
            new Excel(zip).run();
        } else if (zip.containsKey("ppt/presentation.xml")) {
            new PowerPoint(zip).run();
        } else if (zip.containsKey("content.xml")
                && new String(
                                zip.getOrDefault("mimetype", new byte[0]),
                                StandardCharsets.US_ASCII)
                        .startsWith("application/vnd.oasis.opendocument")) {
            new OpenDocument(zip).run();
        } else {
            throw new Failure(UNSUPPORTED);
        }
    }

    /** A parser without entity expansion: a DOCTYPE has no place in these formats. */
    private static XmlPullParser parser(final byte[] xml) throws Exception {
        if (xml == null) {
            throw new Failure(UNREADABLE);
        }
        final String doctype = "<!DOCTYPE";
        final byte[] marker = doctype.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= xml.length - marker.length; ++i) {
            for (int j = 0; j < marker.length; ++j) {
                if (xml[i + j] != marker[j]) {
                    continue outer;
                }
            }
            throw new Failure(UNREADABLE);
        }
        final XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new ByteArrayInputStream(xml), null);
        return parser;
    }

    private static String attribute(final XmlPullParser parser, final String localName) {
        for (int i = 0; i < parser.getAttributeCount(); ++i) {
            if (localName.equals(parser.getAttributeName(i))) {
                return parser.getAttributeValue(i);
            }
        }
        return null;
    }

    /** r:id, not the plain id that some elements (slides, for one) have as well. */
    private static String relationshipId(final XmlPullParser parser) {
        for (int i = 0; i < parser.getAttributeCount(); ++i) {
            if ("id".equals(parser.getAttributeName(i))
                    && parser.getAttributeNamespace(i).contains("relationships")) {
                return parser.getAttributeValue(i);
            }
        }
        return null;
    }

    private static boolean on(final String value) {
        return value == null || !(value.equals("0") || value.equalsIgnoreCase("false")
                || value.equalsIgnoreCase("none"));
    }

    private static int parseInt(final String value, final int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    private static void skip(final XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = parser.next();
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            } else if (event == XmlPullParser.END_DOCUMENT) {
                return;
            }
        }
    }

    /** relationship id -> path inside the zip, from the .rels file next to {@code part}. */
    private static Map<String, String> relationships(
            final Map<String, byte[]> zip, final String part) throws Exception {
        final Map<String, String> relationships = new HashMap<>();
        final int slash = part.lastIndexOf('/');
        final String folder = slash < 0 ? "" : part.substring(0, slash + 1);
        final byte[] rels = zip.get(folder + "_rels/" + part.substring(slash + 1) + ".rels");
        if (rels == null) {
            return relationships;
        }
        final XmlPullParser parser = parser(rels);
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.START_TAG
                    && "Relationship".equals(parser.getName())
                    && !"External".equals(attribute(parser, "TargetMode"))) {
                final String id = attribute(parser, "Id");
                final String target = attribute(parser, "Target");
                if (id != null && target != null) {
                    relationships.put(id, resolve(folder, target));
                }
            }
        }
        return relationships;
    }

    private static String resolve(final String folder, final String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        final Deque<String> parts = new ArrayDeque<>();
        for (final String piece : (folder + target).split("/")) {
            if (piece.equals("..")) {
                parts.pollLast();
            } else if (!piece.isEmpty() && !piece.equals(".")) {
                parts.addLast(piece);
            }
        }
        return String.join("/", parts);
    }

    private static String runStyle(
            final boolean bold,
            final boolean italic,
            final boolean underline,
            final boolean strike,
            final String colour) {
        final StringBuilder style = new StringBuilder();
        if (bold) {
            style.append("font-weight:bold;");
        }
        if (italic) {
            style.append("font-style:italic;");
        }
        if (underline || strike) {
            style.append("text-decoration:");
            style.append(underline ? "underline " : "");
            style.append(strike ? "line-through" : "");
            style.append(';');
        }
        if (colour != null) {
            style.append("color:").append(colour).append(';');
        }
        return style.toString();
    }

    private void styledText(final String text, final String style) throws Failure {
        if (style.isEmpty()) {
            text(text);
        } else {
            raw("<span style=\"" + style + "\">");
            text(text);
            raw("</span>");
        }
    }

    // --- docx ---

    private final class Word {
        private final Map<String, byte[]> zip;
        private final Map<String, Integer> headings = new HashMap<>();
        private final Map<String, String> styleNumbering = new HashMap<>();
        private final Map<String, String> numIdToAbstract = new HashMap<>();
        private final Map<String, String> levelFormats = new HashMap<>();
        private final Map<String, int[]> counters = new HashMap<>();
        private Map<String, String> relationships;

        Word(final Map<String, byte[]> zip) {
            this.zip = zip;
        }

        void run() throws Exception {
            relationships = relationships(zip, "word/document.xml");
            readStyles();
            readNumbering();
            final XmlPullParser parser = parser(zip.get("word/document.xml"));
            final Deque<Paragraph_> paragraphs = new ArrayDeque<>();
            final Deque<int[]> cells = new ArrayDeque<>();
            boolean inRun = false;
            boolean bold = false, italic = false, underline = false, strike = false;
            String colour = null;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                final String tag = parser.getName();
                if (event == XmlPullParser.START_TAG) {
                    switch (tag) {
                        case "Fallback" -> skip(parser);
                        case "p" -> {
                            openCell(cells);
                            paragraphs.push(new Paragraph_(out.length()));
                            raw("<div class=\"p\">");
                        }
                        case "pStyle" -> {
                            if (!paragraphs.isEmpty()) {
                                final String style = attribute(parser, "val");
                                final Integer level = headings.get(style);
                                if (level != null) {
                                    paragraphs.peek().heading = level;
                                }
                                paragraphs.peek().styleNumId = styleNumbering.get(style);
                            }
                        }
                        case "jc" -> {
                            if (!paragraphs.isEmpty() && !inRun) {
                                final String value = attribute(parser, "val");
                                if ("center".equals(value) || "right".equals(value)) {
                                    paragraphs.peek().align = value;
                                }
                            }
                        }
                        case "numId" -> {
                            if (!paragraphs.isEmpty()) {
                                paragraphs.peek().numId = attribute(parser, "val");
                            }
                        }
                        case "ilvl" -> {
                            if (!paragraphs.isEmpty()) {
                                paragraphs.peek().level = parseInt(attribute(parser, "val"), 0);
                            }
                        }
                        case "r" -> {
                            inRun = true;
                            bold = italic = underline = strike = false;
                            colour = null;
                            if (!paragraphs.isEmpty()) {
                                raw(paragraphs.peek().takeMarker(this));
                            }
                        }
                        case "b" -> {
                            if (inRun) bold = on(attribute(parser, "val"));
                        }
                        case "i" -> {
                            if (inRun) italic = on(attribute(parser, "val"));
                        }
                        case "u" -> {
                            if (inRun) underline = on(attribute(parser, "val"));
                        }
                        case "strike" -> {
                            if (inRun) strike = on(attribute(parser, "val"));
                        }
                        case "color" -> {
                            if (inRun) colour = safeColour(attribute(parser, "val"));
                        }
                        case "t" -> {
                            if (inRun) {
                                styledText(
                                        parser.nextText(),
                                        runStyle(bold, italic, underline, strike, colour));
                            }
                        }
                        case "tab" -> {
                            if (inRun) raw("&emsp;");
                        }
                        case "br" -> {
                            if (inRun) raw("<br>");
                        }
                        case "blip" -> embedded(attribute(parser, "embed"));
                        case "imagedata" -> embedded(attribute(parser, "id"));
                        case "tbl" -> {
                            openCell(cells);
                            raw("<div class=\"scroll\"><table>");
                        }
                        case "tr" -> raw("<tr>");
                        case "tc" -> cells.push(new int[] {1, 0});
                        case "gridSpan" -> {
                            if (!cells.isEmpty()) {
                                cells.peek()[0] =
                                        Math.max(
                                                1,
                                                Math.min(
                                                        MAX_COLUMNS,
                                                        parseInt(attribute(parser, "val"), 1)));
                            }
                        }
                        default -> {}
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    switch (tag) {
                        case "r" -> inRun = false;
                        case "p" -> {
                            if (!paragraphs.isEmpty()) {
                                paragraphs.pop().close(this);
                            }
                        }
                        case "tc" -> {
                            openCell(cells);
                            cells.pop();
                            raw("</td>");
                        }
                        case "tr" -> raw("</tr>");
                        case "tbl" -> raw("</table></div>");
                        default -> {}
                    }
                }
            }
        }

        private void openCell(final Deque<int[]> cells) throws Failure {
            if (!cells.isEmpty() && cells.peek()[1] == 0) {
                cells.peek()[1] = 1;
                raw("<td colspan=\"" + cells.peek()[0] + "\">");
            }
        }

        private void embedded(final String id) throws Failure {
            if (id != null) {
                final String path = relationships.get(id);
                image(path == null ? null : zip.get(path));
            }
        }

        private void readStyles() throws Exception {
            final byte[] styles = zip.get("word/styles.xml");
            if (styles == null) {
                return;
            }
            final XmlPullParser parser = parser(styles);
            String styleId = null;
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                if ("style".equals(parser.getName())) {
                    styleId = attribute(parser, "styleId");
                } else if ("numId".equals(parser.getName()) && styleId != null) {
                    styleNumbering.put(styleId, attribute(parser, "val"));
                } else if ("name".equals(parser.getName()) && styleId != null) {
                    final String name = attribute(parser, "val");
                    if (name == null) {
                        continue;
                    }
                    final String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.equals("title")) {
                        headings.put(styleId, 1);
                    } else if (lower.matches("heading [1-6]")) {
                        headings.put(styleId, lower.charAt(lower.length() - 1) - '0');
                    }
                }
            }
        }

        private void readNumbering() throws Exception {
            final byte[] numbering = zip.get("word/numbering.xml");
            if (numbering == null) {
                return;
            }
            final XmlPullParser parser = parser(numbering);
            String abstractId = null;
            String level = null;
            String numId = null;
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                switch (parser.getName()) {
                    case "abstractNum" -> abstractId = attribute(parser, "abstractNumId");
                    case "lvl" -> level = attribute(parser, "ilvl");
                    case "numFmt" -> {
                        if (abstractId != null && level != null) {
                            levelFormats.put(
                                    abstractId + "/" + level, attribute(parser, "val"));
                        }
                    }
                    case "num" -> {
                        numId = attribute(parser, "numId");
                        abstractId = null;
                    }
                    case "abstractNumId" -> {
                        if (numId != null) {
                            numIdToAbstract.put(numId, attribute(parser, "val"));
                        }
                    }
                    default -> {}
                }
            }
        }

        String marker(final String numId, final int level) {
            if (numId == null || numId.equals("0")) {
                return "";
            }
            final int safeLevel = Math.max(0, Math.min(level, 8));
            final String format =
                    levelFormats.get(numIdToAbstract.get(numId) + "/" + safeLevel);
            final String indent = "&emsp;".repeat(safeLevel);
            if (format == null || format.equals("bullet") || format.equals("none")) {
                return indent + "• ";
            }
            final int[] count = counters.computeIfAbsent(numId, k -> new int[9]);
            count[safeLevel]++;
            Arrays.fill(count, safeLevel + 1, count.length, 0);
            return indent + listNumber(format, count[safeLevel]) + ". ";
        }

        private String listNumber(final String format, final int value) {
            return switch (format) {
                case "lowerLetter" -> letters(value);
                case "upperLetter" -> letters(value).toUpperCase(Locale.ROOT);
                case "lowerRoman" -> roman(value).toLowerCase(Locale.ROOT);
                case "upperRoman" -> roman(value);
                default -> String.valueOf(value);
            };
        }

        private String letters(final int value) {
            final StringBuilder letters = new StringBuilder();
            int number = value;
            while (number > 0) {
                number--;
                letters.insert(0, (char) ('a' + number % 26));
                number /= 26;
            }
            return letters.toString();
        }

        private String roman(final int value) {
            final int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            final String[] digits = {
                "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"
            };
            final StringBuilder roman = new StringBuilder();
            int number = Math.min(value, 3999);
            for (int i = 0; i < values.length; ++i) {
                while (number >= values[i]) {
                    roman.append(digits[i]);
                    number -= values[i];
                }
            }
            return roman.toString();
        }

        /** Rewrites the opening tag once the paragraph's properties are known. */
        private final class Paragraph_ {
            final int start;
            int heading = 0;
            String align = null;
            String numId = null;
            String styleNumId = null;
            int level = 0;
            boolean markerDone = false;

            Paragraph_(final int start) {
                this.start = start;
            }

            String takeMarker(final Word word) {
                if (markerDone) {
                    return "";
                }
                markerDone = true;
                return word.marker(numId != null ? numId : styleNumId, level);
            }

            void close(final Word word) throws Failure {
                raw(takeMarker(word));
                raw("</div>");
                if (heading > 0 || align != null) {
                    final String open = "<div class=\"p\">";
                    final StringBuilder tag = new StringBuilder("<div class=\"");
                    tag.append(heading > 0 ? "h" + heading : "p").append('"');
                    if (align != null) {
                        tag.append(" style=\"text-align:").append(align).append('"');
                    }
                    tag.append('>');
                    out.replace(start, start + open.length(), tag.toString());
                }
            }
        }
    }

    // --- xlsx ---

    private final class Excel {
        private final Map<String, byte[]> zip;
        private final List<String> sharedStrings = new ArrayList<>();
        private final List<String> cellFormats = new ArrayList<>();
        private boolean date1904 = false;

        Excel(final Map<String, byte[]> zip) {
            this.zip = zip;
        }

        void run() throws Exception {
            final Map<String, String> relationships = relationships(zip, "xl/workbook.xml");
            readSharedStrings();
            readFormats();
            final List<String[]> sheets = new ArrayList<>();
            final XmlPullParser parser = parser(zip.get("xl/workbook.xml"));
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                if ("workbookPr".equals(parser.getName())) {
                    date1904 = "1".equals(attribute(parser, "date1904"))
                            || "true".equals(attribute(parser, "date1904"));
                } else if ("sheet".equals(parser.getName())
                        && isVisibleSheet(attribute(parser, "state"))) {
                    sheets.add(
                            new String[] {
                                attribute(parser, "name"),
                                relationships.get(relationshipId(parser))
                            });
                }
            }
            for (final String[] sheet : sheets) {
                if (sheets.size() > 1 || sheet[0] != null) {
                    raw("<div class=\"h2\">");
                    text(sheet[0] == null ? "" : sheet[0]);
                    raw("</div>");
                }
                if (sheet[1] != null && zip.get(sheet[1]) != null) {
                    sheet(zip.get(sheet[1]));
                }
            }
        }

        private boolean isVisibleSheet(final String state) {
            return state == null || "visible".equals(state);
        }

        private void readSharedStrings() throws Exception {
            final byte[] xml = zip.get("xl/sharedStrings.xml");
            if (xml == null) {
                return;
            }
            final XmlPullParser parser = parser(xml);
            StringBuilder current = null;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    switch (parser.getName()) {
                        case "si" -> current = new StringBuilder();
                        case "rPh" -> skip(parser);
                        case "t" -> {
                            if (current != null) current.append(parser.nextText());
                        }
                        default -> {}
                    }
                } else if (event == XmlPullParser.END_TAG && "si".equals(parser.getName())) {
                    sharedStrings.add(current == null ? "" : current.toString());
                    current = null;
                }
            }
        }

        private void readFormats() throws Exception {
            final byte[] xml = zip.get("xl/styles.xml");
            if (xml == null) {
                return;
            }
            final Map<String, String> custom = new HashMap<>();
            final XmlPullParser parser = parser(xml);
            boolean inCellXfs = false;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    switch (parser.getName()) {
                        case "numFmt" ->
                                custom.put(
                                        attribute(parser, "numFmtId"),
                                        attribute(parser, "formatCode"));
                        case "cellXfs" -> inCellXfs = true;
                        case "xf" -> {
                            if (inCellXfs) {
                                final String id = attribute(parser, "numFmtId");
                                final String code = custom.get(id);
                                cellFormats.add(code != null ? code : builtInFormat(id));
                            }
                        }
                        default -> {}
                    }
                } else if (event == XmlPullParser.END_TAG && "cellXfs".equals(parser.getName())) {
                    inCellXfs = false;
                }
            }
        }

        private String builtInFormat(final String id) {
            final int number = parseInt(id, 0);
            return switch (number) {
                case 1, 3 -> "0";
                case 2, 4 -> "0.00";
                case 9 -> "0%";
                case 10 -> "0.00%";
                case 14, 15, 16, 17 -> "dd/mm/yyyy";
                case 18, 19, 20, 21, 45, 46, 47 -> "hh:mm:ss";
                case 22 -> "dd/mm/yyyy hh:mm";
                default -> "";
            };
        }

        private void sheet(final byte[] xml) throws Exception {
            final XmlPullParser parser = parser(xml);
            raw("<div class=\"scroll\"><table>");
            int rows = 0;
            int column = 0;
            String type = null;
            int style = -1;
            String value = null;
            boolean cellOpen = false;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                final String tag = parser.getName();
                if (event == XmlPullParser.START_TAG) {
                    switch (tag) {
                        case "row" -> {
                            if (++rows > MAX_ROWS) {
                                raw("</table></div>");
                                note(R.string.velo_doc_truncated);
                                return;
                            }
                            column = 0;
                            raw("<tr>");
                        }
                        case "c" -> {
                            type = attribute(parser, "t");
                            style = parseInt(attribute(parser, "s"), -1);
                            value = null;
                            cellOpen = true;
                            final int target = columnOf(attribute(parser, "r"), column);
                            while (column < target && column < MAX_COLUMNS) {
                                raw("<td></td>");
                                column++;
                            }
                        }
                        case "v" -> {
                            if (cellOpen) value = parser.nextText();
                        }
                        case "t" -> {
                            if (cellOpen) {
                                final String part = parser.nextText();
                                value = value == null ? part : value + part;
                            }
                        }
                        default -> {}
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if ("c".equals(tag)) {
                        cellOpen = false;
                        if (column++ < MAX_COLUMNS) {
                            final String display = display(value, type, style);
                            final boolean number = type == null || "n".equals(type);
                            raw(number ? "<td class=\"n\">" : "<td>");
                            text(display);
                            raw("</td>");
                        }
                    } else if ("row".equals(tag)) {
                        raw("</tr>");
                    }
                }
            }
            raw("</table></div>");
        }

        private int columnOf(final String reference, final int fallback) {
            if (reference == null) {
                return fallback;
            }
            int column = 0;
            for (int i = 0; i < reference.length(); ++i) {
                final char c = reference.charAt(i);
                if (c < 'A' || c > 'Z') {
                    break;
                }
                column = column * 26 + (c - 'A' + 1);
                if (column > 20_000) {
                    return fallback;
                }
            }
            return column == 0 ? fallback : column - 1;
        }

        private String display(final String value, final String type, final int style) {
            if (value == null) {
                return "";
            }
            if (type == null || "n".equals(type)) {
                final String format =
                        style >= 0 && style < cellFormats.size() ? cellFormats.get(style) : "";
                return number(value, format);
            }
            return switch (type) {
                case "s" -> {
                    final int index = parseInt(value, -1);
                    yield index >= 0 && index < sharedStrings.size()
                            ? sharedStrings.get(index)
                            : "";
                }
                case "b" -> "1".equals(value) ? "TRUE" : "FALSE";
                default -> value;
            };
        }

        private String number(final String value, final String rawFormat) {
            final BigDecimal number;
            try {
                number = new BigDecimal(value.trim());
            } catch (final NumberFormatException e) {
                return value;
            }
            final String format = rawFormat.replaceAll("\"[^\"]*\"|\\[[^]]*]|\\\\.", "");
            if (isDateFormat(format)) {
                return date(number.doubleValue(), format);
            }
            final int dot = format.indexOf('.');
            int decimals = -1;
            if (dot >= 0) {
                decimals = 0;
                for (int i = dot + 1; i < format.length() && "0#?".indexOf(format.charAt(i)) >= 0; ++i) {
                    decimals++;
                }
            } else if (format.contains("0") || format.contains("#")) {
                decimals = 0;
            }
            BigDecimal shown = number;
            final boolean percent = format.contains("%");
            if (percent) {
                shown = shown.movePointRight(2);
            }
            shown = decimals >= 0
                    ? shown.setScale(decimals, java.math.RoundingMode.HALF_UP)
                    : shown.round(new MathContext(15));
            final String plain =
                    decimals >= 0
                            ? shown.toPlainString()
                            : shown.stripTrailingZeros().toPlainString();
            return percent ? plain + "%" : plain;
        }

        private boolean isDateFormat(final String format) {
            final String lower = format.toLowerCase(Locale.ROOT);
            return lower.matches(".*[ymdhs].*") && !lower.contains("general");
        }

        private String date(final double serial, final String format) {
            if (serial < 0 || serial > 3_000_000) {
                return String.valueOf(serial);
            }
            final long days1970 = date1904 ? 24107 : 25569;
            final long millis = Math.round((serial - days1970) * 86_400_000d);
            final String lower = format.toLowerCase(Locale.ROOT);
            final boolean hasDate = lower.matches(".*[yd].*") || lower.contains("mmm");
            final boolean hasTime = lower.contains("h") || lower.contains("s");
            final String pattern =
                    hasDate && hasTime
                            ? "dd/MM/yyyy HH:mm"
                            : hasTime ? "HH:mm:ss" : "dd/MM/yyyy";
            final SimpleDateFormat dateFormat = new SimpleDateFormat(pattern, Locale.ROOT);
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            return dateFormat.format(new Date(millis));
        }
    }

    // --- pptx ---

    private final class PowerPoint {
        private final Map<String, byte[]> zip;

        PowerPoint(final Map<String, byte[]> zip) {
            this.zip = zip;
        }

        void run() throws Exception {
            final Map<String, String> relationships = relationships(zip, "ppt/presentation.xml");
            final List<String> slides = new ArrayList<>();
            final XmlPullParser parser = parser(zip.get("ppt/presentation.xml"));
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG
                        && "sldId".equals(parser.getName())) {
                    final String path = relationships.get(relationshipId(parser));
                    if (path != null) {
                        slides.add(path);
                    }
                }
            }
            int number = 0;
            for (final String path : slides) {
                final byte[] xml = zip.get(path);
                if (xml != null) {
                    slide(path, xml, ++number);
                }
            }
        }

        private void slide(final String path, final byte[] xml, final int number)
                throws Exception {
            final Map<String, String> relationships = relationships(zip, path);
            final XmlPullParser parser = parser(xml);
            final int start = out.length();
            raw("<section class=\"slide\"><div class=\"slidenum\">");
            text(context.getString(R.string.velo_doc_slide, number));
            raw("</div>");
            boolean title = false;
            boolean bold = false, italic = false;
            boolean bullet = false;
            boolean inRunProperties = false;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                final String tag = parser.getName();
                if (event == XmlPullParser.START_TAG) {
                    switch (tag) {
                        case "sld" -> {
                            if ("0".equals(attribute(parser, "show"))) {
                                out.setLength(start);
                                return;
                            }
                        }
                        case "Fallback" -> skip(parser);
                        case "sp" -> title = false;
                        case "ph" -> {
                            final String type = attribute(parser, "type");
                            title = "title".equals(type) || "ctrTitle".equals(type);
                        }
                        case "p" -> {
                            bullet = false;
                            raw(title ? "<div class=\"h2\">" : "<div class=\"p\">");
                        }
                        case "buChar", "buAutoNum" -> {
                            if (!bullet) {
                                bullet = true;
                                raw("• ");
                            }
                        }
                        case "r" -> {
                            bold = italic = false;
                        }
                        case "rPr" -> {
                            inRunProperties = true;
                            bold = "1".equals(attribute(parser, "b"));
                            italic = "1".equals(attribute(parser, "i"));
                        }
                        case "t" -> {
                            styledText(
                                    parser.nextText(),
                                    runStyle(bold, italic, false, false, null));
                        }
                        case "br" -> raw("<br>");
                        case "blip" -> {
                            final String target = relationships.get(attribute(parser, "embed"));
                            image(target == null ? null : zip.get(target));
                        }
                        case "tbl" -> raw("<div class=\"scroll\"><table>");
                        case "tr" -> raw("<tr>");
                        case "tc" -> raw("<td>");
                        default -> {}
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    switch (tag) {
                        case "p" -> raw("</div>");
                        case "rPr" -> inRunProperties = false;
                        case "tc" -> raw("</td>");
                        case "tr" -> raw("</tr>");
                        case "tbl" -> raw("</table></div>");
                        default -> {}
                    }
                }
            }
            raw("</section>");
        }
    }

    // --- odt, ods, odp ---

    private final class OpenDocument {
        private final Map<String, byte[]> zip;
        private final Map<String, String> styles = new HashMap<>();

        OpenDocument(final Map<String, byte[]> zip) {
            this.zip = zip;
        }

        void run() throws Exception {
            final XmlPullParser parser = parser(zip.get("content.xml"));
            final Deque<Boolean> spans = new ArrayDeque<>();
            int listDepth = 0;
            int slides = 0;
            boolean listMarker = false;
            String styleName = null;
            TableState table = null;
            final Deque<TableState> tables = new ArrayDeque<>();
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                final String tag = parser.getName();
                if (event == XmlPullParser.TEXT) {
                    final String value = parser.getText();
                    if (!spans.isEmpty() && value != null) {
                        text(value);
                    }
                } else if (event == XmlPullParser.START_TAG) {
                    switch (tag) {
                        case "annotation", "note", "tracked-changes", "script", "forms" ->
                                skip(parser);
                        case "style" -> styleName = attribute(parser, "name");
                        case "text-properties" -> readTextStyle(parser, styleName);
                        case "page" -> {
                            raw("<section class=\"slide\"><div class=\"slidenum\">");
                            text(context.getString(R.string.velo_doc_slide, ++slides));
                            raw("</div>");
                        }
                        case "list" -> listDepth++;
                        case "list-item" -> listMarker = true;
                        case "h" -> {
                            final int level =
                                    Math.max(
                                            1,
                                            Math.min(
                                                    6,
                                                    parseInt(
                                                            attribute(parser, "outline-level"),
                                                            1)));
                            raw("<div class=\"h" + level + "\">");
                            spans.push(false);
                        }
                        case "p" -> {
                            raw("<div class=\"p\">");
                            if (listDepth > 0 && listMarker) {
                                listMarker = false;
                                raw("&emsp;".repeat(Math.min(listDepth - 1, 8)) + "• ");
                            }
                            spans.push(false);
                        }
                        case "span" -> {
                            final String style = styles.get(attribute(parser, "style-name"));
                            if (style != null) {
                                raw("<span style=\"" + style + "\">");
                            }
                            spans.push(style != null);
                        }
                        case "s" -> {
                            final int count =
                                    Math.max(1, Math.min(200, parseInt(attribute(parser, "c"), 1)));
                            raw("&nbsp;".repeat(count));
                        }
                        case "tab" -> raw("&emsp;");
                        case "line-break" -> raw("<br>");
                        case "image" -> {
                            final String href = attribute(parser, "href");
                            image(href == null ? null : zip.get(href.replaceFirst("^\\./", "")));
                        }
                        case "table" -> {
                            if (table != null) {
                                tables.push(table);
                            }
                            table = new TableState(attribute(parser, "name"));
                            if (table.name != null && !zipIsText()) {
                                raw("<div class=\"h2\">");
                                text(table.name);
                                raw("</div>");
                            }
                            raw("<div class=\"scroll\"><table>");
                        }
                        case "table-row" -> {
                            if (table != null) {
                                table.openRow(
                                        out.length(),
                                        parseInt(attribute(parser, "number-rows-repeated"), 1));
                            }
                        }
                        case "table-cell", "covered-table-cell" -> {
                            if (table != null) {
                                table.openCell(
                                        out.length(),
                                        parseInt(attribute(parser, "number-columns-repeated"), 1),
                                        parseInt(attribute(parser, "number-columns-spanned"), 1));
                            }
                        }
                        default -> {}
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    switch (tag) {
                        case "page" -> raw("</section>");
                        case "list" -> listDepth = Math.max(0, listDepth - 1);
                        case "h", "p" -> {
                            if (!spans.isEmpty()) {
                                spans.pop();
                            }
                            raw("</div>");
                        }
                        case "span" -> {
                            if (!spans.isEmpty() && spans.pop()) {
                                raw("</span>");
                            }
                        }
                        case "table-cell", "covered-table-cell" -> {
                            if (table != null) {
                                table.closeCell();
                            }
                        }
                        case "table-row" -> {
                            if (table != null && !table.closeRow()) {
                                // too many rows: the rest of this table is dropped
                                table.truncated = true;
                            }
                        }
                        case "table" -> {
                            final boolean truncated = table != null && table.truncated;
                            raw("</table></div>");
                            if (truncated) {
                                note(R.string.velo_doc_truncated);
                            }
                            table = tables.poll();
                        }
                        default -> {}
                    }
                }
            }
        }

        private boolean zipIsText() {
            return new String(zip.getOrDefault("mimetype", new byte[0]), StandardCharsets.US_ASCII)
                    .contains("opendocument.text");
        }

        private void readTextStyle(final XmlPullParser parser, final String name) {
            if (name == null) {
                return;
            }
            final String style =
                    runStyle(
                            "bold".equals(attribute(parser, "font-weight")),
                            "italic".equals(attribute(parser, "font-style")),
                            attribute(parser, "text-underline-style") != null
                                    && !"none".equals(attribute(parser, "text-underline-style")),
                            attribute(parser, "text-line-through-style") != null
                                    && !"none".equals(attribute(parser, "text-line-through-style")),
                            safeColour(attribute(parser, "color")));
            if (!style.isEmpty()) {
                styles.put(name, style);
            }
        }

        /** Sheets repeat empty cells and rows a million times: only what has content is written. */
        private final class TableState {
            final String name;
            int rows = 0;
            int rowStart;
            int rowRepeat;
            int emptyRows = 0;
            int cellStart;
            int cellRepeat;
            int span;
            int emptyCells = 0;
            int columns = 0;
            boolean rowHasContent;
            boolean truncated = false;

            TableState(final String name) {
                this.name = name;
            }

            void openRow(final int position, final int repeat) throws Failure {
                rowStart = position;
                rowRepeat = Math.max(1, repeat);
                emptyCells = 0;
                columns = 0;
                rowHasContent = false;
                raw("<tr>");
            }

            void openCell(final int position, final int repeat, final int spanned) {
                cellStart = position;
                cellRepeat = Math.max(1, repeat);
                span = Math.max(1, Math.min(MAX_COLUMNS, spanned));
            }

            void closeCell() throws Failure {
                final String content = out.substring(cellStart);
                out.setLength(cellStart);
                if (stripTags(content).isEmpty() && !content.contains("<img")) {
                    emptyCells = Math.min(MAX_COLUMNS, emptyCells + cellRepeat);
                    return;
                }
                rowHasContent = true;
                for (int i = 0; i < emptyCells && columns < MAX_COLUMNS; ++i, ++columns) {
                    raw("<td></td>");
                }
                emptyCells = 0;
                final int copies = Math.min(cellRepeat, MAX_COLUMNS);
                for (int i = 0; i < copies && columns < MAX_COLUMNS; ++i, ++columns) {
                    raw("<td colspan=\"" + span + "\">");
                    raw(content);
                    raw("</td>");
                }
            }

            boolean closeRow() throws Failure {
                raw("</tr>");
                final String row = out.substring(rowStart);
                out.setLength(rowStart);
                if (!rowHasContent) {
                    emptyRows = Math.min(MAX_ROWS, emptyRows + rowRepeat);
                    return true;
                }
                final int blanks = Math.min(emptyRows, 50);
                for (int i = 0; i < blanks && rows < MAX_ROWS; ++i, ++rows) {
                    raw("<tr></tr>");
                }
                emptyRows = 0;
                final int copies = Math.min(rowRepeat, 50);
                for (int i = 0; i < copies; ++i) {
                    if (rows >= MAX_ROWS) {
                        return false;
                    }
                    rows++;
                    raw(row);
                }
                return true;
            }
        }
    }

    private static String stripTags(final String html) {
        return html.replaceAll("<[^>]*>", "").replace("&nbsp;", "").replace("&emsp;", "").trim();
    }

    // --- doc, xls, ppt ---

    private void oleDocument(final InputStream input) throws Exception {
        try (final POIFSFileSystem filesystem = new POIFSFileSystem(input)) {
            final DirectoryNode root = filesystem.getRoot();
            if (root.hasEntry("EncryptedPackage") || root.hasEntry("EncryptedSummary")) {
                throw new Failure(PROTECTED);
            } else if (root.hasEntry("WordDocument")) {
                oldWord(root);
            } else if (root.hasEntry("Workbook") || root.hasEntry("Book")) {
                oldExcel(root);
            } else if (root.hasEntry("PowerPoint Document")) {
                oldPowerPoint(root);
            } else {
                throw new Failure(UNSUPPORTED);
            }
        }
    }

    private static String cleanOldText(final String text) {
        return Range.stripFields(text)
                .replace('\u0007', ' ')
                .replace('\r', ' ')
                .replace('\f', ' ')
                .replace('\u000b', '\n')
                .replaceAll("[\\u0000-\\u0008\\u000e-\\u001f]", "");
    }

    private void oldWord(final DirectoryNode root) throws Exception {
        try (final HWPFDocument document = new HWPFDocument(root)) {
            final Range range = document.getRange();
            final int paragraphs = range.numParagraphs();
            for (int i = 0; i < paragraphs; ++i) {
                final Paragraph paragraph = range.getParagraph(i);
                if (paragraph.isInTable()) {
                    final Table table = range.getTable(paragraph);
                    raw("<div class=\"scroll\"><table>");
                    for (int r = 0; r < table.numRows() && r < MAX_ROWS; ++r) {
                        final TableRow row = table.getRow(r);
                        raw("<tr>");
                        for (int c = 0; c < row.numCells() && c < MAX_COLUMNS; ++c) {
                            final TableCell cell = row.getCell(c);
                            raw("<td>");
                            for (int p = 0; p < cell.numParagraphs(); ++p) {
                                oldParagraph(document, cell.getParagraph(p));
                            }
                            raw("</td>");
                        }
                        raw("</tr>");
                    }
                    raw("</table></div>");
                    i += table.numParagraphs() - 1;
                } else {
                    oldParagraph(document, paragraph);
                }
            }
            final List<Picture> pictures = document.getPicturesTable().getAllPictures();
            for (final Picture picture : pictures) {
                image(picture.getContent());
            }
        }
    }

    private void oldParagraph(final HWPFDocument document, final Paragraph paragraph)
            throws Failure {
        int heading = 0;
        try {
            final String style =
                    document.getStyleSheet()
                            .getStyleDescription(paragraph.getStyleIndex())
                            .getName();
            if (style != null && style.toLowerCase(Locale.ROOT).matches("heading [1-6]")) {
                heading = style.charAt(style.length() - 1) - '0';
            }
        } catch (final RuntimeException ignored) {
            // no style information: an ordinary paragraph
        }
        raw(heading > 0 ? "<div class=\"h" + heading + "\">" : "<div class=\"p\">");
        for (int r = 0; r < paragraph.numCharacterRuns(); ++r) {
            final CharacterRun run = paragraph.getCharacterRun(r);
            final String runText = cleanOldText(run.text());
            if (!runText.isEmpty()) {
                styledText(
                        runText,
                        runStyle(run.isBold(), run.isItalic(), false, run.isStrikeThrough(), null));
            }
        }
        raw("</div>");
    }

    private void oldExcel(final DirectoryNode root) throws Exception {
        try (final HSSFWorkbook workbook = new HSSFWorkbook(root, false)) {
            final DataFormatter formatter = new DataFormatter(Locale.getDefault());
            for (int s = 0; s < workbook.getNumberOfSheets(); ++s) {
                if (workbook.isSheetHidden(s) || workbook.isSheetVeryHidden(s)) {
                    continue;
                }
                final Sheet sheet = workbook.getSheetAt(s);
                raw("<div class=\"h2\">");
                text(sheet.getSheetName());
                raw("</div><div class=\"scroll\"><table>");
                final int lastRow = Math.min(sheet.getLastRowNum(), MAX_ROWS - 1);
                for (int r = sheet.getFirstRowNum(); r <= lastRow && r >= 0; ++r) {
                    final Row row = sheet.getRow(r);
                    raw("<tr>");
                    if (row != null) {
                        final int lastCell = Math.min(row.getLastCellNum(), MAX_COLUMNS);
                        for (int c = 0; c < lastCell; ++c) {
                            final Cell cell = row.getCell(c);
                            final boolean number = cell != null && isNumeric(cell);
                            raw(number ? "<td class=\"n\">" : "<td>");
                            if (cell != null) {
                                text(oldCell(cell, formatter));
                            }
                            raw("</td>");
                        }
                    }
                    raw("</tr>");
                }
                raw("</table></div>");
                if (sheet.getLastRowNum() >= MAX_ROWS) {
                    note(R.string.velo_doc_truncated);
                }
            }
        }
    }

    private static boolean isNumeric(final Cell cell) {
        final CellType type =
                cell.getCellType() == CellType.FORMULA
                        ? cell.getCachedFormulaResultType()
                        : cell.getCellType();
        return type == CellType.NUMERIC;
    }

    private static String oldCell(final Cell cell, final DataFormatter formatter) {
        if (cell.getCellType() != CellType.FORMULA) {
            return formatter.formatCellValue(cell);
        }
        // the value saved with the file: formulas are not calculated again here
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC ->
                    formatter.formatRawCellContents(
                            cell.getNumericCellValue(),
                            cell.getCellStyle().getDataFormat(),
                            cell.getCellStyle().getDataFormatString());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            default -> "";
        };
    }

    /** Reads the records of the .ppt stream by hand: POI's reader needs AWT, absent on Android. */
    private void oldPowerPoint(final DirectoryNode root) throws Exception {
        final byte[] stream;
        try (final DocumentInputStream input = root.createDocumentInputStream("PowerPoint Document")) {
            final long budget = Math.min(Runtime.getRuntime().maxMemory() / 2, 128L * 1024 * 1024);
            if (input.available() > budget) {
                throw new Failure(TOO_BIG);
            }
            stream = new byte[input.available()];
            input.readFully(stream);
        }
        final List<StringBuilder> fromList = new ArrayList<>();
        final List<StringBuilder> fromSlides = new ArrayList<>();
        final ByteBuffer buffer = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN);
        walkPowerPoint(buffer, 0, stream.length, fromList, fromSlides, null, 0, false);
        final List<StringBuilder> slides = new ArrayList<>();
        for (int i = 0; i < Math.max(fromList.size(), fromSlides.size()); ++i) {
            final StringBuilder shapes = i < fromSlides.size() ? fromSlides.get(i) : null;
            final StringBuilder list = i < fromList.size() ? fromList.get(i) : null;
            slides.add(
                    shapes != null && shapes.toString().trim().length() > 0
                            ? shapes
                            : list != null ? list : new StringBuilder());
        }
        if (slides.isEmpty()) {
            throw new Failure(UNREADABLE);
        }
        int number = 0;
        for (final StringBuilder slide : slides) {
            raw("<section class=\"slide\"><div class=\"slidenum\">");
            text(context.getString(R.string.velo_doc_slide, ++number));
            raw("</div>");
            for (final String line : slide.toString().split("[\\r\\n\\u000b]+")) {
                if (!line.trim().isEmpty()) {
                    raw("<div class=\"p\">");
                    text(line);
                    raw("</div>");
                }
            }
            raw("</section>");
        }
    }

    private static final int PPT_DOCUMENT = 0x03E8;
    private static final int PPT_SLIDE = 0x03EE;
    private static final int PPT_SLIDE_LIST = 0x0FF0;
    private static final int PPT_SLIDE_PERSIST = 0x03F3;
    private static final int PPT_TEXT_CHARS = 0x0FA0;
    private static final int PPT_TEXT_BYTES = 0x0FA8;

    /**
     * {@code current} is the slide being read: text atoms go there, at any depth. Only the slide
     * list and the slides count: masters and notes hold placeholder text.
     */
    private void walkPowerPoint(
            final ByteBuffer buffer,
            final int from,
            final int to,
            final List<StringBuilder> fromList,
            final List<StringBuilder> fromSlides,
            final StringBuilder current,
            final int depth,
            final boolean inSlideList) {
        int position = from;
        StringBuilder target = current;
        boolean documentDone = false;
        while (position + 8 <= to) {
            final int versionAndInstance = buffer.getShort(position) & 0xFFFF;
            final int type = buffer.getShort(position + 2) & 0xFFFF;
            final long length = buffer.getInt(position + 4) & 0xFFFFFFFFL;
            final int start = position + 8;
            if (start + length > to) {
                return;
            }
            final int end = (int) (start + length);
            final boolean container = (versionAndInstance & 0xF) == 0xF;
            final int instance = versionAndInstance >> 4;
            if (container && depth < 16) {
                if (type == PPT_SLIDE && current == null && !inSlideList) {
                    final StringBuilder slide = new StringBuilder();
                    fromSlides.add(slide);
                    walkPowerPoint(buffer, start, end, fromList, fromSlides, slide, depth + 1, false);
                } else if (current != null) {
                    walkPowerPoint(buffer, start, end, fromList, fromSlides, current, depth + 1, false);
                } else if (type == PPT_DOCUMENT && !documentDone) {
                    documentDone = true; // later copies come from "fast save"
                    walkPowerPoint(buffer, start, end, fromList, fromSlides, null, depth + 1, false);
                } else if (type == PPT_SLIDE_LIST && instance == 0) {
                    walkPowerPoint(buffer, start, end, fromList, fromSlides, null, depth + 1, true);
                }
            } else if (inSlideList && type == PPT_SLIDE_PERSIST) {
                target = new StringBuilder();
                fromList.add(target);
            } else if ((type == PPT_TEXT_CHARS || type == PPT_TEXT_BYTES) && target != null) {
                final Charset charset =
                        type == PPT_TEXT_CHARS
                                ? StandardCharsets.UTF_16LE
                                : StandardCharsets.ISO_8859_1;
                target.append(new String(buffer.array(), start, end - start, charset)).append('\n');
            }
            position = end;
        }
    }

    // --- txt, csv and the like ---

    private void text(final InputStream input, final String name) throws Exception {
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        final byte[] buffer = new byte[64 * 1024];
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) > 0) {
            final int room = MAX_TEXT_BYTES - content.size();
            if (read > room) {
                content.write(buffer, 0, room);
                truncated = true;
                break;
            }
            content.write(buffer, 0, read);
        }
        final String document = decode(content.toByteArray());
        final String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
            csv(document, lower.endsWith(".tsv"));
        } else {
            raw("<pre>");
            text(document);
            raw("</pre>");
        }
        if (truncated) {
            note(R.string.velo_doc_truncated);
        }
    }

    private static String decode(final byte[] data) {
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        final int offset =
                data.length >= 3
                                && (data[0] & 0xFF) == 0xEF
                                && (data[1] & 0xFF) == 0xBB
                                && (data[2] & 0xFF) == 0xBF
                        ? 3
                        : 0;
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, data.length - offset))
                    .toString();
        } catch (final CharacterCodingException e) {
            return new String(data, offset, data.length - offset, Charset.forName("windows-1252"));
        }
    }

    private void csv(final String document, final boolean tabs) throws Failure {
        final int firstLine = document.indexOf('\n');
        final String first = firstLine < 0 ? document : document.substring(0, firstLine);
        final char separator;
        if (tabs) {
            separator = '\t';
        } else {
            final long commas = first.chars().filter(c -> c == ',').count();
            final long semicolons = first.chars().filter(c -> c == ';').count();
            separator = semicolons > commas ? ';' : ',';
        }
        raw("<div class=\"scroll\"><table><tr><td>");
        boolean quoted = false;
        int rows = 1;
        for (int i = 0; i < document.length(); ++i) {
            final char c = document.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < document.length() && document.charAt(i + 1) == '"') {
                    raw("&quot;");
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    text(String.valueOf(c));
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                raw("</td><td>");
            } else if (c == '\n') {
                if (++rows > MAX_ROWS) {
                    raw("</td></tr></table></div>");
                    note(R.string.velo_doc_truncated);
                    return;
                }
                if (i + 1 < document.length()) {
                    raw("</td></tr><tr><td>");
                }
            } else if (c != '\r') {
                text(String.valueOf(c));
            }
        }
        raw("</td></tr></table></div>");
    }
}
