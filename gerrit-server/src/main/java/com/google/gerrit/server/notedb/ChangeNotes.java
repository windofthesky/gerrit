// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.notedb;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.gerrit.reviewdb.client.RefNames.changeMetaRef;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.data.SubmitRecord;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchLineComment;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ReviewerSet;
import com.google.gerrit.server.git.RefCache;
import com.google.gerrit.server.git.RepoRefCache;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.schema.DisabledChangesReviewDbWrapper;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** View of a single {@link Change} based on the log of its notes branch. */
public class ChangeNotes extends AbstractChangeNotes<ChangeNotes> {
  private static final Logger log = LoggerFactory.getLogger(ChangeNotes.class);

  static final Ordering<PatchSetApproval> PSA_BY_TIME =
      Ordering.natural().onResultOf(
        new Function<PatchSetApproval, Timestamp>() {
          @Override
          public Timestamp apply(PatchSetApproval input) {
            return input.getGranted();
          }
        });

  public static final Ordering<ChangeMessage> MESSAGE_BY_TIME =
      Ordering.natural().onResultOf(
        new Function<ChangeMessage, Timestamp>() {
          @Override
          public Timestamp apply(ChangeMessage input) {
            return input.getWrittenOn();
          }
        });

  public static ConfigInvalidException parseException(Change.Id changeId,
      String fmt, Object... args) {
    return new ConfigInvalidException("Change " + changeId + ": "
        + String.format(fmt, args));
  }

  @Singleton
  public static class Factory {
    private final Args args;
    private final Provider<InternalChangeQuery> queryProvider;
    private final ProjectCache projectCache;

    @VisibleForTesting
    @Inject
    public Factory(Args args,
        Provider<InternalChangeQuery> queryProvider,
        ProjectCache projectCache) {
      this.args = args;
      this.queryProvider = queryProvider;
      this.projectCache = projectCache;
    }

    public ChangeNotes createChecked(ReviewDb db, Change c)
        throws OrmException, NoSuchChangeException {
      ChangeNotes notes = create(db, c.getProject(), c.getId());
      if (notes.getChange() == null) {
        throw new NoSuchChangeException(c.getId());
      }
      return notes;
    }

    public ChangeNotes createChecked(ReviewDb db, Project.NameKey project,
        Change.Id changeId) throws OrmException, NoSuchChangeException {
      ChangeNotes notes = create(db, project, changeId);
      if (notes.getChange() == null) {
        throw new NoSuchChangeException(changeId);
      }
      return notes;
    }

    public ChangeNotes createChecked(Change.Id changeId)
        throws OrmException, NoSuchChangeException {
      InternalChangeQuery query = queryProvider.get().noFields();
      List<ChangeData> changes = query.byLegacyChangeId(changeId);
      if (changes.isEmpty()) {
        throw new NoSuchChangeException(changeId);
      }
      if (changes.size() != 1) {
        log.error(
            String.format("Multiple changes found for %d", changeId.get()));
        throw new NoSuchChangeException(changeId);
      }
      return changes.get(0).notes();
    }

    public ChangeNotes create(ReviewDb db, Project.NameKey project,
        Change.Id changeId) throws OrmException {
      Change change = unwrap(db).changes().get(changeId);
      checkNotNull(change,
          "change %s not found in ReviewDb", changeId);
      checkArgument(change.getProject().equals(project),
          "passed project %s when creating ChangeNotes for %s, but actual"
          + " project is %s",
          project, changeId, change.getProject());
      // TODO: Throw NoSuchChangeException when the change is not found in the
      // database
      return new ChangeNotes(args, change).load();
    }

    /**
     * Create change notes for a change that was loaded from index. This method
     * should only be used when database access is harmful and potentially stale
     * data from the index is acceptable.
     *
     * @param change change loaded from secondary index
     * @return change notes
     */
    public ChangeNotes createFromIndexedChange(Change change) {
      return new ChangeNotes(args, change);
    }

    public ChangeNotes createForNew(Change change) throws OrmException {
      return new ChangeNotes(args, change).load();
    }

    // TODO(dborowitz): Remove when deleting index schemas <27.
    public ChangeNotes createFromIdOnlyWhenNoteDbDisabled(
        ReviewDb db, Change.Id changeId) throws OrmException {
      checkState(!args.migration.readChanges(), "do not call"
          + " createFromIdOnlyWhenNoteDbDisabled when NoteDb is enabled");
      Change change = unwrap(db).changes().get(changeId);
      checkNotNull(change,
          "change %s not found in ReviewDb", changeId);
      return new ChangeNotes(args, change).load();
    }

