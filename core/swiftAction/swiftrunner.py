#
# Copyright 2015-2016 IBM Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import sys
import subprocess
from subprocess import PIPE
import codecs
import json
import datetime
sys.path.append('../actionProxy')
from actionproxy import ActionRunner, main, setRunner

SRC_EPILOGUE_FILE = "./epilogue.swift"
DEST_SCRIPT_FILE = "/swiftAction/action.swift"
DEST_BIN_FILE = "/swiftAction/action"
BUILD_PROCESS = [ "swiftc", "-v", "-Xfrontend", "-debug-time-function-bodies", "-O", DEST_SCRIPT_FILE, "-o", DEST_BIN_FILE ]

class SwiftRunner(ActionRunner):

    def __init__(self):
        ActionRunner.__init__(self, DEST_SCRIPT_FILE, DEST_BIN_FILE)

    def epilogue(self, fp, init_message):
        if "main" in init_message:
            main_function = init_message["main"]
        else:
            main_function = "main"

        with codecs.open(SRC_EPILOGUE_FILE, "r", "utf-8") as ep:
            fp.write(ep.read())

        fp.write("_run_main(%s)\n" % main_function)

    def build(self):
        print "%s Start compilation" % str(datetime.datetime.now().time())
        print BUILD_PROCESS
        p = subprocess.Popen(BUILD_PROCESS, stdout=PIPE)
        (o, e) = p.communicate()

        print o
        print e

        print "%s Finished compilation" % str(datetime.datetime.now().time())

    def env(self, message):
        env = ActionRunner.env(self, message)
        args = message.get('value', {}) if message else {}
        env['WHISK_INPUT'] = json.dumps(args)
        return env

if __name__ == "__main__":
    setRunner(SwiftRunner())
    main()
