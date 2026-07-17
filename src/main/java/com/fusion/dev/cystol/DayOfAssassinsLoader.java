package com.fusion.dev.cystol;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Paper plugin loader: downloads runtime libraries onto this plugin's classpath.
 * SQLite is not shaded into the jar (keeps the artifact small).
 */
@SuppressWarnings("UnstableApiUsage")
public final class DayOfAssassinsLoader implements PluginLoader {

    private static final String SQLITE = "org.xerial:sqlite-jdbc:3.47.2.0";

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addDependency(new Dependency(new DefaultArtifact(SQLITE), null));
        // Paper's Maven Central mirror (do not hit Central CDN directly)
        resolver.addRepository(new RemoteRepository.Builder(
                "maven-central",
                "default",
                MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR
        ).build());
        classpathBuilder.addLibrary(resolver);
    }
}
