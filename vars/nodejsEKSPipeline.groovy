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
                        // Extract the version property
                        appVersion = packageJson.version
                        echo "The application version is: ${appVersion}"
                        printenv
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
                        sh """
                            npm test
                        """
                    } 
                }
            }
            stage('SonarQube Analysis') {
                steps {
                    // 'SonarQube-Server' must match the server name in Manage Jenkins -> System
                    withSonarQubeEnv('sonar-server') {
                        sh "${tool 'sonar-8'}/bin/sonar-scanner"
                    }
                }
            }
            stage('SonarQube Quality Gate') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            def qg = waitForQualityGate() // Pauses pipeline
                            if (qg.status != 'OK') {
                                error "Pipeline aborted: ${qg.status}"
                            }
                        }
                    }
                }
            }
            stage('Check Dependabot Alerts') {
                steps {
                    withCredentials([
                        string(credentialsId: 'github-token', variable: 'GH_TOKEN')
                    ]) {
                        sh '''
                            set -eu

                            REPO="Dasarivinay123/catalogue"

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

                            if [ "$HIGH_CRITICAL_COUNT" -gt 0 ]; then
                                echo "High/Critical dependency alerts found. Failing build."
                                exit 1
                            else
                                echo "No High/Critical dependency alerts found."
                            fi
                        '''
                    }
                }
            }
            stage('Docker Build') {
                steps {
                    script {
                        // in this block we get aws authentication
                        withAWS(credentials: 'aws_creds', region: 'us-east-1') {
                            sh """
                                aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com
                                docker build -t ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} .
                            """
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
                            error "Trivy found HIGH/CRITICAL issues in Dockerfile and/or OS packages. Failing pipeline."
                        }
                    }
                }
            }
            stage('ECR Image push') {
                steps {
                    script {
                        // in this block we get aws authentication
                        withAWS(credentials: 'aws_creds', region: 'us-east-1') {
                            sh """
                                aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com
                                docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                            """
                        }
                    }
                }
            }
            stage('Deploy') {
                when {
                    expression { return params.DEPLOY }
                }
                input {
                    message "Should we continue?"
                    ok "Yes, we should."
                    submitter "alice,bob"
                    parameters {
                        string(name: 'PERSON', defaultValue: 'Mr Jenkins', description: 'Who should I say hello to?')
                    }
                }
                steps {
                    script {
                        echo 'Deploying....'
                    }
                }
            }
        }
        post { 
            always { 
                echo 'I will always say Hello again!'
            }
            success {
                    echo 'I will run when it is successful!'
                }
            failure {
                    echo 'I will run when it is failed!'
                }
        }
    }
}