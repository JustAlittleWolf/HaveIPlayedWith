package me.wolfii.haveiplayedwith.store;

import java.util.Optional;

public final class ImportProgressStore {
    private final StoreDb db;

    ImportProgressStore(StoreDb db) {
        this.db = db;
    }

    public Optional<ImportProgress> get(String source) {
        return db.call(() -> db.importProgress(source));
    }

    public void save(ImportProgress progress) {
        db.run(() -> db.saveImportProgress(progress));
    }
}
