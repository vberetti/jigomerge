/** Copyright Vincent Beretti vberetti|at|gmail.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * */
public class SvnMergeTool {

  def static final List<String> DEFAULT_NO_MERGE_COMMENT_PATTERNS = ['maven-release-plugin', 'NOMERGE', 'NO-MERGE', 'Initialized merge tracking via "svnmerge" with revisions']

  /** parameter dryRun - should the script commit the changes or not           */
  def boolean dryRun = true
  def List<String> noMergeCommentPatterns = []
  def boolean mergeOneByOne = false
  def boolean mergeEager = false
  def boolean verbose = false

  SvnMergeTool(boolean dryRun, List<String> noMergeCommentPatterns, boolean mergeOneByOne, boolean mergeEager, boolean verbose) {
    this.dryRun = dryRun
    if (noMergeCommentPatterns.isEmpty()) {
      this.noMergeCommentPatterns = DEFAULT_NO_MERGE_COMMENT_PATTERNS
    } else {
      this.noMergeCommentPatterns = noMergeCommentPatterns
    }
    this.mergeOneByOne = mergeOneByOne
    this.mergeEager = mergeEager
    this.verbose = verbose
  }

  /**
   * DEPRECATED
   * launch the merge <br>
   * return boolean - true if everything went fine or false if manual merge to be done
   */
  public def boolean launchSvnmerge(String initialMergeUrl) {
    def boolean globalStatus = true

    def String repositoryRoot = retrieveRepositoryRoot()
    def String mergeUrl = initialMergeUrl.replace(repositoryRoot, '')

    // reset workspace
    resetWorkspace()

    // retrieve all available revisions
    def revisions = retrieveAvailableRevisions(mergeUrl)

    def nbRevisions = revisions.size()

    println 'Merging ' + nbRevisions + ' revisions ...'

    def validRevisions = []

    for (int i = 0; i < nbRevisions; i++) {
      def revision = revisions[i]
      println ' Handling revision ' + revision + ' ...'
      def comment = retrieveCommentFromRevision(mergeUrl, revision)

      // verify on comment that revision should not be blocked
      if (shouldRevisionBeBlocked(comment)) {
        println '  Blocking revision ' + revision + ' with comment \'' + comment + '\' ...'
        // block revision
        def status = svnmergeBlock(mergeUrl, revision)
        if (!status) {
          throw new RuntimeException('Blocking revision ' + revision + ' failed !')
        }

        if (!dryRun) {
          println '  Committing block revision ' + revision + ' ...'
          status = svnCommitSvnmerge()
          if (!status) {
            throw new RuntimeException('Commiting block revision ' + revision + ' failed !')
          }
        } else {
          println '  [DRY RUN SIMULATION] - Committing block revision ' + revision + ' ...'
        }

        svnUpdate()
        println '  Revision ' + revision + ' blocked'
      } else {
        // verify merge has no conflict with the current revision and previous ones
        def subRevisions = []
        subRevisions.addAll(validRevisions)
        subRevisions.add(revision)
        def revisionsList = buildRevisionsList(subRevisions)

        svnmergeMerge(mergeUrl, revisionsList)

        def hasConflicts = hasWorkspaceConflicts()

        // in any case, revert changes, cleanup workspace
        resetWorkspace()

        if (hasConflicts) {
          // globalStatus is set to false, this means manual merge needs to be done
          globalStatus = false

          if (!mergeEager) {
            println '  Revision ' + revision + ' has conflict, merging only previous revisions ...'
            // revision has conflict, stop merge
            break;
          } else {
            println '  Revision ' + revision + ' has conflict, continue merging ...'
          }
        } else {
          if (mergeOneByOne) {
            svnmergeAndCommit(mergeUrl, revision)
          } else {
            println '  Revision ' + revision + ' has no conflict'
            // add current revision to revisions to be merged
            validRevisions.add(revision);
          }
        }
      }
    }



    if (!mergeOneByOne) {
      // reset workspace before real merge
      resetWorkspace()
      if (!validRevisions.isEmpty()) {

        // merge all valid revisions

        def validRevisionsList = buildRevisionsList(validRevisions)

        svnmergeAndCommit(mergeUrl, validRevisionsList)
      } else {
        println 'No valid revision to merge'
      }
    }

    svnUpdate()
    if (!globalStatus) {
      println 'MANUAL MERGE NEEDS TO BE DONE !'
    }
    println 'Merge is finished !'

    return globalStatus
  }

