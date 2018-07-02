/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.json.JsonSlurper
import groovy.text.SimpleTemplateEngine
import org.apache.commons.io.FilenameUtils

assert args : "Expecting the OpenWhisk home directory to passed"
owHomePath = args[0]

def repos = loadRepoJson()

def template = getClass().getResource("./modules.md").text
def engine = new SimpleTemplateEngine()

core = new Category("Main", true)
clients = new Category("Clients", true)
runtimes = new Category("Runtimes", true)
packages = new Category("Packages", true)
deployments = new Category("Deployments", true)
utils = new Category("Utilities", false)
others = new Category("Others", false)
samples = new Category("Samples and Examples", false)
devtools = new Category("Development Tools", false)

def categories = [core, clients, runtimes, deployments, packages, samples, devtools, utils, others]

repos.each{ repo ->
    Category c = getCategory(repo.name)
    c.repos << repo
}

categories.each {it.afterPropertiesSet()}

def binding = ["categories":categories]
def result = engine.createTemplate(template).make(binding)

def file = getModuleOutputFile()
file.setText(result.toString(), 'UTF-8')
println "Generated modules details at ${file.getAbsolutePath()}"

def loadRepoJson(){
    File file = new File(FilenameUtils.concat(owHomePath, "build/repos/repos.json"))
    assert file.exists() : "Did not found ${file.absolutePath}. Run './gradlew :tools:dev:listRepos' prior to this script"
    def parser = new JsonSlurper()
    parser.parseText(file.text)
}


def getCategory(String name){
    def coreRepoSuffixes = ['openwhisk', 'apigateway', 'catalog', 'cli', 'wskdeploy']
    def utilRepoSuffixes = ['utilities', 'release']
    def samplesSuffixes = ['workshop', 'slackinvite', 'sample-slackbot', 'sample-matos', 'tutorial', 'GitHubSlackBot']
    def devtoolsSuffixes = ['devtools', 'xcode', 'vscode', 'playground', 'debugger']

    if (matchesAny(coreRepoSuffixes, name)) {
        core
    } else if (name.contains('-client-')){
        clients
    } else if (name.contains('-runtime-')){
        runtimes
    } else if (name.contains('-package-')){
        packages
    } else if (name.contains('-deploy-')){
        deployments
    } else if (matchesAny(utilRepoSuffixes, name)){
        utils
    } else if (matchesAny(samplesSuffixes, name)){
        samples
    } else if (matchesAny(devtoolsSuffixes, name)){
        devtools
    } else {
        others
    }
}

def matchesAny(suffixes, name){
    suffixes.any {name.endsWith("-"+it)}
}

class Category {
    String name
    boolean travisEnabled
    List repos = []

    Category(String name, boolean travisEnabled){
        this.name = name
        this.travisEnabled = travisEnabled
    }

    def afterPropertiesSet(){
        repos.sort {
            a,b -> a.name <=> b.name
        }
    }
}

def getModuleOutputFile(){
    new File(FilenameUtils.concat(owHomePath, "docs/dev/modules.md"))
}
