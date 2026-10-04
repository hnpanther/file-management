# The image both containers run: the official Apache Tika 4.1 "-full" image - Tika server, Java 25,
# Tesseract 5.5 with a few European languages, on Ubuntu 26.04 - with Persian added. The text lane
# runs it with OCR switched off (config/text.json), so one image serves both.
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

# Back to the official image's own unprivileged user.
USER 35002:35002
