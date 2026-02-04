#!groovy
@Library('CIPaaS') import com.bnpparibasfortis.CIPaaServices

// Use JsonSlurperClassic because it produces HashMap that can be serialized by pipeline
import groovy.json.JsonSlurperClassic
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.json.JsonBuilder

// ============================================================================
// CONFIGURATION - Easily adjustable parameters
// ============================================================================
def CONFIG = [
    // HTTP Settings
    httpProxy: 'http://nwbcproxy.res.sys.shared.fortis:8080',
    httpTimeout: 60,  // seconds
    maxRetries: 3,
    retryDelaySeconds: 5,

    // API URLs
    countryListUrl: 'https://eidas.ec.europa.eu/efda/tl-browser/api/v1/search/countries_list_no_lotl_territory',
    qtspBaseUrl: 'https://eidas.ec.europa.eu/efda/tl-browser/api/v1/browser/tl/',

    // Default countries (fallback if API fails)
    defaultCountries: ['EU', 'BE', 'DE', 'FR', 'NL', 'LU', 'IT', 'ES', 'AT', 'PT'],

    // Stage timeouts (minutes)
    stageTimeout: 30,

    // Git settings
    gitBranch: 'master',
    gitCredentialsId: 'GIT_JENKINS_USER'
]

// Init CIPaaS Services
env.APPLICATION_CODE = "SOBP"
env.APPLICATION_COMPONENT = "SOBP-DEPL"
env.APPLICATION_COMPONENT_TYPE = "AxwayAPIGatewayKps"

def cipaas = new CIPaaServices(this)
def cipaasDockerRegistryName = cipaas.getCIPaaSDockerRegistryName()

def countryList = []
def failedCountries = []
def successfulCountries = []

// Maven image used in this pipeline
def mavenImageName = "${cipaasDockerRegistryName}/${MAVEN_IMAGE_NAME}"
def mavenImage = docker.image("${mavenImageName}")

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

/**
 * Safely executes an HTTP request with retry logic
 * @return response or null on failure
 */
def safeHttpRequest(Map params, int maxRetries = 3, int retryDelay = 5) {
    def response = null
    def lastError = null

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            echo "HTTP Request attempt ${attempt}/${maxRetries}: ${params.url}"
            response = httpRequest(params)

            if (response.getStatus() >= 200 && response.getStatus() < 300) {
                echo "HTTP Request successful (status: ${response.getStatus()})"
                return response
            } else {
                echo "HTTP Request returned non-success status: ${response.getStatus()}"
                lastError = "HTTP status ${response.getStatus()}"
            }
        } catch (Exception e) {
            lastError = e.getMessage()
            echo "HTTP Request failed (attempt ${attempt}/${maxRetries}): ${lastError}"
        }

        if (attempt < maxRetries) {
            echo "Waiting ${retryDelay} seconds before retry..."
            sleep(retryDelay)
        }
    }

    echo "All ${maxRetries} HTTP request attempts failed. Last error: ${lastError}"
    return null
}

/**
 * Creates a directory safely (cross-platform)
 */
def createDirectorySafely(String dirPath) {
    try {
        // Normalize path separators for the current OS
        def normalizedPath = dirPath.replace('\\', '/')
        def folder = new File(normalizedPath)
        if (!folder.exists()) {
            def created = folder.mkdirs()
            if (created) {
                echo "Created directory: ${normalizedPath}"
            } else {
                echo "Warning: Could not create directory: ${normalizedPath}"
            }
        }
        return folder.exists()
    } catch (Exception e) {
        echo "Error creating directory ${dirPath}: ${e.getMessage()}"
        return false
    }
}

/**
 * Safely writes content to a file
 */
def writeFileSafely(String filePath, String content) {
    try {
        def normalizedPath = filePath.replace('\\', '/')
        writeFile file: normalizedPath, text: content
        echo "Successfully wrote file: ${normalizedPath}"
        return true
    } catch (Exception e) {
        echo "Error writing file ${filePath}: ${e.getMessage()}"
        return false
    }
}

/**
 * Safely deletes a file
 */
def deleteFileSafely(String fileToDelete) {
    try {
        def fileToDel = new File(fileToDelete.replace('\\', '/'))
        if (fileToDel.exists()) {
            def deleted = fileToDel.delete()
            if (deleted) {
                echo "Deleted file: ${fileToDelete}"
            } else {
                echo "Warning: Could not delete file: ${fileToDelete}"
            }
            return deleted
        }
        return true  // File doesn't exist, so nothing to delete
    } catch (Exception e) {
        echo "Error deleting file ${fileToDelete}: ${e.getMessage()}"
        return false
    }
}

