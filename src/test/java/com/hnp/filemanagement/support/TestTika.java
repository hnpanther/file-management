package com.hnp.filemanagement.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;

/**
 * The Tika the content-search tests read with (roadmap 11): one container per JVM, of the image
 * deploy/tika builds - Tika 4.1, Persian, the JPEG 2000 decoder - run with the OCR container's
 * configuration, which reads Office documents as well as PDFs and images, so one container serves
 * both lanes. The image is the one built on this machine if there is one; otherwise it is built from
 * deploy/tika's Dockerfile, once (the first build pulls the official image, about 2 GB).
 */
public final class TestTika {

    public static final String IMAGE = "file-management/tika:4.1.0-1-fas";
    private static final Path DEPLOY = Path.of("deploy", "tika");

    private TestTika() {
    }

    /** {@code http://host:port} of the container. */
    public static String url() {
        GenericContainer<?> container = Holder.CONTAINER;
        return "http://" + container.getHost() + ":" + container.getMappedPort(9998);
    }

    private static final class Holder {
        static final GenericContainer<?> CONTAINER = started();
    }

    private static GenericContainer<?> started() {
        GenericContainer<?> container = (builtHere() ? new GenericContainer<>(DockerImageName.parse(IMAGE))
                : new GenericContainer<>(new ImageFromDockerfile(IMAGE, false).withFileFromPath(".", DEPLOY)))
                .withCommand("-c", "/tika-config/tika-config.json")
                .withCopyFileToContainer(MountableFile.forHostPath(DEPLOY.resolve("config/ocr.json")), "/tika-config/tika-config.json")
                .withEnv("HOME", "/tmp")
                .withEnv("OMP_THREAD_LIMIT", "1")
                .withExposedPorts(9998)
                .waitingFor(Wait.forHttp("/version").forPort(9998).withStartupTimeout(Duration.ofMinutes(3)));
        container.start();
        return container;
    }

    private static boolean builtHere() {
        try {
            org.testcontainers.DockerClientFactory.instance().client().inspectImageCmd(IMAGE).exec();
            return true;
        } catch (RuntimeException notFound) {
            return false;
        }
    }
}
