package com.redhat.hacbs.container.analyser.build.ant;

import static org.apache.tools.ant.MagicNames.ANT_FILE;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;

import com.redhat.hacbs.container.analyser.build.JavaVersion;
import com.redhat.hacbs.container.analyser.location.VersionRange;

import io.quarkus.logging.Log;

/**
 * Utility class for Ant.
 */
public final class AntUtils {
    public static final String ANT_VERSION_JAVA5 = "1.9.16";

    public static final String ANT_VERSION_JAVA8 = "1.10.13";

    private static final String BUILD_XML = "build.xml";

    private static final String ANT_BUILD_JAVAC_SOURCE = "ant.build.javac.source";

    private static final String ANT_BUILD_JAVAC_TARGET = "ant.build.javac.target";

    private static final List<String> DEFAULT_ANT_ARGS = List.of("-v");

    private AntUtils() {

    }

    /**
     * Returns true if and only if the directory contains a readable file named {@code build.xml}.
     *
     * @param path the base directory or build file
     * @return whether the current directory contains an Ant build
     */
    public static boolean isAntBuild(Path path) {
        var buildFile = Files.isDirectory(path) ? path.resolve(BUILD_XML) : path;
        return (Files.isRegularFile(buildFile) && Files.isReadable(buildFile));
    }

    /**
     * Loads the Ant project from the given Ant build file.
     *
     * @param buildFile the Ant build file
     * @return the Ant project
     */
    public static Project loadProject(Path buildFile) {
        var project = new Project();
        project.init();
        project.setUserProperty(ANT_FILE, buildFile.toAbsolutePath().toString());
        ProjectHelper.configureProject(project, buildFile.toFile());
        return project;
    }

    /**
     * Gets the Java version for the given Ant project.
     *
     * @param project the Ant project
     * @return the Java version if known, or {@code -1} otherwise
     */
    public static int getJavaVersion(Project project) {
        var javaVersion = -1;
        var target = project.getProperty(ANT_BUILD_JAVAC_TARGET);

        if (target != null && !target.isEmpty()) {
            javaVersion = JavaVersion.toVersion(target);
        } else {
            var source = project.getProperty(ANT_BUILD_JAVAC_SOURCE);

            if (source != null && !source.isEmpty()) {
                javaVersion = JavaVersion.toVersion(source);
            }
        }

        return javaVersion;
    }

    /**
     * Gets the Java version from the Ant build file, if any, and matches it with a supported Java version, if
     * possible.
     *
     * @param path the base directory or build file
     * @return the specified Java version, or empty if none
     */
    public static String getJavaVersion(Path path) {
        try {
            var buildFile = Files.isDirectory(path) ? path.resolve(BUILD_XML) : path;
            var project = loadProject(buildFile);
            var javaVersion = getJavaVersion(project);
            return javaVersion != -1 ? Integer.toString(javaVersion) : "";
        } catch (Throwable t) {
            Log.errorf(t, "Failed to determine Java version for ant project");
        }
        return "";
    }

    /**
     * Gets the JDK version range for the given Ant build file.
     *
     * @param path the base directory or build file
     * @return the JDK version range if possible, or {@code null} otherwise
     */
    public static VersionRange getJavaVersionRange(Path path) {
        try {
            var buildFile = Files.isDirectory(path) ? path.resolve(BUILD_XML) : path;
            var project = loadProject(buildFile);
            return getJavaVersionRange(project);
        } catch (Throwable t) {
            Log.errorf(t, "Failed to determine Java version for ant project");
        }
        return new VersionRange("7", "17", "8");
    }

    /**
     * Gets the JDK version range for the given Ant project.
     *
     * @param project the Ant project
     * @return the JDK version range if possible, or {@code null} otherwise
     */
    public static VersionRange getJavaVersionRange(Project project) {
        var javaVersion = getJavaVersion(project);

        if (javaVersion != -1) {
            if (javaVersion < 7) {
                if (javaVersion == 6) {
                    return new VersionRange("7", "11", "8");
                } else {
                    return new VersionRange("7", "8", "8");
                }
            }

            var javaVersionStr = Integer.toString(javaVersion);
            return new VersionRange(javaVersionStr, javaVersionStr, javaVersionStr);
        }

        return new VersionRange("7", "17", "8");
    }

    /**
     * Gets the Ant version that runs on the given Java version.
     *
     * @param javaVersion the Java version
     * @return the Ant version
     */
    public static String getAntVersionForJavaVersion(String javaVersion) {
        return javaVersion.isEmpty() || Integer.parseInt(javaVersion) >= 8 ? ANT_VERSION_JAVA8 : ANT_VERSION_JAVA5;
    }

    /**
     * Get the default Ant arguments.
     *
     * @return the Ant arguments
     */
    public static List<String> getAntArgs() {
        return DEFAULT_ANT_ARGS;
    }
}
