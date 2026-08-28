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
            byte[] raw = session.imports.get(source);
            if (raw == null) {
                return Optional.empty();
            }
            StoreRows.ImportRow row = StoreCodec.imports(raw);
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
        session.run(() -> session.imports.put(progress.source(), StoreCodec.imports(new StoreRows.ImportRow(
            progress.processed(),
            progress.total(),
            progress.lastTimestamp() == null ? "" : progress.lastTimestamp().toString(),
            progress.skip(),
            progress.status(),
            progress.silenced()
        ))));
    }
}
