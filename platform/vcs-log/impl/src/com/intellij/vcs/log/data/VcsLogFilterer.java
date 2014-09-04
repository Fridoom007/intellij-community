package com.intellij.vcs.log.data;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import com.intellij.util.ThrowableConsumer;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashSet;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcs.log.*;
import com.intellij.vcs.log.graph.GraphFacade;
import com.intellij.vcs.log.impl.VcsLogUtil;
import com.intellij.vcs.log.ui.VcsLogUiImpl;
import com.intellij.vcs.log.ui.tables.AbstractVcsLogTableModel;
import com.intellij.vcs.log.ui.tables.EmptyTableModel;
import com.intellij.vcs.log.ui.tables.GraphTableModel;
import gnu.trove.TIntHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VcsLogFilterer {

  private static final Logger LOG = Logger.getInstance(VcsLogFilterer.class);

  private static final int LOAD_MORE_COMMITS_FIRST_STEP_LIMIT = 2000;

  @NotNull private final VcsLogDataHolder myLogDataHolder;
  @NotNull private final VcsLogUiImpl myUI;

  public VcsLogFilterer(@NotNull VcsLogDataHolder logDataHolder, @NotNull VcsLogUiImpl ui) {
    myLogDataHolder = logDataHolder;
    myUI = ui;
  }

  @NotNull
  public AbstractVcsLogTableModel applyFiltersAndUpdateUi(@NotNull DataPack dataPack, @NotNull VcsLogFilterCollection filters) {
    resetFilters(dataPack);
    VcsLogHashFilter hashFilter = filters.getHashFilter();
    if (hashFilter != null && !hashFilter.getHashes().isEmpty()) { // hashes should be shown, no matter if they match other filters or not
      return applyHashFilter(dataPack, hashFilter.getHashes());
    }
    List<VcsLogDetailsFilter> detailsFilters = filters.getDetailsFilters();
    applyGraphFilters(dataPack, filters.getBranchFilter());
    return applyDetailsFilter(dataPack, detailsFilters);
  }

  private GraphTableModel applyHashFilter(@NotNull DataPack dataPack, @NotNull Collection<String> hashes) {
    final List<Integer> indices = ContainerUtil.mapNotNull(hashes, new Function<String, Integer>() {
      @Override
      public Integer fun(String partOfHash) {
        Hash hash = myLogDataHolder.findHashByString(partOfHash);
        return hash != null ? myLogDataHolder.getCommitIndex(hash) : null;
      }
    });
    dataPack.getGraphFacade().setVisibleBranches(null);
    dataPack.getGraphFacade().setFilter(new Condition<Integer>() {
      @Override
      public boolean value(Integer integer) {
        return indices.contains(integer);
      }
    });
    return new GraphTableModel(dataPack, myLogDataHolder, myUI, LoadMoreStage.ALL_REQUESTED);
  }

  private static void resetFilters(@NotNull DataPack dataPack) {
    GraphFacade facade = dataPack.getGraphFacade();
    facade.setVisibleBranches(null);
    facade.setFilter(null);
  }

  private AbstractVcsLogTableModel applyDetailsFilter(DataPack dataPack, List<VcsLogDetailsFilter> detailsFilters) {
    if (!detailsFilters.isEmpty()) {
      List<Hash> filteredCommits = filterInMemory(dataPack, detailsFilters);
      if (filteredCommits.isEmpty()) {
        return new EmptyTableModel(dataPack, myLogDataHolder, myUI, LoadMoreStage.INITIAL);
      }
      else{
        Condition<Integer> filter = getFilterFromCommits(filteredCommits);
        dataPack.getGraphFacade().setFilter(filter);
      }
    }
    else {
      dataPack.getGraphFacade().setFilter(null);
    }
    return new GraphTableModel(dataPack, myLogDataHolder, myUI, LoadMoreStage.INITIAL);
  }

  private Condition<Integer> getFilterFromCommits(List<Hash> filteredCommits) {
    final Set<Integer> commitSet = ContainerUtil.map2Set(filteredCommits, new Function<Hash, Integer>() {
      @Override
      public Integer fun(Hash hash) {
        return myLogDataHolder.getCommitIndex(hash);
      }
    });
    return new Condition<Integer>() {
      @Override
      public boolean value(Integer integer) {
        return commitSet.contains(integer);
      }
    };
  }

  public void requestVcs(@NotNull final DataPack dataPack, @NotNull VcsLogFilterCollection filters,
                         @NotNull final LoadMoreStage loadMoreStage, @NotNull final Runnable onSuccess) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    int maxCount = loadMoreStage == LoadMoreStage.INITIAL ? LOAD_MORE_COMMITS_FIRST_STEP_LIMIT : -1;
    getFilteredDetailsFromTheVcs(dataPack.getLogProviders(), filters, new Consumer<List<Hash>>() {
      @Override
      public void consume(List<Hash> hashes) {
        LoadMoreStage newLoadMoreStage = advanceLoadMoreStage(loadMoreStage);
        TIntHashSet previouslySelected = myUI.getSelectedCommits();
        AbstractVcsLogTableModel model;
        if (hashes.isEmpty()) {
          model = new EmptyTableModel(dataPack, myLogDataHolder, myUI, newLoadMoreStage);
        }
        else {
          dataPack.getGraphFacade().setFilter(getFilterFromCommits(hashes));
          model = new GraphTableModel(dataPack, myLogDataHolder, myUI, newLoadMoreStage);
        }
        myUI.setModel(model, dataPack, previouslySelected);
        myUI.repaintUI();
        onSuccess.run();
      }
    }, maxCount);
  }

  @NotNull
  private static LoadMoreStage advanceLoadMoreStage(@NotNull LoadMoreStage loadMoreStage) {
    LoadMoreStage newLoadMoreStage;
    if (loadMoreStage == LoadMoreStage.INITIAL) {
      newLoadMoreStage = LoadMoreStage.LOADED_MORE;
    }
    else if (loadMoreStage == LoadMoreStage.LOADED_MORE) {
      newLoadMoreStage = LoadMoreStage.ALL_REQUESTED;
    }
    else {
      LOG.warn("Incorrect previous load more stage: " + loadMoreStage);
      newLoadMoreStage = LoadMoreStage.ALL_REQUESTED;
    }
    return newLoadMoreStage;
  }

  private void applyGraphFilters(@NotNull final DataPack dataPack, @Nullable final VcsLogBranchFilter branchFilter) {
    try {
      dataPack.getGraphFacade().setVisibleBranches(branchFilter != null ? getMatchingHeads(dataPack, branchFilter) : null);
    }
    catch (InvalidRequestException e) {
      if (!myLogDataHolder.isFullLogShowing()) {
        myLogDataHolder.showFullLog(EmptyRunnable.getInstance());
        throw new ProcessCanceledException();
      }
      else {
        throw e;
      }
    }
  }

  @NotNull
  private Collection<Integer> getMatchingHeads(@NotNull DataPack dataPack, @NotNull VcsLogBranchFilter branchFilter) {
    final Collection<String> branchNames = new HashSet<String>(branchFilter.getBranchNames());
    return ContainerUtil.mapNotNull(dataPack.getRefsModel().getAllRefs(), new Function<VcsRef, Integer>() {
      @Override
      public Integer fun(VcsRef ref) {
        if (branchNames.contains(ref.getName())) {
          return myLogDataHolder.getCommitIndex(ref.getCommitHash());
        }
        return null;
      }
    });
  }

  @NotNull
  private List<Hash> filterInMemory(@NotNull DataPack dataPack, @NotNull List<VcsLogDetailsFilter> detailsFilters) {
    List<Hash> result = ContainerUtil.newArrayList();
    for (int visibleCommit : VcsLogUtil.getVisibleCommits(dataPack.getGraphFacade())) {
      VcsCommitMetadata data = getDetailsFromCache(visibleCommit);
      if (data == null) {
        // no more continuous details in the cache
        break;
      }
      if (matchesAllFilters(data, detailsFilters)) {
        result.add(data.getId());
      }
    }
    return result;
  }

  private static boolean matchesAllFilters(@NotNull final VcsCommitMetadata commit, @NotNull List<VcsLogDetailsFilter> detailsFilters) {
    return !ContainerUtil.exists(detailsFilters, new Condition<VcsLogDetailsFilter>() {
      @Override
      public boolean value(VcsLogDetailsFilter filter) {
        return !filter.matches(commit);
      }
    });
  }

  @Nullable
  private VcsCommitMetadata getDetailsFromCache(final int commitIndex) {
    final Hash hash = myLogDataHolder.getHash(commitIndex);
    VcsCommitMetadata details = myLogDataHolder.getTopCommitDetails(hash);
    if (details != null) {
      return details;
    }
    final Ref<VcsCommitMetadata> ref = Ref.create();
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        ref.set(myLogDataHolder.getCommitDetailsGetter().getCommitDataIfAvailable(hash));
      }
    });
    return ref.get();
  }

  public void getFilteredDetailsFromTheVcs(@NotNull final Map<VirtualFile, VcsLogProvider> providers,
                                           @NotNull final VcsLogFilterCollection filterCollection,
                                           @NotNull final Consumer<List<Hash>> success,
                                           final int maxCount) {
    myLogDataHolder.runInBackground(new ThrowableConsumer<ProgressIndicator, VcsException>() {
      @Override
      public void consume(ProgressIndicator indicator) throws VcsException {
        Collection<List<? extends TimedVcsCommit>> logs = ContainerUtil.newArrayList();
        for (Map.Entry<VirtualFile, VcsLogProvider> entry : providers.entrySet()) {
          final VirtualFile root = entry.getKey();

          if (filterCollection.getStructureFilter() != null && filterCollection.getStructureFilter().getFiles(root).isEmpty()
              || filterCollection.getUserFilter() != null && filterCollection.getUserFilter().getUserNames(root).isEmpty()) {
            // there is a structure or user filter, but it doesn't match this root
            continue;
          }

          List<TimedVcsCommit> matchingCommits = entry.getValue().getCommitsMatchingFilter(root, filterCollection, maxCount);
          logs.add(matchingCommits);
        }

        final List<? extends TimedVcsCommit> compoundLog = new VcsLogMultiRepoJoiner().join(logs);

        final List<Hash> list = ContainerUtil.map(compoundLog, new Function<TimedVcsCommit, Hash>() {
          @Override
          public Hash fun(TimedVcsCommit commit) {
            return commit.getId();
          }
        });

        UIUtil.invokeAndWaitIfNeeded(new Runnable() {
          @Override
          public void run() {
            if (!myLogDataHolder.getProject().isDisposed()) {
              success.consume(list);
            }
          }
        });
      }
    }, "Looking for more results...");
  }

}
