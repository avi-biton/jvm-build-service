package com.redhat.hacbs.container.analyser.deploy.containerregistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FilePermissions;
import com.google.cloud.tools.jib.api.buildplan.FilePermissionsProvider;
import com.google.cloud.tools.jib.api.buildplan.ImageFormat;
import com.redhat.hacbs.container.analyser.deploy.DeployData;
import com.redhat.hacbs.container.analyser.deploy.Gav;
import com.redhat.hacbs.recipies.util.FileUtil;

import io.quarkus.logging.Log;

public class ContainerRegistryDeployer {

    static {
        if (System.getProperty("jib.httpTimeout") == null) {
            //long timeout, but not infinite
            long fiveMinutes = TimeUnit.MINUTES.toMillis(5);
            System.setProperty("jib.httpTimeout", "" + fiveMinutes);
        }
    }

    private final String host;
    private final int port;
    private final String owner;
    private final String repository;
    private final boolean insecure;
    private final String prependTag;

    private final String username;
    private final String password;

    final String imageId;

    static final ObjectMapper MAPPER = new ObjectMapper();

    public ContainerRegistryDeployer(
            String host,
            int port,
            String owner,
            String token,
            String repository,
            boolean insecure,
            String prependTag, String imageId) {
        if (insecure) {
            System.setProperty("sendCredentialsOverHttp", "true");
        }

        this.host = host;
        this.port = port;
        this.owner = owner;
        this.repository = repository;
        this.insecure = insecure;
        this.prependTag = prependTag;
        this.imageId = imageId;
        String fullName = host + (port == 443 ? "" : ":" + port) + "/" + owner + "/" + repository;
        if (!token.isBlank()) {
            if (token.trim().startsWith("{")) {
                //we assume this is a .dockerconfig file
                try (var parser = MAPPER.createParser(token)) {
                    DockerConfig config = parser.readValueAs(DockerConfig.class);
                    boolean found = false;
                    String tmpUser = null;
                    String tmpPw = null;
                    for (var i : config.getAuths().entrySet()) {
                        if (fullName.startsWith(i.getKey())) {
                            found = true;
                            var decodedAuth = new String(Base64.getDecoder().decode(i.getValue().getAuth()),
                                    StandardCharsets.UTF_8);
                            int pos = decodedAuth.indexOf(":");
                            tmpUser = decodedAuth.substring(0, pos);
                            tmpPw = decodedAuth.substring(pos + 1);
                            break;
                        }
                    }
                    if (!found) {
                        throw new RuntimeException("Unable to find a host matching " + fullName
                                + " in provided dockerconfig, hosts provided: " + config.getAuths().keySet());
                    }
                    username = tmpUser;
                    password = tmpPw;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                var decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
                int pos = decoded.indexOf(":");
                username = decoded.substring(0, pos);
                password = decoded.substring(pos + 1);
            }
        } else {
            Log.errorf("No token configured");
            username = null;
            password = null;
        }
        Log.infof("Using username %s to publish to %s/%s/%s", username, host, owner, repository);
        Log.infof("Prepend tag is %s", prependTag);

    }

    public void deployArchive(Path deployDir, Path sourcePath, Path logsPath, Set<String> gavs,
            BiConsumer<String, String> imageNameHashCallback) throws Exception {
        Log.debugf("Using Container registry %s:%d/%s/%s", host, port, owner, repository);

        // Read the tar to get the gavs and files
        DeployData imageData = new DeployData(deployDir, gavs, prependTag);

        try {
            // Create the image layers
            createImages(imageData, sourcePath, logsPath, imageNameHashCallback);

        } finally {
            FileUtil.deleteRecursive(imageData.getArtifactsPath());
        }
    }

    public void tagArchive(List<String> gavNames) throws Exception {
        if (gavNames.isEmpty()) {
            throw new RuntimeException("Empty GAV list");
        }

        Deque<Gav> gavs = new ArrayDeque<>();
        for (var i : gavNames) {
            gavs.push(Gav.parse(i, prependTag));
        }
        Gav first = gavs.pop();
        String existingImage = createImageName(imageId);
        RegistryImage existingRegistryImage = RegistryImage.named(existingImage);
        RegistryImage registryImage = RegistryImage.named(createImageName(first.getTag()));
        if (username != null) {
            registryImage = registryImage.addCredential(username, password);
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .setAllowInsecureRegistries(insecure);

        JibContainerBuilder containerBuilder = Jib.from(existingRegistryImage)
                .setFormat(ImageFormat.OCI);

        Log.infof("Deploying image with tag %s", first.getTag());
        for (Gav gav : gavs) {
            Log.infof("Deploying image with tag %s", gav.getTag());
            containerizer = containerizer.withAdditionalTag(gav.getTag());
        }
        containerBuilder.addLabel("io.jvmbuildservice.gavs", String.join(",", gavNames));

        var result = containerBuilder.containerize(containerizer);
    }

    public void deployPreBuildImage(String baseImage, Path sourcePath, String imageSourcePath, String tag)
            throws Exception {
        Log.debugf("Using Container registry %s:%d/%s/%s", host, port, owner, repository);
        String imageName = createImageName(tag);
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (username != null) {
            registryImage = registryImage.addCredential(username, password);
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .setAllowInsecureRegistries(insecure);
        Log.infof("Deploying pre build image %s", imageName);

        JibContainerBuilder containerBuilder = Jib.from(baseImage)
                .setFormat(ImageFormat.OCI)
                .addLabel("quay.expires-after", "24h"); //we don't want to keep these around forever, they are an intermediate step

        var pathInContainer = AbsoluteUnixPath.get(imageSourcePath);
        try (Stream<Path> list = Files.list(sourcePath)) {
            var files = list.toList();
            FileEntriesLayer.Builder layerConfigurationBuilder = FileEntriesLayer.builder();
            for (Path file : files) {
                layerConfigurationBuilder.addEntryRecursive(
                        file, pathInContainer.resolve(file.getFileName()), new FilePermissionsProvider() {
                            @Override
                            public FilePermissions get(Path sourcePath, AbsoluteUnixPath destinationPath) {
                                try {
                                    return FilePermissions.fromPosixFilePermissions(Files.getPosixFilePermissions(sourcePath));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
            }

            containerBuilder.addFileEntriesLayer(layerConfigurationBuilder.build());
            Log.debugf("Image %s created", imageName);
            var result = containerBuilder.containerize(containerizer);
        }
    }

    public void deployHermeticPreBuildImage(String baseImage, Path buildArtifactsPath, Path repositoryPath,
            String imageSourcePath, String tag) throws Exception {
        Log.debugf("Using Container registry %s:%d/%s/%s", host, port, owner, repository);
        String imageName = createImageName(tag);
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (username != null) {
            registryImage = registryImage.addCredential(username, password);
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .setAllowInsecureRegistries(insecure);
        Log.infof("Deploying hermetic pre build image %s", imageName);

        JibContainerBuilder containerBuilder = Jib.from(baseImage)
                .setFormat(ImageFormat.OCI);

        FileEntriesLayer.Builder layerConfigurationBuilder = FileEntriesLayer.builder();
        var pathInContainer = AbsoluteUnixPath.get(imageSourcePath);
        Files.walkFileTree(repositoryPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("_remote.repositories")) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = repositoryPath.relativize(file).toString();
                if (Files.exists(buildArtifactsPath.resolve(relative))) {
                    return FileVisitResult.CONTINUE;
                }
                layerConfigurationBuilder.addEntry(file, pathInContainer.resolve(relative),
                        FilePermissions.fromPosixFilePermissions(Files.getPosixFilePermissions(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        containerBuilder.addFileEntriesLayer(layerConfigurationBuilder.build());
        Log.debugf("Image %s created", imageName);
        var result = containerBuilder.containerize(containerizer);
    }

    private void createImages(DeployData imageData, Path sourcePath, Path logsPath,
            BiConsumer<String, String> imageNameHashCallback)
            throws InvalidImageReferenceException, InterruptedException, RegistryException, IOException,
            CacheDirectoryCreationException, ExecutionException {

        String imageName = createImageName();
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (username != null) {
            registryImage = registryImage.addCredential(username, password);
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .setAllowInsecureRegistries(insecure);
        Log.infof("Deploying base image %s", imageName);

        Set<Gav> gavs = imageData.getGavs();

        AbsoluteUnixPath imageRoot = AbsoluteUnixPath.get("/");
        JibContainerBuilder containerBuilder = Jib.fromScratch()
                .setFormat(ImageFormat.OCI)
                .addLabel("groupId", imageData.getGroupIds())
                .addLabel("version", imageData.getVersions())
                .addLabel("artifactId", imageData.getArtifactIds());

        containerBuilder.addLabel("io.jvmbuildservice.gavs",
                gavs.stream().map(Gav::stringForm).collect(Collectors.joining(",")));
        List<Path> layers = getLayers(imageData.getArtifactsPath(), sourcePath, logsPath);
        for (Path layer : layers) {
            containerBuilder = containerBuilder.addLayer(List.of(layer), imageRoot);
        }

        Log.debugf("Image %s created", imageName);

        var result = containerBuilder.containerize(containerizer);

        if (imageNameHashCallback != null) {
            imageNameHashCallback.accept(imageName, result.getDigest().getHash());
        }
    }

    private String createImageName() {
        String tag = imageId == null ? UUID.randomUUID().toString() : imageId;
        return createImageName(tag);
    }

    private String createImageName(String tag) {
        if (port == 443) {
            return host + "/" + owner + "/" + repository
                    + ":" + tag;
        }
        return host + ":" + port + "/" + owner + "/" + repository
                + ":" + tag;
    }

    private List<Path> getLayers(Path artifacts, Path source, Path logs) {
        Log.debug("\n Container details:\n"
                + "\t layer 1 (source) " + source.toString() + "\n"
                + "\t layer 2 (logs) " + logs.toString() + "\n"
                + "\t layer 3 (artifacts) " + artifacts.toString());

        return List.of(source, logs, artifacts);
    }

}
