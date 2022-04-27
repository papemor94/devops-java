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
      stash includes: env.WORKSPACE, name: 'builtSources'
      dir('C:\\ProgramData\\Jenkins\\.jenkins\\workspace\\stashs') {
          unstash 'builtSources'
        }
      }
    }
  }
}
