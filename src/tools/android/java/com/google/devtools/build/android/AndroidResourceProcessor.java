// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.android;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.android.Converters.ExistingPathConverter;
import com.google.devtools.build.android.Converters.FullRevisionConverter;
import com.google.devtools.build.android.SplitConfigurationFilter.UnrecognizedSplitsException;
import com.google.devtools.build.android.resources.RClassGenerator;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.TriState;

import com.android.annotations.Nullable;
import com.android.builder.core.VariantConfiguration;
import com.android.builder.dependency.SymbolFileProvider;
import com.android.builder.internal.SymbolLoader;
import com.android.builder.internal.SymbolWriter;
import com.android.builder.model.AaptOptions;
import com.android.ide.common.internal.CommandLineRunner;
import com.android.ide.common.internal.ExecutorSingleton;
import com.android.ide.common.internal.LoggedErrorException;
import com.android.ide.common.internal.PngCruncher;
import com.android.ide.common.res2.AssetMerger;
import com.android.ide.common.res2.AssetSet;
import com.android.ide.common.res2.MergedAssetWriter;
import com.android.ide.common.res2.MergedResourceWriter;
import com.android.ide.common.res2.MergingException;
import com.android.ide.common.res2.ResourceMerger;
import com.android.ide.common.res2.ResourceSet;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.ManifestMerger2.Invoker;
import com.android.manifmerger.ManifestMerger2.Invoker.Feature;
import com.android.manifmerger.ManifestMerger2.MergeFailureException;
import com.android.manifmerger.ManifestMerger2.MergeType;
import com.android.manifmerger.ManifestMerger2.SystemProperty;
import com.android.manifmerger.MergingReport;
import com.android.manifmerger.PlaceholderHandler;
import com.android.manifmerger.XmlDocument;
import com.android.sdklib.repository.FullRevision;
import com.android.utils.StdLogger;

import org.xml.sax.SAXException;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

/**
 * Provides a wrapper around the AOSP build tools for resource processing.
 */
public class AndroidResourceProcessor {
  /**
   * Options class containing flags for Aapt setup.
   */
  public static final class AaptConfigOptions extends OptionsBase {
    @Option(name = "buildToolsVersion",
        defaultValue = "null",
        converter = FullRevisionConverter.class,
        category = "config",
        help = "Version of the build tools (e.g. aapt) being used, e.g. 23.0.2")
    public FullRevision buildToolsVersion;

    @Option(name = "aapt",
        defaultValue = "null",
        converter = ExistingPathConverter.class,
        category = "tool",
        help = "Aapt tool location for resource packaging.")
    public Path aapt;

    @Option(name = "annotationJar",
        defaultValue = "null",
        converter = ExistingPathConverter.class,
        category = "tool",
        help = "Annotation Jar for builder invocations.")
    public Path annotationJar;

    @Option(name = "androidJar",
        defaultValue = "null",
        converter = ExistingPathConverter.class,
        category = "tool",
        help = "Path to the android jar for resource packaging and building apks.")
    public Path androidJar;

    @Option(name = "useAaptCruncher",
        defaultValue = "auto",
        category = "config",
        help = "Use the legacy aapt cruncher, defaults to true for non-LIBRARY packageTypes. "
            + " LIBRARY packages do not benefit from the additional processing as the resources"
            + " will need to be reprocessed during the generation of the final apk. See"
            + " https://code.google.com/p/android/issues/detail?id=67525 for a discussion of the"
            + " different png crunching methods.")
    public TriState useAaptCruncher;

    @Option(name = "uncompressedExtensions",
        defaultValue = "",
        converter = CommaSeparatedOptionListConverter.class,
        category = "config",
        help = "A list of file extensions not to compress.")
    public List<String> uncompressedExtensions;

    @Option(name = "assetsToIgnore",
        defaultValue = "",
        converter = CommaSeparatedOptionListConverter.class,
        category = "config",
        help = "A list of assets extensions to ignore.")
    public List<String> assetsToIgnore;

    @Option(name = "debug",
        defaultValue = "false",
        category = "config",
        help = "Indicates if it is a debug build.")
    public boolean debug;

    @Option(name = "resourceConfigs",
        defaultValue = "",
        converter = CommaSeparatedOptionListConverter.class,
        category = "config",
        help = "A list of resource config filters to pass to aapt.")
    public List<String> resourceConfigs;

    private static final String ANDROID_SPLIT_DOCUMENTATION_URL =
        "https://developer.android.com/guide/topics/resources/providing-resources.html"
        + "#QualifierRules";

    @Option(
      name = "split",
      defaultValue = "required but ignored due to allowMultiple",
      category = "config",
      allowMultiple = true,
      help =
          "An individual split configuration to pass to aapt."
              + " Each split is a list of configuration filters separated by commas."
              + " Configuration filters are lists of configuration qualifiers separated by dashes,"
              + " as used in resource directory names and described on the Android developer site: "
              + ANDROID_SPLIT_DOCUMENTATION_URL
              + " For example, a split might be 'en-television,en-xxhdpi', containing English"
              + " assets which either are for TV screens or are extra extra high resolution."
              + " Multiple splits can be specified by passing this flag multiple times."
              + " Each split flag will produce an additional output file, named by replacing the"
              + " commas in the split specification with underscores, and appending the result to"
              + " the output package name following an underscore."
    )
    public List<String> splits;
  }

