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
         def executor = this
                    modules.first = load "perf.groovy"
                    modules.first.example1()
          executor.echo "hello world"
        }
      bat 'mvn clean package '
      }
    }
    stage ('Stash'){
      steps {
       
      echo  "stashing"
      echo env.WORKSPACE
        stash includes: "${env.WORKSPACE}\\pom.xml", name: 'builtSources'
      dir('C:\\ProgramData\\Jenkins\\.jenkins\\workspace\\stashs') {
        unstash ${env.WORKSPACE}
        }
      }
    }
  }
}
