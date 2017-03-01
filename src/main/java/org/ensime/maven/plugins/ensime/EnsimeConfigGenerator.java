/*
 * Copyright 2012 Happy-Camper Street.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.ensime.maven.plugins.ensime;

import org.ensime.maven.plugins.ensime.model.EnsimeProject;
import org.ensime.maven.plugins.ensime.model.EnsimeProjectId;
import org.ensime.maven.plugins.ensime.model.EnsimeConfig;
import org.ensime.maven.plugins.ensime.model.EnsimeModule;

import org.ensime.maven.plugins.ensime.formatter.SExpFormatter;

import java.lang.management.ManagementFactory;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;

import java.util.Properties;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.maven.project.MavenProject;
import org.apache.maven.model.Repository;
import org.apache.maven.model.Plugin;
import org.apache.maven.artifact.Artifact;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;

import org.codehaus.plexus.util.xml.Xpp3Dom;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.partitioningBy;

final public class EnsimeConfigGenerator {
  private final MavenProject project;
  private final RepositorySystem repoSystem;
  private final RepositorySystemSession session;
  private final Properties properties;
  private final List<MavenProject> modules;

  private final static String SCALA_MAVEN_PLUGIN_GROUP_ID = "net.alchim31.maven";
  private final static String DEFAULT_SCALA_VERSION = "2.10.6";
  private final static String SCALA_MAVEN_PLUGIN_ARTIFACT_ID = "scala-maven-plugin";

  private final static String JAVA_MAVEN_PLUGIN_GROUP_ID = "org.apache.maven.plugins";
  private final static String JAVA_MAVEN_PLUGIN_ARTIFACT_ID = "maven-compiler-plugin";

  private final static String SCALA_MAVEN_PLUGIN =
    SCALA_MAVEN_PLUGIN_GROUP_ID + ":" +SCALA_MAVEN_PLUGIN_ARTIFACT_ID;

  private final static String JAVA_MAVEN_PLUGIN =
    JAVA_MAVEN_PLUGIN_GROUP_ID + ":" + JAVA_MAVEN_PLUGIN_ARTIFACT_ID;

  private final static String ENSIME_SERVER_VERSION = "2.0.0-SNAPSHOT";


  private final static String SP = File.separator;

  public EnsimeConfigGenerator(final MavenProject project,
    final RepositorySystem repoSystem,
    final RepositorySystemSession session,
    final Properties properties) {

    this.project    = project;
    this.repoSystem = repoSystem;
    this.session    = session;
    this.properties = properties;
    List<MavenProject> temp = project.getCollectedProjects();
    temp.add(project);
    modules = temp.stream().
      filter(p -> !p.getPackaging().equals("pom")).collect(toList());
  }

  private List<RemoteRepository> remoteRepositories() {
    List<Repository> repos = project.getRepositories();
    return repoSystem.newResolutionRepositories(session,
        repos.stream()
        .map(r ->
          new RemoteRepository.Builder(r.getId(), "default", r.getUrl())
          .build()
        ).collect(toList()));
  }

  private static Optional<String> output(final InputStream inputStream) {
    StringBuilder sb = new StringBuilder();
    try(BufferedReader br =
        new BufferedReader(new InputStreamReader(inputStream))) {
      String line = null;
      while ((line = br.readLine()) != null) {
          sb.append(line + System.getProperty("line.separator"));
      }
      String res = sb.toString();
      return Optional.ofNullable(res);
    } catch (IOException ioex) {
      return Optional.empty();
    }
  }

  private File getJavaHome() {

    Optional<String> macResult = Optional.empty();
    try {
      ProcessBuilder pb    = new ProcessBuilder("/usr/libexec/java_home");
      Process process      = pb.start();
      macResult            = output(process.getInputStream());
    } catch (IOException ioex) {}

    Stream<String> possibleJDKs = Stream.of(
      // manual
      Optional.ofNullable(System.getenv("JDK_HOME")),
      Optional.ofNullable(System.getenv("JAVA_HOME")),
      // fallback
      Optional.ofNullable(System.getProperty("java.home"))
        .map(f -> new File(f).getParent()),
      Optional.ofNullable(System.getProperty("java.home")),
      // osx
      macResult)
      .flatMap(o -> o.map(Stream::of).orElseGet(Stream::empty));


    Optional<File> possibleJDK = possibleJDKs.flatMap( n -> {
      File f = new File(n + "/lib/tools.jar");
      if (f.exists())
        return Stream.of(new File(n));
      else return Stream.empty();
    }).findFirst();

    if(!possibleJDK.isPresent()) {
      System.err.println(
        "Could not automatically find the JDK/lib/tools.jar.\n" +
        "You must explicitly set JDK_HOME or JAVA_HOME.");
      System.exit(1);
    }

    return possibleJDK.get();
  }

  private DefaultArtifact artifact(final String groupId,
      final String artifactId, final String version) {
    return new DefaultArtifact(groupId, artifactId, "jar", version);
  }

  private ArtifactRequest artifactRequest(final DefaultArtifact art) {
    return new ArtifactRequest(art, remoteRepositories(), null);
  }

  private Optional<File> resolve(final DefaultArtifact art)  {
    try {
      return Optional.ofNullable(repoSystem.resolveArtifact(session,
        artifactRequest(art)).getArtifact().getFile());
    } catch (Exception ex) {
      return Optional.empty();
    }
  }

  private Set<File> resolveAll(final DefaultArtifact art) {
    Dependency dependency = new Dependency(art, "compile");

    CollectRequest collectRequest =
      new CollectRequest(dependency, remoteRepositories());

    DependencyRequest dependencyRequest = new DependencyRequest();
    dependencyRequest.setCollectRequest(collectRequest);

    Set<File> dependencies;
    try {
      dependencies = repoSystem.resolveDependencies(session, dependencyRequest)
        .getArtifactResults().stream()
        .map(a -> a.getArtifact().getFile())
        .collect(toSet());
    } catch (DependencyResolutionException drex) {
        dependencies = Optional.ofNullable(drex.getResult()).map(r -> {
          return r.getArtifactResults().stream()
            .flatMap(a -> {
              return Optional.ofNullable(a.getArtifact()).map(f -> f.getFile())
                .map(Stream::of).orElseGet(Stream::empty);
            })
            .collect(toSet());
        }).orElse(new HashSet<>());
    }

    return dependencies.stream()
      .filter(f -> !f.getName().endsWith(".pom")).collect(toSet());
  }

  private Pair<Integer, Integer> partialVersion() {
    String[] parts = getScalaVersion().split("\\.");
    return new Pair(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
  }


  private Set<File> resolveScalaJars(final String org, final String version) {
    return Stream.of(
      resolve(artifact(org, "scalap", version)),
      resolve(artifact(org, "scala-compiler", version)),
      resolve(artifact(org, "scala-library", version)),
      resolve(artifact(org, "scala-reflect", version)))
      .flatMap(s -> s.map(Stream::of).orElseGet(Stream::empty))
      .collect(toSet());
  }

  private Set<File> resolveEnsimeJars(final String org, final String ensime) {
    Pair<Integer, Integer> scalaVersion = partialVersion();
    String scala = scalaVersion._1 + "." + scalaVersion._2;

    Set<File> ensimeServerArtifacts =
      resolveAll(artifact("org.ensime", "server_" + scala,
            ENSIME_SERVER_VERSION));

    return resolve(artifact(org, "scalap", getScalaVersion())).map ( f -> {
      Set<File> artifacts = new HashSet();
      artifacts.addAll(ensimeServerArtifacts);
      artifacts.add(f);
      return artifacts;
    }).orElse(ensimeServerArtifacts);
  }

  private EnsimeModule ensimeProjectsToModule(final List<EnsimeProject> p) {
    String name = p.get(0).getId().getProject();
    Set<String> deps = p.stream()
      .flatMap(s -> s.getDependsOn().stream())
      .map(d -> d.getProject()).collect(toSet());

    Map<Boolean,  List<EnsimeProject>> partitioned =
      p.stream()
      .collect(partitioningBy(d -> d.getId().getConfig().equals("compile")));

    List<EnsimeProject> mains = partitioned.get(true);
    List<EnsimeProject> tests = partitioned.get(false);

    Set<File> mainSources =
      mains.stream().flatMap(s -> s.getSources().stream()).collect(toSet());

    Set<File> mainTargets =
      mains.stream().flatMap(s -> s.getTargets().stream()).collect(toSet());

    Set<File> mainJars =
      mains.stream().flatMap(s -> s.getLibraryJars().stream()).collect(toSet());

    Set<File> testSources =
      tests.stream().flatMap(s -> s.getSources().stream()).collect(toSet());

    Set<File> testTargets =
      tests.stream().flatMap(s -> s.getTargets().stream()).collect(toSet());

    Set<File> testJars =
      tests.stream().flatMap(s -> s.getLibraryJars().stream()).collect(toSet());
    testJars.removeAll(mainJars);

    Set<File> sourceJars =
      p.stream().flatMap(s -> s.getLibrarySources().stream()).collect(toSet());

    Set<File> docJars =
      p.stream().flatMap(s -> s.getLibraryDocs().stream()).collect(toSet());

    return new EnsimeModule(
      name, mainSources, testSources, mainTargets,
      testTargets, deps.stream().collect(toSet()), mainJars,
      new HashSet(), testJars, sourceJars, docJars);
  }

  /**
   * Get the scala-version for this project. Uses scala.version as the key.
   *
   * If you want a blue shed, get out a can of paint :)
   * @return String containing the scala version
   */
  private String getScalaVersion() {
    Stream<org.apache.maven.model.Dependency> deps =
      project.getDependencies().stream();

    return deps.filter(d -> d.getGroupId().equals("org.scala-lang") &&
                d.getArtifactId().equals("scala-library"))
            .findFirst()
            .map(d -> d.getVersion())
            .orElse(DEFAULT_SCALA_VERSION); // So arbitrary.
  }


  /**
   * Get the Scala organization for this project.
   * @return String containing the scala organization
   * @author amanjpro
   */
  private String getScalaOrganization() {
    Map<String, Plugin> plugins = project.getPluginManagement()
        .getPluginsAsMap();

    Plugin scalacPlugin = plugins.get(SCALA_MAVEN_PLUGIN);

    return Optional.ofNullable(scalacPlugin).map(s -> s.getConfiguration())
      .flatMap( obj -> {
        if(obj instanceof Xpp3Dom) {
          Xpp3Dom config = (Xpp3Dom) obj;
          return Optional.ofNullable(config.getChild("scalaOrganization"))
            .map(ch -> ch.getValue());
        } else return Optional.empty();
      }).orElse("org.scala-lang");
  }

  private Set<File> getScalaJars() {
    return resolveScalaJars(getScalaOrganization(), getScalaVersion());
  }


  private Set<File> getEnsimeServerJars() {
    Set<File> resolvedEnsimeJars =
      resolveEnsimeJars(getScalaOrganization(), ENSIME_SERVER_VERSION).stream()
        .filter ( f -> {
          String name = f.getName();
          return !(name.contains("scalap") || name.contains("scala-reflect") ||
            name.contains("scala-library") || name.contains("scala-compiler"));
        }).collect(toSet());

    File toolsJar = new File(getJavaHome().getAbsolutePath() + SP + "lib" + SP +
        "tools.jar");
    resolvedEnsimeJars.add(toolsJar);
    return resolvedEnsimeJars;
  }

  /**
   * Get java-flags from environment variable `ENSIME_JAVA_FLAGS` .
   * Used for starting ensime-server.
   * @return List of java flags or empty list if not provided
   * @author parsnips
   */
  private List<String> getEnsimeJavaFlags() {
    // WORKAROUND https://github.com/ensime/ensime-sbt/issues/91
    List<String> raw =
      ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .map(s -> s.equals("-Xss1m")? "-Xss2m" : s)
        .collect(toList());
    if(raw.stream().filter(flag -> flag.startsWith("-Xss"))
        .findFirst().isPresent()) return raw;
    raw.add("-Xss2m");
    return raw;
  }


  private List<String> ensimeSuggestedOptions() {
    List<String> flags = Stream.of(
      "-feature",
      "-deprecation",
      "-Xlint",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Xfuture").collect(toList());
    Pair<Integer, Integer> pv = partialVersion();
    if(pv._1 == 2 && pv._2 == 10) {
      flags.add("-Ymacro-no-expand");
    } else if(pv._1 == 2 && pv._2 >= 11) {
      flags.add("-Ywarn-unused-import");
      flags.add("-Ymacro-expand:discard");
    }
    return flags;
  }

  /**
   * Get the scalacOptions for this project.
   * @return A list containing the scalacOptions
   * @author amanjpro
   */
  private List<String> getScalacOptions(final MavenProject project) {
    Map<String, Plugin> plugins = project.getPluginManagement()
        .getPluginsAsMap();

    Plugin scalacPlugin = plugins.get(SCALA_MAVEN_PLUGIN);

    Optional<List<String>> options =
      Optional.ofNullable(scalacPlugin).map(s -> s.getConfiguration())
        .flatMap( obj -> {
          if(obj instanceof Xpp3Dom) {
            Xpp3Dom config = (Xpp3Dom) obj;
            return Optional.ofNullable(config.getChild("args"))
              .map(ch -> Arrays.stream(ch.getChildren())
                        .map(c -> c.getValue()).collect(toList()));
          } else return Optional.empty();
        });

    if(options.isPresent()) {
      List<String> providedOptions = options.get();
      providedOptions.addAll(ensimeSuggestedOptions());
      return providedOptions.stream().distinct().collect(toList());
    } else {
      return ensimeSuggestedOptions();
    }

  }


  /**
   * Get the javacOptions for this project.
   * @return A list containing the javacOptions
   * @author amanjpro
   */
  private List<String> getJavacOptions(final MavenProject project) {
    Map<String, Plugin> plugins = project.getPluginManagement()
        .getPluginsAsMap();

    Plugin javacPlugin = plugins.get(JAVA_MAVEN_PLUGIN);

    Plugin scalacPlugin = plugins.get(SCALA_MAVEN_PLUGIN);

    Optional<List<String>> javacOptions =
      Optional.ofNullable(javacPlugin).map(p -> p.getConfiguration()).flatMap(obj -> {
      if(obj instanceof Xpp3Dom) {
        Xpp3Dom config = (Xpp3Dom) obj;
        return Optional.ofNullable(config.getChild("compilerArgs"))
          .map(s -> Arrays.stream(s.getChildren()).map(v -> v.getValue())
                    .collect(toList()));
      } else return Optional.empty();
    });

    Optional<List<String>> jvmOptions =
      Optional.ofNullable(scalacPlugin).map(p -> p.getConfiguration()).flatMap (obj -> {
      if(obj instanceof Xpp3Dom) {
        Xpp3Dom config = (Xpp3Dom) obj;
        return Optional.ofNullable(config.getChild("jvmArgs"))
          .map(s -> Arrays.stream(s.getChildren()).map(v -> v.getValue())
                    .collect(toList()));
      } else return Optional.empty();
    });

    List<String> options = new ArrayList();
    javacOptions.ifPresent(opts -> options.addAll(opts));
    jvmOptions.ifPresent(opts -> options.addAll(opts));
    return options;
  }

  private List<String> getSources(final MavenProject module, final String target) {
    Map<String, Plugin> plugins = project.getPluginManagement()
        .getPluginsAsMap();

    Plugin scalacPlugin = plugins.get(SCALA_MAVEN_PLUGIN);

    List<String> sources   = Optional.ofNullable(scalacPlugin).map(p -> p.getConfiguration())
      .flatMap(obj -> {
        if(obj instanceof Xpp3Dom) {
          Xpp3Dom config = (Xpp3Dom) obj;
          return Optional.ofNullable(config.getChild("sources"))
            .map(c -> Arrays.stream(c.getChildren())
                      .map(v -> v.getValue()).collect(toList()));
        } else return Optional.empty();
      }).orElse(new ArrayList());

    if(sources.isEmpty()) {
      sources = Stream.of(
          new File(module.getBasedir().getAbsolutePath() + SP + "src" + SP
            + target + SP + "scala"),
          new File(module.getBasedir().getAbsolutePath() + SP + "src" + SP
            + target + SP + "java")).map(f -> f.getAbsolutePath()).collect(toList());
    }

    sources.addAll(module.getCompileSourceRoots());

    return sources;
  }

  private List<EnsimeProject> getEnsimeProjects() {

    return modules.stream().map ( project -> {
      EnsimeProjectId projectId =
        new EnsimeProjectId(project.getArtifactId(),
            Optional.ofNullable(project.getDefaultGoal()).orElse("compile"));

      Set<Artifact> dependencyArtifacts = project.getDependencyArtifacts();

      // This only gets the direct dependencies, and we filter all the
      // dependencies that are not a subproject of this potentially
      // multiproject project
      List<EnsimeProjectId> depends = modules.stream()
        // .filter(d ->
        //     modules.stream().filter(
        //       m -> m.getId().equals(d.getId())).findFirst().isPresent())
        .map(d -> new EnsimeProjectId(d.getArtifactId(), "compile"))
        .collect(toList());

      List<String> compileSources = new ArrayList();
      compileSources.addAll(getSources(project, "main"));
      compileSources.addAll(getSources(project, "test"));

      Set<File> compileFiles = compileSources.stream()
        .map(s -> new File(s))
        .filter(f -> f.exists())
        .collect(toSet());

      Set<File> targets = Stream.of(
          new File(project.getBuild().getOutputDirectory())).collect(toSet());

      List<String> scalacOptions = getScalacOptions(project);
      List<String> javacOptions = getJavacOptions(project);

      Set<File> libraryJars = dependencyArtifacts.stream().flatMap ( art ->
        resolveAll(new DefaultArtifact(art.getGroupId(),
          art.getArtifactId(), "jar", art.getVersion())).stream()
      ).collect(toSet());

      Set<File> librarySources = dependencyArtifacts.stream().flatMap ( art ->
        resolveAll(new DefaultArtifact(art.getGroupId(),
          art.getArtifactId(), "sources", "jar", art.getVersion())).stream()
      ).collect(toSet());

      Set<File> libraryDocs = dependencyArtifacts.stream().flatMap ( art ->
        resolveAll(new DefaultArtifact(art.getGroupId(),
          art.getArtifactId(), "javadoc", "jar", art.getVersion())).stream()
      ).collect(toSet());

      return new EnsimeProject(projectId, depends, compileFiles,
        targets, scalacOptions, javacOptions,
        libraryJars, librarySources,
        libraryDocs);
    }).collect(toList());
  }

  private void write(final String content, final File out) {
    try(PrintWriter writer = new PrintWriter(out)) {
      writer.write(content);
    } catch(IOException ioex) {}
  }

  /**
   * Generates configurations.
   */
  public void generate(final File out) {

    String projectDir =
      project.getBasedir().toPath().toAbsolutePath().toString();

    File cacheDir = new File(projectDir + SP + ".ensime_cache");

    List<EnsimeProject> subProjects = getEnsimeProjects();

    Map<String, EnsimeModule> modules =
      subProjects.stream().collect(groupingBy(s -> s.getId().getProject()))
      .entrySet().stream()
      .collect(toMap(p -> p.getKey(), p -> ensimeProjectsToModule(p.getValue())));

    File javaSrcFile = new File(getJavaHome().getAbsolutePath() + SP + "src.zip");
    Set<File> javaSrc = new HashSet<>();

    if(javaSrcFile.exists()) javaSrc.add(javaSrcFile);

    EnsimeConfig config = new EnsimeConfig(project.getBasedir(), cacheDir,
      getScalaJars(), getEnsimeServerJars(), project.getName(),
      getScalaVersion(),
      ensimeSuggestedOptions(), modules, getJavaHome(),
      getEnsimeJavaFlags(), getJavacOptions(project),
      javaSrc, subProjects);

    write(SExpFormatter.toSExp(config).replaceAll("\r\n", "\n") + "\n", out);
  }


  private static class Pair<F, S> {
    public final F _1;
    public final S _2;

    public Pair(final F fst, final S snd) {
      this._1 = fst;
      this._2 = snd;
    }
  }
}
