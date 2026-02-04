#!groovy
@Library('CIPaaS') import com.bnpparibasfortis.CIPaaServices

//use JsonSlurperClassic because it produces HashMap that could be serialized by pipeline
import groovy.json.JsonSlurperClassic
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.json.JsonBuilder
import org.apache.commons.io.FileUtils

// Init CIPaaS Services
env.APPLICATION_CODE = "SOBP"
env.APPLICATION_COMPONENT = "SOBP-DEPL"
env.APPLICATION_COMPONENT_TYPE = "AxwayAPIGatewayKps"

def cipaas = new CIPaaServices(this)
def cipaasDockerRegistryName = cipaas.getCIPaaSDockerRegistryName()

def countryList = []

class TpServices {
		String cn
		String encodedCn
		String certSerial
		String notBefore
		String notAfter
		String certSubjectShortName
		String encodedCert

		String toString()
		{
			"{ cn : ${cn}, certSerial : ${certSerial}, notBefore : ${notBefore},notAfter : ${notAfter}, certSubjectShortName : ${certSubjectShortName} }"
		}
	}

def version = "1.0.0"

// Maven image used in this pipeline
def mavenImageName = "${cipaasDockerRegistryName}/${MAVEN_IMAGE_NAME}"
def mavenImage = docker.image("${mavenImageName}")

def GetCountryList(){
    def tempCountryList = ['EU']
    def kpsURL = "https://eidas.ec.europa.eu/efda/tl-browser/api/v1/search/countries_list_no_lotl_territory"
    //def kpsURL = "https://10.65.174.88:6265/getCountryList"
    def get = httpRequest url: kpsURL,
            httpMode: 'GET',
            ignoreSslErrors: true,
            httpProxy: 'http://nwbcproxy.res.sys.shared.fortis:8080'
    println(get.content)
    def getRC = get.getStatus();
    println(getRC);
    if (getRC.equals(200)) {
        def countryResponse = new JsonSlurperClassic().parseText(get.getContent())
         countryResponse.each {
             println("country = " + it.countryCode)
             tempCountryList.add(it.countryCode)
         }
    }   
    println tempCountryList
    return tempCountryList
}

// function that will update the passwords depending into the /node_project/cred/nm_cred.json file
def CallEuQtsp(workspaceDir,country){
   
    println "Country param : " + country
    println "workspaceDir : " + workspaceDir

    def folder = new File(workspaceDir + "\\QTSPlist\\")
        if(!folder.exists())
    		folder.mkdirs()
    
    String jsonStr;
    def jsonInputFileName = workspaceDir + "/QTSPlist/" + country + ".json"

    println jsonInputFileName
    def kpsURL = "https://eidas.ec.europa.eu/efda/tl-browser/api/v1/browser/tl/" + country
    //def kpsURL = "https://10.65.174.88:6265/getEuCA/efda/tl-browser/api/v1/browser/tl/" + country
    def get = httpRequest url: kpsURL,
            httpMode: 'GET',
            outputFile: jsonInputFileName,
            ignoreSslErrors: true,
            httpProxy: 'http://nwbcproxy.res.sys.shared.fortis:8080'

    def getRC = get.getStatus();
    println(getRC);
}


// function that will update the passwords depending into the /node_project/cred/nm_cred.json file
def GenerateQtspKpsFile(workspaceDir,country){

    println "Country param : " + country
    println "workspaceDir : " + workspaceDir

    String jsonStr;
    def jsonInputFileName = workspaceDir + "/QTSPlist/" + country + ".json"

    println jsonInputFileName
    sh "du -h ${jsonInputFileName}"

    // Get The Data from QWAC File
    def qtspjson = new JsonSlurperClassic().parse(new File(jsonInputFileName))
    def tpServiceList = []
    qtspjson.serviceProviders.each {

        if(it.qServiceTypes.contains("QWAC") ) {

           it.services.each {

                if(it.currentStatus.contains("Trusted") && it.qServiceTypes.contains("QWAC") ){
                    TpServices tpServices = new TpServices()
                    tpServices.cn = it.digitalIdentity.certificates[0].subject.bytes.encodeBase64().toString()
                    tpServices.encodedCn = URLEncoder.encode(tpServices.cn, "UTF-8")
                    tpServices.certSubjectShortName = it.digitalIdentity.certificates[0].subjectShortName.replaceAll("[^a-zA-Z0-9]", " ");  
                    tpServices.encodedCert = it.digitalIdentity.certificates[0].base64
                    tpServiceList.add(tpServices)
                    println "tpServices.certSubjectShortName : " + tpServices.certSubjectShortName
                }
            }
        }
    }

    def tableAlias = "QtspListCert"
    println("tableAlias =" + tableAlias)
    
    def keyName = "encodedCn"
    println("keyName =" + keyName)

    tpServiceList.each {item ->
        def str;
        str = '{ "cn" : "' + item.cn + '", "certSerial" : "'+ item.certSerial + '","notBefore" : "'+ item.notBefore + '","notAfter" : "'+       item.notAfter +'","certSubjectShortName" : "' + item.certSubjectShortName + '"}'

        def jsonPemFileName = workspaceDir + "/QWAC/" + item.certSubjectShortName.replaceAll("\\s","") +  ".pem"
        def fullEncodedCert = "-----BEGIN CERTIFICATE-----\n" + item.encodedCert + "\n-----END CERTIFICATE-----"
	    def folder = new File(workspaceDir + "\\QWAC\\")
	
	    if(!folder.exists())
		    folder.mkdirs()
        writeFile file: jsonPemFileName, text: fullEncodedCert
    }
}


def DeleteFile(fileToDelete){
	def fileToDel = new File(fileToDelete)
    if(fileToDel.exists()){
     println "File ${fileToDel} already exists. Deleting"
		fileToDel.delete()
    }
}

node{
    filesFolder = env.WORKSPACE + "/files"

    println "Setting credentials for git projects"

	stage ('Start pipeline') {println "Start pipeline"}
	stage ('Checkout') {checkout scm}

    stage("git pull"){
        sh 'git branch -a'
        sh 'git checkout master'
        sh 'git branch'
    }
    stage("GetCountryList"){
        println("Begin get Country list")
        countryList = GetCountryList()
        println("End get Country list")
        println("Begin get Qwac list")
        if(countryList.isEmpty()){
          println("countryList i empty")
        }
        countryList.each {item ->
            CallEuQtsp(filesFolder,item)
        }
        println("End get Qwac list")
    }
	stage("GenerateQtspFile") {
        println("Begin Generate Qwac list")
        countryList.each {item ->
            println "Country : " + item
            GenerateQtspKpsFile(filesFolder,item)
        }
	    println("End Generate Qwac list")
	}
	
	
	stage("push and commit"){

        sh 'git status'
        sh 'git add .'
		println  sh(returnStdout: true, script: "git diff-index --quiet HEAD || git commit -m 'KeyStore with new CA '").trim()
        
        withCredentials([usernamePassword(credentialsId: "GIT_JENKINS_USER", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
			steps.sh("echo 'https://${GIT_USERNAME}:${GIT_PASSWORD}@gitlab.res.sys.shared.fortis' > ${env.WORKSPACE}/.gitcredentials")
			steps.sh("git config credential.helper 'store --file ${env.WORKSPACE}/.gitcredentials'")
            steps.sh("git push origin master")
		}

    }
	stage('End pipeline') {
        println "End pipeline"
        cleanWs deleteDirs: true
    }
}
