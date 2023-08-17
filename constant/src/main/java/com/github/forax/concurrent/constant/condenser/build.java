package com.github.forax.concurrent.constant.condenser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.github.forax.concurrent.constant.condenser.build.Dependency.DependencyMode.CLASSPATH;
import static com.github.forax.concurrent.constant.condenser.build.Dependency.DependencyMode.MODULEPATH;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;

// run using Leyden premain branch
class build {

  sealed interface Dependency {
    enum DependencyMode { CLASSPATH, MODULEPATH }

    record MavenDependency(String group, String artifact, String version, DependencyMode mode) implements Dependency {
      public MavenDependency {
        Runtime.Version.parse(version);
      }

      public Path groupPath() {
        return Path.of(group.replace('.', '/'));
      }

      public String jarName() {
        return artifact + "-" + version + ".jar";
      }
    }

    record LocalDependency(Path path, String jarName, DependencyMode mode) implements Dependency { }

    String jarName();
    DependencyMode mode();
  }

  sealed interface CDSOption {
    record Share(ShareMode mode) implements CDSOption {
      enum ShareMode { OFF, DUMP }
    }
    record DumpLoadedClassList(Path classListFile) implements CDSOption {}
    record SharedClassListFile(Path classListFile) implements CDSOption {}
    record SharedArchiveFile(Path sharedArchiveFile) implements CDSOption {}
    record ArchiveClassesAtExit(Path sharedArchiveFile) implements CDSOption {}
    enum RecordTraining implements CDSOption { RECORD_TRAINING }
    enum ReplayTraining implements CDSOption { REPLAY_TRAINING }
    enum StoreSharedCode implements CDSOption { STORE_SHARED_CODE }
    enum LoadSharedCode implements CDSOption { LOAD_SHARED_CODE }
    record SharedCodeArchive(Path sharedCodeArchiveFile) implements CDSOption {}
    enum ArchiveInvokeDynamic implements CDSOption { ARCHIVE_INVOKE_DYNAMIC }
    record ReservedSharedCodeSize(String codeCacheSize) implements CDSOption {}
  }

  record JavaHome(Path javaHome) {}

  record JavaCmd(
      List<Dependency> dependencies,
      JavaHome jdkHome
  ) {}

  private static Path dependencyDirectory(Path mavenPath, Dependency dependency) {
    if (dependency instanceof Dependency.MavenDependency mavenDependency) {
      return mavenPath.resolve(mavenDependency.groupPath()).resolve(mavenDependency.artifact).resolve(mavenDependency.version);
    }
    if (dependency instanceof Dependency.LocalDependency localDependency) {
      return localDependency.path;
    }
    throw new AssertionError();
  }

  private static Path dependencyListPath(Path mavenPath, Dependency dependency) {
    var directory = dependencyDirectory(mavenPath, dependency);
    return switch (dependency.mode()) {
      case CLASSPATH -> directory.resolve(dependency.jarName());
      case MODULEPATH -> directory;
    };
  }

  private static String dependencyListPath(Path mavenPath, List<Dependency> dependencies) {
    return dependencies.stream()
        .map(dep -> dependencyListPath(mavenPath, dep))
        .peek(path -> {
          if (!Files.exists(path)) {
            throw new UncheckedIOException(new IOException("unknown dependency " + path));
          }
        })
        .map(Path::toString)
        .collect(joining(":"));
  }

  private static String toCommandOption(CDSOption option) {
    if (option instanceof CDSOption.Share share) {
      return switch (share.mode) {
        case OFF -> "-Xshare:off";
        case DUMP -> "-Xshare:dump";
      };
    }
    if (option instanceof CDSOption.DumpLoadedClassList dumpLoadedClassList) {
      return "-XX:DumpLoadedClassList=" + dumpLoadedClassList.classListFile;
    }
    if (option instanceof CDSOption.SharedClassListFile sharedClassListFile) {
      return "-XX:SharedClassListFile=" + sharedClassListFile.classListFile;
    }
    if (option instanceof CDSOption.SharedArchiveFile sharedArchiveFile) {
      return "-XX:SharedArchiveFile=" + sharedArchiveFile.sharedArchiveFile;
    }
    if (option instanceof CDSOption.ArchiveClassesAtExit archiveClassesAtExit) {
      return "-XX:ArchiveClassesAtExit=" + archiveClassesAtExit.sharedArchiveFile;
    }
    if (option == CDSOption.RecordTraining.RECORD_TRAINING) {
      return "-XX:+RecordTraining";
    }
    if (option == CDSOption.ReplayTraining.REPLAY_TRAINING) {
      return "-XX:+ReplayTraining";
    }
    if (option == CDSOption.StoreSharedCode.STORE_SHARED_CODE) {
      return "-XX:+StoreSharedCode";
    }
    if (option == CDSOption.LoadSharedCode.LOAD_SHARED_CODE) {
      return "-XX:+LoadSharedCode";
    }
    if (option instanceof CDSOption.SharedCodeArchive sharedCodeArchive) {
      return "-XX:SharedCodeArchive=" + sharedCodeArchive.sharedCodeArchiveFile;
    }
    if (option == CDSOption.ArchiveInvokeDynamic.ARCHIVE_INVOKE_DYNAMIC) {
      return "-XX:+ArchiveInvokeDynamic";
    }
    if (option instanceof CDSOption.ReservedSharedCodeSize reservedSharedCodeSize) {
      return "-XX:ReservedSharedCodeSize=" + reservedSharedCodeSize.codeCacheSize;
    }
    throw new AssertionError();
  }

