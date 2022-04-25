def modules = [:]
pipelin {
  def executor = this
  agent any
  tools {
    maven "mav"
    jdk "jdk8"
  }
  stages {
    stage('Initialize'){
      steps{
        executor.echo "PATH = ${M2_HOME}/bin:${PATH}"
        executor.echo "M2_HOME = /opt/maven"
      }
     }
   stage ('Build'){
      steps {
        script{
                    modules.first = load "perf.groovy"
                    modules.first.example1()
        }
      bat 'mvn clean package '
      }
    }
  }
}