    public ChangeNotes createWithAutoRebuildingDisabled(Change change,
        RefCache refs) throws OrmException {
      return new ChangeNotes(args, change, false, refs).load();
    }

    // TODO(ekempin): Remove when database backend is deleted
    /**
     * Instantiate ChangeNotes for a change that has been loaded by a batch read
     * from the database.
     */
    private ChangeNotes createFromChangeOnlyWhenNoteDbDisabled(Change change)
        throws OrmException {
      checkState(!args.migration.readChanges(), "do not call"
          + " createFromChangeWhenNoteDbDisabled when NoteDb is enabled");
      return new ChangeNotes(args, change).load();
    }

    public CheckedFuture<ChangeNotes, OrmException> createAsync(
        final ListeningExecutorService executorService, final ReviewDb db,
        final Project.NameKey project, final Change.Id changeId) {
      return Futures.makeChecked(
          Futures.transformAsync(unwrap(db).changes().getAsync(changeId),
              new AsyncFunction<Change, ChangeNotes>() {
                @Override
                public ListenableFuture<ChangeNotes> apply(
                    final Change change) {
                  return executorService.submit(new Callable<ChangeNotes>() {
                    @Override
                    public ChangeNotes call() throws Exception {
                      checkArgument(change.getProject().equals(project),
                          "passed project %s when creating ChangeNotes for %s,"
                              + " but actual project is %s",
                          project, changeId, change.getProject());
                      return new ChangeNotes(args, change).load();
                    }
                  });
                }
              }), new Function<Exception, OrmException>() {
                @Override
                public OrmException apply(Exception e) {
                  if (e instanceof OrmException) {
                    return (OrmException) e;
                  }
                  return new OrmException(e);
                }
              });
    }

    public List<ChangeNotes> create(ReviewDb db,
        Collection<Change.Id> changeIds) throws OrmException {
      List<ChangeNotes> notes = new ArrayList<>();
      if (args.migration.enabled()) {
        for (Change.Id changeId : changeIds) {
          try {
            notes.add(createChecked(changeId));
          } catch (NoSuchChangeException e) {
            // Ignore missing changes to match Access#get(Iterable) behavior.
          }
        }
        return notes;
      }

      for (Change c : unwrap(db).changes().get(changeIds)) {
        notes.add(createFromChangeOnlyWhenNoteDbDisabled(c));
      }
      return notes;
    }

    public List<ChangeNotes> create(ReviewDb db, Project.NameKey project,
        Collection<Change.Id> changeIds, Predicate<ChangeNotes> predicate)
            throws OrmException {
      List<ChangeNotes> notes = new ArrayList<>();
      if (args.migration.enabled()) {
        for (Change.Id cid : changeIds) {
          ChangeNotes cn = create(db, project, cid);
          if (cn.getChange() != null && predicate.apply(cn)) {
            notes.add(cn);
          }
        }
        return notes;
      }

      for (Change c : unwrap(db).changes().get(changeIds)) {
        if (c != null && project.equals(c.getDest().getParentKey())) {
          ChangeNotes cn = createFromChangeOnlyWhenNoteDbDisabled(c);
          if (predicate.apply(cn)) {
            notes.add(cn);
          }
        }
      }
      return notes;
    }

    public ListMultimap<Project.NameKey, ChangeNotes> create(ReviewDb db,
        Predicate<ChangeNotes> predicate) throws IOException, OrmException {
      ListMultimap<Project.NameKey, ChangeNotes> m = ArrayListMultimap.create();
      if (args.migration.readChanges()) {
        for (Project.NameKey project : projectCache.all()) {
          try (Repository repo = args.repoManager.openRepository(project)) {
            List<ChangeNotes> changes = scanNoteDb(repo, db, project);
            for (ChangeNotes cn : changes) {
              if (predicate.apply(cn)) {
                m.put(project, cn);
              }
            }
          }
        }
      } else {
        for (Change change : unwrap(db).changes().all()) {
          ChangeNotes notes = createFromChangeOnlyWhenNoteDbDisabled(change);
          if (predicate.apply(notes)) {
            m.put(change.getProject(), notes);
          }
        }
      }
      return ImmutableListMultimap.copyOf(m);
    }

    public List<ChangeNotes> scan(Repository repo, ReviewDb db,
        Project.NameKey project) throws OrmException, IOException {
      if (!args.migration.readChanges()) {
        return scanDb(repo, db);
      }

      return scanNoteDb(repo, db, project);
    }