  /**
   * launch the merge <br>
   * return boolean - true if everything went fine or false if manual merge to be done
   */
  public def boolean launchSvnMerge(String mergeUrl) {
    def boolean globalStatus = true

    // reset workspace
    resetWorkspace()

    // retrieve all available revisions
    def revisions = retrieveAvailableRevisionsMergeInfo(mergeUrl)

    def nbRevisions = revisions.size()

    println 'Merging ' + nbRevisions + ' revisions ...'

    def validRevisions = []

    for (int i = 0; i < nbRevisions; i++) {
      def revision = revisions[i]
      println ' Handling revision ' + revision + ' ...'
      def comment = retrieveCommentFromRevisionWithLog(mergeUrl, revision)

      // verify on comment that revision should not be blocked
      if (shouldRevisionBeBlocked(comment)) {
        println '  Blocking revision ' + revision + '\' ...'
        // block revision
        def status = svnMergeBlock(mergeUrl, revision)
        if (!status) {
          throw new RuntimeException('Blocking revision ' + revision + ' failed !')
        }

        if (!dryRun) {
          println '  Committing block revision ' + revision + ' ...'
          status = svnCommitMergeBlock(revision, comment)
          if (!status) {
            throw new RuntimeException('Commiting block revision ' + revision + ' failed !')
          }
        } else {
          println '  [DRY RUN SIMULATION] - Committing block revision ' + revision + ' ...'
        }

        svnUpdate()
        println '  Revision ' + revision + ' blocked'
      } else {
        // verify merge has no conflict with the current revision and previous ones
        def subRevisions = []
        subRevisions.addAll(validRevisions)
        subRevisions.add(revision)

        svnMergeMerge(mergeUrl, subRevisions)

        def hasConflicts = hasWorkspaceConflicts()

        // in any case, revert changes, cleanup workspace
        resetWorkspace()

        if (hasConflicts) {
          // globalStatus is set to false, this means manual merge needs to be done
          globalStatus = false

          if (!mergeEager) {
            println '  Revision ' + revision + ' has conflict, merging only previous revisions ...'
            // revision has conflict, stop merge
            break;
          } else {
            println '  Revision ' + revision + ' has conflict, continue merging ...'
          }
        } else {
          if (mergeOneByOne) {
            svnMergeAndCommit(mergeUrl, revision)
          } else {
            println '  Revision ' + revision + ' has no conflict'
            // add current revision to revisions to be merged
            validRevisions.add(revision);
          }
        }
      }
    }



    if (!mergeOneByOne) {
      // reset workspace before real merge
      resetWorkspace()
      if (!validRevisions.isEmpty()) {

        // merge all valid revisions

        svnMergeAndCommit(mergeUrl, validRevisions)
      } else {
        println 'No valid revision to merge'
      }
    }

    svnUpdate()
    if (!globalStatus) {
      println 'MANUAL MERGE NEEDS TO BE DONE !'
    }
    println 'Merge is finished !'

    return globalStatus
  }

  /**
   * DEPRECATED
   */
  protected def void svnmergeAndCommit(String mergeUrl, String revisionsList) {
    def status = svnmergeMerge(mergeUrl, revisionsList)
    if (!status) {
      throw new RuntimeException('Merging valid revisions (' + revisionsList + ') failed !')
    }

    if (!dryRun) {
      println 'Committing merged revisions (' + revisionsList + ') ...'
      status = svnCommitSvnmerge()
      if (!status) {
        throw new RuntimeException('Committing valid revisions merge (' + revisionsList + ') failed !')
      }
    } else {
      println '[DRY RUN SIMULATION] - Committing merged revisions (' + revisionsList + ') ...'
    }
  }

  protected def void svnMergeAndCommit(String mergeUrl, List<String> revisionsList) {

    def revisionsListLabel = buildRevisionsList(revisionsList)
    def status = svnMergeMerge(mergeUrl, revisionsList)
    if (!status) {
      throw new RuntimeException('Merging valid revisions (' + revisionsListLabel + ') failed !')
    }

    if (!dryRun) {
      println 'Committing merged revisions (' + revisionsListLabel + ') ...'
      def commentFile = new File('jigomerge-comments.txt')
      commentFile << 'Merged revisions : ' + revisionsListLabel + '\n'
      for (String revision in revisionsList) {
        def revisionComment = retrieveCommentFromRevisionWithLog(mergeUrl, revision)
        commentFile << 'Revision  #' + revision + '\n'
        commentFile << '----------------------\n'
        commentFile << revisionComment + '\n'
        commentFile << '----------------------\n'
        commentFile << '\n'
      }
      status = svnCommitMergeMerge(commentFile)
      commentFile.delete()
      if (!status) {
        throw new RuntimeException('Committing valid revisions merge (' + revisionsListLabel + ') failed !')
      }
    } else {
      println '[DRY RUN SIMULATION] - Committing merged revisions (' + revisionsListLabel + ') ...'
    }
  }

