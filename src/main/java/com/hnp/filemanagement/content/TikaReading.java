package com.hnp.filemanagement.content;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What Tika read out of one document: its XHTML ({@code /tika/xml}) taken apart into the units a
 * search answers with. Measured on Tika 4.1 (2026-10-08):
 *
 * <ul>
 *   <li>a PDF: a {@code <div class="page">} per page, the text recognised by OCR in a
 *       {@code <div class="ocr">} inside its page;</li>
 *   <li>a presentation: a {@code <div class="slide-content">} per slide;</li>
 *   <li>a spreadsheet: a {@code <div class="sheet">} per sheet, its name in the first {@code <h1>};</li>
 *   <li>a Word document, a text: no unit - the whole of it is unit 0; an image: its OCR, unit 0.</li>
 * </ul>
 *
 * <p>Text found outside every unit (a document's attachments, a presentation's notes) is unit 0 too.
 * Read as a stream: the answer is never held whole, and once {@code maxCharacters} have been kept the
 * rest is not read - the reading is then {@link #partial()}.
 *
 * @param contentType the type Tika gave the document, as its {@code Content-Type} meta says
 * @param pages       the number of pages the document says it has ({@code xmpTPg:NPages}), or 0
 * @param ocrBlocks   how many blocks of OCR the answer held, read or empty - an OCR that ran
 * @param units       the units, unit 0 first if it has any text, then in the document's order
 * @param partial     cut at {@code maxCharacters}
 * @param characters  the characters kept
 */
public record TikaReading(String contentType, int pages, int ocrBlocks, List<Unit> units, boolean partial,
                          long characters) {

    /** What a unit is, as the document has it. */
    public enum Kind { PAGE, SLIDE, SHEET, WHOLE }

    /**
     * One page, slide or sheet - or the whole, for a document without them.
     *
     * @param number its position, from 1; 0 for the whole
     * @param label  a sheet's name, or null
     * @param layer  the text the document holds (a PDF's text layer)
     * @param ocr    the text recognised from its image
     */
    public record Unit(int number, Kind kind, String label, String layer, String ocr) {

        public boolean isEmpty() {
            return layer.isBlank() && ocr.isBlank();
        }

        @Override
        public String toString() {
            return "Unit[" + kind + " " + number + ", " + layer.length() + "+" + ocr.length() + " characters]";
        }
    }

    @Override
    public String toString() {
        return "TikaReading[" + contentType + ", " + units.size() + " units, " + characters + " characters"
                + (partial ? ", partial" : "") + "]";
    }

    /** The answer of a document that could not be parsed as Tika's XHTML. */
    public static final class MalformedAnswer extends IOException {
        MalformedAnswer(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Reads an answer to its end, or to {@code maxCharacters}. */
    public static TikaReading parse(InputStream xhtml, long maxCharacters) throws IOException {
        Collector collector = new Collector(maxCharacters);
        try {
            parser().parse(new InputSource(xhtml), collector);
        } catch (SAXException e) {
            // Enough kept - the parser may hand the stop back wrapped: the rest of the answer is not read.
            if (!collector.partial) {
                throw new MalformedAnswer("Tika's answer is not XHTML: " + e.getMessage(), e);
            }
        }
        return collector.reading();
    }

    /** A parser that reads no DTD and no external entity: the answer is Tika's, the document anybody's. */
    private static SAXParser parser() throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            return factory.newSAXParser();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("no secure XML parser", e);
        }
    }

    private static final class Collector extends DefaultHandler {

        /** Thrown to stop reading once enough is kept. */
        private static final class Full extends SAXException {
            Full() {
                super("enough");
            }
        }

        private static final Set<String> BLOCKS = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr",
                "br", "table", "ul", "ol", "pre", "blockquote", "section", "article", "title");
        private static final Set<String> CELLS = Set.of("td", "th");

        private final long maxCharacters;
        private final List<UnitBuilder> units = new ArrayList<>();
        private final UnitBuilder whole = new UnitBuilder(0, Kind.WHOLE);
        /** For each open div: what it opened - a unit, an OCR block, or nothing of ours. */
        private final List<String> divs = new ArrayList<>();
        private UnitBuilder current;
        private boolean inOcr;
        private boolean inHead;
        private boolean wantLabel;
        private String contentType;
        private int pages;
        private int ocrBlocks;
        private long characters;
        private boolean partial;
        private int pageCount;
        private int slideCount;
        private int sheetCount;

        Collector(long maxCharacters) {
            this.maxCharacters = maxCharacters;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String name = localName.isEmpty() ? qName : localName;
            switch (name) {
                case "head" -> inHead = true;
                case "meta" -> meta(attributes.getValue("name"), attributes.getValue("content"));
                case "div" -> openDiv(attributes.getValue("class"));
                case "h1" -> {
                    if (current != null && current.kind == Kind.SHEET && current.label == null) {
                        wantLabel = true;
                    }
                }
                default -> {
                }
            }
        }

        private void meta(String name, String content) {
            if (name == null || content == null) {
                return;
            }
            if ("Content-Type".equals(name) && contentType == null) {
                contentType = content;
            } else if ("xmpTPg:NPages".equals(name)) {
                try {
                    pages = Integer.parseInt(content.trim());
                } catch (NumberFormatException ignored) {
                    // a document that lies about its pages has none we count on
                }
            }
        }

        private void openDiv(String cssClass) {
            String opened = "";
            if (current == null && cssClass != null) {
                switch (cssClass) {
                    case "page" -> opened = open(new UnitBuilder(++pageCount, Kind.PAGE));
                    case "slide-content" -> opened = open(new UnitBuilder(++slideCount, Kind.SLIDE));
                    case "sheet" -> opened = open(new UnitBuilder(++sheetCount, Kind.SHEET));
                    default -> {
                    }
                }
            }
            if ("ocr".equals(cssClass) && !inOcr) {
                inOcr = true;
                ocrBlocks++;
                opened = "ocr";
            }
            divs.add(opened);
        }

        private String open(UnitBuilder unit) {
            current = unit;
            units.add(unit);
            return "unit";
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = localName.isEmpty() ? qName : localName;
            if ("head".equals(name)) {
                inHead = false;
                return;
            }
            if ("h1".equals(name)) {
                wantLabel = false;
            }
            UnitBuilder target = target();
            if (BLOCKS.contains(name)) {
                target.breakLine(inOcr);
            } else if (CELLS.contains(name)) {
                target.space(inOcr);
            }
            if ("div".equals(name) && !divs.isEmpty()) {
                String closed = divs.removeLast();
                if ("unit".equals(closed)) {
                    current = null;
                } else if ("ocr".equals(closed)) {
                    inOcr = false;
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws Full {
            if (inHead) {
                return;
            }
            if (wantLabel && current != null) {
                current.labelText.append(ch, start, length);
            }
            long room = maxCharacters - characters;
            if (room <= 0) {
                partial = true;
                throw new Full();
            }
            int kept = (int) Math.min(length, room);
            target().append(ch, start, kept, inOcr);
            characters += kept;
            if (kept < length) {
                partial = true;
                throw new Full();
            }
        }

        private UnitBuilder target() {
            return current == null ? whole : current;
        }

        TikaReading reading() {
            List<Unit> built = new ArrayList<>();
            Unit wholeUnit = whole.build();
            if (!wholeUnit.isEmpty()) {
                built.add(wholeUnit);
            }
            units.forEach(unit -> built.add(unit.build()));
            return new TikaReading(contentType, pages, ocrBlocks, List.copyOf(built), partial, characters);
        }
    }

    private static final class UnitBuilder {
        final int number;
        final Kind kind;
        String label;
        final StringBuilder labelText = new StringBuilder();
        final StringBuilder layer = new StringBuilder();
        final StringBuilder ocr = new StringBuilder();

        UnitBuilder(int number, Kind kind) {
            this.number = number;
            this.kind = kind;
        }

        void append(char[] ch, int start, int length, boolean inOcr) {
            (inOcr ? ocr : layer).append(ch, start, length);
        }

        void breakLine(boolean inOcr) {
            StringBuilder text = inOcr ? ocr : layer;
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
                text.append('\n');
            }
        }

        void space(boolean inOcr) {
            StringBuilder text = inOcr ? ocr : layer;
            if (!text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1))) {
                text.append(' ');
            }
        }

        Unit build() {
            String name = labelText.toString().strip();
            label = name.isEmpty() ? null : (name.length() > 255 ? name.substring(0, 255) : name);
            return new Unit(number, kind, label, tidy(layer), tidy(ocr));
        }

        /** Blank lines and runs of spaces collapsed; the lines kept - a snippet reads by them. */
        private static String tidy(StringBuilder text) {
            StringBuilder out = new StringBuilder(text.length());
            for (String line : text.toString().split("\n")) {
                String collapsed = line.replaceAll("[ \\t\\x0B\\f\\r\\u00A0]+", " ").strip();
                if (!collapsed.isEmpty()) {
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(collapsed);
                }
            }
            return out.toString();
        }
    }
}
