/** Copyright Vincent Beretti vberetti@gmail.com
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
**/   
public class SvnMergeTool {
	
	def static final List<String> DEFAULT_NO_MERGE_COMMENT_PATTERNS= ['maven-release-plugin', 'NOMERGE', 'nomerge', 'no-merge', 'NO-MERGE']

    /** parameter dryRun - should the script commit the changes or not */
    def boolean dryRun = true
    def List<String> noMergeCommentPatterns = []
    def boolean mergeOneByOne = false

    SvnMergeTool(boolean dryRun, List<String> noMergeCommentPatterns, boolean mergeOneByOne){
       this.dryRun = dryRun
      if (noMergeCommentPatterns.isEmpty()){
         this.noMergeCommentPatterns = DEFAULT_NO_MERGE_COMMENT_PATTERNS
      } else {
        this.noMergeCommentPatterns = noMergeCommentPatterns
      }
      this.mergeOneByOne = mergeOneByOne
    }
	
	/**
	 * launch the merge <br>
	 * return boolean - true if everything went fine or false if manual merge to be done
	 */
	public def boolean launch(){
		def boolean globalStatus = true
		
		// reset workspace
		resetWorkspace()
		
		// retrieve all available revisions
		def revisions = retrieveAvailableRevisions()
		
		def nbRevisions = revisions.size()
		
		println 'Merging '+ nbRevisions+ ' revisions ...' 
		
		def validRevisions = []
		
		for(int i=0; i< nbRevisions; i++){
			def revision = revisions[i]
			println ' Handling revision ' + revision + ' ...'
			def comment = retrieveCommentFromRevision(revision)
			
			// verify on comment that revision should not be blocked
			if(shouldRevisionBeBlocked(comment)){
				println '  Blocking revision ' + revision + ' with comment \''+comment+'\' ...'
				// block revision
				def status = svnMergeBlock(revision)
				if(!status) { throw new RuntimeException('Blocking revision ' + revision + ' failed !')
				}
				
				if(!dryRun){
					println '  Committing block revision ' + revision + ' ...'
					status = svnCommitMerge()
					if(!status) { throw new RuntimeException('Commiting block revision ' + revision + ' failed !')
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
				
				svnMergeMerge(revisionsList)
				
				def hasConflicts = hasWorkspaceConflicts()
				
				// in any case, revert changes, cleanup workspace
				resetWorkspace()

				if(hasConflicts){
					// globalStatus is set to false, this means manual merge needs to be done
					globalStatus = false
					println '  Revision ' + revision + ' has conflict, merging only previous revisions ...'
					// revision has conflict, stop merge
					break;
				}else{
					if(mergeOneByOne){
						mergeAndCommit(revision)
					}else{
						println '  Revision ' + revision + ' has no conflict'
						// add current revision to revisions to be merged
						validRevisions.add(revision);
					}
				}
			}
		}
		
		if(!mergeOneByOne){
			// reset workspace before real merge
			resetWorkspace()
			if(!validRevisions.isEmpty()){
			
				// merge all valid revisions
				
				def validRevisionsList = buildRevisionsList(validRevisions)
				
				mergeAndCommit(validRevisionsList)
			}else{
				println 'No valid revision to merge'
			}
		}
		
		svnUpdate()
		if(!globalStatus){
			println 'MANUAL MERGE NEEDS TO BE DONE !'
		}
		println 'Merge is finished !'
		
		return globalStatus
	}
	
	
	protected def void mergeAndCommit(String revisionsList){
			def status = svnMergeMerge(revisionsList)
			if(!status) { throw new RuntimeException('Merging valid revisions (' + revisionsList + ') failed !')
			}
			
			if(!dryRun){
				println 'Committing merged revisions (' + validRevisionsList + ') ...'
				status = svnCommitMerge()
				if(!status) { throw new RuntimeException('Committing valid revisions merge (' + revisionsList + ') failed !')
				}
			} else {
				println '[DRY RUN SIMULATION] - Committing merged revisions (' + revisionsList + ') ...'
			}
	}
	
	protected def void resetWorkspace(){
		def status = true
		status &= svnRevertAllRecursively()
		status &= svnUpdate()
		
		// delete unversionned files
		listUnversionnedFiles().each(){ file ->
			if(file.isDirectory()){
				file.deleteDir()
			}else{
				file.delete()
			}
		}
		
		if(!status) { throw new RuntimeException('Failed to reset workspace !')
		}
	}
	
	protected def List<File> listUnversionnedFiles(){
		def process = svnStatus()
		def statusLog = process.in.text
		def files = []
		statusLog.eachLine(){ line ->
			if(line.contains('?    ')){
				files.add(new File(line.substring(1,line.length()).trim()))
			}
		}
		
		return files
	}
	
	protected def boolean hasWorkspaceConflicts(){
		def process = svnStatus()
		def statusLog = process.in.text
		
		return statusLog.contains('C    ') || statusLog.contains('   C ')
	}
	
	protected def String buildRevisionsList(List<String> revisions){
		
		def revisionsList = ''
		
		for(String revision in revisions){
			revisionsList += revision + ','
		}
		
		revisionsList=revisionsList.substring(0,revisionsList.length() - 1 )
		return revisionsList
	}
	
	protected def boolean shouldRevisionBeBlocked(String comment){
		def boolean block = false
		for(pattern in noMergeCommentPatterns){
			block = comment.contains(pattern)
			if(block){
				break;
			}
		}
		
		return block
	}
	
	protected def String[] retrieveAvailableRevisions(){
		def process = executeCommand('svnmerge avail')
		def log = process.in.text
		
		def tmpRevisions = []
		if(log.trim() != ''){
			tmpRevisions = log.trim().split(',')
		}
		
		def revisions = []
		for(tmpRevision in tmpRevisions){
			if(tmpRevision.contains('-')){
				def startEnd = tmpRevision.split('-')
				def start = new Integer(startEnd[0])
				def end = new Integer(startEnd[1])
				for(revision in start..end){
					revisions.add(revision)
				}
			}else{
				revisions.add(tmpRevision)
			}
		}
		
		return revisions
	}
	
	protected def String retrieveCommentFromRevision(String revision){
		def process = executeCommand('svnmerge avail -l -r ' + revision)
		def log = process.in.text
		
		def comment = log.trim()
		return comment
	}

    protected def String retrieveRepositoryRoot(){
        def process = executeCommand('svn info .')
		def log = process.in.text

        def matcher = (log =~ 'Repository Root:(.*)')

        return matcher[0][1].trim()
    }

    public def boolean checkMergeIsInitialized(String mergeUrl){
       def String repositoryRoot = retrieveRepositoryRoot()

    // validate merge url
    if(!mergeUrl.startsWith(repositoryRoot)){
       throw new RuntimeException('Merge url must reference an url in the same repository than the working copy (\''+repositoryRoot+'\')')
    }

        def mergeUrlSuffix = mergeUrl.replace(repositoryRoot,'')
        def process = executeCommand('svn propget svnmerge-integrated')
		def log = process.in.text

        boolean initialized = false
        log.eachLine(){ line ->
          initialized |= line.startsWith(mergeUrlSuffix)
        }

        return initialized
    }

    public def initMerge(String mergeUrl){

		println 'Initializing merge to \''+mergeUrl+'\' ...' 

		// reset workspace
		resetWorkspace()

        def status = executeCommandWithStatus('svnmerge init ' + mergeUrl)    
        if(!status) { throw new RuntimeException('Merge initialization to \'' + mergeUrl + '\' failed !')
				}

        if(!dryRun){
					println '  Committing merge initialization ...'
					status = svnCommitMerge()
					if(!status) { throw new RuntimeException('Commiting merge initialization to \''+mergeUrl+'\' failed !')
					}
				} else {
					println '  [DRY RUN SIMULATION] - Committing merge initialization ...'
				}

		println 'Merge initialization is finished !'
        return true
    }
	
	protected def boolean svnMergeBlock(String revision){
		return executeCommandWithStatus('svnmerge block -r ' + revision)
	}
	
	protected def boolean svnMergeMerge(String revisions){
		return executeCommandWithStatus('svnmerge merge -r ' + revisions)
	}
	
	protected def boolean svnUpdate(){
		return executeCommandWithStatus('svn update')
	}
	
	protected def svnStatus(){
		return executeCommand('svn status')
	}
	
	protected def boolean svnCommitMerge(){
		return svnCommit('-F svnmerge-commit-message.txt')
	}
	
	protected def boolean svnCommit(String options){
		return executeCommandWithStatus('svn commit ' + options)
	}
	
	protected def boolean svnRevertAllRecursively(){
		return svnRevert('-R .')
	}
	
	protected def boolean svnRevert(String options){
		return executeCommandWithStatus('svn revert ' + options)
	}
	
	protected def executeCommandWithStatus(String commandLabel){
		def process = executeCommand(commandLabel)
		return (process.exitValue() == 0)
	}
	
	protected def executeCommand(String commandLabel){
		def process = commandLabel.execute()
		process.waitFor()
		return process
	}

  public static void main(String[] args) {
    def cli = new CliBuilder(usage: 'Launch merge')
    cli.h(longOpt: 'help', 'prints this message')
    cli.i(longOpt: 'init', 'only init the merge, then stop')
    cli.n(longOpt: 'no-init', 'do not init the merge. Fails if no initialization has been done for the specified url')
    cli.b(longOpt: 'bidirectional', 'bidirectional merge. Used to ignore reflected revisions')
    cli.u(longOpt: 'url', args: 1, 'repository url to merge [REQUIRED]')
    cli.d(longOpt: 'dryRun', 'do not commit any modification')
    cli.s(longOpt: 'single', 'Merge one revision by one. One merge, one commit, one merge, one commit, ...')
    cli.p(longOpt: 'patterns', args: 1, 'patterns contained in comments of revisions not to be merged, separated by \',\'')
    cli.P(longOpt: 'patternsFile', args: 1, optionalArg: true, 'patterns file, default is \'patterns.txt\'')
    def options = cli.parse(args)

    if (!options || options.h || !options.u) {
      cli.usage()
      return
    }

    def boolean dryRun = options.d
    def boolean mergeOneByOne = options.s
    def boolean blockInitialization = options.n
    def String mergeUrl = new String(options.u.value)

    List<String> additionalPatterns = extractAdditionalPatterns(options)

    SvnMergeTool tool = new SvnMergeTool(dryRun, additionalPatterns, mergeOneByOne)

    // check svnmerge init
    def boolean isMergeInitialized = tool.checkMergeIsInitialized(mergeUrl)

    // perform svnmerge init
    if(!isMergeInitialized){
      if(blockInitialization){
        println 'Merge is not initialized and option -n is active -> Exiting ...'
        System.exit(1)
      }
      println 'Merge is not yet initialized to \'' + mergeUrl + '\''
      tool.initMerge(mergeUrl)
    }else{
      println 'Merge is already initialized'
    }

    def boolean status = false
    status = tool.launch()

    System.exit(status ? 0 : 1)
  }

  private static def extractAdditionalPatterns(options) {
    def additionalPatterns = []
    if (options.p) {
      String patternsList = new String(options.p.value)
      patternsList.split(',').each() { entry ->
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