  /**
   * {@link AaptOptions} backed by an {@link AaptConfigOptions}.
   */
  public static final class FlagAaptOptions implements AaptOptions {
    private final AaptConfigOptions options;

    public FlagAaptOptions(AaptConfigOptions options) {
      this.options = options;
    }

    @Override
    public boolean getUseAaptPngCruncher() {
      return options.useAaptCruncher != TriState.NO;
    }

    @Override
    public Collection<String> getNoCompress() {
      if (!options.uncompressedExtensions.isEmpty()) {
        return options.uncompressedExtensions;
      }
      return ImmutableList.of();
    }

    @Override
    public String getIgnoreAssets() {
      if (!options.assetsToIgnore.isEmpty()) {
        return Joiner.on(":").join(options.assetsToIgnore);
      }
      return null;
    }

    @Override
    public boolean getFailOnMissingConfigEntry() {
      return false;
    }
  }

  /** Shutdowns and verifies that no tasks are running in the executor service. */
  private static final class ExecutorServiceCloser implements Closeable {
    private final ListeningExecutorService executorService;
    private ExecutorServiceCloser(ListeningExecutorService executorService) {
      this.executorService = executorService;
    }

    @Override
    public void close() throws IOException {
      List<Runnable> unfinishedTasks = executorService.shutdownNow();
      if (!unfinishedTasks.isEmpty()) {
        throw new IOException(
            "Shutting down the executor with unfinished tasks:" + unfinishedTasks);
      }
    }

    public static Closeable createWith(ListeningExecutorService executorService) {
      return new ExecutorServiceCloser(executorService);
    }
  }

  private static final ImmutableMap<SystemProperty, String> SYSTEM_PROPERTY_NAMES = Maps.toMap(
      Arrays.asList(SystemProperty.values()), new Function<SystemProperty, String>() {
        @Override
        public String apply(SystemProperty property) {
          if (property == SystemProperty.PACKAGE) {
            return "applicationId";
          } else {
            return property.toCamelCase();
          }
        }
      });

  private static final Pattern HEX_REGEX = Pattern.compile("0x[0-9A-Fa-f]{8}");
  private final StdLogger stdLogger;

  public AndroidResourceProcessor(StdLogger stdLogger) {
    this.stdLogger = stdLogger;
  }

