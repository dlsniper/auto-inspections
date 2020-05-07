package ro.florinpatan.autoinspections;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiManager;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class AutoInspections implements StartupActivity {
    private final static Logger LOG = Logger.getInstance(AutoInspections.class);
    private Project myProject;
    private AutoInspectionsWatcher myWatcher;
    private GlobalInspectionContextImpl myInspectionContext;

    public void runActivity(@NotNull Project project) {
        if (myWatcher != null) myWatcher.deactivate();
        if (myInspectionContext != null) {
            myInspectionContext.cleanup();
            myInspectionContext = null;
        }
        // TODO: Is there anything else that we need to cancel?

        myProject = project;
        myInspectionContext = ((InspectionManagerEx) InspectionManager.getInstance(myProject)).createNewGlobalContext();
        myWatcher = createWatcher(project);
        myWatcher.activate();
    }

    @NotNull
    protected AutoInspectionsWatcher createWatcher(Project project) {
        // Let's hardcode the value to 1 second, for now
        // TODO: Provide a UI to allow changing of this
        return new DelayedDocumentWatcher(project, 1000, this::runAnnotator, file -> {
            if (ScratchFileService.getInstance().getRootType(file) != null) {
                return false;
            }

            return FileEditorManager.getInstance(project).isFileOpen(file);
        });
    }

    private void runAnnotator(int modificationStamp, Set<VirtualFile> changedFiles) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (myWatcher.isUpToDate(modificationStamp)) {
                run(changedFiles);
            }
        }, ModalityState.any());
    }

    private void run(Set<VirtualFile> changedFiles) {
        Set<VirtualFile> changedDirs = new THashSet<>();
        PsiManager psiManager = PsiManager.getInstance(myProject);

        String profileName = "Project Default";
        InspectionProfileImpl inspectionProfile = InspectionProfileManager.getInstance(myProject).getProfile(profileName, true);

        // Get all the directories that contain changed code
        changedFiles.forEach(virtualFile -> {
            if (myProject.isDisposed() || !virtualFile.isValid()) return;
            if (virtualFile.isDirectory()) {
                changedDirs.add(virtualFile);
                return;
            }

            while (!virtualFile.isDirectory()) {
                virtualFile = virtualFile.getParent();
            }

            changedDirs.add(virtualFile);
        });

        // Add all the files in all the directories that contain user changed code
        Set<VirtualFile> allFiles = new THashSet<>();
        changedDirs.forEach(virtualDirectory -> {
            PsiDirectory psiDir = psiManager.findDirectory(virtualDirectory);
            if (psiDir == null) return;

            VirtualFile[] virtualFiles = virtualDirectory.getChildren();
            for (VirtualFile vFile : virtualFiles) {
                // Skip directories
                if (vFile.isDirectory()) continue;

                allFiles.add(vFile);
            }
        });

        AnalysisScope scope = new AnalysisScope(myProject, allFiles);
        scope.setSearchInLibraries(false);

        // Run the inspections
        myInspectionContext.setExternalProfile(inspectionProfile);
        myInspectionContext.setCurrentScope(scope);
        // TODO: Prevent the results window to take focus from keyboard
        myInspectionContext.doInspections(scope);
    }
}
