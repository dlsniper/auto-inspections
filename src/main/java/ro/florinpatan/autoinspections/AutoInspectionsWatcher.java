package ro.florinpatan.autoinspections;

public interface AutoInspectionsWatcher {
    void activate();
    void deactivate();

    boolean isUpToDate(int modificationStamp);
}
