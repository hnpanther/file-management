# The content-search test corpus (roadmap 11)

Made for the tests, never taken from the archive: no real document, person or number is in them.
`MakeFixtures.java.txt` made them (PDFBox, POI and the JPEG 2000 codec, all from the Tika image's
own `lib/`, and Segoe UI and Tahoma from Windows); it is kept beside them as text, not compiled.
Each Persian word sits on one page only, so a test can say which page a match must be answered on.

| File | What it is | What a reading must give |
|---|---|---|
| `text-fa.pdf` | 2 pages of Persian text, stored in visual order as sound PDFs are | page 1 `قرارداد`, page 2 `مناقصه` - `TEXT` |
| `reversed-fa.pdf` | the same, stored in logical order - read back reversed, as the office-automation letters of 2026-10-08 | put right: `TEXT_REVERSED`, `قرارداد` on page 1 |
| `garbage-layer.pdf` | a scanned page under an invisible text layer of Latin noise - the Canon scanner's of 2026-10-08 | `auto` trusts the layer; the quality check sends it to `every-page`: `BOTH`, `مخزن`, `انبار` |
| `scan-jp2.pdf` | one scanned page, its image JPEG 2000 | read only with `jai-imageio-jpeg2000` in the image: `OCR`, `انبار`, `PMP` |
| `mixed.pdf` | page 1 text, page 2 a scan | one request: page 1 `TEXT` with `پیمانکار`, page 2 `OCR` with `مخزن` |
| `photo.png` | a photographed page | `OCR`, `انبار`, `تمدید` |
| `slides.pptx` | 2 slides | slide 1 `بودجه`, slide 2 `تعمیرات` |
| `sheets.xlsx` | 2 sheets, `خلاصه` and `جزئیات` | each a `SHEET` with its name |
| `letter.docx` | a short letter | the whole of it, `تمدید` |

OCR is asked for words both Persian models read right (Ubuntu's standard `fas` and `tessdata_best`'s):
Java draws `س` and `ش` with a stretch Tesseract does not read, and the first line of a sparse page
can be dropped - hence the throwaway first lines.
