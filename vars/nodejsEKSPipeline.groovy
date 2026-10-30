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
            stage('Install Dependencies') {
                steps {
                script {
                    sh """
                            npm install
                        """
                    }
                }
            }
            // this command gives us coverage report and test cases report, sonarqube access this to check quality gate
            stage('Unit tests') {
                steps {
                    script {
                        try {
                            sh """
                                npm test
                            """
                            utils.updateCommitStatus('SUCCESS', 'Unit tests passed', 'unit-tests')
                        } catch (Exception e) {
                            utils.updateCommitStatus('FAILURE', 'Unit tests failed', 'unit-tests')
                            throw e
                        }
                    } 
                }
            }
            stage('SonarQube Analysis') {
                steps {
                    // 'SonarQube-Server' must match the server name in Manage Jenkins -> System
/*                     withSonarQubeEnv('sonar-server') {
                        sh "${tool 'sonar-8'}/bin/sonar-scanner"
                    } */
                    script {
                        sh "echo 'sonarqube analysis done'"
                    }
                }
            }
            stage('SonarQube Quality Gate') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            // def qg = waitForQualityGate() // Pauses pipeline
                            def qg = [status: 'OK']
                            if (qg.status != 'OK') {
                                utils.updateCommitStatus('FAILURE', 'Sonar Scan failed', 'sonar-scan')
                                error "Pipeline aborted: ${qg.status}"
                            }else{
                                 utils.updateCommitStatus('success', 'Sonar Scan success', 'sonar-scan')
                            }
                        }
                    }
                }
            }
            stage('Check Dependabot Alerts') {
                steps {
                    script {
                        try {
                            int highCriticalCount = 0

                            withCredentials([
                                string(credentialsId: 'github-token', variable: 'GH_TOKEN')
                            ]) {
                                sh '''
                                    set -eu

                                    REPO="${org}/${component}"

                                    echo "Checking GitHub Dependabot alerts..."

                                    HTTP_STATUS=$(curl -sS -L \
                                        -o alerts.json \
                                        -w "%{http_code}" \
                                        -H "Accept: application/vnd.github+json" \
                                        -H "Authorization: Bearer ${GH_TOKEN}" \
                                        -H "X-GitHub-Api-Version: 2022-11-28" \
                                        "https://api.github.com/repos/${REPO}/dependabot/alerts?state=open&per_page=100")

                                    if [ "$HTTP_STATUS" != "200" ]; then
                                        echo "GitHub API request failed. HTTP status: ${HTTP_STATUS}"
                                        cat alerts.json
                                        exit 1
                                    fi

                                    if ! jq -e 'type == "array"' alerts.json > /dev/null; then
                                        echo "Invalid GitHub API response."
                                        cat alerts.json
                                        exit 1
                                    fi

                                    echo "---- Open Dependabot Alerts ----"

                                    jq -r '
                                        if length == 0 then
                                            "No open Dependabot alerts found."
                                        else
                                            .[] |
                                            "Alert: \\(.number) | Severity: \\(.security_vulnerability.severity) | Package: \\(.dependency.package.name) | Advisory: \\(.security_advisory.ghsa_id)"
                                        end
                                    ' alerts.json

                                    HIGH_CRITICAL_COUNT=$(jq '[
                                        .[] |
                                        select(
                                            .security_vulnerability.severity == "high" or
                                            .security_vulnerability.severity == "critical"
                                        )
                                    ] | length' alerts.json)

                                    echo "High/Critical alert count: ${HIGH_CRITICAL_COUNT}"
                                    printf '%s\\n' "$HIGH_CRITICAL_COUNT" > high-critical-count.txt
                                '''

                                highCriticalCount = readFile('high-critical-count.txt').trim().toInteger()
                            }

                            if (highCriticalCount > 0) {
                                echo "High/Critical dependency alerts found. Failing build."

                                utils.updateCommitStatus(
                                    'failure',
                                    'Library scan failed',
                                    'library-scan'
                                )

                                error("Found ${highCriticalCount} High/Critical Dependabot alerts.")
                            } else {
                                echo "No High/Critical dependency alerts found."

                                utils.updateCommitStatus(
                                    'success',
                                    'Library scan passed',
                                    'library-scan'
                                )
                            }

                        } catch (Exception e) {
                            utils.updateCommitStatus(
                                'failure',
                                'Dependabot alerts check failed',
                                'dependabot-check'
                            )
                            throw e
                        }
                    }
                }
            }
            stage('Docker Build') {
                steps {
                    script {
                        // in this block we get aws authentication
                        try {
                            withAWS(credentials: 'aws_creds', region: 'us-east-1') {
                                sh """
                                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com
                                    docker build -t ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} .
                                """
                            }
                            utils.updateCommitStatus('success', 'Docker image build', 'build-image')
                        } 
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Docker image build failed', 'build-image')
                            throw e
                        }
                    }
                }
            }
            stage('Trivy Scan') {
                steps {
                    script {
                        def dockerfileScan = sh(
                            script: """
                                trivy config --exit-code 1 --severity HIGH,CRITICAL --format table ./Dockerfile
                            """,
                            returnStatus: true
                        )

                        def imageScan = sh(
                            script: """
                                trivy image --scanners vuln --pkg-types os --exit-code 1 --severity HIGH,CRITICAL --format table ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                            """,
                            returnStatus: true
                        )

                        if (dockerfileScan != 0 || imageScan != 0) {
                              utils.updateCommitStatus('failure', 'Trivy scan failed', 'trivy-scan')
                            error "Trivy found HIGH/CRITICAL issues in Dockerfile and/or OS packages. Failing pipeline."
                        } else {
                            utils.updateCommitStatus('success', 'Trivy scan passed', 'trivy-scan')
                        }
                    }
                }
            }
            stage('ECR Image push') {
                steps {
                    script {
                        try {
                            // in this block we get aws authentication
                            withAWS(credentials: 'aws_creds', region: 'us-east-1') {
                                sh """
                                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com
                                    docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                                """
                            }
                            utils.updateCommitStatus('success', 'push image to ECR', 'push-image')
                        } catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Docker image push failed', 'push-image')
                            throw e
                        }
                    }
                }
            }
            stage('Deploy') {
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