  protected def void resetWorkspace() {
    def status = true
    status &= svnRevertAllRecursively()
    status &= svnUpdate()

    // delete unversionned files
    listUnversionnedFiles().each() {file ->
      if (file.isDirectory()) {
        file.deleteDir()
      } else {
        file.delete()
      }
    }

    if (!status) {
      throw new RuntimeException('Failed to reset workspace !')
    }
  }

  protected def List<File> listUnversionnedFiles() {
    def process = svnStatus('--xml')
    def statusXmlLog = process.in.text
    def files = []

    def statusParser = new XmlSlurper().parseText(statusXmlLog)
    def unversionned = statusParser.target.entry.findAll() {it -> it."wc-status".@item.text() == 'unversioned'}

    unversionned.each() {it ->
      def path = it.@path.text()
      files.add(new File(path))
    }

    return files
  }

  protected def boolean hasWorkspaceConflicts() {
    def process = svnStatus('--xml')
    def statusXmlLog = process.in.text

    def statusParser = new XmlSlurper().parseText(statusXmlLog)
    def conflicts = statusParser.target.entry.findAll() {it -> it."wc-status".@item.text() == 'conflicted' || it."wc-status".@props.text() == 'conflicted' || it."wc-status".@"tree-conflicted".text() == "true" }

    return conflicts.size() > 0
  }

  protected def String buildRevisionsList(List<String> revisions) {

    def revisionsList = ''

    for (String revision in revisions) {
      revisionsList += revision + ','
    }

    revisionsList = revisionsList.substring(0, revisionsList.length() - 1)
    return revisionsList
  }

  protected def boolean shouldRevisionBeBlocked(String comment) {
    def boolean block = false
    for (pattern in noMergeCommentPatterns) {
      block = comment.toUpperCase().contains(pattern.toUpperCase())
      if (block) {
        break;
      }
    }

    return block
  }

  /**
   * DEPRECATED
   */
  protected def String[] retrieveAvailableRevisions(String mergeUrl) {
    def process = executeCommand('svnmerge avail -S ' + mergeUrl)
    def log = process.in.text

    def tmpRevisions = []
    if (log.trim() != '') {
      tmpRevisions = log.trim().split(',')
    }

    def revisions = []
    for (tmpRevision in tmpRevisions) {
      if (tmpRevision.contains('-')) {
        def startEnd = tmpRevision.split('-')
        def start = new Integer(startEnd[0])
        def end = new Integer(startEnd[1])
        for (revision in start..end) {
          revisions.add(revision)
        }
      } else {
        revisions.add(tmpRevision)
      }
    }

    return revisions
  }

  protected def String[] retrieveAvailableRevisionsMergeInfo(String mergeUrl) {
    def process = executeCommand('svn mergeinfo --show-revs eligible ' + mergeUrl + ' .')
    def log = process.in.text

    def revisions = []
    log.eachLine() {it ->
      revisions.add(it.replace('r', ''));
    }

    return revisions
  }

  /**
   * DEPRECATED
   */
  protected def String retrieveCommentFromRevision(String mergeUrl, String revision) {
    def process = executeCommand('svnmerge avail -l -r ' + revision + ' -S ' + mergeUrl)
    def log = process.in.text

    def comment = log.trim()
    return comment
  }


  protected def String retrieveCommentFromRevisionWithLog(String mergeUrl, String revision) {
    def process = executeCommand('svn log --xml -r ' + revision + ' ' + mergeUrl)
    def logXml = process.in.text

    def log = new XmlSlurper().parseText(logXml)
    def comment = log.logentry.msg.text()

    return comment
  }

  /**
   * DEPRECATED
   */
  protected def String retrieveRepositoryRoot() {
    def process = executeCommand('svn info .')
    def log = process.in.text

    def matcher = (log =~ 'Repository Root:(.*)')

    return matcher[0][1].trim()
  }

