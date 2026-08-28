package me.wolfii.haveiplayedwith.store;

import java.time.LocalDateTime;
import java.util.Optional;

public final class ImportProgressStore {
    private final StoreSession session;

    ImportProgressStore(StoreSession session) {
        this.session = session;
    }

    public Optional<ImportProgress> get(String source) {
        return session.call(() -> {
            StoreRows.ImportRow row = session.db.importProgress(source);
            if (row == null) {
                return Optional.empty();
            }
            return Optional.of(new ImportProgress(
                source,
                row.processed(),
                row.total(),
                row.lastTimestamp().isBlank() ? null : LocalDateTime.parse(row.lastTimestamp()),
                row.skip(),
                row.status(),
                row.silenced()
            ));
        });
    }

    public void save(ImportProgress progress) {
        session.run(() -> session.db.putImportProgress(progress.source(), new StoreRows.ImportRow(
            progress.processed(),
            progress.total(),
            progress.lastTimestamp() == null ? "" : progress.lastTimestamp().toString(),
            progress.skip(),
            progress.status(),
            progress.silenced()
        )));
    }
}
