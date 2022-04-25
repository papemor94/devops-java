def modules = [:]
pipeline {
  agent any
  tools {
    maven "mav"
    jdk "jdk8"
  }
  stages {
    stage('Initialize'){
      steps{
        echo "PATH = ${M2_HOME}/bin:${PATH}"
        echo "M2_HOME = /opt/maven"
      }
     }
   stage ('Build'){
      steps {
          script{
                    modules.first = load "perf.groovy"
                }
      bat 'mvn clean package '
      }
    }
  }
}
