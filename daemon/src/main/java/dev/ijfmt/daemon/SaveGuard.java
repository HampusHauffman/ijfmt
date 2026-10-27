package dev.ijfmt.daemon;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The daemon edits documents of real files in memory. This vetoer guarantees nothing is ever written back to disk
 * (auto-save, save-on-exit, ...) unless a file was explicitly requested to be formatted in place.
 */
public final class SaveGuard extends FileDocumentSynchronizationVetoer {
  private static volatile boolean active;
  private static final Set<Document> allowed = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

  static void activate() {
    active = true;
  }

  static void allowSaving(Document document, Runnable save) {
    allowed.add(document);
    try {
      save.run();
    }
    finally {
      allowed.remove(document);
    }
  }

  @Override
  public boolean maySaveDocument(@NotNull Document document, boolean isSaveExplicit) {
    return !active || allowed.contains(document);
  }
}