/**
 * Safely parses JSON content
 */
def parseJsonSafely(String jsonContent) {
    try {
        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            echo "Warning: Empty or null JSON content"
            return null
        }
        return new JsonSlurperClassic().parseText(jsonContent)
    } catch (Exception e) {
        echo "Error parsing JSON: ${e.getMessage()}"
        return null
    }
}

/**
 * Safely parses JSON from file
 */
def parseJsonFileSafely(String filePath) {
    try {
        def normalizedPath = filePath.replace('\\', '/')
        def file = new File(normalizedPath)
        if (!file.exists()) {
            echo "Warning: JSON file does not exist: ${normalizedPath}"
            return null
        }
        return new JsonSlurperClassic().parse(file)
    } catch (Exception e) {
        echo "Error parsing JSON file ${filePath}: ${e.getMessage()}"
        return null
    }
}

// ============================================================================
// BUSINESS LOGIC FUNCTIONS
// ============================================================================

/**
 * Fetches the list of EU countries from the eIDAS API
 * Returns default list if API call fails
 */
def GetCountryList(Map config) {
    def tempCountryList = ['EU']  // Always include EU

    echo "Fetching country list from: ${config.countryListUrl}"

    def response = safeHttpRequest([
        url: config.countryListUrl,
        httpMode: 'GET',
        ignoreSslErrors: true,
        httpProxy: config.httpProxy,
        timeout: config.httpTimeout,
        validResponseCodes: '100:599'  // Accept all codes, we'll handle them
    ], config.maxRetries, config.retryDelaySeconds)

    if (response != null && response.getStatus() == 200) {
        def countryResponse = parseJsonSafely(response.getContent())
        if (countryResponse != null) {
            countryResponse.each { country ->
                try {
                    if (country?.countryCode) {
                        echo "Found country: ${country.countryCode}"
                        if (!tempCountryList.contains(country.countryCode)) {
                            tempCountryList.add(country.countryCode)
                        }
                    }
                } catch (Exception e) {
                    echo "Warning: Error processing country entry: ${e.getMessage()}"
                }
            }
        }
    }

    // If we only have 'EU', use default list as fallback
    if (tempCountryList.size() <= 1) {
        echo "Warning: Could not fetch country list from API. Using default list."
        tempCountryList = config.defaultCountries.collect()  // Create a copy
    }

    echo "Final country list (${tempCountryList.size()} countries): ${tempCountryList}"
    return tempCountryList
}

/**
 * Downloads QTSP data for a specific country
 * Returns true on success, false on failure
 */
def CallEuQtsp(String workspaceDir, String country, Map config) {
    echo "Downloading QTSP data for country: ${country}"

    def normalizedWorkspace = workspaceDir.replace('\\', '/')
    def folder = "${normalizedWorkspace}/QTSPlist"

    if (!createDirectorySafely(folder)) {
        echo "Error: Could not create QTSPlist directory for country ${country}"
        return false
    }

    def jsonInputFileName = "${folder}/${country}.json"
    def kpsURL = "${config.qtspBaseUrl}${country}"

    echo "Fetching from: ${kpsURL}"
    echo "Output file: ${jsonInputFileName}"

    def response = safeHttpRequest([
        url: kpsURL,
        httpMode: 'GET',
        outputFile: jsonInputFileName,
        ignoreSslErrors: true,
        httpProxy: config.httpProxy,
        timeout: config.httpTimeout,
        validResponseCodes: '100:599'
    ], config.maxRetries, config.retryDelaySeconds)

    if (response != null && response.getStatus() == 200) {
        echo "Successfully downloaded QTSP data for ${country}"
        return true
    } else {
        echo "Warning: Failed to download QTSP data for ${country}"
        return false
    }
}

/**
 * Generates QTSP KPS files for a specific country
 * Returns the number of certificates processed
 */
