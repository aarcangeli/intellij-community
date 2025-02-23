// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl.compilation

import com.google.gson.Gson
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.Trinity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.Decompressor
import groovy.transform.CompileStatic
import org.apache.http.HttpStatus
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpHead
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.tools.ant.BuildException
import org.jetbrains.annotations.NotNull
import org.jetbrains.intellij.build.BuildMessages
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.CompilationContext
import org.jetbrains.intellij.build.TraceManager
import org.jetbrains.intellij.build.impl.BuildHelperKt
import org.jetbrains.intellij.build.impl.logging.IntelliJBuildException

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.zip.GZIPOutputStream

@CompileStatic
final class CompilationPartsUtil {
  static void packAndUploadToServer(CompilationContext context, String zipsLocation) {
    upload(zipsLocation, context.messages, pack(context, zipsLocation))
  }

  @SuppressWarnings('GrUnnecessaryPublicModifier')
  public static List<PackAndUploadContext> pack(CompilationContext context, String zipsLocation) {
    BuildMessages messages = context.messages

    messages.progress("Packing classes and uploading them to the server")

    //region Prepare executor
    int executorThreadsCount = Runtime.getRuntime().availableProcessors()
    messages.info("Will use up to $executorThreadsCount threads for packing and uploading")

    def executor = new NamedThreadPoolExecutor('Compile Parts', executorThreadsCount)
    executor.prestartAllCoreThreads()
    //endregion

    def incremental = context.options.incrementalCompilation
    if (!incremental) {
      FileUtil.delete(new File(zipsLocation))
    }
    FileUtil.ensureExists(new File(zipsLocation))

    List<PackAndUploadContext> contexts = new ArrayList<PackAndUploadContext>(2048)

    File root = context.getProjectOutputDirectory().toFile().getAbsoluteFile()
    List<File> subRoots = root.listFiles().toList().collect { it.absoluteFile } // production, test
    Path zipsLocationDir = Path.of(zipsLocation)
    Files.createDirectories(zipsLocationDir)
    for (File subRoot : subRoots) {
      Files.createDirectories(zipsLocationDir.resolve(subRoot.name))

      def modules = subRoot.listFiles().toList().collect { it.absoluteFile }
      for (File module : modules) {
        def files = module.list()
        if (files == null || files.size() == 0) {
          // Skip empty directories
          continue
        }

        if (context.findModule(module.name) == null) {
          messages.warning("Skipping module output from missing in project module: ${module.name}")
          continue
        }

        String name = "${subRoot.name}/${module.name}".toString()
        PackAndUploadContext ctx = new PackAndUploadContext(module, name, "$zipsLocation/${name}.jar".toString())
        contexts.add(ctx)
      }
    }

    messages.block("Building zip archives") {
      runUnderStatisticsTimer(messages, 'compile-parts:pack:time') {
        contexts.each { PackAndUploadContext ctx ->
          executor.submit {
            packItem(context, ctx)
          }
        }

        executor.waitForAllComplete(messages)
      }
      executor.reportErrors(messages)
    }
    contexts
  }

