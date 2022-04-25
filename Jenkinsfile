def modules = [:]
def script  = this
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
         
                    this.modules.first = load "perf.groovy"
                    this.modules.first.example1()
               
      bat 'mvn clean package '
      }
    }
  }
}
