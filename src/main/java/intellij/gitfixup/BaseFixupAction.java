package intellij.gitfixup;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsBundle;
import com.intellij.openapi.vcs.actions.DescindingFilesFilter;
import com.intellij.openapi.vcs.actions.VcsContext;
import com.intellij.openapi.vcs.actions.VcsContextWrapper;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeList;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.InvokeAfterUpdateMode;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vcs.changes.ui.CommitChangeListDialog;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.VcsLog;
import com.intellij.vcs.log.VcsLogDataKeys;
import com.intellij.vcs.log.VcsShortCommitDetails;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.intellij.util.ArrayUtil.isEmpty;
import static com.intellij.util.containers.ContainerUtil.concat;
import static com.intellij.util.containers.ContainerUtil.emptyList;
import static com.intellij.util.containers.UtilKt.stream;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toSet;

public abstract class BaseFixupAction extends DumbAwareAction {
    private static final Logger LOG = Logger.getInstance(FixupAction.class);
    private final String messagePrefix;

    public BaseFixupAction(String prefix) {
        messagePrefix = prefix;
    }

    @NotNull
    private static Set<Change> getChangesIn(@NotNull Project project, @NotNull FilePath[] roots) {
        ChangeListManager manager = ChangeListManager.getInstance(project);
        return stream(roots)
                .flatMap(path -> manager.getChangesIn(path).stream())
                .collect(toSet());
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        VcsShortCommitDetails commit = getCommit(e);

        String commitMessage = createFixupMessage(commit);
        showCommitDialog(VcsContextWrapper.createCachedInstanceOn(e), commitMessage);
    }

    @Override
    public void update(AnActionEvent e) {
        super.update(e);
        //TODO: check if commit is on current branch
        if(e.getData(VcsLogDataKeys.VCS_LOG) == null){
            e.getPresentation().setEnabledAndVisible(false);
        }
    }

    private String createFixupMessage(VcsShortCommitDetails commit) {
        return messagePrefix + commit.getId().asString();
    }

    private void showCommitDialog(VcsContext context, String commitMessage) {
        Project project = ObjectUtils.notNull(context.getProject());

        if(ProjectLevelVcsManager.getInstance(project).isBackgroundVcsOperationRunning()) {
            LOG.debug("Background operation is running. returning.");
        } else {
            FilePath[] roots = prepareRootsForCommit(getRoots(context), project);
            ChangeListManager.getInstance(project)
                    .invokeAfterUpdate(() -> performCheckIn(context, project, roots, commitMessage), InvokeAfterUpdateMode.SYNCHRONOUS_CANCELLABLE,
                            VcsBundle.message("waiting.changelists.update.for.show.commit.dialog.message"), ModalityState.current());
        }
    }

    protected void performCheckIn(@NotNull VcsContext context, @NotNull Project project, @NotNull FilePath[] roots, String commitMessage) {
        LOG.debug("invoking commit dialog after update");
        LocalChangeList initialSelection = getInitiallySelectedChangeList(context, project);
        Change[] selectedChanges = context.getSelectedChanges();
        List<Change> selectedChangesList = isEmpty(selectedChanges) ? emptyList() : asList(selectedChanges);
        List<VirtualFile> selectedUnversioned = context.getSelectedUnversionedFiles();
        Collection<Change> changesToCommit;
        Collection<?> included;
        if(!ContainerUtil.isEmpty(selectedChangesList) || !ContainerUtil.isEmpty(selectedUnversioned)) {
            changesToCommit = selectedChangesList;
            included = concat(selectedChangesList, selectedUnversioned);
        } else {
            changesToCommit = getChangesIn(project, roots);
            included = changesToCommit;
        }

        CommitChangeListDialog.commitChanges(project, changesToCommit, included, initialSelection, null, commitMessage);
    }

    @Nullable
    protected LocalChangeList getInitiallySelectedChangeList(@NotNull VcsContext context, @NotNull Project project) {
        LocalChangeList result;
        ChangeListManager manager = ChangeListManager.getInstance(project);
        ChangeList[] changeLists = context.getSelectedChangeLists();

        if(!isEmpty(changeLists)) {
            // convert copy to real
            result = manager.findChangeList(changeLists[0].getName());
        } else {
            Change[] changes = context.getSelectedChanges();
            result = !isEmpty(changes) ? manager.getChangeList(changes[0]) : manager.getDefaultChangeList();
        }

        return result;
    }

    private VcsShortCommitDetails getCommit(AnActionEvent e) {
        VcsLog log = e.getRequiredData(VcsLogDataKeys.VCS_LOG);
        return ContainerUtil.getFirstItem(log.getSelectedShortDetails());

    }

    @NotNull
    private FilePath[] prepareRootsForCommit(@NotNull FilePath[] roots, @NotNull Project project) {
        ApplicationManager.getApplication().saveAll();

        return DescindingFilesFilter.filterDescindingFiles(roots, project);
    }

    @NotNull
    private FilePath[] getRoots(@NotNull VcsContext context) {
        Project project = context.getProject();
        if(project == null) {
            return new FilePath[]{};
        }
        ProjectLevelVcsManager manager = ProjectLevelVcsManager.getInstance(project);

        return Stream.of(manager.getAllActiveVcss())
                .filter(vcs -> vcs.getCheckinEnvironment() != null)
                .flatMap(vcs -> Stream.of(manager.getRootsUnderVcs(vcs)))
                .map(VcsUtil::getFilePath)
                .toArray(FilePath[]::new);
    }
}