    private List<ChangeNotes> scanDb(Repository repo, ReviewDb db)
        throws OrmException, IOException {
      Set<Change.Id> ids = scan(repo);
      List<ChangeNotes> notes = new ArrayList<>(ids.size());
      // A batch size of N may overload get(Iterable), so use something smaller,
      // but still >1.
      for (List<Change.Id> batch : Iterables.partition(ids, 30)) {
        for (Change change : unwrap(db).changes().get(batch)) {
          notes.add(createFromChangeOnlyWhenNoteDbDisabled(change));
        }
      }
      return notes;
    }

    private List<ChangeNotes> scanNoteDb(Repository repo, ReviewDb db,
        Project.NameKey project) throws OrmException, IOException {
      Set<Change.Id> ids = scan(repo);
      List<ChangeNotes> changeNotes = new ArrayList<>(ids.size());
      for (Change.Id id : ids) {
        changeNotes.add(create(db, project, id));
      }
      return changeNotes;
    }

    public static Set<Change.Id> scan(Repository repo) throws IOException {
      Map<String, Ref> refs =
          repo.getRefDatabase().getRefs(RefNames.REFS_CHANGES);
      Set<Change.Id> ids = new HashSet<>(refs.size());
      for (Ref r : refs.values()) {
        Change.Id id = Change.Id.fromRef(r.getName());
        if (id != null) {
          ids.add(id);
        }
      }
      return ids;
    }
  }

  private static ReviewDb unwrap(ReviewDb db) {
    if (db instanceof DisabledChangesReviewDbWrapper) {
      db = ((DisabledChangesReviewDbWrapper) db).unsafeGetDelegate();
    }
    return db;
  }

  private final RefCache refs;

  private Change change;
  private ChangeNotesState state;

  // Parsed note map state, used by ChangeUpdate to make in-place editing of
  // notes easier.
  RevisionNoteMap revisionNoteMap;

  private DraftCommentNotes draftCommentNotes;

  @VisibleForTesting
  public ChangeNotes(Args args, Change change) {
    this(args, change, true, null);
  }

  private ChangeNotes(Args args, Change change, boolean autoRebuild,
      @Nullable RefCache refs) {
    super(args, change.getId(), autoRebuild);
    this.change = new Change(change);
    this.refs = refs;
  }

  public Change getChange() {
    return change;
  }

  public ImmutableMap<PatchSet.Id, PatchSet> getPatchSets() {
    return state.patchSets();
  }

  public ImmutableListMultimap<PatchSet.Id, PatchSetApproval> getApprovals() {
    return state.approvals();
  }

  public ReviewerSet getReviewers() {
    return state.reviewers();
  }

  /**
   *
   * @return a ImmutableSet of all hashtags for this change sorted in alphabetical order.
   */
  public ImmutableSet<String> getHashtags() {
    return ImmutableSortedSet.copyOf(state.hashtags());
  }

  /**
   * @return a list of all users who have ever been a reviewer on this change.
   */
  public ImmutableList<Account.Id> getAllPastReviewers() {
    return state.allPastReviewers();
  }

  /**
   * @return submit records stored during the most recent submit; only for
   *     changes that were actually submitted.
   */
  public ImmutableList<SubmitRecord> getSubmitRecords() {
    return state.submitRecords();
  }

  /** @return all change messages, in chronological order, oldest first. */
  public ImmutableList<ChangeMessage> getChangeMessages() {
    return state.allChangeMessages();
  }

  /**
   * @return change messages by patch set, in chronological order, oldest
   *     first.
   */
  public ImmutableListMultimap<PatchSet.Id, ChangeMessage>
      getChangeMessagesByPatchSet() {
    return state.changeMessagesByPatchSet();
  }

  /** @return inline comments on each revision. */
  public ImmutableListMultimap<RevId, PatchLineComment> getComments() {
    return state.publishedComments();
  }

  public ImmutableListMultimap<RevId, PatchLineComment> getDraftComments(
      Account.Id author) throws OrmException {
    loadDraftComments(author);
    final Multimap<RevId, PatchLineComment> published =
        state.publishedComments();
    // Filter out any draft comments that also exist in the published map, in
    // case the update to All-Users to delete them during the publish operation
    // failed.
    Multimap<RevId, PatchLineComment> filtered = Multimaps.filterEntries(
        draftCommentNotes.getComments(),
        new Predicate<Map.Entry<RevId, PatchLineComment>>() {
          @Override
          public boolean apply(Map.Entry<RevId, PatchLineComment> in) {
            for (PatchLineComment c : published.get(in.getKey())) {
              if (c.getKey().equals(in.getValue().getKey())) {
                return false;
              }
            }
            return true;
          }
        });
    return ImmutableListMultimap.copyOf(
        filtered);
  }

