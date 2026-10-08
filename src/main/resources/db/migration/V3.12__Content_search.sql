-- Roadmap Phase 11 (2.15.0): searching the contents of files - the text Apache Tika reads out of each
-- revision, kept page by page so that a match can say on which page it is.
--
-- file_content: one row per revision - the queue and the outcome. Written PENDING in the transaction
-- that stores the revision's bytes (an outbox: an upload that rolls back leaves none), taken by the
-- worker one at a time with FOR UPDATE SKIP LOCKED and a lease (a worker killed mid-file leaves a
-- READING row whose lease runs out, and it is taken again), and removed with the revision.
--
--   priority      0 a revision uploaded since 2.15.0, 1 one stored before it (the backfill): new
--                 files are read first
--   attempts      failed readings so far; a reading cut short because Tika could not be reached
--                 costs none
--   lane          the Tika container that read it: TEXT (Office documents, text) or OCR (PDFs and
--                 images - a text page read as text, a scanned one recognised)
--   reason        why SKIPPED or FAILED, or what made a DONE partial - never any of the text
--   partial       a cap cut the reading short (max-text-mb)
--
-- file_content_page: the text, a row per page, slide or sheet (page_number 0 for what has none - a
-- Word document, a photo - and for text found outside every page); a long unit in parts, so that no
-- row's tsvector nears PostgreSQL's 1 MB. text is what was read, for the snippet; search_text is the
-- same folded as names are folded (SearchKey: Arabic yeh and kaf to Persian, the half-space dropped,
-- digits to ASCII, upper case) and reduced to its letters and digits, so that matching depends on
-- neither the database's locale nor its parser's idea of punctuation; search_vector is its tsvector
-- ('simple': no stemming, no stop words), generated, GIN-indexed.
--
--   source   TEXT the page's text layer; TEXT_REVERSED a text layer stored reversed, put right;
--            OCR recognised from the page's image; BOTH a text layer judged garbage and the page's
--            OCR beside it (the layer kept: a page of codes and tags can look like garbage and be right)
--   score    the share of the page's words that are common function words, in thousandths - what
--            told a reversed or garbage text layer from a sound one (roadmap 11, "Measured again on
--            fifteen real files"); null where it was not asked

CREATE TABLE file_content
(
    file_details_id INTEGER        NOT NULL,
    state           VARCHAR(10)    NOT NULL,
    priority        SMALLINT       NOT NULL DEFAULT 0,
    attempts        SMALLINT       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ(0) NOT NULL,
    lease_until     TIMESTAMPTZ(0),
    lane            VARCHAR(4),
    detected_type   VARCHAR(255),
    reason          VARCHAR(500),
    partial         BOOLEAN        NOT NULL DEFAULT FALSE,
    pages           INTEGER,
    ocr_pages       INTEGER,
    characters      INTEGER,
    queued_at       TIMESTAMPTZ(0) NOT NULL,
    read_at         TIMESTAMPTZ(0),
    CONSTRAINT pk_file_content PRIMARY KEY (file_details_id),
    CONSTRAINT fk_file_content_file_details FOREIGN KEY (file_details_id) REFERENCES file_details (id) ON DELETE CASCADE,
    CONSTRAINT ck_file_content_state CHECK (state IN ('PENDING', 'READING', 'DONE', 'EMPTY', 'SKIPPED', 'FAILED')),
    CONSTRAINT ck_file_content_lane CHECK (lane IS NULL OR lane IN ('TEXT', 'OCR')),
    CONSTRAINT ck_file_content_lease CHECK ((state = 'READING') = (lease_until IS NOT NULL))
);

-- The queue the worker takes from: the pending rows only, in the order it takes them.
CREATE INDEX ix_file_content_queue ON file_content (priority, next_attempt_at, file_details_id) WHERE state = 'PENDING';
-- Readings whose lease may have run out - a worker that died holding them.
CREATE INDEX ix_file_content_reading ON file_content (lease_until) WHERE state = 'READING';
-- The status page's counts, and its list of failures.
CREATE INDEX ix_file_content_state ON file_content (state, file_details_id);

CREATE TABLE file_content_page
(
    file_details_id INTEGER  NOT NULL,
    page_number     INTEGER  NOT NULL,
    part            SMALLINT NOT NULL DEFAULT 0,
    unit            VARCHAR(5) NOT NULL,
    label           VARCHAR(255),
    source          VARCHAR(13) NOT NULL,
    score           SMALLINT,
    text            TEXT     NOT NULL,
    search_text     TEXT     NOT NULL,
    search_vector   TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, search_text)) STORED,
    CONSTRAINT pk_file_content_page PRIMARY KEY (file_details_id, page_number, part),
    CONSTRAINT fk_file_content_page_content FOREIGN KEY (file_details_id) REFERENCES file_content (file_details_id) ON DELETE CASCADE,
    CONSTRAINT ck_file_content_page_unit CHECK (unit IN ('PAGE', 'SLIDE', 'SHEET', 'WHOLE')),
    CONSTRAINT ck_file_content_page_source CHECK (source IN ('TEXT', 'TEXT_REVERSED', 'OCR', 'BOTH')),
    CONSTRAINT ck_file_content_page_number CHECK (page_number >= 0 AND part >= 0)
);

CREATE INDEX ix_file_content_page_search ON file_content_page USING GIN (search_vector);