  /**
   * Copies the R.txt to the expected place.
   *
   * @param generatedSourceRoot The path to the generated R.txt.
   * @param rOutput The Path to write the R.txt.
   * @param staticIds Boolean that indicates if the ids should be set to 0x1 for caching purposes.
   */
  public void copyRToOutput(Path generatedSourceRoot, Path rOutput, boolean staticIds) {
    try {
      Files.createDirectories(rOutput.getParent());
      final Path source = generatedSourceRoot.resolve("R.txt");
      if (Files.exists(source)) {
        if (staticIds) {
          String contents = HEX_REGEX.matcher(Joiner.on("\n").join(
              Files.readAllLines(source, UTF_8))).replaceAll("0x1");
          Files.write(rOutput, contents.getBytes(UTF_8));
        } else {
          Files.copy(source, rOutput);
        }
      } else {
        // The R.txt wasn't generated, create one for future inheritance, as Bazel always requires
        // outputs. This state occurs when there are no resource directories.
        Files.createFile(rOutput);
      }
      // Set to the epoch for caching purposes.
      Files.setLastModifiedTime(rOutput, FileTime.fromMillis(0L));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a zip archive from all found R.java files.
   */
  public void createSrcJar(Path generatedSourcesRoot, Path srcJar, boolean staticIds) {
    try {
      Files.createDirectories(srcJar.getParent());
      try (final ZipOutputStream zip = new ZipOutputStream(
          new BufferedOutputStream(Files.newOutputStream(srcJar)))) {
        SymbolFileSrcJarBuildingVisitor visitor =
            new SymbolFileSrcJarBuildingVisitor(zip, generatedSourcesRoot, staticIds);
        Files.walkFileTree(generatedSourcesRoot, visitor);
      }
      // Set to the epoch for caching purposes.
      Files.setLastModifiedTime(srcJar, FileTime.fromMillis(0L));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a zip archive from all found R.class (and inner class) files.
   */
  public void createClassJar(Path generatedClassesRoot, Path classJar) {
    try {
      Files.createDirectories(classJar.getParent());
      try (final ZipOutputStream zip = new ZipOutputStream(
          new BufferedOutputStream(Files.newOutputStream(classJar)))) {
        ClassJarBuildingVisitor visitor = new ClassJarBuildingVisitor(zip, generatedClassesRoot);
        Files.walkFileTree(generatedClassesRoot, visitor);
        visitor.writeManifestContent();
      }
      // Set to the epoch for caching purposes.
      Files.setLastModifiedTime(classJar, FileTime.fromMillis(0L));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Copies the AndroidManifest.xml to the specified output location.
   *
   * @param androidData The MergedAndroidData which contains the manifest to be written to
   *     manifestOut.
   * @param manifestOut The Path to write the AndroidManifest.xml.
   */
  public void copyManifestToOutput(MergedAndroidData androidData, Path manifestOut) {
    try {
      Files.createDirectories(manifestOut.getParent());
      Files.copy(androidData.getManifest(), manifestOut);
      // Set to the epoch for caching purposes.
      Files.setLastModifiedTime(manifestOut, FileTime.fromMillis(0L));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a zip file containing the provided android resources and assets.
   *
   * @param resourcesRoot The root containing android resources to be written.
   * @param assetsRoot The root containing android assets to be written.
   * @param output The path to write the zip file
   * @throws IOException
   */
  public void createResourcesZip(Path resourcesRoot, Path assetsRoot, Path output)
      throws IOException {
    try (ZipOutputStream zout = new ZipOutputStream(
        new BufferedOutputStream(Files.newOutputStream(output)))) {
      if (Files.exists(resourcesRoot)) {
        Files.walkFileTree(resourcesRoot, new ZipBuilderVisitor(zout, resourcesRoot, "res"));
      }
      if (Files.exists(assetsRoot)) {
        Files.walkFileTree(assetsRoot, new ZipBuilderVisitor(zout, assetsRoot, "assets"));
      }
    }
  }

  // TODO(bazel-team): Clean up this method call -- 13 params is too many.
  /** Processes resources for generated sources, configs and packaging resources. */
  public void processResources(
      Path aapt,
      Path androidJar,
      @Nullable FullRevision buildToolsVersion,
      VariantConfiguration.Type variantType,
      boolean debug,
      String customPackageForR,
      AaptOptions aaptOptions,
      Collection<String> resourceConfigs,
      Collection<String> splits,
      MergedAndroidData primaryData,
      List<DependencyAndroidData> dependencyData,
      Path sourceOut,
      Path packageOut,
      Path proguardOut,
      Path mainDexProguardOut,
      Path publicResourcesOut)
      throws IOException, InterruptedException, LoggedErrorException, UnrecognizedSplitsException {
    Path androidManifest = primaryData.getManifest();
    Path resourceDir = primaryData.getResourceDir();
    Path assetsDir = primaryData.getAssetDir();
    if (publicResourcesOut != null) {
      prepareOutputPath(publicResourcesOut.getParent());
    }

    AaptCommandBuilder commandBuilder =
        new AaptCommandBuilder(aapt)
        .forBuildToolsVersion(buildToolsVersion)
        .forVariantType(variantType)
        // first argument is the command to be executed, "package"
        .add("package")
        // If the logger is verbose, set aapt to be verbose
        .when(stdLogger.getLevel() == StdLogger.Level.VERBOSE).thenAdd("-v")
        // Overwrite existing files, if they exist.
        .add("-f")
        // Resources are precrunched in the merge process.
        .add("--no-crunch")
        // Do not automatically generate versioned copies of vector XML resources.
        .whenVersionIsAtLeast(new FullRevision(23)).thenAdd("--no-version-vectors")
        // Add the android.jar as a base input.
        .add("-I", androidJar)
        // Add the manifest for validation.
        .add("-M", androidManifest.toAbsolutePath())
        // Maybe add the resources if they exist
        .when(Files.isDirectory(resourceDir)).thenAdd("-S", resourceDir)
        // Maybe add the assets if they exist
        .when(Files.isDirectory(assetsDir)).thenAdd("-A", assetsDir)
        // Outputs
        .when(sourceOut != null).thenAdd("-m")
        .add("-J", prepareOutputPath(sourceOut))
        .add("--output-text-symbols", prepareOutputPath(sourceOut))
        .add("-F", packageOut)
        .add("-G", proguardOut)
        .whenVersionIsAtLeast(new FullRevision(24)).thenAdd("-D", mainDexProguardOut)
        .add("-P", publicResourcesOut)
        .when(debug).thenAdd("--debug-mode")
        .add("--custom-package", customPackageForR)
        // If it is a library, do not generate final java ids.
        .whenVariantIs(VariantConfiguration.Type.LIBRARY).thenAdd("--non-constant-id")
        .add("--ignore-assets", aaptOptions.getIgnoreAssets())
        .when(aaptOptions.getFailOnMissingConfigEntry()).thenAdd("--error-on-missing-config-entry")
        // Never compress apks.
        .add("-0", "apk")
        // Add custom no-compress extensions.
        .addRepeated("-0", aaptOptions.getNoCompress())
        // Filter by resource configuration type.
        .add("-c", Joiner.on(',').join(resourceConfigs))
        // Split APKs if any splits were specified.
        .whenVersionIsAtLeast(new FullRevision(23)).thenAddRepeated("--split", splits);

    new CommandLineRunner(stdLogger).runCmdLine(commandBuilder.build(), null);

    // The R needs to be created for each library in the dependencies,
    // but only if the current project is not a library.
    if (sourceOut != null && variantType != VariantConfiguration.Type.LIBRARY) {
      writeDependencyPackageRJavaFiles(
          dependencyData, customPackageForR, androidManifest, sourceOut);
    }
    // Reset the output date stamps.
    if (proguardOut != null) {
      Files.setLastModifiedTime(proguardOut, FileTime.fromMillis(0L));
    }
    if (mainDexProguardOut != null) {
      Files.setLastModifiedTime(mainDexProguardOut, FileTime.fromMillis(0L));
    }
    if (packageOut != null) {
      Files.setLastModifiedTime(packageOut, FileTime.fromMillis(0L));
      if (!splits.isEmpty()) {
        Iterable<Path> splitFilenames = findAndRenameSplitPackages(packageOut, splits);
        for (Path splitFilename : splitFilenames) {
          Files.setLastModifiedTime(splitFilename, FileTime.fromMillis(0L));
        }
      }
    }
    if (publicResourcesOut != null && Files.exists(publicResourcesOut)) {
      Files.setLastModifiedTime(publicResourcesOut, FileTime.fromMillis(0L));
    }
  }

  /** Task to parse java package from AndroidManifest.xml */
  private static final class PackageParsingTask implements Callable<String> {

    private final File manifest;

    PackageParsingTask(File manifest) {
      this.manifest = manifest;
    }

    @Override
    public String call() throws Exception {
      return VariantConfiguration.getManifestPackage(manifest);
    }
  }

  /** Task to load and parse R.txt symbols */
  private static final class SymbolLoadingTask implements Callable<Object> {

    private final SymbolLoader symbolLoader;

    SymbolLoadingTask(SymbolLoader symbolLoader) {
      this.symbolLoader = symbolLoader;
    }

    @Override
    public Object call() throws Exception {
      symbolLoader.load();
      return null;
    }
  }

  @Nullable
  public SymbolLoader loadResourceSymbolTable(
      List<SymbolFileProvider> libraries,
      String appPackageName,
      Path primaryRTxt,
      Multimap<String, SymbolLoader> libMap) throws IOException {
    // The reported availableProcessors may be higher than the actual resources
    // (on a shared system). On the other hand, a lot of the work is I/O, so it's not completely
    // CPU bound. As a compromise, divide by 2 the reported availableProcessors.
    int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(numThreads));
    try (Closeable closeable = ExecutorServiceCloser.createWith(executorService)) {
      // Load the package names from the manifest files.
      Map<SymbolFileProvider, ListenableFuture<String>> packageJobs = new HashMap<>();
      for (final SymbolFileProvider lib : libraries) {
        packageJobs.put(lib, executorService.submit(new PackageParsingTask(lib.getManifest())));
      }
      Map<SymbolFileProvider, String> packageNames = new HashMap<>();
      try {
        for (Map.Entry<SymbolFileProvider, ListenableFuture<String>> entry : packageJobs
            .entrySet()) {
          packageNames.put(entry.getKey(), entry.getValue().get());
        }
      } catch (InterruptedException | ExecutionException e) {
        throw new IOException("Failed to load package name: ", e);
      }
      // Associate the packages with symbol files.
      for (SymbolFileProvider lib : libraries) {
        String packageName = packageNames.get(lib);
        // If the library package matches the app package skip -- the final app resource IDs are
        // stored in the primaryRTxt file.
        if (appPackageName.equals(packageName)) {
          continue;
        }
        File rFile = lib.getSymbolFile();
        // If the library has no resource, this file won't exist.
        if (rFile.isFile()) {
          SymbolLoader libSymbols = new SymbolLoader(rFile, stdLogger);
          libMap.put(packageName, libSymbols);
        }
      }
      // Even if there are no libraries, load fullSymbolValues, in case we only have resources
      // defined for the binary.
      File primaryRTxtFile = primaryRTxt.toFile();
      SymbolLoader fullSymbolValues = null;
      if (primaryRTxtFile.isFile()) {
        fullSymbolValues = new SymbolLoader(primaryRTxtFile, stdLogger);
      }
      // Now load the symbol files in parallel.
      List<ListenableFuture<?>> loadJobs = new ArrayList<>();
      Iterable<SymbolLoader> toLoad = fullSymbolValues != null
          ? Iterables.concat(libMap.values(), ImmutableList.of(fullSymbolValues))
          : libMap.values();
      for (final SymbolLoader loader : toLoad) {
        loadJobs.add(executorService.submit(new SymbolLoadingTask(loader)));
      }
      try {
        Futures.allAsList(loadJobs).get();
      } catch (InterruptedException | ExecutionException e) {
        throw new IOException("Failed to load SymbolFile: ", e);
      }
      return fullSymbolValues;
    }
  }

  void writeDependencyPackageRJavaFiles(
      List<DependencyAndroidData> dependencyData,
      String customPackageForR,
      Path androidManifest,
      Path sourceOut) throws IOException {
    List<SymbolFileProvider> libraries = new ArrayList<>();
    for (DependencyAndroidData dataDep : dependencyData) {
      SymbolFileProvider library = dataDep.asSymbolFileProvider();
      libraries.add(library);
    }
    String appPackageName = customPackageForR;
    if (appPackageName == null) {
      appPackageName = VariantConfiguration.getManifestPackage(androidManifest.toFile());
    }
    Multimap<String, SymbolLoader> libSymbolMap = ArrayListMultimap.create();
    Path primaryRTxt = sourceOut != null ? sourceOut.resolve("R.txt") : null;
    if (primaryRTxt != null && !libraries.isEmpty()) {
      SymbolLoader fullSymbolValues = loadResourceSymbolTable(libraries,
          appPackageName, primaryRTxt, libSymbolMap);
      if (fullSymbolValues != null) {
        writePackageRJavaFiles(libSymbolMap, fullSymbolValues, sourceOut);
      }
    }
  }

  private void writePackageRJavaFiles(
      Multimap<String, SymbolLoader> libMap,
      SymbolLoader fullSymbolValues,
      Path sourceOut) throws IOException {
    // Loop on all the package name, merge all the symbols to write, and write.
    for (String packageName : libMap.keySet()) {
      Collection<SymbolLoader> symbols = libMap.get(packageName);
      SymbolWriter writer = new SymbolWriter(sourceOut.toString(), packageName, fullSymbolValues);
      for (SymbolLoader symbolLoader : symbols) {
        writer.addSymbolsToWrite(symbolLoader);
      }
      writer.write();
    }
  }

  void writePackageRClasses(
      Multimap<String, SymbolLoader> libMap,
      SymbolLoader fullSymbolValues,
      String appPackageName,
      Path classesOut,
      boolean finalFields) throws IOException {
    for (String packageName : libMap.keySet()) {
      Collection<SymbolLoader> symbols = libMap.get(packageName);
      RClassGenerator classWriter =
          new RClassGenerator(classesOut.toFile(), packageName, fullSymbolValues, finalFields);
      for (SymbolLoader symbolLoader : symbols) {
        classWriter.addSymbolsToWrite(symbolLoader);
      }
      classWriter.write();
    }
    // Unlike the R.java generation, we also write the app's R.class file so that the class
    // jar file can be complete (aapt doesn't generate it for us).
    RClassGenerator classWriter =
        new RClassGenerator(classesOut.toFile(), appPackageName, fullSymbolValues, finalFields);
    classWriter.addSymbolsToWrite(fullSymbolValues);
    classWriter.write();
  }

  /** Finds aapt's split outputs and renames them according to the input flags. */
  private Iterable<Path> findAndRenameSplitPackages(Path packageOut, Iterable<String> splits)
      throws UnrecognizedSplitsException, IOException {
    String prefix = packageOut.getFileName().toString() + "_";
    // The regex java string literal below is received as [\\{}\[\]*?] by the regex engine,
    // which produces a character class containing \{}[]*?
    // The replacement string literal is received as \\$0 by the regex engine, which places
    // a backslash before the match.
    String prefixGlob = prefix.replaceAll("[\\\\{}\\[\\]*?]", "\\\\$0") + "*";
    Path outputDirectory = packageOut.getParent();
    ImmutableList.Builder<String> filenameSuffixes = new ImmutableList.Builder<>();
    try (DirectoryStream<Path> glob = Files.newDirectoryStream(outputDirectory, prefixGlob)) {
      for (Path file : glob) {
        filenameSuffixes.add(file.getFileName().toString().substring(prefix.length()));
      }
    }
    Map<String, String> outputs =
        SplitConfigurationFilter.mapFilenamesToSplitFlags(filenameSuffixes.build(), splits);
    ImmutableList.Builder<Path> outputPaths = new ImmutableList.Builder<>();
    for (Map.Entry<String, String> splitMapping : outputs.entrySet()) {
      Path resultPath = packageOut.resolveSibling(prefix + splitMapping.getValue());
      outputPaths.add(resultPath);
      if (!splitMapping.getKey().equals(splitMapping.getValue())) {
        Path sourcePath = packageOut.resolveSibling(prefix + splitMapping.getKey());
        Files.move(sourcePath, resultPath);
      }
    }
    return outputPaths.build();
  }

  public MergedAndroidData processManifest(
      VariantConfiguration.Type variantType,
      String customPackageForR,
      String applicationId,
      int versionCode,
      String versionName,
      MergedAndroidData primaryData,
      Path processedManifest) throws IOException {

    ManifestMerger2.MergeType mergeType = variantType == VariantConfiguration.Type.DEFAULT
        ? ManifestMerger2.MergeType.APPLICATION : ManifestMerger2.MergeType.LIBRARY;

    String newManifestPackage = variantType == VariantConfiguration.Type.DEFAULT
        ? applicationId : customPackageForR;

    if (versionCode != -1 || versionName != null || newManifestPackage != null) {
      Files.createDirectories(processedManifest.getParent());

      // The generics on Invoker don't make sense, so ignore them.
      @SuppressWarnings("unchecked")
      Invoker<?> manifestMergerInvoker =
          ManifestMerger2.newMerger(primaryData.getManifest().toFile(), stdLogger, mergeType);
      // Stamp new package
      if (newManifestPackage != null) {
        manifestMergerInvoker.setOverride(SystemProperty.PACKAGE, newManifestPackage);
      }
      // Stamp version and applicationId (if provided) into the manifest
      if (versionCode > 0) {
        manifestMergerInvoker.setOverride(SystemProperty.VERSION_CODE, String.valueOf(versionCode));
      }
      if (versionName != null) {
        manifestMergerInvoker.setOverride(SystemProperty.VERSION_NAME, versionName);
      }

      if (mergeType == ManifestMerger2.MergeType.APPLICATION) {
        manifestMergerInvoker.withFeatures(Invoker.Feature.REMOVE_TOOLS_DECLARATIONS);
      }

      try {
        MergingReport mergingReport = manifestMergerInvoker.merge();
        switch (mergingReport.getResult()) {
          case WARNING:
            mergingReport.log(stdLogger);
            writeMergedManifest(mergingReport, processedManifest);
            break;
          case SUCCESS:
            writeMergedManifest(mergingReport, processedManifest);
            break;
          case ERROR:
            mergingReport.log(stdLogger);
            throw new RuntimeException(mergingReport.getReportString());
          default:
            throw new RuntimeException("Unhandled result type : " + mergingReport.getResult());
        }
      } catch (
          IOException | SAXException | ParserConfigurationException | MergeFailureException e) {
        throw new RuntimeException(e);
      }
      return new MergedAndroidData(primaryData.getResourceDir(), primaryData.getAssetDir(),
          processedManifest);
    }
    return primaryData;
  }

  /**
   * Merge several manifests into one and perform placeholder substitutions. This operation uses
   * Gradle semantics.
   *
   * @param manifest The primary manifest of the merge.
   * @param mergeeManifests Manifests to be merged into {@code manifest}.
   * @param mergeType Whether the merger should operate in application or library mode.
   * @param values A map of strings to be used as manifest placeholders and overrides. packageName
   *     is the only disallowed value and will be ignored.
   * @param output The path to write the resultant manifest to.
   * @return The path of the resultant manifest, either {@code output}, or {@code manifest} if no
   *     merging was required.
   * @throws IOException if there was a problem writing the merged manifest.
   */
  public Path mergeManifest(
      Path manifest,
      List<Path> mergeeManifests,
      MergeType mergeType,
      Map<String, String> values,
      Path output) throws IOException {
    if (mergeeManifests.isEmpty() && values.isEmpty()) {
      return manifest;
    }

    Invoker<?> manifestMerger = ManifestMerger2.newMerger(manifest.toFile(), stdLogger, mergeType);
    if (mergeType == MergeType.APPLICATION) {
      manifestMerger.withFeatures(Feature.REMOVE_TOOLS_DECLARATIONS);
    }

    // Add mergee manifests
    for (Path mergeeManifest : mergeeManifests) {
      manifestMerger.addLibraryManifest(mergeeManifest.toFile());
    }

    // Extract SystemProperties from the provided values.
    Map<String, String> placeholders = new HashMap<>(values);
    for (SystemProperty property : SystemProperty.values()) {
      if (values.containsKey(SYSTEM_PROPERTY_NAMES.get(property))) {
        manifestMerger.setOverride(property, values.get(SYSTEM_PROPERTY_NAMES.get(property)));

        // The manifest merger does not allow explicitly specifying either applicationId or
        // packageName as placeholders if SystemProperty.PACKAGE is specified. It forces these
        // placeholders to have the same value as specified by SystemProperty.PACKAGE.
        if (property == SystemProperty.PACKAGE) {
          placeholders.remove(PlaceholderHandler.APPLICATION_ID);
          placeholders.remove(PlaceholderHandler.PACKAGE_NAME);
        }
      }
    }

    // Add placeholders for all values.
    // packageName is populated from either the applicationId override or from the manifest itself;
    // it cannot be manually specified.
    placeholders.remove(PlaceholderHandler.PACKAGE_NAME);
    manifestMerger.setPlaceHolderValues(placeholders);

    try {
      MergingReport mergingReport = manifestMerger.merge();
      switch (mergingReport.getResult()) {
        case WARNING:
          mergingReport.log(stdLogger);
          Files.createDirectories(output.getParent());
          writeMergedManifest(mergingReport, output);
          break;
        case SUCCESS:
          Files.createDirectories(output.getParent());
          writeMergedManifest(mergingReport, output);
          break;
        case ERROR:
          mergingReport.log(stdLogger);
          throw new RuntimeException(mergingReport.getReportString());
        default:
          throw new RuntimeException("Unhandled result type : " + mergingReport.getResult());
      }
    } catch (
        SAXException | ParserConfigurationException | MergeFailureException e) {
      throw new RuntimeException(e);
    }

    return output;
  }

  private void writeMergedManifest(MergingReport mergingReport,
      Path manifestOut) throws IOException, SAXException, ParserConfigurationException {
    XmlDocument xmlDocument = mergingReport.getMergedDocument().get();
    String annotatedDocument = mergingReport.getActions().blame(xmlDocument);
    stdLogger.verbose(annotatedDocument);
    Files.write(
        manifestOut, xmlDocument.prettyPrint().getBytes(UTF_8));
  }

  /**
   * Overwrite the package attribute of {@code <manifest>} in an AndroidManifest.xml file.
   *
   * @param manifest The input manifest.
   * @param customPackage The package to write to the manifest.
   * @param output The output manifest to generate.
   * @return The output manifest if generated or the input manifest if no overwriting is required.
   */
  /* TODO(apell): switch from custom xml parsing to Gradle merger with NO_PLACEHOLDER_REPLACEMENT
   * set when android common is updated to version 2.5.0. 
   */
  public Path writeManifestPackage(Path manifest, String customPackage, Path output) {
    if (Strings.isNullOrEmpty(customPackage)) {
      return manifest;
    }
    try {
      Files.createDirectories(output.getParent());
      XMLEventReader reader = XMLInputFactory.newInstance()
          .createXMLEventReader(Files.newInputStream(manifest), UTF_8.name());
      XMLEventWriter writer = XMLOutputFactory.newInstance()
          .createXMLEventWriter(Files.newOutputStream(output), UTF_8.name());
      XMLEventFactory eventFactory = XMLEventFactory.newInstance();
      while (reader.hasNext()) {
        XMLEvent event = reader.nextEvent();
        if (event.isStartElement()
            && event.asStartElement().getName().toString().equalsIgnoreCase("manifest")) {
          StartElement element = event.asStartElement();
          @SuppressWarnings("unchecked")
          Iterator<Attribute> attributes = element.getAttributes();
          ImmutableList.Builder<Attribute> newAttributes = ImmutableList.builder();
          while (attributes.hasNext()) {
            Attribute attr = attributes.next();
            if (attr.getName().toString().equalsIgnoreCase("package")) {
              newAttributes.add(eventFactory.createAttribute("package", customPackage));
            } else {
              newAttributes.add(attr);
            }
          }
          writer.add(eventFactory.createStartElement(
              element.getName(), newAttributes.build().iterator(), element.getNamespaces()));
        } else {
          writer.add(event);
        }
      }
      writer.flush();
    } catch (XMLStreamException | FactoryConfigurationError | IOException e) {
      throw new RuntimeException(e);
    }

    return output;
  }

  /**
   * Merges all secondary resources with the primary resources.
   */
  public MergedAndroidData mergeData(
      final UnvalidatedAndroidData primary,
      final List<DependencyAndroidData> secondary,
      final Path resourcesOut,
      final Path assetsOut,
      final ImmutableList<DirectoryModifier> modifiers,
      @Nullable final PngCruncher cruncher,
      final boolean strict) throws MergingException {

    List<ResourceSet> resourceSets = new ArrayList<>();
    List<AssetSet> assetSets = new ArrayList<>();

    if (strict) {
      androidDataToStrictMergeSet(primary, secondary, modifiers, resourceSets, assetSets);
    } else {
      androidDataToRelaxedMergeSet(primary, secondary, modifiers, resourceSets, assetSets);
    }
    ResourceMerger merger = new ResourceMerger();
    for (ResourceSet set : resourceSets) {
      set.loadFromFiles(stdLogger);
      merger.addDataSet(set);
    }

    AssetMerger assetMerger = new AssetMerger();
    for (AssetSet set : assetSets) {
      set.loadFromFiles(stdLogger);
      assetMerger.addDataSet(set);
    }

    MergedResourceWriter resourceWriter = new MergedResourceWriter(resourcesOut.toFile(), cruncher);
    MergedAssetWriter assetWriter = new MergedAssetWriter(assetsOut.toFile());

    merger.mergeData(resourceWriter, false);
    assetMerger.mergeData(assetWriter, false);

    return new MergedAndroidData(resourcesOut, assetsOut, primary.getManifest());
  }

  /**
   * Shutdown AOSP utilized thread-pool.
   */
  public void shutdown() {
    // AOSP code never shuts down its singleton executor and leaves the process hanging.
    ExecutorSingleton.getExecutor().shutdownNow();
  }

  private void androidDataToRelaxedMergeSet(UnvalidatedAndroidData primary,
      List<DependencyAndroidData> secondary, ImmutableList<DirectoryModifier> modifiers,
      List<ResourceSet> resourceSets, List<AssetSet> assetSets) {

    for (DependencyAndroidData dependency : secondary) {
      DependencyAndroidData modifiedDependency = dependency.modify(modifiers);
      modifiedDependency.addAsResourceSets(resourceSets);
      modifiedDependency.addAsAssetSets(assetSets);
    }
    UnvalidatedAndroidData modifiedPrimary = primary.modify(modifiers);
    modifiedPrimary.addAsResourceSets(resourceSets);
    modifiedPrimary.addAsAssetSets(assetSets);

  }

  private void androidDataToStrictMergeSet(UnvalidatedAndroidData primary,
      List<DependencyAndroidData> secondary, ImmutableList<DirectoryModifier> modifiers,
      List<ResourceSet> resourceSets, List<AssetSet> assetSets) {
    UnvalidatedAndroidData modifiedPrimary = primary.modify(modifiers);
    ResourceSet mainResources = modifiedPrimary.addToResourceSet(new ResourceSet("main"));
    AssetSet mainAssets = modifiedPrimary.addToAssets(new AssetSet("main"));
    ResourceSet dependentResources = new ResourceSet("deps");
    AssetSet dependentAssets = new AssetSet("deps");
    for (DependencyAndroidData dependency : secondary) {
      DependencyAndroidData modifiedDependency = dependency.modify(modifiers);
      modifiedDependency.addToResourceSet(dependentResources);
      modifiedDependency.addToAssets(dependentAssets);
    }
    resourceSets.add(dependentResources);
    resourceSets.add(mainResources);
    assetSets.add(dependentAssets);
    assetSets.add(mainAssets);
  }

  @Nullable
  private Path prepareOutputPath(@Nullable Path out) throws IOException {
    if (out == null) {
      return null;
    }
    return Files.createDirectories(out);
  }

  private static class ZipBuilderVisitor extends SimpleFileVisitor<Path> {

    // The earliest date representable in a zip file, 1-1-1980 (the DOS epoch).
    private static final long ZIP_EPOCH = 315561600000L;
    // ZIP timestamps have a resolution of 2 seconds.
    // see http://www.info-zip.org/FAQ.html#limits
    private static final long MINIMUM_TIMESTAMP_INCREMENT = 2000L;

    private final ZipOutputStream zip;
    protected final Path root;
    private final String directoryPrefix;
    private int storageMethod = ZipEntry.STORED;

    ZipBuilderVisitor(ZipOutputStream zip, Path root, String directory) {
      this.zip = zip;
      this.root = root;
      this.directoryPrefix = directory;
    }

    public void setCompress(boolean compress) {
      storageMethod = compress ? ZipEntry.DEFLATED : ZipEntry.STORED;
    }

    /**
     * Normalize timestamps for deterministic builds. Stamp .class files to be a bit newer
     * than .java files. See:
     * {@link com.google.devtools.build.buildjar.jarhelper.JarHelper#normalizedTimestamp(String)}
     */
    protected long normalizeTime(String filename) {
      if (filename.endsWith(".class")) {
        return ZIP_EPOCH + MINIMUM_TIMESTAMP_INCREMENT;
      } else {
        return ZIP_EPOCH;
      }
    }

    protected void addEntry(Path file, byte[] content) throws IOException {
      String prefix = directoryPrefix != null ? (directoryPrefix + "/") : "";
      String relativeName = root.relativize(file).toString();
      ZipEntry entry = new ZipEntry(prefix + relativeName);
      entry.setMethod(storageMethod);
      entry.setTime(normalizeTime(relativeName));
      entry.setSize(content.length);
      CRC32 crc32 = new CRC32();
      crc32.update(content);
      entry.setCrc(crc32.getValue());

      zip.putNextEntry(entry);
      zip.write(content);
      zip.closeEntry();
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      byte[] content = Files.readAllBytes(file);
      addEntry(file, content);
      return FileVisitResult.CONTINUE;
    }
  }

  /**
   * A FileVisitor that will add all R.java files to be stored in a zip archive.
   */
  private static final class SymbolFileSrcJarBuildingVisitor extends ZipBuilderVisitor {

    static final Pattern PACKAGE_PATTERN = Pattern.compile(
        "\\s*package ([a-zA-Z_$][a-zA-Z\\d_$]*(?:\\.[a-zA-Z_$][a-zA-Z\\d_$]*)*)");
    static final Pattern ID_PATTERN = Pattern.compile(
        "public static int ([\\w\\.]+)=0x[0-9A-fa-f]+;");
    static final Pattern INNER_CLASS = Pattern.compile("public static class ([a-z_]*) \\{(.*?)\\}",
        Pattern.DOTALL);

    private final boolean staticIds;

    private SymbolFileSrcJarBuildingVisitor(ZipOutputStream zip, Path root, boolean staticIds) {
      super(zip, root, null);
      this.staticIds = staticIds;
    }

    private String replaceIdsWithStaticIds(String contents) {
      Matcher packageMatcher = PACKAGE_PATTERN.matcher(contents);
      if (!packageMatcher.find()) {
        return contents;
      }
      String pkg = packageMatcher.group(1);
      StringBuffer out = new StringBuffer();
      Matcher innerClassMatcher = INNER_CLASS.matcher(contents);
      while (innerClassMatcher.find()) {
        String resourceType = innerClassMatcher.group(1);
        Matcher idMatcher = ID_PATTERN.matcher(innerClassMatcher.group(2));
        StringBuffer resourceIds = new StringBuffer();
        while (idMatcher.find()) {
          String javaId = idMatcher.group(1);
          idMatcher.appendReplacement(resourceIds, String.format("public static int %s=0x%08X;",
              javaId, Objects.hash(pkg, resourceType, javaId)));
        }
        idMatcher.appendTail(resourceIds);
        innerClassMatcher.appendReplacement(out,
            String.format("public static class %s {%s}", resourceType, resourceIds.toString()));
      }
      innerClassMatcher.appendTail(out);
      return out.toString();
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      if (file.getFileName().endsWith("R.java")) {
        byte[] content = Files.readAllBytes(file);
        if (staticIds) {
          content = replaceIdsWithStaticIds(UTF_8.decode(
              ByteBuffer.wrap(content)).toString()).getBytes(UTF_8);
        }
        addEntry(file, content);
      }
      return FileVisitResult.CONTINUE;
    }
  }

  /**
   * A FileVisitor that will add all R class files to be stored in a zip archive.
   */
  private static final class ClassJarBuildingVisitor extends ZipBuilderVisitor {

    ClassJarBuildingVisitor(ZipOutputStream zip, Path root) {
      super(zip, root, null);
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      Path filename = file.getFileName();
      String name = filename.toString();
      if (name.endsWith(".class")) {
        byte[] content = Files.readAllBytes(file);
        addEntry(file, content);
      }
      return FileVisitResult.CONTINUE;
    }

    private byte[] manifestContent() throws IOException {
      Manifest manifest = new Manifest();
      Attributes attributes = manifest.getMainAttributes();
      attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
      Attributes.Name createdBy = new Attributes.Name("Created-By");
      if (attributes.getValue(createdBy) == null) {
        attributes.put(createdBy, "bazel");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      manifest.write(out);
      return out.toByteArray();
    }

    void writeManifestContent() throws IOException {
      addEntry(root.resolve(JarFile.MANIFEST_NAME), manifestContent());
    }
  }

}
