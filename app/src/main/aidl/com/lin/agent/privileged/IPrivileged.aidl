// Copyright 2026 The Lin Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.lin.agent.privileged;

/**
 * Lin's own code, running in a process that has the ADB shell's privileges.
 *
 * Shizuku starts this service for us under the `shell` user and hands back a Binder. Nothing
 * here is a system interface: it is this app's class, loaded from this app's APK, simply
 * running as somebody who is allowed to turn Wi-Fi off.
 *
 * Deliberately one method. A wider interface in a privileged process is a wider thing to get
 * wrong, and everything Lin needs is "run this and tell me what happened".
 */
interface IPrivileged {

    /**
     * Shizuku's own tear-down call. The transaction code is fixed by Shizuku's server, not by
     * us - a different number here means the service is never stopped and the process stays.
     */
    void destroy() = 16777114;

    /**
     * Runs one command and returns the exit code and output, encoded as one string.
     *
     * Numbered because AIDL insists: once one method has an id every method needs one, and
     * destroy's is fixed at 16777114 by Shizuku's own server rather than chosen here.
     */
    String run(in String[] argv) = 1;
}