  private static void upload(String zipsLocation,BuildMessages messages, List<PackAndUploadContext> contexts) {
    String serverUrl = System.getProperty("intellij.build.compiled.classes.server.url")
    if (StringUtil.isEmptyOrSpaces(serverUrl)) {
      messages.error("Compile Parts archive server url is not defined. \n" +
                     "Please set 'intellij.compile.archive.url' system property.")
      return
    }

    Map<String, String> hashes = new ConcurrentHashMap<String, String>(2048)

    String intellijCompileArtifactsBranchProperty = 'intellij.build.compiled.classes.branch'
    String branch = System.getProperty(intellijCompileArtifactsBranchProperty)
    if (StringUtil.isEmptyOrSpaces(branch)) {
      messages.error("Unable to determine current git branch, assuming 'master'. \n" +
                     "Please set '$intellijCompileArtifactsBranchProperty' system property")
      return
    }

    int executorThreadsCount = Runtime.getRuntime().availableProcessors()
    messages.info("Will use up to $executorThreadsCount threads for uploading")

    def executor = new NamedThreadPoolExecutor('Compile Parts Pack', executorThreadsCount)
    executor.prestartAllCoreThreads()

    // TODO: Remove hardcoded constant
    String uploadPrefix = "intellij-compile/v2".toString()

    messages.block("Compute archives checksums") {
      runUnderStatisticsTimer(messages, 'compile-parts:checksum:time') {
        contexts.each { PackAndUploadContext ctx ->
          executor.submit {
            String hash = computeHash(Path.of(ctx.archive))
            hashes.put(ctx.name, hash)
          }
        }
        executor.waitForAllComplete(messages)
      }
    }

    // Prepare metadata for writing into file
    CompilationPartsMetadata m = new CompilationPartsMetadata()
    m.serverUrl = serverUrl
    m.branch = branch
    m.prefix = uploadPrefix
    m.files = new TreeMap<String, String>(hashes)
    String metadataJson = new Gson().toJson(m)

    messages.block("Uploading archives") {
      AtomicInteger uploadedCount = new AtomicInteger()
      AtomicLong uploadedBytes = new AtomicLong()
      AtomicInteger reusedCount = new AtomicInteger()
      AtomicLong reusedBytes = new AtomicLong()

      runUnderStatisticsTimer(messages, 'compile-parts:upload:time') {
        CompilationPartsUploader uploader = new CompilationPartsUploader(serverUrl, messages)

        Set<String> alreadyUploaded = new HashSet<>()
        boolean fallbackToHeads
        def files = uploader.getFoundAndMissingFiles(metadataJson)
        if (files != null) {
          messages.info("Successfully fetched info about already uploaded files")
          alreadyUploaded.addAll(files.found)
          fallbackToHeads = false
        }
        else {
          messages.warning("Failed to fetch info about already uploaded files, will fallback to HEAD requests")
          fallbackToHeads = true
        }

        // Upload with higher threads count
        executor.setMaximumPoolSize(executorThreadsCount * 2)
        executor.prestartAllCoreThreads()

        contexts.each { PackAndUploadContext ctx ->
          if (alreadyUploaded.contains(ctx.name)) {
            reusedCount.getAndIncrement()
            reusedBytes.getAndAdd(new File(ctx.archive).size())
            return
          }

          executor.submit {
            Path archiveFile = Path.of(ctx.archive)

            String hash = hashes.get(ctx.name)
            def path = "$uploadPrefix/${ctx.name}/${hash}.jar".toString()

            if (uploader.upload(path, archiveFile, fallbackToHeads)) {
              uploadedCount.getAndIncrement()
              uploadedBytes.getAndAdd(archiveFile.size())
            }
            else {
              reusedCount.getAndIncrement()
              reusedBytes.getAndAdd(archiveFile.size())
            }
          }
        }

        executor.waitForAllComplete(messages)

        CloseStreamUtil.closeStream(uploader)
      }

      messages.info("Upload complete: reused ${reusedCount.get()} parts, uploaded ${uploadedCount.get()} parts")
      messages.reportStatisticValue('compile-parts:reused:bytes', reusedBytes.get().toString())
      messages.reportStatisticValue('compile-parts:reused:count', reusedCount.get().toString())
      messages.reportStatisticValue('compile-parts:uploaded:bytes', uploadedBytes.get().toString())
      messages.reportStatisticValue('compile-parts:uploaded:count', uploadedCount.get().toString())
      messages.reportStatisticValue('compile-parts:total:bytes', (reusedBytes.get() + uploadedBytes.get()).toString())
      messages.reportStatisticValue('compile-parts:total:count', (reusedCount.get() + uploadedCount.get()).toString())
    }

    executor.close()

    executor.reportErrors(messages)

    // Save and publish metadata file
    def metadataFile = new File("$zipsLocation/metadata.json")
    FileUtil.writeToFile(metadataFile, metadataJson)
    messages.artifactBuilt(metadataFile.absolutePath)

    def gzippedMetadataFile = new File(zipsLocation, "metadata.json.gz")
    new GZIPOutputStream(gzippedMetadataFile.newOutputStream()).withCloseable { OutputStream outputStream ->
      metadataFile.newInputStream().withCloseable { InputStream inputStream ->
        FileUtil.copy(inputStream, outputStream)
      }
    }
    messages.artifactBuilt(gzippedMetadataFile.absolutePath)
  }