  /**
   * DEPRECATED
   */
  public def boolean checkMergeIsInitialized(String mergeUrl) {
    def String repositoryRoot = retrieveRepositoryRoot()

    // validate merge url
    if (!mergeUrl.startsWith(repositoryRoot)) {
      throw new RuntimeException('Merge url must reference an url in the same repository than the working copy (\'' + repositoryRoot + '\')')
    }

    def mergeUrlSuffix = mergeUrl.replace(repositoryRoot, '')
    def process = executeCommand('svn propget svnmerge-integrated')
    def log = process.in.text

    boolean initialized = false
    log.eachLine() {line ->
      initialized |= line.contains(mergeUrlSuffix)
    }

    return initialized
  }

  /**
   * DEPRECATED
   */
  public def initMerge(String mergeUrl, String revision) {

    println 'Initializing merge to \'' + mergeUrl + '\' ...'

    // reset workspace
    resetWorkspace()
    String command = 'svnmerge init ' + mergeUrl
    if (revision != null) {
      command += ' -r1-' + revision
    }
    def status = executeCommandWithStatus(command)
    if (!status) {
      throw new RuntimeException('Merge initialization to \'' + mergeUrl + '\' failed !')
    }

    if (!dryRun) {
      println '  Committing merge initialization ...'
      status = svnCommitSvnmerge()
      if (!status) {
        throw new RuntimeException('Commiting merge initialization to \'' + mergeUrl + '\' failed !')
      }
    } else {
      println '  [DRY RUN SIMULATION] - Committing merge initialization ...'
    }

    println 'Merge initialization is finished !'
    return true
  }

  /**
   * DEPRECATED
   */
  protected def boolean svnmergeBlock(String mergeUrl, String revision) {
    return executeCommandWithStatus('svnmerge block -r ' + revision + ' -S ' + mergeUrl)
  }

  protected def boolean svnMergeBlock(String mergeUrl, String revision) {
    return executeCommandWithStatus('svn merge --accept postpone --record-only -c ' + revision + ' ' + mergeUrl + ' .')
  }

  /**
   * DEPRECATED
   */
  protected def boolean svnmergeMerge(String mergeUrl, String revisions) {
    return executeCommandWithStatus('svnmerge merge -r ' + revisions + ' -S ' + mergeUrl)
  }

  protected def boolean svnMergeMerge(String mergeUrl, List<String> revisions) {
    boolean status = true
    for (String revision: revisions) {
      String command = 'svn --accept postpone merge -c ' + revision + ' ' + mergeUrl + ' .'
      status = executeCommandWithStatus(command)
      if (!status) {
        println ' Executing ' + command + ' failed !'
        return false
      }
    }
    return true
  }

  protected def boolean svnUpdate() {
    return executeCommandWithStatus('svn update')
  }

  protected def svnStatus() {
    return svnStatus('')
  }

  protected def svnStatus(String options) {
    return executeCommand('svn status ' + options)
  }

  /**
   * DEPRECATED
   */
  protected def boolean svnCommitSvnmerge() {
    return svnCommit('-F svnmerge-commit-message.txt')
  }

  protected def boolean svnCommitMerge(String message) {
    return svnCommit('-m "' + message + '" .')
  }

  protected def boolean svnCommitMergeBlock(String revision, String comment) {
    def commentFile = new File('jigomerge-comments.txt')
    commentFile << 'Block revision #' + revision + '\n'
    commentFile << 'Initial message was : ' + comment
    def status = svnCommit('-F ' + commentFile.path + ' .')
    commentFile.delete()
    return status
  }

  protected def boolean svnCommitMergeMerge(File commentFile) {
    return svnCommit('-F ' + commentFile.path + ' .')
  }

  protected def boolean svnCommit(String options) {
    return executeCommandWithStatus('svn commit ' + options)
  }

  protected def boolean svnRevertAllRecursively() {
    return svnRevert('-R .')
  }

  protected def boolean svnRevert(String options) {
    return executeCommandWithStatus('svn revert ' + options)
  }

