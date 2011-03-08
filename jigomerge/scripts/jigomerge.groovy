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

  /** parameter dryRun - should the script commit the changes or not             */
  def boolean dryRun = true
  def List<String> noMergeCommentPatterns = []
  def boolean mergeOneByOne = false
  def boolean mergeEager = false
  def boolean verbose = false
  def boolean authentication = false
  def String username = null
  def String password = null

  public SvnMergeTool(boolean dryRun, List<String> noMergeCommentPatterns, boolean mergeOneByOne, boolean mergeEager, boolean verbose, String username, String password) {
    this.dryRun = dryRun
    if (noMergeCommentPatterns.isEmpty()) {
      this.noMergeCommentPatterns = DEFAULT_NO_MERGE_COMMENT_PATTERNS
    } else {
      this.noMergeCommentPatterns = noMergeCommentPatterns
    }
    this.mergeOneByOne = mergeOneByOne
    this.mergeEager = mergeEager
    this.verbose = verbose

    // authentication
    this.username = username
    this.password = password
    this.authentication = (username != null)
  }

  /**
   * launch the merge <br>
   * return boolean - true if everything went fine or false if manual merge to be done
   */
  public def boolean launchSvnMerge(String mergeUrl, String validationScript, String workingDirectory) {
    def boolean globalStatus = true

    // reset workspace
    resetWorkspace(workingDirectory)

    // retrieve all available revisions
    def revisions = retrieveAvailableRevisionsMergeInfo(mergeUrl, workingDirectory)

    def nbRevisions = revisions.size()

    println 'Merging ' + nbRevisions + ' revisions ...'

    def validRevisions = []

    for (int i = 0; i < nbRevisions; i++) {
      def revision = revisions[i]
      println ' Handling revision ' + revision + ' ...'
      def comment = retrieveCommentFromRevisionWithLog(mergeUrl, revision, workingDirectory)

      // verify on comment that revision should not be blocked
      if (shouldRevisionBeBlocked(comment)) {
        println '  Blocking revision ' + revision + '\' ...'
        // block revision
        def status = svnMergeBlock(mergeUrl, revision, workingDirectory)
        if (!status) {
          throw new RuntimeException('Blocking revision ' + revision + ' failed !')
        }

        if (!dryRun) {
          println '  Committing block revision ' + revision + ' ...'
          status = svnCommitMergeBlock(revision, comment, workingDirectory)
          if (!status) {
            throw new RuntimeException('Commiting block revision ' + revision + ' failed !')
          }
        } else {
          println '  [DRY RUN SIMULATION] - Committing block revision ' + revision + ' ...'
        }

        svnUpdate(workingDirectory)
        println '  Revision ' + revision + ' blocked'
      } else {
        // verify merge has no conflict with the current revision and previous ones
        def subRevisions = []
        subRevisions.addAll(validRevisions)
        subRevisions.add(revision)

        svnMergeMerge(mergeUrl, subRevisions, workingDirectory)

        def hasConflicts = hasWorkspaceConflicts(workingDirectory)

        // in any case, revert changes, cleanup workspace
        resetWorkspace(workingDirectory)

        if (hasConflicts) {
          // globalStatus is set to false, this means manual merge needs to be done
          globalStatus = false

          if (!mergeEager) {
            println '  Revision ' + revision + ' has conflict, merging only previous revisions ...'
            // revision has conflict, stop merge
            // create file with commands to ease conflict resolution
            break;
          } else {
            println '  Revision ' + revision + ' has conflict, continue merging ...'
          }
        } else {
          if (mergeOneByOne) {
            svnMergeAndCommit(mergeUrl, revision, validationScript, workingDirectory)
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

        svnMergeAndCommit(mergeUrl, validRevisions, validationScript, workingDirectory)
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

  protected def void svnMergeAndCommit(String mergeUrl, List<String> revisionsList, String validationScript, String workingDirectory) {

    def revisionsListLabel = buildRevisionsList(revisionsList)
    def status = svnMergeMerge(mergeUrl, revisionsList)
    if (!status) {
      throw new RuntimeException('Merging valid revisions (' + revisionsListLabel + ') failed !')
    }

    if(validationScript != null){
       status = executeCommandWithStatus(validationScript);
       if(!status){
	throw new RuntimeException('Validation using ' + validationScript + ' failed !')
       }
    }

    if (!dryRun) {
      println 'Committing merged revisions (' + revisionsListLabel + ') ...'
      def commentFile = new File('jigomerge-comments.txt')
      commentFile << 'Merged revisions : ' + revisionsListLabel + '\n'
      for (String revision in revisionsList) {
        def revisionComment = retrieveCommentFromRevisionWithLog(mergeUrl, revision, workingDirectory)
        commentFile << 'Revision  #' + revision + '\n'
        commentFile << '----------------------\n'
        commentFile << revisionComment + '\n'
        commentFile << '----------------------\n'
        commentFile << '\n'
      }
      status = svnCommitMergeMerge(commentFile, workingDirectory)
      commentFile.delete()
      if (!status) {
        throw new RuntimeException('Committing valid revisions merge (' + revisionsListLabel + ') failed !')
      }
    } else {
      println '[DRY RUN SIMULATION] - Committing merged revisions (' + revisionsListLabel + ') ...'
    }
  }

  protected def void resetWorkspace(String workingDirectory) {
    def status = true
    status &= svnRevertAllRecursively(workingDirectory)
    status &= svnUpdate(workingDirectory)

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

  protected def List<File> listUnversionnedFiles(String workingDirectory) {
    def process = svnStatus('--xml ' + workingDirectory)
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

  protected def boolean hasWorkspaceConflicts(String workingDirectory) {
    def process = svnStatus('--xml ' + workingDirectory)
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

  protected def String[] retrieveAvailableRevisionsMergeInfo(String mergeUrl, String workingDirectory) {
    def process = executeSvnCommand('mergeinfo --show-revs eligible ' + mergeUrl + ' ' + workingDirectory)
    def log = process.in.text

    def revisions = []
    log.eachLine() {it ->
      revisions.add(it.replace('r', ''));
    }

    return revisions
  }


  protected def String retrieveCommentFromRevisionWithLog(String mergeUrl, String revision, String workingDirectory) {
    def process = executeSvnCommand('log --xml -r ' + revision + ' ' + mergeUrl + ' ' + workingDirectory)
    def logXml = process.in.text

    def log = new XmlSlurper().parseText(logXml)
    def comment = log.logentry.msg.text()

    return comment
  }

  protected def boolean svnMergeBlock(String mergeUrl, String revision, String workingDirectory) {
    return executeSvnCommandWithStatus('merge --accept postpone --record-only -c ' + revision + ' ' + mergeUrl + ' ' + workingDirectory)
  }

  protected def boolean svnMergeMerge(String mergeUrl, List<String> revisions, String workingDirectory) {
    boolean status = true
    for (String revision: revisions) {
      String command = '--accept postpone merge -c ' + revision + ' ' + mergeUrl + ' ' + workingDirectory
      status = executeSvnCommandWithStatus(command)
      if (!status) {
        println ' Executing ' + command + ' failed !'
        return false
      }
    }
    return true
  }

  protected def boolean svnUpdate(String workingDirectory) {
    return executeSvnCommandWithStatus('update ' + workingDirectory)
  }

  protected def svnStatus(String workingDirectory) {
    return svnStatus(' ' + workingDirectory)
  }

  protected def svnStatus(String options, String workingDirectory) {
    return executeCommand('svn status ' + options + ' ' + workingDirectory)
  }

  protected def boolean svnCommitMerge(String message, String workingDirectory) {
    return svnCommit('-m "' + message + '"', workingDirectory)
  }

  protected def boolean svnCommitMergeBlock(String revision, String comment, String workingDirectory) {
    def commentFile = new File('jigomerge-comments.txt')
    commentFile << 'Block revision #' + revision + '\n'
    commentFile << 'Initial message was : ' + comment
    def status = svnCommit('-F ' + commentFile.path, workingDirectory)
    commentFile.delete()
    return status
  }

  protected def boolean svnCommitMergeMerge(File commentFile, String workingDirectory) {
    return svnCommit('-F ' + commentFile.path, workingDirectory)
  }

  protected def boolean svnCommit(String options, String workingDirectory) {
    return executeSvnCommandWithStatus('commit ' + options + ' ' + workingDirectory)
  }

  protected def boolean svnRevertAllRecursively(String workingDirectory) {
    return svnRevert('-R ', workingDirectory)
  }

  protected def boolean svnRevert(String options, String workingDirectory) {
    return executeSvnCommandWithStatus('revert ' + options + ' ' + workingDirectory)
  }

  protected def executeSvnCommand(String commandLabel) {
    String svnCommandLabel = 'svn --non-interactive '
    if(authentication){
      svnCommandLabel += ' --username ' + this.username + ' '
      if(this.password != null){
      svnCommandLabel += ' --password ' + this.password + ' '
      }
    }
    svnCommandLabel += commandLabel
    return executeCommand(svnCommandLabel)
  }

  protected def executeSvnCommandWithStatus(String commandLabel) {
    String svnCommandLabel = 'svn --non-interactive '
    if(authentication){
      svnCommandLabel += ' --username ' + this.username + ' '
      if(this.password != null){
      svnCommandLabel += ' --password ' + this.password + ' '
      }
    }
    svnCommandLabel += commandLabel
    return executeCommandWithStatus(svnCommandLabel)
  }

  protected def executeCommandWithStatus(String commandLabel) {
    def process = executeCommand(commandLabel, false)
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
    process.waitFor()
    if (verbose) {
      println '[DEBUG] exit value : ' + process.exitValue()
    }
 
    return (process.exitValue() == 0)
  }
  
  protected def executeCommand(String commandLabel){
    executeCommand(commandLabel, true)
  }

  protected def executeCommand(String commandLabel, boolean wait) {
    if (verbose) {
      println '[DEBUG] executing command \'' + commandLabel + '\''
    }
    def process = commandLabel.execute()
    
    if(wait){
      process.waitFor()
      if (verbose) {
        println '[DEBUG] exit value : ' + process.exitValue()
      }
    }
    return process
  }

  public static void main(String[] args) {
    def cli = new CliBuilder(usage: 'Launch merge')
    cli.h(longOpt: 'help', 'prints this message')
    cli.b(longOpt: 'bidirectional', 'bidirectional merge. Used to ignore reflected revisions')
    cli.S(longOpt: 'url', args: 1, 'source repository url to merge [REQUIRED]')
    cli.d(longOpt: 'dryRun', 'do not commit any modification')
    cli.s(longOpt: 'single', 'Merge one revision by one. One merge, one commit, one merge, one commit, ...')
    cli.a(longOpt: 'patterns', args: 1, 'patterns contained in comments of revisions not to be merged, separated by \',\'')
    cli.A(longOpt: 'patternsFile', args: 1, optionalArg: true, 'patterns file, default is \'patterns.txt\'')
    cli.e(longOpt: 'eager', 'eager merge: merge every revision that can be merged without conflict even if it follows a conflict')
    cli.u(longOpt: 'username', args: 1, 'username to use in svn commands')
    cli.p(longOpt: 'password', args: 1, 'password to use in svn commands')
    cli.V(longOpt: 'validation', args: 1, 'validation script');
    cli.v(longOpt: 'verbose', 'verbose mode')
    def options = cli.parse(args)

    if (!options || options.h || !options.S) {
      cli.usage()
      return
    }

    def boolean dryRun = options.d
    def boolean mergeOneByOne = options.s
    def boolean isMergeEager = options.e
    def boolean isVerbose = options.v
    def String mergeUrl = new String(options.S.value)

    // authentication
    String username = null
    String password = null
    if (options.u) {
      username = new String(options.u.value)
      if (options.p) {
        password = new String(options.p.value)
      }
    }

    def String validationScript = null
    if(options.V){
      validationScript = new String(options.V.value)
    }

    List<String> additionalPatterns = extractAdditionalPatterns(options)

    SvnMergeTool tool = new SvnMergeTool(dryRun, additionalPatterns, mergeOneByOne, isMergeEager, isVerbose, username, password)

    def boolean status = false

    status = tool.launchSvnMerge(mergeUrl, validationScript, '.')

    System.exit(status ? 0 : 1)
  }

  private static def extractAdditionalPatterns(options) {
    def additionalPatterns = []
    if (options.a) {
      String patternsList = new String(options.a.value)
      patternsList.split(',').each() {entry ->
        if (entry.trim() != "") {additionalPatterns.add(entry)}
      }
    } else if (options.A) {
      def patternsFilepath = 'patterns.txt'
      if (options.A.value != null) {
        patternsFilepath = options.A.value
      }
      def reader = new BufferedReader(new InputStreamReader(new FileInputStream(patternsFilepath)))
      reader.eachLine {line ->
        if (!line.startsWith('#') && line.trim() != "") {additionalPatterns.add(line)}
      }
    }
    return additionalPatterns
  }
}