  static void fetchAndUnpackCompiledClasses(BuildMessages messages, Path classesOutput, BuildOptions options) {
    def metadataFile = new File(options.pathToCompiledClassesArchivesMetadata)
    if (!metadataFile.isFile()) {
      messages.error("Cannot fetch compiled classes: metadata file not found at '$options.pathToCompiledClassesArchivesMetadata'")
      return
    }
    boolean forInstallers = System.getProperty('intellij.fetch.compiled.classes.for.installers', 'false').toBoolean()
    CompilationPartsMetadata metadata
    try {
      metadata = new Gson().fromJson(FileUtil.loadFile(metadataFile, CharsetToolkit.UTF8),
                                     CompilationPartsMetadata.class)
    }
    catch (Exception e) {
      messages.error("Failed to parse metadata file content: $e.message", e)
      return
    }
    String persistentCache = System.getProperty('agent.persistent.cache')
    Path cache = persistentCache == null ? classesOutput.parent : Path.of(persistentCache).toAbsolutePath().normalize()
    Path tempDownloadsStorage = cache.resolve("idea-compile-parts-v2")

    Set<String> upToDate = ContainerUtil.newConcurrentSet()

    List<FetchAndUnpackContext> contexts = new ArrayList<FetchAndUnpackContext>(metadata.files.size())
    new TreeMap<String, String>(metadata.files).each { entry ->
      contexts.add(new FetchAndUnpackContext(entry.key, entry.value, classesOutput.resolve(entry.key), !forInstallers))
    }

    //region Prepare executor
    int executorThreadsCount = Runtime.getRuntime().availableProcessors()
    messages.info("Will use up to $executorThreadsCount threads for downloading, verifying and unpacking")

    def executor = new NamedThreadPoolExecutor('Compile Parts', executorThreadsCount)
    executor.prestartAllCoreThreads()
    //endregion

    long verifyTime = 0l

    messages.block("Check previously unpacked directories") {
      long start = System.nanoTime()
      contexts.each { ctx ->
        def out = ctx.output
        if (!Files.exists(out)) return
        executor.submit {
          if (Files.isDirectory(out)) {
            def hashFile = out.resolve(".hash")
            if (Files.exists(hashFile) && Files.isRegularFile(hashFile)) {
              try {
                String actual = Files.readString(hashFile)
                if (actual == ctx.hash) {
                  upToDate.add(ctx.name)
                  return
                }
                else {
                  messages.info("Output directory '$ctx.name' hash mismatch, expected '$ctx.hash', got '$actual'")
                }
              }
              catch (Throwable e) {
                messages.warning("Output directory '$ctx.name' hash calculation failed: $e.message")
              }
            }
            else {
              messages.debug("There's no .hash file in output directory '$ctx.name'")
            }
          }
          FileUtil.delete(out)
          return
        }
      }
      executor.submit {
        // Remove stalled directories not present in metadata
        def expectedDirectories = new HashSet<String>(metadata.files.keySet())
        // We need to traverse with depth 2 since first level is [production,test]
        def subroots = (classesOutput.toFile().listFiles() ?: new File[0]).toList().findAll { it.directory }.collect { it.absoluteFile }
        for (File subroot : subroots) {
          def modules = subroot.listFiles()
          if (modules == null) continue
          for (File module : modules) {
            def name = "$subroot.name/$module.name".toString()
            if (!expectedDirectories.contains(name)) {
              messages.info("Removing stalled directory '$name'")
              FileUtil.delete(module)
            }
          }
        }
      }
      executor.waitForAllComplete(messages)
      verifyTime += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    messages.reportStatisticValue('compile-parts:up-to-date:count', upToDate.size().toString())
    executor.reportErrors(messages)

    List<FetchAndUnpackContext> toUnpack = new ArrayList<FetchAndUnpackContext>(contexts.size())
    Deque<FetchAndUnpackContext> toDownload = new ConcurrentLinkedDeque<FetchAndUnpackContext>()

    messages.block("Check previously downloaded archives") {
      long start = System.nanoTime()
      contexts.each { ctx ->
        ctx.jar = tempDownloadsStorage.resolve("${ctx.name}/${ctx.hash}.jar")
        if (upToDate.contains(ctx.name)) return
        toUnpack.add(ctx)
        executor.submit {
          def file = ctx.jar
          if (Files.exists(file) && ctx.hash != computeHash(file)) {
            messages.info("File $file has unexpected hash, will refetch")
            FileUtil.delete(file)
          }
          if (!Files.exists(file)) {
            toDownload.add(ctx)
          }
          return
        }
      }
      executor.waitForAllComplete(messages)
      verifyTime += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

      executor.reportErrors(messages)
    }

    messages.block("Cleanup outdated compiled classes archives") {
      long start = System.nanoTime()
      int count = 0
      long bytes = 0
      try {
        def preserve = new HashSet<Path>(contexts.collect { it.jar })
        def epoch = FileTime.fromMillis(0)
        def daysAgo = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(4))
        Files.createDirectories(tempDownloadsStorage)
        // We need to traverse with depth 3 since first level is [production, test], second level is module name, third is file.
        Files
          .walk(tempDownloadsStorage, 3, FileVisitOption.FOLLOW_LINKS)
          .filter({ !preserve.contains(it) } as Predicate<Path>)
          .forEach({ Path file ->
            BasicFileAttributes attr = Files.readAttributes(file, BasicFileAttributes.class)
            if (attr.isRegularFile()) {
              def lastAccessTime = attr.lastAccessTime()
              if (lastAccessTime > epoch && lastAccessTime < daysAgo) {
                count++
                bytes += attr.size()
                FileUtil.delete(file)
              }
            }
                   } as Consumer<Path>)
      }
      catch (Throwable e) {
        messages.warning("Failed to cleanup outdated archives: $e.message")
      }

      messages.reportStatisticValue('compile-parts:cleanup:time',
                                    TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - start)).toString())
      messages.reportStatisticValue('compile-parts:removed:bytes', bytes.toString())
      messages.reportStatisticValue('compile-parts:removed:count', count.toString())
    }

    messages.block("Fetch compiled classes archives") {
      long start = System.nanoTime()

      String prefix = metadata.prefix
      String serverUrl = metadata.serverUrl

      Set<Pair<FetchAndUnpackContext, Integer>> failed = ContainerUtil.newConcurrentSet()

      if (!toDownload.isEmpty()) {
        def httpClient = HttpClientBuilder.create()
          .setUserAgent('Parts Downloader')
          .setMaxConnTotal(20)
          .setMaxConnPerRoute(10)
          .build()

        String urlWithPrefix = "$serverUrl/$prefix/".toString()

        // First let's check for initial redirect (mirror selection)
        messages.block("Mirror selection") {
          def head = new HttpHead(urlWithPrefix)
          head.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build())
          httpClient.execute(head).withCloseable { response ->
            int statusCode = response.getStatusLine().getStatusCode()
            def locationHeader = response.getFirstHeader("location")
            if ((statusCode == HttpStatus.SC_MOVED_TEMPORARILY ||
                 statusCode == HttpStatus.SC_MOVED_PERMANENTLY ||
                 statusCode == HttpStatus.SC_TEMPORARY_REDIRECT ||
                 statusCode == HttpStatus.SC_SEE_OTHER)
              && locationHeader != null) {
              urlWithPrefix = locationHeader.getValue()
              messages.info("Redirected to mirror: " + urlWithPrefix)
            }
            else {
              messages.info("Will use origin server: " + urlWithPrefix)
            }
          }
        }

        toDownload.each { ctx ->
          executor.submit {
            Files.createDirectories(ctx.jar.parent)
            def get = new HttpGet("${urlWithPrefix}${ctx.name}/${ctx.jar.fileName}")
            httpClient.execute(get).withCloseable { response ->
              if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                failed.add(Pair.create(ctx, response.getStatusLine().getStatusCode()))
              }
              else {
                new BufferedInputStream(response.getEntity().getContent()).withCloseable { bis ->
                  new BufferedOutputStream(Files.newOutputStream(ctx.jar)).withCloseable { bos ->
                    FileUtil.copy(bis, bos)
                  }
                }
              }
            }
          }
        }
        executor.waitForAllComplete(messages)

        CloseStreamUtil.closeStream(httpClient)
      }

      messages.reportStatisticValue('compile-parts:download:time',
                                    TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - start)).toString())

      long downloadedBytes = toDownload.collect { it.jar.size() }.sum(0l) as long

      messages.reportStatisticValue('compile-parts:downloaded:bytes', downloadedBytes.toString())
      messages.reportStatisticValue('compile-parts:downloaded:count', toDownload.size().toString())

      if (!failed.isEmpty()) {
        failed.each { pair ->
          messages.warning("Failed to fetch '${pair.first.name}/${pair.first.jar.fileName}', status code: ${pair.second}".toString())
        }
        messages.error("Failed to fetch ${failed.size()} file${failed.size() != 1 ? 's' : ''}, see details above")
      }

      executor.reportErrors(messages)
    }

    messages.block("Verify downloaded archives") {
      long start = System.nanoTime()
      // todo: retry download if hash verification failed
      Set<Trinity<Path, String, String>> failed = ContainerUtil.newConcurrentSet()

      toDownload.each { ctx ->
        executor.submit {
          def computed = computeHash(ctx.jar)
          def expected = ctx.hash
          if (expected != computed) {
            failed.add(Trinity.create(ctx.jar, expected, computed))
          }
          return
        }
      }
      executor.waitForAllComplete(messages)

      verifyTime += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      messages.reportStatisticValue('compile-parts:verify:time', verifyTime.toString())
      if (!failed.isEmpty()) {
        failed.each { trinity ->
          messages.warning("Downloaded file '$trinity.first' hash mismatch, expected '$trinity.second', got '$trinity.third'")
        }
        messages.error("Hash mismatch for ${failed.size()} downloaded files, see details above")
      }

      executor.reportErrors(messages)
    }

    messages.block("Unpack compiled classes archives") {
      long start = System.nanoTime()
      toUnpack.each { ctx ->
        executor.submit {
          unpack(ctx)
        }
      }
      executor.waitForAllComplete(messages)

      messages.reportStatisticValue('compile-parts:unpacked:bytes', toUnpack.collect { it.jar.size() }.sum(0l).toString())
      messages.reportStatisticValue('compile-parts:unpacked:count', toUnpack.size().toString())
      messages.reportStatisticValue('compile-parts:unpack:time',
                                    TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - start)).toString())

      executor.reportErrors(messages)
    }

    executor.close()

    executor.reportErrors(messages)
  }

  private static void unpack(FetchAndUnpackContext ctx) {
    BuildHelperKt.span(TraceManager.spanBuilder("unpack").setAttribute("name", ctx.name), new Runnable() {
      @Override
      void run() {
        Files.createDirectories(ctx.output)
        new Decompressor.Zip(ctx.jar).overwrite(true).extract(ctx.output)
        if (ctx.saveHash) {
          // Save actual hash
          Files.writeString(ctx.output.resolve(".hash"), ctx.hash)
        }
      }
    })
  }

  private static void packItem(CompilationContext compilationContext, PackAndUploadContext ctx) {
    BuildHelperKt.span(TraceManager.spanBuilder("pack").setAttribute("name", ctx.name), new Runnable() {
      @Override
      void run() {
        def destination = Path.of(ctx.archive)
        Files.deleteIfExists(destination)
        BuildHelperKt.zip(compilationContext, destination, ctx.output.absoluteFile.toPath(), true)
      }
    })
  }

  private static String computeHash(Path file) {
    if (file == null || !Files.exists(file)) {
      return null
    }

    MessageDigest messageDigest = MessageDigest.getInstance("SHA-256")
    Files.copy(file, new DigestOutputStream(messageDigest))
    def digest = messageDigest.digest()
    def hex = StringUtil.toHexString(digest)
    return hex
  }

  private static class DigestOutputStream extends OutputStream {
    private final MessageDigest myDigest

    DigestOutputStream(MessageDigest digest) {
      this.myDigest = digest
    }

    @Override
    void write(int b) throws IOException {
      myDigest.update(b as byte)
    }

    @Override
    void write(@NotNull byte[] b, int off, int len) throws IOException {
      myDigest.update(b, off, len)
    }

    @Override
    String toString() {
      return "[Digest Output Stream] $myDigest"
    }
  }

  @SuppressWarnings('GrUnnecessaryPublicModifier')
  public static final class PackAndUploadContext {
    final File output
    final String archive
    final String name

    PackAndUploadContext(File output, String name, String archive) {
      this.output = output
      this.archive = archive
      this.name = name
    }
  }

  private static final class FetchAndUnpackContext {
    final String name
    final String hash
    final Path output
    final boolean saveHash

    Path jar

    FetchAndUnpackContext(String name, String hash, Path output, boolean saveHash) {
      this.name = name
      this.hash = hash
      this.output = output
      this.saveHash = saveHash
    }
  }

  // based on org.jetbrains.intellij.build.impl.logging.BuildMessagesImpl.block
  private static <V> V runUnderStatisticsTimer(BuildMessages messages, String name, Closure<V> body) {
    def start = System.nanoTime()
    try {
      return body()
    }
    catch (IntelliJBuildException e) {
      throw e
    }
    catch (BuildException e) {
      throw new IntelliJBuildException(name, e.message, e.cause)
    }
    finally {
      def time = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      messages.reportStatisticValue(name, time.toString())
    }
  }
}
