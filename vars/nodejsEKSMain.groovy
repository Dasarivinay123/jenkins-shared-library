def call (Map configMap){
    pipeline {
        agent {
            node {
                label 'ROBOSHOP'
            }
        }
        environment {
            def appVersion = "" 
            acc_id = "039077527503"
            project = configMap.get("project")
            component = configMap.get("component")
            org = "Dasarivinay123"
        }
        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }
        // parameters {
        //     string(name: 'PERSON', defaultValue: 'Mr Jenkins', description: 'Who should I say hello to?')
        //     text(name: 'BIOGRAPHY', defaultValue: '', description: 'Enter some information about the person')
        //     booleanParam(name: 'DEPLOY', defaultValue: true, description: 'Toggle this value')
        //     choice(name: 'CHOICE', choices: ['One', 'Two', 'Three'], description: 'Pick something')
        //     password(name: 'PASSWORD', defaultValue: 'SECRET', description: 'Enter a password')
        // }       
        // Build
        stages {
            stage('Read version'){
                steps{
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version

                        echo "The application version is: ${appVersion}"

                        sh 'printenv'
                    }
                }
            }
            stage('Dev Deploy') {
                steps {
                    script {
                         try {
                            withAWS(credentials: 'aws_creds', region: 'us-east-1') {
                                    sh """
                                        aws eks update-kubeconfig --name roboshop --region us-east-1
                                        cd helm
                                        helm upgrade --install ${component} . \
                                            -f values-dev.yaml \
                                            -n roboshop-dev \
                                            --set deployment.imageVersion=${appVersion} \
                                            --wait --timeout 5m
                                        kubectl rollout status deployment/${component} -n roboshop-dev --timeout=2m
                                    """
                            }
                            utils.updateCommitStatus('success', 'Deployed to roboshop-dev', 'dev-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-dev failed', 'dev-deploy')
                            throw e
                        }
                    }
                }
            }
            stage('api-tests') {
                steps {
                    script {
                        try {
                            build job: 'ROBOSHOP/catalogue-api-tests', parameters: [
                                string(name: 'NAMESPACE', value: 'roboshop-dev'),
                                string(name: 'COMMIT_ID', value: env.GIT_COMMIT)
                            ], wait: true, propagate: true
                            utils.updateCommitStatus('success', 'catalogue-api-tests passed', 'api-tests')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'catalogue-api-tests failed', 'api-tests')
                            throw e
                        }
                    }
                }
            }
            stage('promote-image') {
                when {
                     expression { env.ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws_creds', region: 'us-east-1') {
                                sh """
                                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com

                                    docker pull ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                                    docker tag ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${env.GIT_COMMIT}
                                    docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${env.GIT_COMMIT}
                                """
                            }
                            utils.updateCommitStatus('success', "Promoted image as ${env.GIT_COMMIT}", 'promote-image')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Image promotion failed', 'promote-image')
                            throw e
                        }
                    }
                }
            }
        }
        post { 
            always { 
                echo 'I will always say Hello again!'
            }
            success {
                    slackSend channel: '#jenkins-alerts-90s',
                    color: 'good',
                    message: "Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL}) was successful."
                }
            failure {
                    slackSend channel: '#jenkins-alerts-90s',
                    color: 'danger',
                    message: "Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL}) failed."
                }
        }
    }
}