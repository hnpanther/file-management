# The OCR lane's image: the official Apache Tika "-full" image - Tika server, Java, Tesseract and a
# few European languages - with Persian added. Same Tika version as the application's tika-core
# (pom.xml, tika.version), so detection and parsing agree.
#
#   docker compose build tika-ocr
#   docker compose run --rm --entrypoint tesseract tika-ocr --list-langs    # must list fas and eng
ARG TIKA_VERSION=3.3.2.0
FROM apache/tika:${TIKA_VERSION}-full

USER root
# tesseract-ocr-fas: Persian, from the distribution (tessdata). tessdata_best's fas.traineddata reads
# Persian more accurately and more slowly - README.md, "A better Persian model".
RUN apt-get update \
    && apt-get install --yes --no-install-recommends tesseract-ocr-fas tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/* \
    && tesseract --list-langs 2>&1 | grep -qx fas \
    && tesseract --list-langs 2>&1 | grep -qx eng

# Back to the official image's own unprivileged user.
USER 35002:35002