  record Log(String log) {}

  public static void java(JavaCmd app, List<CDSOption> cdsOptions, Log log, String className, String... args) throws IOException, InterruptedException {
    var javaCmd = app.jdkHome.javaHome.resolve("bin").resolve("java");
    var userHome = Path.of(System.getProperty("user.home"));
    var mavenPath = userHome.resolve(".m2").resolve("repository");
    var groupByMode = app.dependencies.stream()
        .collect(groupingBy(Dependency::mode));
    var modulePath = dependencyListPath(mavenPath, groupByMode.getOrDefault(MODULEPATH, List.of()));
    var classPath = dependencyListPath(mavenPath, groupByMode.getOrDefault(CLASSPATH, List.of()));

    var commands = Stream.of(
        Stream.of(javaCmd.toString()),
        Stream.of(modulePath).filter(Predicate.not(String::isEmpty)).flatMap(p -> Stream.of("--module-path", p)),
        Stream.of(classPath).filter(Predicate.not(String::isEmpty)).flatMap(p -> Stream.of("--class-path", p)),
        cdsOptions.stream().map(build::toCommandOption),
        Stream.of(log).filter(Objects::nonNull).map(Log::log),
        Stream.of(className).filter(Objects::nonNull),
        Arrays.stream(args)
        )
        .flatMap(s -> s)
        .toList();
    System.out.println(String.join(" ", commands));
    var process = new ProcessBuilder(commands)
        .inheritIO()
        .start();
    var exitCode = process.waitFor();
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    var leydenJDK = Path.of("/Users/forax/leyden-premain/leyden/build/macosx-aarch64-server-release/images/jdk/");

    var className = "com.github.forax.concurrent.constant.condenser.ComputedConstantRewriter";
    var javaCmd = new JavaCmd(
        List.of(
            new Dependency.MavenDependency("org.ow2.asm", "asm", "9.5", CLASSPATH),
            new Dependency.LocalDependency(Path.of("constant/target"), "constant-1.0-SNAPSHOT.jar", CLASSPATH)
        ),
        new JavaHome(leydenJDK)
    );

    var sharedArchiveFile = Path.of("computed-constant-rewriter.jsa");
    var sharedCodeArchiveFile = Path.of("computed-constant-rewriter.jsca");
    if (Files.exists(sharedArchiveFile)) {
      System.out.println("replay ...");

      // replay archive with shared code + training data
      java(javaCmd, List.of(
              new CDSOption.SharedArchiveFile(sharedArchiveFile),
              new CDSOption.SharedCodeArchive(sharedCodeArchiveFile),
              CDSOption.ReplayTraining.REPLAY_TRAINING,
              CDSOption.LoadSharedCode.LOAD_SHARED_CODE,
              new CDSOption.ReservedSharedCodeSize("1000M")
          ),
          new Log("-Xlog:sca*=trace:file=replay-store-sc.log::filesize=0"),
          className,
          "constant/target/classes", "constant/target/rewriter");
    } else {
      System.out.println("training ...");
      var sharedClasslistFile = Path.of("computed-constant-rewriter.classlist");

      // generate classlist
      java(javaCmd, List.of(
              new CDSOption.Share(CDSOption.Share.ShareMode.OFF),
              new CDSOption.DumpLoadedClassList(sharedClasslistFile)
          ),
          null,
          className,
          "constant/target/classes", "constant/target/rewriter");

      // create archive
      java(javaCmd, List.of(
              new CDSOption.Share(CDSOption.Share.ShareMode.DUMP),
              new CDSOption.SharedArchiveFile(sharedArchiveFile),
              new CDSOption.SharedClassListFile(sharedClasslistFile),
              CDSOption.ArchiveInvokeDynamic.ARCHIVE_INVOKE_DYNAMIC
          ),
          new Log("-Xlog:cds=debug,cds+class=debug:file=training.dump.log::filesize=0"),
          null);

      // add shared code + training data
      java(javaCmd, List.of(
              new CDSOption.ArchiveClassesAtExit(sharedArchiveFile),
              new CDSOption.SharedCodeArchive(sharedCodeArchiveFile),
              CDSOption.RecordTraining.RECORD_TRAINING,
              CDSOption.StoreSharedCode.STORE_SHARED_CODE,
              new CDSOption.ReservedSharedCodeSize("1000M")
          ),
          new Log("-Xlog:cds=debug,cds+class=debug:file=training.dump.log::filesize=0"),
          className,
          "constant/target/classes", "constant/target/rewriter");
    }
  }
}