  /**
   * If draft comments have already been loaded for this author, then they will
   * not be reloaded. However, this method will load the comments if no draft
   * comments have been loaded or if the caller would like the drafts for
   * another author.
   */
  private void loadDraftComments(Account.Id author)
      throws OrmException {
    if (draftCommentNotes == null ||
        !author.equals(draftCommentNotes.getAuthor())) {
      draftCommentNotes =
          new DraftCommentNotes(args, change, author, autoRebuild);
      draftCommentNotes.load();
    }
  }

  @VisibleForTesting
  DraftCommentNotes getDraftCommentNotes() {
    return draftCommentNotes;
  }

  public boolean containsComment(PatchLineComment c) throws OrmException {
    if (containsCommentPublished(c)) {
      return true;
    }
    loadDraftComments(c.getAuthor());
    return draftCommentNotes.containsComment(c);
  }

  public boolean containsCommentPublished(PatchLineComment c) {
    for (PatchLineComment l : getComments().values()) {
      if (c.getKey().equals(l.getKey())) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected String getRefName() {
    return changeMetaRef(getChangeId());
  }

  public PatchSet getCurrentPatchSet() {
    PatchSet.Id psId = change.currentPatchSetId();
    return checkNotNull(state.patchSets().get(psId),
        "missing current patch set %s", psId.get());
  }

  @Override
  protected void onLoad(LoadHandle handle)
      throws IOException, ConfigInvalidException {
    ObjectId rev = handle.id();
    if (rev == null) {
      loadDefaults();
      return;
    }

    ChangeNotesCache.Value v = args.cache.get().get(
        getProjectName(), getChangeId(), rev, handle.walk());
    state = v.state();
    state.copyColumnsTo(change);
    revisionNoteMap = v.revisionNoteMap();
  }

  @Override
  protected void loadDefaults() {
    state = ChangeNotesState.empty(change);
  }

  @Override
  public Project.NameKey getProjectName() {
    return change.getProject();
  }

  @Override
  protected ObjectId readRef(Repository repo) throws IOException {
    return refs != null
        ? refs.get(getRefName()).orNull()
        : super.readRef(repo);
  }

  @Override
  protected LoadHandle openHandle(Repository repo) throws IOException {
    if (autoRebuild) {
      NoteDbChangeState state = NoteDbChangeState.parse(change);
      ObjectId id = readRef(repo);
      if (state == null && id == null) {
        return super.openHandle(repo, id);
      }
      RefCache refs = this.refs != null ? this.refs : new RepoRefCache(repo);
      if (!NoteDbChangeState.isChangeUpToDate(state, refs, getChangeId())) {
        return rebuildAndOpen(repo, id);
      }
    }
    return super.openHandle(repo);
  }

  private LoadHandle rebuildAndOpen(Repository repo, ObjectId oldId)
      throws IOException {
    try {
      NoteDbChangeState newState;
      try {
        newState = args.rebuilder.get().rebuild(args.db.get(), getChangeId());
        repo.scanForRepoChanges();
      } catch (IOException e) {
        newState = recheckUpToDate(repo, e);
      }
      if (newState == null) {
        return super.openHandle(repo, oldId); // May be null in tests.
      }
      return LoadHandle.create(
          ChangeNotesCommit.newRevWalk(repo), newState.getChangeMetaId());
    } catch (NoSuchChangeException e) {
      return super.openHandle(repo, oldId);
    } catch (OrmException | ConfigInvalidException e) {
      throw new IOException(e);
    }
  }

  private NoteDbChangeState recheckUpToDate(Repository repo, IOException e)
      throws IOException {
    // Should only be non-null if auto-rebuilding disabled.
    checkState(refs == null);
    // An error during auto-rebuilding might be caused by LOCK_FAILURE or a
    // similar contention issue, where another thread successfully rebuilt the
    // change. Reread the change from ReviewDb and NoteDb and recheck the state.
    Change newChange;
    try {
      newChange = unwrap(args.db.get()).changes().get(getChangeId());
    } catch (OrmException e2) {
      logRecheckError(e2);
      throw e;
    }
    NoteDbChangeState newState = NoteDbChangeState.parse(newChange);
    boolean upToDate;
    try {
      repo.scanForRepoChanges();
      upToDate = NoteDbChangeState.isChangeUpToDate(
          newState, new RepoRefCache(repo), getChangeId());
    } catch (IOException e2) {
      logRecheckError(e2);
      throw e;
    }
    if (!upToDate) {
      log.warn("Rechecked change {} after a rebuild error, but it was not up to"
          + " date; rethrowing exception", getChangeId());
      throw e;
    }
    change = new Change(newChange);
    return newState;
  }

  private void logRecheckError(Throwable t) {
    log.error("Error rechecking if change " + getChangeId()
        + " is up to date; logging this exception but rethrowing original", t);
  }
}