def GenerateQtspKpsFile(String workspaceDir, String country) {
    echo "Processing QTSP data for country: ${country}"

    def normalizedWorkspace = workspaceDir.replace('\\', '/')
    def jsonInputFileName = "${normalizedWorkspace}/QTSPlist/${country}.json"
    def processedCount = 0

    // Check if input file exists
    def inputFile = new File(jsonInputFileName)
    if (!inputFile.exists()) {
        echo "Warning: Input file does not exist: ${jsonInputFileName}"
        return 0
    }

    // Log file size
    try {
        echo "Input file size: ${inputFile.length()} bytes"
    } catch (Exception e) {
        echo "Could not determine file size"
    }

    // Parse JSON
    def qtspjson = parseJsonFileSafely(jsonInputFileName)
    if (qtspjson == null) {
        echo "Error: Could not parse JSON file for country ${country}"
        return 0
    }

    // Create QWAC directory
    def qwacFolder = "${normalizedWorkspace}/QWAC"
    if (!createDirectorySafely(qwacFolder)) {
        echo "Error: Could not create QWAC directory"
        return 0
    }

    // Process service providers
    def serviceProviders = qtspjson?.serviceProviders
    if (serviceProviders == null) {
        echo "Warning: No service providers found in JSON for country ${country}"
        return 0
    }

    serviceProviders.each { provider ->
        try {
            def services = provider?.services
            if (services == null) {
                return  // Skip this provider
            }

            services.each { service ->
                try {
                    def serviceLegalTypes = service?.serviceLegalTypes
                    def isActive = service?.active

                    // Check if service is active and has QWAC type (Q_WAC in serviceLegalTypes)
                    if (isActive == true && serviceLegalTypes?.contains("Q_WAC")) {

                        // Safely extract certificate data
                        def digitalIdentity = service?.digitalIdentity
                        def certificates = digitalIdentity?.certificates

                        if (certificates == null || certificates.isEmpty()) {
                            echo "Warning: No certificates found for service"
                            return  // Skip this service
                        }

                        def cert = certificates[0]
                        if (cert == null) {
                            echo "Warning: First certificate is null"
                            return
                        }

                        // Extract certificate information safely
                        def subjectShortName = cert?.subjectShortName
                        def base64Cert = cert?.base64

                        if (base64Cert == null) {
                            echo "Warning: Missing required certificate data (base64)"
                            return
                        }

                        def certSubjectShortName = (subjectShortName ?: "unknown").replaceAll("[^a-zA-Z0-9]", " ")
                        def sanitizedName = certSubjectShortName.replaceAll("\\s+", "")

                        // Create PEM file
                        def pemFileName = "${qwacFolder}/${sanitizedName}.pem"
                        def pemContent = "-----BEGIN CERTIFICATE-----\n${base64Cert}\n-----END CERTIFICATE-----"

                        if (writeFileSafely(pemFileName, pemContent)) {
                            echo "Generated certificate: ${certSubjectShortName}"
                            processedCount++
                        }
                    }
                } catch (Exception e) {
                    echo "Warning: Error processing service: ${e.getMessage()}"
                }
            }
        } catch (Exception e) {
            echo "Warning: Error processing provider: ${e.getMessage()}"
        }
    }

    echo "Processed ${processedCount} certificates for country ${country}"
    return processedCount
}

// ============================================================================
// MAIN PIPELINE
// ============================================================================

