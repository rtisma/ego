def commit = "UNKNOWN"
def version = "UNKNOWN"
def chartversion = "3.1.0"

pipeline {
    agent {
        kubernetes {
            label 'ego-executor'
            yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: maven
    command: ['cat']
    tty: true
    image: maven:3.6.3-openjdk-11
  - name: jdk
    tty: true
    image: adoptopenjdk/openjdk11:jdk-11.0.7_10-alpine-slim
    env:
      - name: DOCKER_HOST
        value: tcp://localhost:2375
  - name: dind-daemon
    image: docker:18.06-dind
    securityContext:
        privileged: true
        runAsUser: 0
    volumeMounts:
      - name: docker-graph-storage
        mountPath: /var/lib/docker
  - name: helm
    image: alpine/helm:2.12.3
    command:
    - cat
    tty: true
  - name: docker
    image: docker:18-git
    tty: true
    env:
    - name: DOCKER_HOST
      value: tcp://localhost:2375
    - name: HOME
      value: /home/jenkins/agent
  securityContext:
    runAsUser: 1000
  volumes:
  - name: docker-graph-storage
    emptyDir: {}
"""
        }
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    commit = sh(returnStdout: true, script: 'git describe --always').trim()
                }
                script {
                    version = readMavenPom().getVersion()
                }
            }
        }
        stage('Test') {
            environment {
                SELENIUM_TEST_TYPE = 'BROWSERSTACK'
                BROWSERSTACK_USERNAME = credentials('browserstack_user')
                BROWSERSTACK_ACCESS_KEY = credentials('browserstack_key')
                FACEBOOK_CLIENT_CLIENTID = '1196783150487854'
                FACEBOOK_CLIENT_CLIENTSECRET = credentials('facebook_selenium_secret')
                FACEBOOK_USER = 'immxgmqvsf_1551302168@tfbnw.net'
                FACEBOOK_PASS = credentials('facebook_pass')
            }
            steps {
                container('jdk') {
                    sh "./mvnw test"
                }
            }
        }
        stage('Build Artifact & Publish') {
             when {
                anyOf {
                    branch "master"
                    branch "develop"
                }
            }
            steps {
                container('maven') {
                    configFileProvider(
                        [configFile(fileId: '01ae7759-03a9-47c0-9db6-925aebb50ae1', variable: 'MAVEN_SETTINGS')]) {
                        sh 'mvn -s $MAVEN_SETTINGS clean package deploy -DskipTests'
                    }
                }
            }
        }
        stage('Build & Publish Develop') {
            when {
                branch "develop"
            }
            steps {
                container('docker') {
                    withCredentials([usernamePassword(credentialsId:'OvertureDockerHub', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        sh 'docker login -u $USERNAME -p $PASSWORD'
                    }
                    sh "docker build --network=host -f Dockerfile . -t overture/ego:edge -t overture/ego:${commit}"
                    sh "docker push overture/ego:${commit}"
                    sh "docker push overture/ego:edge"
                }
            }
        }

        stage('Release & tag') {
          when {
            branch "master"
          }
          steps {
                container('docker') {
                    withCredentials([usernamePassword(credentialsId: 'OvertureBioGithub', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                        sh "git tag ${version}"
                        sh "git push https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/overture-stack/ego --tags"
                    }
                    withCredentials([usernamePassword(credentialsId:'OvertureDockerHub', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        sh 'docker login -u $USERNAME -p $PASSWORD'
                    }
                    sh "docker build --network=host -f Dockerfile . -t overture/ego:latest -t overture/ego:${version}"
                    sh "docker push overture/ego:${version}"
                    sh "docker push overture/ego:latest"
                }
            }
        }

        stage('Deploy to Overture QA') {
            when {
                  branch "develop"
            }
			steps {
				build(job: "/Overture.bio/provision/helm", parameters: [
						[$class: 'StringParameterValue', name: 'OVERTURE_ENV', value: 'qa' ],
						[$class: 'StringParameterValue', name: 'OVERTURE_CHART_NAME', value: 'ego'],
						[$class: 'StringParameterValue', name: 'OVERTURE_RELEASE_NAME', value: 'ego'],
						[$class: 'StringParameterValue', name: 'OVERTURE_HELM_CHART_VERSION', value: "${chartversion}"],
						[$class: 'StringParameterValue', name: 'OVERTURE_HELM_REPO_URL', value: "https://overture-stack.github.io/charts-server/"],
						[$class: 'StringParameterValue', name: 'OVERTURE_HELM_REUSE_VALUES', value: "false" ],
						[$class: 'StringParameterValue', name: 'OVERTURE_ARGS_LINE', value: "--set-string image.tag=${commit}" ]
				])
			}
        }

        stage('Deploy to Overture Staging') {
            when {
                  branch "master"
            }
            steps {
				build(job: "/Overture.bio/provision/helm", parameters: [
						[$class: 'StringParameterValue', name: 'OVERTURE_ENV', value: 'staging' ],
						[$class: 'StringParameterValue', name: 'OVERTURE_CHART_NAME', value: 'ego'],
						[$class: 'StringParameterValue', name: 'OVERTURE_RELEASE_NAME', value: 'ego'],
						[$class: 'StringParameterValue', name: 'OVERTURE_HELM_CHART_VERSION', value: "${chartversion}"],
						[$class: 'StringParameterValue', name: 'OVERTURE_HELM_REPO_URL', value: "https://overture-stack.github.io/charts-server/"],
						[$class: 'StringParameterValue', name: 'OVERTURE_HELM_REUSE_VALUES', value: "false" ],
						[$class: 'StringParameterValue', name: 'OVERTURE_ARGS_LINE', value: "--set-string image.tag=${version}" ]
				])
            }
        }

    }
}
