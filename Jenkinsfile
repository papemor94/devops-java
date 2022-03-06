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
stage('Build') {
steps {
dir("/var/lib/jenkins/workspace/test") {
sh 'mvn -B -DskipTests clean package'
}
}
}
}
}
