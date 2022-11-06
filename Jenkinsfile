def modules = [:]
pipeline {
  agent any

  envrironnement{

     USER_NAME = "Pape";
     USER_ID = 22;
  }
  stages {
      stage('Initialize'){
          steps{
            echo "USER_NAME  = ${env.USER_NAME}"
            //echo "M2_HOME = /opt/maven"
          }
      }
  /*    stage ('Build'){
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
      echo readFile("./").size()
      }
    }*/
  }
}