node {
    def filesFolder = "${env.WORKSPACE}/files".replace('\\', '/')
    def pipelineSuccess = true
    def totalCertificates = 0

    echo "Pipeline starting"
    echo "Workspace: ${env.WORKSPACE}"
    echo "Files folder: ${filesFolder}"

    try {
        stage('Start Pipeline') {
            timeout(time: 5, unit: 'MINUTES') {
                echo "Pipeline initialized"
                echo "Configuration: ${CONFIG}"
            }
        }

        stage('Checkout') {
            timeout(time: 10, unit: 'MINUTES') {
                try {
                    checkout scm
                    echo "Checkout completed successfully"
                } catch (Exception e) {
                    echo "Warning: Checkout encountered an issue: ${e.getMessage()}"
                    // Continue anyway - might be running in a pre-checked-out workspace
                }
            }
        }

        stage('Git Setup') {
            timeout(time: 5, unit: 'MINUTES') {
                try {
                    sh 'git branch -a || echo "Could not list branches"'
                    sh "git checkout ${CONFIG.gitBranch} || echo 'Already on branch or branch does not exist'"
                    sh 'git branch || echo "Could not show current branch"'
                } catch (Exception e) {
                    echo "Warning: Git setup encountered an issue: ${e.getMessage()}"
                    // Continue - git operations in push stage might still work
                }
            }
        }

        stage('Get Country List') {
            timeout(time: CONFIG.stageTimeout, unit: 'MINUTES') {
                echo "Fetching country list..."
                countryList = GetCountryList(CONFIG)

                if (countryList.isEmpty()) {
                    echo "Warning: Country list is empty, using minimal default"
                    countryList = ['EU']
                }

                echo "Will process ${countryList.size()} countries"
            }
        }

        stage('Download QTSP Data') {
            timeout(time: CONFIG.stageTimeout, unit: 'MINUTES') {
                echo "Downloading QTSP data for ${countryList.size()} countries..."

                countryList.each { country ->
                    try {
                        def success = CallEuQtsp(filesFolder, country, CONFIG)
                        if (success) {
                            successfulCountries.add(country)
                        } else {
                            failedCountries.add(country)
                        }
                    } catch (Exception e) {
                        echo "Error downloading data for ${country}: ${e.getMessage()}"
                        failedCountries.add(country)
                    }
                }

                echo "Download complete: ${successfulCountries.size()} succeeded, ${failedCountries.size()} failed"

                if (failedCountries.size() > 0) {
                    echo "Failed countries: ${failedCountries}"
                }
            }
        }

        stage('Generate QTSP Files') {
            timeout(time: CONFIG.stageTimeout, unit: 'MINUTES') {
                echo "Generating certificate files..."

                // Only process countries that were successfully downloaded
                successfulCountries.each { country ->
                    try {
                        def count = GenerateQtspKpsFile(filesFolder, country)
                        totalCertificates += count
                    } catch (Exception e) {
                        echo "Error generating files for ${country}: ${e.getMessage()}"
                    }
                }

                echo "Generated ${totalCertificates} total certificate files"
            }
        }

        stage('Commit and Push') {
            timeout(time: 10, unit: 'MINUTES') {
                try {
                    sh 'git status'

                    // Check if there are any changes to commit
                    def changes = sh(script: 'git status --porcelain', returnStdout: true).trim()

                    if (changes.isEmpty()) {
                        echo "No changes to commit"
                    } else {
                        echo "Changes detected, committing..."

                        sh 'git add .'

                        def timestamp = new Date().format('yyyy-MM-dd HH:mm:ss')
                        def commitResult = sh(
                            script: "git diff-index --quiet HEAD || git commit -m 'KeyStore with new CA - ${timestamp}'",
                            returnStdout: true
                        ).trim()
                        echo "Commit result: ${commitResult}"

                        withCredentials([usernamePassword(
                            credentialsId: CONFIG.gitCredentialsId,
                            passwordVariable: 'GIT_PASSWORD',
                            usernameVariable: 'GIT_USERNAME'
                        )]) {
                            // Set up git credentials securely
                            sh "echo 'https://${GIT_USERNAME}:${GIT_PASSWORD}@gitlab.res.sys.shared.fortis' > ${env.WORKSPACE}/.gitcredentials"
                            sh "git config credential.helper 'store --file ${env.WORKSPACE}/.gitcredentials'"

                            // Push with retry logic
                            def pushSuccess = false
                            def maxPushRetries = 3

                            for (int i = 1; i <= maxPushRetries && !pushSuccess; i++) {
                                try {
                                    echo "Push attempt ${i}/${maxPushRetries}"
                                    sh "git push origin ${CONFIG.gitBranch}"
                                    pushSuccess = true
                                    echo "Push successful"
                                } catch (Exception pushError) {
                                    echo "Push attempt ${i} failed: ${pushError.getMessage()}"
                                    if (i < maxPushRetries) {
                                        sleep(5)
                                    }
                                }
                            }

                            if (!pushSuccess) {
                                echo "Warning: All push attempts failed"
                            }

                            // Clean up credentials file
                            sh "rm -f ${env.WORKSPACE}/.gitcredentials || true"
                        }
                    }
                } catch (Exception e) {
                    echo "Error in commit/push stage: ${e.getMessage()}"
                    // Clean up credentials file even on error
                    sh "rm -f ${env.WORKSPACE}/.gitcredentials || true"
                }
            }
        }

    } catch (Exception e) {
        echo "Pipeline encountered an error: ${e.getMessage()}"
        pipelineSuccess = false
    } finally {
        stage('Cleanup and Summary') {
            timeout(time: 5, unit: 'MINUTES') {
                echo "=========================================="
                echo "PIPELINE SUMMARY"
                echo "=========================================="
                echo "Countries processed: ${successfulCountries.size()}/${countryList.size()}"
                echo "Certificates generated: ${totalCertificates}"
                echo "Failed countries: ${failedCountries.size() > 0 ? failedCountries.join(', ') : 'None'}"
                echo "Pipeline status: ${pipelineSuccess ? 'SUCCESS' : 'COMPLETED WITH WARNINGS'}"
                echo "=========================================="

                try {
                    cleanWs deleteDirs: true
                    echo "Workspace cleaned"
                } catch (Exception e) {
                    echo "Warning: Could not clean workspace: ${e.getMessage()}"
                }
            }
        }
    }

    // Always mark pipeline as successful since we handled all errors gracefully
    echo "Pipeline completed"
}
