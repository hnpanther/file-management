# The image both containers run: the official Apache Tika 4.1 "-full" image - Tika server, Java 25,
# Tesseract 5.5 with a few European languages, on Ubuntu 26.04 - with Persian added, and the
# JPEG 2000 decoder PDFBox needs. The text lane runs it with OCR switched off (config/text.json), so
# one image serves both.
#
#   docker compose build
#   docker compose run --rm --no-deps --entrypoint tesseract tika-ocr --list-langs    # fas and eng
ARG TIKA_VERSION=4.1.0-1
FROM apache/tika:${TIKA_VERSION}-full

USER root
# tesseract-ocr-fas: Persian, from the distribution (Tesseract's standard model). tessdata_best's
# fas.traineddata reads Persian more accurately and more slowly - README.md, "A better Persian model".
RUN apt-get update \
    && apt-get install --yes --no-install-recommends tesseract-ocr-fas \
    && rm -rf /var/lib/apt/lists/* \
    && tesseract --list-langs 2>&1 | grep -qx fas \
    && tesseract --list-langs 2>&1 | grep -qx eng

# JPEG 2000 (a PDF's JPXDecode images - what many scanners write): PDFBox reads it only with
# jai-imageio-jpeg2000, which the official image leaves out for its licence. Without it a scanned
# PDF of such images answers 200 with no text at all, and the log says "Cannot read JPEG2000 image:
# Java Advanced Imaging (JAI) Image I/O Tools are not installed" (README.md, "What trying it found").
# /tika-extras/ is on the server's classpath (the image's entrypoint), and the forked parsing JVMs
# inherit it. Pinned by its SHA-256 - Maven Central's SHA-1 of it, fc2179dc...e40660, agrees.
#
# The directory is made first: ADD's --chmod gives a directory it creates the file's 644, and one
# without its execute bit hides the jar from the server's user.
ARG JPEG2000_VERSION=1.4.0
RUN install --directory --mode=755 /tika-extras
ADD --checksum=sha256:07fb6e3a3040122b846c5e52520033175c3251e2ec8830df82f87cb21f388bb1 --chmod=644 \
    https://repo1.maven.org/maven2/com/github/jai-imageio/jai-imageio-jpeg2000/${JPEG2000_VERSION}/jai-imageio-jpeg2000-${JPEG2000_VERSION}.jar \
    /tika-extras/jai-imageio-jpeg2000-${JPEG2000_VERSION}.jar

# Back to the official image's own unprivileged user - and the jar checked as that user reads it.
USER 35002:35002
RUN test -r /tika-extras/jai-imageio-jpeg2000-${JPEG2000_VERSION}.jar
