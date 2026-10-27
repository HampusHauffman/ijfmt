package dev.ijfmt.daemon;

import com.intellij.application.options.CodeStyle;
import com.intellij.formatting.commandLine.FormatterStarterKt;
import com.intellij.formatting.service.CoreFormattingService;
import com.intellij.formatting.service.FormattingService;
import com.intellij.formatting.service.FormattingServiceUtil;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.lang.LanguageFormatting;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListenerBackgroundable;
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.core.impl.PomModelImpl;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.application.options.codeStyle.cache.CodeStyleCachingService;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
import com.intellij.util.messages.MessageBus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Formats files exactly the way IntelliJ's own command line formatter ({@code idea format}) does, but inside a long-lived
 * application so each request takes milliseconds instead of a full IDE startup.
 * <p>
 * The mirrored behaviour (see {@code com.intellij.formatting.commandLine.FileSetFormatter} and
 * {@code FileDocumentManagerImpl.saveDocument}):
 * <ul>
 *   <li>one throw-away project; per file the "main project code style" is set to the per-project scheme of the nearest
 *       enclosing {@code .idea} directory (if it uses per-project settings), else the fallback scheme;</li>
 *   <li>{@code .editorconfig} (including all {@code ij_*} properties) is applied by IntelliJ's EditorConfig plugin;</li>
 *   <li>{@code CodeStyleManager.reformatText} on the real {@link VirtualFile}'s document;</li>
 *   <li>the save pipeline: {@code beforeAnyDocumentSaving}, then - only if the text differs from disk -
 *       {@code beforeDocumentSaving} listeners (trailing whitespace stripping, final newline, ...) and the file's
 *       line separator.</li>
 * </ul>
 * All methods except {@link #create} and {@link #dispose} must be called on the EDT.
 */
final class Formatter {
  private static final Logger LOG = Logger.getInstance(Formatter.class);

  private final Project project;
  private final Path projectDir;
  private final CodeStyleSettings factoryDefaults;
  private final @Nullable Path fallbackSettingsFile;
  private final Map<Path, CachedSettings> settingsCache = new HashMap<>();
  private final Map<Path, Long> editorConfigStamps = new HashMap<>();

  private Formatter(Project project, Path projectDir, @Nullable Path fallbackSettingsFile) {
    this.project = project;
    this.projectDir = projectDir;
    this.fallbackSettingsFile = fallbackSettingsFile;
    this.factoryDefaults = CodeStyleSettingsManager.getInstance().createSettings();
  }

  /** Creates the throw-away project the same way {@code FormatterStarter.createProject} does. Must not run on the EDT. */
  static Formatter create(@Nullable Path fallbackSettingsFile) throws IOException {
    Path root = Files.createTempDirectory("ijfmt.format." + UUID.randomUUID() + ".tmp.");
    Path dotIdea = Files.createDirectories(root.resolve(".idea"));
    Project project = ProjectManagerEx.getInstanceEx().openProject(dotIdea, OpenProjectTask.build().asNewProject());
    if (project == null) throw new IOException("Failed to create the formatting project in " + root);
    Formatter formatter = new Formatter(project, root, fallbackSettingsFile);
    ApplicationManager.getApplication().invokeAndWait(
      () -> CodeStyle.setMainProjectSettings(project, CodeStyleSettingsManager.getInstance().createSettings()));
    return formatter;
  }

  /** Loads and JIT-warms the formatter classes so the first real request is fast. Must be called on the EDT. */
  void warmUp() {
    String sample = String.join("\n",
      "package warmup;", "import java.util.*;", "/** Doc. @param <T> type */",
      "public class Warmup<T> implements Comparable<Warmup<T>>{",
      "@Override public int compareTo(Warmup<T> o){return 0;}",
      "private final List<String> names=new ArrayList<>();int[] a={1,2,3};",
      "void run(int x){if(x>0){for(int i=0;i<x;i++){names.add(\"n\"+i);}}else{switch(x){case 0->{}default->{}}}",
      "Runnable r=()->{try{x();}catch(Exception e){throw new RuntimeException(e);}finally{}};}",
      "static void x()throws Exception{}", "record R(int a,String b){}", "enum E{A,B}", "}");
    for (int i = 0; i < 3; i++) {
      try {
        formatText(projectDir.resolve("warmup").resolve("Warmup" + i + ".java"), sample, null);
      }
      catch (Exception e) {
        LOG.info("ijfmt warm-up failed: " + e);
        return;
      }
    }
  }

  void dispose() {
    try {
      ApplicationManager.getApplication().invokeAndWait(() -> ProjectManager.getInstance().closeAndDispose(project));
    }
    catch (Throwable t) {
      LOG.warn("Failed to close formatting project", t);
    }
    FileUtil.delete(projectDir.toFile());
  }

  /** Thrown for files IntelliJ cannot format (binary, unknown type, no formatter for the language). */
  static final class UnsupportedFileException extends Exception {
    UnsupportedFileException(String message) { super(message); }
  }

  /** Thrown when the formatter refused to process a file (IntelliJ: "Skipped, rejected by formatter."). */
  static final class RejectedException extends Exception {
    RejectedException(String message) { super(message); }
  }

  /**
   * Formats {@code text} as if it were the current editor content of {@code path} and the user ran Reformat Code and then
   * saved. {@code path} does not have to exist; it determines the language, {@code .editorconfig} sections and project.
   *
   * @param lineRanges optional 1-based inclusive line ranges ({@code [start, end]}) to restrict formatting to
   * @return the formatted text using the input's dominant line separator (IntelliJ's rule), or IntelliJ's choice for the
   *     file if the input has none
   */
  String formatText(@NotNull Path path, @NotNull String text, @Nullable List<int[]> lineRanges)
    throws UnsupportedFileException, RejectedException {
    boolean bom = text.startsWith("\uFEFF");
    String content = bom ? text.substring(1) : text;
    String inputSeparator = detectLineSeparator(content);
    String normalized = content.indexOf('\r') >= 0 ? content.replace("\r\n", "\n").replace('\r', '\n') : content;

    prepare(path);
    VirtualFile file = findFile(path);
    String result;
    String fileSeparator;
    if (file != null) {
      Document document = documentFor(file);
      try {
        PsiFile psiFile = psiFor(document, file);
        UndoUtil.disableUndoFor(document);
        WriteCommandAction.runWriteCommandAction(project, () -> {
          replaceChangedLines(document, normalized);
          PsiDocumentManager.getInstance(project).commitDocument(document);
        });
        reformat(psiFile, document, lineRanges);
        emulateSave(document, file);
        result = document.getText();
        fileSeparator = FileDocumentManagerImpl.getLineSeparator(document, file);
      }
      finally {
        discardChanges(document);
      }
    }
    else {
      ShadowFile shadow = new ShadowFile(path);
      Document document = FileDocumentManager.getInstance().getDocument(shadow);
      if (document == null) throw new UnsupportedFileException("No document for " + path);
      PsiFile psiFile = psiFor(document, shadow);
      UndoUtil.disableUndoFor(document);
      WriteCommandAction.runWriteCommandAction(project, () -> {
        document.setText(normalized);
        PsiDocumentManager.getInstance(project).commitDocument(document);
      });
      reformat(psiFile, document, lineRanges);
      // A new file is always saved; the save listeners resolve the project through ProjectLocator.
      try (AccessToken ignored = ProjectLocator.withPreferredProject(shadow, project)) {
        if (!document.getText().isEmpty()) publishBeforeDocumentSaving(document);
        result = document.getText();
        fileSeparator = FileDocumentManagerImpl.getLineSeparator(document, shadow);
      }
    }
    String separator = inputSeparator != null ? inputSeparator : fileSeparator;
    if (!separator.equals("\n")) result = result.replace("\n", separator);
    return bom ? "\uFEFF" + result : result;
  }

  enum FileMode { WRITE, CHECK }

  /**
   * Formats a file on disk exactly like {@code idea format <file>}: reformat the document and save it through IntelliJ
   * (charset, BOM and line separators are preserved by IntelliJ). In {@link FileMode#CHECK} mode nothing is written.
   *
   * @return whether the file content changed (or would change)
   */
  boolean formatFile(@NotNull Path path, @NotNull FileMode mode) throws UnsupportedFileException, RejectedException, IOException {
    prepare(path);
    VirtualFile file = findFile(path);
    if (file == null) throw new IOException("File not found: " + path);
    Document document = documentFor(file);
    try {
      PsiFile psiFile = psiFor(document, file);
      UndoUtil.disableUndoFor(document);
      reformat(psiFile, document, null);
      if (mode == FileMode.CHECK) {
        emulateSave(document, file);
        return isSaveNeeded(document, file);
      }
      byte[] before = Files.readAllBytes(path);
      SaveGuard.allowSaving(document, () -> FileDocumentManager.getInstance().saveDocument(document));
      byte[] written = file.contentsToByteArray(false);
      awaitDiskContent(path, written);
      return !Arrays.equals(before, written);
    }
    finally {
      discardChanges(document);
    }
  }

  /**
   * IntelliJ's save has returned and VFS holds the new content, but on some systems the bytes only become visible on
   * disk a few milliseconds later. Clients read the file right after the response, so wait until it's really there.
   */
  private static void awaitDiskContent(Path path, byte[] expected) throws IOException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!Arrays.equals(expected, Files.readAllBytes(path))) {
      if (System.nanoTime() > deadline) {
        LOG.warn("Timed out waiting for " + path + " to be written");
        return;
      }
      try {
        Thread.sleep(2);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  // --- pipeline steps -------------------------------------------------------------------------------------------------

  private void prepare(Path path) {
    refreshEditorConfigs(path);
    CodeStyleSettings style = styleFor(path);
    CodeStyleSettingsManager manager = CodeStyleSettingsManager.getInstance(project);
    if (manager.getMainProjectCodeStyle() != style) {
      CodeStyle.setMainProjectSettings(project, style);
      // A one-shot `idea format` run never formats a file twice, but the daemon does: per-file settings computed from the
      // previous scheme must go. IntelliJ only drops this cache on plugin (un)load, via the same private method.
      clearPerFileCodeStyleCache();
      manager.notifyCodeStyleSettingsChanged();
    }
  }

  private void clearPerFileCodeStyleCache() {
    Object service = CodeStyleCachingService.getInstance(project);
    try {
      Method clear = service.getClass().getDeclaredMethod("clearCache");
      clear.setAccessible(true);
      clear.invoke(service);
    }
    catch (ReflectiveOperationException | RuntimeException e) {
      LOG.warn("Cannot clear the per-file code style cache; scheme switches may apply late", e);
    }
  }

  private static @Nullable VirtualFile findFile(Path path) {
    VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
    if (file == null) return null;
    if (file.isDirectory()) throw new IllegalArgumentException(path + " is a directory");
    VfsUtil.markDirtyAndRefresh(false, false, false, file);
    return file;
  }

  private static Document documentFor(VirtualFile file) throws UnsupportedFileException {
    if (file.getFileType().isBinary()) throw new UnsupportedFileException("Binary file: " + file.getPresentableUrl());
    FileDocumentManager manager = FileDocumentManager.getInstance();
    Document document = manager.getDocument(file);
    if (document == null) throw new UnsupportedFileException("No document available for " + file.getPresentableUrl());
    if (manager.isDocumentUnsaved(document)) manager.reloadFromDisk(document);
    return document;
  }

  private PsiFile psiFor(Document document, VirtualFile file) throws UnsupportedFileException {
    PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
    if (!(file instanceof LightVirtualFile)) NonProjectFileWritingAccessProvider.allowWriting(List.of(file));
    if (psiFile == null) throw new UnsupportedFileException("Unable to get a PSI file for " + file.getPresentableUrl());
    FormattingService service = FormattingServiceUtil.findService(psiFile, true, true);
    if (service instanceof CoreFormattingService && LanguageFormatting.INSTANCE.forContext(psiFile) == null) {
      throw new UnsupportedFileException("Formatting is not supported for " + file.getFileType().getName() + " files");
    }
    return psiFile;
  }

  private void reformat(PsiFile psiFile, Document document, @Nullable List<int[]> lineRanges) throws RejectedException {
    try {
      WriteCommandAction.runWriteCommandAction(project, () -> {
        PsiFile current = Objects.requireNonNullElse(PsiDocumentManager.getInstance(project).getPsiFile(document), psiFile);
        CodeStyleManager manager = CodeStyleManager.getInstance(project);
        if (lineRanges == null) {
          manager.reformatText(current, 0, current.getTextLength());
        }
        else {
          manager.reformatText(current, toTextRanges(document, lineRanges));
        }
        PsiDocumentManager.getInstance(project).commitDocument(document);
      });
    }
    catch (ProcessCanceledException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new RejectedException("Skipped, rejected by formatter. " + Objects.toString(cause.getMessage(), ""));
    }
  }

  /**
   * Mirrors {@code FileDocumentManagerImpl.saveDocument} up to (but excluding) the actual disk write: the "before save"
   * listeners only run when the document really differs from the file on disk.
   */
  private void emulateSave(Document document, VirtualFile file) {
    MessageBus bus = ApplicationManager.getApplication().getMessageBus();
    bus.syncPublisher(FileDocumentManagerListenerBackgroundable.TOPIC).beforeAnyDocumentSaving(document, true);
    if (!FileDocumentManager.getInstance().isDocumentUnsaved(document)) return;
    if (!isSaveNeeded(document, file)) return;
    publishBeforeDocumentSaving(document);
  }

  private static void publishBeforeDocumentSaving(Document document) {
    MessageBus bus = ApplicationManager.getApplication().getMessageBus();
    ApplicationManager.getApplication().runWriteAction(() -> PomModelImpl.guardPsiModificationsIn(
      () -> bus.syncPublisher(FileDocumentManagerListenerBackgroundable.TOPIC).beforeDocumentSaving(document)));
  }

  private static final @Nullable Method IS_SAVE_NEEDED = findIsSaveNeeded();

  private static @Nullable Method findIsSaveNeeded() {
    try {
      Method method = FileDocumentManagerImpl.class.getDeclaredMethod("isSaveNeeded", Document.class, VirtualFile.class);
      method.setAccessible(true);
      return method;
    }
    catch (ReflectiveOperationException | RuntimeException e) {
      LOG.warn("FileDocumentManagerImpl.isSaveNeeded not found, using a re-implementation", e);
      return null;
    }
  }

  /** IntelliJ only saves (and runs the "before save" listeners) when the document text differs from the file on disk. */
  private static boolean isSaveNeeded(Document document, VirtualFile file) {
    try {
      if (IS_SAVE_NEEDED != null) return (Boolean)IS_SAVE_NEEDED.invoke(null, document, file);
      if (file.getFileType().isBinary() || document.getTextLength() > 1000 * 1000) return true;
      CharSequence loaded = LoadTextUtil.getTextByBinaryPresentation(file.contentsToByteArray(), file, false, false);
      return !Comparing.equal(document.getCharsSequence(), loaded);
    }
    catch (ReflectiveOperationException | IOException e) {
      return true;
    }
  }

  private static void discardChanges(Document document) {
    FileDocumentManager manager = FileDocumentManager.getInstance();
    if (manager.isDocumentUnsaved(document)) manager.reloadFromDisk(document);
  }

  private static List<TextRange> toTextRanges(Document document, List<int[]> lineRanges) {
    List<TextRange> ranges = new ArrayList<>();
    int lineCount = Math.max(document.getLineCount(), 1);
    for (int[] range : lineRanges) {
      int start = Math.min(Math.max(range[0], 1), lineCount) - 1;
      int end = Math.min(Math.max(range[1], range[0]), lineCount) - 1;
      if (document.getLineCount() == 0) {
        ranges.add(TextRange.EMPTY_RANGE);
      }
      else {
        ranges.add(new TextRange(document.getLineStartOffset(start), document.getLineEndOffset(end)));
      }
    }
    return ranges;
  }

  /**
   * Updates the document to {@code newText} by replacing only the lines that differ, so IntelliJ's per-line modification
   * tracking (used by "strip trailing spaces on modified lines") sees the same edits as if the user had typed them.
   */
  private static void replaceChangedLines(Document document, String newText) {
    String oldText = document.getText();
    if (oldText.equals(newText)) return;
    String[] before = splitKeepingTerminators(oldText);
    String[] after = splitKeepingTerminators(newText);
    Diff.Change change;
    try {
      change = Diff.buildChanges(before, after);
    }
    catch (FilesTooBigForDiffException e) {
      document.setText(newText);
      return;
    }
    int[] offsets = new int[before.length + 1];
    for (int i = 0; i < before.length; i++) offsets[i + 1] = offsets[i] + before[i].length();
    List<Diff.Change> changes = new ArrayList<>();
    for (Diff.Change c = change; c != null; c = c.link) changes.add(c);
    for (int i = changes.size() - 1; i >= 0; i--) {
      Diff.Change c = changes.get(i);
      StringBuilder inserted = new StringBuilder();
      for (int j = c.line1; j < c.line1 + c.inserted; j++) inserted.append(after[j]);
      document.replaceString(offsets[c.line0], offsets[c.line0 + c.deleted], inserted);
    }
    if (!document.getText().equals(newText)) document.setText(newText);
  }

  private static String[] splitKeepingTerminators(String text) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        lines.add(text.substring(start, i + 1));
        start = i + 1;
      }
    }
    if (start < text.length()) lines.add(text.substring(start));
    return lines.toArray(String[]::new);
  }

  /** Same rule as IntelliJ's {@code LoadTextUtil.ConvertResult.majorLineSeparator()}: the most frequent separator wins. */
  static @Nullable String detectLineSeparator(String text) {
    int cr = 0, lf = 0, crlf = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r') {
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          crlf++;
          i++;
        }
        else {
          cr++;
        }
      }
      else if (c == '\n') {
        lf++;
      }
    }
    if (crlf > cr && crlf > lf) return "\r\n";
    if (cr > lf) return "\r";
    if (lf > 0) return "\n";
    return null;
  }

  // --- code style resolution ------------------------------------------------------------------------------------------

  /**
   * Same precedence as the command line formatter run with {@code -allowDefaults}: the per-project scheme of the nearest
   * enclosing {@code .idea} directory when it has {@code USE_PER_PROJECT_SETTINGS=true}, otherwise the fallback scheme
   * (factory defaults unless configured). {@code .editorconfig} is layered on top of either by IntelliJ itself.
   */
  private CodeStyleSettings styleFor(Path file) {
    for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
      Path dotIdea = dir.resolve(".idea");
      if (Files.isDirectory(dotIdea)) {
        CodeStyleSettings settings = projectSettings(dotIdea.resolve("codeStyles"));
        return settings != null ? settings : fallbackSettings();
      }
    }
    return fallbackSettings();
  }

  private @Nullable CodeStyleSettings projectSettings(Path codeStyles) {
    Path config = codeStyles.resolve("codeStyleConfig.xml");
    Path projectXml = codeStyles.resolve("Project.xml");
    return cachedSettings(codeStyles, List.of(config, projectXml), () -> {
      if (!Files.isRegularFile(config) || !usesPerProjectSettings(config)) return null;
      return Files.isRegularFile(projectXml) ? FormatterStarterKt.readSettings(projectXml) : null;
    });
  }

  private CodeStyleSettings fallbackSettings() {
    if (fallbackSettingsFile == null) return factoryDefaults;
    CodeStyleSettings settings = cachedSettings(fallbackSettingsFile, List.of(fallbackSettingsFile),
                                                () -> FormatterStarterKt.readSettings(fallbackSettingsFile));
    if (settings == null) {
      LOG.warn("Cannot read fallback code style settings from " + fallbackSettingsFile + ", using factory defaults");
      return factoryDefaults;
    }
    return settings;
  }

  private interface SettingsLoader {
    @Nullable CodeStyleSettings load() throws Exception;
  }

  private record CachedSettings(String fingerprint, @Nullable CodeStyleSettings settings) {}

  private @Nullable CodeStyleSettings cachedSettings(Path key, List<Path> inputs, SettingsLoader loader) {
    String fingerprint = fingerprint(inputs);
    CachedSettings cached = settingsCache.get(key);
    if (cached != null && cached.fingerprint.equals(fingerprint)) return cached.settings;
    CodeStyleSettings settings;
    try {
      settings = loader.load();
    }
    catch (Exception e) {
      LOG.warn("Cannot load code style settings from " + key, e);
      settings = null;
    }
    settingsCache.put(key, new CachedSettings(fingerprint, settings));
    return settings;
  }

  private static String fingerprint(List<Path> files) {
    StringBuilder result = new StringBuilder();
    for (Path file : files) {
      try {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        result.append(attributes.lastModifiedTime().toMillis()).append(':').append(attributes.size()).append(';');
      }
      catch (IOException e) {
        result.append("missing;");
      }
    }
    return result.toString();
  }

  private static boolean usesPerProjectSettings(Path codeStyleConfig) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    org.w3c.dom.Document xml = factory.newDocumentBuilder().parse(new InputSource(new StringReader(Files.readString(codeStyleConfig))));
    Object value = XPathFactory.newDefaultInstance().newXPath().evaluate(
      "/component[@name='ProjectCodeStyleConfiguration']/state/option[@name='USE_PER_PROJECT_SETTINGS']/@value",
      xml, XPathConstants.NODE);
    return value instanceof Node node && "true".equals(node.getNodeValue());
  }

  /**
   * Makes IntelliJ notice edits to {@code .editorconfig} files between requests: the EditorConfig plugin caches parsed
   * files and drops the cache on VFS events, which only happen when the VFS is refreshed.
   */
  private void refreshEditorConfigs(Path file) {
    LocalFileSystem fs = LocalFileSystem.getInstance();
    for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
      Path editorConfig = dir.resolve(".editorconfig");
      long stamp = stamp(editorConfig);
      Long previous = editorConfigStamps.put(editorConfig, stamp);
      if (previous != null && previous == stamp) continue;
      VirtualFile vFile = fs.refreshAndFindFileByNioFile(editorConfig);
      if (vFile != null) {
        VfsUtil.markDirtyAndRefresh(false, false, false, vFile);
      }
      else if (previous != null) {
        VirtualFile vDir = fs.findFileByNioFile(dir);
        if (vDir != null) VfsUtil.markDirtyAndRefresh(false, false, true, vDir);
      }
    }
  }

  private static long stamp(Path file) {
    try {
      BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
      return attributes.lastModifiedTime().toMillis() * 31 + attributes.size();
    }
    catch (IOException e) {
      return -1;
    }
  }

  /**
   * In-memory stand-in for a file that does not exist on disk yet (e.g. a new, never saved editor buffer). It reports the
   * real path and parent directory so {@code .editorconfig} lookup and file type detection behave as for the real file.
   */
  private static final class ShadowFile extends LightVirtualFile {
    private final String path;
    private final @Nullable VirtualFile parent;

    ShadowFile(Path path) {
      super(path.getFileName().toString(), "");
      this.path = FileUtil.toSystemIndependentName(path.toAbsolutePath().toString());
      VirtualFile dir = null;
      for (Path p = path.toAbsolutePath().getParent(); p != null && dir == null; p = p.getParent()) {
        dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p);
      }
      this.parent = dir;
    }

    @Override
    public @NotNull String getPath() { return path; }

    @Override
    public @Nullable VirtualFile getParent() { return parent; }

    @Override
    public boolean isInLocalFileSystem() { return true; }
  }
}
