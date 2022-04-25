class Selenium {
	
	SeleniumTestsParallel(script) {
		super(script);
	}
	
	def run() {
		def errorsFolder = "Errors"
		//This is the number of tests that should be put in a test batch
		//If a scenario folder has more tests than this then it will be split in multiple batches and the result merged.
		def batchSize = getBatchSize();
		
		def branch = getBranch()
		def tagName = getTagName()
		def dbType = getDbType()
		
		def teexmaImageName = DockerUtils.getTeexmaImageName(branch.toLowerCase().replace("branches/", ""), tagName);
		
		Map<String, String> filesMap = new HashMap<String, String>()
		
		def hasCreatedSelTagImage = false;
		def hasCreatedTeexmaTagImage = false;
		if (tagName != null && ! tagName.isEmpty()) {
			def version = branch.tokenize("/").last()
			DockerUtils.createTeexmaImage(_script, WorkflowUtils.getIISNodeLabel(_script), branch, version, tagName);
			hasCreatedTeexmaTagImage = true
			if (doScriptsTagExists(tagName)) {
				SeleniumUtils.createContainer(_script, "Tags/${tagName}");
				hasCreatedSelTagImage = true;
			}
		}
		
		def scenariosFolders = getScenariosFolders().tokenize(",");
		
		def databases = []
		def cleanedScenariosFolders = []
		def batchCounts = []
		for (int i = 0; i < scenariosFolders.size(); i++) {
			def splitScenariosFolder = scenariosFolders.get(i).tokenize("|");
			def database
			def cleanedScenariosFolder
			if (splitScenariosFolder.size() == 2) {
				cleanedScenariosFolder = splitScenariosFolder[0].trim().replaceAll("(^\\[\\t\\xA0\\u1680\\u180e\\u2000-\\u200a\\u202f\\u205f\\u3000]*)|(\\h*\$)","")
				database = splitScenariosFolder[1].trim().replaceAll("(^\\[\\t\\xA0\\u1680\\u180e\\u2000-\\u200a\\u202f\\u205f\\u3000]*)|(\\h*\$)","")
			} else if (splitScenariosFolder.size() == 1) {
				cleanedScenariosFolder = splitScenariosFolder[0].trim().replaceAll("(^\\[\\t\\xA0\\u1680\\u180e\\u2000-\\u200a\\u202f\\u205f\\u3000]*)|(\\h*\$)","")
				database = splitScenariosFolder[0].trim().replaceAll("(^\\[\\t\\xA0\\u1680\\u180e\\u2000-\\u200a\\u202f\\u205f\\u3000]*)|(\\h*\$)","")
			} else {
				_script.echo "Failed to parse ${scenariosFolders.get(i)}"
			}
			databases[i] = database
			cleanedScenariosFolders[i] = cleanedScenariosFolder
			def filesCount = SeleniumUtils.getTestsCount(_script, branch, cleanedScenariosFolder)
			//Rounding up filesCount / batchSize to count unfilled batches
			batchCounts[i] = ((filesCount + batchSize - 1) / batchSize) as Integer
		}
		
		_script.stage("DB restore") {
			if (dbType == "postgresql") {
				_script.node(WorkflowUtils.getPostgreSqlNodeLabel()) {
					def workspacePath = WorkflowUtils.getWorkspacePath(_script);
					//TODO Kenza lancer un conteneur postgresql dans le cas postgresql
					//TODO Kenza faire la restauration sur le conteneur postgre du backup postgre
					//nom du schema -> getTeexmaName(scenario + "${j}"), nom du backup -> database
					_script.bat "docker run -d --name postgresql -p 5432:5432 -e POSTGRES_PASSWORD=kamisama123 -v ${workspacePath}/PGSDocker/script/:/docker-entrypoint-initdb.d/ -v ${workspacePath}/sharedscripts:/home/sharedscripts postgres"
								 
				}
			}
			_script.node (WorkflowUtils.getIISNodeLabel(_script)) {
				for (int i = 0; i < databases.size(); i++) {
					def database = databases.get(i);
					def scenario = cleanedScenariosFolders.get(i); 
					def splitCount = batchCounts.get(i)
					for (int j = 0; j < splitCount; j++) {
						try {
							def teexmaName = getTeexmaName(scenario + "${j}")
							if (dbType == "postgresql") {
								restorePostGreSQLDatabase(teexmaName, database, i == 0 && j == 0);
							} else {
								restoreSqlServerDatabase(teexmaName, database, i == 0 && j == 0);
							}
						} catch (Exception e) {
							_script.echo "Failed to restore database ${database} for scenario ${scenario}"
							_script.echo e.getMessage()
						}
					}
				}
			}
			
		}
		
		def fullTeexmaVersion = getRevision(scenariosFolders.get(0), teexmaImageName);
		def testTasks = [:]
		for (int i = 0; i < cleanedScenariosFolders.size(); i++) {
			String scenariosFolder = cleanedScenariosFolders.get(i);
			String database = databases.get(i);
			def batchCount = batchCounts.get(i);
			for (int j = 0; j < batchCount; j++) {
				def batchIndex = j;
				testTasks[scenariosFolder + "${batchIndex}"] = {
					_script.stage(scenariosFolder + "${batchIndex}") {
						try {
							  _script.lock('selenium tests throttle') {
								  _script.echo "sleeping for parallel execution throttling"
								  sleep 20
							  }
							runTestFolder(scenariosFolder, database, batchIndex, batchSize, errorsFolder, teexmaImageName, filesMap, hasCreatedSelTagImage);
						} catch (Exception e) {
							_script.currentBuild.result = 'FAILED'
							String message = StackTraceUtils.sanitize(e).getMessage();
							def stackTrace = StackTraceUtils.sanitize(e).getStackTrace().join("\r\n");
							def exceptMsg = message + "\r\n\r\n" + stackTrace + "\r\n\r\n";
							filesMap.put((String) ".\\${errorsFolder}\\${scenariosFolder}\\${scenariosFolder}-Jenkins.txt", exceptMsg)
							filesMap[".\\${errorsFolder}\\errorsCount.txt"] = filesMap[".\\${errorsFolder}\\errorsCount.txt"] + "${scenariosFolder} : Error during execution\r\n\r\n"
							_script.echo message
							_script.echo stackTrace
						}
					}
				}
			}
		}
		
		_script.node (WorkflowUtils.getIISNodeLabel(_script)) {
			_script.stage("Tests") {
				_script.parallel testTasks
			}
			archiveResults(cleanedScenariosFolders, batchCounts, errorsFolder, filesMap, fullTeexmaVersion)
		}
		
		//TODO Kenza supprimer le conteneur PostgreSQL et son dossier de données dans le cas PostgreSQL
		
		if (hasCreatedTeexmaTagImage) {
			_script.node (WorkflowUtils.getIISNodeLabel(_script)) {
				_script.bat "docker rmi ${teexmaImageName}" 
			}
		}
	}
	/**
	 * Runs test from a folder starting a TEEXMA container and running tests against it
	 * this will execute a subset of the tests contained in the folder from test number batchIndex * batchSize to (batchIndex + 1) * batchSize
	 * @param scenariosFolder the tests scenario folder that should be executed
	 * @param database the name of the database file to use without the extension
	 * @param batchIndex the index of the batch of tests to execute
	 * @param batchSize the size of the batch of tests to execute
	 * @param errorsFolder the name of the error folder
	 * @param teexmaImageName the name of the  TEEXMA image that will be used to create the TEEXMA container
	 * @param filesMap the map object that will store the log files format is [filePath, fileContent]
	 * @param useSelTagImage true if a specific selenium image should be used
	 * @param retryCount the number of failed attempt already made to start these tests
	 * @return nothing
	 */
	def runTestFolder(String scenariosFolder, String database, int batchIndex, int batchSize, String errorsFolder, String teexmaImageName, filesMap, boolean useSelTagImage, int retryCount = 0) {
		def txUtils = new TeexmaUtils()
		def branch = getBranch()
		def pathDB = getPathDB();
		def teexmaName = getTeexmaName(scenariosFolder + "${batchIndex}");
		def sgbdUrl = _script.env.sgbdUrl.replace("/", "\\");
		def isLoginFailure = false;
		
		_script.node (WorkflowUtils.getIISNodeLabel(_script)) {
			def now = new Date()
			_script.echo "Starting tests for ${scenariosFolder} | ${database} at : " + now.format("HH:mm dd-MM-yyyy", TimeZone.getTimeZone('Europe/Paris'))
			def workspacePath = WorkflowUtils.getWorkspacePath(_script)
			def containerName = getContainerName()
			DockerUtils.cleanContainerByName(_script, containerName);
			if (retryCount > 0) {
				def isNeo = WorkflowUtils.isNeo(branch);
				//TODO Kenza refaire la restauration de la base PostgreSQL ici dans le cas postgreSQL
				restoreSqlServerDatabase(teexmaName, database, true);
			}
			
			def txFilesPath = "${workspacePath}\\CustomFiles${teexmaName}";
			WorkflowUtils.deleteDirIfExists(_script, txFilesPath);
			
			//Getting documents and customer resources
			_script.bat "robocopy \"${pathDB}\\CRAndDoc\\Default\\Customer Resources\" \"${txFilesPath}\\Customer Resources\" /NFL /NDL /NJH /NJS /np /E \r\nif errorlevel 1 (exit /b 0)"
			if (_script.fileExists("${pathDB}\\CRAndDoc\\Default\\Web")) {
				_script.bat "robocopy \"${pathDB}\\CRAndDoc\\Default\\Web\" \"${txFilesPath}\\Web\" /NFL /NDL /NJH /NJS /np /E \r\nif errorlevel 1 (exit /b 0)"
			}
			
			WorkflowUtils.deleteDirIfExists(_script, "${txFilesPath}\\Documents")
			_script.bat "robocopy \"${pathDB}\\CRAndDoc\\Default\\Documents\" \"${txFilesPath}\\Documents\" /NFL /NDL /NJH /NJS /np /E \r\nif errorlevel 1 (exit /b 0)"
					
			if (_script.fileExists("${pathDB}\\CRAndDoc\\${database}\\Customer Resources")) {
				_script.bat "robocopy \"${pathDB}\\CRAndDoc\\${database}\\Customer Resources\" \"${txFilesPath}\\Customer Resources\" /NFL /NDL /NJH /NJS /np /is /E \r\nif errorlevel 1 (exit /b 0)"
			}
			
			if (_script.fileExists("${pathDB}\\CRAndDoc\\${database}\\Web")) {
				_script.bat "robocopy \"${pathDB}\\CRAndDoc\\${database}\\Web\" \"${txFilesPath}\\Web\" /NFL /NDL /NJH /NJS /np /is /E \r\nif errorlevel 1 (exit /b 0)"
			}
			
			if (_script.fileExists("${pathDB}\\CRAndDoc\\${database}\\Documents")) {
				_script.bat "robocopy \"${pathDB}\\CRAndDoc\\${database}\\Documents\" \"${txFilesPath}\\Documents\" /NFL /NDL /NJH /NJS /np /is /E \r\nif errorlevel 1 (exit /b 0)"
			}
			
			def portNumber = 81 + _script.env.EXECUTOR_NUMBER.toInteger();
			
			//TODO Kenza remplacer le port, le driverId et le sgbdUrl dans le cas PostgreSQL 
			def dbPort = "1433";
			def dbDriverId="MSSQL"; //PG
			
			_script.bat "docker run -d -p ${portNumber}:443 -e \"dbName=${teexmaName}\" -e \"dbPath=${sgbdUrl}\" -e \"dbPort=${dbPort}\" -e \"dbDriverId=${dbDriverId}\" --name ${containerName} -v ${txFilesPath}:C:\\CustomFiles ${teexmaImageName}"
			try {
				def siteUrl = txUtils.getDockerSiteUrl(_script, teexmaName);
				def passedTxUpgrade = false
				_script.echo "siteUrl : ${siteUrl}"
				_script.node ("NGinx") {
					ReverseProxyUtils.defineNewAlias(_script, portNumber, siteUrl);
				}
				def testTagName = null;
				if (useSelTagImage) {
					testTagName = tagName;
				}
				//This sleep is important without it TxUpgrade failures are systematic
				//Teexma needs some time before receiving the first request
				_script.sleep(120)
				try {
					_script.timeout(10) {
						_script.bat "docker exec ${containerName} powershell Start-Process C:\\TEEXMA\\Core\\TxUpgrade.exe -NoNewWindow -Wait -ArgumentList \"-bAutomatic\""
						passedTxUpgrade = true
					}
				} catch (e) {
					//Archiving the TEEXMA Logs to get a trace of the errors
					_script.echo "TxUpgrade timeout error archiving TxLogs"
					isLoginFailure = true;
				}
				
				if (passedTxUpgrade) {
					def maxCurlAttempts = 3
					def teexmaCurlSuccess = false
					for (int i = 0; i < maxCurlAttempts && !teexmaCurlSuccess; i++) {
						try {
							def lifePageUrl
							if (branch == "Trunk") {
								lifePageUrl = "https://${siteUrl}/code/common/isSessionActive.aspx"
							} else {
								lifePageUrl = "https://${siteUrl}/code/asp/isSessionActive.aspx"
							}
							_script.echo "Checking TEEXMA life page"
							//It is imperative to keep the line "@ without any space before it or the powershell script refuses to work
							_script.powershell """
								add-type @"
								    using System.Net;
								    using System.Security.Cryptography.X509Certificates;
								    public class TrustAllCertsPolicy : ICertificatePolicy {
								        public bool CheckValidationResult(
								            ServicePoint srvPoint, X509Certificate certificate,
								            WebRequest request, int certificateProblem) {
								            return true;
								        }
								    }
"@
								[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllCertsPolicy
								try
								{
									
									\$Response = Invoke-WebRequest -Uri "${lifePageUrl}" -ErrorAction Stop
									# This will only execute if the Invoke-WebRequest is successful.
									\$StatusCode = \$Response.StatusCode
								}
								catch
								{
									\$StatusCode = \$_.Exception.Response.StatusCode.value__
								}
								\$Response.Content
								\$StatusCode
								if (\$StatusCode -ne "200") { throw "bad status " + \$StatusCode }
								if (-not (\$Response.Content -Match "IIS : ok")) { throw "bad response " + \$Response.Content }
							"""
							teexmaCurlSuccess = true
						} catch (Exception e) {
							_script.sleep(60)
							_script.echo "Invoke-WebRequest isSessionActive.aspx failure attempt ${i + 1} out of ${maxCurlAttempts}"
						}
					}
					if (teexmaCurlSuccess) {
						_script.echo "starting tests"
						seleniumTests(scenariosFolder, batchIndex, batchSize, errorsFolder, siteUrl, testTagName, filesMap);
						isLoginFailure = filesMap[".\\${errorsFolder}\\${scenariosFolder}\\${scenariosFolder}-Login.txt"] != null
						filesMap[".\\${errorsFolder}\\${scenariosFolder}\\${scenariosFolder}-Login.txt"] = null;
					} else {
						isLoginFailure = true;
					}
				}
				
				WorkflowUtils.deleteDirIfExists(_script, "${workspacePath}\\${scenariosFolder}TxLogs");
				_script.bat "mkdir ${workspacePath}\\${scenariosFolder}TxLogs"
				_script.bat "docker cp ${containerName}:/TEEXMA/Files .\\${scenariosFolder}TxLogs\\"
				_script.bat "\"C:\\Program Files\\7-Zip\\7z.exe\" a -tzip ${scenariosFolder}TxLogs${retryCount}.zip \"${workspacePath}\\${scenariosFolder}TxLogs\""
				_script.archiveArtifacts "${scenariosFolder}TxLogs${retryCount}.zip"
				_script.bat "del .\\${scenariosFolder}TxLogs${retryCount}.zip"						
			} finally {
				try {
					_script.bat "docker rm -fv ${containerName}"
				} catch(Exception e) {
					_script.echo "failed to delete container ${containerName} : ${e.message}"
				}
			}
			WorkflowUtils.deleteDirIfExists(_script, txFilesPath);
			try {
				_script.powershell "invoke-sqlcmd -ServerInstance \"${sgbdUrl}\" -Query \"Drop database ${teexmaName};\""
			} catch(Exception e) {
				_script.echo "failed to delete database ${teexmaName} : ${e.message}"
			}
		}
		if (isLoginFailure && retryCount < 2) {
			//Login seems impossible this might be caused by an installation problem
			_script.echo "isLoginFailure retrying count : ${retryCount}"
			retryCount++;
			runTestFolder(scenariosFolder, database, batchIndex, batchSize, errorsFolder, teexmaImageName, filesMap, useSelTagImage, retryCount);
		}
	}
	
	/**
	 * Starts Selenium tests 
	 * @param scenariosFolder the name of the scenario folder 
	 * @param batchIndex the index of the batch of tests to execute
	 * @param batchSize the size of the batch of tests to execute
	 * @param errorsFolder the name of the error folder
	 * @param siteUrl the url of the TEEXMA application to run the tests against
	 * @param testTagName the name of the tag of the tests if there is a specific one null otherwise
	 * @param filesMap the map object that will store the log files format is [filePath, fileContent]
	 * @return nothing
	 */
	def seleniumTests(String scenariosFolder, batchIndex, batchSize, String errorsFolder, String siteUrl, String testTagName, filesMap) {
		def isFirstBatch = false
		def isLastBatch = false;
		def isAdminUser = isAdminUser();
		def releaseNumber = "00000"
		def branch = getBranch();
		
		_script.echo "Execute Tests"
		def selUtils = new SeleniumUtils();
		def language = _script.env.language;
		def browser = _script.env.browser;
		def logsOnError = _script.env.logsOnError;
		def screenshotOnError = _script.env.screenshotOnError;
		def spreadSheetId = _script.env.spreadSheetId;
		def driveFolderId = _script.env.driveFolderId;
		def scenariosPath = "/src/SeleniumXml/StandardTest/" + scenariosFolder
		def testBranch = branch;
		if (testTagName != null) testBranch = "Tags/${testTagName}"
		
		def selConfig = new SeleniumConfig(testBranch, ("http://${siteUrl}").toString(), language, scenariosPath, browser, logsOnError, screenshotOnError, spreadSheetId, isFirstBatch, driveFolderId, releaseNumber, false, isAdminUser)
		selUtils.seleniumTests(_script, selConfig, scenariosFolder, errorsFolder, filesMap, batchIndex, batchSize, isFirstBatch, isLastBatch, isAdminUser)
	}
	
	/**
	 * archives results contained in a fileMap
	 * @param scenariosFolders the list of scenario folders
	 * @param batchCounts the list of batch counts
	 * @param errorsFolder the errors folder name
	 * @param filesMap the logs files map
	 * @param fullTeexmaVersion the full TEEXMA version Major.Minor.Release.Revision should contain the tag name if this is one 
	 * @return nothing
	 */
	def archiveResults(scenariosFolders, batchCounts, errorsFolder, filesMap, fullTeexmaVersion) {
		_script.node (WorkflowUtils.getIISNodeLabel(_script)) {
			String errorCountString = ""
			int batchTotalCount = 0
			int batchErrorCount = 0
			WorkflowUtils.deleteDirIfExists(_script, errorsFolder)
			def workspacePath = WorkflowUtils.getWorkspacePath(_script)
			
			for (int i = 0; i < scenariosFolders.size(); i++) {
				int errorCount = 0;
				String scenariosFolder = scenariosFolders.get(i);
				_script.echo "extracting result for ${scenariosFolder}"
				String failed = filesMap[".\\${errorsFolder}\\${scenariosFolder}\\${scenariosFolder}-Sel.txt"];
				String parse = filesMap[".\\${errorsFolder}\\${scenariosFolder}\\${scenariosFolder}-Parse.txt"];
				String success = filesMap[".\\${errorsFolder}\\${scenariosFolder}\\${scenariosFolder}-Success.txt"];
				errorCount = errorCount + getFileTestCount(failed);
				errorCount = errorCount + getFileTestCount(parse);
				batchErrorCount = batchErrorCount + errorCount
				int totalCount = errorCount + getFileTestCount(success);
				batchTotalCount = batchTotalCount + totalCount
				errorCountString = errorCountString + "${scenariosFolder} : " + errorCount + " / " + totalCount + "\r\n\r\n"
			}
	
			errorCountString = errorCountString + "Total : " + batchErrorCount + " / " + batchTotalCount + "\r\n\r\n"
		
			Object[] keys = filesMap.keySet().toArray();
		
			for (int j = 0; j < keys.length; j++)
			{
				String path = (String) keys[j];
				_script.bat "mkdir ${path}"
				_script.bat "rd /s /q ${path}"
				def fileText = filesMap.get(path);
				if (fileText != null) {
					_script.writeFile file: path, text: fileText
				}
			}
			
			_script.bat "if not exist \".\\${errorsFolder}\" mkdir .\\${errorsFolder}"
			_script.writeFile file: ".\\${errorsFolder}\\errorsCount.txt", text: errorCountString
			
			WorkflowUtils.deleteFileIfExists(_script, "Errors.zip")
			_script.bat "\"C:\\Program Files\\7-Zip\\7z.exe\" a -tzip Errors.zip \"${workspacePath}\\${errorsFolder}\""
			_script.archiveArtifacts "Errors.zip"
			
			WorkflowUtils.deleteDirIfExists(_script, "logsXml");
			_script.bat "if not exist \".\\logsXml\" mkdir .\\logsXml"
			
			for (int i = 0; i < scenariosFolders.size(); i++) {
				String scenariosFolder = scenariosFolders.get(i);
				def batchCount = batchCounts.get(i);
				def totalTime = 0;
				_script.bat "if not exist \".\\logsXml\\${scenariosFolder}\" mkdir .\\logsXml\\${scenariosFolder}"
				//Changing working directory to the scenariosFolder directory
				_script.dir("logsXml/${scenariosFolder}") {
					_script.echo "unstashing : ${scenariosFolder}"
					for(int batchIndex = 0; batchIndex < batchCount; batchIndex++) {
						try {
							_script.unstash scenariosFolder + "${batchIndex}"
							def batchTimeXml = _script.readFile "TotalTime.xml"
							def batchTime = (batchTimeXml.replaceAll("[^0-9]", "")) as Integer
							totalTime = totalTime + batchTime
							if (batchIndex == (batchCount - 1)) {
								_script.writeFile file: "TotalTime.xml", text: "<TotalTime>${totalTime}</TotalTime>"
							}
						} catch (Exception e) {
							_script.echo "failed to unstash ${scenariosFolder} : ${e.message}"
						}
					}
				}
			}
			if (_script.env.spreadSheetId != null && _script.env.spreadSheetId != "") {
				_script.bat "del .\\GSheetWriter.7z"
				def gSheetWriterArtifact = new Artifact("GSheetWriter", "com.bassetti.teexma", "LATEST", "7z")
				WorkflowUtils.getArtifact(_script, gSheetWriterArtifact, ".\\")
				WorkflowUtils.deleteDirIfExists(_script, "${workspacePath}\\GSheetWriter")
				_script.bat "mkdir ${workspacePath}\\GSheetWriter"
				_script.bat "\"C:\\Program Files\\7-Zip\\7z.exe\" x .\\GSheetWriter.7z -y -o\"${workspacePath}\\GSheetWriter\""
				def gSheetConfig = _script.readFile "${workspacePath}\\GSheetWriter\\gSheetConfig.xml"
				gSheetConfig = gSheetConfig.replace("#ScreenshotOnError#", "true");
				def isAdminUser = isAdminUser();
				gSheetConfig = gSheetConfig.replace("#IsAdminTest#", isAdminUser.toString());
				def tagName = getTagName();
				def isTagVersion = tagName != null && tagName != "";
				gSheetConfig = gSheetConfig.replace("#IsTagVersion#", isTagVersion.toString());
				def branch = getBranch();
				gSheetConfig = gSheetConfig.replace("#ReleaseNumber#", fullTeexmaVersion);
				if (tagName == null) tagName = "";
				gSheetConfig = gSheetConfig.replace("#TagName#", tagName);
				def isPrepareSheet = isPrepareSheet();
				gSheetConfig = gSheetConfig.replace("#PrepareSpreadSheet#", "${isPrepareSheet}");
				def spreadSheetId = _script.env.spreadSheetId;
				gSheetConfig = gSheetConfig.replace("#SpreadSheetId#", spreadSheetId);
				def driveFolderId = _script.env.driveFolderId;
				gSheetConfig = gSheetConfig.replace("#DriveFolderId#", driveFolderId);
				def jobDuration = _script.currentBuild.durationString.replace(" and counting", "");
				gSheetConfig = gSheetConfig.replace("#JobDuration#", jobDuration);
				def jobNumber = _script.currentBuild.number.toString();
				gSheetConfig = gSheetConfig.replace("#JobNumber#", jobNumber);
				_script.writeFile file: "${workspacePath}\\logsXml\\gSheetConfig.xml", text: gSheetConfig
				_script.dir ("GSheetWriter") {
					_script.bat ".\\GSheetWriter.exe \"..\\logsXml\""
				}
			}
		}
	}
	
	/**
	 * Starts a TEEXMA container and gets its revision number
	 * @param scenariosFolder the scenario folder used to create a TEEXMA container to get its revision from
	 * @param teexmaImageName the name of the TEEXMA image that will be used to create the container
	 * @return the revision number as a String
	 */
	def getRevision(String scenariosFolder, String teexmaImageName) {
		def txUtils = new TeexmaUtils()
		def dockerUtils = new DockerUtils()
		def branch = getBranch()
		def pathDB = getPathDB();
		def teexmaName = getTeexmaName(scenariosFolder);
		def sgbdUrl = _script.env.sgbdUrl.replace("/", "\\");
		def releaseNumber
		_script.node (WorkflowUtils.getIISNodeLabel(_script)) {
			def workspacePath = WorkflowUtils.getWorkspacePath(_script)
			def txFilesPath = "${workspacePath}\\CustomFiles${teexmaName}";
			WorkflowUtils.deleteDirIfExists(_script, txFilesPath);
			_script.bat "mkdir ${txFilesPath}"
			def portNumber = 81 + _script.env.EXECUTOR_NUMBER.toInteger();
			def containerName = getContainerName()
			try {
				_script.bat "docker rm -fv ${containerName}"
			} catch (Exception e) {
				_script.echo "expected docker error for removing non exsiting container"
			}
			_script.bat "docker run -d -p ${portNumber}:443 -e \"dbName=${teexmaName}\" -e \"dbPath=${sgbdUrl}\" --name ${containerName} -v ${txFilesPath}:C:\\CustomFiles ${teexmaImageName}"
			try {
				def txASPPath = "C:\\TEEXMA\\Core\\dlls\\TxAPI.dll";
				def escapedFilePath = txASPPath//.replace("\\", "\\\\");
				def fileVersion = _script.bat (returnStdout: true, script: "@echo off\r\ndocker exec ${containerName} powershell [System.Diagnostics.FileVersionInfo]::GetVersionInfo(\\\"${escapedFilePath}\\\").FileVersion")
				releaseNumber = fileVersion.replace("\n", "").replace("\r", "").trim();
				
			} finally {
				try {
					_script.bat "docker rm -fv ${containerName}"
				} catch(Exception e) {
					_script.echo "failed to delete container ${containerName} : ${e.message}"
				}
				WorkflowUtils.deleteDirIfExists(_script, txFilesPath);
			}
		}
		return releaseNumber
	}
	
	/**
	 * restores a SQLServer database to a SQLServer instance
	 * @param dbInstanceName the name of the sqlServer database instance that will be created
	 * @param database the name of the database file that will be restored without its extension
	 * @param shouldRestoreArtifact set to true to download the artifacts required to restore a database can be set to false if this not the first call in the same workspace
	 * @return nothing
	 */
	def restorePostGreSQLDatabase(String dbInstanceName, String database, boolean shouldRestoreArtifact = true) {
		def workspacePath = WorkflowUtils.getWorkspacePath(_script)
		def txUtils = new TeexmaUtils()
		def dbFolder = "BDD"
		WorkflowUtils.deleteDirIfExists(_script, "${workspacePath}\\${dbFolder}")
		_script.bat "mkdir ${workspacePath}\\${dbFolder}"
		def pathDB = getPathPostGreDB();
		//Restoring database
		_script.bat "copy \"${pathDB}\\${database}.backup\" \"${workspacePath}\\${dbFolder}\\\""
		//TODO Kenza appel scp depuis powershell pour copier la base vers la machine linux
	
		//TODO Kenza restauration de la base vers le conteneur
		
		WorkflowUtils.deleteFileIfExists(_script, "${workspacePath}\\${dbFolder}\\${database}.backup");
	}
	
	/**
	 * restores a SQLServer database to a SQLServer instance
	 * @param dbInstanceName the name of the sqlServer database instance that will be created
	 * @param database the name of the database file that will be restored without its extension
	 * @param shouldRestoreArtifact set to true to download the artifacts required to restore a database can be set to false if this not the first call in the same workspace
	 * @return nothing
	 */
	def restoreSqlServerDatabase(String dbInstanceName, String database, boolean shouldRestoreArtifact = true) {
		def workspacePath = WorkflowUtils.getWorkspacePath(_script)
		def txUtils = new TeexmaUtils()
		def dbFolder = "BDD"
		WorkflowUtils.deleteDirIfExists(_script, "${workspacePath}\\${dbFolder}")
		_script.bat "mkdir ${workspacePath}\\${dbFolder}"
		def pathDB = getPathDB();
		//Restoring database
		_script.bat "copy \"${pathDB}\\${database}.bak\" \"${workspacePath}\\${dbFolder}\\\""
		def sgbdUrl = _script.env.sgbdUrl.replace("/", "\\");
		for (int j = 0; j < 3; j++) {
			try {
				_script.sleep(j * 30)
				txUtils.restoreDB(_script, "${workspacePath}\\${dbFolder}\\${database}.bak", sgbdUrl, dbInstanceName, shouldRestoreArtifact);
				break;
			} catch (Exception e) {
				_script.echo "Failed to restore database ${database} for scenario ${dbInstanceName} retrying"
				_script.echo e.getMessage()
				_script.echo e.getStackTrace().toString()			}
		}
		WorkflowUtils.deleteFileIfExists(_script, "${workspacePath}\\${dbFolder}\\${database}.bak");
	}
	
	/**
	 * checks if a tag of test code exists for a TEEXMA tag
	 * @param tagName the name of the tag
	 * @return true if a tag of test code exists for a TEEXMA tag
	 */
	def doScriptsTagExists(tagName) {
		_script.node (WorkflowUtils.getSeleniumNodeLabel(_script)) {
			try {
				def tagUrl = SvnUtils.getTxWebTestsSvnUrl("Tags/${tagName}");
				//This command does not return 0 if the tag does not exists
				_script.sh "svn ls ${tagUrl}"
				return true
			} catch (Exception e) {
				_script.echo "${tagName} : Scripts tag not found"
				return false
			}
		}
	}
	
	def getBranch() {
		def branch = _script.env.branch
		if (branch == null) {
			branch = _script.params.branch
		}
		return branch;
	}
	
	def isPrepareSheet() {
		def isPrepareSheet = _script.params.isPrepareSheet
		if (isPrepareSheet == null) {
			isPrepareSheet = _script.env.isPrepareSheet == "true"
		}
		return isPrepareSheet;
	}
	
	def getTagName() {
		def tagName = _script.env.tagName
		if (tagName == null) {
			tagName = _script.params.tagName
		}
		return tagName;
	}
	
	def getDbType() {
		def dbType = _script.env.dbType
		if (dbType == null) {
			dbType = _script.params.dbType
		}
		if (dbType == null) {
			dbType = "SqlServer"
		}
		return dbType;
	}
	
	def isAdminUser() {
		def isAdminUser = _script.env.isAdminUser
		if (isAdminUser == null) {
			isAdminUser = _script.params.isAdminUser
		} else {
			isAdminUser = isAdminUser == "true";
		}
		return isAdminUser;
	}
	
	def getScenariosFolders() {
		def scenariosFolders = _script.params.scenariosFolders
		if (scenariosFolders == null) {
			scenariosFolders = _script.env.scenariosFolders
		}
		return scenariosFolders
	}
	
	def getCreateTxWebTestsImage() {
		def createTxWebTestsImage = _script.params.createTxWebTestsImage
		if (createTxWebTestsImage == null) {
			createTxWebTestsImage = _script.env.createTxWebTestsImage == "true"
		}
		return createTxWebTestsImage
		
	}
	
	def getBatchSize() {
		def batchSize = _script.env.batchSize
		if (branch == null) {
			batchSize = _script.params.batchSize
		}
		return batchSize as Integer;
	}
	
	def int getFileTestCount(String fileContent) {
		int count;	
		if (fileContent != null) {
			count = fileContent.tokenize("\r\n").size()
		} else {
			count = 0;
		}
		return count;
	}
	
	def getContainerName() {
		def executorNumber = _script.env.EXECUTOR_NUMBER.toInteger();
		return "teexma${executorNumber}"
	}
	
	def getTeexmaName(String scenariosFolder) {
		def jobName = WorkflowUtils.getSanitizedJobName(_script);
		return jobName.concat(scenariosFolder).replaceAll("[^a-zA-Z0-9]+", "");
	}
	
	def getPathPostGreDB() {
		def branch = getBranch()
		def version = branch.tokenize("/").last();	
		def modifiedVersion = version;
		if (version != "Trunk") {
			modifiedVersion = version.substring(0, 5);
		}
		return "\\\\VFILER01\\Partage\\SOURCES_APPLICATIVES\\SOURCES_TEEXMA\\BDD\\REF_BDD_TESTS_INDIA\\${modifiedVersion}\\StandardTest\\pgBackup";
	}
	
	def getPathDB() {
		def branch = getBranch()
		def version = branch.tokenize("/").last();	
		def modifiedVersion = version;
		if (version != "Trunk") {
			modifiedVersion = version.substring(0, 5);
		}
		return "\\\\VFILER01\\Partage\\SOURCES_APPLICATIVES\\SOURCES_TEEXMA\\BDD\\REF_BDD_TESTS_INDIA\\${modifiedVersion}\\StandardTest";
	}
}