  protected def executeCommandWithStatus(String commandLabel) {
    def process = executeCommand(commandLabel)
    if (verbose) {
      def output = process.in.text
      if (output != null && output.trim() != '') {
        def debugOuput = ''
        output.trim().eachLine() {it ->
          debugOuput += '[DEBUG] ' + it + '\n'
        }
        println '[DEBUG] BEGIN command output :'
        print debugOuput
        println '[DEBUG] END command output'
      }
      def errOutput = process.errorStream.text
      if (errOutput != null && errOutput.trim() != '') {
        def errDebugOuput = ''
        errOutput.trim().eachLine() {it ->
          errDebugOuput += '[DEBUG][ERROR] ' + it + '\n'
        }
        println '[DEBUG] BEGIN ERROR command output :'
        print errDebugOuput
        println '[DEBUG] END ERROR command output'
      }
    }
    return (process.exitValue() == 0)
  }

  protected def executeCommand(String commandLabel) {
    if (verbose) {
      println '[DEBUG] executing command \'' + commandLabel + '\''
    }
    def process = commandLabel.execute()
    process.waitFor()
    if (verbose) {
      println '[DEBUG] exit value : ' + process.exitValue()
    }
    return process
  }

  public static void main(String[] args) {
    def cli = new CliBuilder(usage: 'Launch merge')
    cli.o(longOpt: 'old', 'old and DEPRECATED svnmerge version')
    cli.h(longOpt: 'help', 'prints this message')
    cli.i(longOpt: 'init', '[DEPRECATED] only init the merge, then stop. Only available with -o')
    cli.n(longOpt: 'no-init', '[DEPRECATED] do not init the merge. Fails if no initialization has been done for the specified url. Only available with -o')
    cli.b(longOpt: 'bidirectional', 'bidirectional merge. Used to ignore reflected revisions')
    cli.u(longOpt: 'url', args: 1, 'repository url to merge [REQUIRED]')
    cli.d(longOpt: 'dryRun', 'do not commit any modification')
    cli.s(longOpt: 'single', 'Merge one revision by one. One merge, one commit, one merge, one commit, ...')
    cli.p(longOpt: 'patterns', args: 1, 'patterns contained in comments of revisions not to be merged, separated by \',\'')
    cli.P(longOpt: 'patternsFile', args: 1, optionalArg: true, 'patterns file, default is \'patterns.txt\'')
    cli.r(longOpt: 'revisionInit', args: 1, '[DEPRECATED] initial revision for merge initialization')
    cli.e(longOpt: 'eager', 'eager merge: merge every revision that can be merged without conflict even if it follows a conflict')
    cli.v(longOpt: 'verbose', 'verbose mode')
    def options = cli.parse(args)

    if (!options || options.h || !options.u) {
      cli.usage()
      return
    }

    def boolean dryRun = options.d
    def boolean mergeOneByOne = options.s
    def boolean blockInitialization = options.n
    def boolean isMergeEager = options.e
    def boolean deprecatedSvnmerge = options.o
    def boolean isVerbose = options.v
    def String mergeUrl = new String(options.u.value)
    def String revisionInit = null
    if (options.r) {
      revisionInit = new String(options.r.value)
    }

    List<String> additionalPatterns = extractAdditionalPatterns(options)

    SvnMergeTool tool = new SvnMergeTool(dryRun, additionalPatterns, mergeOneByOne, isMergeEager, isVerbose)

    def boolean status = false
    if (deprecatedSvnmerge) {
      // check svnmerge init
      def boolean isMergeInitialized = tool.checkMergeIsInitialized(mergeUrl)

      // perform svnmerge init
      if (!isMergeInitialized) {
        if (blockInitialization) {
          println 'Merge is not initialized and option -n is active -> Exiting ...'
          System.exit(1)
        }
        println 'Merge is not yet initialized to \'' + mergeUrl + '\''
        tool.initMerge(mergeUrl, revisionInit)
      } else {
        println 'Merge is already initialized'
      }

      status = tool.launchSvnmerge(mergeUrl)
    } else {

      status = tool.launchSvnMerge(mergeUrl)
    }


    System.exit(status ? 0 : 1)
  }

  private static def extractAdditionalPatterns(options) {
    def additionalPatterns = []
    if (options.p) {
      String patternsList = new String(options.p.value)
      patternsList.split(',').each() {entry ->
        if (entry.trim() != "") {additionalPatterns.add(entry)}
      }
    } else if (options.P) {
      def patternsFilepath = 'patterns.txt'
      if (options.P.value != null) {
        patternsFilepath = options.P.value
      }
      def reader = new BufferedReader(new InputStreamReader(new FileInputStream(patternsFilepath)))
      reader.eachLine {line ->
        if (!line.startsWith('#') && line.trim() != "") {additionalPatterns.add(line)}
      }
    }
    return additionalPatterns
  }
}


