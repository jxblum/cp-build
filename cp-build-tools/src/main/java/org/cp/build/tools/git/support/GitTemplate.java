/*
 * Copyright 2011-Present Author or Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cp.build.tools.git.support;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.cp.build.tools.api.support.Utils;
import org.cp.build.tools.git.model.CommitHistory;
import org.cp.build.tools.git.model.CommitRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.lang.NonNull;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Data Access Object (DAO) and Template for {@link Git}.
 *
 * @author John Blum
 * @see org.cp.build.tools.git.model.CommitHistory
 * @see org.cp.build.tools.git.model.CommitRecord
 * @see org.eclipse.jgit.api.Git
 * @since 2.0.0
 */
@Getter(AccessLevel.PROTECTED)
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@SuppressWarnings("unused")
public class GitTemplate {

  protected static final String MAIN_BRANCH_NAME = "main";
  protected static final String MASTER_BRANCH_NAME = "master";

  public static @NonNull GitTemplate from(@NonNull Supplier<Git> gitSupplier) {

    Utils.requireObject(gitSupplier, "Supplier for Git is required");

    return new GitTemplate(gitSupplier);
  }

  @NonNull
  private final Supplier<Git> git;

  protected @NonNull Git git() {
    return getGit().get();
  }

  public @NonNull CommitHistory getCommitHistory() {

    try (Git git = git()){

      LogCommand logCommand = git.log().all();

      Iterable<RevCommit> commits = logCommand.call();

      Set<CommitRecord> commitRecords = new HashSet<>();

      for (RevCommit commit : commits) {

        CommitRecord commitRecord =
          resolveCommittedSourceFiles(logCommand.getRepository(), commit, newCommitRecord(commit));

        commitRecords.add(commitRecord);
      }

      return CommitHistory.of(commitRecords);
    }
    catch (Exception cause) {
      throw new GitException("Failed to load commit history", cause);
    }
  }

  private @NonNull CommitRecord newCommitRecord(@NonNull RevCommit commit) {

    PersonIdent commitAuthor = resolveCommitAuthor(commit);

    LocalDateTime dateTime = resolveCommitDateTime(commit);

    String hash = resolveCommitHash(commit);

    CommitRecord.Author author = newCommitRecordAuthor(commitAuthor);

    return CommitRecord.of(author, dateTime, hash)
      .withMessage(commit.getFullMessage());
  }

  private @NonNull CommitRecord.Author newCommitRecordAuthor(@NonNull PersonIdent commitAuthor) {
    return CommitRecord.Author.as(commitAuthor.getName()).withEmailAddress(commitAuthor.getEmailAddress());
  }

  private PersonIdent resolveCommitAuthor(@NonNull RevCommit commit) {
    //return commit.getAuthorIdent();
    return commit.getCommitterIdent();
  }

  private LocalDateTime resolveCommitDateTime(@NonNull RevCommit commit) {

    return Instant.ofEpochMilli(TimeUnit.SECONDS.toMillis(commit.getCommitTime()))
      .atZone(ZoneOffset.systemDefault())
      .toLocalDateTime();
  }

  private String resolveCommitHash(@NonNull RevCommit commit) {
    return commit.name();
  }

  private @NonNull CommitRecord resolveCommittedSourceFiles(@NonNull Repository repository, @NonNull RevCommit commit,
      @NonNull CommitRecord commitRecord) throws Exception {

    List<File> sourceFiles = resolveCommittedSourceFilesUsingDiffFormatter(repository, commit);

    commitRecord.add(sourceFiles.toArray(new File[0]));

    return commitRecord;
  }

  // @see https://www.eclipse.org/forums/index.php/t/213979/
  private List<File> resolveCommittedSourceFilesUsingDiffFormatter(Repository repository, RevCommit commit)
      throws Exception {

    DiffFormatter diffFormatter = newDiffFormatter(repository);

    List<DiffEntry> diffs =
      diffFormatter.scan(resolvePreviousCommit(repository, commit).getTree(), commit.getTree());

    List<File> sourceFiles = new ArrayList<>();

    for (DiffEntry diff : diffs) {
      sourceFiles.add(new File(diff.getNewPath()));
    }

    return sourceFiles;
  }

  private static @NonNull DiffFormatter newDiffFormatter(@NonNull Repository repository) {

    DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);

    diffFormatter.setRepository(repository);
    diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);
    diffFormatter.setDetectRenames(true);

    return diffFormatter;
  }

  private static @NonNull RevCommit resolvePreviousCommit(@NonNull Repository repository, @NonNull RevCommit commit)
      throws Exception {

    Supplier<ObjectId> headObjectIdSupplier = () -> {
      try {
        return repository.resolve(Constants.HEAD);
      }
      catch (Exception cause) {
        throw new GitException("Failed to resolve ObjectId for HEAD", cause);
      }
    };

    ObjectId previousCommitId = Utils.get(repository.resolve(commit.name().concat("~1")), headObjectIdSupplier);

    return new RevWalk(repository).parseCommit(previousCommitId);
  }
}
